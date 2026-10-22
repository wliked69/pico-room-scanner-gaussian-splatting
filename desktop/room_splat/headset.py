"""Read-only USB discovery and verified direct import of RoomSplat scan packages."""
from __future__ import annotations

import json
import os
import re
import shlex
import shutil
import subprocess
import threading
import time
import uuid
from dataclasses import dataclass
from pathlib import Path, PurePosixPath

from .capture import digest, import_archive

REMOTE_ROOT = "/sdcard/Android/data/com.example.roomsplat/files/RoomSplat"
ARCHIVE_PATH = re.compile(re.escape(REMOTE_ROOT) + r"/exports(?:-app(?:-[0-9a-f]{8})?)?/[^/\r\n\x00]+\.zip$", re.I)


class HeadsetCancelled(Exception):
    pass


@dataclass(frozen=True)
class Device:
    serial: str
    state: str
    model: str

    @property
    def label(self):
        return f"{self.model} · {self.serial}"


@dataclass(frozen=True)
class Archive:
    device: Device
    path: str
    size: int
    modified: int

    @property
    def name(self):
        return PurePosixPath(self.path).name


def parse_devices(output: str) -> list[Device]:
    devices = []
    for line in output.splitlines():
        fields = line.split()
        if len(fields) < 2 or fields[1] not in ("device", "unauthorized", "offline"):
            continue
        model = next((field[6:].replace("_", " ") for field in fields[2:] if field.startswith("model:")), "Android/PICO")
        devices.append(Device(fields[0], fields[1], model))
    return devices


def locate_adb(app_root: Path) -> str:
    candidates = [app_root / "tools/adb/adb.exe"]
    if os.environ.get("ANDROID_HOME"):
        candidates.append(Path(os.environ["ANDROID_HOME"]) / "platform-tools/adb.exe")
    if os.environ.get("LOCALAPPDATA"):
        candidates.append(Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk/platform-tools/adb.exe")
    for candidate in candidates:
        if candidate.is_file():
            return str(candidate.resolve())
    found = shutil.which("adb")
    if found:
        return found
    raise RuntimeError("缺少 USB 连接工具，请使用完整的 RoomSplat 便携包。")


class HeadsetClient:
    def __init__(self, app_root: Path, log=print, cancelled: threading.Event | None = None):
        self.adb = locate_adb(app_root)
        self.log = log
        self.cancelled = cancelled or threading.Event()

    def check_cancelled(self):
        if self.cancelled.is_set():
            raise HeadsetCancelled("头显导入已取消，头显文件和已有项目保留。")

    def execute(self, args: list[str], timeout=30) -> str:
        self.check_cancelled()
        flags = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0
        process = subprocess.Popen([self.adb, *args], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                   creationflags=flags, shell=False)
        deadline = time.monotonic() + timeout
        try:
            while True:
                self.check_cancelled()
                if time.monotonic() > deadline:
                    raise RuntimeError("头显连接/传输超时，请检查 USB 连接并唤醒头显。")
                try:
                    output, _ = process.communicate(timeout=.2)
                    break
                except subprocess.TimeoutExpired:
                    continue
            text = output.decode("utf-8", errors="replace")
            if process.returncode:
                raise RuntimeError("头显操作失败，请检查 USB 连接与调试授权。\n" + text[-2000:])
            return text
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.communicate(timeout=3)
                except subprocess.TimeoutExpired:
                    process.kill(); process.communicate()

    def devices(self) -> list[Device]:
        all_devices = parse_devices(self.execute(["devices", "-l"]))
        online = [device for device in all_devices if device.state == "device"]
        if not online:
            if any(device.state == "unauthorized" for device in all_devices):
                raise RuntimeError("头显尚未授权这台电脑。请戴上头显，在“允许 USB 调试”提示中点允许，然后重新识别。")
            raise RuntimeError("没有识别到可用头显。请接好 USB、唤醒头显并开启 USB 调试，然后重新识别。")
        return online

    def shell(self, device: Device, command: str, timeout=30) -> str:
        return self.execute(["-s", device.serial, "shell", command], timeout)

    def metadata(self, device: Device, path: str) -> tuple[int, int]:
        if not ARCHIVE_PATH.fullmatch(path):
            raise ValueError("扫描数据包路径不受支持。")
        text = self.shell(device, "stat -c '%s|%Y' " + shlex.quote(path)).strip()
        match = re.fullmatch(r"(\d+)\|(\d+)", text)
        if not match:
            raise RuntimeError("无法读取扫描数据包信息。")
        return int(match[1]), int(match[2])

    def archives(self, device: Device) -> list[Archive]:
        base = shlex.quote(REMOTE_ROOT)
        found = self.shell(device, f"if [ -d {base} ]; then find {base} -maxdepth 2 -type f -name '*.zip' -print; fi")
        result = []
        for path in sorted(set(line.strip() for line in found.splitlines())):
            self.check_cancelled()
            if ARCHIVE_PATH.fullmatch(path):
                size, modified = self.metadata(device, path)
                if size:
                    result.append(Archive(device, path, size, modified))
        return sorted(result, key=lambda archive: (archive.modified, archive.path), reverse=True)

    def remote_hash(self, archive: Archive) -> str:
        text = self.shell(archive.device, "sha256sum " + shlex.quote(archive.path), timeout=180)
        match = re.match(r"([0-9a-fA-F]{64})\s", text)
        if not match:
            raise RuntimeError("头显未返回有效的数据包校验值。")
        return match[1].lower()

    def download_checked(self, archive: Archive, parent: Path) -> Path:
        if getattr(archive, 'raw', False):
            from .raw_scans import preview_archive
            return preview_archive(self, archive, parent)
        self.check_cancelled()
        if self.metadata(archive.device, archive.path) != (archive.size, archive.modified):
            raise RuntimeError("头显数据包已变化，请重新识别后再导入。")
        expected = self.remote_hash(archive)
        serial = re.sub(r"[^A-Za-z0-9_-]", "_", archive.device.serial)[:40]
        run = parent / (f"headset-{serial}-" + time.strftime("%Y%m%d-%H%M%S") + "-" + uuid.uuid4().hex[:8])
        run.mkdir(parents=True, exist_ok=False)
        partial = run / "scan.zip.partial"
        local = run / "scan.zip"
        try:
            self.log(f"从 {archive.device.model} 传输：{archive.name}（{archive.size / 1024**2:.1f} MB）")
            self.log(self.execute(["-s", archive.device.serial, "pull", archive.path, str(partial)], timeout=3600).strip())
            self.check_cancelled()
            if partial.stat().st_size != archive.size or digest(partial, self.check_cancelled) != expected:
                raise RuntimeError("传输文件大小或 SHA256 不一致，请重试。")
            if self.remote_hash(archive) != expected:
                raise RuntimeError("传输时头显数据包发生变化，请重新识别。")
            partial.rename(local)
            return local
        finally:
            if partial.exists():
                partial.unlink()

    def import_selected(self, archive: Archive, parent: Path) -> tuple[Path, dict]:
        if getattr(archive, 'raw', False):
            from .raw_scans import download
            if len(archive.frames) < 3:
                raise ValueError('至少需要 3 张照片才能导入建模。')
            return download(self, archive, parent)
        local = self.download_checked(archive, parent)
        self.log("传输校验通过，正在导入照片并逐张校验…")
        destination = local.parent / "capture"
        manifest = import_archive(local, destination, self.check_cancelled)
        (local.parent / "headset-import.json").write_text(json.dumps({
            "device": archive.device.serial, "model": archive.device.model, "remote_path": archive.path,
            "zip_sha256": digest(local, self.check_cancelled), "bytes": archive.size,
            "frames": len(manifest["frames"]), "remote_files_modified": False
        }, ensure_ascii=False, indent=2), encoding="utf-8")
        self.log(f"已直接导入 {len(manifest['frames'])} 张图像；可点击开始重建。")
        return destination, manifest

    def delete_selected(self, archive: Archive):
        """Caller must obtain explicit UI confirmation; delete only this completed ZIP."""
        if self.metadata(archive.device, archive.path) != (archive.size, archive.modified):
            raise RuntimeError("数据包已变化，请刷新列表后重新确认删除。")
        self.shell(archive.device, "rm -- " + shlex.quote(archive.path))
        self.log("已从头显删除打包数据：" + archive.name + "；原始扫描照片和电脑项目未删除。")
