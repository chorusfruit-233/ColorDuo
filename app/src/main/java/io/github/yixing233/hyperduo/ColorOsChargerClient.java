/* SPDX-License-Identifier: GPL-3.0-only */
package io.github.yixing233.hyperduo;

import android.os.IBinder;
import android.os.SystemClock;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.function.Supplier;

/** Read-only ColorOS 17 AIDL adapter. No service waits, shell commands or charger writes. */
final class ColorOsChargerClient implements ColorOsCharger.Reader {
    private static final String INTERFACE = "vendor.oplus.hardware.charger.ICharger";
    private static final String DEVICE_CONFIG = "/odm/etc/devices_config/devices_config.json";
    private final ClassLoader loader;
    private final Supplier<ColorOsCharger.Topology> topologySource;
    private IBinder binder;
    private Object service;
    private Method query;
    private ColorOsCharger.Topology topology = ColorOsCharger.Topology.UNKNOWN;
    private long nextConfigProbe = Long.MIN_VALUE;

    ColorOsChargerClient(ClassLoader loader) { this(loader, null); }

    ColorOsChargerClient(ClassLoader loader, Supplier<ColorOsCharger.Topology> topologySource) {
        this.loader = loader;
        this.topologySource = topologySource == null ? this::deviceTopology : topologySource;
    }

    @Override public ColorOsCharger.Input read() throws Exception {
        try {
            if (binder == null || !binder.isBinderAlive()) {
                clear();
                Class<?> manager = Class.forName("android.os.ServiceManager");
                Object found = manager.getDeclaredMethod("checkService", String.class)
                        .invoke(null, INTERFACE + "/default");
                if (!(found instanceof IBinder)) throw new IOException("charger service unavailable");
                binder = (IBinder) found;
                Class<?> type = Class.forName(INTERFACE, false, loader);
                Class<?> stub = Class.forName(INTERFACE + "$Stub", false, loader);
                service = stub.getDeclaredMethod("asInterface", IBinder.class).invoke(null, binder);
                if (service == null) throw new IOException("charger interface unavailable");
                query = type.getMethod("queryChargeInfo");
            }
            Object value = query.invoke(service);
            if (!(value instanceof String)) throw new IOException("charger response unavailable");
            return new ColorOsCharger.Input((String) value, topologySource.get());
        } catch (InvocationTargetException failure) {
            clear();
            Throwable cause = failure.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof LinkageError) throw (LinkageError) cause;
            throw failure;
        } catch (Exception | LinkageError unavailable) {
            clear();
            throw unavailable;
        }
    }

    private void clear() { binder = null; service = null; query = null; }

    private ColorOsCharger.Topology deviceTopology() {
        long now = SystemClock.elapsedRealtime();
        if (nextConfigProbe != Long.MIN_VALUE && now < nextConfigProbe) return topology;
        nextConfigProbe = now + ColorOsCharger.RETRY_MILLIS;
        try (InputStream input = Files.newInputStream(Paths.get(DEVICE_CONFIG))) {
            byte[] data = new byte[65_536];
            int size = 0;
            while (size < data.length) {
                int count = input.read(data, size, data.length - size);
                if (count < 0) break;
                if (count == 0) return topology = ColorOsCharger.Topology.UNKNOWN;
                size += count;
            }
            if (size == data.length && input.read() >= 0) return topology = ColorOsCharger.Topology.UNKNOWN;
            JSONObject charge = new JSONObject(new String(data, 0, size, StandardCharsets.UTF_8))
                    .optJSONObject("charge");
            if (charge == null) return topology = ColorOsCharger.Topology.UNKNOWN;
            Object series = charge.opt("series_dual_battery_support");
            Object parallel = charge.opt("parallel_dual_battery_support");
            topology = ColorOsCharger.topology(series instanceof Boolean ? (Boolean) series : null,
                    parallel instanceof Boolean ? (Boolean) parallel : null);
        } catch (Exception unavailable) {
            topology = ColorOsCharger.Topology.UNKNOWN;
        }
        return topology;
    }
}
