/* Copyright (C) 2026 jahn2007 and ColorDuo contributors. SPDX-License-Identifier: GPL-3.0-only */
package io.github.yixing233.hyperduo;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.lang.reflect.Executable;
import java.util.concurrent.atomic.AtomicInteger;

/** Keeps other modules in the hook chain, including hooks on constructors and native failures. */
final class LegacyRuntime implements HookRuntime {
    private static final AtomicInteger IDS = new AtomicInteger();
    @Override public void hook(Executable method, String id, Step before, Step after) {
        String key = "ColorDuo.call." + IDS.incrementAndGet();
        XposedBridge.hookMethod(method, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Call call = new Call(method, param.thisObject, param.args);
                param.setObjectExtra(key, call);
                step(before, call);
                param.args = call.args;
                if (call.returnEarly) {
                    if (call.throwable != null) param.setThrowable(call.throwable);
                    else param.setResult(call.result);
                }
            }
            @Override protected void afterHookedMethod(MethodHookParam param) {
                Call call = (Call) param.getObjectExtra(key);
                if (call == null) return;
                try {
                    call.receiver = param.thisObject;
                    call.args = param.args;
                    call.result = param.getResult();
                    call.throwable = param.getThrowable();
                    step(after, call);
                    if (call.throwable != null) param.setThrowable(call.throwable);
                    else param.setResult(call.result);
                } finally { param.setObjectExtra(key, null); }
            }
        });
    }
    @Override public int getApiVersion() { return XposedBridge.getXposedVersion(); }
    @Override public void log(int priority, String tag, String message) { XposedBridge.log(tag + ": " + message); }
}
