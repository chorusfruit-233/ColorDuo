/* SPDX-License-Identifier: GPL-3.0-only */
package io.github.yixing233.hyperduo;

import android.os.Handler;
import android.os.Looper;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Serialize generation handoff with native view binding and all UI callbacks. */
final class ColorOsReload {
    static volatile boolean retiring;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final List<Runnable> CLEANUPS = new ArrayList<>();

    static synchronized void watch(Handler handler) {
        CLEANUPS.add(() -> handler.removeCallbacksAndMessages(null));
    }

    static synchronized void watch(Context context, BroadcastReceiver receiver) {
        CLEANUPS.add(() -> context.unregisterReceiver(receiver));
    }

    static synchronized void watch(SharedPreferences prefs,
                                  SharedPreferences.OnSharedPreferenceChangeListener listener) {
        CLEANUPS.add(() -> prefs.unregisterOnSharedPreferenceChangeListener(listener));
    }

    static synchronized void watch(ConnectivityManager manager, ConnectivityManager.NetworkCallback callback) {
        CLEANUPS.add(() -> manager.unregisterNetworkCallback(callback));
    }

    static synchronized void closeCallbacks() {
        // Reverse order closes listeners before the handlers they can enqueue work on.
        RuntimeException failure = null;
        for (int i = CLEANUPS.size() - 1; i >= 0; i--) {
            try { CLEANUPS.get(i).run(); }
            catch (RuntimeException error) { failure = error; }
        }
        CLEANUPS.clear();
        if (failure != null) throw failure;
    }

    static <T> T onMain(Callable<T> action) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) return action.call();
        FutureTask<T> task = new FutureTask<>(action);
        MAIN.post(task);
        try { return task.get(10, TimeUnit.SECONDS); }
        finally { task.cancel(false); }
    }
}
