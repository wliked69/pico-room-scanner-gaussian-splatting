import os
import unittest
from unittest.mock import patch
from room_splat.gpu import Adapter, OVERRIDE, choose_gpu, gpu_environment, parse_adapter
from room_splat.pipeline import Settings


class GpuCompatibilityTests(unittest.TestCase):
    def setUp(self):
        self.amd = Adapter("AMD Radeon RX 9070 XT", 4098, "DiscreteGpu", "Vulkan")
        self.nvidia = Adapter("NVIDIA GeForce RTX 4090", 4318, "DiscreteGpu", "Vulkan")

    def test_nvidia_adapter_is_read_from_actual_engine_log(self):
        info = parse_adapter('[INFO cubecl_wgpu::runtime] Using adapter AdapterInfo { name: "NVIDIA GeForce RTX 4090", vendor: 4318, device: 1, device_type: DiscreteGpu, driver: "NVIDIA", backend: Vulkan }')
        self.assertEqual(self.nvidia, info)
        self.assertEqual("nvidia", info.vendor)

    def test_auto_accepts_nvidia_without_cuda_environment(self):
        probe = lambda brush, selection: self.nvidia
        info, env = choose_gpu("brush", "auto", lambda _: None, probe)
        self.assertEqual(self.nvidia, info)
        self.assertNotIn(OVERRIDE, env)

    def test_nvidia_on_second_discrete_adapter_uses_verified_index(self):
        seen = []
        def probe(brush, selection):
            seen.append(selection)
            return self.nvidia if selection == "DiscreteGpu(1)" else self.amd
        info, env = choose_gpu("brush", "nvidia", lambda _: None, probe, lambda: 2)
        self.assertEqual(self.nvidia, info)
        self.assertEqual("DiscreteGpu(1)", env[OVERRIDE])
        self.assertEqual([None, "DiscreteGpu(0)", "DiscreteGpu(1)"], seen)

    def test_explicit_nvidia_never_silently_trains_on_amd(self):
        with self.assertRaisesRegex(RuntimeError, "未找到可用的 NVIDIA"):
            choose_gpu("brush", "nvidia", lambda _: None, lambda b, s: self.amd, lambda: 1)

    def test_manual_nvidia_can_bypass_an_unusable_default_adapter(self):
        def probe(brush, selection):
            if selection is None:
                raise RuntimeError("Default adapter driver failed")
            return self.nvidia
        info, env = choose_gpu("brush", "nvidia", lambda _: None, probe, lambda: 1)
        self.assertEqual(self.nvidia, info)
        self.assertEqual("DiscreteGpu(0)", env[OVERRIDE])

    def test_amd_path_still_uses_real_auto_adapter(self):
        info, env = choose_gpu("brush", "amd", lambda _: None, lambda b, s: self.amd)
        self.assertEqual(self.amd, info)
        self.assertNotIn(OVERRIDE, env)

    def test_auto_clears_inherited_cpu_or_stale_gpu_choices(self):
        with patch.dict(os.environ, {OVERRIDE: "Cpu", "CUBECL_DEFAULT_DEVICE": "Cpu", "WGPU_ADAPTER_NAME": "wrong"}):
            env = gpu_environment()
        self.assertNotIn(OVERRIDE, env)
        self.assertNotIn("CUBECL_DEFAULT_DEVICE", env)
        self.assertNotIn("WGPU_ADAPTER_NAME", env)

    def test_missing_adapter_log_and_bad_settings_are_not_accepted(self):
        self.assertIsNone(parse_adapter("GPU processing completed"))
        with self.assertRaises(ValueError):
            choose_gpu("brush", "cuda", lambda _: None)

    def test_existing_settings_callers_keep_auto_default(self):
        self.assertEqual("auto", Settings("colmap", "brush", 7000).gpu)


if __name__ == "__main__":
    unittest.main()
