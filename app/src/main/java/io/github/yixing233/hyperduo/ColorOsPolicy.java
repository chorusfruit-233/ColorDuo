/* SPDX-License-Identifier: GPL-3.0-only */
package io.github.yixing233.hyperduo;

import java.util.HashSet;
import java.util.Collection;
import java.util.Set;

/** ROM-independent rules used by the ColorOS adapter. */
final class ColorOsPolicy {
    private ColorOsPolicy() {}

    // Verified with BatteryPropertiesRegistrar.getProperty(CURRENT_NOW), the OPLUS
    // BatteryService dump and the current_now node. Other builds keep the Android uA contract.
    private static final String ONEPLUS_FIRMWARE =
            "OnePlus/PJZ110/OP5D0DL1:17/CP2A.260605.016/V.487427a-2227f32-224bc4c:user/release-keys";
    private static final String OPPO_PMX_FIRMWARE =
            "OPPO/PMX110/OP659FL1:17/CP2A.260605.016/C.34208f1-dbc5d7-d921b4:user/release-keys";

    static boolean batteryReportsMilliamps(String fingerprint) {
        return ONEPLUS_FIRMWARE.equals(fingerprint) || OPPO_PMX_FIRMWARE.equals(fingerprint);
    }

    // Issue #5: this firmware exposes 4474 mV in sysfs but broadcasts only 4 mV.
    static boolean batteryVoltageNodeReportsMillivolts(String fingerprint) {
        return OPPO_PMX_FIRMWARE.equals(fingerprint);
    }

    static int batteryCurrentMicroAmps(String fingerprint, int reported) {
        if (reported == Integer.MIN_VALUE || !batteryReportsMilliamps(fingerprint)) return reported;
        try {
            return Math.multiplyExact(reported, 1000);
        } catch (ArithmeticException overflow) {
            return Integer.MIN_VALUE;
        }
    }

    /** Oplus aging_ffc_data: version, series-cell count, reserved, debug cycles, cycles. */
    static int batterySeriesCells(String agingFfcData) {
        if (agingFfcData == null) return 0;
        String[] fields = agingFfcData.trim().split(",", -1);
        if (fields.length < 5 || (fields.length - 5) % 2 != 0) return 0;
        try {
            for (String field : fields) {
                Integer.parseInt(field.trim());
            }
            if (Integer.parseInt(fields[0].trim()) < 0 || Integer.parseInt(fields[4].trim()) < 0) return 0;
            if (Integer.parseInt(fields[2].trim()) != 0) return 0;
            int cells = Integer.parseInt(fields[1].trim());
            return cells == 1 || cells == 2 ? cells : 0;
        } catch (NumberFormatException malformed) {
            return 0;
        }
    }

    /** Only the reference OnePlus 13 firmware has a verified two-cell hardware fallback. */
    static int batterySeriesCells(String fingerprint, int detected) {
        if (detected == 1 || detected == 2) return detected;
        return ONEPLUS_FIRMWARE.equals(fingerprint) ? 2 : 0;
    }

    /** Do not multiply a pack voltage, a parallel pack or an unknown topology. */
    static int batteryPackVoltageMilliVolts(int reported, int seriesCells, int measuredPack) {
        if (seriesCells != 2 || reported <= 0 || reported > 5000) return reported;
        if (measuredPack > 5000 && measuredPack <= 10000) return measuredPack;
        return reported * 2;
    }

    static int mobileLevel(int level, int numberOfLevels, boolean inService) {
        if (!inService) return 0;
        if (level < 0 || numberOfLevels < 2) return -1;
        return Math.round(Math.min(level, numberOfLevels - 1) * 4f / (numberOfLevels - 1));
    }

    /** Read the backing field first: OEM callers can inline a getter completely. */
    static Object nativeMember(Object owner, String fieldName, String getterName) {
        if (owner == null) return null;
        java.lang.reflect.Field field = Refl.field(owner.getClass(), fieldName);
        return field != null ? Refl.get(field, owner) : Refl.callByName(owner, getterName);
    }

    /** Native ColorOS labels are models, rather than CharSequences like the upstream input. */
    static String networkType(Object model) {
        if (model instanceof CharSequence) return model.toString();
        Object mapped = nativeMember(model, "networkTypeTextModel", "getNetworkTypeTextModel");
        if (mapped != null) model = mapped;
        Object text = nativeMember(model, "networkTypeText", "getNetworkTypeText");
        if (text instanceof CharSequence) return text.toString();
        if (text == null) text = model;
        Object prefix = nativeMember(text, "prefix", "getPrefix");
        Object suffix = nativeMember(text, "suffix", "getSuffix");
        return (prefix instanceof CharSequence ? prefix.toString() : "")
                + (suffix instanceof CharSequence ? suffix.toString() : "");
    }

    /** Android network/override constants, kept numeric so these rules run without Android. */
    static String radioType(int displayNetwork, int override, int data, int voice) {
        int network = data != 0 ? data : displayNetwork != 0 ? displayNetwork : voice;
        if (displayNetwork != 0 && (data == 0 || data == displayNetwork)) {
            if (override == 3) return "5G";       // NR_NSA
            if (override == 5) return "5G+";      // NR_ADVANCED
            if (override == 1 || override == 2) return "4G+"; // LTE_CA / LTE_ADVANCED_PRO
        }
        return switch (network) {
            case 1 -> "G";
            case 2 -> "E";
            case 3, 5, 6, 12, 17 -> "3G";
            case 4, 7 -> "1X";
            case 8, 9, 10 -> "H";
            case 15 -> "H+";
            case 13 -> "4G";
            case 19 -> "4G+";
            case 20 -> "5G";
            case 16 -> "G";
            default -> "";
        };
    }

    static String chooseNetworkType(String rendered, String mapped, String wrapper,
                                    String cellular, String radio) {
        for (String label : new String[] {rendered, mapped, wrapper, cellular, radio}) {
            if (label != null && !label.isEmpty()) return label;
        }
        return "";
    }

    /** A cold repository must not suppress a working live connection or rendered Wi-Fi model. */
    static int wifiLevel(Boolean connected, int platformLevel, Boolean renderedConnected,
                         int renderedLevel, int repositoryLevel, boolean carrierMerged) {
        if (carrierMerged || Boolean.FALSE.equals(connected)) return -1;
        if (Boolean.FALSE.equals(renderedConnected) && !Boolean.TRUE.equals(connected)) return -1;
        if (validWifiLevel(renderedLevel)) return renderedLevel;
        if (validWifiLevel(repositoryLevel)) return repositoryLevel;
        return Boolean.TRUE.equals(connected) && validWifiLevel(platformLevel) ? platformLevel : -1;
    }

    private static boolean validWifiLevel(int level) { return level >= 0 && level <= 4; }

    static boolean wifiPresent(Boolean connected, int level, Boolean rendered, boolean carrierMerged) {
        if (carrierMerged) return false;
        if (connected != null) return connected;
        return level >= 0 || Boolean.TRUE.equals(rendered);
    }

    /** ColorOS strips InnerClass annotations, so getSimpleName() is not a reliable type test. */
    static int wifiModelLevel(Class<?> activeType, Object model) {
        if (activeType == null || !activeType.isInstance(model)) return -1;
        Object value;
        try {
            java.lang.reflect.Method getter = model.getClass().getMethod("getLevel");
            getter.setAccessible(true);
            value = getter.invoke(model);
        } catch (ReflectiveOperationException | RuntimeException missingGetter) {
            try {
                java.lang.reflect.Field field = model.getClass().getDeclaredField("level");
                field.setAccessible(true);
                value = field.get(model);
            } catch (ReflectiveOperationException | RuntimeException missingField) {
                return -1;
            }
        }
        int level = value instanceof Number ? ((Number) value).intValue() : -1;
        return validWifiLevel(level) ? level : -1;
    }

    /** Remembers only slots this adapter added; existing block lists belong to their owners. */
    static final class SlotOwnership {
        private final Set<String> added = new HashSet<>();

        boolean sync(Collection<String> nativeSlots, Set<String> requested) {
            boolean changed = false;
            for (String slot : new HashSet<>(added)) {
                if (!requested.contains(slot)) {
                    changed |= nativeSlots.remove(slot);
                    added.remove(slot);
                }
            }
            for (String slot : requested) {
                if (!nativeSlots.contains(slot) && nativeSlots.add(slot)) {
                    added.add(slot);
                    changed = true;
                }
            }
            return changed;
        }
    }
}
