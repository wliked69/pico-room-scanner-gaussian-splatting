"""Fetch pinned official Windows binaries into this workspace only."""
import hashlib
import json
import shutil
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / "desktop" / "tools"
RELEASES = (("ArthurBrussee/brush", "v0.3.0", "brush-app-x86_64-pc-windows-msvc.zip", "brush"),
            ("colmap/colmap", "3.11.1", "COLMAP-3.11.1-windows-no-cuda.zip", "colmap"))


def request(url):
    req = urllib.request.Request(url, headers={"User-Agent": "RoomSplat/0.1", "Accept": "application/vnd.github+json"})
    return urllib.request.urlopen(req, timeout=90)


def main():
    ROOT.mkdir(parents=True, exist_ok=True)
    records = []
    for repo, tag, asset_name, folder in RELEASES:
        with request(f"https://api.github.com/repos/{repo}/releases/tags/{tag}") as response:
            release = json.load(response)
        asset = next((a for a in release["assets"] if a["name"] == asset_name), None)
        if asset is None:
            raise RuntimeError(f"官方发行版缺少 {asset_name}；实际文件：{[a['name'] for a in release['assets']]}")
        archive = ROOT / asset_name
        if not archive.exists() or archive.stat().st_size != asset["size"]:
            print(f"下载 {asset_name} ({asset['size'] / 1024**2:.1f} MB)", flush=True)
            partial = archive.with_suffix(".partial")
            with request(asset["browser_download_url"]) as source, partial.open("wb") as output:
                shutil.copyfileobj(source, output, 1024 * 1024)
            if partial.stat().st_size != asset["size"]:
                raise RuntimeError("下载大小不完整。")
            partial.replace(archive)
        sha = hashlib.sha256(archive.read_bytes()).hexdigest()
        official_digest = asset.get("digest")
        if official_digest and official_digest != "sha256:" + sha:
            raise RuntimeError("官方 SHA256 校验失败。")
        target = ROOT / folder
        target.mkdir(exist_ok=True)
        with zipfile.ZipFile(archive) as z:
            for info in z.infolist():
                resolved = (target / info.filename).resolve()
                if not resolved.is_relative_to(target.resolve()):
                    raise RuntimeError("发行包包含无效路径。")
            z.extractall(target)
        record = {"repository": repo, "tag": tag, "url": asset["browser_download_url"],
                  "sha256": sha, "publisher_digest_available": bool(official_digest)}
        records.append(record)
        (ROOT / "engines.json").write_text(json.dumps(records, indent=2), encoding="utf-8")
        print(f"已安装 {folder}，SHA256 {sha}", flush=True)


if __name__ == "__main__":
    main()
