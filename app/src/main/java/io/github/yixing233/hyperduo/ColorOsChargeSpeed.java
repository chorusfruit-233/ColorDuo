/* SPDX-License-Identifier: GPL-3.0-only */
package io.github.yixing233.hyperduo;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Read-only protocol sampling; current and wattage never select a charging tier. */
final class ColorOsChargeSpeed {
    // OnePlus's oplus_configfs.c exposes OPLUS_CHARGER_SUBTYPE as the normalized protocol_type on the common device.
    static final String TYPE_PATH = "/sys/class/oplus_chg/common/protocol_type";
    private static final long POLL_MILLIS = 1000L;
    private static final int MAX_BYTES = 64;

    interface Reader { String read(String path) throws IOException; }

    static final class Sample {
        final ChargeSpeed speed;
        final String subtype;

        Sample(ChargeSpeed speed, String subtype) {
            this.speed = speed;
            this.subtype = subtype;
        }
    }

    static final class NativeSample {
        final ChargeSpeed speed;
        final String flags;

        NativeSample(ChargeSpeed speed, String flags) {
            this.speed = speed;
            this.flags = flags;
        }
    }

    private final Reader reader;
    private long nextPoll = Long.MIN_VALUE;
    private Sample latest = new Sample(null, "unavailable");
    private Class<?> nativeClass;
    private final List<Method> nativeFlags = new ArrayList<>();

    ColorOsChargeSpeed() { this(ColorOsChargeSpeed::readNode); }
    ColorOsChargeSpeed(Reader reader) { this.reader = reader; }

    synchronized void invalidate() {
        latest = new Sample(null, "unavailable");
        nextPoll = Long.MIN_VALUE;
    }

    synchronized Sample sample(boolean charging, int plugged, long now) {
        if (!charging || (plugged & 7) == 0) {
            invalidate();
            return latest;
        }
        if (nextPoll == Long.MIN_VALUE || now >= nextPoll) {
            nextPoll = now + POLL_MILLIS;
            try {
                String text = reader.read(TYPE_PATH);
                Integer type = subtype(text);
                latest = new Sample(type == null ? null : speed(type),
                        type == null ? "unavailable" : Integer.toString(type));
            } catch (IOException | RuntimeException unavailable) {
                // Replace stale data, including the previous charger's protocol.
                latest = new Sample(null, "unavailable");
            }
        }
        return latest;
    }

    private static Integer subtype(String text) {
        if (text == null || text.length() > MAX_BYTES) return null;
        String value = text.trim();
        if (!value.matches("[0-9]{1,3}")) return null;
        try { return Integer.valueOf(value); }
        catch (NumberFormatException malformed) { return null; }
    }

    /** OPLUS_CHARGER_SUBTYPE from OnePlus's charger/v2/include/oplus_chg.h. */
    static ChargeSpeed speed(int subtype) {
        switch (subtype) {
            case 0: return ChargeSpeed.NORMAL;
            case 1: return ChargeSpeed.FLASH; // VOOC
            case 2: return ChargeSpeed.SUPER; // SVOOC
            case 3: // PD
            case 4: // QC
            case 5: // PPS
            case 7: return ChargeSpeed.QUICK; // PE2.0
            case 6: return ChargeSpeed.FLASH; // UFCS
            default: return null;
        }
    }

    /** Discover actual boolean getters, including firmware-specific UFCS/PD flags. */
    synchronized NativeSample nativeSample(Object model) {
        if (model == null) return new NativeSample(null, "unavailable");
        if (nativeClass != model.getClass()) {
            nativeClass = model.getClass();
            nativeFlags.clear();
            for (Method method : nativeClass.getMethods()) {
                String name = method.getName().toLowerCase(Locale.ROOT);
                if (method.getParameterCount() == 0 && !Modifier.isStatic(method.getModifiers())
                        && (method.getReturnType() == boolean.class || method.getReturnType() == Boolean.class)
                        && (name.startsWith("get") || name.startsWith("is"))
                        && (name.contains("charg") || name.contains("vooc") || name.contains("ufcs")
                            || name.contains("pps") || name.equals("getpd") || name.equals("ispd"))) {
                    nativeFlags.add(method);
                }
            }
        }
        ChargeSpeed speed = null;
        StringBuilder flags = new StringBuilder();
        for (Method method : nativeFlags) {
            try {
                Object value = method.invoke(model);
                if (flags.length() > 0) flags.append(',');
                flags.append(method.getName()).append('=').append(value);
                if (!Boolean.TRUE.equals(value)) continue;
                String name = method.getName().toLowerCase(Locale.ROOT);
                ChargeSpeed candidate = name.contains("supervooc") || name.contains("svooc")
                        || name.contains("wirelessvooc") ? ChargeSpeed.SUPER
                        : name.contains("ufcs") || name.contains("vooc") ? ChargeSpeed.FLASH
                        : name.contains("fast") || name.contains("quick") || name.contains("pd")
                            || name.contains("pps") ? ChargeSpeed.QUICK : null;
                if (candidate != null && (speed == null || candidate.ordinal() > speed.ordinal())) {
                    speed = candidate;
                }
            } catch (ReflectiveOperationException | RuntimeException unavailable) {
                // A missing/inaccessible getter does not change other valid flags.
            }
        }
        return new NativeSample(speed, flags.length() == 0 ? "unavailable" : flags.toString());
    }

    private static String readNode(String path) throws IOException {
        try (InputStream stream = Files.newInputStream(Paths.get(path))) {
            byte[] data = new byte[MAX_BYTES];
            int size = 0;
            while (size < data.length) {
                int count = stream.read(data, size, data.length - size);
                if (count < 0) break;
                if (count == 0) return null;
                size += count;
            }
            if (size == data.length && stream.read() >= 0) return null;
            return new String(data, 0, size, StandardCharsets.UTF_8);
        }
    }
}
