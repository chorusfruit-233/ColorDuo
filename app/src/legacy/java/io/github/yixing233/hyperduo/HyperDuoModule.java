/* Copyright (C) 2026 jahn2007 and ColorDuo contributors. SPDX-License-Identifier: GPL-3.0-only */
package io.github.yixing233.hyperduo;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class HyperDuoModule implements IXposedHookLoadPackage {
    private boolean installed;
    @Override public synchronized void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        if (installed || !Prefs.SYSTEMUI_PACKAGE.equals(param.packageName)
                || !Prefs.SYSTEMUI_PACKAGE.equals(param.processName)) return;
        HookRuntime runtime = new LegacyRuntime();
        try {
            if (Refl.cls(ColorOsHooks.METER_CLASS, param.classLoader) == null) return;
            installed = true;
            ColorOsHooks.install(runtime, param.classLoader);
        } catch (Throwable error) { runtime.log(5, "ColorDuo", "legacy install failed: " + error); }
    }
}
