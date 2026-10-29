#!/usr/bin/env python3
"""Run production telephony callbacks and reload entry against independent framework doubles."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'app/src/main/java/io/github/yixing233/hyperduo'
files = {
'android/annotation/TargetApi.java': 'package android.annotation; public @interface TargetApi { int value(); }',
'android/os/Build.java': 'package android.os; public class Build { public static class VERSION { public static int SDK_INT=37; } }',
'android/os/Bundle.java': 'package android.os; public class Bundle {}',
'android/os/Looper.java': '''package android.os; public class Looper {
 static final Looper MAIN=new Looper(); public static Looper myLooper(){return MAIN;}
 public static Looper getMainLooper(){return MAIN;} }''',
'android/os/Handler.java': '''package android.os; public class Handler {
 public static int clears; public Handler(Looper l){} public void post(Runnable r){r.run();}
 public void removeCallbacksAndMessages(Object token){clears++;} }''',
'android/content/BroadcastReceiver.java': 'package android.content; public class BroadcastReceiver {}',
'android/content/SharedPreferences.java': '''package android.content; public interface SharedPreferences {
 interface OnSharedPreferenceChangeListener {} void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l); }''',
'android/content/Context.java': '''package android.content; public class Context {
 public int removed; public android.telephony.TelephonyManager manager=new android.telephony.TelephonyManager();
 public <T>T getSystemService(Class<T> c){return c.cast(manager);}
 public java.util.concurrent.Executor getMainExecutor(){return Runnable::run;}
 public void unregisterReceiver(BroadcastReceiver r){removed++;} }''',
'android/net/ConnectivityManager.java': '''package android.net; public class ConnectivityManager {
 public int removed; public static class NetworkCallback {} public void unregisterNetworkCallback(NetworkCallback c){removed++;} }''',
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
'io/github/yixing233/hyperduo/ColorOsHooks.java': '''package io.github.yixing233.hyperduo; class ColorOsHooks {
 static final String METER_CLASS="java.lang.Object"; static int installs,resumes,repaints;static boolean stopped=true;
 static java.util.Map<String,Object> handed;static ClassLoader installedLoader;
 static boolean isInstalled(){return installs>0;} static void install(Object module,ClassLoader loader){installs++;installedLoader=loader;}
 static java.util.Map<String,Object> retire(){ColorOsReload.retiring=true; ColorOsNetworkType.stop(); ColorOsReload.closeCallbacks();
 handed=new java.util.HashMap<>();handed.put("nativeView",new android.content.Context());return handed;}
 static void drainRetiredCallbacks(){} static boolean stopWorkers(){return stopped;} static void resume(java.util.Map<String,Object> state){
 if(state!=handed)throw new AssertionError("lost handoff");resumes++;}
 static void invalidateHosts(){repaints++;} }''',
'io/github/yixing233/hyperduo/TrioHooks.java': 'package io.github.yixing233.hyperduo; class TrioHooks {static void install(Object m,ClassLoader l){} }',
'io/github/yixing233/hyperduo/ReloadTest.java': r'''
package io.github.yixing233.hyperduo;
import android.content.*;import android.os.*;import android.telephony.*;import java.util.*;
import io.github.libxposed.api.*;
public class ReloadTest {
 static int checks;static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
 static class State implements XposedModuleInterface.HotReloadingParam,XposedModuleInterface.HotReloadedParam {
  Object saved;int unhooks;public void setSavedInstanceState(Object o){saved=o;}public Object getSavedInstanceState(){return saved;}
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
  int[] prefs={0};ColorOsReload.watch(h);ColorOsReload.watch(c,new BroadcastReceiver());
  ColorOsReload.watch(l->prefs[0]++,new SharedPreferences.OnSharedPreferenceChangeListener(){});
  ColorOsReload.watch(cm,new android.net.ConnectivityManager.NetworkCallback());
  var module=new HyperDuoModule();var state=new State();
  check(!module.onHotReloading(state),"uninstalled adapter refuses reload");
  ClassLoader loader=ReloadTest.class.getClassLoader();
  module.onPackageReady(new XposedModuleInterface.PackageReadyParam(){
   public String getPackageName(){return "com.android.systemui";}public ClassLoader getClassLoader(){return loader;}});
  check(module.onHotReloading(state),"API102 old generation accepts after cleanup");
  check(b.removed==1&&c.removed==1&&cm.removed==1&&prefs[0]==1&&Handler.clears==1,"all external registrations released");
  int before=ColorOsHooks.repaints;
  ((TelephonyCallback.DisplayInfoListener)callback).onDisplayInfoChanged(new TelephonyDisplayInfo(13,5));
  check(ColorOsHooks.repaints==before,"late callback cannot execute retired generation");
  check(((Map<?,?>)state.saved).get("loader")==loader,"target loader survives without a module object");
  // A new module entry receives no package callbacks. It must reinstall itself.
  ColorOsReload.retiring=false;new HyperDuoModule().onHotReloaded(state);
  check(state.unhooks==2&&ColorOsHooks.installs==2&&ColorOsHooks.resumes==1,"new generation removes old hooks and reclaims hosts");
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
    sources += [str(SOURCE/n) for n in ['ColorOsPolicy.java','ColorOsNetworkType.java','ColorOsReload.java','HyperDuoModule.java','Refl.java']]
    subprocess.run(['javac','-encoding','UTF-8','-d',str(root),*sources],check=True)
    subprocess.run(['java','-cp',str(root),'io.github.yixing233.hyperduo.ReloadTest'],check=True)
