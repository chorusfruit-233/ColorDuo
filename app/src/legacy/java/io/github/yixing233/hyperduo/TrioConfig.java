/* Copyright (C) 2026 jahn2007 and ColorDuo contributors. SPDX-License-Identifier: GPL-3.0-only */
package io.github.yixing233.hyperduo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import java.util.concurrent.CopyOnWriteArrayList;

/** Legacy settings transport: persisted provider snapshots, notification-only broadcasts. */
public final class TrioConfig {
    public interface Listener { void onConfigChanged(); }
    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile TrioSettings snapshot = TrioSettings.defaults();
    private static Handler worker;
    private static Context context;
    private static int frameworkApi;
    private static boolean queued;
    private TrioConfig() {}
    static void install(HookRuntime runtime) { frameworkApi = runtime.getApiVersion(); }
    static void restoreForReload(Bundle saved) { if (saved != null) snapshot = TrioSettings.fromBundle(saved); }
    static TrioSettings get() { return snapshot; }
    static TrioAppearance appearance() { return TrioAppearance.of(snapshot); }
    static boolean debugLog() { return snapshot.debugLog; }
    static void addListener(Listener listener) { if (listener != null) LISTENERS.add(listener); }

    static synchronized void installReceiver(Context supplied) {
        if (context != null) return;
        Context app = supplied.getApplicationContext();
        context = app == null ? supplied : app;
        HandlerThread thread = new HandlerThread("ColorDuo-legacy-settings", android.os.Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        worker = new Handler(thread.getLooper());
        worker.post(new Runnable() {
            @Override public void run() {
                readSnapshot();
                worker.postDelayed(this, 5000L);
            }
        });
        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent intent) { requestRead(); }
            };
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver,
                    new IntentFilter(Prefs.ACTION_RELOAD), Context.RECEIVER_EXPORTED);
            else context.registerReceiver(receiver, new IntentFilter(Prefs.ACTION_RELOAD));
        } catch (RuntimeException error) {
            TrioHooks.log(TrioHooks.LOG_WARN, "legacy settings receiver unavailable; polling remains active: " + error);
        }
    }

    private static synchronized void requestRead() {
        if (worker == null || queued) return;
        queued = true;
        worker.post(() -> {
            synchronized (TrioConfig.class) { queued = false; }
            readSnapshot();
        });
    }

    private static void readSnapshot() {
        try {
            Bundle extras = new Bundle();
            extras.putInt("api", frameworkApi);
            Bundle values = context.getContentResolver().call(LegacySettings.AUTHORITY, "snapshot", null, extras);
            if (values == null || !values.containsKey(Prefs.KEY_ENABLED)) return;
            TrioSettings next = TrioSettings.fromBundle(values);
            MAIN.post(() -> {
                if (next.equals(snapshot)) return;
                snapshot = next;
                for (Listener listener : LISTENERS) {
                    try { listener.onConfigChanged(); }
                    catch (Throwable error) { TrioHooks.log(TrioHooks.LOG_WARN, "legacy config listener: " + error); }
                }
            });
        } catch (RuntimeException error) {
            if (debugLog()) TrioHooks.log(TrioHooks.LOG_WARN, "legacy settings retry: " + error);
        }
    }
}
