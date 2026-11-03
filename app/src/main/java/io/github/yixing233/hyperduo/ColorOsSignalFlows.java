/* SPDX-License-Identifier: GPL-3.0-only */
package io.github.yixing233.hyperduo;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

/** Keep OEM signal algorithms running while their original views are hidden. */
final class ColorOsSignalFlows {
    private final Map<Object, Subscription> subscriptions = new IdentityHashMap<>();
    private final Consumer<String> warning;
    private boolean paused;

    ColorOsSignalFlows(Consumer<String> warning) { this.warning = warning; }

    void sync(Collection<?> wanted) {
        Map<Object, Boolean> active = new IdentityHashMap<>();
        for (Object flow : wanted) if (flow != null) active.put(flow, Boolean.TRUE);
        for (Object flow : new ArrayList<>(subscriptions.keySet())) {
            if (!active.containsKey(flow)) {
                subscriptions.get(flow).close();
                subscriptions.remove(flow);
            }
        }
        if (paused) return;
        for (Object flow : active.keySet()) {
            if (subscriptions.containsKey(flow)) continue;
            Subscription subscription = new Subscription(flow);
            subscriptions.put(flow, subscription);
            start(subscription);
        }
    }

    /** Never let an uncollected, visibility-filtered cache override the live radio sample. */
    Object read(Object icon, Object cellularIcon) {
        Object current = value(icon);
        return current == null ? value(cellularIcon) : current;
    }

    private Object value(Object flow) {
        Subscription subscription = subscriptions.get(flow);
        return subscription != null && subscription.active()
                ? Refl.callByName(flow, "getValue") : null;
    }

    void pause() {
        ArrayList<Subscription> closed = new ArrayList<>();
        try {
            for (Subscription subscription : subscriptions.values()) {
                subscription.close();
                closed.add(subscription);
            }
        } catch (RuntimeException failure) {
            for (Subscription subscription : closed) start(subscription);
            throw failure;
        }
        paused = true;
    }

    void resume() {
        paused = false;
        for (Subscription subscription : subscriptions.values()) start(subscription);
    }

    void clear() {
        pause();
        subscriptions.clear();
        paused = false;
    }

    private void start(Subscription subscription) {
        try { subscription.open(); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            // R8 and vendor builds may omit coroutine entry points. The host then uses
            // TelephonyManager's periodically refreshed per-SIM levels instead.
            warning.accept("ColorOS signal flow unavailable; using telephony: " + unavailable);
        }
    }

    private static Class<?> nativeType(ClassLoader loader, String namespace, String name)
            throws ClassNotFoundException {
        // This class is kept unoptimized with the hook package. Building the name here
        // prevents R8 from replacing host Kotlin names with this APK's obfuscated names.
        return Class.forName(namespace + "." + name, false, loader);
    }

    private static final class Subscription {
        final Object flow;
        Object job;
        Method cancel;
        Method isActive;

        Subscription(Object flow) { this.flow = flow; }

        void open() throws ReflectiveOperationException {
            if (job != null) return;
            // Resolve all coroutine types through SystemUI's loader. Module Kotlin types
            // have a different identity even when their package names are identical.
            ClassLoader loader = flow.getClass().getClassLoader();
            Class<?> context = nativeType(loader, "kotlin.coroutines", "CoroutineContext");
            Class<?> scope = nativeType(loader, "kotlinx.coroutines", "CoroutineScope");
            Class<?> flowType = nativeType(loader, "kotlinx.coroutines.flow", "Flow");
            if (!nativeType(loader, "kotlinx.coroutines.flow", "StateFlow").isInstance(flow)) {
                throw new IllegalArgumentException("Signal model is not a StateFlow");
            }
            Class<?> jobType = nativeType(loader, "kotlinx.coroutines", "Job");
            cancel = jobType.getMethod("cancel", CancellationException.class);
            isActive = jobType.getMethod("isActive");
            Object empty = nativeType(loader, "kotlin.coroutines", "EmptyCoroutineContext")
                    .getField("INSTANCE").get(null);
            Method makeScope = nativeType(loader, "kotlinx.coroutines", "CoroutineScopeKt")
                    .getMethod("CoroutineScope", context);
            makeScope.setAccessible(true);
            Object nativeScope = makeScope.invoke(null, empty);
            Method launch = nativeType(loader, "kotlinx.coroutines.flow", "FlowKt")
                    .getMethod("launchIn", flowType, scope);
            launch.setAccessible(true);
            job = launch.invoke(null, flow, nativeScope);
        }

        boolean active() {
            try { return job != null && Boolean.TRUE.equals(isActive.invoke(job)); }
            catch (ReflectiveOperationException | RuntimeException unavailable) { return false; }
        }

        void close() {
            if (job == null) return;
            try { cancel.invoke(job, new Object[] {null}); }
            catch (ReflectiveOperationException | RuntimeException failure) {
                throw new IllegalStateException("Cannot release native signal collection", failure);
            }
            job = null;
        }
    }
}
