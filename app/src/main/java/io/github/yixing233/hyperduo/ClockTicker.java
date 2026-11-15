/*
 * Copyright (C) 2026 yixing233 and HyperDuo contributors
 *
 * This file is part of HyperDuo, released under the GNU General Public
 * License v3.0. See the LICENSE file at the repository root, or
 * <https://www.gnu.org/licenses/gpl-3.0.html>.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */

package io.github.yixing233.hyperduo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.format.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** One boundary-aligned clock for all hosts; seconds run only while awake. */
final class ClockTicker {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Runnable TICK = () -> { scheduledAt = -1; refreshNow(); };
    private static Context context;
    private static long scheduledAt = -1, lastBucket = Long.MIN_VALUE, lastInterval;
    private static String formatKey;
    private static java.text.DateFormat formatter;

    static { ColorOsReload.watch(MAIN); }

    private ClockTicker() {}

    static synchronized void start(Context value) {
        if (value == null || context != null || ColorOsReload.retiring) return;
        Context app = value.getApplicationContext();
        context = app == null ? value : app;
        PowerSampling.watch(context, ClockTicker::refreshNow);
        ColorOsReload.watch(ClockTicker::pause, ClockTicker::refreshNow);
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        filter.addAction(Intent.ACTION_LOCALE_CHANGED);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) {
                formatKey = null;
                refreshNow();
            }
        };
        try {
            if (Build.VERSION.SDK_INT >= 33)
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            else context.registerReceiver(receiver, filter);
            ColorOsReload.watch(context, receiver, filter);
        } catch (RuntimeException ignored) { }
        MAIN.post(ClockTicker::refreshNow);
    }

    static void refreshNow() {
        if (ColorOsReload.retiring) { pause(); return; }
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(ClockTicker::refreshNow);
            return;
        }
        try {
            refreshAt(System.currentTimeMillis());
        } catch (RuntimeException unavailable) {
            if (context != null && usesClock(TrioConfig.appearance()))
                schedule(SystemClock.uptimeMillis() + 60_000L);
        }
    }

    /** Wall time drives the display; uptime only drives the Handler deadline. */
    private static void refreshAt(long now) {
        TrioAppearance a = TrioConfig.appearance();
        if (context == null || ColorOsReload.retiring || !usesClock(a)) {
            pause();
            return;
        }
        boolean seconds = a.clockSeconds && PowerSampling.active(context);
        long interval = seconds ? 1_000L : 60_000L;
        Locale locale = Locale.getDefault();
        TimeZone zone = TimeZone.getDefault();
        boolean twentyFour = DateFormat.is24HourFormat(context);
        String key = locale.toLanguageTag() + '|' + zone.getID() + '|' + twentyFour + '|' + seconds;
        long bucket = Math.floorDiv(now, interval);
        if (!key.equals(formatKey)) {
            formatter = seconds
                    ? new SimpleDateFormat(DateFormat.getBestDateTimePattern(locale,
                            twentyFour ? "Hms" : "hms"), locale)
                    : DateFormat.getTimeFormat(context);
            formatter.setTimeZone(zone);
            formatKey = key;
            lastBucket = Long.MIN_VALUE;
        }
        if (bucket != lastBucket || interval != lastInterval) {
            publish(formatter.format(new Date(now)));
            lastBucket = bucket;
            lastInterval = interval;
        }
        schedule(SystemClock.uptimeMillis() + boundaryDelay(now, interval));
    }

    static long boundaryDelay(long now, long interval) {
        return interval - Math.floorMod(now, interval);
    }

    static void pause() {
        schedule(-1);
        lastBucket = Long.MIN_VALUE;
    }

    private static void schedule(long at) {
        if (at == scheduledAt || at >= 0 && scheduledAt >= 0 && Math.abs(at - scheduledAt) <= 1) return;
        MAIN.removeCallbacks(TICK);
        scheduledAt = at;
        if (at >= 0) MAIN.postDelayed(TICK, Math.max(1, at - SystemClock.uptimeMillis()));
    }

    private static boolean usesClock(TrioAppearance a) {
        if (!a.glyph || !a.slotted) return false;
        // Every visible cell may hold time, including a priority-list fallback.
        // Untouched capsules also retain their historical left-hand clock.
        return a.hasRingPart(Prefs.PART_CLOCK)
                || a.capsule && a.ringZones[TrioZones.Zone.LEFT.ordinal()].length == 0
                    && (a.capsuleColumns == 3 || a.ringZones[Prefs.ZONE_CAPSULE_MIDDLE].length == 0);
    }

    private static void publish(String text) {
        if (text == null || text.equals(TrioState.sClockText)) return;
        TrioState.sClockText = text;
        TrioHooks.invalidateHosts();
    }
}
