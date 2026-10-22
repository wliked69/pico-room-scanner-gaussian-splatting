"""Download the pinned official Brush executable, with its publisher checksum."""
import hashlib
import json
import shutil
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / "desktop/tools"
NAME = "brush-app-x86_64-pc-windows-msvc.zip"
URL = "https://github.com/ArthurBrussee/brush/releases/download/v0.3.0/" + NAME


def download(url):
    return urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "RoomSplat/0.1"}), timeout=60)


def main():
    ROOT.mkdir(parents=True, exist_ok=True)
    with download(URL + ".sha256") as response:
        expected = response.read().decode().split()[0].lower()
    archive = ROOT / NAME
    if not archive.exists() or hashlib.sha256(archive.read_bytes()).hexdigest() != expected:
        print("Downloading official Brush v0.3.0...", flush=True)
        partial = archive.with_suffix(".partial")
        with download(URL) as source, partial.open("wb") as output:
            shutil.copyfileobj(source, output, 1024 * 1024)
        actual = hashlib.sha256(partial.read_bytes()).hexdigest()
        if actual != expected:
            raise RuntimeError("Official Brush checksum mismatch.")
        partial.replace(archive)
    target = ROOT / "brush"
    target.mkdir(exist_ok=True)
    with zipfile.ZipFile(archive) as z:
        for info in z.infolist():
            if not (target / info.filename).resolve().is_relative_to(target.resolve()):
                raise RuntimeError("Unsafe archive entry.")
        z.extractall(target)
    (ROOT / "brush-version.json").write_text(json.dumps({"version": "0.3.0", "url": URL, "sha256": expected}, indent=2))
    print("Brush installed and publisher SHA256 verified.", flush=True)


if __name__ == "__main__":
    main()
