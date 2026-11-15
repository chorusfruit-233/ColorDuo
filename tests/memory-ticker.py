#!/usr/bin/env python3
"""Exercise the production RAM sampler with real layout settings and controlled Android services."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/src/main/java/io/github/yixing233/hyperduo"
from sampler_fixtures import fixtures

scenes = ("sampling", "unavailable", "sleep", "aod", "rectangle", "unused-left", "disabled",
          "removed", "throttle", "retired", "wake", "thread", "missing-power", "screen-event", "config-event", "interval")
with tempfile.TemporaryDirectory(prefix="colorduo-memory-") as directory:
    files = [p for p in (ROOT / "tests/shims").rglob("*.java") if str(p.relative_to(ROOT / "tests/shims")) not in fixtures]
    for name, content in fixtures.items():
        path = Path(directory) / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
        files.append(path)
    files += [SOURCE / (name + ".java") for name in (
        "Prefs", "TrioSettings", "TrioAppearance", "TrioGeometry", "TrioZones", "TrioSizing",
        "TrioRenderer", "ChargeFlowRenderer", "DuoPolicy", "ChargeSpeed", "ColorOsChargeSpeed", "MemoryTicker", "PowerSampling")]
    files.append(ROOT / "tests/MemoryTickerTest.java")
    subprocess.run(["javac", "-encoding", "UTF-8", "-d", directory, *map(str, files)], check=True)
    failures = []
    for scene in scenes:
        result = subprocess.run(["java", "-Djava.awt.headless=true", "-cp", directory,
                                 "io.github.yixing233.hyperduo.MemoryTickerTest", scene])
        if result.returncode:
            failures.append(scene)
    if failures:
        raise SystemExit("RAM regressions failed: " + ", ".join(failures))
    print(f"RAM sampling, layout ownership, sleep and lifecycle: {len(scenes)} scenarios passed")

subprocess.run([__import__("sys").executable, str(ROOT / "tests/battery-demand.py")], check=True)
