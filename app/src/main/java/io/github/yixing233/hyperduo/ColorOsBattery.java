/* SPDX-License-Identifier: GPL-3.0-only */
package io.github.yixing233.hyperduo;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Read-only Oplus battery topology and pack-voltage input normalization. */
final class ColorOsBattery {
    static final String CELLS_PATH = "/sys/class/oplus_chg/battery/aging_ffc_data";
    static final String BATTERY_VOLTAGE_PATH = "/sys/class/power_supply/battery/voltage_now";
    static final String PACK_VOLTAGE_PATH = "/sys/class/power_supply/bms/voltage_now";
    static final long RETRY_MILLIS = 60_000L;

    interface Reader {
        String read(String path) throws IOException;
    }

    static final class Sample {
        final int currentMicroAmps;
        final int voltageMilliVolts;
        final int seriesCells;
        final String cellsSource;
        final String voltageSource;
        final String dataSource;

        Sample(int current, int voltage, int cells, String cellsSource, String voltageSource) {
            this(current, voltage, cells, cellsSource, voltageSource, "framework");
        }

        Sample(int current, int voltage, int cells, String cellsSource, String voltageSource, String dataSource) {
            currentMicroAmps = current;
            voltageMilliVolts = voltage;
            seriesCells = cells;
            this.cellsSource = cellsSource;
            this.voltageSource = voltageSource;
            this.dataSource = dataSource;
        }
    }

    private final Reader reader;
    private int detectedCells;
    private long cellsProbeAt = Long.MIN_VALUE;
    private long packFailureAt = Long.MIN_VALUE;
    private long voltageFailureAt = Long.MIN_VALUE;

    ColorOsBattery() {
        this(ColorOsBattery::readBounded);
    }

    ColorOsBattery(Reader reader) {
        this.reader = reader;
    }

    // Called by BatteryPresence's bounded sampling, never by a drawing callback.
    synchronized Sample sample(String fingerprint, int rawCurrent, int rawVoltage, long elapsedMillis) {
        return sample(fingerprint, rawCurrent, rawVoltage, elapsedMillis, null);
    }

    synchronized Sample sample(String fingerprint, int rawCurrent, int rawVoltage, long elapsedMillis,
                               ColorOsCharger.Reading vendor) {
        // The driver itself returns one cell while its gauge is still unavailable.
        if (detectedCells != 2 && retryDue(cellsProbeAt, elapsedMillis)) {
            cellsProbeAt = elapsedMillis;
            detectedCells = ColorOsPolicy.batterySeriesCells(read(CELLS_PATH));
        }
        int cells = ColorOsPolicy.batterySeriesCells(fingerprint, detectedCells);
        String cellsSource = detectedCells != 0 ? "aging_ffc_data" : cells != 0 ? "firmware" : "unknown";
        if (vendor != null && vendor.topology == ColorOsCharger.Topology.SERIES) {
            cells = 2;
            cellsSource = "odm_series";
        } else if (vendor != null && vendor.topology == ColorOsCharger.Topology.PARALLEL) {
            // The HAL field may be a single branch's current. Until that contract is
            // verified, retain the platform fallback and never apply a series multiplier.
            cells = 1;
            cellsSource = "odm_parallel";
        }
        boolean useVendor = vendor != null && vendor.topology != ColorOsCharger.Topology.PARALLEL
                && vendor.topology != ColorOsCharger.Topology.CONFLICT;
        int inputVoltage = useVendor ? vendor.voltageMilliVolts : rawVoltage;
        String voltageSource = useVendor ? "oplus_hal" : "broadcast";
        if (!useVendor && ColorOsPolicy.batteryVoltageNodeReportsMillivolts(fingerprint)
                && !validMilliVolts(rawVoltage)) {
            // The broadcast has already lost its fractional volts; scaling 4 to 4000
            // cannot recover the measured 4474 mV. Read the verified mV node instead.
            inputVoltage = 0;
            voltageSource = "unavailable";
            if (retryDue(voltageFailureAt, elapsedMillis)) {
                inputVoltage = batteryMilliVolts(read(BATTERY_VOLTAGE_PATH));
                if (inputVoltage == 0) voltageFailureAt = elapsedMillis;
                else voltageSource = "battery_node_mV";
            }
        }
        int measuredPack = 0;
        if (cells == 2 && inputVoltage > 0 && inputVoltage <= 5000
                && retryDue(packFailureAt, elapsedMillis)) {
            measuredPack = packMilliVolts(read(PACK_VOLTAGE_PATH));
            if (measuredPack == 0) packFailureAt = elapsedMillis;
        }
        int voltage = ColorOsPolicy.batteryPackVoltageMilliVolts(inputVoltage, cells, measuredPack);
        if (cells == 2 && inputVoltage > 0 && inputVoltage <= 5000) {
            if (measuredPack > 0) voltageSource = "bms_pack";
            else if (useVendor && vendor.secondMilliVolts > 0) {
                voltage = inputVoltage + vendor.secondMilliVolts;
                voltageSource = "oplus_hal_series_sum";
            } else voltageSource = voltageSource.equals("broadcast") ? "single_cell_x2" : voltageSource + "_x2";
        } else if (cells == 2 && inputVoltage > 5000) {
            voltageSource += "_pack";
        }
        return new Sample(useVendor ? vendor.currentMicroAmps
                        : ColorOsPolicy.batteryCurrentMicroAmps(fingerprint, rawCurrent),
                voltage, cells, cellsSource, voltageSource, useVendor ? "oplus_hal" : "framework");
    }

    private static boolean retryDue(long previous, long now) {
        return previous == Long.MIN_VALUE || now - previous >= RETRY_MILLIS;
    }

    private static boolean validMilliVolts(int value) { return value >= 2000 && value <= 10000; }

    private static int batteryMilliVolts(String value) {
        if (value == null) return 0;
        try {
            int milliVolts = Integer.parseInt(value.trim());
            return validMilliVolts(milliVolts) ? milliVolts : 0;
        } catch (NumberFormatException malformed) {
            return 0;
        }
    }

    private String read(String path) {
        try {
            return reader.read(path);
        } catch (IOException | RuntimeException unavailable) {
            return null;
        }
    }

    private static int packMilliVolts(String value) {
        if (value == null) return 0;
        try {
            long microVolts = Long.parseLong(value.trim());
            // This Linux power_supply node is uV. A cell-only BMS is not a pack reading.
            return microVolts >= 5_001_000L && microVolts <= 10_000_000L
                    ? (int) (microVolts / 1000L) : 0;
        } catch (NumberFormatException malformed) {
            return 0;
        }
    }

    private static String readBounded(String path) throws IOException {
        try (InputStream input = Files.newInputStream(Paths.get(path))) {
            byte[] bytes = new byte[512];
            int length = 0;
            while (length < bytes.length) {
                int count = input.read(bytes, length, bytes.length - length);
                if (count < 0) break;
                if (count == 0) return null;
                length += count;
            }
            if (length == bytes.length && input.read() >= 0) return null;
            return new String(bytes, 0, length, StandardCharsets.UTF_8);
        }
    }
}
