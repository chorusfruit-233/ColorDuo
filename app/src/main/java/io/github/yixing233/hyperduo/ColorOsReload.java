/* SPDX-License-Identifier: GPL-3.0-only */
package io.github.yixing233.hyperduo;

import android.os.Handler;
import android.os.Looper;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.net.ConnectivityManager;
import android.net.NetworkRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Serialize generation handoff with native view binding and all UI callbacks. */
final class ColorOsReload {
    static volatile boolean retiring;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final List<Registration> CALLBACKS = new ArrayList<>();
    private static final List<Handler> HANDLERS = new ArrayList<>();

    /** Keep unregister and re-register paired so a refused reload can resume the old code. */
    static final class Registration {
        final Runnable close, open;
        boolean active = true;
        Registration(Runnable close, Runnable open) { this.close = close; this.open = open; }
        void release() {
            synchronized (ColorOsReload.class) {
                if (active) { close.run(); active = false; }
                CALLBACKS.remove(this);
            }
        }
    }

    static synchronized Registration watch(Runnable close, Runnable open) {
        Registration registration = new Registration(close, open);
        CALLBACKS.add(registration);
        return registration;
    }

    static synchronized void watch(Handler handler) {
        HANDLERS.add(handler);
    }

    static void watch(Context context, BroadcastReceiver receiver, IntentFilter filter) {
        watch(() -> context.unregisterReceiver(receiver), () -> {
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            else context.registerReceiver(receiver, filter);
        });
    }

    static synchronized void watch(SharedPreferences prefs,
                                  SharedPreferences.OnSharedPreferenceChangeListener listener) {
        watch(() -> prefs.unregisterOnSharedPreferenceChangeListener(listener),
                () -> prefs.registerOnSharedPreferenceChangeListener(listener));
    }

    static void watch(ConnectivityManager manager, ConnectivityManager.NetworkCallback callback,
                      NetworkRequest request) {
        watch(() -> manager.unregisterNetworkCallback(callback),
                () -> manager.registerNetworkCallback(request, callback));
    }

    static synchronized void closeCallbacks() {
        List<Registration> closed = new ArrayList<>();
        try {
            for (int i = CALLBACKS.size() - 1; i >= 0; i--) {
                Registration registration = CALLBACKS.get(i);
                if (!registration.active) continue;
                registration.close.run();
                registration.active = false;
                closed.add(registration);
            }
        } catch (RuntimeException failure) {
            // Do not cancel ticking handlers when an external registration cannot be released.
            for (int i = closed.size() - 1; i >= 0; i--) {
                Registration registration = closed.get(i);
                try { registration.open.run(); registration.active = true; }
                catch (RuntimeException rollback) { failure.addSuppressed(rollback); }
            }
            throw failure;
        }
        for (Handler handler : HANDLERS) handler.removeCallbacksAndMessages(null);
        CALLBACKS.clear();
        HANDLERS.clear();
    }

    static <T> T onMain(Callable<T> action) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) return action.call();
        FutureTask<T> task = new FutureTask<>(action);
        MAIN.post(task);
        try { return task.get(10, TimeUnit.SECONDS); }
        finally { task.cancel(false); }
    }
}
