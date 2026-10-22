import hashlib
import json
import tempfile
import unittest
import zipfile
from pathlib import Path

from room_splat.capture import SCHEMA, import_archive, validate_capture


class CaptureTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.archive = self.root / "scan.zip"

    def tearDown(self):
        self.temp.cleanup()

    def make_archive(self, corrupt=False, extra=None):
        frames = []
        with zipfile.ZipFile(self.archive, "w") as z:
            for i in range(3):
                data = b"test-pixel-payload-" + bytes([i])
                name = f"images/frame_{i:06}.jpg"
                frames.append({"file": name, "width": 640, "height": 480, "sha256": hashlib.sha256(data).hexdigest()})
                z.writestr(name, b"corrupted" if corrupt and i == 1 else data)
            z.writestr("manifest.json", json.dumps({"schema": SCHEMA, "frames": frames}))
            if extra:
                z.writestr(extra, "unwanted")

    def test_valid_archive_commits_and_can_be_revalidated(self):
        self.make_archive()
        destination = self.root / "capture"
        self.assertEqual(3, len(import_archive(self.archive, destination)["frames"]))
        self.assertEqual(SCHEMA, validate_capture(destination)["schema"])

    def test_optional_coverage_data_is_preserved_for_new_headset_archives(self):
        self.make_archive()
        coverage = {"schema": "room-splat.coverage/1", "rgb_alignment_verified": False, "cells": []}
        with zipfile.ZipFile(self.archive, "a") as z:
            z.writestr("coverage.json", json.dumps(coverage))
        destination = self.root / "capture"
        self.assertEqual(3, len(import_archive(self.archive, destination)["frames"]))
        self.assertEqual(coverage, json.loads((destination / "coverage.json").read_text()))

    def test_corrupted_photo_never_commits_partial_destination(self):
        self.make_archive(corrupt=True)
        destination = self.root / "capture"
        with self.assertRaisesRegex(ValueError, "校验失败"):
            import_archive(self.archive, destination)
        self.assertFalse(destination.exists())

    def test_traversal_cannot_escape_destination(self):
        self.make_archive(extra="../outside.txt")
        with self.assertRaisesRegex(ValueError, "不安全"):
            import_archive(self.archive, self.root / "capture")
        self.assertFalse((self.root / "outside.txt").exists())

    def test_unlisted_image_is_rejected(self):
        self.make_archive(extra="images/unlisted.jpg")
        with self.assertRaisesRegex(ValueError, "未列入"):
            import_archive(self.archive, self.root / "capture")

    def test_existing_capture_is_preserved(self):
        self.make_archive()
        destination = self.root / "capture"
        destination.mkdir()
        (destination / "user-data").write_text("keep")
        with self.assertRaises(ValueError):
            import_archive(self.archive, destination)
        self.assertEqual("keep", (destination / "user-data").read_text())

    def test_duplicate_case_insensitive_paths_are_rejected(self):
        self.make_archive(extra="Images/FRAME_000000.JPG")
        with self.assertRaisesRegex(ValueError, "重复"):
            import_archive(self.archive, self.root / "capture")


if __name__ == "__main__":
    unittest.main()
