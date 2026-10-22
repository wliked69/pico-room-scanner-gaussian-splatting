import json
import os
import struct
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from room_splat.gpu import Adapter, OVERRIDE, gpu_environment
from room_splat.pipeline import Pipeline, Settings


class PipelineGpuTests(unittest.TestCase):
    def simulate(self, actual_vendor):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        root = Path(temp.name); capture = root / "capture"; (capture / "images").mkdir(parents=True)
        colmap = root / "colmap.exe"; colmap.touch()
        brush = root / "brush.exe"; brush.touch()
        commands = []
        class SimulatedPipeline(Pipeline):
            def run(self, args, cwd, logfile):
                commands.append((args, self.gpu_env.copy()))
                if args[1] == "mapper":
                    model = cwd / "sparse/0"; model.mkdir()
                    (model / "images.bin").write_bytes(struct.pack("<Q", 3))
                elif args[1] == "image_undistorter":
                    (cwd / "dataset/images").mkdir(parents=True)
                elif args[0] == str(brush):
                    name = "NVIDIA GeForce RTX 4090" if actual_vendor == 4318 else "AMD Radeon RX 9070 XT"
                    logfile.write_text(f'Using adapter AdapterInfo {{ name: "{name}", vendor: {actual_vendor}, device: 1, device_type: DiscreteGpu, driver: "test", backend: Vulkan }}')
                    destination = Path(args[args.index("--export-path") + 1])
                    (destination / "export_100.ply").write_bytes(b"ply\n" + b"\0" * 200)
        selected = Adapter("NVIDIA GeForce RTX 4090", 4318, "DiscreteGpu", "Vulkan")
        with patch("room_splat.pipeline.validate_capture", return_value={"frames": [{}, {}, {}]}), \
             patch("room_splat.pipeline.choose_gpu", return_value=(selected, gpu_environment("DiscreteGpu(1)"))), \
             patch("room_splat.pipeline.subprocess.run", return_value=subprocess.CompletedProcess([], 0, "--total-steps --export-path", "")):
            result = SimulatedPipeline(lambda _: None).reconstruct(capture, Settings(str(colmap), str(brush), 100, gpu="nvidia"))
        report = json.loads((result.parents[1] / "report.json").read_text())
        return commands, report

    def test_nvidia_gpu_training_selection_reaches_child_and_report(self):
        commands, report = self.simulate(4318)
        self.assertEqual("nvidia", report["gpu"]["vendor"])
        self.assertEqual("NVIDIA GeForce RTX 4090", report["gpu"]["name"])
        self.assertEqual("CPU COLMAP", report["camera_solver"])
        training, env = commands[-1]
        self.assertEqual("DiscreteGpu(1)", env[OVERRIDE])
        extraction = commands[0][0]
        self.assertEqual("0", extraction[extraction.index("--SiftExtraction.use_gpu") + 1])

    def test_wrong_actual_training_gpu_cannot_be_reported_as_success(self):
        with self.assertRaisesRegex(RuntimeError, "显卡与已选择显卡不一致"):
            self.simulate(4098)
