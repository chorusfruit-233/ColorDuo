#!/usr/bin/env python3
"""Execute persisted migration, provider caller checks and legacy notification-only refresh."""
from pathlib import Path
import subprocess
import tempfile

ROOT=Path(__file__).resolve().parents[1]
files={
'android/content/SharedPreferences.java': '''package android.content;import java.util.*;
public interface SharedPreferences {
 Map<String,?> getAll();boolean contains(String k);boolean getBoolean(String k,boolean d);int getInt(String k,int d);long getLong(String k,long d);
 Editor edit();interface Editor {Editor putBoolean(String k,boolean v);Editor putInt(String k,int v);Editor putLong(String k,long v);Editor putFloat(String k,float v);Editor putString(String k,String v);Editor putStringSet(String k,Set<String> v);boolean commit();void apply();}
}''',
'android/os/Bundle.java': '''package android.os;import java.util.*;public class Bundle {
 public final Map<String,Object> values=new HashMap<>();public void putBoolean(String k,boolean v){values.put(k,v);}public boolean getBoolean(String k){return getBoolean(k,false);}public boolean getBoolean(String k,boolean d){return (Boolean)values.getOrDefault(k,d);}public void putInt(String k,int v){values.put(k,v);}public int getInt(String k,int d){return (Integer)values.getOrDefault(k,d);}public int getInt(String k){return getInt(k,0);}public boolean containsKey(String k){return values.containsKey(k);}
}''',
'android/os/UserManager.java':'package android.os;public class UserManager {public static boolean unlocked;public boolean isUserUnlocked(){return unlocked;}}',
'android/os/SystemClock.java':'package android.os;public class SystemClock {public static long now=100_000;public static long elapsedRealtime(){return now;}}',
'android/os/Binder.java':'package android.os;public class Binder {public static int caller=2000;public static int getCallingUid(){return caller;}}',
'android/os/Process.java':'package android.os;public class Process {public static final int THREAD_PRIORITY_BACKGROUND=10;public static int myUid(){return 2000;}}',
'android/os/Build.java':'package android.os;public class Build {public static class VERSION {public static int SDK_INT=37;}}',
'android/os/Looper.java':'package android.os;public class Looper {public static final Looper MAIN=new Looper();public static Looper running=MAIN;public static Looper getMainLooper(){return MAIN;}}',
'android/os/Handler.java':'''package android.os;public class Handler {final Looper looper;public Handler(Looper l){looper=l;}public boolean post(Runnable r){Looper prior=Looper.running;Looper.running=looper;try{r.run();}finally{Looper.running=prior;}return true;}public boolean postDelayed(Runnable r,long ms){return true;}}''',
'android/os/HandlerThread.java':'package android.os;public class HandlerThread {public HandlerThread(String n,int p){}public void start(){}public Looper getLooper(){return new Looper();}}',
'android/content/Intent.java':'package android.content;import android.os.Bundle;public class Intent {public Bundle extras=new Bundle();public Bundle getExtras(){return extras;}}',
'android/content/IntentFilter.java':'package android.content;public class IntentFilter {public IntentFilter(String a){}}',
'android/content/BroadcastReceiver.java':'package android.content;public abstract class BroadcastReceiver {public abstract void onReceive(Context c,Intent i);}',
'android/content/ContentValues.java':'package android.content;public class ContentValues {}',
'android/database/Cursor.java':'package android.database;public interface Cursor {}',
'android/net/Uri.java':'package android.net;public class Uri {}',
'android/content/Context.java':'''package android.content;
import android.os.*;import java.util.*;
public class Context {
 public static final int MODE_PRIVATE=0,RECEIVER_EXPORTED=2;final boolean device;
 public Context(){this(false);}Context(boolean d){device=d;}
 public static final Map<String,Memory> stores=new HashMap<>();public static BroadcastReceiver receiver;
 public Context createDeviceProtectedStorageContext(){return new Context(true);}
 public Context getApplicationContext(){return this;}public SharedPreferences getSharedPreferences(String n,int mode){return stores.computeIfAbsent((device?"DE/":"CE/")+n,k->new Memory());}
 public <T>T getSystemService(Class<T> type){return type.cast(new UserManager());}
 public static class Packages {public String[] getPackagesForUid(int uid){return uid==1000?new String[]{"android","com.android.systemui"}:new String[]{"third.party"};}}
 public Packages getPackageManager(){return new Packages();}
 public static class Metrics {public float density=1;}public static class Resources {public Metrics getDisplayMetrics(){return new Metrics();}}
 public Resources getResources(){return new Resources();}
 public void registerReceiver(BroadcastReceiver r,IntentFilter f){receiver=r;}public void registerReceiver(BroadcastReceiver r,IntentFilter f,int flags){receiver=r;}
 public ContentResolver getContentResolver(){return new ContentResolver();}
 public static class Memory implements SharedPreferences {
  public final Map<String,Object> values=new HashMap<>();public boolean fail;public int commits;
  public Map<String,?> getAll(){return new HashMap<>(values);}public boolean contains(String k){return values.containsKey(k);}
  public boolean getBoolean(String k,boolean d){return (Boolean)values.getOrDefault(k,d);}public int getInt(String k,int d){return (Integer)values.getOrDefault(k,d);}public long getLong(String k,long d){return (Long)values.getOrDefault(k,d);}
  public Editor edit(){return new Editor(){final Map<String,Object> next=new HashMap<>();
   public Editor putBoolean(String k,boolean v){next.put(k,v);return this;}public Editor putInt(String k,int v){next.put(k,v);return this;}public Editor putLong(String k,long v){next.put(k,v);return this;}public Editor putFloat(String k,float v){next.put(k,v);return this;}public Editor putString(String k,String v){next.put(k,v);return this;}public Editor putStringSet(String k,Set<String> v){next.put(k,v);return this;}
   public boolean commit(){commits++;if(fail)return false;values.putAll(next);return true;}public void apply(){commit();}
  };}
 }
}''',
'android/content/ContentProvider.java':'''package android.content;import android.net.Uri;import android.os.Bundle;import android.database.Cursor;
public abstract class ContentProvider {public Context context;public Context getContext(){return context;}public abstract boolean onCreate();public abstract Bundle call(String m,String a,Bundle e);public abstract Cursor query(Uri u,String[] p,String s,String[] a,String o);public abstract String getType(Uri u);public abstract Uri insert(Uri u,ContentValues v);public abstract int update(Uri u,ContentValues v,String s,String[] a);public abstract int delete(Uri u,String s,String[] a);}''',
'android/content/ContentResolver.java':'''package android.content;import android.os.*;public class ContentResolver {
 public static ContentProvider provider;public static int calls;public static boolean background;
 public Bundle call(String authority,String method,String arg,Bundle extras){calls++;background=Looper.running!=Looper.MAIN;return provider.call(method,arg,extras);}
}''',
'io/github/yixing233/hyperduo/Prefs.java':'package io.github.yixing233.hyperduo;public class Prefs {static final String NAME="config",SYSTEMUI_PACKAGE="com.android.systemui",ACTION_RELOAD="reload",KEY_ENABLED="enabled";}',
'io/github/yixing233/hyperduo/BuildConfig.java':'package io.github.yixing233.hyperduo;public class BuildConfig {static final String APPLICATION_ID="io.github.chorusfruit_233.colorduo";}',
'io/github/yixing233/hyperduo/TrioAppearance.java':'package io.github.yixing233.hyperduo;public class TrioAppearance {static TrioAppearance of(TrioSettings s){return new TrioAppearance();}}',
'io/github/yixing233/hyperduo/TrioSettings.java':'''package io.github.yixing233.hyperduo;import android.content.SharedPreferences;import android.os.Bundle;
public class TrioSettings {boolean enabled,debugLog;int signalTrackAlpha;
 static TrioSettings defaults(){return new TrioSettings();}static TrioSettings from(SharedPreferences p,float d){TrioSettings s=new TrioSettings();s.enabled=p.getBoolean("enabled",false);s.signalTrackAlpha=p.getInt("signal_track_alpha",56);return s;}
 static TrioSettings fromBundle(Bundle b){TrioSettings s=new TrioSettings();s.enabled=b.getBoolean("enabled");s.signalTrackAlpha=b.getInt("signal_track_alpha",56);return s;}
 Bundle toBundle(){Bundle b=new Bundle();b.putBoolean("enabled",enabled);b.putInt("signal_track_alpha",signalTrackAlpha);return b;}
 public boolean equals(Object o){return o instanceof TrioSettings&&((TrioSettings)o).enabled==enabled&&((TrioSettings)o).signalTrackAlpha==signalTrackAlpha;}
}''',
'io/github/yixing233/hyperduo/TrioHooks.java':'package io.github.yixing233.hyperduo;class TrioHooks {static final int LOG_WARN=5;static void log(int p,String s){}}',
'io/github/yixing233/hyperduo/SettingsPlatformTest.java':r'''
package io.github.yixing233.hyperduo;
import android.content.*;import android.os.*;import java.util.*;
public class SettingsPlatformTest {
 static int checks;static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
 public static void main(String[] args){
  Context context=new Context();Context.Memory old=(Context.Memory)context.getSharedPreferences(Prefs.NAME,0);
  old.values.putAll(Map.of("enabled",true,"signal_track_alpha",53,"ring_layout","7|1|8","capsule.value",123,"font","/font.ttf","large",123L,"fraction",1.5f,"set",Set.of("a","b")));
  UserManager.unlocked=false;
  check(SettingsStore.preferences(context).getAll().isEmpty(),"locked upgrade does not open credential storage or mark migration complete");
  UserManager.unlocked=true;
  SharedPreferences migrated=SettingsStore.preferences(context);
  check(migrated.getAll().equals(old.values),"all original types and style keys survive migration");
  migrated.edit().putInt("signal_track_alpha",180).commit();old.values.put("signal_track_alpha",1);
  check(SettingsStore.preferences(context).getInt("signal_track_alpha",0)==180,"reopening cannot reimport the old modern mirror");
  UserManager.unlocked=false;
  check(SettingsStore.preferences(context).getInt("signal_track_alpha",0)==180,"saved settings remain available before unlock");
  UserManager.unlocked=true;
  LegacySettingsProvider provider=new LegacySettingsProvider();provider.context=context;provider.onCreate();
  Bundle extras=new Bundle();extras.putInt("api",93);extras.putBoolean("enabled",false);
  Binder.caller=3333;
  try{provider.call("snapshot",null,extras);throw new AssertionError("untrusted caller accepted");}catch(SecurityException expected){checks++;}
  Binder.caller=2000;provider.call("snapshot",null,extras);
  check(!LegacySettings.status().getBoolean("active"),"opening the app cannot claim SystemUI activation");
  Binder.caller=1000;
  check(provider.call("snapshot",null,extras).getBoolean("enabled"),"SystemUI reads persisted values; request extras cannot change them");
  check(LegacySettings.status().getInt("api")==93&&LegacySettings.status().getBoolean("active"),"actual framework heartbeat is visible to settings UI");
  SystemClock.now+=20_001;check(!LegacySettings.status().getBoolean("active"),"stale activation expires");
  try{provider.call("write",null,extras);throw new AssertionError("remote write allowed");}catch(IllegalArgumentException expected){checks++;}
  ContentResolver.provider=provider;
  TrioConfig.install(new HookRuntime(){public int getApiVersion(){return 93;}public void log(int p,String t,String s){}public void hook(java.lang.reflect.Executable m,String id,Step b,Step a){}});
  int[] changes={0};TrioConfig.addListener(()->{check(Looper.running==Looper.MAIN,"view notification runs on main");changes[0]++;});
  TrioConfig.installReceiver(context);
  check(ContentResolver.background&&TrioConfig.get().enabled&&TrioConfig.get().signalTrackAlpha==180,"boot reads persisted snapshot off the UI thread");
  int before=changes[0];Intent forged=new Intent();forged.extras.putBoolean("enabled",false);forged.extras.putInt("signal_track_alpha",0);
  Context.receiver.onReceive(context,forged);
  check(TrioConfig.get().enabled&&TrioConfig.get().signalTrackAlpha==180&&changes[0]==before,"broadcast content is ignored and unchanged snapshots do not repaint");
  migrated.edit().putBoolean("enabled",false).putInt("signal_track_alpha",200).commit();
  Context.receiver.onReceive(context,new Intent());
  check(!TrioConfig.get().enabled&&TrioConfig.get().signalTrackAlpha==200&&changes[0]==before+1,"UI edit refreshes the full persisted snapshot");
  // Independent upgrade with an already populated legacy store: explicit values must win.
  Context.stores.clear();old=(Context.Memory)context.getSharedPreferences(Prefs.NAME,0);old.values.put("enabled",true);old.values.put("signal_track_alpha",53);
  SharedPreferences existing=context.createDeviceProtectedStorageContext().getSharedPreferences(Prefs.NAME,0);existing.edit().putInt("signal_track_alpha",0).commit();
  check(SettingsStore.preferences(context).getInt("signal_track_alpha",-1)==0,"existing legacy zero is not overwritten by stale modern opacity");
  Context.stores.clear();old=(Context.Memory)context.getSharedPreferences(Prefs.NAME,0);old.values.put("enabled",true);
  Context.Memory failed=(Context.Memory)context.createDeviceProtectedStorageContext().getSharedPreferences(Prefs.NAME,0);failed.fail=true;
  SettingsStore.preferences(context);failed.fail=false;
  check(SettingsStore.preferences(context).getBoolean("enabled",false),"failed persistence leaves migration retryable");
  System.out.println("Legacy settings migration, provider and reload passed: "+checks+" checks");
 }
}
'''
}
with tempfile.TemporaryDirectory(prefix='colorduo-legacy-settings-') as tmp:
    tmp=Path(tmp)
    for name,content in files.items():
        p=tmp/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content)
    sources=[str(p) for p in tmp.rglob('*.java')]
    for flavor,names in [('main',['SettingsStore','HookRuntime']),('legacy',['LegacySettings','LegacySettingsProvider','TrioConfig'])]:
        sources += [str(ROOT/f'app/src/{flavor}/java/io/github/yixing233/hyperduo/{name}.java') for name in names]
    subprocess.run(['javac','-encoding','UTF-8','-d',str(tmp),*sources],check=True)
    subprocess.run(['java','-cp',str(tmp),'io.github.yixing233.hyperduo.SettingsPlatformTest'],check=True)
