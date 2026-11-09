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

import android.app.ActivityManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

/**
 * The memory reading, and the repaint that keeps it current.
 *
 * <p>Every other reading in the module arrives as an event: a battery broadcast, a
 * signal level, a tint change. Memory has none. It drifts continuously as processes
 * start and stop, so something has to sample it on a timer or the capsule would sit on
 * whatever percentage it happened to be drawn at when the module loaded.
 *
 * <p>Deliberately shaped like {@link ClockTicker}, and for the same reasons: a static
 * guard so the start is idempotent, one re-posted runnable on the main looper rather
 * than a scheduled executor, and a sample that publishes only when the number moved so
 * an idle phone does not repaint its status bar every interval for nothing.
 *
 * <p><b>The sample is {@link ActivityManager.MemoryInfo}, not {@code /proc/meminfo}.</b>
 * The proc file is readable from an app process on many builds but its access has been
 * restricted release by release, while {@code getMemoryInfo} is the public SDK's own
 * answer and returns the same pair the system uses: {@code totalMem} and the memory
 * still available without killing anything. Usage is the difference as a percentage.
 */
final class MemoryTicker {

    /**
     * How often the reading is sampled.
     *
     * <p>Three seconds: memory moves on the scale of app launches and reclaim passes, so
     * a faster sample mostly re-reads the same number, while a slower one lets a heavy
     * app's footprint appear long after it was opened.
     */
    private static final long INTERVAL_MS = 3_000L;

    private static boolean started;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    // ColorDuo reload lifecycle.
    static { ColorOsReload.watch(MAIN); }
    // End ColorDuo reload lifecycle.

    /**
     * The context the sample runs against, held for the life of the process so a later
     * settings change can publish immediately without walking the view tree.
     */
    private static volatile Context sContext;

    private MemoryTicker() {
    }

    /** Idempotent: the first caller starts the ticker, later ones do nothing. */
    static synchronized void start(final Context context) {
        // ColorDuo reload lifecycle.
        if (ColorOsReload.retiring) return;
        // End ColorDuo reload lifecycle.
        if (started || context == null) {
            return;
        }
        started = true;
        final Context app = context.getApplicationContext();
        sContext = (app != null) ? app : context;
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                tick();
            }
        });
    }

    /**
     * Publishes the reading now, without waiting for the next interval.
     *
     * <p>Called when the settings change: a user who just placed the piece should not
     * watch an empty cell for up to three seconds, which reads as the feature being
     * broken rather than as the ticker not having run yet.
     */
    static void refreshNow() {
        final Context context = sContext;
        if (context == null || !usesMemory()) {
            return;
        }
        publish(sample(context));
    }

    /** One sample, then re-post. */
    private static void tick() {
        // ColorDuo reload lifecycle.
        if (ColorOsReload.retiring) return;
        // End ColorDuo reload lifecycle.
        try {
            final Context context = sContext;
            if (context != null && usesMemory()) {
                publish(sample(context));
            }
        } catch (Throwable ignored) {
            // keep ticking
        } finally {
            // In a finally, deliberately: a ticker that died on one bad frame would leave
            // the reading frozen for the life of the process, which is the one failure
            // this class exists to prevent.
            MAIN.postDelayed(new Runnable() {
                @Override
                public void run() {
                    tick();
                }
            }, INTERVAL_MS);
        }
    }

    /**
     * Whether anything on screen wants the reading.
     *
     * <p>Asks the live settings, not a stored flag: the piece can be placed or removed at
     * any moment, and an install that never uses it should pay one cheap check per
     * interval rather than a sample and an IPC.
     */
    private static boolean usesMemory() {
        return TrioConfig.appearance().hasRingPart(Prefs.PART_MEMORY);
    }

    /**
     * Memory in use, as a percentage, or {@link TrioState#MEMORY_UNKNOWN}.
     *
     * <p>Clamped rather than trusted: {@code availMem} is a snapshot the system itself
     * updates asynchronously, and a frame that landed between two of its passes can read
     * as slightly more available than total. A negative or 100+ percentage would be drawn
     * as-is, so the clamp is what keeps the ink honest.
     */
    private static int sample(Context context) {
        try {
            final ActivityManager am =
                    (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) {
                return TrioState.MEMORY_UNKNOWN;
            }
            final ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(info);
            if (info.totalMem <= 0L) {
                return TrioState.MEMORY_UNKNOWN;
            }
            final long used = info.totalMem - info.availMem;
            final int percent = (int) Math.round(used * 100.0 / info.totalMem);
            return Math.max(0, Math.min(100, percent));
        } catch (Throwable ignored) {
            return TrioState.MEMORY_UNKNOWN;
        }
    }

    /**
     * Stores the reading and repaints, but only when it really changed - the same value
     * comes round on most ticks, and an unconditional invalidate would repaint the status
     * bar every three seconds on installs that are not even showing the number.
     */
    private static void publish(int percent) {
        if (percent < 0 || percent == TrioState.sMemoryPercent) {
            return;
        }
        TrioState.sMemoryPercent = percent;
        TrioHooks.invalidateHosts();
    }
}
