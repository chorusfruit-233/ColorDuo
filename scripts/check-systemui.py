#!/usr/bin/env python3
"""Read-only native entry-point check; this does not certify a ROM without device testing."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dexsql", default="dexsql")
    parser.add_argument("--aapt2", default="aapt2")
    parser.add_argument("--output", type=Path)
    parser.add_argument("apks", nargs="+", type=Path)
    args = parser.parse_args()
    reports = []
    for apk in args.apks:
        badging = subprocess.check_output([args.aapt2, "dump", "badging", str(apk)], text=True)
        package = re.search(r"^package: name='([^']+)'.*?versionName='([^']+)'", badging, re.M)
        if not package or package[1] != "com.android.systemui":
            raise SystemExit(f"Not a SystemUI APK: {apk.name}")
        version = package[2]
        generation = int(version.split(".")[0])
        if generation not in (15, 16, 17):
            raise SystemExit(f"No reference contract for SystemUI {version}")
        battery = "com.oplus.systemui.statusbar.pipeline.battery."
        wifi = "com.android.systemui.statusbar.pipeline.wifi."
        mobile = "com.oplus.systemui.statusbar.pipeline.mobile.ui.viewmodel."
        required = [
            (battery + "ui.view.StatBatteryMeterView", "onFinishInflate", 0),
            (battery + "ui.view.StatBatteryMeterView", "onAttachedToWindow", 0),
            (battery + "ui.view.StatBatteryMeterView", "bindViewModel", 1),
            (battery + "ui.view.StatBatteryMeterView", "getBatteryIcon", 0),
            (battery + "ui.binder.BatteryViewBinder", "bind", 2),
            (battery + "data.repository.BatteryRepositoryImpl", "getBatteryLevel", 0),
            (battery + "data.repository.BatteryRepositoryImpl", "getChargeState", 0),
            (battery + "data.repository.BatteryRepositoryImpl", "isSavingMode", 0),
            (battery + "ui.viewmodel.LocationBasedBatteryViewModel", "getBatteryIcon", 0),
            (battery + "ui.viewmodel.LocationBasedBatteryViewModel", "getBatteryColor", 0),
            (battery + "ui.model.StatBatteryIcon", "getPowerLevel", 0),
            (battery + "ui.model.StatBatteryIcon", "getTintColor", 0),
            (battery + "ui.model.BatteryIconColor", "getSingleToneColor", 0),
            (wifi + "ui.model.WifiIcon$Companion", "fromModel", 3),
            (wifi + "shared.model.WifiNetworkModel$Active", "getLevel", 0),
            (wifi + "data.repository.WifiRepositorySwitcher", "getWifiNetwork", 0),
            (wifi + "data.repository.prod.WifiRepositoryImpl", "getWifiNetwork", 0),
            (mobile + "OplusMobileIconViewModel", "getIcon", 0),
            ("com.android.systemui.statusbar.phone.StatusIconContainer", "updateStates", 0),
            ("com.oplus.systemui.aod.aodclock.constant.AodData", "setAodIsInShow", 1),
            ("kotlinx.coroutines.CoroutineScopeKt", "CoroutineScope", 1),
            ("kotlinx.coroutines.flow.FlowKt", "launchIn", 2),
            ("kotlinx.coroutines.Job", "cancel", 1),
            ("kotlinx.coroutines.Job", "isActive", 0),
        ]
        signal = "com.android.systemui.statusbar.pipeline.mobile.domain.model.SignalIconModel$"
        signal += "CellularTypeIconModel$Cellular" if generation == 17 else "Cellular"
        required += [(signal, "getLevel", 0), (signal, "getNumberOfLevels", 0)]
        if generation == 17:
            required += [(mobile + "OplusCellularIconViewModelImpl", "getIcon", 0),
                         (mobile + "OplusMobileIconViewModel", "getNetworkTypeText", 0)]
        else:
            required += [(mobile + "OplusMobileIconViewModel", "getNetworkTypeIcon", 0),
                         ("com.android.systemui.statusbar.pipeline.mobile.domain.model.NetworkTypeIconModel$DefaultIcon", "getName", 0)]
        if generation >= 16:
            frame = "com.oplus.systemui.seamless.widget.StatusBarFakeFrame"
            required += [(frame, "getMView", 0), (frame, "setFakeView", 2), (frame, "dispatchDraw", 1)]
        classes = sorted({"L" + item[0].replace(".", "/") + ";" for item in required})
        sql = ("SELECT DISTINCT class_descriptor, name, param_count FROM methods WHERE access_flags IS NOT NULL "
               "AND class_descriptor IN (" + ",".join("'" + c + "'" for c in classes) + ")")
        table = subprocess.check_output([args.dexsql, "query", "-i", str(apk), "-s", sql], text=True)
        rows = [[part.strip() for part in line.split("|")[1:-1]] for line in table.splitlines() if line.startswith("|")][1:]
        defined = {(r[0][1:-1].replace("/", "."), r[1], int(r[2])) for r in rows}
        missing = [f"{c}.{m}/{count}" for c, m, count in required if (c, m, count) not in defined]
        with apk.open("rb") as source:
            digest = hashlib.file_digest(source, "sha256").hexdigest()
        report = {"apk": apk.name, "systemui": version, "sha256": digest,
                  "checked_methods": len(required), "missing": missing, "validation": "static_only"}
        reports.append(report)
        print(f"SystemUI {version}: {len(required) - len(missing)}/{len(required)} native entry points present")
        for item in missing:
            print("  missing: " + item)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(reports, indent=2) + "\n")
    if any(r["missing"] for r in reports):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
