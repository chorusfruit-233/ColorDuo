#!/usr/bin/env python3
"""Run production telephony callbacks and reload entry against independent framework doubles."""
from pathlib import Path
import subprocess
import re
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'app/src/main/java/io/github/yixing233/hyperduo'
hooks = (SOURCE / 'ColorOsHooks.java').read_text()
def production_method(name):
    match = re.search(r'    static (?:synchronized )?[^\n]+ ' + name + r'\([^\n]*\).*?^    \}', hooks, re.M | re.S)
    assert match, name
    return match.group()
lifecycle = '\n'.join(production_method(name) for name in ['prepareReload', 'stopWorkers', 'cancelReload'])
files = {
'android/annotation/TargetApi.java': 'package android.annotation; public @interface TargetApi { int value(); }',
'android/os/Build.java': 'package android.os; public class Build { public static class VERSION { public static int SDK_INT=37; } }',
'android/os/HandlerThread.java': 'package android.os; public class HandlerThread extends Thread {public boolean quit(){interrupt();return true;} }',
'android/os/Bundle.java': 'package android.os; public class Bundle {}',
'android/os/Looper.java': '''package android.os; public class Looper {
 static final Looper MAIN=new Looper(); public static Looper myLooper(){return MAIN;}
 public static Looper getMainLooper(){return MAIN;} }''',
'android/os/Handler.java': '''package android.os; public class Handler {
 public static int clears; public Handler(Looper l){} public void post(Runnable r){r.run();}
 public void removeCallbacksAndMessages(Object token){clears++;} }''',
'android/content/IntentFilter.java': 'package android.content; public class IntentFilter {}',
'android/content/BroadcastReceiver.java': 'package android.content; public class BroadcastReceiver {}',
'android/content/SharedPreferences.java': '''package android.content; public interface SharedPreferences {
 interface OnSharedPreferenceChangeListener {} default void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l){} void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l); }''',
'android/content/Context.java': '''package android.content; public class Context {
 public static final int RECEIVER_EXPORTED=2; public void registerReceiver(BroadcastReceiver r,IntentFilter f){} public void registerReceiver(BroadcastReceiver r,IntentFilter f,int flags){}
 public int removed; public android.telephony.TelephonyManager manager=new android.telephony.TelephonyManager();
 public <T>T getSystemService(Class<T> c){return c.cast(manager);}
 public java.util.concurrent.Executor getMainExecutor(){return Runnable::run;}
 public void unregisterReceiver(BroadcastReceiver r){removed++;} }''',
'android/net/NetworkRequest.java': 'package android.net; public class NetworkRequest {}',
'android/net/ConnectivityManager.java': '''package android.net; public class ConnectivityManager {
 public int removed; public void registerNetworkCallback(NetworkRequest r,NetworkCallback c){} public static class NetworkCallback {} public void unregisterNetworkCallback(NetworkCallback c){removed++;} }''',
'android/telephony/SubscriptionInfo.java': '''package android.telephony; public record SubscriptionInfo(int id) {public int getSubscriptionId(){return id;} }''',
'android/telephony/TelephonyCallback.java': '''package android.telephony; public class TelephonyCallback {
 public interface DisplayInfoListener {void onDisplayInfoChanged(TelephonyDisplayInfo info);} }''',
'android/telephony/TelephonyDisplayInfo.java': '''package android.telephony; public record TelephonyDisplayInfo(int network,int override) {
 public int getNetworkType(){return network;} public int getOverrideNetworkType(){return override;} }''',
'android/telephony/TelephonyManager.java': '''package android.telephony; public class TelephonyManager {
 public final java.util.Map<Integer,TelephonyManager> lines=new java.util.HashMap<>();
 public int data,voice,registered,removed; public boolean deny; public TelephonyCallback callback;
 public TelephonyManager createForSubscriptionId(int id){return lines.computeIfAbsent(id,i->new TelephonyManager());}
 public void registerTelephonyCallback(java.util.concurrent.Executor e,TelephonyCallback c){
  if(deny)throw new SecurityException(); callback=c;registered++; }
 public void unregisterTelephonyCallback(TelephonyCallback c){if(c!=callback)throw new AssertionError();removed++;callback=null;}
 public int getDataNetworkType(){return data;} public int getVoiceNetworkType(){return voice;}
 public void emit(int n,int o){((TelephonyCallback.DisplayInfoListener)callback).onDisplayInfoChanged(new TelephonyDisplayInfo(n,o));} }''',
'io/github/libxposed/api/XposedModuleInterface.java': '''package io.github.libxposed.api; public interface XposedModuleInterface {
 interface PackageReadyParam {String getPackageName();ClassLoader getClassLoader();}
 interface HotReloadingParam {void setSavedInstanceState(Object state);}
 interface HotReloadedParam {Object getSavedInstanceState();java.util.List<XposedInterface.HookHandle> getOldHookHandles();}
 default void onPackageReady(PackageReadyParam p){} default boolean onHotReloading(HotReloadingParam p){return false;}
 default void onHotReloaded(HotReloadedParam p){} }''',
'io/github/libxposed/api/XposedInterface.java': '''package io.github.libxposed.api; public interface XposedInterface {interface HookHandle {void unhook();}}''',
'io/github/libxposed/api/XposedModule.java': '''package io.github.libxposed.api; public class XposedModule implements XposedModuleInterface {public void log(int l,String t,String s){} }''',
'io/github/yixing233/hyperduo/ColorOsHooks.java': '''package io.github.yixing233.hyperduo;
import java.util.*;import java.util.concurrent.*;import android.os.*;
class ColorOsHooks {
 static final String METER_CLASS="java.lang.Object"; static int installs,resumes,repaints,retires;
 static boolean installed,batteryReadActive,reloadPreparing,batteryRefreshQueued,batteryWorkerStopped;
 static Handler batteryWorker;static HandlerThread batteryThread;static ExecutorService chargerWorker;
 static ColorOsCharger charger;static ClassLoader chargerLoader;
 static final List<Thread> stoppingBatteryThreads=new ArrayList<>();
 static final List<ExecutorService> stoppingChargerWorkers=new ArrayList<>();
 static Map<String,Object> handed;static ClassLoader installedLoader;
 static boolean isInstalled(){return installed;}
 static void install(Object module,ClassLoader loader){installed=true;installs++;installedLoader=loader;chargerLoader=loader;}
 static Map<String,Object> snapshotForReload(){handed=new HashMap<>();handed.put("nativeView",new android.content.Context());return handed;}
 static void retire(){retires++;ColorOsReload.retiring=true;ColorOsReload.closeCallbacks();ColorOsNetworkType.stop();}
 static void startChargerWorker(ClassLoader loader){chargerWorker=Executors.newSingleThreadExecutor();
 charger=new ColorOsCharger(()->null,chargerWorker,System::currentTimeMillis);}
 static void drainRetiredCallbacks(){} static void resume(Map<String,Object> state){
 if(state!=handed)throw new AssertionError("lost handoff");resumes++;}
 static void invalidateHosts(){repaints++;}
@LIFECYCLE@
}'''.replace('@LIFECYCLE@', lifecycle),
'io/github/yixing233/hyperduo/TrioHooks.java': 'package io.github.yixing233.hyperduo; class TrioHooks {static void install(Object m,ClassLoader l){} }',
'io/github/yixing233/hyperduo/ReloadTest.java': r'''
package io.github.yixing233.hyperduo;
import android.content.*;import android.os.*;import android.telephony.*;import java.util.*;
import io.github.libxposed.api.*;
public class ReloadTest {
 static int checks;static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
 static class State implements XposedModuleInterface.HotReloadingParam,XposedModuleInterface.HotReloadedParam {
  Object saved;int unhooks;boolean deny;public void setSavedInstanceState(Object o){if(deny)throw new IllegalArgumentException("rejected state");saved=o;}public Object getSavedInstanceState(){return saved;}
  public List<XposedInterface.HookHandle> getOldHookHandles(){return List.of(()->unhooks++,()->unhooks++);}
 }
 public static void main(String[] args){
  Context c=new Context();var a=c.manager.createForSubscriptionId(11);var b=c.manager.createForSubscriptionId(22);
  a.data=13;b.data=20;
  ColorOsNetworkType.sync(c,List.of(new SubscriptionInfo(11),new SubscriptionInfo(22)));
  check(a.registered==1&&b.registered==1,"one listener per physical subscription");
  ColorOsNetworkType.sync(c,List.of(new SubscriptionInfo(11),new SubscriptionInfo(22)));
  check(a.registered==1,"repaint does not duplicate listeners");
  a.emit(13,3);check(ColorOsNetworkType.read(11).equals("5G"),"NSA label independent of native visibility");
  check(ColorOsNetworkType.read(22).equals("5G"),"data SIM switch reads the selected line");
  a.emit(0,0);a.data=0;a.voice=13;check(ColorOsNetworkType.read(11).equals("4G"),"data off retains voice RAT");
  a.voice=0;check(ColorOsNetworkType.read(11).isEmpty(),"explicit unknown clears previous 5G");
  ColorOsNetworkType.sync(c,List.of(new SubscriptionInfo(22)));
  check(a.removed==1,"removed SIM listener released");
  var callback=b.callback;
  var cm=new android.net.ConnectivityManager();var h=new Handler(Looper.getMainLooper());
  int[] prefs={0};ColorOsReload.watch(h);ColorOsReload.watch(c,new BroadcastReceiver(),new IntentFilter());
  ColorOsReload.watch(l->prefs[0]++,new SharedPreferences.OnSharedPreferenceChangeListener(){});
  ColorOsReload.watch(cm,new android.net.ConnectivityManager.NetworkCallback(),new android.net.NetworkRequest());
  var module=new HyperDuoModule();var state=new State();
  check(!module.onHotReloading(state),"uninstalled adapter refuses reload");
  ClassLoader loader=ReloadTest.class.getClassLoader();
  module.onPackageReady(new XposedModuleInterface.PackageReadyParam(){
   public String getPackageName(){return "com.android.systemui";}public ClassLoader getClassLoader(){return loader;}});
  // Extracted production preflight must reject busy work before retiring any UI.
  ColorOsHooks.batteryReadActive=true;int beforeRetire=ColorOsHooks.retires;
  check(!module.onHotReloading(state)&&ColorOsHooks.retires==beforeRetire,"busy framework read preserves the old generation");
  ColorOsHooks.batteryReadActive=false;
  var worker=java.util.concurrent.Executors.newSingleThreadExecutor();
  var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
  ColorOsHooks.charger=new ColorOsCharger(()->{entered.countDown();release.await();return null;},worker,System::currentTimeMillis);
  ColorOsHooks.charger.sample(System.currentTimeMillis());
  try {entered.await();}catch(InterruptedException error){throw new AssertionError(error);}
  check(!module.onHotReloading(state)&&!ColorOsReload.retiring&&ColorOsHooks.retires==beforeRetire,
        "in-flight vendor IPC refuses without unregistering listeners or detaching views");
  release.countDown();worker.shutdown();try {worker.awaitTermination(1,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException error){throw new AssertionError(error);}
  ColorOsHooks.charger=null;
  state.deny=true;
  check(!module.onHotReloading(state)&&!ColorOsHooks.reloadPreparing&&ColorOsHooks.retires==beforeRetire,
        "rejected saved state clears preflight without touching live UI");
  state.deny=false;
  var refusing=new java.util.concurrent.AbstractExecutorService(){
   boolean shut;public void execute(Runnable r){}public void shutdown(){shut=true;}public List<Runnable> shutdownNow(){shut=true;return List.of();}
   public boolean isShutdown(){return shut;}public boolean isTerminated(){return false;}
   public boolean awaitTermination(long t,java.util.concurrent.TimeUnit u){return false;}
  };
  ColorOsHooks.chargerWorker=refusing;
  check(!module.onHotReloading(state)&&!ColorOsReload.retiring&&!ColorOsHooks.reloadPreparing
        &&ColorOsHooks.retires==beforeRetire&&ColorOsHooks.chargerWorker!=refusing,
        "worker shutdown timeout preserves UI and starts a usable replacement sampler");
  check(!module.onHotReloading(state),"old worker that has not exited prevents a later unload");
  ColorOsHooks.stoppingChargerWorkers.clear();
  ColorOsHooks.chargerWorker.shutdownNow();ColorOsHooks.chargerWorker=null;ColorOsHooks.charger=null;
  // Simulate external unregister failure after another registration already closed.
  int[] registration={1};boolean[] refuse={true};
  ColorOsReload.Registration failure=ColorOsReload.watch(()->{if(refuse[0])throw new SecurityException("cleanup");},()->{});
  ColorOsReload.Registration recovered=ColorOsReload.watch(()->registration[0]--,()->registration[0]++);
  int handlerClears=Handler.clears, beforeResume=ColorOsHooks.resumes;
  check(!module.onHotReloading(state)&&!ColorOsReload.retiring&&!ColorOsHooks.reloadPreparing,
        "cleanup exception returns control to the old generation");
  check(registration[0]==1&&Handler.clears==handlerClears&&ColorOsHooks.resumes==beforeResume+1,
        "failed cleanup restores external callbacks and hosts without cancelling ticking handlers");
  refuse[0]=false;failure.release();recovered.release();
  check(module.onHotReloading(state),"API102 old generation accepts after cleanup");
  check(b.removed==1&&c.removed==1&&cm.removed==1&&prefs[0]==1&&Handler.clears==1,"all external registrations released");
  int before=ColorOsHooks.repaints;
  ((TelephonyCallback.DisplayInfoListener)callback).onDisplayInfoChanged(new TelephonyDisplayInfo(13,5));
  check(ColorOsHooks.repaints==before,"late callback cannot execute retired generation");
  check(((Map<?,?>)state.saved).get("loader")==loader,"target loader survives without a module object");
  // A new module entry receives no package callbacks. It must reinstall itself.
  ColorOsReload.retiring=false;new HyperDuoModule().onHotReloaded(state);
  check(state.unhooks==2&&ColorOsHooks.installs==2&&ColorOsHooks.resumes==beforeResume+2,"new generation removes old hooks and reclaims hosts");
  check(ColorOsHooks.installedLoader==loader,"reload uses original SystemUI classloader");
  ColorOsNetworkType.sync(c,List.of(new SubscriptionInfo(22)));
  check(b.registered==2,"second generation can register fresh callbacks");
  ColorOsNetworkType.stop();ColorOsReload.closeCallbacks();
  check(b.removed==2&&c.removed==1,"repeat cleanup has no duplicate unregistration");
  System.out.println("Telephony and reload lifecycle: "+checks+" checks passed");
 }
}
'''
}
with tempfile.TemporaryDirectory(prefix='colorduo-reload-') as d:
    root=Path(d)
    sources=[]
    for name,content in files.items():
        p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content,encoding='utf-8');sources.append(str(p))
    sources += [str(SOURCE/n) for n in ['ColorOsPolicy.java','ColorOsNetworkType.java','ColorOsReload.java','HyperDuoModule.java','Refl.java','ColorOsCharger.java']]
    subprocess.run(['javac','-encoding','UTF-8','-d',str(root),*sources],check=True)
    subprocess.run(['java','-cp',str(root),'io.github.yixing233.hyperduo.ReloadTest'],check=True)
