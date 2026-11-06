/* SPDX-License-Identifier: GPL-3.0-only */
package io.github.yixing233.hyperduo;

import android.content.SharedPreferences;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Executable;
import java.util.List;

final class ModernRuntime implements HookRuntime {
    private final XposedModule module;
    ModernRuntime(XposedModule module) { this.module = module; }

    @Override public void hook(Executable method, String id, Step before, Step after) {
        module.hook(method).setId(id).intercept(chain -> {
            Call call = new Call(method, chain.getThisObject(), chain.getArgs().toArray());
            step(before, call);
            if (!call.returnEarly) {
                try { call.result = chain.proceed(call.args); }
                catch (Throwable error) { call.throwable = error; }
            }
            call.receiver = chain.getThisObject();
            step(after, call);
            if (call.throwable != null) throw call.throwable;
            return call.result;
        });
    }

    @Override public void intercept(Executable method, String id, Hooker hooker) {
        module.hook(method).setId(id).intercept(chain -> hooker.intercept(new Chain() {
            public Object getThisObject() { return chain.getThisObject(); }
            public Object getArg(int index) { return chain.getArg(index); }
            public List<Object> getArgs() { return chain.getArgs(); }
            public Object proceed() throws Throwable { return chain.proceed(); }
            public Object proceed(Object[] args) throws Throwable { return chain.proceed(args); }
        }));
    }
    @Override public SharedPreferences getRemotePreferences(String name) { return module.getRemotePreferences(name); }
    @Override public int getApiVersion() { return module.getApiVersion(); }
    @Override public void log(int priority, String tag, String message) { module.log(priority, tag, message); }
}
