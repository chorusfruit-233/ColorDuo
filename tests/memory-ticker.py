#!/usr/bin/env python3
"""Exercise the production RAM sampler with real layout settings and controlled Android services."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/src/main/java/io/github/yixing233/hyperduo"
fixtures = {
    "android/os/Build.java": "package android.os; public class Build {public static class VERSION {public static final int SDK_INT=34;}}",
    "android/content/Intent.java": "package android.content; public class Intent {public static final String ACTION_SCREEN_ON=\"screen_on\",ACTION_SCREEN_OFF=\"screen_off\"; final String action;public Intent(String a){action=a;}public String getAction(){return action;}}",
    "android/content/IntentFilter.java": "package android.content; public class IntentFilter {public void addAction(String a){}}",
    "android/content/BroadcastReceiver.java": "package android.content; public abstract class BroadcastReceiver {public abstract void onReceive(Context c,Intent i);}",
    "io/github/yixing233/hyperduo/BatteryPresence.java": "package io.github.yixing233.hyperduo; class BatteryPresence {static void pause(){}}",
    "android/os/Looper.java": """package android.os; public class Looper {
 public static final Looper MAIN=new Looper(); public static boolean onMain=true;
 public static Looper getMainLooper(){return MAIN;} public static Looper myLooper(){return onMain?MAIN:null;}
}""",
    "android/os/SystemClock.java": "package android.os; public class SystemClock {public static long now; public static long uptimeMillis(){return now;}}",
    "android/os/Handler.java": """package android.os; import java.util.*; public class Handler {
 public record Task(Handler owner,Runnable run,long due){} public static final List<Task> tasks=new ArrayList<>();
 public Handler(Looper l){} public boolean post(Runnable r){return postDelayed(r,0);}
 public boolean postDelayed(Runnable r,long delay){tasks.add(new Task(this,r,SystemClock.now+delay));return true;}
 public void removeCallbacks(Runnable r){tasks.removeIf(t->t.owner==this&&t.run==r);}
 public void removeCallbacksAndMessages(Object token){tasks.removeIf(t->t.owner==this);}
 public static void advance(long ms){SystemClock.now+=ms;for(;;){Task next=null;
  for(Task t:tasks)if(t.due<=SystemClock.now&&(next==null||t.due<next.due))next=t;
  if(next==null)return;tasks.remove(next);next.run.run();}}
}""",
    "android/os/PowerManager.java": """package android.os; public class PowerManager {
 public boolean interactive=true,broken; public boolean isInteractive(){if(broken)throw new IllegalStateException();return interactive;}
}""",
    "android/app/ActivityManager.java": """package android.app; public class ActivityManager {
 public long total=1000,available=320;public int calls;public boolean broken;
 public static class MemoryInfo {public long totalMem,availMem;}
 public void getMemoryInfo(MemoryInfo out){calls++;if(broken)throw new SecurityException();out.totalMem=total;out.availMem=available;}
}""",
    "android/content/Context.java": """package android.content; public class Context {
 public static final String ACTIVITY_SERVICE="activity"; public android.app.ActivityManager memory=new android.app.ActivityManager();
 public android.os.PowerManager power=new android.os.PowerManager();public Context application=this;
 public java.util.List<BroadcastReceiver> receivers=new java.util.ArrayList<>();
 public Intent registerReceiver(BroadcastReceiver receiver,IntentFilter filter){receivers.add(receiver);return null;}
 public Intent registerReceiver(BroadcastReceiver receiver,IntentFilter filter,int flags){return registerReceiver(receiver,filter);}
 public static final int RECEIVER_EXPORTED=2;
 public void send(String action){for(BroadcastReceiver receiver:receivers)receiver.onReceive(this,new Intent(action));}
 public Context getApplicationContext(){return application;}
 public Object getSystemService(String type){return memory;}
 public <T>T getSystemService(Class<T> type){return type.cast(type==android.os.PowerManager.class?power:memory);}
}""",
    "io/github/yixing233/hyperduo/TrioConfig.java": """package io.github.yixing233.hyperduo; class TrioConfig {
 static TrioSettings settings=TrioSettings.defaults();static TrioSettings get(){return settings;}
 static java.util.List<Runnable> listeners=new java.util.ArrayList<>();
 static void addListener(Runnable r){listeners.add(r);} static void changed(){for(Runnable r:listeners)r.run();}
 static TrioAppearance appearance(){return TrioAppearance.of(settings);}static boolean debugLog(){return false;}
}""",
    "io/github/yixing233/hyperduo/TrioHooks.java": """package io.github.yixing233.hyperduo; class TrioHooks {
 static int repaints;static void invalidateHosts(){repaints++;}
}""",
    "io/github/yixing233/hyperduo/ColorOsHooks.java": """package io.github.yixing233.hyperduo; class ColorOsHooks {
 static boolean aod;static boolean isAodShowing(){return aod;}
}""",
    "io/github/yixing233/hyperduo/ColorOsReload.java": """package io.github.yixing233.hyperduo; class ColorOsReload {
 static boolean retiring;static android.os.Handler watched;
 static java.util.List<android.os.Handler> handlers=new java.util.ArrayList<>();
 static void watch(android.os.Handler h){watched=h;handlers.add(h);}
 static void watch(Runnable close,Runnable open){}
 static void watch(android.content.Context c,android.content.BroadcastReceiver r,android.content.IntentFilter f){}
 static void stop(){for(android.os.Handler h:handlers)h.removeCallbacksAndMessages(null);}
}""",
}
scenes = ("sampling", "unavailable", "sleep", "aod", "rectangle", "unused-left", "disabled",
          "removed", "throttle", "retired", "wake", "thread", "missing-power", "screen-event", "config-event", "interval")
with tempfile.TemporaryDirectory(prefix="colorduo-memory-") as directory:
    files = [p for p in (ROOT / "tests/shims").rglob("*.java") if str(p.relative_to(ROOT / "tests/shims")) not in fixtures]
    for name, content in fixtures.items():
        path = Path(directory) / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
        files.append(path)
    files += [SOURCE / (name + ".java") for name in (
        "Prefs", "TrioSettings", "TrioAppearance", "TrioGeometry", "TrioZones", "TrioSizing",
        "TrioRenderer", "ChargeFlowRenderer", "DuoPolicy", "ChargeSpeed", "ColorOsChargeSpeed", "MemoryTicker", "PowerSampling")]
    files.append(ROOT / "tests/MemoryTickerTest.java")
    subprocess.run(["javac", "-encoding", "UTF-8", "-d", directory, *map(str, files)], check=True)
    failures = []
    for scene in scenes:
        result = subprocess.run(["java", "-Djava.awt.headless=true", "-cp", directory,
                                 "io.github.yixing233.hyperduo.MemoryTickerTest", scene])
        if result.returncode:
            failures.append(scene)
    if failures:
        raise SystemExit("RAM regressions failed: " + ", ".join(failures))
    print(f"RAM sampling, layout ownership, sleep and lifecycle: {len(scenes)} scenarios passed")

subprocess.run([__import__("sys").executable, str(ROOT / "tests/battery-demand.py")], check=True)
