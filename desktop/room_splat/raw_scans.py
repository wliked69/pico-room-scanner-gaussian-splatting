"""Manage original capture sessions while preserving a verified PC backup."""
from __future__ import annotations
import copy
import hashlib
import json
import re
import shlex
import tempfile
import time
import uuid
import zipfile
from dataclasses import dataclass
from pathlib import Path
from .capture import SCHEMA, MAX_FILES, digest
from .headset import REMOTE_ROOT, Device

SESSION = re.compile(re.escape(REMOTE_ROOT) + r'/sessions/session-[A-Za-z0-9._-]+$')
PHOTO = re.compile(r'images/frame_[0-9]{6,}\.(?:jpg|jpeg|png)$', re.I)

@dataclass(frozen=True)
class RawSession:
    device: Device
    path: str
    size: int
    modified: int
    frames: tuple
    fingerprint: str
    exported: bool
    raw: bool = True
    @property
    def name(self):
        return self.path.rsplit('/', 1)[-1]

def parse_frames(text):
    frames = [json.loads(line) for line in text.splitlines() if line.strip()]
    if len(frames) >= MAX_FILES:
        raise ValueError('扫描照片数量超出上限。')
    names = set()
    for frame in frames:
        name = frame.get('file', '')
        if not PHOTO.fullmatch(name) or name in names or not re.fullmatch(r'[0-9a-f]{64}', frame.get('sha256', '')):
            raise ValueError('原始扫描照片清单无效。')
        names.add(name)
    return frames

def read_session(client, device, path):
    if not SESSION.fullmatch(path):
        raise ValueError('原始扫描目录不受支持。')
    q = shlex.quote(path)
    text = client.shell(device, f'cat {q}/frames.jsonl', timeout=90)
    frames = parse_frames(text)
    # Include timestamps/count of all files so external edits invalidate confirmation.
    inventory = client.shell(device, f'find {q} -type f -exec stat -c "%n|%s|%Y" {{}} \\;', timeout=90)
    fingerprint = hashlib.sha256((text + '\n' + '\n'.join(sorted(inventory.splitlines()))).encode()).hexdigest()
    lines = [line.rsplit('|', 2) for line in inventory.splitlines()]
    size = sum(int(parts[1]) for parts in lines if len(parts) == 3)
    modified = max((int(parts[2]) for parts in lines if len(parts) == 3), default=0)
    return RawSession(device, path, size, modified, tuple(frames), fingerprint,
                      any(parts[0] == path + '/exported.txt' for parts in lines))

def sessions(client, device):
    base = shlex.quote(REMOTE_ROOT + '/sessions')
    found = client.shell(device, f'if [ -d {base} ]; then find {base} -mindepth 1 -maxdepth 1 -type d; fi')
    result = []
    for path in found.splitlines():
        if SESSION.fullmatch(path):
            exists = client.shell(device, f'if [ -f {shlex.quote(path)}/frames.jsonl ]; then echo yes; fi').strip()
            if exists:
                result.append(read_session(client, device, path))
    return sorted(result, key=lambda session: session.modified, reverse=True)

def unchanged(client, session):
    if read_session(client, session.device, session.path).fingerprint != session.fingerprint:
        raise RuntimeError('扫描照片已变化，请刷新后重新操作。')

def require_closed(client, device):
    if client.shell(device, 'pidof com.example.roomsplat || true').strip():
        raise RuntimeError('请先在头显退出 RoomSplat 扫描软件，再删除原始照片。暂停仍会保留内存中的清单。')

def delete_without_backup(client, session):
    """Explicit UI confirmation required. No pull, local backup, or ZIP deletion."""
    if not SESSION.fullmatch(session.path):
        raise ValueError('原始扫描目录不受支持。')
    require_closed(client, session.device)
    unchanged(client, session)
    token = uuid.uuid4().hex
    q = shlex.quote(session.path)
    old = shlex.quote(REMOTE_ROOT + '/sessions/.removed-' + token)
    base = shlex.quote(REMOTE_ROOT + '/sessions')
    # Keep the short commit independent of window-close cancellation.
    from .headset import HeadsetClient
    import threading
    commit = HeadsetClient.__new__(HeadsetClient)
    commit.adb, commit.log, commit.cancelled = client.adb, client.log, threading.Event()
    command = '; '.join([
        'set -e', 'test -z "$(pidof com.example.roomsplat || true)"',
        f'test ! -L {q}', f'test ! -e {old}',
        f'test "$(readlink -f {q})" = "$(readlink -f {base})/{session.name}"',
        f'mv {q} {old}',
        f'test "$(readlink -f {old})" = "$(readlink -f {base})/.removed-{token}"',
        f'rm -r -- {old}',
    ])
    commit.shell(session.device, command, timeout=90)
    client.log('已直接删除头显原始扫描（未备份）：' + session.name + '；已打包 ZIP 保留。')

def download(client, session, parent):
    unchanged(client, session)
    run = Path(parent) / ('raw-' + time.strftime('%Y%m%d-%H%M%S') + '-' + uuid.uuid4().hex[:8])
    run.mkdir(parents=True)
    capture = run / 'capture'; (capture / 'images').mkdir(parents=True)
    client.log(f'传输原始扫描：{session.name}，{len(session.frames)} 张照片')
    # Pull only validated image paths and camera metadata; never trust extra device files.
    for name in ['camera.json', 'frames.jsonl'] + [frame['file'] for frame in session.frames]:
        client.execute(['-s', session.device.serial, 'pull', session.path + '/' + name, str(capture / name)], timeout=180)
    for frame in session.frames:
        if digest(capture / frame['file'], client.check_cancelled) != frame['sha256']:
            raise RuntimeError('原始照片校验失败，未修改头显数据。')
    unchanged(client, session)
    camera = json.loads((capture / 'camera.json').read_text(encoding='utf-8'))
    manifest = dict(schema=SCHEMA, session_id=session.name, device_model=session.device.model,
                    camera=camera, frames=list(session.frames), metric_scale_available=False,
                    pose_source='optional HMD; reconstruct_with_sfm')
    (capture / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')
    (run / 'source.json').write_text(json.dumps(dict(device=session.device.serial, path=session.path,
                                                   fingerprint=session.fingerprint), indent=2), encoding='utf-8')
    return capture, manifest

def preview_archive(client, session, parent):
    capture, manifest = download(client, session, parent)
    path = capture.parent / 'scan.zip'
    with zipfile.ZipFile(path, 'w', compression=zipfile.ZIP_STORED) as archive:
        for name in ['manifest.json', 'camera.json', 'frames.jsonl'] + [frame['file'] for frame in session.frames]:
            archive.write(capture / name, name)
    # Preview owns this temporary directory only.
    import shutil
    if capture.resolve().is_relative_to(Path(parent).resolve()):
        shutil.rmtree(capture)
    (path.parent / 'source.json').unlink()
    return path

def renumber(capture, manifest, removed):
    """Rebuild contiguous names so headset resume never overwrites a surviving photo."""
    frames = copy.deepcopy(manifest['frames'])
    if removed not in [frame['file'] for frame in frames]:
        raise ValueError('未找到选中的照片。')
    retained = [frame for frame in frames if frame['file'] != removed]
    output = capture.parent / 'replacement'; (output / 'images').mkdir(parents=True)
    import shutil
    for index, frame in enumerate(retained, 1):
        name = f'images/frame_{index:06d}' + Path(frame['file']).suffix
        shutil.copyfile(capture / frame['file'], output / name)
        frame['file'] = name
    shutil.copyfile(capture / 'camera.json', output / 'camera.json')
    (output / 'frames.jsonl').write_text(''.join(json.dumps(frame) + '\n' for frame in retained), encoding='utf-8')
    # Drop stale manifests/export markers/coverage; future pack regenerates them.
    return output

def delete(client, session, backup_root, photo=None):
    require_closed(client, session.device)
    unchanged(client, session)
    capture, manifest = download(client, session, backup_root)
    replacement = renumber(capture, manifest, photo) if photo else None
    token = uuid.uuid4().hex
    stage = REMOTE_ROOT + '/sessions/.edit-' + token
    old = REMOTE_ROOT + '/sessions/.removed-' + token
    q, qs, qo = map(shlex.quote, (session.path, stage, old))
    if replacement:
        client.execute(['-s', session.device.serial, 'push', str(replacement), stage], timeout=3600)
        # Verify uploaded bytes before the commit.
        for file in replacement.rglob('*'):
            if file.is_file():
                remote = stage + '/' + file.relative_to(replacement).as_posix()
                actual = client.shell(session.device, 'sha256sum ' + shlex.quote(remote), timeout=180).split()[0]
                if actual != digest(file, client.check_cancelled):
                    raise RuntimeError('上传校验失败，原始扫描保留。')
    require_closed(client, session.device); unchanged(client, session)
    # One short uncancellable commit. If swapping fails, restore the original directory.
    from .headset import HeadsetClient
    commit = HeadsetClient.__new__(HeadsetClient)
    commit.adb, commit.log = client.adb, client.log
    import threading
    commit.cancelled = threading.Event()
    commands = ['set -e', 'test -z "$(pidof com.example.roomsplat || true)"',
                f'test ! -L {q}', f'test ! -e {qo}', f'mv {q} {qo}']
    if replacement:
        commands.append(f'if ! mv {qs} {q}; then mv {qo} {q}; exit 1; fi')
    # Leave the app-owned active marker intact. A marker pointing at a deleted session
    # safely yields no recovery; writing a replacement via ADB makes it unreadable to the app.
    # Resolved delete target is a fixed, newly allocated child of the sessions root.
    sessions_root = shlex.quote(REMOTE_ROOT + '/sessions')
    commands.append(f'test "$(readlink -f {qo})" = "$(readlink -f {sessions_root})/.removed-{token}"')
    commands.append(f'rm -r -- {qo}')
    commit.shell(session.device, '; '.join(commands), timeout=90)
    client.log('已删除原始照片；电脑备份：' + str(capture) + '；已打包 ZIP 保留。')
    return capture
