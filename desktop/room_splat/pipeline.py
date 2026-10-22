"""COLMAP CPU camera solving followed by Brush WebGPU splat training."""
from __future__ import annotations

import json
import os
import re
import shutil
import struct
import subprocess
import threading
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

from .capture import validate_capture
from .gpu import choose_gpu, parse_adapter


@dataclass
class Settings:
    colmap: str
    brush: str
    iterations: int = 7000
    max_image_size: int = 1600
    exhaustive: bool = False
    gpu: str = "auto"
    collision: bool = True
    collision_quality: str = '标准'


class Cancelled(Exception):
    pass


def registered_images(model: Path) -> int:
    binary = model / "images.bin"
    if binary.exists():
        with binary.open("rb") as f:
            data = f.read(8)
        return struct.unpack("<Q", data)[0] if len(data) == 8 else 0
    return 0


def executable(path: str, label: str) -> str:
    result = Path(path).resolve() if Path(path).is_file() else None
    resolved = str(result) if result else shutil.which(path)
    if not resolved:
        raise ValueError(f"找不到 {label}。请在设置中选择其可执行文件。")
    return resolved


class Pipeline:
    def __init__(self, log: Callable[[str], None] = print):
        self.log = log
        self.cancelled = threading.Event()
        self.process: subprocess.Popen | None = None
        self._lock = threading.Lock()
        self.gpu_env: dict[str, str] = {}

    def cancel(self):
        self.cancelled.set()
        with self._lock:
            if self.process and self.process.poll() is None:
                self.process.terminate()

    def run(self, args: list[str], cwd: Path, logfile: Path) -> None:
        if self.cancelled.is_set():
            raise Cancelled("任务已取消，采集数据保留。")
        self.log("运行：" + subprocess.list2cmdline(args))
        flags = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0
        env = self.gpu_env.copy() if self.gpu_env else os.environ.copy()
        env.update(QT_QPA_PLATFORM="offscreen", RUST_LOG="info")
        with logfile.open("a", encoding="utf-8") as output:
            with self._lock:
                self.process = subprocess.Popen(args, cwd=cwd, stdout=output, stderr=subprocess.STDOUT,
                                                creationflags=flags, env=env, shell=False)
            while self.process.poll() is None:
                if self.cancelled.wait(.25):
                    with self._lock:
                        if self.process.poll() is None:
                            self.process.terminate()
                    try:
                        self.process.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        self.process.kill()
                        self.process.wait()
                    raise Cancelled("任务已取消，采集数据保留。")
            code = self.process.returncode
            self.process = None
        if self.cancelled.is_set():
            raise Cancelled("任务已取消，采集数据保留。")
        if code:
            tail = logfile.read_text(encoding="utf-8", errors="replace")[-5000:]
            raise RuntimeError(f"处理失败（退出码 {code}）。\n{tail}")

    def reconstruct(self, capture: Path, settings: Settings) -> Path:
        capture = capture.resolve()
        manifest = validate_capture(capture)
        if not 100 <= settings.iterations <= 100000:
            raise ValueError("训练步数应在 100–100000 之间。")
        colmap = executable(settings.colmap, "COLMAP")
        brush = executable(settings.brush, "Brush")
        brush_help = subprocess.run([brush, "--help"], capture_output=True, text=True,
                                    encoding="utf-8", errors="replace", timeout=30,
                                    creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        help_text = brush_help.stdout + brush_help.stderr
        iteration_flag = next((flag for flag in ("--total-train-iters", "--total-steps") if flag in help_text), None)
        if brush_help.returncode or not iteration_flag or "--export-path" not in help_text:
            raise RuntimeError("Brush CLI 版本不兼容，缺少训练或导出参数。请使用已验证的 v0.3.0。")
        adapter, self.gpu_env = choose_gpu(brush, settings.gpu, self.log)
        if self.cancelled.is_set():
            raise Cancelled("任务已取消，采集数据保留。")
        self.log(f"训练显卡已确认：{adapter.name} · {adapter.backend}；相机求解使用 CPU。")
        work = capture.parent / ("reconstruction-" + time.strftime("%Y%m%d-%H%M%S"))
        work.mkdir(exist_ok=False)
        logfile = work / "pipeline.log"
        sparse = work / "sparse"
        sparse.mkdir()
        database = work / "database.db"
        self.log("步骤 1/4：CPU 特征提取；图像总数 " + str(len(manifest["frames"])))
        self.run([colmap, "feature_extractor", "--database_path", str(database),
                  "--image_path", str(capture / "images"), "--ImageReader.single_camera", "1",
                  "--ImageReader.camera_model", "OPENCV", "--SiftExtraction.use_gpu", "0",
                  "--SiftExtraction.max_image_size", str(settings.max_image_size)], work, logfile)
        self.log("步骤 2/4：CPU 图像匹配与相机位姿求解")
        self.run([colmap, "exhaustive_matcher" if settings.exhaustive else "sequential_matcher",
                  "--database_path", str(database), "--SiftMatching.use_gpu", "0"], work, logfile)
        self.run([colmap, "mapper", "--database_path", str(database), "--image_path", str(capture / "images"),
                  "--output_path", str(sparse)], work, logfile)
        models = sorted((p for p in sparse.iterdir() if p.is_dir()), key=registered_images, reverse=True)
        if not models or registered_images(models[0]) < 3:
            raise RuntimeError("相机求解失败。请缓慢移动、保持相邻画面重叠并补拍有纹理的物体。")
        model = models[0]
        count = registered_images(model)
        fraction = count / len(manifest["frames"])
        report = {"registered_images": count, "capture_images": len(manifest["frames"]),
                  "registered_fraction": fraction, "scale": "arbitrary_sfm_units",
                  "engine": "Brush WebGPU", "iterations": settings.iterations,
                  "gpu_selection": settings.gpu, "gpu": adapter.report(), "camera_solver": "CPU COLMAP"}
        (work / "report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
        self.log(f"最大连通模型注册 {count}/{len(manifest['frames'])} 张照片（{fraction:.1%}）；尺寸尚未标定。")
        if fraction < .6:
            raise RuntimeError("有效匹配不足 60%，房间扫描可能分裂。补拍连接区域后再重建；已保留求解结果。")
        dataset = work / "dataset"
        self.log("步骤 3/4：去畸变并准备 Gaussian Splatting 数据")
        self.run([colmap, "image_undistorter", "--image_path", str(capture / "images"),
                  "--input_path", str(model), "--output_path", str(dataset), "--output_type", "COLMAP",
                  "--max_image_size", str(settings.max_image_size)], work, logfile)
        # Brush 0.3.0 reads a 16387-byte header before decoding. Keep the
        # undistorted JPEG/PNG copies compatible even for very small images.
        # Trailing bytes do not alter pixels; original captures stay immutable.
        for image_path in (dataset / "images").rglob("*"):
            if image_path.is_file() and image_path.suffix.lower() in (".jpg", ".jpeg", ".png"):
                length = image_path.stat().st_size
                if length < 16387:
                    with image_path.open("ab") as output:
                        output.write(b"\0" * (16387 - length))
        exports = work / "models"
        exports.mkdir()
        self.log("步骤 4/4：Brush GPU 高斯训练，AMD / NVIDIA / Intel；进度见 pipeline.log")
        training_log = work / "training.log"
        self.run([brush, str(dataset), iteration_flag, str(settings.iterations),
                  "--export-every", str(settings.iterations), "--export-path", str(exports)], work, training_log)
        with training_log.open(encoding="utf-8", errors="replace") as training_output:
            actual = parse_adapter(training_output.read(65536))
        if actual is None or actual.vendor_id != adapter.vendor_id or actual.name != adapter.name:
            raise RuntimeError("训练日志中的显卡与已选择显卡不一致；请查看 training.log。")
        report["gpu"] = actual.report()
        self.log(f"训练实际使用：{actual.name} · {actual.backend}")
        outputs = list(exports.glob("*.ply"))
        if not outputs:
            raise RuntimeError("训练进程已结束，但没有生成 PLY；请查看 pipeline.log。")
        result = max(outputs, key=lambda p: p.stat().st_mtime)
        if result.stat().st_size < 100:
            raise RuntimeError("导出的模型文件为空或不完整。")
        report["result"] = str(result)
        if settings.collision:
            from .collision import export_collision
            def check_mesh():
                if self.cancelled.is_set(): raise Cancelled('碰撞网格生成已取消；已完成的高斯模型保留。')
            try:
                mesh=export_collision(result,quality=settings.collision_quality,log=self.log,check=check_mesh)
                report['collision_mesh']=str(mesh)
            except Cancelled:
                raise
            except Exception as error:
                report['collision_error']=str(error)
                self.log('高斯模型已完成，碰撞网格未完成：'+str(error)+'；可以单独重新生成。')
        (work / "report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
        self.log("模型完成：" + str(result))
        return result
