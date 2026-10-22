import hashlib
import io
import json
import shlex
import tempfile
import threading
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

from room_splat.capture import SCHEMA, import_archive
from room_splat.headset import ARCHIVE_PATH, REMOTE_ROOT, Archive, Device, HeadsetCancelled, HeadsetClient, parse_devices


class HeadsetImportTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.device = Device("PICO123", "device", "PICO B3110")
        self.remote = REMOTE_ROOT + "/exports-app/room.zip"
        content = io.BytesIO(); frames = []
        with zipfile.ZipFile(content, "w") as archive:
            for index in range(3):
                name = f"images/frame_{index}.jpg"; data = b"test-image" + bytes([index])
                archive.writestr(name, data)
                frames.append({"file": name, "width": 640, "height": 480, "sha256": hashlib.sha256(data).hexdigest()})
            archive.writestr("manifest.json", json.dumps({"schema": SCHEMA, "frames": frames}))
        self.data = content.getvalue()
        self.archive = Archive(self.device, self.remote, len(self.data), 123)
        with patch("room_splat.headset.locate_adb", return_value="adb"):
            self.client = HeadsetClient(self.root, lambda _: None)

    def transfer_client(self, corrupt=False):
        self.client.metadata = lambda device, path: (len(self.data), 123)
        self.client.remote_hash = lambda archive: hashlib.sha256(self.data).hexdigest()
        calls = []
        def execute(args, timeout=30):
            calls.append(args)
            data = bytearray(self.data)
            if corrupt:
                data[-1] ^= 1
            Path(args[-1]).write_bytes(data)
            return "1 file pulled"
        self.client.execute = execute
        return calls

    def test_device_inventory_distinguishes_unauthorized_and_scopes_serial(self):
        devices = parse_devices("List of devices attached\nPICO123 device model:PICO_B3110 transport_id:1\nOTHER unauthorized\n")
        self.assertEqual("PICO B3110", devices[0].model)
        self.assertEqual("unauthorized", devices[1].state)

    def test_export_aliases_supported_but_other_apps_and_partial_files_rejected(self):
        for directory in ("exports", "exports-app", "exports-app-deadbeef"):
            self.assertTrue(ARCHIVE_PATH.fullmatch(REMOTE_ROOT + f"/{directory}/room.zip"))
        self.assertFalse(ARCHIVE_PATH.fullmatch(REMOTE_ROOT + "/exports-app/room.zip.partial"))
        self.assertFalse(ARCHIVE_PATH.fullmatch("/sdcard/Download/room.zip"))
        self.assertFalse(ARCHIVE_PATH.fullmatch(REMOTE_ROOT + "/exports-app/../images/photo.zip"))

    def test_unauthorized_headset_has_actionable_message(self):
        self.client.execute = lambda args, timeout=30: "PICO123 unauthorized\n"
        with self.assertRaisesRegex(RuntimeError, "允许 USB 调试"):
            self.client.devices()

    def test_direct_import_selects_correct_serial_and_commits_valid_capture(self):
        old = self.root / "old-project"; old.mkdir(); (old / "keep").write_text("preserve")
        calls = self.transfer_client()
        destination, manifest = self.client.import_selected(self.archive, self.root)
        self.assertEqual(3, len(manifest["frames"]))
        self.assertTrue((destination / "manifest.json").exists())
        self.assertEqual(["-s", "PICO123", "pull", self.remote], calls[0][:4])
        self.assertTrue((destination.parent / "scan.zip").is_file())
        self.assertEqual("preserve", (old / "keep").read_text())

    def test_corrupted_transfer_never_commits_a_capture(self):
        self.transfer_client(corrupt=True)
        with self.assertRaisesRegex(RuntimeError, "SHA256"):
            self.client.import_selected(self.archive, self.root)
        self.assertFalse(list(self.root.rglob("capture")))
        self.assertFalse(list(self.root.rglob("*.partial")))

    def test_changed_package_is_not_imported_or_deleted(self):
        self.client.metadata = lambda device, path: (len(self.data) + 1, 124)
        self.client.shell = lambda *args, **kwargs: self.fail("No delete command allowed")
        with self.assertRaisesRegex(RuntimeError, "已变化"):
            self.client.import_selected(self.archive, self.root)
        with self.assertRaisesRegex(RuntimeError, "已变化"):
            self.client.delete_selected(self.archive)

    def test_delete_is_one_quoted_completed_zip_not_a_directory_or_photos(self):
        calls = []
        self.client.metadata = lambda device, path: (len(self.data), 123)
        self.client.shell = lambda device, command: calls.append((device.serial, command))
        self.client.delete_selected(self.archive)
        self.assertEqual([("PICO123", "rm -- " + shlex.quote(self.remote))], calls)
        self.assertNotIn("-r", calls[0][1])

    def test_unsupported_delete_path_cannot_issue_remote_command(self):
        self.client.execute = lambda *args, **kwargs: self.fail("No device command allowed")
        with self.assertRaises(ValueError):
            self.client.delete_selected(Archive(self.device, "/sdcard/other.zip", 100, 123))

    def test_cancelled_transfer_does_not_touch_device_or_create_project(self):
        self.client.cancelled.set()
        self.client.metadata = lambda *args: self.fail("No device command allowed")
        with self.assertRaises(HeadsetCancelled):
            self.client.import_selected(self.archive, self.root)
        self.assertFalse(list(self.root.iterdir()))

    def test_cancelled_extraction_cleans_only_its_temporary_project(self):
        archive = self.root / "data.zip"; archive.write_bytes(self.data)
        destination = self.root / "capture"
        def cancel():
            raise HeadsetCancelled()
        with self.assertRaises(HeadsetCancelled):
            import_archive(archive, destination, cancel)
        self.assertTrue(archive.is_file()); self.assertFalse(destination.exists())
        self.assertFalse(list(self.root.glob(".capture-*")))
