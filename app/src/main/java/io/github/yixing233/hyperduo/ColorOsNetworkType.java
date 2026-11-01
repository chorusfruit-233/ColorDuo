/* SPDX-License-Identifier: GPL-3.0-only */
package io.github.yixing233.hyperduo;

import android.content.Context;
import android.os.Build;
import android.telephony.SubscriptionInfo;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyDisplayInfo;
import android.telephony.TelephonyManager;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

/** RAT identity independent of SystemUI's visibility-filtered, WhileSubscribed icon flows. */
final class ColorOsNetworkType {
    private static final Map<Integer, Line> LINES = new HashMap<>();

    static void sync(Context context, List<SubscriptionInfo> subscriptions) {
        if (subscriptions == null || ColorOsReload.retiring) return;
        TelephonyManager manager = context.getSystemService(TelephonyManager.class);
        if (manager == null) return;
        Set<Integer> active = new HashSet<>();
        for (SubscriptionInfo info : subscriptions) {
            if (info == null) continue;
            int id = info.getSubscriptionId();
            active.add(id);
            if (LINES.containsKey(id)) continue;
            Line line = new Line(manager.createForSubscriptionId(id));
            LINES.put(id, line);
            if (Build.VERSION.SDK_INT >= 31) {
                try {
                    line.callback = new DisplayCallback(line);
                    TelephonyCallback callback = line.callback;
                    Executor executor = context.getMainExecutor();
                    line.manager.registerTelephonyCallback(executor, callback);
                    line.registration = ColorOsReload.watch(
                            () -> line.manager.unregisterTelephonyCallback(callback),
                            () -> line.manager.registerTelephonyCallback(executor, callback));
                } catch (RuntimeException unavailable) {
                    line.callback = null;
                }
            }
        }
        for (Integer id : new HashSet<>(LINES.keySet())) {
            if (!active.contains(id)) LINES.remove(id).close();
        }
    }

    static String read(int subscriptionId) {
        Line line = LINES.get(subscriptionId);
        if (line == null) return "";
        int data = 0;
        int voice = 0;
        try { data = line.manager.getDataNetworkType(); } catch (RuntimeException unavailable) { }
        try { voice = line.manager.getVoiceNetworkType(); } catch (RuntimeException unavailable) { }
        // A display callback carries NSA/advanced overrides which raw LTE cannot express.
        // Never revive its old label after an explicit UNKNOWN update.
        return ColorOsPolicy.radioType(line.network, line.override, data, voice);
    }

    static void stop() {
        for (Line line : LINES.values()) line.close();
        LINES.clear();
    }

    private static final class Line {
        final TelephonyManager manager;
        TelephonyCallback callback;
        ColorOsReload.Registration registration;
        int network;
        int override;
        Line(TelephonyManager manager) { this.manager = manager; }
        void close() {
            if (registration != null) registration.release();
            else if (callback != null) manager.unregisterTelephonyCallback(callback);
            registration = null;
            callback = null;
        }
    }

    @android.annotation.TargetApi(31)
    private static final class DisplayCallback extends TelephonyCallback
            implements TelephonyCallback.DisplayInfoListener {
        private final Line line;
        DisplayCallback(Line line) { this.line = line; }
        @Override public void onDisplayInfoChanged(TelephonyDisplayInfo info) {
            if (ColorOsReload.retiring) return;
            line.network = info.getNetworkType();
            line.override = info.getOverrideNetworkType();
            ColorOsHooks.invalidateHosts();
        }
    }
}
