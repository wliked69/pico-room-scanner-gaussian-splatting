from __future__ import annotations

import json
import os
import queue
import subprocess
import sys
import threading
import tkinter as tk
from pathlib import Path
from tkinter import filedialog, messagebox, ttk

from .capture import import_archive, validate_capture
from .pipeline import Cancelled, Pipeline, Settings, executable
from .gpu import GPU_CHOICES
from .headset import HeadsetClient, HeadsetCancelled
from .headset_ui import HeadsetManager


def app_root() -> Path:
    return Path(sys.executable).parent if getattr(sys, "frozen", False) else Path(__file__).resolve().parents[1]


class App:
    def __init__(self, window: tk.Tk):
        self.window = window
        window.title("Room Splat · 房间高斯重建")
        window.geometry("960x740")
        window.minsize(780, 600)
        self.events: queue.Queue = queue.Queue()
        self.pipeline: Pipeline | None = None
        self.capture: Path | None = None
        self.result: Path | None = None
        self.mesh: Path | None = None
        self.busy = False
        self.headset_client: HeadsetClient | None = None
        self.root = app_root()
        self.config_path = self.root / "settings.json"
        config = json.loads(self.config_path.read_text(encoding="utf-8")) if self.config_path.exists() else {}
        tools = self.root / "tools"
        colmap = next(tools.rglob("colmap-worker.exe"), next(tools.rglob("colmap.exe"), Path("colmap"))) if tools.exists() else Path("colmap")
        brush = next(tools.rglob("brush_app.exe"), next(tools.rglob("brush-app.exe"), Path("brush_app"))) if tools.exists() else Path("brush_app")
        self.colmap = tk.StringVar(value=config.get("colmap", str(colmap)))
        self.brush = tk.StringVar(value=config.get("brush", str(brush)))
        self.iterations = tk.IntVar(value=config.get("iterations", 7000))
        self.exhaustive = tk.BooleanVar(value=config.get("exhaustive", False))
        self.gpu = tk.StringVar(value=next((label for label, mode in GPU_CHOICES.items() if mode == config.get("gpu", "auto")), next(iter(GPU_CHOICES))))
        self.collision = tk.BooleanVar(value=config.get('collision',True))
        self.collision_quality = tk.StringVar(value=config.get('collision_quality','标准'))
        self.status = tk.StringVar(value="导入头显导出的 .zip 数据包，开始本地重建。")
        outer = ttk.Frame(window, padding=24)
        outer.pack(fill="both", expand=True)
        ttk.Label(outer, text="把房间带回来", font=("Microsoft YaHei UI", 24, "bold")).pack(anchor="w")
        ttk.Label(outer, text="CPU 求解相机 · NVIDIA / AMD / Intel GPU 训练 · 本地 PLY 模型", font=("Microsoft YaHei UI", 11)).pack(anchor="w", pady=(4, 16))
        self.actions = ttk.Frame(outer)
        self.actions.pack(fill="x")
        self.import_button = ttk.Button(self.actions, text="导入扫描 ZIP", command=self.import_zip)
        self.import_button.pack(side="left", padx=(0, 8))
        self.open_button = ttk.Button(self.actions, text="打开已有采集目录", command=self.open_capture)
        self.open_button.pack(side="left", padx=(0, 8))
        self.start_button = ttk.Button(self.actions, text="开始重建", command=self.start)
        self.start_button.pack(side="left", padx=(0, 8))
        ttk.Button(self.actions, text="取消重建", command=self.cancel).pack(side="left")
        self.headset_button = ttk.Button(self.actions, text="头显数据 · 导入 / 管理", command=self.pull)
        self.headset_button.pack(side="left", padx=8)
        settings = ttk.LabelFrame(outer, text="处理设置", padding=12)
        settings.pack(fill="x", pady=16)
        for row, (label, variable) in enumerate((("COLMAP 程序", self.colmap), ("Brush 程序", self.brush))):
            ttk.Label(settings, text=label).grid(row=row, column=0, sticky="w", padx=(0, 12), pady=4)
            ttk.Entry(settings, textvariable=variable).grid(row=row, column=1, sticky="ew")
            ttk.Button(settings, text="选择…", command=lambda v=variable: self.choose_tool(v)).grid(row=row, column=2, padx=(8, 0))
        settings.columnconfigure(1, weight=1)
        ttk.Label(settings, text="训练步数").grid(row=2, column=0, sticky="w", pady=8)
        ttk.Spinbox(settings, from_=100, to=100000, increment=1000, textvariable=self.iterations, width=12).grid(row=2, column=1, sticky="w")
        ttk.Checkbutton(settings, text="全量匹配（用于断开较多的采集；速度较慢）", variable=self.exhaustive).grid(row=3, column=0, columnspan=3, sticky="w")
        ttk.Label(settings, text="训练显卡").grid(row=4, column=0, sticky="w", pady=8)
        ttk.Combobox(settings, textvariable=self.gpu, values=list(GPU_CHOICES), state="readonly", width=26).grid(row=4, column=1, sticky="w")
        ttk.Checkbutton(settings,text='同时导出碰撞网格（OBJ）',variable=self.collision).grid(row=5,column=0,columnspan=2,sticky='w')
        ttk.Combobox(settings,textvariable=self.collision_quality,values=('快速','标准','精细'),state='readonly',width=9).grid(row=5,column=2,sticky='w')
        ttk.Label(outer, textvariable=self.status, wraplength=880).pack(anchor="w", pady=(0, 8))
        self.progress = ttk.Progressbar(outer, mode="indeterminate")
        self.progress.pack(fill="x")
        self.console = tk.Text(outer, height=15, wrap="word", font=("Consolas", 10), state="disabled")
        footer = ttk.Frame(outer)
        footer.pack(side="bottom", fill="x")
        ttk.Button(footer, text="查看模型", command=self.view).pack(side="left", padx=(0, 8))
        ttk.Button(footer, text="打开项目文件夹", command=self.folder).pack(side="left")
        self.mesh_button=ttk.Button(footer,text='生成碰撞网格…',command=self.generate_mesh); self.mesh_button.pack(side='left',padx=8)
        ttk.Button(footer,text='预览碰撞网格…',command=self.preview_mesh).pack(side='left')
        self.console.pack(fill="both", expand=True, pady=12)
        window.protocol("WM_DELETE_WINDOW", self.close)
        self.poll_id = window.after(100, self.poll)

    def log(self, text):
        self.events.put(("log", text))

    def launch_job(self, job):
        if self.busy:
            return
        self.busy = True
        self.progress.start()
        for button in (self.import_button, self.open_button, self.start_button, self.headset_button,self.mesh_button):
            button.configure(state="disabled")
        def work():
            try:
                job()
            except (Cancelled, HeadsetCancelled) as exc:
                self.log(str(exc))
            except Exception as exc:
                self.events.put(("error", str(exc)))
            finally:
                self.events.put(("done", None))
        threading.Thread(target=work, daemon=True).start()

    def import_zip(self):
        selected = filedialog.askopenfilename(filetypes=[("扫描数据包", "*.zip")])
        if not selected:
            return
        destination = filedialog.askdirectory(title="选择存放项目的父目录")
        if not destination:
            return
        target = Path(destination) / (Path(selected).stem + "-capture")
        def work():
            self.log("校验并导入：" + selected)
            manifest = import_archive(Path(selected), target)
            self.events.put(("capture", (target, manifest)))
        self.launch_job(work)

    def open_capture(self):
        selected = filedialog.askdirectory(title="选择包含 manifest.json 的采集目录")
        if selected:
            try:
                manifest = validate_capture(Path(selected))
                self.events.put(("capture", (Path(selected), manifest)))
            except Exception as exc:
                messagebox.showerror("采集数据无效", str(exc))

    def choose_tool(self, variable):
        selected = filedialog.askopenfilename(filetypes=[("程序", "*.exe"), ("全部文件", "*")])
        if selected:
            variable.set(selected)

    def start(self):
        if not self.capture:
            messagebox.showinfo("先导入数据", "请先导入头显扫描 ZIP。")
            return
        try:
            settings = Settings(self.colmap.get(), self.brush.get(), self.iterations.get(), exhaustive=self.exhaustive.get(), gpu=GPU_CHOICES[self.gpu.get()],collision=self.collision.get(),collision_quality=self.collision_quality.get())
        except tk.TclError:
            messagebox.showerror("设置无效", "请输入整数训练步数。")
            return
        self.config_path.write_text(json.dumps(settings.__dict__, indent=2), encoding="utf-8")
        self.result = None
        self.pipeline = Pipeline(self.log)
        capture = self.capture
        pipeline = self.pipeline
        self.status.set("正在重建；相机日志见 pipeline.log，训练显卡与进度见 training.log。")
        self.launch_job(lambda: self.events.put(("result", pipeline.reconstruct(capture, settings))))

    def cancel(self):
        if self.pipeline:
            self.pipeline.cancel()
        if self.headset_client:
            self.headset_client.cancelled.set()

    def pull(self):
        if self.busy:
            return
        def work():
            client = HeadsetClient(self.root, self.log)
            self.headset_client = client
            self.events.put(("headset", (client.devices(), client)))
        self.status.set("正在识别 USB 头显…")
        self.launch_job(work)

    def import_headset(self, archive, client):
        destination = filedialog.askdirectory(title="选择扫描项目的保存位置")
        if not destination:
            return False
        self.headset_client = client
        self.status.set("正在从头显传输、校验并导入扫描…")
        self.launch_job(lambda: self.events.put(("capture", client.import_selected(archive, Path(destination)))))
        return True

    def poll(self):
        while True:
            try:
                kind, value = self.events.get_nowait()
            except queue.Empty:
                break
            if kind == "log":
                self.console.configure(state="normal")
                self.console.insert("end", value + "\n")
                self.console.see("end")
                self.console.configure(state="disabled")
            elif kind == "capture":
                self.capture, manifest = value
                self.result = None
                self.mesh = None
                self.status.set(f"已导入 {len(manifest['frames'])} 张图像 · {self.capture}")
            elif kind == "headset":
                devices, client = value
                HeadsetManager(self.window, devices, client, self.import_headset)
            elif kind == "result":
                self.result = value
                self.status.set("重建完成：" + str(value))
                report_path=value.parents[1]/'report.json'
                if report_path.exists():
                    report=json.loads(report_path.read_text(encoding='utf-8'))
                    if report.get('collision_mesh'):
                        self.mesh=Path(report['collision_mesh']); self.show_mesh(self.mesh)
                    elif report.get('collision_error'):
                        self.status.set('高斯模型完成；碰撞网格未完成，可单独重新生成。')
            elif kind == 'mesh':
                self.mesh=value; self.status.set('碰撞网格完成：'+str(value)); self.show_mesh(value)
            elif kind == "error":
                self.status.set("处理未完成。采集数据保留，可修正后重试。")
                self.log(value)
                messagebox.showerror("处理未完成", value)
            elif kind == "done":
                self.busy = False
                self.progress.stop()
                for button in (self.import_button, self.open_button, self.start_button, self.headset_button,self.mesh_button):
                    button.configure(state="normal")
        self.poll_id = self.window.after(100, self.poll)

    def view(self):
        selected = str(self.result) if self.result else filedialog.askopenfilename(filetypes=[("高斯模型", "*.ply")])
        if selected:
            try:
                subprocess.Popen([executable(self.brush.get(), "Brush"), selected, "--with-viewer"], shell=False)
            except Exception as exc:
                messagebox.showerror("无法打开模型", str(exc))

    def generate_mesh(self):
        if self.busy: return
        selected=str(self.result) if self.result else filedialog.askopenfilename(title='选择已有高斯 PLY 模型',filetypes=[('高斯模型','*.ply')])
        if not selected: return
        from .collision import export_collision
        self.pipeline=Pipeline(self.log)
        pipeline=self.pipeline; quality=self.collision_quality.get()
        def check():
            if pipeline.cancelled.is_set(): raise Cancelled('碰撞网格生成已取消，原模型保留。')
        self.status.set('正在生成碰撞网格 · CPU 表面重建…')
        self.launch_job(lambda:self.events.put(('mesh',export_collision(Path(selected),quality=quality,log=self.log,check=check))))

    def show_mesh(self,path):
        try:
            from .mesh_preview import MeshPreview
            MeshPreview(self.window,path)
        except Exception as error:
            messagebox.showerror('无法预览网格',str(error))

    def preview_mesh(self):
        selected=str(self.mesh) if self.mesh and self.mesh.is_file() else filedialog.askopenfilename(title='选择碰撞 OBJ 网格',filetypes=[('碰撞网格','*.obj')])
        if selected: self.show_mesh(Path(selected))

    def folder(self):
        path = self.result.parent if self.result else self.capture or self.root
        if os.name == "nt":
            os.startfile(path)

    def close(self):
        if self.busy and not messagebox.askyesno("取消并关闭？", "当前任务会停止，原始采集数据保留。"):
            return
        self.cancel()
        self.window.after_cancel(self.poll_id)
        self.window.destroy()


def main():
    window = tk.Tk()
    App(window)
    window.mainloop()
