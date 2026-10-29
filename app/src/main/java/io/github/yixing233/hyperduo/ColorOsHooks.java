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

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

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
    private static volatile Object wifiFlow;
    private static volatile WifiSample renderedWifi = WifiSample.UNKNOWN;
    private static Class<?> wifiActiveClass;
    private static Class<?> wifiCarrierMergedClass;
    private static final Set<Class<?>> disconnectedWifiClasses = new HashSet<>();
    private static volatile Object batteryLevelFlow;
    private static volatile Object chargeFlow;
    private static volatile Object savingFlow;
    private static volatile boolean installed;
    private static boolean refreshQueued;
    private static boolean tickerStarted;
    private static Class<?> meterClass;
    private static Class<?> phoneBarClass;
    private static Class<?> statusIconsClass;
    private static Context context;
    private static String lastBatterySample;
    private static String lastBatterySource;
    private static Handler batteryWorker;
    private static HandlerThread batteryThread;
    private static ExecutorService chargerWorker;
    private static boolean batteryRefreshQueued;
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

    /** Called on the main looper: leave only platform/SystemUI objects in the handoff. */
    static Map<String, Object> retire() {
        ColorOsReload.retiring = true;
        MAIN.removeCallbacksAndMessages(null);
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
            h.onViewDetachedFromWindow(h.meter);
            h.meter.removeOnAttachStateChangeListener(h);
            h.glyph.setCallback(null);
        }
        saved.put("meters", meters);
        HOSTS.clear();
        CHILDREN.clear();
        ColorOsNetworkType.stop();
        ColorOsReload.closeCallbacks();
        return saved;
    }

    /** Do not approve unloading while vendor IPC or a sampler is still executing old code. */
    static boolean stopWorkers() throws InterruptedException {
        HandlerThread thread;
        synchronized (ColorOsHooks.class) {
            thread = batteryThread;
            if (batteryWorker != null) batteryWorker.removeCallbacksAndMessages(null);
            if (thread != null) thread.quit();
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
        if (ColorOsReload.retiring) return true;
        if (!installed) return false;
        if (batteryWorker == null) {
            HandlerThread thread = new HandlerThread("ColorDuo-battery",
                    android.os.Process.THREAD_PRIORITY_BACKGROUND);
            thread.start();
            batteryThread = thread;
            batteryWorker = new Handler(thread.getLooper());
        }
        if (Looper.myLooper() == batteryWorker.getLooper()) return false;
        if (!batteryRefreshQueued) {
            batteryRefreshQueued = true;
            batteryWorker.post(() -> {
                synchronized (ColorOsHooks.class) { batteryRefreshQueued = false; }
                refresh.run();
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
    private static final ColorOsRadio RADIO_SAMPLER = new ColorOsRadio();
    private static volatile DuoPolicy.Charge observedCharge = DuoPolicy.Charge.UNKNOWN;
    private static String lastChargeNote;
    private static int lastBatteryPlugged = -1;

    static boolean batteryStatus(int status, int plugged) {
        if (plugged != lastBatteryPlugged) {
            lastBatteryPlugged = plugged;
            if (charger != null) charger.invalidate();
        }
        ColorOsCharge.Sample sample = CHARGE_SAMPLER.sample(status, plugged, SystemClock.elapsedRealtime());
        DuoPolicy.Charge next = sample.charge;
        String note = "ColorOS charging: state=" + next + " status=" + status + " plugged=" + plugged
                + " plc=" + (sample.bypass == null ? "unavailable" : sample.bypass ? "enabled(3)" : "disabled(2)");
        if (next != observedCharge) {
            observedCharge = next;
            TrioHooks.log(TrioHooks.LOG_INFO, note);
            invalidateHosts();
        } else if (TrioConfig.debugLog() && !note.equals(lastChargeNote)) {
            TrioHooks.log(TrioHooks.LOG_INFO, note);
        }
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

    static void install(XposedModule module, ClassLoader loader) {
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
        installed = true;
        TrioConfig.install(module);
        TrioConfig.addListener(ColorOsHooks::invalidateHosts);

        int count = 0;
        // OEM/R8 builds can inline binder helpers. Filter framework writes by our exact child
        // instances too, so an inlined style update cannot leave a stale restoration snapshot.
        count += hook(module, Refl.method(ImageView.class, "setImageDrawable", Drawable.class), chain -> {
            Host owner = childOwner((View) chain.getThisObject());
            if (owner == null || owner.image != chain.getThisObject()
                    || owner.moduleWriting || owner.nativeDepth != 0) return chain.proceed();
            Drawable drawable = (Drawable) chain.getArg(0);
            if (drawable == owner.glyph) return chain.proceed();
            owner.nativeDrawable = drawable;
            if (!owner.active) return chain.proceed();
            ViewGroup.LayoutParams current = owner.image.getLayoutParams();
            if (current instanceof LinearLayout.LayoutParams
                    && (current.width != owner.ownedWidth || current.height != owner.ownedHeight)) {
                owner.nativeParams = new LinearLayout.LayoutParams((LinearLayout.LayoutParams) current);
            }
            Object result = chain.proceed(new Object[] {owner.glyph});
            owner.enforceImageSize();
            return result;
        });
        count += hook(module, Refl.method(ImageView.class, "setScaleType", ImageView.ScaleType.class), chain -> {
            Host owner = childOwner((View) chain.getThisObject());
            if (owner == null || owner.image != chain.getThisObject()
                    || owner.moduleWriting || owner.nativeDepth != 0) return chain.proceed();
            owner.nativeScaleType = (ImageView.ScaleType) chain.getArg(0);
            return owner.active ? chain.proceed(new Object[] {ImageView.ScaleType.FIT_XY}) : chain.proceed();
        });
        count += hook(module, Refl.method(View.class, "setVisibility", int.class), chain -> {
            View view = (View) chain.getThisObject();
            Host owner = childOwner(view);
            if (owner == null || owner.moduleWriting || owner.nativeDepth != 0) return chain.proceed();
            owner.nativeVisibility.put(view, (Integer) chain.getArg(0));
            Integer desired = owner.visibilityOverrides.get(view);
            return owner.active && desired != null ? chain.proceed(new Object[] {desired}) : chain.proceed();
        });
        for (Method method : meterClass.getDeclaredMethods()) {
            String name = method.getName();
            if (name.equals("onFinishInflate") || name.equals("onAttachedToWindow")
                    || name.equals("bindViewModel") || name.equals("onDarkChanged")
                    || name.equals("onConfigurationChanged") || name.equals("disable")
                    || name.equals("onHiddenChanged") || name.equals("setHiddenIconListChanged")) {
                count += hook(module, method, chain -> {
                    ViewGroup meter = (ViewGroup) chain.getThisObject();
                    Host host = host(meter);
                    if (chain.getExecutable().getName().equals("bindViewModel")) {
                        host.viewModel = chain.getArg(0);
                    }
                    return nativeUpdate(host, chain);
                });
            }
        }

        // All binder writes happen against the native view state. Capture the result once the
        // outermost binder call finishes, then put our drawable back. This also handles the old
        // horizontal style, which uses a separate ProgressBar instead of battery_icon_view.
        Class<?> binder = Refl.cls(BATTERY + "ui.binder.BatteryViewBinder", loader);
        if (binder != null) for (Method method : binder.getDeclaredMethods()) {
            if (!method.getName().startsWith("bind")) continue;
            count += hook(module, method, chain -> {
                Host owner = null;
                for (Object arg : chain.getArgs()) {
                    if (arg instanceof View) {
                        owner = existingHost((View) arg);
                        if (owner != null) break;
                    }
                }
                return owner == null ? chain.proceed() : nativeUpdate(owner, chain);
            });
        }

        count += captureWifiFlow(module, loader, WIFI + "data.repository.WifiRepositorySwitcher");
        count += captureWifiFlow(module, loader, WIFI + "data.repository.prod.WifiRepositoryImpl");
        // Observe the model actually converted into the native icon. Merely reading an unused
        // WhileSubscribed repository can keep returning its initial Inactive snapshot forever.
        Class<?> wifiIcons = Refl.cls(WIFI + "ui.model.WifiIcon$Companion", loader);
        if (wifiIcons != null) for (Method method : wifiIcons.getDeclaredMethods()) {
            if (!method.getName().equals("fromModel") || method.getParameterCount() != 3) continue;
            count += hook(module, method, chain -> {
                Object result = chain.proceed();
                WifiSample sample = wifiSample(chain.getArg(0));
                WifiSample previous = renderedWifi;
                renderedWifi = sample;
                if (!java.util.Objects.equals(previous.connected, sample.connected)
                        || previous.level != sample.level || previous.carrierMerged != sample.carrierMerged) {
                    invalidateHosts();
                }
                return result;
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
        if (networkType != null) count += hook(module,
                Refl.method(mobileBinding, "bindNetworkTypeText", networkType), chain -> {
                    Object result = chain.proceed();
                    Object model = ColorOsPolicy.nativeMember(chain.getThisObject(),
                            "oplusViewModel", "getOplusViewModel");
                    Mobile mobile = captureMobileModel(model, true);
                    if (mobile != null) {
                        String text = ColorOsPolicy.networkType(chain.getArg(0));
                        if (!text.equals(mobile.renderedType)) {
                            mobile.renderedType = text;
                            invalidateHosts();
                        }
                    }
                    return result;
                });
        for (String name : new String[] {"getBatteryLevel", "getChargeState", "isSavingMode"}) {
            Method method = Refl.method(Refl.cls(BATTERY + "data.repository.BatteryRepositoryImpl", loader), name);
            if (method == null) continue;
            count += hook(module, method, chain -> {
                Object result = chain.proceed();
                if (name.equals("getBatteryLevel")) batteryLevelFlow = result;
                else if (name.equals("getChargeState")) chargeFlow = result;
                else savingFlow = result;
                return result;
            });
        }

        TrioHooks.log(TrioHooks.LOG_INFO, "ColorOS adapter installed, hooks=" + count
                + " frameworkApi=" + module.getApiVersion()
                + " SystemUI=" + METER_CLASS);
    }

    private static int hook(XposedModule module, Executable method, XposedInterface.Hooker callback) {
        if (method == null) return 0;
        try {
            method.setAccessible(true);
            module.hook(method).setId("colorduo:" + method.toGenericString()).intercept(chain ->
                    ColorOsReload.retiring ? chain.proceed() : callback.intercept(chain));
            return 1;
        } catch (Throwable error) {
            TrioHooks.log(TrioHooks.LOG_WARN, "ColorOS skip " + method.getName() + ": " + error);
            return 0;
        }
    }

    private static int captureMobileModels(XposedModule module, ClassLoader loader,
                                          String name, boolean wrapper) {
        Class<?> type = Refl.cls(MOBILE + name, loader);
        if (type == null) return 0;
        int count = 0;
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            count += hook(module, constructor, chain -> {
                Object result = chain.proceed();
                Mobile mobile = captureMobileModel(chain.getThisObject(), wrapper);
                if (mobile != null) {
                    if (wrapper) mobile.renderedType = null;
                    // Kotlin/R8 constructor parameter order differs across firmware.
                    for (Object interactor : chain.getArgs()) {
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
                return result;
            });
        }
        // Keep getter observation for firmware variants that do not inline these calls.
        for (String getter : new String[] {"getIcon", "getNetworkTypeText"}) {
            count += hook(module, Refl.method(type, getter), chain -> {
                Object result = chain.proceed();
                Mobile mobile = mobileFor(chain.getThisObject());
                if (mobile != null) {
                    if (getter.equals("getIcon")) {
                        if (wrapper) mobile.icon = result;
                        else mobile.cellularIcon = result;
                    } else {
                        if (wrapper) mobile.type = result;
                        else mobile.cellularType = result;
                    }
                }
                return result;
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

    private static int captureWifiFlow(XposedModule module, ClassLoader loader, String className) {
        Method method = Refl.method(Refl.cls(className, loader), "getWifiNetwork");
        if (method == null) {
            TrioHooks.log(TrioHooks.LOG_WARN, "ColorOS missing " + className + ".getWifiNetwork");
            return 0;
        }
        return hook(module, method, chain -> {
            Object flow = chain.proceed();
            wifiFlow = flow;
            return flow;
        });
    }

    private static Object nativeUpdate(Host host, XposedInterface.Chain chain) throws Throwable {
        boolean outer = host.nativeDepth++ == 0;
        if (outer) host.restoreBattery();
        try {
            return chain.proceed();
        } finally {
            host.nativeDepth--;
            if (outer) {
                try {
                    host.capture();
                    host.apply();
                } catch (Throwable error) {
                    host.fail(error);
                }
            }
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

    private static void refreshHosts() {
        if (ColorOsReload.retiring) return;
        ClockTicker.refreshNow();
        for (WeakReference<Host> reference : new ArrayList<>(HOSTS.values())) {
            Host host = reference.get();
            if (host == null || host.nativeDepth != 0) continue;
            try { host.apply(); }
            catch (Throwable error) { host.fail(error); }
        }
    }

    private static void start(Context supplied) {
        if (context == null) {
            Context app = supplied.getApplicationContext();
            context = app == null ? supplied : app;
            TrioConfig.installReceiver(context);
            TrioState.attachContext(context);
        }
        if (tickerStarted) return;
        tickerStarted = true;
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
            Object current = value(icon);
            return current == null ? value(cellularIcon) : current;
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
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(nativeParams);
            int height = Math.round(20 * meter.getResources().getDisplayMetrics().density);
            TrioGeometry.setCapsuleLength(appearance.capsuleLength);
            params.height = appearance.capsule ? TrioGeometry.capHostHeightPx(height * 3 / 4) : height;
            params.width = appearance.capsule ? TrioGeometry.capHostWidthPx(height * 3 / 4)
                    : Math.round(24 * meter.getResources().getDisplayMetrics().density);
            ownedWidth = params.width;
            ownedHeight = params.height;
            if (image.getLayoutParams().width != params.width || image.getLayoutParams().height != params.height) {
                image.setLayoutParams(params);
            }
            image.setScaleType(ImageView.ScaleType.FIT_XY);
            if (image.getDrawable() != glyph) image.setImageDrawable(glyph);
            visibilityOverrides.put(image, View.VISIBLE);
            image.setVisibility(View.VISIBLE);
            hide("battery_content", true);
            hide("battery_percentage_view", !appearance.handsBackPercentage());
            hide("battery_charge_icon", appearance.hidesNativeBolt());
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
            state.quickCharging = state.charging && (bool(Refl.callByName(charge, "getSuperVoocCharging"), false)
                    || bool(Refl.callByName(charge, "getWirelessVoocCharging"), false));
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
            if (TrioConfig.debugLog()) {
                String note = "ColorOS wifi: connected=" + connected
                        + " rendered=" + nativeSample.kind + "/" + nativeSample.level
                        + " repository=" + repository.kind + "/" + repository.level
                        + " platformLevel=" + TrioState.sWifiLevel + " resolved=" + state.wifiLevel;
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
                        String radioType = ColorOsNetworkType.read(id);
                        state.mobileType = mobile == null ? radioType : mobile.networkType(radioType);
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
                        + " levels=" + java.util.Arrays.toString(state.slotLevels) + " type=" + state.mobileType
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
            if (iconContainer != null) for (int i = 0; i < iconContainer.getChildCount(); i++) {
                Object slot = Refl.callByName(iconContainer.getChildAt(i), "getSlot");
                if (!(slot instanceof String)) continue;
                boolean noticeVisible = extra != null && extra.hasVisibleRadioNotice();
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
            MAIN.post(() -> { try { apply(); } catch (Throwable error) { fail(error); } });
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
            // The shared renderer clears its canvas. A clipped layer confines that clear to the
            // battery drawable, keeping neighbouring SystemUI icons on the parent canvas intact.
            int save = canvas.save();
            try {
                canvas.saveLayer(getBounds().left, getBounds().top,
                        getBounds().right, getBounds().bottom, layerPaint);
                canvas.translate(getBounds().left, getBounds().top);
                canvas.clipRect(0, 0, getBounds().width(), getBounds().height());
                TrioRenderer.drawState(canvas, getBounds().width(), getBounds().height(),
                        host.state, TrioConfig.get());
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
