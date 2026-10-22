"""The shared, versioned headset capture archive contract."""
from __future__ import annotations

import hashlib
import json
import re
import shutil
import tempfile
import zipfile
from pathlib import Path, PurePosixPath

SCHEMA = "room-splat.capture/1"
MAX_ARCHIVE_BYTES = 40 * 1024**3
MAX_FILES = 30000


def digest(path: Path, cancel_check=lambda: None) -> str:
    h = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            cancel_check()
            h.update(chunk)
    return h.hexdigest()


def safe_name(name: str) -> str:
    p = PurePosixPath(name)
    if not name or "\\" in name or ":" in name or p.is_absolute() or any(
        part in ("", ".", "..") for part in name.split("/")
    ):
        raise ValueError(f"数据包包含不安全路径：{name}")
    return name


def validate_capture(root: Path, cancel_check=lambda: None) -> dict:
    manifest_path = root / "manifest.json"
    if manifest_path.stat().st_size > 32 * 1024**2:
        raise ValueError("数据清单过大。")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest.get("schema") != SCHEMA:
        raise ValueError("不支持的数据包版本。")
    frames = manifest.get("frames", [])
    if not isinstance(frames, list) or not 3 <= len(frames) < MAX_FILES:
        raise ValueError("数据包需要至少 3 张有效图像；扫描房间建议 200 张以上。")
    seen = set()
    for frame in frames:
        cancel_check()
        name = safe_name(frame["file"])
        if not re.fullmatch(r"images/[A-Za-z0-9_.-]+\.(jpg|jpeg|png)", name, re.I):
            raise ValueError(f"无效图像路径：{name}")
        if name.casefold() in seen:
            raise ValueError("数据清单存在重复图像。")
        seen.add(name.casefold())
        path = root / name
        if path.is_symlink() or not path.is_file() or path.stat().st_size == 0:
            raise ValueError(f"图像缺失或为空：{name}")
        if digest(path, cancel_check) != frame["sha256"]:
            raise ValueError(f"图像校验失败，请重新传输：{name}")
        if not all(isinstance(frame.get(k), int) and frame[k] > 0 for k in ("width", "height")):
            raise ValueError(f"图像尺寸无效：{name}")
    return manifest


def import_archive(archive: Path, destination: Path, cancel_check=lambda: None) -> dict:
    """Validate into a temporary sibling and commit only a complete capture."""
    if destination.exists():
        raise ValueError("目标目录已存在，请使用新的项目目录。")
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix=".capture-", dir=destination.parent))
    try:
        with zipfile.ZipFile(archive) as z:
            files = z.infolist()
            if len(files) > MAX_FILES or sum(i.file_size for i in files) > MAX_ARCHIVE_BYTES:
                raise ValueError("数据包超过文件数或 40GB 解压上限。")
            names = set()
            for entry in files:
                cancel_check()
                name = safe_name(entry.filename.rstrip("/"))
                if name.casefold() in names:
                    raise ValueError("压缩包存在重复路径。")
                names.add(name.casefold())
                if (entry.external_attr >> 16) & 0o170000 == 0o120000:
                    raise ValueError("数据包不可包含符号链接。")
                if entry.is_dir():
                    continue
                target = temporary / name
                target.parent.mkdir(parents=True, exist_ok=True)
                with z.open(entry) as source, target.open("wb") as output:
                    while chunk := source.read(1024 * 1024):
                        cancel_check()
                        output.write(chunk)
        manifest = validate_capture(temporary, cancel_check)
        allowed_images = {f["file"].casefold() for f in manifest["frames"]}
        for image in (temporary / "images").rglob("*"):
            if image.is_file() and image.relative_to(temporary).as_posix().casefold() not in allowed_images:
                raise ValueError("图像目录含有未列入清单的文件。")
        temporary.rename(destination)
        return manifest
    finally:
        if temporary.exists():
            shutil.rmtree(temporary)
