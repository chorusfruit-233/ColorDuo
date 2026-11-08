/* SPDX-License-Identifier: GPL-3.0-only */
package io.github.yixing233.hyperduo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.PowerManager;
import android.view.Display;
import android.view.View;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Objects;

/** Panorama AOD keeps the status bar visible; visibility alone cannot permit animation. */
final class ColorOsDoze {
    private static final String AOD_DATA = "com.oplus.systemui.aod.aodclock.constant.AodData";
    private final Class<?> aodClass;
    private final Field singleton, showing;

    ColorOsDoze(ClassLoader loader) {
        aodClass = Refl.cls(AOD_DATA, loader);
        singleton = Refl.field(aodClass, "sAodData");
        showing = Refl.field(aodClass, "mAodIsInShow");
    }

    private Object showing(Object instance) { return Refl.get(showing, instance); }

    int install(HookRuntime module, Runnable repaint) {
        Method setter = Refl.method(aodClass, "setAodIsInShow", boolean.class);
        if (setter == null) return 0;
        try {
            module.hook(setter, "ColorDuo.aodState",
                    call -> call.data = showing(call.receiver),
                    call -> {
                        if (call.throwable == null && !Objects.equals(call.data, showing(call.receiver)))
                            repaint.run();
                    });
            return 1;
        } catch (RuntimeException | LinkageError error) {
            module.log(5, "ColorDuo", "AOD observer unavailable: " + error);
            return 0;
        }
    }

    boolean allowsAnimation(View view) {
        try {
            PowerManager power = view.getContext().getSystemService(PowerManager.class);
            if (power == null || !power.isInteractive()) return false;
            Display display = view.getDisplay();
            if (display == null || display.getState() != Display.STATE_ON) return false;
        } catch (RuntimeException ignored) {
            return false;
        }
        try {
            // Read an existing native singleton. Do not start the AOD subsystem ourselves.
            if (singleton != null && Boolean.TRUE.equals(showing(singleton.get(null)))) return false;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Other firmware still has the platform power/display gates above.
        }
        return true;
    }

    void watch(Context context, Runnable repaint) {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) {
                if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())
                        || Intent.ACTION_SCREEN_ON.equals(intent.getAction())) repaint.run();
            }
        };
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        else context.registerReceiver(receiver, filter);
        ColorOsReload.watch(context, receiver, filter);
    }
}
