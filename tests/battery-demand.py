#!/usr/bin/env python3
"""Run real battery scheduling/selection code against counted, filtered Android services."""
import ast,subprocess,tempfile,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
SOURCE=ROOT/'app/src/main/java/io/github/yixing233/hyperduo'
tree=ast.parse((ROOT/'tests/memory-ticker.py').read_text(encoding='utf-8'))
fixtures=ast.literal_eval(next(n.value for n in tree.body if isinstance(n,ast.Assign) and any(isinstance(t,ast.Name) and t.id=='fixtures' for t in n.targets)))
fixtures['android/content/Intent.java']=r'''package android.content;public class Intent {
 public static final String ACTION_SCREEN_ON="on",ACTION_SCREEN_OFF="off",ACTION_BATTERY_CHANGED="battery";
 final String action;public java.util.Map<String,Integer> values=new java.util.HashMap<>();
 public Intent(String a){action=a;}public String getAction(){return action;}
 public int getIntExtra(String name,int fallback){return values.getOrDefault(name,fallback);}
}'''
fixtures['android/content/IntentFilter.java']=r'''package android.content;public class IntentFilter {
 public java.util.List<String> actions=new java.util.ArrayList<>();public IntentFilter(){}public IntentFilter(String a){addAction(a);}public void addAction(String a){actions.add(a);}
}'''
fixtures['android/os/BatteryManager.java']=r'''package android.os;public class BatteryManager {
 public static final int BATTERY_PROPERTY_CURRENT_NOW=1,BATTERY_STATUS_UNKNOWN=1,BATTERY_STATUS_CHARGING=2,BATTERY_STATUS_FULL=5;
 public static final String EXTRA_PLUGGED="plug",EXTRA_STATUS="status",EXTRA_TEMPERATURE="temp",EXTRA_VOLTAGE="voltage";
 public int calls,current=500000;public int getIntProperty(int property){calls++;return current;}
}'''
fixtures['android/content/Context.java']=r'''package android.content;public class Context {
 public static final int RECEIVER_EXPORTED=2;public static final String ACTIVITY_SERVICE="activity";
 public android.os.PowerManager power=new android.os.PowerManager();public android.os.BatteryManager battery=new android.os.BatteryManager();
 public java.util.Map<BroadcastReceiver,IntentFilter> receivers=new java.util.LinkedHashMap<>();
 public Intent sticky=new Intent(Intent.ACTION_BATTERY_CHANGED);public int stickyReads;
 public Context(){sticky.values.put("plug",1);sticky.values.put("status",2);sticky.values.put("temp",320);sticky.values.put("voltage",4000);}
 public Context getApplicationContext(){return this;}
 public Intent registerReceiver(BroadcastReceiver r,IntentFilter f){if(r==null){stickyReads++;return sticky;}receivers.put(r,f);return sticky;}
 public Intent registerReceiver(BroadcastReceiver r,IntentFilter f,int flags){return registerReceiver(r,f);}
 public <T>T getSystemService(Class<T> type){return type.cast(type==android.os.PowerManager.class?power:battery);}
 public void send(String action){for(var entry:receivers.entrySet())if(entry.getValue().actions.contains(action))entry.getKey().onReceive(this,new Intent(action));}
}'''
fixtures['io/github/yixing233/hyperduo/ColorOsHooks.java']=r'''package io.github.yixing233.hyperduo;class ColorOsHooks {
 static int normalized,statuses;static boolean isAodShowing(){return false;}static boolean isInstalled(){return true;}
 static boolean dispatchBatteryRefresh(Runnable r){return false;}static boolean dispatchBatteryPublish(Runnable r){return false;}
 static boolean batteryStatus(int status,int plug){statuses++;return status==2;}
 static ColorOsBattery.Sample batterySample(int current,int voltage){normalized++;return new ColorOsBattery.Sample(current,voltage);}
}'''
fixtures['io/github/yixing233/hyperduo/ColorOsBattery.java']=r'''package io.github.yixing233.hyperduo;class ColorOsBattery {static class Sample {
 final int currentMicroAmps,voltageMilliVolts;Sample(int c,int v){currentMicroAmps=c;voltageMilliVolts=v;}}}'''
fixtures['io/github/yixing233/hyperduo/MemoryTicker.java']='package io.github.yixing233.hyperduo;class MemoryTicker {static void pause(){}}'
fixtures.pop('io/github/yixing233/hyperduo/BatteryPresence.java')
fixtures['io/github/yixing233/hyperduo/TrioHooks.java']=r'''package io.github.yixing233.hyperduo;class TrioHooks {
 static int repaints;static void invalidateHosts(){repaints++;}
 static String perfText(TrioAppearance a){String s="";if(a.perfPower&&TrioState.sChargingAttached&&!Float.isNaN(TrioState.sChargePowerW))s=String.format(java.util.Locale.ROOT,"%.1fW",TrioState.sChargePowerW);
 if(a.perfTemp&&TrioState.sBatteryTempC!=TrioState.TEMP_UNKNOWN&&(!a.perfTempChargingOnly||TrioState.sChargingAttached))s+="T"+TrioState.sBatteryTempC;return s;}
}'''
state=(ROOT/'tests/shims/io/github/yixing233/hyperduo/TrioState.java').read_text(encoding='utf-8')
state=state.replace('final class TrioState {','final class TrioState {\n static final int TEMP_UNKNOWN=Integer.MIN_VALUE;static final float POWER_UNKNOWN=Float.NaN;static boolean sChargingAttached;static float sChargePowerW=Float.NaN;static int sBatteryTempC=TEMP_UNKNOWN;')
fixtures['io/github/yixing233/hyperduo/TrioState.java']=state
harness=r'''package io.github.yixing233.hyperduo;
import android.content.*;import android.os.*;
public class BatteryDemandTest {
 static void check(boolean ok,String note){if(!ok)throw new AssertionError(note);}
 public static void main(String[] args){
  Context c=new Context();TrioSettings s=TrioConfig.settings;s.enabled=true;s.perfPower=false;s.perfTemp=false;
  BatteryPresence.start(c);Handler.advance(0);int status=ColorOsHooks.statuses;
  check(c.battery.calls==0&&ColorOsHooks.normalized==0,"hidden power never reads current or vendor current normalization");
  Handler.advance(3000);check(ColorOsHooks.statuses==status,"no three-second status poll when no readout is used");
  Handler.advance(12000);check(ColorOsHooks.statuses==status+1,"low-frequency charging status recovery is retained");
  s.perfPower=true;TrioConfig.changed();Handler.advance(0);
  check(c.battery.calls==1&&ColorOsHooks.normalized==1,"enabling power samples immediately");
  int paints=TrioHooks.repaints;c.battery.current++;Handler.advance(3000);
  check(TrioHooks.repaints==paints,"power noise below display precision does not repaint");
  s.perfPower=false;s.perfTemp=true;TrioConfig.changed();Handler.advance(0);int reads=c.battery.calls;
  check(TrioState.sBatteryTempC==32,"temperature remains available without current reads");
  Handler.advance(3000);check(c.battery.calls==reads,"temperature-only sampling skips power IPC");
  c.power.interactive=false;c.send(Intent.ACTION_SCREEN_OFF);Handler.advance(0);status=ColorOsHooks.statuses;
  check(Handler.tasks.isEmpty(),"screen off removes periodic sampler callbacks");
  Handler.advance(60000);check(ColorOsHooks.statuses==status&&c.battery.calls==reads,"screen off does not poll");
  c.power.interactive=true;c.send(Intent.ACTION_SCREEN_ON);Handler.advance(0);
  check(ColorOsHooks.statuses==status+1,"waking samples charging state immediately");
  s.enabled=false;TrioConfig.changed();Handler.advance(0);status=ColorOsHooks.statuses;
  c.send(Intent.ACTION_BATTERY_CHANGED);Handler.advance(60000);
  check(Handler.tasks.isEmpty()&&ColorOsHooks.statuses==status,"disabled module leaves no callbacks or sampling");
  System.out.println("Battery demand, power precision, sleep/resume and disabled-module regression passed");
 }
}'''
with tempfile.TemporaryDirectory(prefix='colorduo-battery-demand-') as directory:
 files=[p for p in (ROOT/'tests/shims').rglob('*.java') if str(p.relative_to(ROOT/'tests/shims')) not in fixtures]
 for name,content in fixtures.items():
  p=Path(directory)/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content,encoding='utf-8');files.append(p)
 files += [SOURCE/(n+'.java') for n in ['Prefs','TrioSettings','TrioAppearance','TrioGeometry','TrioZones','TrioSizing','TrioRenderer','ChargeFlowRenderer','DuoPolicy','ChargeSpeed','ColorOsChargeSpeed','BatteryPresence','PowerSampling']]
 p=Path(directory)/'BatteryDemandTest.java';p.write_text(harness,encoding='utf-8');files.append(p)
 subprocess.run(['javac','-encoding','UTF-8','-d',directory,*map(str,files)],check=True)
 subprocess.run(['java','-Djava.awt.headless=true','-cp',directory,'io.github.yixing233.hyperduo.BatteryDemandTest'],check=True)
