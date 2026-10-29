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

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
import io.github.libxposed.api.XposedInterface;
import java.util.Map;

/**
 * HyperDuo — folds the Wi-Fi signal and the mobile signal into MIUI's battery
 * icon, drawing one trio glyph (battery ring + Wi-Fi arcs + signal dots) in the
 * 28dp x 20dp slot the battery icon already occupies.
 *
 * <p>Entry point declared in {@code META-INF/xposed/java_init.list}; scope in
 * {@code META-INF/xposed/scope.list}.
 */
public final class HyperDuoModule extends XposedModule {

    private static final String TAG = "ColorDuo";
    private static final int LOG_INFO = 4;
    private static final int LOG_WARN = 5;

    private static final String TARGET_PACKAGE = "com.android.systemui";
    private ClassLoader targetLoader;

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        final String pkg = param.getPackageName();
        if (!TARGET_PACKAGE.equals(pkg)) {
            return;
        }
        try {
            if (Refl.cls(ColorOsHooks.METER_CLASS, param.getClassLoader()) != null) {
                ColorOsHooks.install(this, param.getClassLoader());
                targetLoader = param.getClassLoader();
            } else {
                TrioHooks.install(this, param.getClassLoader());
            }
        } catch (Throwable t) {
            log(LOG_WARN, "install failed: " + t);
        }
    }

    @Override
    public boolean onHotReloading(XposedModuleInterface.HotReloadingParam param) {
        // The inherited MIUI adapter has a different ownership model. Never unload it unsafely.
        if (targetLoader == null || !ColorOsHooks.isInstalled()) return false;
        try {
            Map<String, Object> saved = ColorOsReload.onMain(ColorOsHooks::retire);
            saved.put("loader", targetLoader);
            if (!ColorOsHooks.stopWorkers()) {
                log(LOG_WARN, "hot reload refused: background work has not stopped; retry or restart SystemUI");
                return false;
            }
            // A worker can pass its publish guard just before retire() sets the flag.
            // Drain its final main-looper work after join, before releasing the generation.
            ColorOsReload.onMain(() -> {
                ColorOsHooks.drainRetiredCallbacks();
                return null;
            });
            param.setSavedInstanceState(saved);
            return true;
        } catch (Exception failure) {
            log(LOG_WARN, "hot reload cleanup failed: " + failure);
            return false;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void onHotReloaded(XposedModuleInterface.HotReloadedParam param) {
        param.getOldHookHandles().forEach(XposedInterface.HookHandle::unhook);
        Object state = param.getSavedInstanceState();
        if (!(state instanceof Map)) throw new IllegalStateException("Missing ColorDuo reload state");
        Map<String, Object> saved = (Map<String, Object>) state;
        targetLoader = (ClassLoader) saved.get("loader");
        try {
            ColorOsReload.onMain(() -> {
                ColorOsHooks.install(this, targetLoader);
                ColorOsHooks.resume(saved);
                return null;
            });
            log(LOG_INFO, "ColorOS hot reload complete");
        } catch (Exception failure) {
            throw new IllegalStateException("ColorDuo hot reload failed; restart SystemUI", failure);
        }
    }

    private void log(int priority, String msg) {
        try {
            log(priority, TAG, msg);
        } catch (Throwable ignored) {
            // never let logging break the module
        }
    }
}
