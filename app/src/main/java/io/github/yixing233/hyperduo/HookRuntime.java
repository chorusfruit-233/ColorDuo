/* SPDX-License-Identifier: GPL-3.0-only */
package io.github.yixing233.hyperduo;

import android.content.SharedPreferences;
import java.lang.reflect.Executable;
import java.util.List;

/** Framework boundary. ColorOS hooks use real before/after callbacks on both APIs. */
interface HookRuntime {
    @FunctionalInterface interface Step { void run(Call call) throws Throwable; }
    final class Call {
        final Executable executable;
        Object receiver;
        Object[] args;
        Object result;
        Throwable throwable;
        boolean returnEarly;
        boolean entered;
        Object data;

        Call(Executable executable, Object receiver, Object[] args) {
            this.executable = executable;
            this.receiver = receiver;
            this.args = args;
        }
        void returnValue(Object value) { result = value; throwable = null; returnEarly = true; }
    }

    void hook(Executable method, String id, Step before, Step after);
    void log(int priority, String tag, String message);
    int getApiVersion();

    default SharedPreferences getRemotePreferences(String name) {
        throw new UnsupportedOperationException("This framework uses the settings provider");
    }

    // Retained for the inherited MIUI adapter; it is never installed by the legacy entry point.
    interface Chain {
        Object getThisObject();
        Object getArg(int index);
        List<Object> getArgs();
        Object proceed() throws Throwable;
        Object proceed(Object[] args) throws Throwable;
    }
    @FunctionalInterface interface Hooker { Object intercept(Chain chain) throws Throwable; }
    default void intercept(Executable method, String id, Hooker hooker) {
        throw new UnsupportedOperationException("The legacy build supports ColorOS only");
    }

    default void step(Step step, Call call) {
        if (step == null) return;
        try { step.run(call); }
        catch (Throwable error) { log(5, "ColorDuo", "hook callback " + call.executable + ": " + error); }
    }
}
