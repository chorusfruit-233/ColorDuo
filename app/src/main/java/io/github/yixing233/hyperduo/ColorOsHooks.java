/*
 * Copyright (C) 2026 ColorDuo contributors
 * Based on HyperDuo by yixing233 and contributors.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package io.github.yixing233.hyperduo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.telephony.SubscriptionInfo;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.ImageView;
import android.widget.LinearLayout;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** ColorOS 17 adapter, verified against OnePlus SystemUI 17.99.02 (API 37). */
final class ColorOsHooks {
    static final String METER_CLASS =
            "com.oplus.systemui.statusbar.pipeline.battery.ui.view.StatBatteryMeterView";
    private static final String BATTERY = "com.oplus.systemui.statusbar.pipeline.battery.";
    private static final String WIFI = "com.android.systemui.statusbar.pipeline.wifi.";
    private static final String MOBILE = "com.oplus.systemui.statusbar.pipeline.mobile.ui.viewmodel.";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    // Views retain their Host as an attach listener. Neither side of this lookup roots a view tree.
    private static final Map<View, WeakReference<Host>> HOSTS = new WeakHashMap<>();
    private static final Map<View, WeakReference<Host>> CHILDREN = new WeakHashMap<>();
    private static final Map<Integer, Mobile> MOBILES = new ConcurrentHashMap<>();
    private static final ColorOsSignalFlows SIGNAL_FLOWS = new ColorOsSignalFlows(
            message -> TrioHooks.log(TrioHooks.LOG_WARN, message));
    private static volatile Object wifiFlow;
    private static volatile WifiSample renderedWifi = WifiSample.UNKNOWN;
    private static Class<?> wifiActiveClass;
    private static Class<?> wifiCarrierMergedClass;
    private static final Set<Class<?>> disconnectedWifiClasses = new HashSet<>();
    private static volatile Object batteryLevelFlow;
    private static volatile Object chargeFlow;
    private static volatile Object savingFlow;
    private static volatile boolean installed;
    private static boolean refreshingMirrors;
    private static final View.OnAttachStateChangeListener MIRROR_ATTACH = new View.OnAttachStateChangeListener() {
        @Override public void onViewAttachedToWindow(View view) {
            ColorOsSizing.mirrorAttached((ViewGroup)view,true);
            refreshMirrorSizes();
        }
        @Override public void onViewDetachedFromWindow(View view) {
            ColorOsSizing.mirrorAttached((ViewGroup)view,false);
            refreshMirrorSizes();
        }
    };
    private static boolean refreshQueued;
    private static boolean tickerStarted;
    private static Class<?> meterClass;
    private static Class<?> phoneBarClass;
    private static Class<?> statusIconsClass;
    private static Context context;
    private static ColorOsDoze doze;
    private static String lastBatterySample;
    private static String lastBatterySource;
    private static Handler batteryWorker;
    private static HandlerThread batteryThread;
    private static ExecutorService chargerWorker;
    private static boolean batteryRefreshQueued;
    private static boolean batteryReadActive;
    private static boolean reloadPreparing;
    private static boolean batteryWorkerStopped;
    private static final List<Thread> stoppingBatteryThreads = new ArrayList<>();
    private static final List<ExecutorService> stoppingChargerWorkers = new ArrayList<>();
    private static ClassLoader chargerLoader;
    private static ColorOsCharger charger;
    private static long nextSimPoll;
    private static final ColorOsLayout.Tree<View> VIEW_TREE = new ColorOsLayout.Tree<>() {
        @Override public View parent(View view) {
            return view.getParent() instanceof View ? (View) view.getParent() : null;
        }
        @Override public String id(View view) { return resourceName(view); }
        @Override public int childCount(View view) {
            return view instanceof ViewGroup ? ((ViewGroup) view).getChildCount() : 0;
        }
        @Override public View childAt(View view, int index) { return ((ViewGroup) view).getChildAt(index); }
        @Override public boolean isIconContainer(View view) {
            return statusIconsClass != null && statusIconsClass.isInstance(view);
        }
    };

    private ColorOsHooks() {}

    static boolean isInstalled() { return installed; }

    static void drainRetiredCallbacks() { MAIN.removeCallbacksAndMessages(null); }

    /** A busy sampler declines before touching views, callbacks or worker executors. */
    static synchronized boolean prepareReload() {
        stoppingBatteryThreads.removeIf(thread -> !thread.isAlive());
        stoppingChargerWorkers.removeIf(ExecutorService::isTerminated);
        if (reloadPreparing || batteryReadActive || !stoppingBatteryThreads.isEmpty()
                || !stoppingChargerWorkers.isEmpty() || (charger != null && !charger.isIdle())) return false;
        reloadPreparing = true;
        if (batteryWorker != null) batteryWorker.removeCallbacksAndMessages(null);
        batteryRefreshQueued = false;
        return true;
    }

    /** Called on the main looper; no destructive changes before state validation succeeds. */
    static Map<String, Object> snapshotForReload() {
        Map<String, Object> saved = new HashMap<>();
        saved.put("settings", TrioConfig.get().toBundle());
        saved.put("context", context);
        saved.put("wifi", wifiFlow);
        saved.put("wifiSample", new Object[] {renderedWifi.connected, renderedWifi.level,
                renderedWifi.carrierMerged, renderedWifi.kind});
        saved.put("battery", new Object[] {batteryLevelFlow, chargeFlow, savingFlow});
        Map<Integer, Object[]> mobiles = new HashMap<>();
        for (Map.Entry<Integer, Mobile> entry : MOBILES.entrySet()) {
            Mobile m = entry.getValue();
            mobiles.put(entry.getKey(), new Object[] {m.icon, m.type, m.cellularIcon,
                    m.cellularType, m.iconGroup, m.inService, m.renderedType});
        }
        saved.put("mobiles", mobiles);
        List<Object[]> meters = new ArrayList<>();
        for (WeakReference<Host> reference : new ArrayList<>(HOSTS.values())) {
            Host h = reference.get();
            if (h == null) continue;
            meters.add(new Object[] {h.meter, h.viewModel});
        }
        saved.put("meters", meters);
        return saved;
    }

    static void retire() {
        ColorOsReload.retiring = true;
        try {
            for (WeakReference<Host> reference : new ArrayList<>(HOSTS.values())) {
                Host h = reference.get();
                if (h == null) continue;
                h.onViewDetachedFromWindow(h.meter);
                h.meter.removeOnAttachStateChangeListener(h);
                h.glyph.setCallback(null);
            }
            // Failed unregister operations restore already released registrations. Handlers
            // are cancelled only after all external registrations have closed successfully.
            ColorOsReload.closeCallbacks();
            ColorOsNetworkType.stop();
            SIGNAL_FLOWS.clear();
            stopMirrors();
            MAIN.removeCallbacksAndMessages(null);
        } finally {
            HOSTS.clear();
            CHILDREN.clear();
        }
    }

    static void cancelReload(Map<String, Object> saved) {
        boolean detached = ColorOsReload.retiring;
        ColorOsReload.retiring = false;
        synchronized (ColorOsHooks.class) {
            reloadPreparing = false;
            batteryRefreshQueued = false;
            if (batteryThread != null && !batteryThread.isAlive()) {
                batteryWorker = null;
                batteryThread = null;
            } else if (batteryThread != null && batteryWorkerStopped) {
                stoppingBatteryThreads.add(batteryThread);
                batteryWorker = null;
                batteryThread = null;
            }
            if (chargerWorker != null && chargerWorker.isShutdown()) {
                if (!chargerWorker.isTerminated()) stoppingChargerWorkers.add(chargerWorker);
                startChargerWorker(chargerLoader);
            }
        }
        if (detached && saved != null) resume(saved);
        invalidateHosts();
    }

    /** Do not approve unloading while vendor IPC or a sampler is still executing old code. */
    static boolean stopWorkers() throws InterruptedException {
        HandlerThread thread;
        synchronized (ColorOsHooks.class) {
            thread = batteryThread;
            if (batteryWorker != null) batteryWorker.removeCallbacksAndMessages(null);
            if (thread != null) thread.quit();
            batteryWorkerStopped = true;
        }
        if (chargerWorker != null) chargerWorker.shutdownNow();
        if (thread != null) thread.join(3000L);
        boolean stopped = thread == null || !thread.isAlive();
        return (chargerWorker == null || chargerWorker.awaitTermination(3, TimeUnit.SECONDS)) && stopped;
    }

    @SuppressWarnings("unchecked")
    static void resume(Map<String, Object> saved) {
        TrioConfig.restoreForReload((android.os.Bundle) saved.get("settings"));
        wifiFlow = saved.get("wifi");
        Object[] w = (Object[]) saved.get("wifiSample");
        renderedWifi = new WifiSample((Boolean) w[0], (Integer) w[1], (Boolean) w[2], (String) w[3]);
        Object[] b = (Object[]) saved.get("battery");
        batteryLevelFlow = b[0]; chargeFlow = b[1]; savingFlow = b[2];
        Map<Integer, Object[]> mobiles = (Map<Integer, Object[]>) saved.get("mobiles");
        for (Map.Entry<Integer, Object[]> entry : mobiles.entrySet()) {
            Object[] v = entry.getValue();
            Mobile m = new Mobile();
            m.icon = v[0]; m.type = v[1]; m.cellularIcon = v[2]; m.cellularType = v[3];
            m.iconGroup = v[4]; m.inService = v[5]; m.renderedType = (String) v[6];
            MOBILES.put(entry.getKey(), m);
        }
        Context oldContext = (Context) saved.get("context");
        if (oldContext != null) start(oldContext);
        for (Object[] pair : (List<Object[]>) saved.get("meters")) {
            Host h = host((ViewGroup) pair[0]);
            h.viewModel = pair[1];
            h.capture();
            h.apply();
        }
        invalidateHosts();
    }

    /** Coalesce ColorOS reads off the main thread; the inherited MIUI path stays synchronous. */
    static synchronized boolean dispatchBatteryRefresh(Runnable refresh) {
        if (ColorOsReload.retiring || reloadPreparing) return true;
        if (!installed) return false;
        if (batteryWorker == null) {
            HandlerThread thread = new HandlerThread("ColorDuo-battery",
                    android.os.Process.THREAD_PRIORITY_BACKGROUND);
            thread.start();
            batteryThread = thread;
            batteryWorkerStopped = false;
            batteryWorker = new Handler(thread.getLooper());
        }
        if (Looper.myLooper() == batteryWorker.getLooper()) return false;
        if (!batteryRefreshQueued) {
            batteryRefreshQueued = true;
            batteryWorker.post(() -> {
                synchronized (ColorOsHooks.class) {
                    batteryRefreshQueued = false;
                    if (reloadPreparing || ColorOsReload.retiring) return;
                    batteryReadActive = true;
                }
                try { refresh.run(); }
                finally { synchronized (ColorOsHooks.class) { batteryReadActive = false; } }
            });
        }
        return true;
    }

    static boolean dispatchBatteryPublish(Runnable publish) {
        if (ColorOsReload.retiring) return true;
        if (!installed || Looper.myLooper() == Looper.getMainLooper()) return false;
        MAIN.post(publish);
        return true;
    }

    private static final ColorOsBattery BATTERY_SAMPLER = new ColorOsBattery();
    private static final ColorOsCharge CHARGE_SAMPLER = new ColorOsCharge();
    private static final ColorOsChargeSpeed CHARGE_SPEED_SAMPLER = new ColorOsChargeSpeed();
    private static volatile ColorOsChargeSpeed.Sample observedSpeed =
            new ColorOsChargeSpeed.Sample(null, "unavailable");
    private static String lastSpeedNote;
    private static final ColorOsRadio RADIO_SAMPLER = new ColorOsRadio();
    private static volatile DuoPolicy.Charge observedCharge = DuoPolicy.Charge.UNKNOWN;
    private static String lastChargeNote;
    private static int lastBatteryPlugged = -1;

    static boolean batteryStatus(int status, int plugged) {
        if (plugged != lastBatteryPlugged) {
            lastBatteryPlugged = plugged;
            if (charger != null) charger.invalidate();
            CHARGE_SPEED_SAMPLER.invalidate();
        }
        ColorOsCharge.Sample sample = CHARGE_SAMPLER.sample(status, plugged, SystemClock.elapsedRealtime());
        DuoPolicy.Charge next = sample.charge;
        ColorOsChargeSpeed.Sample speed = CHARGE_SPEED_SAMPLER.sample(
                next == DuoPolicy.Charge.CHARGING, plugged, SystemClock.elapsedRealtime());
        boolean speedChanged = speed.speed != observedSpeed.speed || !speed.subtype.equals(observedSpeed.subtype);
        observedSpeed = speed;
        String note = "ColorOS charging: state=" + next + " status=" + status + " plugged=" + plugged
                + " plc=" + (sample.bypass == null ? "unavailable" : sample.bypass ? "enabled(3)" : "disabled(2)");
        if (next != observedCharge) {
            observedCharge = next;
            TrioHooks.log(TrioHooks.LOG_INFO, note);
            invalidateHosts();
        } else if (TrioConfig.debugLog() && !note.equals(lastChargeNote)) {
            TrioHooks.log(TrioHooks.LOG_INFO, note);
        }
        if (speedChanged) invalidateHosts();
        lastChargeNote = note;
        return next == DuoPolicy.Charge.CHARGING;
    }

    /** Normalize current units and per-cell voltage before the shared upstream calculation. */
    static ColorOsBattery.Sample batterySample(int reported, int voltageMilliVolts) {
        long now = SystemClock.elapsedRealtime();
        ColorOsCharger.Observation vendor = charger == null
                ? new ColorOsCharger.Observation(null, "unavailable") : charger.sample(now);
        ColorOsBattery.Sample sample = BATTERY_SAMPLER.sample(Build.FINGERPRINT, reported, voltageMilliVolts,
                now, vendor.reading);
        String halState = vendor.reading != null && vendor.reading.topology == ColorOsCharger.Topology.PARALLEL
                ? "parallel_current_unverified" : vendor.state;
        String note = "ColorOS battery: frameworkCurrent=" + reported
                + " frameworkUnit=" + (ColorOsPolicy.batteryReportsMilliamps(Build.FINGERPRINT) ? "mA" : "uA")
                + " currentMicroAmps=" + sample.currentMicroAmps + " broadcastVoltageMilliVolts=" + voltageMilliVolts
                + " seriesCells=" + sample.seriesCells + " cellsSource=" + sample.cellsSource
                + " powerVoltageMilliVolts=" + sample.voltageMilliVolts + " voltageSource=" + sample.voltageSource
                + " dataSource=" + sample.dataSource + " hal=" + halState
                + " halTopology=" + (vendor.reading == null ? "unknown" : vendor.reading.topology)
                + " halVoltageMilliVolts=" + (vendor.reading == null ? 0 : vendor.reading.voltageMilliVolts)
                + " halSecondMilliVolts=" + (vendor.reading == null ? 0 : vendor.reading.secondMilliVolts);
        String source = sample.dataSource + "/" + sample.voltageSource + "/" + sample.cellsSource + "/" + halState;
        if (!source.equals(lastBatterySource) || (TrioConfig.debugLog() && !note.equals(lastBatterySample))) {
            TrioHooks.log(TrioHooks.LOG_INFO, note);
        }
        lastBatterySource = source;
        lastBatterySample = note;
        return sample;
    }

    private static void startChargerWorker(ClassLoader loader) {
        chargerWorker = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(() -> {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                task.run();
            }, "ColorDuo-charger");
            thread.setDaemon(true);
            return thread;
        });
        charger = new ColorOsCharger(new ColorOsChargerClient(loader),
                chargerWorker, SystemClock::elapsedRealtime);
    }

    static void install(HookRuntime module, ClassLoader loader) {
        meterClass = Refl.cls(METER_CLASS, loader);
        phoneBarClass = Refl.cls("com.android.systemui.statusbar.phone.PhoneStatusBarView", loader);
        statusIconsClass = Refl.cls("com.android.systemui.statusbar.phone.StatusIconContainer", loader);
        wifiActiveClass = Refl.cls(WIFI + "shared.model.WifiNetworkModel$Active", loader);
        wifiCarrierMergedClass = Refl.cls(WIFI + "shared.model.WifiNetworkModel$CarrierMerged", loader);
        for (String name : new String[] {"Inactive", "Unavailable", "Invalid"}) {
            Class<?> type = Refl.cls(WIFI + "shared.model.WifiNetworkModel$" + name, loader);
            if (type != null) disconnectedWifiClasses.add(type);
        }
        if (meterClass == null || phoneBarClass == null) {
            throw new IllegalStateException("ColorOS battery/status bar classes unavailable");
        }
        TrioHooks.useLogger(module);
        doze = new ColorOsDoze(loader);
        chargerLoader = loader;
        startChargerWorker(loader);
        installed = true;
        TrioConfig.install(module);
        TrioConfig.addListener(ColorOsHooks::invalidateHosts);

        int count = 0;
        count += doze.install(module, ColorOsHooks::invalidateHosts);
        // OEM/R8 builds can inline binder helpers. Filter framework writes by our exact child
        // instances too, so an inlined style update cannot leave a stale restoration snapshot.
        count += around(module, Refl.method(ImageView.class, "setImageDrawable", Drawable.class), ColorOsHooks::writeDrawable, ColorOsHooks::afterDrawable);
        count += before(module, Refl.method(ImageView.class, "setScaleType", ImageView.ScaleType.class), ColorOsHooks::writeScaleType);
        count += before(module, Refl.method(View.class, "setVisibility", int.class), ColorOsHooks::writeVisibility);
        count += before(module, Refl.method(ImageView.class, "getDrawable"), ColorOsHooks::readDrawable);
        count += before(module, Refl.method(View.class, "getLayoutParams"), ColorOsHooks::readLayoutParams);
        count += before(module, Refl.method(View.class, "setLayoutParams", ViewGroup.LayoutParams.class), ColorOsHooks::writeLayoutParams);
        count += before(module, Refl.method(View.class, "setMinimumHeight", int.class), call -> {
            int requested=(Integer)call.args[0];
            int retained=ColorOsSizing.retainMinimumHeight((View)call.receiver,requested);
            call.args[0]=retained;
        });
        count += before(module, Refl.method(Refl.cls("androidx.constraintlayout.widget.ConstraintLayout",loader),
                "setMinHeight",int.class), call -> {
            int requested=(Integer)call.args[0];
            int retained=ColorOsSizing.retainWidgetMinimumHeight((View)call.receiver,requested);
            call.args[0]=retained;
        });
        count += before(module, Refl.method(View.class, "setClipBounds", android.graphics.Rect.class), call -> {
            android.graphics.Rect requested=(android.graphics.Rect)call.args[0];
            android.graphics.Rect retained=ColorOsSizing.retainClipBounds((View)call.receiver,requested);
            call.args[0]=retained;
        });

        Set<Method> sizingMeasures=new HashSet<>();
        for(Class<?> type:new Class<?>[]{LinearLayout.class,android.widget.FrameLayout.class,meterClass,
                Refl.cls("androidx.constraintlayout.widget.ConstraintLayout",loader),
                Refl.cls("com.oplus.systemui.qs.OplusQuickStatusBarHeader",loader)}) {
            Method measure=Refl.methodDeep(type,"onMeasure",int.class,int.class);
            if(measure==null || !sizingMeasures.add(measure)) continue;
            count += before(module,measure,call -> {
                int requested=(Integer)call.args[1];
                int retained=ColorOsSizing.retainMeasureSpec((View)call.receiver,requested);
                call.args[1]=retained;
            });
        }

        // Both seamless implementations redraw a different view tree during expansion.
        // Register at binding/reset time, before the first measure and animation frame.
        for (String name : new String[] {"StatusBarFakeFrame", "SystemIconFakeFrame"}) {
            Class<?> frameClass=Refl.cls("com.oplus.systemui.seamless.widget."+name,loader);
            if(frameClass==null) continue;
            for(Method method:frameClass.getDeclaredMethods()) {
                String methodName=method.getName();
                if(methodName.equals("dispatchDraw") && method.getParameterCount()==1) {
                    count += around(module,method,ColorOsHooks::drawMirror,ColorOsHooks::afterMirror);
                } else if(methodName.equals("setFakeView") || methodName.equals("setMView")
                        || methodName.equals("bindRealView") || methodName.equals("resetLayoutParams")
                        || methodName.equals("clearFakeView")) {
                    count += after(module,method,call -> {
                        ViewGroup frame=(ViewGroup)call.receiver;
                        Object source=Refl.callByName(frame,"getMView");
                        if(ColorOsSizing.trackMirror(frame,source instanceof View?(View)source:null))
                            frame.addOnAttachStateChangeListener(MIRROR_ATTACH);
                        refreshMirrorSizes();
                    });
                }
            }
        }

        for (Method method : meterClass.getDeclaredMethods()) {
            String name = method.getName();
            if (name.equals("onFinishInflate") || name.equals("onAttachedToWindow")
                    || name.equals("bindViewModel") || name.equals("onDarkChanged")
                    || name.equals("onConfigurationChanged") || name.equals("disable")
                    || name.equals("onHiddenChanged") || name.equals("setHiddenIconListChanged")) {
                count += around(module, method, call -> {
                    ViewGroup meter = (ViewGroup) call.receiver;
                    Host host = host(meter);
                    if (call.executable.getName().equals("bindViewModel")) {
                        host.viewModel = call.args[0];
                    }
                    beginNative(host, call);
                }, ColorOsHooks::finishNative);
            }
        }

        // Native binder reads use a restoration snapshot while writes keep the owned UI
        // continuously installed. This includes the separate horizontal ProgressBar children.
        Class<?> binder = Refl.cls(BATTERY + "ui.binder.BatteryViewBinder", loader);
        if (binder != null) for (Method method : binder.getDeclaredMethods()) {
            if (!method.getName().startsWith("bind")) continue;
            count += around(module, method, call -> {
                Host owner = null;
                for (Object arg : call.args) {
                    if (arg instanceof View) {
                        owner = existingHost((View) arg);
                        if (owner != null) break;
                    }
                }
                if (owner != null) beginNative(owner, call);
            }, ColorOsHooks::finishNative);
        }

        count += captureWifiFlow(module, loader, WIFI + "data.repository.WifiRepositorySwitcher");
        count += captureWifiFlow(module, loader, WIFI + "data.repository.prod.WifiRepositoryImpl");
        // Observe the model actually converted into the native icon. Merely reading an unused
        // WhileSubscribed repository can keep returning its initial Inactive snapshot forever.
        Class<?> wifiIcons = Refl.cls(WIFI + "ui.model.WifiIcon$Companion", loader);
        if (wifiIcons != null) for (Method method : wifiIcons.getDeclaredMethods()) {
            if (!method.getName().equals("fromModel") || method.getParameterCount() != 3) continue;
            count += after(module, method, call -> {
                WifiSample sample = wifiSample(call.args[0]);
                WifiSample previous = renderedWifi;
                renderedWifi = sample;
                if (!java.util.Objects.equals(previous.connected, sample.connected)
                        || previous.level != sample.level || previous.carrierMerged != sample.carrierMerged) {
                    invalidateHosts();
                }
            });
        }
        count += captureMobileModels(module, loader, "OplusCellularIconViewModelImpl", false);
        count += captureMobileModels(module, loader, "OplusMobileIconViewModel", true);
        // This call remains in both initViews and the live Flow collector in the reference DEX.
        // Observe explicit null updates too, so a disconnect cannot retain the last label.
        Class<?> mobileBinding = Refl.cls(
                "com.oplus.systemui.statusbar.pipeline.mobile.ui.view.OplusStatusBarMobileViewBinder$Os17Binding", loader);
        Class<?> networkType = Refl.cls(
                "com.android.systemui.statusbar.pipeline.mobile.domain.model.OplusNetworkTypeTextModel", loader);
        if (networkType != null) count += after(module,
                Refl.method(mobileBinding, "bindNetworkTypeText", networkType), call -> {
                    Object model = ColorOsPolicy.nativeMember(call.receiver,
                            "oplusViewModel", "getOplusViewModel");
                    Mobile mobile = captureMobileModel(model, true);
                    if (mobile != null) {
                        String text = ColorOsPolicy.networkType(call.args[0]);
                        if (!text.equals(mobile.renderedType)) {
                            mobile.renderedType = text;
                            invalidateHosts();
                        }
                    }
                });
        for (String name : new String[] {"getBatteryLevel", "getChargeState", "isSavingMode"}) {
            Method method = Refl.method(Refl.cls(BATTERY + "data.repository.BatteryRepositoryImpl", loader), name);
            if (method == null) continue;
            count += after(module, method, call -> {
                Object result = call.result;
                if (name.equals("getBatteryLevel")) batteryLevelFlow = result;
                else if (name.equals("getChargeState")) chargeFlow = result;
                else savingFlow = result;
            });
        }

        TrioHooks.log(TrioHooks.LOG_INFO, "ColorOS adapter installed, hooks=" + count
                + " frameworkApi=" + module.getApiVersion()
                + " SystemUI=" + METER_CLASS);
    }

    private static int before(HookRuntime module, Executable method, HookRuntime.Step callback) {
        return around(module, method, callback, null);
    }

    private static int after(HookRuntime module, Executable method, HookRuntime.Step callback) {
        return around(module, method, null, call -> {
            if (call.throwable == null) callback.run(call);
        });
    }

    private static int around(HookRuntime module, Executable method,
                              HookRuntime.Step before, HookRuntime.Step after) {
        if (method == null) return 0;
        try {
            method.setAccessible(true);
            module.hook(method, "colorduo:" + method.toGenericString(), call -> {
                call.entered = !ColorOsReload.retiring;
                if (call.entered && before != null) before.run(call);
            }, call -> {
                if (call.entered && after != null) after.run(call);
            });
            return 1;
        } catch (Throwable error) {
            TrioHooks.log(TrioHooks.LOG_WARN, "ColorOS skip " + method.getName() + ": " + error);
            return 0;
        }
    }

    private static int captureMobileModels(HookRuntime module, ClassLoader loader,
                                          String name, boolean wrapper) {
        Class<?> type = Refl.cls(MOBILE + name, loader);
        if (type == null) return 0;
        int count = 0;
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            count += after(module, constructor, call -> {
                Mobile mobile = captureMobileModel(call.receiver, wrapper);
                if (mobile != null) {
                    if (wrapper) mobile.renderedType = null;
                    // Kotlin/R8 constructor parameter order differs across firmware.
                    for (Object interactor : call.args) {
                        Object group = ColorOsPolicy.nativeMember(interactor,
                                "networkTypeIconGroup", "getNetworkTypeIconGroup");
                        if (group != null) {
                            mobile.iconGroup = group;
                            mobile.inService = ColorOsPolicy.nativeMember(interactor,
                                    "isInService", "isInService");
                            break;
                        }
                    }
                }
                invalidateHosts();
            });
        }
        // Keep getter observation for firmware variants that do not inline these calls.
        for (String getter : new String[] {"getIcon", "getNetworkTypeText"}) {
            count += after(module, Refl.method(type, getter), call -> {
                Object result = call.result;
                Mobile mobile = mobileFor(call.receiver);
                if (mobile != null) {
                    if (getter.equals("getIcon")) {
                        if (wrapper) mobile.icon = result;
                        else mobile.cellularIcon = result;
                    } else {
                        if (wrapper) mobile.type = result;
                        else mobile.cellularType = result;
                    }
                }
            });
        }
        return count;
    }

    private static Mobile captureMobileModel(Object model, boolean wrapper) {
        Mobile mobile = mobileFor(model);
        if (mobile == null) return null;
        Object icon = ColorOsPolicy.nativeMember(model, "icon", "getIcon");
        Object type = ColorOsPolicy.nativeMember(model, "networkTypeText", "getNetworkTypeText");
        if (wrapper) {
            mobile.icon = icon;
            mobile.type = type;
        } else {
            mobile.cellularIcon = icon;
            mobile.cellularType = type;
        }
        return mobile;
    }

    private static Mobile mobileFor(Object model) {
        int id = integer(ColorOsPolicy.nativeMember(model, "subscriptionId", "getSubscriptionId"), -1);
        return id < 0 ? null : MOBILES.computeIfAbsent(id, ignored -> new Mobile());
    }

    private static int captureWifiFlow(HookRuntime module, ClassLoader loader, String className) {
        Method method = Refl.method(Refl.cls(className, loader), "getWifiNetwork");
        if (method == null) {
            TrioHooks.log(TrioHooks.LOG_WARN, "ColorOS missing " + className + ".getWifiNetwork");
            return 0;
        }
        return after(module, method, call -> {
            Object flow = call.result;
            wifiFlow = flow;
        });
    }

    private static final class MirrorCall {
        ViewGroup previous;
        Canvas canvas;
        int save;
        boolean trace;
    }

    private static void drawMirror(HookRuntime.Call call) {
        ViewGroup frame = (ViewGroup) call.receiver;
        MirrorCall state = new MirrorCall();
        call.data = state;
        state.trace = TrioConfig.debugLog();
        if (state.trace) state.previous = ColorOsDiagnostics.enterMirror(frame);
        float offset = ColorOsSizing.mirrorOffset(frame);
        if (offset != 0) {
            Canvas canvas = (Canvas) call.args[0];
            state.save = canvas.save();
            state.canvas = canvas;
            canvas.translate(0, offset);
        }
    }

    private static void afterMirror(HookRuntime.Call call) {
        if (!(call.data instanceof MirrorCall)) return;
        MirrorCall state = (MirrorCall) call.data;
        try {
            if (state.canvas != null) state.canvas.restoreToCount(state.save);
        } finally { if (state.trace) ColorOsDiagnostics.leaveMirror(state.previous); }
    }

    private static void writeDrawable(HookRuntime.Call call) {
        Host owner = childOwner((View) call.receiver);
        if (owner == null || owner.image != call.receiver || owner.moduleWriting) return;
        Drawable drawable = (Drawable) call.args[0];
        if (drawable == owner.glyph) return;
        owner.nativeDrawable = drawable;
        if (!owner.active) return;
        ViewGroup.LayoutParams current = owner.image.getLayoutParams();
        if (current instanceof LinearLayout.LayoutParams
                && (current.width != owner.ownedWidth || current.height != owner.ownedHeight)) {
            owner.nativeParams = new LinearLayout.LayoutParams((LinearLayout.LayoutParams) current);
        }
        call.args[0] = owner.glyph;
        call.data = owner;
    }

    private static void afterDrawable(HookRuntime.Call call) {
        if (call.throwable == null && call.data instanceof Host) ((Host) call.data).enforceImageSize();
    }

    private static void writeScaleType(HookRuntime.Call call) {
        Host owner = childOwner((View) call.receiver);
        if (owner == null || owner.image != call.receiver || owner.moduleWriting) return;
        owner.nativeScaleType = (ImageView.ScaleType) call.args[0];
        if (owner.active) call.args[0] = ImageView.ScaleType.FIT_XY;
    }

    private static void writeVisibility(HookRuntime.Call call) {
        View view = (View) call.receiver;
        Host owner = childOwner(view);
        if (owner == null || owner.moduleWriting) return;
        owner.nativeVisibility.put(view, (Integer) call.args[0]);
        Integer desired = owner.visibilityOverrides.get(view);
        if (owner.active && desired != null) call.args[0] = desired;
    }

    /** Vendor binders may cast the native drawable; virtualize reads without displaying it. */
    private static void readDrawable(HookRuntime.Call call) {
        Host owner = childOwner((View) call.receiver);
        if (owner != null && owner.image == call.receiver && owner.active
                && !owner.moduleWriting && owner.nativeDepth > 0) call.returnValue(owner.nativeDrawable);
    }

    private static void readLayoutParams(HookRuntime.Call call) {
        Host owner = childOwner((View) call.receiver);
        if (owner != null && owner.image == call.receiver && owner.active
                && !owner.moduleWriting && owner.nativeDepth > 0 && owner.nativeParams != null)
            call.returnValue(new LinearLayout.LayoutParams(owner.nativeParams));
    }

    private static void writeLayoutParams(HookRuntime.Call call) {
        View target = (View) call.receiver;
        if (target instanceof ViewGroup && call.args[0] instanceof ViewGroup.LayoutParams) {
            ColorOsSizing.retainLayoutParams(target, (ViewGroup.LayoutParams) call.args[0]);
            return;
        }
        Host owner = childOwner(target);
        if (owner == null || owner.image != call.receiver || owner.moduleWriting || !owner.active
                || !(call.args[0] instanceof LinearLayout.LayoutParams)) return;
        LinearLayout.LayoutParams requested = (LinearLayout.LayoutParams) call.args[0];
        owner.nativeParams = new LinearLayout.LayoutParams(requested);
        LinearLayout.LayoutParams visible = new LinearLayout.LayoutParams(requested);
        visible.width = owner.ownedWidth; visible.height = owner.ownedHeight;
        visible.gravity = android.view.Gravity.CENTER_VERTICAL;
        call.args[0] = visible;
    }

    private static final class NativeCall {
        final Host host;
        final boolean outer;
        final boolean retained;
        NativeCall(Host host) {
            this.host = host;
            outer = host.nativeDepth++ == 0;
            retained = outer && host.active;
        }
    }

    private static void beginNative(Host host, HookRuntime.Call call) {
        NativeCall state = new NativeCall(host);
        call.data = state;
        if (state.outer && !state.retained) host.restoreBattery();
    }

    private static void finishNative(HookRuntime.Call call) {
        if (!(call.data instanceof NativeCall)) return;
        NativeCall state = (NativeCall) call.data;
        Host host = state.host;
        host.nativeDepth--;
        if (state.outer) {
            try {
                if (!state.retained || host.find("battery_icon_view") != host.image) {
                    if (state.retained) host.restoreBattery();
                    host.image = null; host.nativeParams = null;
                    host.capture();
                }
                host.apply();
            } catch (Throwable error) { host.fail(error); }
        }
    }

    private static Host host(ViewGroup meter) {
        WeakReference<Host> reference = HOSTS.get(meter);
        Host host = reference == null ? null : reference.get();
        if (host == null) {
            host = new Host(meter);
            HOSTS.put(meter, new WeakReference<>(host));
            meter.addOnAttachStateChangeListener(host);
        }
        return host;
    }

    private static Host existingHost(View view) {
        for (View current = view; current != null; ) {
            WeakReference<Host> reference = HOSTS.get(current);
            Host host = reference == null ? null : reference.get();
            if (host != null) return host;
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return null;
    }

    private static Host childOwner(View view) {
        WeakReference<Host> reference = CHILDREN.get(view);
        return reference == null ? null : reference.get();
    }

    static void invalidateHosts() {
        if (ColorOsReload.retiring) return;
        MAIN.post(() -> {
            if (ColorOsReload.retiring) return;
            if (refreshQueued) return;
            refreshQueued = true;
            MAIN.post(() -> {
                refreshQueued = false;
                refreshHosts();
            });
        });
    }

    private static void refreshMirrorSizes() {
        if(ColorOsReload.retiring || refreshingMirrors) return;
        refreshingMirrors=true;
        try {
            for(WeakReference<Host> reference:new ArrayList<>(HOSTS.values())) {
                Host host=reference.get();
                if(host==null || !host.active || host.nativeDepth!=0) continue;
                try {
                    if(!TrioConfig.get().enabled || !host.meter.isAttachedToWindow()
                            || host.meter.getVisibility()!=View.VISIBLE) host.apply();
                    else host.resize(TrioConfig.appearance());
                } catch(Throwable error) { host.fail(error); }
            }
        } finally { refreshingMirrors=false; }
    }

    private static void stopMirrors() {
        for(ViewGroup frame:ColorOsSizing.trackedMirrors())
            frame.removeOnAttachStateChangeListener(MIRROR_ATTACH);
        ColorOsSizing.clearMirrors();
    }

    private static void refreshHosts() {
        if (ColorOsReload.retiring) return;
        if (!TrioConfig.get().enabled || !TrioConfig.appearance().foldsMobile()) {
            SIGNAL_FLOWS.sync(Collections.emptyList());
        }
        ClockTicker.refreshNow();
        for (WeakReference<Host> reference : new ArrayList<>(HOSTS.values())) {
            Host host = reference.get();
            if (host == null || host.nativeDepth != 0) continue;
            try { host.apply();host.traceLayout(); }
            catch (Throwable error) { host.fail(error); }
        }
    }

    private static void start(Context supplied) {
        if (context == null) {
            Context app = supplied.getApplicationContext();
            context = app == null ? supplied : app;
            TrioConfig.installReceiver(context);
            TrioState.attachContext(context);
            doze.watch(context, ColorOsHooks::invalidateHosts);
        }
        if (tickerStarted) return;
        tickerStarted = true;
        ColorOsReload.watch(SIGNAL_FLOWS::pause, SIGNAL_FLOWS::resume);
        MAIN.post(new Runnable() {
            @Override public void run() {
                if (ColorOsReload.retiring) return;
                boolean interactive = true;
                PowerManager power = context.getSystemService(PowerManager.class);
                if (power != null) interactive = power.isInteractive();
                if (interactive && TrioConfig.get().enabled) refreshHosts();
                MAIN.postDelayed(this, interactive ? 1000L : 5000L);
            }
        });
    }

    private static Object value(Object flow) { return Refl.callByName(flow, "getValue"); }
    private static String resourceName(View view) {
        if (view == null || view.getId() == View.NO_ID) return "";
        try { return view.getResources().getResourceEntryName(view.getId()); }
        catch (android.content.res.Resources.NotFoundException ignored) { return ""; }
    }
    private static int integer(Object value, int fallback) {
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }
    private static boolean bool(Object value, boolean fallback) {
        return value instanceof Boolean ? (Boolean) value : fallback;
    }

    private static WifiSample wifiSample(Object model) {
        if (model == null) return WifiSample.UNKNOWN;
        int level = ColorOsPolicy.wifiModelLevel(wifiActiveClass, model);
        if (level >= 0) {
            return new WifiSample(Boolean.TRUE, level, false, "Active");
        }
        if (wifiCarrierMergedClass != null && wifiCarrierMergedClass.isInstance(model)) {
            return new WifiSample(Boolean.FALSE, -1, true, "CarrierMerged");
        }
        if (disconnectedWifiClasses.contains(model.getClass())) {
            return new WifiSample(Boolean.FALSE, -1, false, "Disconnected");
        }
        return WifiSample.UNKNOWN;
    }

    private static final class WifiSample {
        static final WifiSample UNKNOWN = new WifiSample(null, -1, false, "Unknown");
        final Boolean connected;
        final int level;
        final boolean carrierMerged;
        final String kind;
        WifiSample(Boolean connected, int level, boolean carrierMerged, String kind) {
            this.connected = connected;
            this.level = level;
            this.carrierMerged = carrierMerged;
            this.kind = kind;
        }
    }

    private static final class Mobile {
        volatile Object icon;
        volatile Object type;
        volatile Object cellularIcon;
        volatile Object cellularType;
        volatile Object iconGroup;
        volatile Object inService;
        /** null: no binder observation yet; empty: native binder explicitly cleared it. */
        volatile String renderedType;

        Object signal() {
            return SIGNAL_FLOWS.read(icon, cellularIcon);
        }

        String networkType(String radio) {
            // Empty binder text is a visibility decision, not evidence that the RAT is unknown.
            return ColorOsPolicy.chooseNetworkType(renderedType,
                    ColorOsPolicy.networkType(value(iconGroup)),
                    ColorOsPolicy.networkType(value(type)),
                    ColorOsPolicy.networkType(value(cellularType)), radio);
        }
    }

    private static final class Host implements View.OnAttachStateChangeListener {
        final ViewGroup meter;
        final TrioState state;
        final Glyph glyph;
        final Map<View, Integer> nativeVisibility = new IdentityHashMap<>();
        final Map<View, Integer> visibilityOverrides = new IdentityHashMap<>();
        final Set<View> nativeTypes = Collections.newSetFromMap(new IdentityHashMap<>());
        final ColorOsPolicy.SlotOwnership slots = new ColorOsPolicy.SlotOwnership();
        ImageView image;
        ViewGroup iconContainer;
        Object viewModel;
        Drawable nativeDrawable;
        LinearLayout.LayoutParams nativeParams;
        ImageView.ScaleType nativeScaleType;
        int nativeDepth;
        boolean active;
        boolean failed;
        boolean logged;
        boolean moduleWriting;
        int ownedWidth;
        int ownedHeight;
        float ownedScale;
        final ColorOsSizing sizing = new ColorOsSizing();
        final ColorOsDiagnostics diagnostics = new ColorOsDiagnostics();
        String lastSizeNote;
        long nextSignalSample;
        String lastWifiNote;
        String lastWifiHandoff;
        String lastMobileNote;
        ColorOsReadings extra;
        View clockContainer;

        Host(ViewGroup meter) {
            this.meter = meter;
            state = new TrioState(meter);
            glyph = new Glyph(this);
        }

        View find(String name) {
            int id = meter.getResources().getIdentifier(name, "id", "com.android.systemui");
            return id == 0 ? null : meter.findViewById(id);
        }

        void capture() {
            View view = find("battery_icon_view");
            if (!(view instanceof ImageView)) return;
            image = (ImageView) view;
            nativeDrawable = image.getDrawable();
            if (image.getLayoutParams() instanceof LinearLayout.LayoutParams) {
                nativeParams = new LinearLayout.LayoutParams((LinearLayout.LayoutParams) image.getLayoutParams());
            }
            nativeScaleType = image.getScaleType();
            for (View child : nativeVisibility.keySet()) {
                if (childOwner(child) == this) CHILDREN.remove(child);
            }
            nativeVisibility.clear();
            visibilityOverrides.clear();
            nativeTypes.clear();
            for (String name : new String[] {"battery_icon_view", "battery_content",
                    "battery_charge_icon", "battery_charge_reverse", "battery_percentage_view"}) {
                View child = find(name);
                if (child != null) {
                    nativeVisibility.put(child, child.getVisibility());
                    CHILDREN.put(child, new WeakReference<>(this));
                }
            }
            clockContainer = null;
            int clockId = meter.getResources().getIdentifier("clock_for_fake", "id", "com.android.systemui");
            for (ViewParent p = meter.getParent(); p instanceof View; p = p.getParent()) {
                if (!phoneBarClass.isInstance(p)) continue;
                clockContainer = ((View) p).findViewById(clockId);
                if (clockContainer != null) {
                    nativeVisibility.put(clockContainer, clockContainer.getVisibility());
                    CHILDREN.put(clockContainer, new WeakReference<>(this));
                }
                break;
            }
        }

        void apply() {
            if (ColorOsReload.retiring) return;
            boolean previous = moduleWriting;
            moduleWriting = true;
            try { applyInternal(); }
            finally { moduleWriting = previous; }
        }

        void applyInternal() {
            if (nativeDepth != 0) return;
            if (image == null) capture();
            // Like upstream's class-wide battery draw hook, every supported meter instance
            // can carry the glyph, including keyguard and expanded/collapsed shade headers.
            boolean wanted = !failed && TrioConfig.get().enabled
                    && meter.isAttachedToWindow() && meter.getVisibility() == View.VISIBLE
                    && image != null && nativeParams != null;
            if (!wanted) {
                restore();
                return;
            }
            start(meter.getContext());
            readState();
            // An unknown battery state must leave a complete native status bar available.
            if (state.level < 0 || state.level > 100) {
                restore();
                return;
            }
            if (!active) capture();
            active = true;
            TrioAppearance appearance = TrioConfig.appearance();
            resize(appearance);
            image.setScaleType(ImageView.ScaleType.FIT_XY);
            if (image.getDrawable() != glyph) image.setImageDrawable(glyph);
            visibilityOverrides.put(image, View.VISIBLE);
            image.setVisibility(View.VISIBLE);
            hide("battery_content", true);
            hide("battery_percentage_view", !appearance.handsBackPercentage());
            hide("battery_charge_icon", appearance.hidesNativeBolt());
            // battery_charge_reverse is wireless power sharing, not a second bolt layer.
            // Its visibility remains under the native binder's control.
            hide(clockContainer, TrioRenderer.drawsClock(state, appearance));
            syncExtra(appearance);
            syncSlots(appearance);
            glyph.invalidateSelf();
            if (!logged) {
                logged = true;
                View row = ColorOsLayout.rowFor(meter, VIEW_TREE);
                View root = row == null ? null : VIEW_TREE.parent(row);
                TrioHooks.log(TrioHooks.LOG_INFO, "ColorOS host active: meter=" + resourceName(meter)
                        + " row=" + resourceName(row) + " parent=" + (root == null ? "none" : root.getClass().getName())
                        + " level=" + state.level + " wifiFlow=" + (wifiFlow != null)
                        + " wifiLevel=" + state.wifiLevel + " wifiPresent=" + state.wifiPresent
                        + " mobileSubscriptions=" + MOBILES.size());
            }
        }

        /** Geometry-only refresh for native seamless binding/layout callbacks. */
        void resize(TrioAppearance appearance) {
            boolean previous=moduleWriting;
            moduleWriting=true;
            try {
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(nativeParams);
                TrioSizing.Metrics dimensions = sizing.measure(meter,image,appearance);
                params.height = dimensions.height;
                params.width = dimensions.width;
                params.gravity = android.view.Gravity.CENTER_VERTICAL;
                ownedScale = dimensions.scale;
                if (TrioConfig.debugLog()) {
                    String note = "ColorOS size: requested=" + TrioConfig.get().glyphScale
                            + " effective=" + Math.round(dimensions.effectivePercent)
                            + " width=" + dimensions.width + " height=" + dimensions.height
                            + " limited=" + dimensions.limited;
                    if (!note.equals(lastSizeNote)) {
                        lastSizeNote = note;
                        TrioHooks.log(TrioHooks.LOG_INFO,note);
                    }
                }
                ownedWidth = params.width;
                ownedHeight = params.height;
                if (image.getLayoutParams().width != params.width || image.getLayoutParams().height != params.height) {
                    image.setLayoutParams(params);
                }
            } finally { moduleWriting=previous; }
        }

        void traceLayout() {
            if(!TrioConfig.debugLog() || !active || image==null) return;
            try {
                diagnostics.layout(meter,image,ownedWidth,ownedHeight,ownedScale,sizing.mirrorFrames(),SystemClock.uptimeMillis(),
                        note -> TrioHooks.log(TrioHooks.LOG_INFO,note));
            } catch(Throwable error) {
                TrioHooks.log(TrioHooks.LOG_WARN,"ColorDuoTrace layout unavailable: "+error);
            }
        }

        void traceDraw(Canvas canvas,android.graphics.Rect bounds) {
            if(!TrioConfig.debugLog()) return;
            try {
                diagnostics.draw(meter,image,canvas,bounds,ownedScale,SystemClock.uptimeMillis(),
                        note -> TrioHooks.log(TrioHooks.LOG_INFO,note));
            } catch(Throwable error) {
                TrioHooks.log(TrioHooks.LOG_WARN,"ColorDuoTrace draw unavailable: "+error);
            }
        }

        void hide(String name, boolean hidden) {
            hide(find(name), hidden);
        }

        void hide(View view, boolean hidden) {
            Integer original = nativeVisibility.get(view);
            if (view != null && original != null) {
                if (hidden) visibilityOverrides.put(view, View.GONE);
                else visibilityOverrides.remove(view);
                view.setVisibility(hidden ? View.GONE : original);
            }
        }

        void enforceImageSize() {
            boolean previous = moduleWriting;
            moduleWriting = true;
            try {
                ViewGroup.LayoutParams params = image.getLayoutParams();
                if (params.width != ownedWidth || params.height != ownedHeight) {
                    LinearLayout.LayoutParams fixed = new LinearLayout.LayoutParams(nativeParams);
                    fixed.width = ownedWidth;
                    fixed.height = ownedHeight;
                    fixed.gravity = android.view.Gravity.CENTER_VERTICAL;
                    image.setLayoutParams(fixed);
                }
            } finally { moduleWriting = previous; }
        }

        void restoreBattery() {
            boolean previous = moduleWriting;
            moduleWriting = true;
            try { restoreBatteryInternal(); }
            finally { moduleWriting = previous; }
        }

        void restoreBatteryInternal() {
            sizing.release();
            if (active && image != null) {
                image.setImageDrawable(nativeDrawable);
                image.setScaleType(nativeScaleType);
                if (nativeParams != null) image.setLayoutParams(new LinearLayout.LayoutParams(nativeParams));
                for (Map.Entry<View, Integer> entry : nativeVisibility.entrySet()) {
                    entry.getKey().setVisibility(entry.getValue());
                }
            }
            active = false;
            if (extra != null) extra.setVisibility(View.GONE);
        }

        void restore() {
            restoreBattery();
            releaseSlots();
        }

        void fail(Throwable error) {
            failed = true;
            try { restore(); } catch (Throwable ignored) { /* preserve the original exception */ }
            TrioHooks.log(TrioHooks.LOG_WARN, "ColorOS host restored after failure: " + error);
        }

        void readState() {
            Object battery = value(Refl.callByName(viewModel, "getBatteryIcon"));
            if (battery == null) battery = Refl.callByName(meter, "getBatteryIcon");
            state.level = integer(value(batteryLevelFlow), integer(Refl.callByName(battery, "getPowerLevel"), -1));
            state.useTint = true;
            Object color = value(Refl.callByName(viewModel, "getBatteryColor"));
            state.tintColor = integer(Refl.callByName(color, "getSingleToneColor"),
                    integer(Refl.callByName(battery, "getTintColor"), 0xFFFFFFFF));
            Object charge = value(chargeFlow);
            if (charge == null && viewModel != null) {
                charge = Refl.get(Refl.field(viewModel.getClass(), "chargeState"), viewModel);
            }
            state.charging = observedCharge == DuoPolicy.Charge.UNKNOWN
                    ? bool(Refl.callByName(charge, "getCharging"), TrioState.sChargingAttached)
                    : observedCharge == DuoPolicy.Charge.CHARGING;
            state.chargingPaused = observedCharge == DuoPolicy.Charge.PAUSED;
            ColorOsChargeSpeed.NativeSample nativeSpeed = CHARGE_SPEED_SAMPLER.nativeSample(charge);
            ColorOsChargeSpeed.Sample protocol = observedSpeed;
            state.chargingSpeed = !state.charging ? ChargeSpeed.NORMAL
                    : protocol.speed != null ? protocol.speed
                    : nativeSpeed.speed != null ? nativeSpeed.speed : ChargeSpeed.NORMAL;
            state.quickCharging = state.charging && state.chargingSpeed != ChargeSpeed.NORMAL;
            String speedNote = "ColorOS charge speed: tier=" + state.chargingSpeed
                    + " charging=" + state.charging + " protocolSubtype=" + protocol.subtype
                    + " source=" + (!state.charging ? "disconnected"
                        : protocol.speed != null ? "oplus_sysfs"
                        : nativeSpeed.speed != null ? "native_flags" : "fallback_normal");
            if (TrioConfig.debugLog()) speedNote += " native={" + nativeSpeed.flags + "}";
            if (!speedNote.equals(lastSpeedNote)) {
                lastSpeedNote = speedNote;
                TrioHooks.log(TrioHooks.LOG_INFO, speedNote);
            }
            state.powerSave = bool(value(savingFlow), bool(Refl.get(
                    Refl.field(viewModel == null ? null : viewModel.getClass(), "isSavingMode"), viewModel), false));
            state.low = state.level >= 0 && state.level <= 15;

            // Wi-Fi reads cached observations only. Process every connection notification even
            // inside the telephony sampling interval so disconnect/reconnect is not delayed.
            WifiSample nativeSample = renderedWifi;
            WifiSample repository = wifiSample(value(wifiFlow));
            Boolean connected = WifiPresence.connectedSnapshot();
            boolean carrierMerged = nativeSample.connected == null
                    ? repository.carrierMerged : nativeSample.carrierMerged;
            state.wifiLevel = ColorOsPolicy.wifiLevel(connected, TrioState.sWifiLevel,
                    nativeSample.connected, nativeSample.level, repository.level, carrierMerged);
            state.wifiPresent = ColorOsPolicy.wifiPresent(connected, state.wifiLevel,
                    nativeSample.connected, carrierMerged);
            state.wifiPresent = WifiPresence.forDisplay(state.wifiPresent);
            state.wifiStandard = state.wifiPresent ? TrioState.sWifiStandard : "";
            if (TrioConfig.debugLog()) {
                String note = "ColorOS wifi: connected=" + connected
                        + " rendered=" + nativeSample.kind + "/" + nativeSample.level
                        + " repository=" + repository.kind + "/" + repository.level
                        + " platformLevel=" + TrioState.sWifiLevel + " resolved=" + state.wifiLevel
                        + " route=" + InternetRoute.current() + " displayed=" + state.wifiPresent
                        + " standard=" + state.wifiStandard;
                if (!note.equals(lastWifiNote)) {
                    lastWifiNote = note;
                    TrioHooks.log(TrioHooks.LOG_INFO, note);
                }
            }

            // Dark-mode animation dispatches many binder updates per second. Only cached battery
            // and color values are read on those updates; telephony IPC is bounded independently.
            long now = SystemClock.elapsedRealtime();
            if (now < nextSignalSample) return;
            nextSignalSample = now + 1000L;
            if (now >= nextSimPoll) {
                nextSimPoll = now + 1000L;
                TrioState.pollSimsNow();
            }
            state.slotLevels = TrioState.sSlotLevels.clone();
            state.mobileLevel = -1;
            state.mobileType = "";
            ColorOsRadio.Sample radio = RADIO_SAMPLER.sample(context, now);
            ColorOsNetworkType.sync(context, radio.subscriptions);
            // Reading StateFlow.value alone does not keep WhileSubscribed models live.
            // Subscribe only the active SIMs while the module owns their signal display.
            List<Object> signalFlows = new ArrayList<>();
            if (TrioConfig.appearance().foldsMobile() && radio.subscriptions != null) {
                for (SubscriptionInfo subscription : radio.subscriptions) {
                    if (subscription == null) continue;
                    Mobile mobile = MOBILES.get(subscription.getSubscriptionId());
                    if (mobile != null) {
                        signalFlows.add(mobile.icon);
                        signalFlows.add(mobile.cellularIcon);
                    }
                }
            }
            SIGNAL_FLOWS.sync(signalFlows);
            state.dataSlot = radio.dataSlot;
            state.mobileService = radio.radio;
            if (radio.radio == DuoPolicy.Radio.AIRPLANE || radio.radio == DuoPolicy.Radio.NO_SIM) {
                state.slotLevels = new int[] {-1, -1};
                state.sims = 0;
                TrioState.setMobileType("");
                noteMobile(radio);
                return;
            }
            int dataId = radio.dataId;
            try {
                List<SubscriptionInfo> subscriptions = radio.subscriptions;
                if (subscriptions != null) for (SubscriptionInfo subscription : subscriptions) {
                    if (subscription == null) continue;
                    int id = subscription.getSubscriptionId();
                    int slot = subscription.getSimSlotIndex();
                    if (slot < 0 || slot >= state.slotLevels.length) continue;
                    Mobile mobile = MOBILES.get(id);
                    Object signal = mobile == null ? null : mobile.signal();
                    if (signal != null && signal.getClass().getName().endsWith("$Cellular")) {
                        boolean inService = bool(Refl.get(Refl.field(signal.getClass(), "isInService"), signal), false);
                        int level = ColorOsPolicy.mobileLevel(integer(Refl.callByName(signal, "getLevel"), -1),
                                integer(Refl.callByName(signal, "getNumberOfLevels"), -1), inService);
                        if (level >= 0) state.slotLevels[slot] = level;
                    } else if (signal != null) {
                        // Satellite models share the icon channel, but are not cellular bars.
                        state.slotLevels[slot] = -1;
                    }
                    if (DuoPolicy.hasNotice(radio.services[slot])) state.slotLevels[slot] = 0;
                    if (id == dataId || (dataId < 0 && state.mobileLevel < 0)) {
                        state.mobileLevel = state.slotLevels[slot];
                        state.mobileType = mobile == null ? "" : mobile.networkType("");
                        if (state.mobileType.isEmpty()) state.mobileType = ColorOsNetworkType.read(id);
                        if (state.dataSlot < 0) state.dataSlot = slot;
                        if (state.mobileService == DuoPolicy.Radio.UNKNOWN && mobile != null) {
                            Object service = value(mobile.inService);
                            if (Boolean.TRUE.equals(service)) state.mobileService = DuoPolicy.Radio.AVAILABLE;
                        }
                    }
                }
            } catch (RuntimeException error) {
                if (TrioConfig.debugLog()) TrioHooks.log(TrioHooks.LOG_WARN, "ColorOS telephony: " + error);
            }
            state.sims = (state.slotLevels[0] >= 0 ? 1 : 0) + (state.slotLevels[1] >= 0 ? 1 : 0);
            if (DuoPolicy.hasNotice(state.mobileService) || state.mobileService == DuoPolicy.Radio.REGISTERING) {
                state.mobileLevel = state.mobileService == DuoPolicy.Radio.REGISTERING ? -1 : 0;
                state.mobileType = "";
            }
            TrioState.setMobileType(state.mobileType);
            noteMobile(radio);
        }

        private void noteMobile(ColorOsRadio.Sample radio) {
            if (TrioConfig.debugLog()) {
                String note = "ColorOS mobile: models=" + MOBILES.size() + " dataSlot=" + TrioState.sDataSlot
                        + " levels=" + java.util.Arrays.toString(state.slotLevels)
                        + " telephonyLevels=" + java.util.Arrays.toString(TrioState.sSlotLevels)
                        + " type=" + state.mobileType
                        + " service=" + state.mobileService + " reported=" + radio.reportedRadio
                        + " primarySlot=" + state.dataSlot
                        + " subscriptions=" + (radio.subscriptions == null ? -1 : radio.subscriptions.size());
                if (!note.equals(lastMobileNote)) {
                    lastMobileNote = note;
                    TrioHooks.log(TrioHooks.LOG_INFO, note);
                }
            }
        }

        @SuppressWarnings("unchecked")
        boolean updateSlots(Set<String> wanted) {
            if (iconContainer == null) return wanted.isEmpty();
            Object ignored = Refl.get(Refl.field(iconContainer.getClass(), "mIgnoredSlots"), iconContainer);
            if (!(ignored instanceof java.util.Collection)) return false;
            java.util.Collection<String> ignoredSlots = (java.util.Collection<String>) ignored;
            if (slots.sync(ignoredSlots, wanted)) {
                iconContainer.requestLayout();
                Refl.callByName(iconContainer, "updateStates");
            }
            return ignoredSlots.containsAll(wanted);
        }

        void releaseSlots() { updateSlots(Collections.emptySet()); }

        void releaseNativeTypes() {
            for (View type : nativeTypes) {
                hide(type, false);
                nativeVisibility.remove(type);
                visibilityOverrides.remove(type);
                if (childOwner(type) == this) CHILDREN.remove(type);
            }
            nativeTypes.clear();
        }

        void syncSlots(TrioAppearance appearance) {
            View resolved = ColorOsLayout.iconsFor(meter, VIEW_TREE);
            if (iconContainer != resolved) {
                releaseSlots();
                releaseNativeTypes();
                iconContainer = resolved instanceof ViewGroup ? (ViewGroup) resolved : null;
            }
            Set<String> wanted = new HashSet<>();
            // Register before an HD child exists, so both shade hosts inherit the choice.
            boolean noticeVisible = extra != null && extra.hasVisibleRadioNotice();
            if (TrioConfig.get().hideHd) wanted.add("volte");
            if (iconContainer != null) for (int i = 0; i < iconContainer.getChildCount(); i++) {
                Object slot = Refl.callByName(iconContainer.getChildAt(i), "getSlot");
                if (!(slot instanceof String)) continue;
                if (DuoPolicy.foldSlot((String) slot, appearance.foldsMobile(), state.sims,
                        state.mobileService, noticeVisible)) wanted.add((String) slot);
                if (slot.equals("wifi") && appearance.ownsWifi() && state.wifiLevel >= 0) wanted.add((String) slot);
                if (slot.equals("mobile") || slot.equals("stacked_mobile")) {
                    // Upstream also hands off the RAT label when bars remain native.
                    // Track only these children; never hide the whole radio for a type label.
                    for (String name : new String[] {"mobile_type_text", "mobile_type_image"}) {
                        int id = meter.getResources().getIdentifier(name, "id", "com.android.systemui");
                        View type = id == 0 ? null : iconContainer.getChildAt(i).findViewById(id);
                        if (type == null) continue;
                        if (!nativeVisibility.containsKey(type)) {
                            nativeVisibility.put(type, type.getVisibility());
                            CHILDREN.put(type, new WeakReference<>(this));
                        }
                        nativeTypes.add(type);
                        hide(type, appearance.ownsType()
                                && (!DuoPolicy.hasNotice(state.mobileService) || noticeVisible));
                    }
                }
            }
            boolean applied = updateSlots(wanted);
            if (TrioConfig.debugLog()) {
                String note = "ColorOS wifi handoff: owns=" + appearance.ownsWifi()
                        + " level=" + state.wifiLevel + " container=" + (iconContainer != null)
                        + " requested=" + wanted + " applied=" + applied;
                if (!note.equals(lastWifiHandoff)) {
                    lastWifiHandoff = note;
                    TrioHooks.log(TrioHooks.LOG_INFO, note);
                }
            }
        }

        void syncExtra(TrioAppearance appearance) {
            if (extra == null) {
                ViewParent parent = meter.getParent();
                // QS uses ConstraintLayout with an end-to-start battery constraint. Put the
                // strip inside its LinearLayout meter so the battery's measured width reserves
                // space automatically. Standard status/keyguard rows accept a sibling strip.
                ViewGroup row = parent instanceof LinearLayout ? (ViewGroup) parent : meter;
                extra = new ColorOsReadings(meter.getContext(), state, image);
                row.addView(extra, row == meter ? 0 : row.indexOfChild(meter), new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            extra.update(appearance);
        }

        @Override public void onViewAttachedToWindow(View view) {
            try { apply(); } catch (Throwable error) { fail(error); }
        }
        @Override public void onViewDetachedFromWindow(View view) {
            restore();
            releaseNativeTypes();
            iconContainer = null;
            if (extra != null && extra.getParent() instanceof ViewGroup) {
                ((ViewGroup) extra.getParent()).removeView(extra);
                extra = null;
            }
        }
    }

    private static final class Glyph extends Drawable {
        final Host host;
        final Paint layerPaint = new Paint();
        Glyph(Host host) { this.host = host; }

        @Override public void draw(Canvas canvas) {
            if (!host.active || host.failed || getBounds().isEmpty()) return;
            host.traceDraw(canvas,getBounds());
            boolean animate = doze != null && doze.allowsAnimation(host.image);
            // The shared renderer clears its canvas. A clipped layer confines that clear to the
            // battery drawable, keeping neighbouring SystemUI icons on the parent canvas intact.
            int save = canvas.save();
            try {
                canvas.saveLayer(getBounds().left, getBounds().top,
                        getBounds().right, getBounds().bottom, layerPaint);
                canvas.translate(getBounds().left, getBounds().top);
                canvas.clipRect(0, 0, getBounds().width(), getBounds().height());
                TrioRenderer.drawSizedState(canvas, getBounds().width(), getBounds().height(),
                        host.state, TrioConfig.get(), host.ownedScale, true, animate);
                if (animate && TrioRenderer.chargeFlowActive(TrioConfig.get(), host.state.level,
                        host.state.charging, host.state.chargingPaused)
                        && host.image.isShown() && host.meter.getWindowVisibility() == View.VISIBLE) {
                    host.image.postInvalidateOnAnimation();
                }
            } catch (Throwable error) {
                host.failed = true;
                MAIN.post(() -> host.fail(error));
            } finally { canvas.restoreToCount(save); }
        }
        @Override public int getIntrinsicWidth() { return 24; }
        @Override public int getIntrinsicHeight() { return 20; }
        @Override public void setAlpha(int alpha) { layerPaint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { layerPaint.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

}
