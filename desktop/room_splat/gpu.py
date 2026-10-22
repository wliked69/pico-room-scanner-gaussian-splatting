"""Brush 0.3 / pinned CubeCL GPU selection, verified against actual adapter logs."""
from __future__ import annotations

import json
import os
import re
import struct
import subprocess
import tempfile
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Callable

GPU_CHOICES = {"自动（优先独立显卡）": "auto", "NVIDIA（英伟达）": "nvidia", "AMD": "amd", "Intel": "intel"}
VENDORS = {4318: "nvidia", 4098: "amd", 32902: "intel"}
OVERRIDE = "CUBECL_WGPU_DEFAULT_DEVICE"


@dataclass(frozen=True)
class Adapter:
    name: str
    vendor_id: int
    device_type: str
    backend: str

    @property
    def vendor(self):
        return VENDORS.get(self.vendor_id, "other")

    def report(self):
        return asdict(self) | {"vendor": self.vendor}


def parse_adapter(log: str) -> Adapter | None:
    match = re.search(r'Using adapter AdapterInfo \{ name: "([^"\n]+)", vendor: (\d+),.*?device_type: (\w+),.*?backend: (\w+)', log)
    return Adapter(match[1], int(match[2]), match[3], match[4]) if match else None


def gpu_environment(override: str | None = None) -> dict[str, str]:
    env = os.environ.copy()
    # Auto must not inherit an old CPU/integrated adapter selection from another tool.
    for key in (OVERRIDE, "CUBECL_DEFAULT_DEVICE", "WGPU_ADAPTER_NAME"):
        env.pop(key, None)
    env["RUST_LOG"] = "info"
    if override:
        env[OVERRIDE] = override
    return env


def windows_gpu_count() -> int:
    """Inventory bounds probe count; WMI ordering is never used as a GPU index."""
    if os.name != "nt":
        return 4
    command = "@(Get-CimInstance Win32_VideoController | Where-Object { $_.PNPDeviceID -match 'VEN_(10DE|1002|8086)' -and $_.ConfigManagerErrorCode -eq 0 } | Select-Object -ExpandProperty Name) | ConvertTo-Json -Compress"
    try:
        result = subprocess.run(["powershell.exe", "-NoProfile", "-Command", command],
                                capture_output=True, text=True, timeout=15,
                                creationflags=subprocess.CREATE_NO_WINDOW)
        names = json.loads(result.stdout) if result.stdout.strip() else []
        return min(8, max(1, len(names) if isinstance(names, list) else 1))
    except (OSError, ValueError, subprocess.TimeoutExpired):
        return 4


def probe_adapter(brush: str, override: str | None) -> Adapter:
    """Tiny local PLY import initializes the real compute adapter without training a room."""
    with tempfile.TemporaryDirectory(prefix="roomsplat-gpu-") as temporary:
        model = Path(temporary) / "probe.ply"
        fields = ["x", "y", "z", "f_dc_0", "f_dc_1", "f_dc_2", "opacity", "scale_0", "scale_1", "scale_2", "rot_0", "rot_1", "rot_2", "rot_3"]
        header = "ply\nformat binary_little_endian 1.0\nelement vertex 1\n" + "".join(f"property float {field}\n" for field in fields) + "end_header\n"
        model.write_bytes(header.encode("ascii") + struct.pack("<14f", 0, 0, 0, 0, 0, 0, 0, -3, -3, -3, 1, 0, 0, 0))
        result = subprocess.run([brush, str(model)], env=gpu_environment(override), capture_output=True,
                                text=True, encoding="utf-8", errors="replace", timeout=20,
                                creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        output = result.stdout + result.stderr
        adapter = parse_adapter(output)
        if result.returncode or adapter is None:
            raise RuntimeError("显卡初始化失败，请更新官方显卡驱动。\n" + output[-1500:])
        if adapter.device_type not in ("DiscreteGpu", "IntegratedGpu", "VirtualGpu"):
            raise RuntimeError("训练需要可用的 GPU，当前只有软件/CPU 图形设备：" + adapter.name)
        return adapter


def choose_gpu(brush: str, mode: str = "auto", log: Callable[[str], None] = print,
               probe=probe_adapter, count=windows_gpu_count) -> tuple[Adapter, dict[str, str]]:
    if mode not in GPU_CHOICES.values():
        raise ValueError("训练显卡设置无效，请选择自动、NVIDIA、AMD 或 Intel。")
    log("检查训练显卡…")
    errors = []
    try:
        first = probe(brush, None)
    except (RuntimeError, OSError, subprocess.TimeoutExpired) as error:
        if mode == "auto":
            raise
        first = None
        errors.append(str(error))
    if first is not None and (mode == "auto" or first.vendor == mode):
        return first, gpu_environment()
    # CubeCL indices are backend-specific. Probe and verify them rather than guessing WMI order.
    for family in ("DiscreteGpu", "IntegratedGpu"):
        for index in range(count()):
            override = f"{family}({index})"
            try:
                candidate = probe(brush, override)
                if candidate.vendor == mode:
                    log("已验证显卡选择：" + candidate.name)
                    return candidate, gpu_environment(override)
            except (RuntimeError, OSError, subprocess.TimeoutExpired) as error:
                errors.append(str(error))
                # An out-of-range adapter index cannot uncover later devices in this family.
                if "No Discrete GPU" in str(error) or "No Integrated GPU" in str(error):
                    break
    label = {"nvidia": "NVIDIA（英伟达）", "amd": "AMD", "intel": "Intel"}[mode]
    detected = first.name if first else "默认设备初始化失败"
    raise RuntimeError(f"未找到可用的 {label} 训练显卡。自动检测到：{detected}。请确认显卡及驱动，或改选自动。" +
                       ("\n" + errors[-1][-1000:] if errors else ""))
