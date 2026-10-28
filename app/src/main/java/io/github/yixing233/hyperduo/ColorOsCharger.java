/* SPDX-License-Identifier: GPL-3.0-only */
package io.github.yixing233.hyperduo;

import java.io.StringReader;
import java.util.Properties;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;

/** Validated Oplus charger observations. One asynchronous query; stale data never wins. */
final class ColorOsCharger {
    static final long POLL_MILLIS = 3000L;
    static final long MAX_AGE_MILLIS = 6000L;
    static final long RETRY_MILLIS = 60_000L;
    private static final int MAX_CHARS = 16_384;

    enum Topology { UNKNOWN, SERIES, PARALLEL, CONFLICT }

    interface Reader { Input read() throws Exception; }

    static final class Input {
        final String properties;
        final Topology topology;

        Input(String properties, Topology topology) {
            this.properties = properties;
            this.topology = topology == null ? Topology.UNKNOWN : topology;
        }
    }

    static final class Reading {
        final int currentMicroAmps;
        final int voltageMilliVolts;
        final int secondMilliVolts;
        final Topology topology;

        Reading(int current, int voltage, int second, Topology topology) {
            currentMicroAmps = current;
            voltageMilliVolts = voltage;
            secondMilliVolts = second;
            this.topology = topology;
        }
    }

    static final class Observation {
        final Reading reading;
        final String state;

        Observation(Reading reading, String state) { this.reading = reading; this.state = state; }
    }

    private final Reader reader;
    private final Executor executor;
    private final LongSupplier clock;
    private Reading reading;
    private long sampledAt;
    private long nextQuery = Long.MIN_VALUE;
    private boolean inFlight;
    private long generation;
    private String state = "unavailable";

    ColorOsCharger(Reader reader, Executor executor, LongSupplier clock) {
        this.reader = reader;
        this.executor = executor;
        this.clock = clock;
    }

    synchronized Observation sample(long now) {
        if (!inFlight && (nextQuery == Long.MIN_VALUE || now >= nextQuery)) {
            inFlight = true;
            nextQuery = now + POLL_MILLIS;
            if (reading == null) state = "pending";
            long requestGeneration = generation;
            try { executor.execute(() -> query(requestGeneration)); }
            catch (RuntimeException unavailable) {
                inFlight = false;
                reading = null;
                state = "executor_unavailable";
                nextQuery = now + RETRY_MILLIS;
            }
        }
        boolean fresh = reading != null && now >= sampledAt && now - sampledAt <= MAX_AGE_MILLIS;
        return new Observation(fresh ? reading : null, reading != null && !fresh ? "stale" : state);
    }

    synchronized void invalidate() {
        generation++;
        reading = null;
        state = "connection_changed";
        nextQuery = Long.MIN_VALUE;
    }

    private void query(long requestGeneration) {
        long started = clock.getAsLong();
        Reading next = null;
        String result;
        try {
            next = parse(reader.read());
            result = next == null ? "invalid" : "ready";
        } catch (Exception | LinkageError unavailable) {
            result = unavailable.getClass().getSimpleName();
        }
        long finished = clock.getAsLong();
        if (finished < started || finished - started > MAX_AGE_MILLIS) {
            next = null;
            result = "slow";
        }
        synchronized (this) {
            if (generation != requestGeneration) {
                inFlight = false;
                nextQuery = Long.MIN_VALUE;
                return;
            }
            reading = next;
            sampledAt = started;
            state = result;
            if (next == null) nextQuery = finished + RETRY_MILLIS;
            inFlight = false;
        }
    }

    static Topology topology(Boolean series, Boolean parallel) {
        if (Boolean.TRUE.equals(series) && Boolean.TRUE.equals(parallel)) return Topology.CONFLICT;
        if (Boolean.TRUE.equals(series)) return Topology.SERIES;
        if (Boolean.TRUE.equals(parallel)) return Topology.PARALLEL;
        return Topology.UNKNOWN;
    }

    static Reading parse(Input input) {
        if (input == null || input.properties == null || input.properties.isBlank()
                || input.properties.length() > MAX_CHARS) return null;
        try {
            Properties values = new Properties() {
                @Override public synchronized Object put(Object key, Object value) {
                    if (containsKey(key) && ("battery_current_now".equals(key)
                            || "battery_voltage_now".equals(key) || "battery_voltage_min".equals(key))) {
                        throw new IllegalArgumentException("duplicate battery input");
                    }
                    return super.put(key, value);
                }
            };
            values.load(new StringReader(input.properties));
            long current = Long.parseLong(values.getProperty("battery_current_now").trim());
            // This vendor interface reports mA, unlike the Android BatteryManager contract.
            // Reject incompatible/unavailable values; do not guess units from current magnitude.
            if (current < -50_000L || current > 50_000L) return null;
            int voltage = voltageMilliVolts(values.getProperty("battery_voltage_now"));
            if (voltage == 0 || input.topology == Topology.CONFLICT) return null;
            int second = voltageMilliVolts(values.getProperty("battery_voltage_min"));
            if (second > 5000) second = 0;
            return new Reading(Math.toIntExact(current * 1000L), voltage, second, input.topology);
        } catch (Exception malformed) {
            return null;
        }
    }

    /** Cell/pack mV and uV ranges do not overlap; truncated volts are never scaled back. */
    private static int voltageMilliVolts(String text) {
        if (text == null) return 0;
        try {
            long value = Long.parseLong(text.trim());
            if (value >= 2000L && value <= 10000L) return (int) value;
            if (value >= 2_000_000L && value <= 10_000_000L) return (int) (value / 1000L);
        } catch (NumberFormatException malformed) { /* unavailable */ }
        return 0;
    }
}
