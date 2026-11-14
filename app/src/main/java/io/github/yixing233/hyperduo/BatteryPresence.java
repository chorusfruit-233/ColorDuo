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
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/**
 * Event-driven battery state for the status-bar performance readouts: the
 * charging power and the battery temperature.
 *
 * <p>Why the framework and not the filesystem: this module runs inside SystemUI
 * as {@code android.uid.systemui}, and every kernel-side route is closed to it.
 * {@code /sys/class/power_supply/battery/} refuses even a rooted shell, the
 * health HAL answers {@code FAILED_TRANSACTION} to {@code dumpsys
 * android.hardware.health.IHealth/default}, and {@code dumpsys battery} is a
 * shell tool rather than an API. What is left is the pair the platform itself
 * uses: the sticky {@code ACTION_BATTERY_CHANGED} broadcast for the temperature
 * and the voltage, and {@link BatteryManager} for the current.
 *
 * <p>Copied in shape from {@link WifiPresence}, for the same reasons: the
 * broadcast is sticky and cheap to re-read, a bounded low-frequency watchdog
 * recovers from a delivery the ROM dropped, and nothing here is ever called from
 * the per-frame drawing path.
 */
final class BatteryPresence {

    private static boolean registered;
    private static boolean watchdogStarted;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    // ColorDuo reload lifecycle.
    static { ColorOsReload.watch(MAIN); }
    // End ColorDuo reload lifecycle.
    private static Runnable pendingRefresh;
    private static Context samplingContext;
    private static long nextSampleAt,scheduledAt=-1,lastInterval;
    private static int lastDemand=-1;
    private static final Runnable WATCHDOG=()->{scheduledAt=-1;refreshNow();};
    static void pause(){schedule(-1);nextSampleAt=0;}
    private static void schedule(long at) {
        if(scheduledAt==at)return;
        MAIN.removeCallbacks(WATCHDOG);scheduledAt=at;
        if(at>=0)MAIN.postDelayed(WATCHDOG,Math.max(0,at-SystemClock.uptimeMillis()));
    }
    static void refreshNow() {
        if(ColorOsReload.retiring)return;
        if(Looper.myLooper()!=Looper.getMainLooper()){MAIN.post(BatteryPresence::refreshNow);return;}
        Context context=samplingContext;
        if(!PowerSampling.active(context)) {
            schedule(-1);nextSampleAt=0;lastDemand=-1;
            if(pendingRefresh!=null){MAIN.removeCallbacks(pendingRefresh);pendingRefresh=null;}
            return;
        }
        TrioAppearance a=TrioConfig.appearance();
        boolean readouts=a.perfPower&&TrioState.sChargingAttached
                ||a.perfTemp&&(!a.perfTempChargingOnly||TrioState.sChargingAttached);
        long interval=readouts?3000L:15000L;
        int demand=(a.perfPower?1:0)|(a.perfTemp?2:0)|(a.perfTempChargingOnly?4:0);
        if(interval!=lastInterval||demand!=lastDemand){nextSampleAt=0;lastInterval=interval;lastDemand=demand;}
        long now=SystemClock.uptimeMillis();
        if(now>=nextSampleAt){nextSampleAt=now+interval;refresh(context);}
        schedule(nextSampleAt);
    }

    private BatteryPresence() {}

    static synchronized void start(final Context context) {
        // ColorDuo reload lifecycle.
        if (ColorOsReload.retiring) return;
        // End ColorDuo reload lifecycle.
        if (!watchdogStarted) {
            watchdogStarted=true;
            Context app=context.getApplicationContext();samplingContext=app==null?context:app;
            PowerSampling.watch(samplingContext,BatteryPresence::refreshNow);
            MAIN.post(BatteryPresence::refreshNow);
        }
        if (registered) return;
        final IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ignored, Intent intent) {
                // Re-read the sticky intent rather than trust this delivery's
                // extras: the two must not be able to disagree.
                refresh(context);
                scheduleRefresh(context);
            }
        };
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                // ACTION_BATTERY_CHANGED is a protected broadcast, so only the
                // system can send it; the flag is required for every receiver
                // registered on a modern SDK and does not gate this delivery.
                // Nothing is taken from the extras of a received broadcast, so a
                // forged one could not move the reading either.
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(receiver, filter);
            }
            registered = true;
            // ColorDuo reload lifecycle.
            ColorOsReload.watch(context, receiver, filter);
            // End ColorDuo reload lifecycle.
            refresh(context);
        } catch (RuntimeException ignored) {
            // A ROM that refuses the registration leaves the readouts on their
            // "unknown" state rather than crashing SystemUI.
        }
    }

    // A broadcast can arrive before the charge counter and the current have both
    // settled, so the frame after it is re-read once. The same coalescer serves
    // a burst of deliveries.
    private static void scheduleRefresh(final Context context) {
        // ColorDuo reload lifecycle.
        if (ColorOsReload.retiring || !PowerSampling.active(context)) return;
        // End ColorDuo reload lifecycle.
        MAIN.post(new Runnable() {
            @Override public void run() {
                if(!PowerSampling.active(context))return;
                if (pendingRefresh != null) MAIN.removeCallbacks(pendingRefresh);
                pendingRefresh = new Runnable() {
                    @Override public void run() { pendingRefresh=null;nextSampleAt=0;refreshNow(); }
                };
                MAIN.postDelayed(pendingRefresh, 1000L);
            }
        });
    }

    private static void refresh(Context context) {
        // ColorDuo reload lifecycle.
        if (ColorOsReload.retiring) return;
        // End ColorDuo reload lifecycle.
        if(!TrioConfig.get().enabled)return;
        if (ColorOsHooks.dispatchBatteryRefresh(() -> refresh(context))) return;
        try {
            final Intent battery = context.registerReceiver(null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (battery == null) {
                return;
            }
            final int plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            final int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS,
                    BatteryManager.BATTERY_STATUS_UNKNOWN);
            final int temperature = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE,
                    Integer.MIN_VALUE);
            final int voltage = battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
            // "An external source is attached" is deliberately wider than
            // "status == charging": once the battery is full the platform flips
            // the status to FULL and stops pushing current, and a readout that
            // vanished at 100% would be at its most confusing exactly where a
            // user looks for it.
            final boolean attached = plugged != 0
                    || status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL;
            // ColorOS readouts follow actual charging, not merely an attached cable.
            final boolean readoutActive = ColorOsHooks.isInstalled()
                    ? ColorOsHooks.batteryStatus(status, plugged) : attached;
            final int whole = (temperature == Integer.MIN_VALUE)
                    ? TrioState.TEMP_UNKNOWN
                    : Math.round(temperature / 10f);
            TrioAppearance appearance=TrioConfig.appearance();
            boolean visible=PowerSampling.active(context);
            boolean powerWanted=visible&&appearance.perfPower&&readoutActive;
            int current=powerWanted?currentNow(context):Integer.MIN_VALUE;
            int powerVoltage = voltage;
            // Normalize ROM input units and pack voltage before the shared calculation.
            if (powerWanted && ColorOsHooks.isInstalled()) {
                ColorOsBattery.Sample sample = ColorOsHooks.batterySample(current, voltage);
                current = sample.currentMicroAmps;
                powerVoltage = sample.voltageMilliVolts;
            }
            final float watts = wattsOf(current, powerVoltage);
            boolean tempWanted=visible&&appearance.perfTemp&&(!appearance.perfTempChargingOnly||readoutActive);
            publish(readoutActive, watts, tempWanted?whole:TrioState.TEMP_UNKNOWN);
        } catch (RuntimeException ignored) {
            // Permissions/ROM differences must not crash SystemUI.
        }
    }

    /**
     * The battery current in microamperes, or {@link Integer#MIN_VALUE} when the
     * platform will not answer.
     */
    private static int currentNow(Context context) {
        try {
            final BatteryManager manager = context.getSystemService(BatteryManager.class);
            if (manager == null) {
                return Integer.MIN_VALUE;
            }
            return manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
        } catch (RuntimeException ignored) {
            return Integer.MIN_VALUE;
        }
    }

    /**
     * The power going into the battery, in watts, from its own current and its
     * own voltage.
     *
     * <p>Deliberately the battery's side of the circuit rather than the
     * charger's negotiated limit: the negotiated figure is fixed for the whole
     * session and would sit there unchanged while the real power tapered away,
     * which is the opposite of what a performance readout is for.
     *
     * <p>The sign of {@code current_now} is not portable, so only its magnitude
     * is used. This platform answers with a <em>negative</em> current while the
     * battery is charging - measured at -204000 uA against 4008 mV with USB
     * powered, status charging and the charge counter rising - while other
     * platforms answer positive for the same situation. Reading the sign as
     * "discharging" therefore turned every real charging current into 0 and the
     * status bar sat on "0.0W" until a sampling glitch happened to land on a
     * positive value. The magnitude is the current actually flowing either way,
     * and the caller only draws it while something is attached.
     *
     * <p>Not being able to ask is a different answer, and it is
     * {@link TrioState#POWER_UNKNOWN} rather than 0. {@code getIntProperty}
     * answers {@code Integer.MIN_VALUE} when the platform does not implement the
     * property, and the voltage is absent on a device that does not report it;
     * folding either into 0 would put a "0.0W" next to the temperature that
     * claims a reading the platform never gave. A real zero stays a real zero:
     * no current flowing is 0 W and is drawn as such.
     */
    private static float wattsOf(int currentMicroAmps, int voltageMilliVolts) {
        if (currentMicroAmps == Integer.MIN_VALUE || voltageMilliVolts <= 0) {
            return TrioState.POWER_UNKNOWN;
        }
        return Math.abs(currentMicroAmps) * (voltageMilliVolts / 1000f) / 1000000f;
    }

    private static void publish(boolean attached, float watts, int temperature) {
        if (ColorOsHooks.dispatchBatteryPublish(() -> publish(attached, watts, temperature))) return;
        final boolean beforeAttached = TrioState.sChargingAttached;
        final TrioAppearance appearance=TrioConfig.appearance();
        final String before=TrioHooks.perfText(appearance);
        TrioState.sChargingAttached=attached;TrioState.sChargePowerW=watts;TrioState.sBatteryTempC=temperature;
        if(beforeAttached!=attached||!before.equals(TrioHooks.perfText(appearance)))TrioHooks.invalidateHosts();
    }
}
