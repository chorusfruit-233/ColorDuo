#!/usr/bin/env python3
"""Execute the real Host activation/restoration methods with independent native view fixtures."""
from pathlib import Path
import argparse
import re
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "app/src/main/java/io/github/yixing233/hyperduo"
parser = argparse.ArgumentParser()
parser.add_argument("--hooks-source", type=Path, default=SOURCE / "ColorOsHooks.java")
hooks = parser.parse_args().hooks_source.read_text()


def method(name):
    match = re.search(rf"^        void {name}\([^\n]*\) \{{.*?^        \}}", hooks, re.M | re.S)
    if not match:
        raise RuntimeError(f"Production Host method missing: {name}")
    return match.group()


methods = "\n".join(method(name) for name in ("applyInternal", "restoreBatteryInternal", "syncExtra"))
harness = r'''
package io.github.yixing233.hyperduo;
import java.util.*;

public final class ColorOsHostTest {
    static class Drawable { }
    interface ViewParent { }
    static class View implements ViewParent {
        static final int VISIBLE=0, GONE=8;
        int visibility=VISIBLE;
        boolean attached=true;
        View parent;
        ViewGroup.LayoutParams params=new LinearLayout.LayoutParams(18,16);
        final Resources resources=new Resources();
        int getVisibility(){return visibility;}
        void setVisibility(int value){visibility=value;}
        boolean isAttachedToWindow(){return attached;}
        Resources getResources(){return resources;}
        Object getContext(){return this;}
        View getParent(){return parent;}
        ViewGroup.LayoutParams getLayoutParams(){return params;}
        void setLayoutParams(ViewGroup.LayoutParams value){params=value;}
    }
    static class Resources { final Metrics metrics=new Metrics(); Metrics getDisplayMetrics(){return metrics;} }
    static class Metrics { float density=1; }
    static class ViewGroup extends View {
        static class LayoutParams {
            static final int WRAP_CONTENT=-2, MATCH_PARENT=-1;
            int width,height;
            LayoutParams(int w,int h){width=w;height=h;}
        }
        final List<View> children=new ArrayList<>();
        int indexOfChild(View v){return children.indexOf(v);}
        void addView(View view,int at,LayoutParams p){view.parent=this;view.params=p;children.add(at,view);}
    }
    static class LinearLayout extends ViewGroup {
        static class LayoutParams extends ViewGroup.LayoutParams {
            LayoutParams(int w,int h){super(w,h);}
            LayoutParams(LayoutParams p){this(p.width,p.height);}
        }
    }
    static class ImageView extends View {
        enum ScaleType { CENTER, FIT_XY }
        Drawable drawable=new Drawable();
        ScaleType scale=ScaleType.CENTER;
        Drawable getDrawable(){return drawable;}
        void setImageDrawable(Drawable value){drawable=value;}
        void setScaleType(ScaleType value){scale=value;}
    }
    static class Glyph extends Drawable { void invalidateSelf(){} }
    static class State { int level=65,wifiLevel=3;boolean wifiPresent=true,clock; }
    static class TrioRenderer {
        static boolean drawsClock(State state,TrioAppearance appearance){return state.clock;}
    }
    static class Config { boolean enabled=true; }
    static class TrioConfig {
        static Config config=new Config();
        static TrioAppearance appearance=new TrioAppearance();
        static Config get(){return config;}
        static TrioAppearance appearance(){return appearance;}
    }
    static class TrioAppearance {
        boolean capsule,ring=true;
        int splitMode;
        int signalIconScale=100;
        boolean drawsSimPair(){return false;}
        int capsuleLength=100;
        boolean handsBackPercentage(){return false;}
        boolean hidesNativeBolt(){return false;}
        boolean hidesStatusClock(){return false;}
    }
    static class TrioGeometry {
        static class SplitRing { SplitRing(TrioAppearance a){} float width(){return 240;} float height(){return 120;} }
        static void setCapsuleLength(int v){}
        static int capHostHeightPx(int v){return v;}
        static int capHostWidthPx(int v){return v*3;}
    }
    static class TrioState { static String sClockText="12:34"; }
    static class TrioHooks {
        static final int LOG_INFO=4;
        static void log(int level,String note){}
    }
    static class ColorOsReadings extends View {
        ColorOsReadings(Object context,State state,ImageView image){}
        void update(TrioAppearance appearance){}
    }
    static final Map<Integer,Object> MOBILES=new HashMap<>();
    static Object wifiFlow=new Object();
    static final ColorOsLayout.Tree<View> VIEW_TREE=new ColorOsLayout.Tree<>() {
        public View parent(View view){return view.parent;}
        public String id(View view){return "";}
        public int childCount(View view){return view instanceof ViewGroup?((ViewGroup)view).children.size():0;}
        public View childAt(View view,int index){return ((ViewGroup)view).children.get(index);}
        public boolean isIconContainer(View view){return false;}
    };
    static String resourceName(View view){return "fixture";}
    static void start(Object context){}
    static class Host {
        final ViewGroup meter=new LinearLayout();
        final State state=new State();
        final Glyph glyph=new Glyph();
        final ImageView image=new ImageView();
        final Drawable nativeDrawable=image.drawable;
        final LinearLayout.LayoutParams nativeParams=new LinearLayout.LayoutParams(18,16);
        final ImageView.ScaleType nativeScaleType=ImageView.ScaleType.CENTER;
        final Map<View,Integer> nativeVisibility=new IdentityHashMap<>();
        final Map<View,Integer> visibilityOverrides=new IdentityHashMap<>();
        ColorOsReadings extra;
        View clockContainer=new View();
        int nativeDepth,ownedWidth,ownedHeight;
        boolean active,failed,logged;
        final boolean main;
        Host(boolean main,boolean constraintParent){
            this.main=main;
            ViewGroup parent=constraintParent?new ViewGroup():new LinearLayout();
            parent.addView(meter,0,new LinearLayout.LayoutParams(-2,-1));
            meter.addView(image,0,new LinearLayout.LayoutParams(18,16));
            nativeVisibility.put(image,View.VISIBLE);
            nativeVisibility.put(clockContainer,View.VISIBLE);
        }
        // Retained in the fixture so re-introducing the old main-only predicate fails QS/keyguard tests.
        boolean primaryBar(){return main;}
        void capture(){}
        void readState(){}
        void hide(String name,boolean hidden){}
        void hide(View view,boolean hidden){if(view!=null)view.setVisibility(hidden?View.GONE:nativeVisibility.get(view));}
        void syncSlots(TrioAppearance appearance){}
        void restore(){restoreBatteryInternal();}

@METHODS@
    }
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args){
        Host main=new Host(true,false);
        Host keyguard=new Host(false,false);
        Host notification=new Host(false,true);
        Host controls=new Host(false,true);
        for(Host host:List.of(main,keyguard,notification,controls)){
            host.applyInternal();
            check(host.active&&host.image.drawable==host.glyph,"all four contexts must display the shared glyph");
            check(host.extra!=null,"every supported context mounts its readouts");
        }
        check(main.extra.parent==main.meter.parent,"standard rows reserve a sibling strip");
        check(keyguard.extra.parent==keyguard.meter.parent,"keyguard owns its own strip");
        check(notification.extra.parent==notification.meter,"notification ConstraintLayout reserves inside its meter");
        check(controls.extra.parent==controls.meter,"control-panel ConstraintLayout reserves inside its meter");
        TrioConfig.appearance.splitMode=1;
        main.applyInternal();
        check(main.image.params.width==40,"split geometry reserves additional native row width");
        TrioConfig.appearance.splitMode=0;
        main.applyInternal();
        check(main.image.params.width==24,"classic geometry returns to original row width");
        main.state.clock=true;
        main.applyInternal();
        check(main.clockContainer.visibility==View.GONE,"a frame that draws time hides the native clock");
        main.state.clock=false;
        main.applyInternal();
        check(main.clockContainer.visibility==View.VISIBLE,"a fallback change hands time back immediately");
        main.state.clock=true;
        main.applyInternal();
        keyguard.state.level=-1;
        keyguard.applyInternal();
        check(!keyguard.active&&keyguard.image.drawable==keyguard.nativeDrawable,"unknown battery restores only that host");
        check(main.active&&controls.active,"one host's restoration must not disable other contexts");
        controls.meter.attached=false;
        controls.applyInternal();
        check(!controls.active&&controls.image.drawable==controls.nativeDrawable,"detached host restores native drawing");
        check(controls.image.params.width==18&&controls.image.params.height==16,"native dimensions are restored");
        check(controls.image.scale==ImageView.ScaleType.CENTER,"native scale type is restored");
        controls.meter.attached=true;
        controls.applyInternal();
        check(controls.active,"reattached control panel reclaims its own glyph");
        TrioConfig.config.enabled=false;
        for(Host host:List.of(main,keyguard,notification,controls)){
            host.applyInternal();
            check(!host.active&&host.image.drawable==host.nativeDrawable,"master off restores every context");
            check(host.extra.visibility==View.GONE,"master off removes each readout strip");
            check(host.clockContainer.visibility==View.VISIBLE,"master off restores each original clock state");
        }
        System.out.println("ColorOS production host activation, header mounting and per-instance restoration passed");
    }
}
'''.replace("@METHODS@", methods)
with tempfile.TemporaryDirectory(prefix="colorduo-hosts-") as directory:
    fixture=Path(directory)/"ColorOsHostTest.java"
    fixture.write_text(harness)
    compiler=[shutil.which("javac")] if shutil.which("javac") else ["java","-m","jdk.compiler/com.sun.tools.javac.Main"]
    subprocess.run(compiler+["-encoding","UTF-8","-d",directory,str(SOURCE/"ColorOsLayout.java"),
                             str(ROOT/"tests/ColorOsLayoutTest.java"),str(fixture)],check=True)
    for test in ("ColorOsLayoutTest","ColorOsHostTest"):
        subprocess.run(["java","-cp",directory,"io.github.yixing233.hyperduo."+test],check=True)
