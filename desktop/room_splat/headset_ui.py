from __future__ import annotations

import hashlib
import io
import json
import queue
import tempfile
import threading
import zipfile
from datetime import datetime
from pathlib import Path
import tkinter as tk
from tkinter import messagebox, ttk, filedialog

from .capture import MAX_ARCHIVE_BYTES, MAX_FILES, SCHEMA, safe_name
from .headset import Archive, HeadsetCancelled


def clear_preview(path: Path, cache: Path):
    if not path.resolve().is_relative_to(cache.resolve()):
        return
    for candidate in (path, path.parent, cache):
        try:
            candidate.unlink() if candidate.is_file() else candidate.rmdir()
        except OSError:
            pass


class PhotoPreview:
    def __init__(self, parent, archive: Archive, path: Path, cache: Path, on_delete=None):
        from PIL import Image, ImageTk
        self.Image, self.ImageTk = Image, ImageTk
        self.path, self.cache = path, cache
        self.zip = zipfile.ZipFile(path)
        try:
            entries = self.zip.infolist()
            if len(entries) > MAX_FILES or sum(entry.file_size for entry in entries) > MAX_ARCHIVE_BYTES:
                raise ValueError("数据包超过查看上限。")
            if self.zip.getinfo("manifest.json").file_size > 32 * 1024**2:
                raise ValueError("数据清单过大。")
            manifest = json.loads(self.zip.read("manifest.json"))
            self.frames = manifest.get("frames", [])
            if manifest.get("schema") != SCHEMA or not isinstance(self.frames, list) or not 1 <= len(self.frames) < MAX_FILES:
                raise ValueError("不是受支持的扫描数据包。")
            for frame in self.frames:
                name = safe_name(frame["file"])
                if not name.startswith("images/") or Path(name).suffix.lower() not in (".jpg", ".jpeg", ".png"):
                    raise ValueError("数据包包含无效照片路径。")
        except Exception:
            self.zip.close(); clear_preview(path, cache); raise
        self.window = tk.Toplevel(parent)
        self.window.title("扫描照片 · " + archive.name)
        self.window.geometry("900x700"); self.window.minsize(760, 600)
        self.window.transient(parent)
        self.closed = False; self.request = 0; self.index = 0; self.events = queue.Queue()
        outer = ttk.Frame(self.window, padding=16); outer.pack(fill="both", expand=True)
        ttk.Label(outer, text=f"{archive.device.model} · {len(self.frames)} 张照片 · {archive.size / 1024**2:.1f} MB", wraplength=700).pack(anchor="w")
        self.caption = tk.StringVar(value="正在读取照片…")
        ttk.Label(outer, textvariable=self.caption, wraplength=700).pack(anchor="w", pady=8)
        nav = ttk.Frame(outer); nav.pack(side="bottom", fill="x")
        self.previous = ttk.Button(nav, text="上一张", command=lambda: self.load(self.index - 1)); self.previous.pack(side="left")
        self.next = ttk.Button(nav, text="下一张", command=lambda: self.load(self.index + 1)); self.next.pack(side="left", padx=8)
        if on_delete:
            ttk.Button(nav, text="删除这张原始照片", command=lambda: on_delete(self.frames[self.index]['file'], self)).pack(side="left", padx=8)
        ttk.Button(nav, text="关闭", command=self.close).pack(side="right")
        self.image = ttk.Label(outer, anchor="center"); self.image.pack(fill="both", expand=True)
        self.window.protocol("WM_DELETE_WINDOW", self.close)
        self.window.grab_set(); self.after_id = self.window.after(60, self.poll); self.load(0)

    def load(self, index):
        if not 0 <= index < len(self.frames):
            return
        self.index = index; self.request += 1; request = self.request
        self.caption.set(f"正在读取第 {index + 1}/{len(self.frames)} 张照片…")
        def work():
            try:
                frame = self.frames[index]; name = frame["file"]
                if self.zip.getinfo(name).file_size > 64 * 1024**2:
                    raise ValueError("照片文件过大。")
                data = self.zip.read(name)
                if hashlib.sha256(data).hexdigest() != frame.get("sha256"):
                    raise ValueError("照片校验失败，建议重新传输数据包。")
                with self.Image.open(io.BytesIO(data)) as source:
                    size = source.size; image = source.convert("RGB"); image.thumbnail((640, 480))
                self.events.put((request, "photo", (image, name, size)))
            except Exception as error:
                self.events.put((request, "error", str(error)))
        threading.Thread(target=work, daemon=True).start()

    def poll(self):
        if self.closed:
            return
        while not self.events.empty():
            request, kind, value = self.events.get()
            if request != self.request:
                continue
            if kind == "photo":
                image, name, size = value
                self.photo = self.ImageTk.PhotoImage(image, master=self.window)
                self.image.configure(image=self.photo)
                self.caption.set(f"{self.index + 1}/{len(self.frames)} · {name} · {size[0]}×{size[1]}")
            else:
                self.caption.set(value)
        self.previous.configure(state="normal" if self.index > 0 else "disabled")
        self.next.configure(state="normal" if self.index + 1 < len(self.frames) else "disabled")
        self.after_id = self.window.after(60, self.poll)

    def close(self):
        self.closed = True; self.window.after_cancel(self.after_id); self.zip.close(); clear_preview(self.path, self.cache)
        parent = self.window.master
        self.window.destroy()
        if parent.winfo_exists():
            parent.grab_set()


class HeadsetManager:
    def __init__(self, parent, devices, client, on_import):
        self.client = client; self.devices = devices; self.on_import = on_import
        self.closed = False; self.busy = False; self.request = 0; self.events = queue.Queue(); self.files = {}; self.preview_window = None
        self.window = tk.Toplevel(parent)
        self.window.title("头显扫描数据 · 查看、导入、删除")
        self.window.geometry("800x460"); self.window.minsize(700, 380); self.window.transient(parent)
        outer = ttk.Frame(self.window, padding=16); outer.pack(fill="both", expand=True)
        top = ttk.Frame(outer); top.pack(fill="x")
        ttk.Label(top, text="设备").pack(side="left")
        self.device_label = tk.StringVar(value=devices[0].label)
        self.combo = ttk.Combobox(top, values=[device.label for device in devices], textvariable=self.device_label, state="readonly", width=52)
        self.combo.pack(side="left", padx=8); self.combo.bind("<<ComboboxSelected>>", lambda _: self.refresh())
        self.refresh_button = ttk.Button(top, text="刷新", command=self.refresh); self.refresh_button.pack(side="right")
        self.mode = tk.StringVar(value='已打包 ZIP')
        self.mode_combo = ttk.Combobox(outer, values=('已打包 ZIP', '原始照片（含未打包扫描）'), textvariable=self.mode, state='readonly', width=36)
        self.mode_combo.pack(anchor='w', pady=(8, 0)); self.mode_combo.bind('<<ComboboxSelected>>', lambda _: self.refresh())
        self.status = tk.StringVar(value="正在识别已打包的扫描数据…")
        ttk.Label(outer, textvariable=self.status, wraplength=660).pack(anchor="w", pady=12)
        self.progress = ttk.Progressbar(outer, mode="indeterminate"); self.progress.pack(fill="x")
        bottom = ttk.Frame(outer); bottom.pack(side="bottom", fill="x", pady=(12, 0))
        self.preview_button = ttk.Button(bottom, text="查看照片 / 详情", command=self.preview); self.preview_button.pack(side="left")
        self.import_button = ttk.Button(bottom, text="传输并导入", command=self.import_selected); self.import_button.pack(side="left", padx=8)
        self.delete_button = ttk.Button(bottom, text="删除头显数据包", command=self.delete); self.delete_button.pack(side="left")
        self.direct_delete_button = ttk.Button(bottom, text="直接删除（不备份）", command=self.delete_direct)
        ttk.Button(bottom, text="关闭", command=self.close).pack(side="right")
        self.tree = ttk.Treeview(outer, columns=("time", "size"), selectmode="browse")
        self.tree.heading("#0", text="扫描数据包"); self.tree.heading("time", text="打包时间"); self.tree.heading("size", text="大小")
        self.tree.column("#0", width=390); self.tree.column("time", width=160); self.tree.column("size", width=95)
        scrollbar = ttk.Scrollbar(outer, orient="vertical", command=self.tree.yview)
        self.tree.configure(yscrollcommand=scrollbar.set); scrollbar.pack(side="right", fill="y", pady=(12, 0))
        self.tree.pack(fill="both", expand=True, pady=(12, 0)); self.tree.bind("<<TreeviewSelect>>", lambda _: self.enable())
        self.window.protocol("WM_DELETE_WINDOW", self.close); self.window.grab_set()
        self.after_id = self.window.after(80, self.poll); self.refresh()

    def selected(self):
        rows = self.tree.selection()
        return self.files.get(rows[0]) if rows else None

    def enable(self):
        self.combo.configure(state="disabled" if self.busy else "readonly")
        self.mode_combo.configure(state="disabled" if self.busy else "readonly")
        self.refresh_button.configure(state="disabled" if self.busy else "normal")
        for button in (self.preview_button, self.import_button, self.delete_button):
            button.configure(state="normal" if not self.busy and self.selected() else "disabled")
        self.direct_delete_button.configure(state='normal' if not self.busy and getattr(self.selected(), 'raw', False) else 'disabled')

    def run(self, kind, job):
        if self.busy:
            return
        self.busy = True; self.request += 1; request = self.request; self.enable(); self.progress.start()
        def work():
            try:
                self.events.put((request, kind, job()))
            except HeadsetCancelled:
                pass
            except Exception as error:
                self.events.put((request, "error", str(error)))
        threading.Thread(target=work, daemon=True).start()

    def refresh(self):
        if self.busy:
            return
        device = next(device for device in self.devices if device.label == self.device_label.get())
        self.tree.delete(*self.tree.get_children()); self.files = {}
        raw = self.mode.get() != '已打包 ZIP'
        if raw:
            self.direct_delete_button.pack(side='left', padx=8)
        else:
            self.direct_delete_button.pack_forget()
        self.tree.heading('#0', text='原始扫描 · 照片数 · 状态' if raw else '扫描数据包')
        self.delete_button.configure(text='删除整次原始扫描' if raw else '删除头显数据包')
        self.status.set("正在读取原始扫描照片…" if raw else "正在读取已打包扫描数据…")
        if raw:
            from .raw_scans import sessions
            self.run('files', lambda: sessions(self.client, device))
        else:
            self.run("files", lambda: self.client.archives(device))

    def preview(self):
        archive = self.selected()
        if archive is None or self.busy:
            return
        self.status.set("正在传输并校验照片预览…")
        def job():
            cache = Path(tempfile.mkdtemp(prefix="roomsplat-preview-"))
            path = self.client.download_checked(archive, cache)
            return archive, path, cache
        self.run("preview", job)

    def import_selected(self):
        archive = self.selected()
        if archive is not None and not self.busy and self.on_import(archive, self.client):
            self.closed = True; self.window.after_cancel(self.after_id); self.window.destroy()

    def delete(self):
        archive = self.selected()
        if archive is None or self.busy:
            return
        if getattr(archive, 'raw', False):
            self.delete_raw(archive)
            return
        if not messagebox.askyesno("确认删除头显数据包", f"设备：{archive.device.label}\n数据包：{archive.name}\n\n只删除这个已打包 ZIP；不删除头显原始扫描照片，也不删除电脑已导入项目。此操作不可撤销。\n\n确认删除？", parent=self.window):
            return
        self.status.set("正在删除选中的头显数据包…")
        self.run("deleted", lambda: self.client.delete_selected(archive))

    def delete_raw(self, session, photo=None, preview=None):
        target = photo or f'整次扫描（{len(session.frames)} 张照片）'
        if not messagebox.askyesno('确认删除原始照片', f'设备：{session.device.label}\n扫描：{session.name}\n删除：{target}\n\n请先暂停采集。电脑将关闭头显 RoomSplat 扫描软件，把此次扫描的全部照片备份到电脑，再删除原始数据。已打包 ZIP 保留。\n\n关闭扫描软件并继续？', parent=preview.window if preview else self.window):
            return
        backup = filedialog.askdirectory(title='选择删除前备份的电脑文件夹', parent=preview.window if preview else self.window)
        if not backup:
            return
        if preview:
            preview.close()
        from .raw_scans import delete
        self.status.set('正在校验并备份原始扫描，完成后删除…')
        def job():
            self.client.shell(session.device, 'am force-stop com.example.roomsplat')
            return delete(self.client, session, Path(backup), photo)
        self.run('deleted', job)

    def delete_direct(self):
        session = self.selected()
        if self.busy or not getattr(session, 'raw', False):
            return
        if not messagebox.askyesno('直接删除原始扫描 · 不备份',
            f'设备：{session.device.label}\n扫描：{session.name}\n照片：{len(session.frames)} 张\n\n'
            '将直接删除此次扫描的全部原始照片和网格数据，不传输到电脑、不创建备份，删除后无法恢复。'
            '已打包 ZIP 和电脑已有项目保留。\n\n请先暂停采集；确认后将关闭头显扫描软件。\n\n确认不备份直接删除？', parent=self.window):
            return
        from .raw_scans import delete_without_backup
        def job():
            self.client.shell(session.device, 'am force-stop com.example.roomsplat')
            return delete_without_backup(self.client, session)
        self.status.set('正在直接删除选中的原始扫描（不备份）…')
        self.run('deleted', job)

    def poll(self):
        if self.closed:
            return
        while not self.events.empty():
            request, kind, value = self.events.get()
            if request != self.request:
                continue
            self.busy = False; self.progress.stop()
            if kind == "files":
                self.tree.delete(*self.tree.get_children()); self.files = {}
                for index, archive in enumerate(value):
                    row = str(index); self.files[row] = archive
                    name = archive.name
                    if getattr(archive, 'raw', False):
                        name += f" · {len(archive.frames)} 张 · " + ('已打包' if archive.exported else '未打包')
                    self.tree.insert("", "end", iid=row, text=name,
                                     values=(datetime.fromtimestamp(archive.modified).strftime("%Y-%m-%d %H:%M"), f"{archive.size / 1024**2:.1f} MB"))
                if value:
                    self.tree.selection_set("0")
                self.status.set(f"发现 {len(value)} 项扫描数据。" if value else "此分类没有扫描数据。")
            elif kind == "preview":
                archive, path, cache = value
                try:
                    on_delete = (lambda name, preview: self.delete_raw(archive, name, preview)) if getattr(archive, 'raw', False) else None
                    self.preview_window = PhotoPreview(self.window, archive, path, cache, on_delete)
                    self.status.set("照片查看完成后，可选择导入或删除数据包。")
                except Exception as error:
                    clear_preview(path, cache); self.status.set(str(error))
            elif kind == "deleted":
                self.status.set("数据包已删除，正在刷新列表…"); self.refresh()
            else:
                self.status.set(value)
            self.enable()
        self.after_id = self.window.after(80, self.poll)

    def close(self):
        self.closed = True; self.window.after_cancel(self.after_id); self.client.cancelled.set()
        if self.preview_window and not self.preview_window.closed:
            self.preview_window.close()
        self.window.destroy()
