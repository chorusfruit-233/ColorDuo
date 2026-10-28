/* SPDX-License-Identifier: GPL-3.0-only */
package io.github.yixing233.hyperduo;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.concurrent.Executor;

public final class ColorOsChargerTest {
    private static int checks;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static ColorOsCharger.Input input(String current, String voltage, String second,
                                               ColorOsCharger.Topology topology) {
        return new ColorOsCharger.Input("battery_current_now=" + current
                + "\nbattery_voltage_now=" + voltage
                + (second == null ? "" : "\nbattery_voltage_min=" + second), topology);
    }

    private static final class Jobs implements Executor {
        final ArrayDeque<Runnable> jobs = new ArrayDeque<>();
        public void execute(Runnable job) { jobs.add(job); }
        void run() { jobs.remove().run(); }
    }

    private static final class Source implements ColorOsCharger.Reader {
        ColorOsCharger.Input input = input("-1314", "4474000", "4430000", ColorOsCharger.Topology.SERIES);
        int reads;
        boolean denied;
        long[] clock;
        long delay;
        public ColorOsCharger.Input read() throws IOException {
            reads++;
            if (clock != null) clock[0] += delay;
            if (denied) throw new IOException("service permission denied");
            return input;
        }
    }

    private static final class Nodes implements ColorOsBattery.Reader {
        String cells;
        String pack;
        public String read(String path) {
            if (path.equals(ColorOsBattery.CELLS_PATH)) return cells;
            if (path.equals(ColorOsBattery.PACK_VOLTAGE_PATH)) return pack;
            return null;
        }
    }

    public static void main(String[] args) {
        ColorOsCharger.Reading qualcomm = ColorOsCharger.parse(input("-1314", "4474000", "4430000",
                ColorOsCharger.Topology.SERIES));
        check(qualcomm != null && qualcomm.currentMicroAmps == -1314000
                && qualcomm.voltageMilliVolts == 4474 && qualcomm.secondMilliVolts == 4430,
                "vendor mA/uV inputs retain two measured cell voltages");
        ColorOsCharger.Reading mtk = ColorOsCharger.parse(input("-1314", "4474", "4430",
                ColorOsCharger.Topology.SERIES));
        check(mtk != null && mtk.voltageMilliVolts == qualcomm.voltageMilliVolts
                && mtk.currentMicroAmps == qualcomm.currentMicroAmps, "mV and uV inputs normalize identically");
        check(ColorOsCharger.parse(input("0", "4474", null, null)).currentMicroAmps == 0,
                "a genuine zero current is a valid vendor sample");
        check(ColorOsCharger.parse(input("1314", "4474", null, null)).currentMicroAmps == 1314000,
                "either current sign preserves the shared power formula");
        check(ColorOsCharger.parse(input("-1314", "4", null, null)) == null,
                "truncated broadcast-style volts never become a fabricated voltage");
        for (String bad : new String[] {"-2147483648", "2147483647", "-50001", "50001", "-1314000", "", "NaN"}) {
            check(ColorOsCharger.parse(input(bad, "4474", null, null)) == null,
                    "invalid or incompatible current cannot win: " + bad);
        }
        for (String bad : new String[] {"0", "-4474", "1999", "10001", "100000", "10000001", "Infinity"}) {
            check(ColorOsCharger.parse(input("-1314", bad, null, null)) == null,
                    "invalid voltage cannot win: " + bad);
        }
        check(ColorOsCharger.parse(null) == null, "absent service data remains unavailable");
        check(ColorOsCharger.parse(new ColorOsCharger.Input("chargewattage=100", null)) == null,
                "a protocol wattage cannot replace measured current and voltage");
        check(ColorOsCharger.parse(new ColorOsCharger.Input("battery_voltage_now=4474", null)) == null,
                "missing current is not mistaken for a genuine zero");
        check(ColorOsCharger.parse(new ColorOsCharger.Input("x".repeat(16385), null)) == null,
                "oversized service replies are bounded");
        check(ColorOsCharger.parse(new ColorOsCharger.Input("battery_current_now=-1\nbattery_current_now=0\n"
                + "battery_voltage_now=4474", null)) == null, "duplicate current fields are rejected");
        check(ColorOsCharger.parse(input("-1314", "4474", "bad", null)).secondMilliVolts == 0,
                "an invalid optional second voltage does not fabricate another cell");
        check(ColorOsCharger.topology(true, false) == ColorOsCharger.Topology.SERIES, "series feature flag");
        check(ColorOsCharger.topology(false, true) == ColorOsCharger.Topology.PARALLEL, "parallel feature flag");
        check(ColorOsCharger.topology(null, null) == ColorOsCharger.Topology.UNKNOWN, "missing config is unknown");
        check(ColorOsCharger.topology(false, false) == ColorOsCharger.Topology.UNKNOWN,
                "false feature flags alone do not prove the gauge topology");
        check(ColorOsCharger.parse(input("-1314", "4474", null, ColorOsCharger.Topology.CONFLICT)) == null,
                "contradictory feature flags do not select the HAL");

        Nodes nodes = new Nodes();
        nodes.cells = "0,1,0,0,0"; // Driver's startup fallback must not defeat ODM series metadata.
        ColorOsBattery sampler = new ColorOsBattery(nodes);
        ColorOsBattery.Sample series = sampler.sample("new firmware", -1314, 4, 0, qualcomm);
        check(series.dataSource.equals("oplus_hal") && series.currentMicroAmps == -1314000
                && series.voltageMilliVolts == 8904 && series.seriesCells == 2,
                "a validated vendor pair wins over broken framework units without a firmware whitelist");
        check(series.voltageSource.equals("oplus_hal_series_sum") && series.cellsSource.equals("odm_series"),
                "two actual series voltages replace the approximate maximum-cell multiplier");
        String known = "OnePlus/PJZ110/OP5D0DL1:17/CP2A.260605.016/V.487427a-2227f32-224bc4c:user/release-keys";
        check(sampler.sample(known, -1314, 4, 3000, qualcomm).currentMicroAmps == -1314000,
                "known firmware cannot apply its mA multiplier twice to the vendor reading");
        ColorOsCharger.Reading pack = ColorOsCharger.parse(input("-1314", "8904000", "4430000",
                ColorOsCharger.Topology.SERIES));
        check(sampler.sample("new firmware", -1314, 4, 6000, pack).voltageMilliVolts == 8904,
                "an already complete pack voltage is never added or multiplied again");
        Nodes measured = new Nodes();
        measured.pack = "8870000";
        check(new ColorOsBattery(measured).sample("new firmware", -1, 4, 0, qualcomm).voltageMilliVolts == 8870,
                "verified BMS pack voltage retains priority over cell arithmetic");
        ColorOsCharger.Reading missingCell = ColorOsCharger.parse(input("-1314", "4474", null,
                ColorOsCharger.Topology.SERIES));
        check(sampler.sample("new firmware", -1, 4, 9000, missingCell).voltageMilliVolts == 8948,
                "a missing second cell retains the existing series fallback");
        nodes.cells = "0,2,0,0,0";
        ColorOsCharger.Reading parallel = ColorOsCharger.parse(input("-1314", "4474", "4430",
                ColorOsCharger.Topology.PARALLEL));
        ColorOsBattery.Sample parallelFallback = new ColorOsBattery(nodes)
                .sample("new firmware", -1314000, 4474, 0, parallel);
        check(parallelFallback.dataSource.equals("framework") && parallelFallback.voltageMilliVolts == 4474
                && parallelFallback.currentMicroAmps == -1314000 && parallelFallback.seriesCells == 1,
                "parallel branch current is not promoted to total power or given a series multiplier");
        ColorOsBattery.Sample ordinary = new ColorOsBattery(nodes).sample(known, -496, 4465, 0, null);
        check(ordinary.dataSource.equals("framework") && ordinary.currentMicroAmps == -496000
                && ordinary.voltageMilliVolts == 8930, "unavailable vendor data preserves the previous path");

        Jobs jobs = new Jobs();
        Source source = new Source();
        long[] clock = {0};
        ColorOsCharger cache = new ColorOsCharger(source, jobs, () -> clock[0]);
        check(cache.sample(0).reading == null && jobs.jobs.size() == 1 && source.reads == 0,
                "requesting a sample does not perform IPC on the caller");
        cache.sample(1);
        cache.sample(2);
        check(jobs.jobs.size() == 1, "bursts coalesce into one queued query");
        jobs.run();
        check(cache.sample(1000).reading != null && source.reads == 1 && jobs.jobs.isEmpty(),
                "completed data becomes available without another query");
        clock[0] = 3000;
        check(cache.sample(3000).reading != null && jobs.jobs.size() == 1,
                "a refresh uses the previous fresh pair while the next query is in flight");
        check(cache.sample(6001).reading == null && jobs.jobs.size() == 1,
                "a hung query cannot retain stale data or create unbounded tasks");
        source.denied = true;
        clock[0] = 6001;
        jobs.run();
        check(cache.sample(6002).reading == null && jobs.jobs.isEmpty(),
                "permission failure clears the old pair and uses the fallback");
        clock[0] = 66001;
        cache.sample(clock[0]);
        source.denied = false;
        jobs.run();
        check(cache.sample(clock[0]).reading != null, "the source can recover after the failure cooldown");
        clock[0] = 69001;
        cache.sample(clock[0]);
        source.clock = clock;
        source.delay = 6001;
        jobs.run();
        check(cache.sample(clock[0]).reading == null && cache.sample(clock[0]).state.equals("slow"),
                "a response that took too long is not relabelled as a fresh measurement");
        ColorOsCharger rejected = new ColorOsCharger(source, job -> { throw new IllegalStateException(); }, () -> 0);
        check(rejected.sample(0).reading == null && rejected.sample(1).state.equals("executor_unavailable"),
                "worker startup failure cannot escape into SystemUI");
        Jobs connectionJobs = new Jobs();
        Source connectionSource = new Source();
        ColorOsCharger connection = new ColorOsCharger(connectionSource, connectionJobs, () -> 0);
        connection.sample(0);
        connection.invalidate();
        connectionJobs.run();
        check(connection.sample(1).reading == null && connectionJobs.jobs.size() == 1,
                "a late reply from before a plug transition cannot become the new connection's sample");
        connectionJobs.run();
        check(connection.sample(2).reading != null, "a new connection can acquire its own fresh sample");
        connection.invalidate();
        check(connection.sample(3).reading == null, "unplugging immediately invalidates the previous power pair");
        System.out.println("Oplus charger parsing, source selection, series/parallel policy and asynchronous fallback passed: "
                + checks + " checks");
    }
}
