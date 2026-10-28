#!/usr/bin/env python3
"""Run the device-unit regression through the real upstream calculation and formatter."""
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "app/src/main/java/io/github/yixing233/hyperduo"
battery = (SOURCE / "BatteryPresence.java").read_text()
hooks = (SOURCE / "TrioHooks.java").read_text()


def method(source, name):
    pattern = rf"^    (?:private )?static [\w\[\]]+ {re.escape(name)}\([^\n]*\) \{{.*?^    \}}"
    match = re.search(pattern, source, re.M | re.S)
    if not match:
        raise RuntimeError(f"Production method missing: {name}")
    return match.group()


separator = re.search(r"static final char PERF_BREAK = ('[^']+');", hooks)
if not separator:
    raise RuntimeError("Production separator missing")
methods = "\n".join((method(battery, "wattsOf"), method(battery, "publish"),
                     method(hooks, "powerText"), method(hooks, "perfText")))
harness = r'''
package io.github.yixing233.hyperduo;

public final class ColorOsPowerTest {
    private static final class Nodes implements ColorOsBattery.Reader {
        String cells;
        String pack;
        String voltage;
        int cellReads;
        int packReads;
        int voltageReads;
        boolean denied;
        boolean voltageDenied;

        public String read(String path) throws java.io.IOException {
            if (path.equals(ColorOsBattery.CELLS_PATH)) cellReads++;
            else if (path.equals(ColorOsBattery.PACK_VOLTAGE_PATH)) packReads++;
            else if (path.equals(ColorOsBattery.BATTERY_VOLTAGE_PATH)) voltageReads++;
            else throw new AssertionError("Unexpected battery path: " + path);
            if (denied || (voltageDenied && path.equals(ColorOsBattery.BATTERY_VOLTAGE_PATH))) {
                throw new java.io.IOException("permission denied");
            }
            return path.equals(ColorOsBattery.CELLS_PATH) ? cells
                    : path.equals(ColorOsBattery.PACK_VOLTAGE_PATH) ? pack : voltage;
        }
    }

    static class TrioState {
        static final int TEMP_UNKNOWN = Integer.MIN_VALUE;
        static final float POWER_UNKNOWN = Float.NaN;
        static boolean sChargingAttached;
        static float sChargePowerW;
        static int sBatteryTempC = TEMP_UNKNOWN;
    }
    static class TrioAppearance {
        boolean perfPower = true;
        boolean perfTemp;
        boolean perfTempChargingOnly;
        boolean perfAnywhere() { return perfPower || perfTemp; }
    }
    static final char PERF_BREAK = @SEPARATOR@;

    static class ColorOsHooks {
        static boolean worker;
        static Runnable publish;
        static boolean dispatchBatteryPublish(Runnable value) {
            if (!worker) return false;
            publish = value;
            return true;
        }
    }
    static class TrioHooks {
        static int invalidations;
        static void invalidateHosts() { invalidations++; }
    }

@METHODS@

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        String firmware = "OnePlus/PJZ110/OP5D0DL1:17/CP2A.260605.016/V.487427a-2227f32-224bc4c:user/release-keys";
        // User's raw Binder result: low word fffffe10, high word ffffffff = -496 mA.
        int current = ColorOsPolicy.batteryCurrentMicroAmps(firmware, -496);
        check(current == -496000, "verified mA input must become uA exactly once");
        float watts = wattsOf(current, 4465);
        check(Math.abs(watts - 2.21464f) < 0.00001f, "real sample must produce about 2.2 W");
        check(powerText(watts).equals("2.2W"), "fixed sample uses upstream decimal formatting");
        check(powerText(wattsOf(-496, 4465)).equals("0.0W"), "fixture reproduces alpha3's unit error");

        TrioAppearance appearance = new TrioAppearance();
        TrioState.sChargingAttached = true;
        TrioState.sChargePowerW = watts;
        check(perfText(appearance).equals("2.2W"), "FULL with an attached source retains the real reading");
        TrioState.sBatteryTempC = 32;
        appearance.perfTemp = true;
        check(perfText(appearance).equals("2.2W\n32\u00B0C"), "power and temperature retain upstream two-line formatting");
        TrioState.sChargingAttached = false;
        check(perfText(appearance).equals("32\u00B0C"), "unplugging removes the power segment");
        TrioState.sChargingAttached = true;
        appearance.perfTemp = false;
        TrioState.sChargePowerW = wattsOf(ColorOsPolicy.batteryCurrentMicroAmps(firmware, 0), 4465);
        check(perfText(appearance).equals("0.0W"), "a real zero must remain a real zero");

        int unknown = ColorOsPolicy.batteryCurrentMicroAmps(firmware, Integer.MIN_VALUE);
        check(unknown == Integer.MIN_VALUE, "unsupported current keeps its sentinel");
        TrioState.sChargePowerW = wattsOf(unknown, 4465);
        check(Float.isNaN(TrioState.sChargePowerW) && perfText(appearance).isEmpty(),
                "unsupported current must not display a false zero");
        check(Float.isNaN(wattsOf(current, 0)), "unknown voltage stays unknown");
        check(ColorOsPolicy.batteryCurrentMicroAmps("different firmware", -496000) == -496000,
                "unverified builds retain the Android uA contract");
        check(ColorOsPolicy.batteryCurrentMicroAmps(firmware.replace("V.487427a", "V.new"), -496000) == -496000,
                "future firmware must not inherit an unverified multiplier");
        check(ColorOsPolicy.batteryCurrentMicroAmps(firmware, Integer.MAX_VALUE) == Integer.MIN_VALUE,
                "overflow is unavailable rather than a fabricated power reading");
        check(wattsOf(ColorOsPolicy.batteryCurrentMicroAmps(firmware, 496), 4465) == watts,
                "the upstream formula handles either current sign");

        Nodes nodes = new Nodes();
        nodes.cells = "4,2,0,0,654,4450,4470\n";
        ColorOsBattery sampler = new ColorOsBattery(nodes);
        ColorOsBattery.Sample dual = sampler.sample(firmware, -496, 4465, 0);
        check(dual.seriesCells == 2 && dual.cellsSource.equals("aging_ffc_data"),
                "kernel gauge metadata detects a two-cell series pack");
        check(dual.currentMicroAmps == -496000 && dual.voltageMilliVolts == 8930,
                "mA conversion and series-cell voltage correction happen independently, once each");
        check(dual.voltageSource.equals("single_cell_x2"), "the fallback records its voltage basis");
        check(Math.abs(wattsOf(dual.currentMicroAmps, dual.voltageMilliVolts) - 4.42928f) < 0.00001f,
                "the dual-cell sample represents total battery-side power");
        check(powerText(wattsOf(dual.currentMicroAmps, dual.voltageMilliVolts)).equals("4.4W"),
                "dual-cell input retains the original power formatter");

        Nodes actual = new Nodes();
        actual.cells = "0,2,0,0,655,4530,4470,4500,4455\n";
        // Device voltage_now: 4434000 uV = the framework's 4434 mV input.
        // bms/voltage_now was unavailable. Current and voltage were separate probes.
        // Three bracketed CURRENT_NOW calls matched the -753 mA gauge reading.
        ColorOsBattery.Sample device = new ColorOsBattery(actual).sample(firmware, -753, 4434, 0);
        check(device.seriesCells == 2 && device.cellsSource.equals("aging_ffc_data"),
                "the actual Android 17 aging_ffc_data record detects two series cells");
        check(device.voltageMilliVolts == 8868 && device.voltageSource.equals("single_cell_x2"),
                "the actual single-cell voltage is doubled once when BMS voltage is unavailable");
        check(device.currentMicroAmps == -753000, "the matched API/gauge current only changes units");
        check(Math.abs(wattsOf(device.currentMicroAmps, device.voltageMilliVolts) - 6.677604f) < 0.00001f,
                "verified device input fixtures account for the entire series pack");
        check(powerText(wattsOf(device.currentMicroAmps, device.voltageMilliVolts)).equals("6.7W"),
                "verified inputs keep the shared decimal format");
        check(powerText(wattsOf(device.currentMicroAmps, 4434)).equals("3.3W"),
                "fixture reproduces the previous single-cell undercount");

        ColorOsBattery.Sample alreadyPack = sampler.sample(firmware, -496, 8930, 3000);
        check(alreadyPack.voltageMilliVolts == 8930 && nodes.packReads == 1,
                "an existing pack voltage is not doubled or queried again");
        check(alreadyPack.voltageSource.equals("broadcast_pack"), "pack basis is logged");

        nodes.pack = "8920000\n";
        ColorOsBattery.Sample measured = sampler.sample(firmware, -496, 4465, 60000);
        check(measured.voltageMilliVolts == 8920 && measured.voltageSource.equals("bms_pack"),
                "a readable real pack voltage takes precedence over twice the maximum cell voltage");
        check(nodes.cellReads == 1, "validated hardware topology is cached");

        Nodes one = new Nodes();
        one.cells = "0,1,0,-1,654";
        ColorOsBattery.Sample single = new ColorOsBattery(one).sample(firmware, -496, 4465, 0);
        check(single.seriesCells == 1 && single.voltageMilliVolts == 4465 && one.packReads == 0,
                "one-cell gauge metadata overrides firmware fallback without changing the voltage");
        ColorOsBattery.Sample parallel = new ColorOsBattery(one).sample("other firmware", -992000, 4465, 0);
        check(powerText(wattsOf(parallel.currentMicroAmps, parallel.voltageMilliVolts)).equals("4.4W"),
                "a parallel pack with summed current is not multiplied as two series cells");

        Nodes singleBms = new Nodes();
        singleBms.cells = "1,2,0,0,654";
        singleBms.pack = "4463000";
        check(new ColorOsBattery(singleBms).sample(firmware, -496, 4465, 0).voltageMilliVolts == 8930,
                "a BMS reporting only one cell is not mistaken for total pack voltage");

        Nodes denied = new Nodes();
        denied.denied = true;
        ColorOsBattery fallback = new ColorOsBattery(denied);
        ColorOsBattery.Sample known = fallback.sample(firmware, -496, 4465, 0);
        check(known.seriesCells == 2 && known.cellsSource.equals("firmware")
                && known.voltageMilliVolts == 8930, "known OnePlus firmware survives inaccessible sysfs");
        fallback.sample(firmware, -496, 4465, 3000);
        check(denied.cellReads == 1 && denied.packReads == 1,
                "denied nodes are rate-limited instead of retried on every battery sample");
        fallback.sample(firmware, -496, 4465, 60000);
        check(denied.cellReads == 2 && denied.packReads == 2, "unavailable nodes can recover after a minute");
        ColorOsBattery.Sample unverified = new ColorOsBattery(denied).sample("other firmware", -496000, 4465, 0);
        check(unverified.seriesCells == 0 && unverified.currentMicroAmps == -496000
                && unverified.voltageMilliVolts == 4465, "unknown hardware keeps Android input semantics");

        Nodes recovering = new Nodes();
        ColorOsBattery late = new ColorOsBattery(recovering);
        check(late.sample("other firmware", -496000, 4465, 0).seriesCells == 0, "missing topology is unknown");
        recovering.cells = "1,2,0,0,654";
        check(late.sample("other firmware", -496000, 4465, 3000).seriesCells == 0, "probe cooldown is respected");
        check(late.sample("other firmware", -496000, 4465, 60000).voltageMilliVolts == 8930,
                "a late hardware node can activate dual-cell correction");

        Nodes initializing = new Nodes();
        initializing.cells = "0,1,0,0,0";
        ColorOsBattery boot = new ColorOsBattery(initializing);
        check(boot.sample("other firmware", -496000, 4465, 0).seriesCells == 1,
                "the initial driver fallback is accepted conservatively");
        initializing.cells = "0,2,0,0,0";
        check(boot.sample("other firmware", -496000, 4465, 60000).seriesCells == 2,
                "the driver's startup one-cell fallback cannot stay cached forever");

        for (String invalid : new String[] {"", "1,2", "1,2,0,0", "1,3,0,0,654", "1,0,0,0,654",
                "1,2,1,0,654", "1,2,0,0,654,", "1,2,0,0,nope", "1,2,0,0,2147483648"}) {
            check(ColorOsPolicy.batterySeriesCells(invalid) == 0, "invalid topology is not guessed: " + invalid);
        }
        ColorOsBattery.Sample unavailable = sampler.sample(firmware, Integer.MIN_VALUE, 4465, 63000);
        check(Float.isNaN(wattsOf(unavailable.currentMicroAmps, unavailable.voltageMilliVolts)),
                "dual-cell correction preserves unavailable current");
        ColorOsBattery.Sample noVoltage = sampler.sample(firmware, -496, 0, 66000);
        check(Float.isNaN(wattsOf(noVoltage.currentMicroAmps, noVoltage.voltageMilliVolts)),
                "dual-cell correction does not fabricate missing voltage");
        check(ColorOsPolicy.batteryPackVoltageMilliVolts(8930, 2, 9000) == 8930,
                "reported total voltage remains authoritative");
        check(ColorOsPolicy.batteryPackVoltageMilliVolts(4465, 0, 8930) == 4465,
                "a pack node alone cannot establish hardware topology");

        String pmx = "OPPO/PMX110/OP659FL1:17/CP2A.260605.016/C.34208f1-dbc5d7-d921b4:user/release-keys";
        // Issue #5: Binder fffffade/ffffffff and current_now both report -1314 mA;
        // battery/voltage_now is 4474 mV, while the broadcast is truncated to 4 mV.
        check((int) Long.parseLong("fffffade", 16) == -1314, "the issue's signed Binder value is -1314");
        check(powerText(wattsOf(-1314, 4)).equals("0.0W"), "the exact issue input reproduces zero power");
        check(powerText(wattsOf(-1314000, 4)).equals("0.0W"), "fixing current alone leaves the voltage bug");
        check(powerText(wattsOf(-1314, 4474)).equals("0.0W"), "fixing voltage alone leaves the current bug");
        Nodes pmxNodes = new Nodes();
        pmxNodes.voltage = "4474\n";
        ColorOsBattery pmxSampler = new ColorOsBattery(pmxNodes);
        ColorOsBattery.Sample pmxSample = pmxSampler.sample(pmx, -1314, 4, 0);
        check(pmxSample.currentMicroAmps == -1314000 && pmxSample.voltageMilliVolts == 4474,
                "both measured PMX input units must be corrected before the shared formula");
        check(pmxSample.seriesCells == 0 && pmxSample.cellsSource.equals("unknown"),
                "a verified mA input does not establish PMX hardware topology");
        check(pmxSample.voltageSource.equals("battery_node_mV") && pmxNodes.voltageReads == 1,
                "the lost fractional voltage comes from the actual mV node");
        check(Math.abs(wattsOf(pmxSample.currentMicroAmps, pmxSample.voltageMilliVolts) - 5.878836f) < 0.00001f,
                "the measured input represents 5.878836 W before any independently detected series correction");
        check(powerText(wattsOf(pmxSample.currentMicroAmps, pmxSample.voltageMilliVolts)).equals("5.9W"),
                "the PMX fix retains the upstream decimal formatter");
        check(pmxSampler.sample(pmx, 1314, 4, 3000).currentMicroAmps == 1314000,
                "the new firmware handles either current sign");
        check(pmxSampler.sample(pmx, 0, 4, 6000).currentMicroAmps == 0, "real zero remains zero on PMX");
        check(pmxSampler.sample(pmx, Integer.MIN_VALUE, 4, 9000).currentMicroAmps == Integer.MIN_VALUE,
                "unavailable PMX current preserves its sentinel");
        check(pmxSampler.sample(pmx, Integer.MAX_VALUE, 4, 12000).currentMicroAmps == Integer.MIN_VALUE,
                "PMX current overflow must not become a fabricated reading");
        pmxNodes.voltage = "4410";
        check(pmxSampler.sample(pmx, -1314, 4, 15000).voltageMilliVolts == 4410,
                "valid fallback voltage is refreshed rather than cached as a stale reading");
        int reads = pmxNodes.voltageReads;
        ColorOsBattery.Sample correctBroadcast = pmxSampler.sample(pmx, -1314, 4474, 18000);
        check(correctBroadcast.voltageMilliVolts == 4474 && correctBroadcast.voltageSource.equals("broadcast")
                && pmxNodes.voltageReads == reads, "a valid broadcast needs no sysfs fallback");
        check(pmxSampler.sample(pmx, -1314, 0, 21000).voltageMilliVolts == 4410,
                "an absent broadcast can use the verified voltage node");

        Nodes pmxDual = new Nodes();
        pmxDual.cells = "0,2,0,0,654";
        pmxDual.voltage = "4474";
        ColorOsBattery.Sample pmxSeries = new ColorOsBattery(pmxDual).sample(pmx, -1314, 4, 0);
        check(pmxSeries.seriesCells == 2 && pmxSeries.voltageMilliVolts == 8948
                && pmxSeries.voltageSource.equals("battery_node_mV_x2"),
                "independently detected series cells correct the normalized node voltage once");
        check(Math.abs(wattsOf(pmxSeries.currentMicroAmps, pmxSeries.voltageMilliVolts) - 11.757672f) < 0.00001f
                && powerText(wattsOf(pmxSeries.currentMicroAmps, pmxSeries.voltageMilliVolts)).equals("11W"),
                "series correction retains the upstream whole-watt format above 10 W");
        pmxDual.pack = "8920000";
        ColorOsBattery.Sample pmxMeasured = new ColorOsBattery(pmxDual).sample(pmx, -1314, 4, 0);
        check(pmxMeasured.voltageMilliVolts == 8920 && pmxMeasured.voltageSource.equals("bms_pack"),
                "a measured pack voltage still takes precedence after node normalization");
        pmxDual.voltage = "8948";
        ColorOsBattery.Sample pmxPack = new ColorOsBattery(pmxDual).sample(pmx, -1314, 4, 0);
        check(pmxPack.voltageMilliVolts == 8948 && pmxPack.voltageSource.equals("battery_node_mV_pack"),
                "a node already reporting pack voltage is never doubled");

        pmxNodes.voltageDenied = true;
        ColorOsBattery.Sample lost = pmxSampler.sample(pmx, -1314, 4, 24000);
        check(lost.voltageMilliVolts == 0 && lost.voltageSource.equals("unavailable")
                && Float.isNaN(wattsOf(lost.currentMicroAmps, lost.voltageMilliVolts)),
                "permission failure drops the stale voltage and does not pretend that 4 mV is usable");
        reads = pmxNodes.voltageReads;
        pmxNodes.voltageDenied = false;
        check(pmxSampler.sample(pmx, -1314, 4, 27000).voltageMilliVolts == 0
                && pmxNodes.voltageReads == reads, "failed voltage reads respect the retry cooldown");
        check(pmxSampler.sample(pmx, -1314, 4474, 30000).voltageMilliVolts == 4474,
                "a valid broadcast recovers immediately during sysfs cooldown");
        check(pmxSampler.sample(pmx, -1314, 4, 84000).voltageMilliVolts == 4410
                && pmxNodes.voltageReads == reads + 1, "the voltage node can recover after a minute");

        for (String invalid : new String[] {"", "4", "1999", "10001", "4474000", "-4474", "nope",
                "4474\n4475", "2147483648"}) {
            Nodes invalidVoltage = new Nodes();
            invalidVoltage.voltage = invalid;
            ColorOsBattery.Sample invalidSample = new ColorOsBattery(invalidVoltage).sample(pmx, -1314, 4, 0);
            check(invalidSample.voltageMilliVolts == 0, "invalid mV node is not guessed or rescaled: " + invalid);
        }
        Nodes scoped = new Nodes();
        scoped.voltage = "4474";
        String futurePmx = pmx.replace("C.34208f1", "C.new");
        ColorOsBattery.Sample future = new ColorOsBattery(scoped).sample(futurePmx, -1314000, 4474, 0);
        check(future.currentMicroAmps == -1314000 && future.voltageMilliVolts == 4474
                && scoped.voltageReads == 0, "unverified PMX firmware retains Android units");
        ColorOsBattery.Sample otherBroken = new ColorOsBattery(scoped).sample("other firmware", -1314000, 4, 0);
        check(otherBroken.voltageMilliVolts == 4 && scoped.voltageReads == 0,
                "the measured mV-node exception does not become a general voltage heuristic");
        check(nodes.voltageReads == 0 && actual.voltageReads == 0,
                "the existing OnePlus firmware never reads the PMX-specific voltage fallback");
        TrioState.sChargingAttached = false;
        TrioState.sChargePowerW = 0;
        TrioState.sBatteryTempC = 0;
        ColorOsHooks.worker = true;
        publish(true, 5.9f, 32);
        check(!TrioState.sChargingAttached && TrioState.sChargePowerW == 0 && TrioState.sBatteryTempC == 0,
                "background sampling queues the entire UI snapshot without partially publishing it");
        ColorOsHooks.worker = false;
        ColorOsHooks.publish.run();
        check(TrioState.sChargingAttached && TrioState.sChargePowerW == 5.9f && TrioState.sBatteryTempC == 32
                && TrioHooks.invalidations == 1, "the production publisher applies the snapshot on the UI side");
        publish(true, 5.9f, 32);
        check(TrioHooks.invalidations == 1, "an unchanged sample does not cause another redraw");
        System.out.println("ColorOS current units, series-cell detection, pack-voltage selection and upstream rendering passed");
    }
}
'''
harness = harness.replace("@SEPARATOR@", separator.group(1)).replace("@METHODS@", methods)
with tempfile.TemporaryDirectory(prefix="colorduo-power-") as directory:
    # Independent Android/service fixtures for the production AIDL reflection adapter.
    fixtures = {
        "android/os/IBinder.java": """package android.os;
            public interface IBinder { boolean isBinderAlive(); }""",
        "android/os/SystemClock.java": """package android.os;
            public class SystemClock { public static long elapsedRealtime() { return 0; } }""",
        "android/os/ServiceManager.java": """package android.os;
            public class ServiceManager {
                public static IBinder binder;
                public static boolean denied;
                public static int lookups;
                public static IBinder checkService(String name) {
                    lookups++;
                    if (!name.equals("vendor.oplus.hardware.charger.ICharger/default"))
                        throw new AssertionError("Unexpected service: " + name);
                    if (denied) throw new SecurityException("denied");
                    return binder;
                }
            }""",
        "vendor/oplus/hardware/charger/ICharger.java": """package vendor.oplus.hardware.charger;
            import android.os.IBinder;
            public interface ICharger {
                String queryChargeInfo();
                abstract class Stub {
                    public static ICharger asInterface(IBinder binder) { return (ICharger) binder; }
                }
            }""",
        "org/json/JSONObject.java": """package org.json;
            public class JSONObject {
                public JSONObject(String text) {}
                public JSONObject optJSONObject(String key) { return null; }
                public Object opt(String key) { return null; }
            }""",
    }
    fixture_paths = []
    for name, content in fixtures.items():
        path = Path(directory) / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
        fixture_paths.append(str(path))
    test_source = Path(directory) / "ColorOsPowerTest.java"
    test_source.write_text(harness)
    compiler = [shutil.which("javac")] if shutil.which("javac") else ["java", "-m", "jdk.compiler/com.sun.tools.javac.Main"]
    subprocess.run(compiler + ["-encoding", "UTF-8", "-d", directory,
                              str(SOURCE / "Refl.java"), str(SOURCE / "ColorOsPolicy.java"),
                              str(SOURCE / "ColorOsBattery.java"),
                              str(SOURCE / "ColorOsCharger.java"),
                              str(SOURCE / "ColorOsChargerClient.java"),
                              str(SOURCE / "ColorOsCharge.java"), str(SOURCE / "DuoPolicy.java"),
                              str(ROOT / "tests/ColorOsChargeTest.java"),
                              str(ROOT / "tests/ColorOsChargerTest.java"),
                              str(ROOT / "tests/ColorOsChargerClientTest.java"),
                              str(test_source)] + fixture_paths, check=True)
    subprocess.run(["java", "-cp", directory, "io.github.yixing233.hyperduo.ColorOsPowerTest"], check=True)
    subprocess.run(["java", "-cp", directory, "io.github.yixing233.hyperduo.ColorOsChargeTest"], check=True)
    subprocess.run(["java", "-cp", directory, "io.github.yixing233.hyperduo.ColorOsChargerTest"], check=True)
    subprocess.run(["java", "-cp", directory, "io.github.yixing233.hyperduo.ColorOsChargerClientTest"], check=True)
