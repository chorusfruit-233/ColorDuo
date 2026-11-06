/* Copyright (C) 2026 jahn2007 and ColorDuo contributors. SPDX-License-Identifier: GPL-3.0-only */
package io.github.yixing233.hyperduo;

import android.os.Bundle;
import android.os.SystemClock;

/** The provider and UI share an app process; activation expires and never survives a reboot. */
final class LegacySettings {
    static final String AUTHORITY = BuildConfig.APPLICATION_ID + ".legacy.settings";
    private record Status(long seen, int api) {}
    private static volatile Status current;
    private LegacySettings() {}

    static void heartbeat(int api) { current = new Status(SystemClock.elapsedRealtime(), api); }
    static Bundle status() {
        Status value = current;
        long age = value == null ? -1 : SystemClock.elapsedRealtime() - value.seen();
        Bundle result = new Bundle();
        result.putBoolean("active", age >= 0 && age < 20_000);
        result.putInt("api", value == null ? 0 : value.api());
        return result;
    }
}
