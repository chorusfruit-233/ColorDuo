/* SPDX-License-Identifier: GPL-3.0-only */
package io.github.yixing233.hyperduo;

/** State rules shared by the ColorOS adapter, live rendering and previews. */
public final class DuoPolicy {
    private DuoPolicy() {}

    public enum Radio { UNKNOWN, AVAILABLE, NO_SIM, NO_SERVICE, REGISTERING, EMERGENCY_ONLY, AIRPLANE }
    public enum Charge { UNKNOWN, DISCONNECTED, CHARGING, PAUSED, FULL }

    // BatteryManager status values. Current magnitude never determines charge state.
    static Charge charge(int status, int plugged) {
        if (status == 2) return Charge.CHARGING;
        if (status == 5) return Charge.FULL;
        if (plugged != 0 && (status == 3 || status == 4)) return Charge.PAUSED;
        if (status == 3 || status == 4) return Charge.DISCONNECTED;
        return Charge.UNKNOWN;
    }

    /** Native PLC bypass is independent of Android's status, which can remain CHARGING. */
    static Charge charge(int status, int plugged, Boolean bypass) {
        // Oplus PLC applies to wired power (AC=1, USB=2). Ignore stale PLC on wireless or unplugged.
        if ((plugged & 3) != 0 && Boolean.TRUE.equals(bypass)) return Charge.PAUSED;
        return charge(status, plugged);
    }

    // ServiceState: a working packet-data registration is service even without voice.
    static Radio service(int voiceRegistration, int dataRegistration, Boolean emergencyOnly) {
        return service(voiceRegistration, dataRegistration, emergencyOnly, null);
    }

    static Radio service(int voiceRegistration, int dataRegistration, Boolean emergencyOnly, Boolean searching) {
        if (voiceRegistration == 0 || dataRegistration == 0) return Radio.AVAILABLE;
        if (Boolean.TRUE.equals(searching)) return Radio.REGISTERING;
        if (Boolean.TRUE.equals(emergencyOnly)) return Radio.EMERGENCY_ONLY;
        if (voiceRegistration == 3 || (voiceRegistration == 1 && dataRegistration > 0)) return Radio.NO_SERVICE;
        return Radio.UNKNOWN;
    }

    static Radio radio(boolean airplane, int subscriptions, Boolean simPresent,
                       int dataSlot, Radio[] services) {
        if (airplane) return Radio.AIRPLANE;
        if (subscriptions < 0) return Radio.UNKNOWN;
        if (subscriptions == 0) {
            // An authoritative empty list means no usable line. A disabled/locked
            // inserted SIM is different from a failed subscription query above.
            return Boolean.FALSE.equals(simPresent) ? Radio.NO_SIM : Radio.NO_SERVICE;
        }
        if (dataSlot >= 0 && dataSlot < services.length) return services[dataSlot];
        for (Radio service : services) if (service == Radio.AVAILABLE) return service;
        for (Radio service : services) if (service != Radio.UNKNOWN) return service;
        return Radio.UNKNOWN;
    }

    static boolean hasNotice(Radio radio) {
        return radio == Radio.NO_SIM || radio == Radio.NO_SERVICE || radio == Radio.EMERGENCY_ONLY;
    }

    static boolean foldMobile(boolean owns, int readings, Radio radio, boolean noticeVisible) {
        if (!owns) return false;
        if (radio == Radio.REGISTERING) return true;
        if (hasNotice(radio)) return noticeVisible;
        return radio == Radio.AVAILABLE && readings > 0;
    }

    static boolean foldSlot(String slot, boolean owns, int readings, Radio radio, boolean noticeVisible) {
        if ("mobile".equals(slot) || "stacked_mobile".equals(slot)) {
            return foldMobile(owns, readings, radio, noticeVisible);
        }
        // ColorOS uses separate status slots when no subscriptions are active.
        boolean noSim = "nosim_all".equals(slot) || "nosim_one".equals(slot) || "nosim_two".equals(slot);
        return noSim && foldMobile(owns, readings, radio, noticeVisible);
    }

    /** Registration commonly passes through emergency-only before normal service. */
    static final class RadioStability {
        static final long REGISTRATION_HOLD_MILLIS = 3000L;
        private String registration;
        private Radio candidate = Radio.UNKNOWN;
        private long warningSince = Long.MIN_VALUE;

        Radio filter(Radio reported, String registrationKey, boolean activeLine, long now) {
            boolean transientWarning = reported == Radio.EMERGENCY_ONLY
                    || (activeLine && reported == Radio.NO_SERVICE);
            if (!transientWarning) {
                registration = null;
                candidate = Radio.UNKNOWN;
                warningSince = Long.MIN_VALUE;
                return reported;
            }
            if (warningSince == Long.MIN_VALUE || candidate != reported
                    || !java.util.Objects.equals(registration, registrationKey)) {
                registration = registrationKey;
                candidate = reported;
                warningSince = now;
            }
            return now - warningSince >= REGISTRATION_HOLD_MILLIS ? reported : Radio.REGISTERING;
        }
    }

    /** Automatic rows follow the data line; named SIM components keep physical slots. */
    static int primarySlot(int dataSlot, int[] levels) {
        if (levels == null || levels.length < 2) return -1;
        if (dataSlot >= 0 && dataSlot < 2 && levels[dataSlot] >= 0) return dataSlot;
        return levels[0] >= 0 ? 0 : levels[1] >= 0 ? 1 : -1;
    }

    static int[] automaticLevels(int[] levels, int dataSlot) {
        if (levels == null || levels.length < 2) return levels;
        return primarySlot(dataSlot, levels) == 1
                ? new int[] {levels[1], levels[0]} : levels;
    }

    static int pausedColor(int foreground) { return (foreground & 0xff000000) | 0x008e8e93; }
}
