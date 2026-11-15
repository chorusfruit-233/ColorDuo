#!/usr/bin/env python3
"""Run the production clock against controlled services, wall time and Handler deadlines."""
from pathlib import Path
import subprocess
import tempfile
from sampler_fixtures import fixtures as sampler_fixtures

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/src/main/java/io/github/yixing233/hyperduo"
fixtures = dict(sampler_fixtures)
fixtures.update({
    "android/content/Intent.java": '''package android.content; public class Intent {
 public static final String ACTION_SCREEN_ON="screen_on",ACTION_SCREEN_OFF="screen_off",
 ACTION_TIME_CHANGED="time",ACTION_TIMEZONE_CHANGED="zone",ACTION_LOCALE_CHANGED="locale";
 final String action;public Intent(String a){action=a;}public String getAction(){return action;}
}''',
    "android/text/format/DateFormat.java": '''package android.text.format;
import java.util.*;import java.text.*;
public class DateFormat {
 public static boolean twentyFour=true,broken;public static int formats;
 public static boolean is24HourFormat(android.content.Context c){if(broken)throw new SecurityException();return twentyFour;}
 public static String getBestDateTimePattern(Locale l,String skeleton){formats++;return skeleton.equals("Hms")?"HH:mm:ss":"h:mm:ss a";}
 public static java.text.DateFormat getTimeFormat(android.content.Context c){formats++;return new SimpleDateFormat(twentyFour?"HH:mm":"h:mm a",Locale.getDefault());}
}''',
    "io/github/yixing233/hyperduo/ColorOsReload.java": '''package io.github.yixing233.hyperduo; class ColorOsReload {
 static boolean retiring;
 static java.util.List<android.os.Handler> handlers=new java.util.ArrayList<>();
 static java.util.List<Runnable> close=new java.util.ArrayList<>(),open=new java.util.ArrayList<>();
 static void watch(android.os.Handler h){handlers.add(h);}
 static void watch(Runnable c,Runnable o){close.add(c);open.add(o);}
 static void watch(android.content.Context c,android.content.BroadcastReceiver r,android.content.IntentFilter f){}
 static void stop(){retiring=true;for(Runnable r:close)r.run();for(android.os.Handler h:handlers)h.removeCallbacksAndMessages(null);}
 static void resume(){retiring=false;for(Runnable r:open)r.run();}
}''',
    "io/github/yixing233/hyperduo/MemoryTicker.java": "package io.github.yixing233.hyperduo; class MemoryTicker {static void pause(){}}",
})
scenes = ("boundaries", "minute", "seconds", "right", "middle", "hidden-middle", "disabled",
          "format", "sleep", "aod", "wake", "config", "time-event", "retire", "unavailable")
with tempfile.TemporaryDirectory(prefix="colorduo-clock-") as directory:
    files = [p for p in (ROOT / "tests/shims").rglob("*.java")
             if str(p.relative_to(ROOT / "tests/shims")) not in fixtures]
    for name, content in fixtures.items():
        path = Path(directory) / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
        files.append(path)
    files += [SOURCE / (name + ".java") for name in (
        "Prefs", "TrioSettings", "TrioAppearance", "TrioGeometry", "TrioZones", "TrioSizing",
        "TrioRenderer", "ChargeFlowRenderer", "DuoPolicy", "ChargeSpeed", "ColorOsChargeSpeed", "ClockTicker", "PowerSampling")]
    files.append(ROOT / "tests/ClockTickerTest.java")
    subprocess.run(["javac", "-encoding", "UTF-8", "-d", directory, *map(str, files)], check=True)
    for scene in scenes:
        subprocess.run(["java", "-Djava.awt.headless=true", "-cp", directory,
                        "io.github.yixing233.hyperduo.ClockTickerTest", scene], check=True)
    print(f"Clock seconds, layout, time changes and lifecycle: {len(scenes)} scenarios passed")
