#!/usr/bin/env python3
"""Run the production AOD gate, native callbacks and Glyph drawing/scheduling with Android fixtures."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/src/main/java/io/github/yixing233/hyperduo"
hooks = (SOURCE / "ColorOsHooks.java").read_text()
start = hooks.index("    private static final class Glyph extends Drawable {")
end, depth = hooks.index("{", start) + 1, 1
while depth:
    depth += (hooks[end] == "{") - (hooks[end] == "}")
    end += 1
glyph = hooks[start:end]

fixtures = {
"android/content/SharedPreferences.java": "package android.content; public interface SharedPreferences {}",
"android/content/Intent.java": '''package android.content; public class Intent {
 public static final String ACTION_SCREEN_OFF="off",ACTION_SCREEN_ON="on";
 final String action;public Intent(String s){action=s;}public String getAction(){return action;}
}''',
"android/content/IntentFilter.java": '''package android.content;public class IntentFilter {
 public final java.util.Set<String> actions=new java.util.HashSet<>();public void addAction(String s){actions.add(s);}
}''',
"android/content/BroadcastReceiver.java": '''package android.content;public abstract class BroadcastReceiver {
 public abstract void onReceive(Context c,Intent i);
}''',
"android/content/Context.java": '''package android.content;public class Context {
 public static final int RECEIVER_EXPORTED=2;public android.os.PowerManager power=new android.os.PowerManager();
 public BroadcastReceiver receiver;public IntentFilter filter;public int flags=-1;
 public <T>T getSystemService(Class<T> type){return type.cast(power);}
 public Intent registerReceiver(BroadcastReceiver r,IntentFilter f){receiver=r;filter=f;return null;}
 public Intent registerReceiver(BroadcastReceiver r,IntentFilter f,int v){flags=v;return registerReceiver(r,f);}
 public void unregisterReceiver(BroadcastReceiver r){if(receiver!=r)throw new AssertionError();receiver=null;}
}''',
"android/os/PowerManager.java": '''package android.os;public class PowerManager {
 public boolean interactive=true,broken;public boolean isInteractive(){if(broken)throw new IllegalStateException();return interactive;}
}''',
"android/os/Build.java": "package android.os;public class Build {public static class VERSION {public static int SDK_INT=37;}}",
"android/view/Display.java": '''package android.view;public class Display {
 public static final int STATE_ON=2;public int state=2;public int getState(){return state;}
}''',
"android/view/View.java": '''package android.view;public class View {
 public static final int VISIBLE=0;public final android.content.Context context=new android.content.Context();
 public Display display=new Display();public boolean shown=true;public int visibility,posts;
 public android.content.Context getContext(){return context;}public Display getDisplay(){return display;}
 public boolean isShown(){return shown;}public int getWindowVisibility(){return visibility;}
 public void postInvalidateOnAnimation(){posts++;}
}''',
"android/graphics/Rect.java": '''package android.graphics;public class Rect {
 public int left,top,right=24,bottom=20;public int width(){return right-left;}public int height(){return bottom-top;}
 public boolean isEmpty(){return width()<=0||height()<=0;}
}''',
"android/graphics/Canvas.java": '''package android.graphics;public class Canvas {
 public int save(){return 1;}public int saveLayer(float a,float b,float c,float d,Paint p){return 2;}
 public void translate(float a,float b){}public void clipRect(int a,int b,int c,int d){}public void restoreToCount(int n){}
}''',
"android/graphics/Paint.java": "package android.graphics;public class Paint {public void setAlpha(int a){}public void setColorFilter(ColorFilter c){}}",
"android/graphics/ColorFilter.java": "package android.graphics;public class ColorFilter {}",
"android/graphics/PixelFormat.java": "package android.graphics;public class PixelFormat {public static final int TRANSLUCENT=-3;}",
"android/graphics/drawable/Drawable.java": '''package android.graphics.drawable;public abstract class Drawable {
 private final android.graphics.Rect bounds=new android.graphics.Rect();public android.graphics.Rect getBounds(){return bounds;}
 public void invalidateSelf(){}public abstract void draw(android.graphics.Canvas c);
 public abstract int getIntrinsicWidth();public abstract int getIntrinsicHeight();
 public abstract void setAlpha(int a);public abstract void setColorFilter(android.graphics.ColorFilter c);public abstract int getOpacity();
}''',
"com/oplus/systemui/aod/aodclock/constant/AodData.java": '''package com.oplus.systemui.aod.aodclock.constant;
 public class AodData {public static AodData sAodData;public static int constructed;public boolean mAodIsInShow;
 public AodData(){constructed++;}public void setAodIsInShow(boolean v){mAodIsInShow=v;}}
''',
"io/github/yixing233/hyperduo/ColorOsReload.java": '''package io.github.yixing233.hyperduo;
 class ColorOsReload {static Runnable close,reopen;
 static void watch(android.content.Context c,android.content.BroadcastReceiver r,android.content.IntentFilter f){
 close=()->c.unregisterReceiver(r);reopen=()->c.registerReceiver(r,f,android.content.Context.RECEIVER_EXPORTED);}}
''',
"io/github/yixing233/hyperduo/DozeTest.java": r'''package io.github.yixing233.hyperduo;
import android.content.*;import android.graphics.*;import android.graphics.drawable.Drawable;
import android.view.*;import java.lang.reflect.*;
import com.oplus.systemui.aod.aodclock.constant.AodData;
public class DozeTest {
 static int checks,repaints;static void check(boolean v,String text){checks++;if(!v)throw new AssertionError(text);}
 static ColorOsDoze doze;
 static final Main MAIN=new Main();static class Main {void post(Runnable r){r.run();}}
 static class Settings {}static class TrioConfig {static Settings get(){return new Settings();}}
 static class State {int level=66;boolean charging=true,chargingPaused;}
 static class TrioRenderer {static boolean allowed;static int draws;
  static void drawSizedState(Canvas c,int w,int h,State s,Settings cfg,float scale,boolean clear,boolean animate){allowed=animate;draws++;}
  static boolean chargeFlowActive(Settings s,int level,boolean charging,boolean paused){return true;}
 }
 static class Host {boolean active=true,failed;final View image=new View(),meter=new View();
  final State state=new State();float ownedScale=1;void traceDraw(Canvas c,Rect r){}void fail(Throwable t){throw new AssertionError(t);}}
 @GLYPH@
 static class Runtime implements HookRuntime {
  Step before,after;Executable method;boolean reject;
  public void hook(Executable m,String id,Step b,Step a){if(reject)throw new IllegalStateException();method=m;before=b;after=a;}
  public void log(int p,String t,String m){}public int getApiVersion(){return 102;}
  void set(AodData data,boolean value)throws Throwable {Call c=new Call(method,data,new Object[]{value});before.run(c);
   data.setAodIsInShow(value);after.run(c);}
 }
 public static void main(String[] args)throws Throwable {
  doze=new ColorOsDoze(DozeTest.class.getClassLoader());Host host=new Host();Glyph glyph=new Glyph(host);Canvas canvas=new Canvas();
  check(doze.allowsAnimation(host.image),"awake view permits animation before native AOD initialization");
  check(AodData.constructed==0&&AodData.sAodData==null,"gate never creates the AOD subsystem");
  glyph.draw(canvas);check(TrioRenderer.allowed&&host.image.posts==1,"awake glyph draws and schedules its next frame");
  host.image.context.power.interactive=false;glyph.draw(canvas);glyph.draw(canvas);
  check(!TrioRenderer.allowed&&host.image.posts==1,"noninteractive visible glyph draws static and stops self-invalidating");
  host.image.context.power.interactive=true;
  for(int state:new int[]{0,1,3,4,6}) {host.image.display.state=state;glyph.draw(canvas);
   check(!TrioRenderer.allowed&&host.image.posts==1,"off, unknown and suspended display states never animate");}
  host.image.display.state=Display.STATE_ON;
  Runtime runtime=new Runtime();check(doze.install(runtime,()->repaints++)==1,"native AOD setter is observed");
  AodData data=new AodData();AodData.sAodData=data;runtime.set(data,true);glyph.draw(canvas);
  check(!TrioRenderer.allowed&&host.image.posts==1,"panorama AOD vetoes animation even with an ON interactive display");
  check(repaints==1,"entering AOD requests a static redraw");runtime.set(data,true);
  check(repaints==1,"repeated native status does not start periodic redraws");runtime.set(data,false);glyph.draw(canvas);
  check(repaints==2&&TrioRenderer.allowed&&host.image.posts==2,"leaving AOD repaints and resumes flow");
  Context context=host.image.context;doze.watch(context,()->{repaints++;glyph.draw(canvas);});
  check(context.flags==Context.RECEIVER_EXPORTED&&context.filter.actions.size()==2,"only screen transitions are subscribed");
  context.power.interactive=false;context.receiver.onReceive(context,new Intent(Intent.ACTION_SCREEN_OFF));
  check(!TrioRenderer.allowed&&host.image.posts==2,"screen-off callback clears the highlight and stops frames");
  int before=repaints;context.receiver.onReceive(context,new Intent("other"));check(repaints==before,"unrelated broadcasts ignored");
  context.power.interactive=true;context.receiver.onReceive(context,new Intent(Intent.ACTION_SCREEN_ON));
  check(TrioRenderer.allowed&&host.image.posts==3,"screen-on callback restarts an enabled flow");
  ColorOsReload.close.run();check(context.receiver==null,"receiver participates in hot-reload cleanup");
  ColorOsReload.reopen.run();check(context.receiver!=null,"failed reload can restore the screen observer");
  host.image.shown=false;glyph.draw(canvas);check(host.image.posts==3,"hidden glyph retains existing scheduling guard");host.image.shown=true;
  host.meter.visibility=8;glyph.draw(canvas);check(host.image.posts==3,"hidden window never schedules");host.meter.visibility=0;
  host.image.display=null;glyph.draw(canvas);check(!TrioRenderer.allowed&&host.image.posts==3,"unattached display stays static");
  host.image.display=new Display();context.power.broken=true;glyph.draw(canvas);
  check(!TrioRenderer.allowed&&!host.failed,"power query failure preserves static rendering");context.power.broken=false;
  doze=new ColorOsDoze(new ClassLoader(DozeTest.class.getClassLoader()){
   @Override public Class<?> loadClass(String name)throws ClassNotFoundException {
    if(name.endsWith(".AodData"))throw new ClassNotFoundException(name);return super.loadClass(name);}});
  check(doze.install(new Runtime(),()->repaints++)==0&&doze.allowsAnimation(host.image),"firmware without native AOD fields uses platform checks");
  context.power.interactive=false;check(!doze.allowsAnimation(host.image),"platform sleep gate survives missing OEM classes");
  android.os.Build.VERSION.SDK_INT=32;Context older=new Context();doze.watch(older,()->{});
  check(older.receiver!=null&&older.flags==-1,"older API receiver overload remains supported");
  System.out.println("AOD state, static rendering handoff, wake and frame scheduling: "+checks+" checks passed");
 }
}'''.replace("@GLYPH@", glyph),
}
with tempfile.TemporaryDirectory(prefix="colorduo-doze-") as directory:
    files = []
    for name, content in fixtures.items():
        path = Path(directory) / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
        files.append(path)
    files += [SOURCE / (name + ".java") for name in ("ColorOsDoze", "Refl", "HookRuntime")]
    subprocess.run(["javac", "-encoding", "UTF-8", "-d", directory, *map(str, files)], check=True)
    subprocess.run(["java", "-cp", directory, "io.github.yixing233.hyperduo.DozeTest"], check=True)
