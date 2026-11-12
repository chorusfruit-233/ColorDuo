#!/usr/bin/env python3
"""Execute native ownership against the OS15/16/17 resource and model shapes."""
from pathlib import Path
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/src/main/java/io/github/yixing233/hyperduo"
hooks = (SOURCE / "ColorOsHooks.java").read_text()


def block(pattern):
    match = re.search(pattern, hooks, re.M | re.S)
    if not match:
        raise RuntimeError(f"Production block missing: {pattern}")
    return match.group()


host_methods = "\n".join(block(r"^        (?:void|boolean) " + name + r"\([^\n]*\) \{.*?^        \}")
                         for name in ("capture", "syncSlots", "updateSlots", "releaseNativeTypes"))
host_methods += "\n" + block(r"^        void hide\(View view, boolean hidden\) \{.*?^        \}")
write_visibility = block(r"^    private static void writeVisibility\([^\n]*\) \{.*?^    \}")
mobile = block(r"^    private static final class Mobile \{.*?^    \}")
harness = r'''
package io.github.yixing233.hyperduo;
import java.util.*;
import java.lang.ref.WeakReference;
class CompatTest {
    static int checks;
    static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    interface ViewParent { ViewParent getParent(); }
    static class Drawable {}
    static class View implements ViewParent {
        static final int VISIBLE=0,INVISIBLE=4,GONE=8;
        final String id; ViewGroup parent; int visibility=VISIBLE;
        ViewGroup.LayoutParams params=new LinearLayout.LayoutParams(16,16);
        View(String id){this.id=id;}
        public ViewGroup getParent(){return parent;}
        int getVisibility(){return visibility;}
        void setVisibility(int value){
            var call=new HookRuntime.Call(this,new Object[]{value});
            writeVisibility(call);visibility=(Integer)call.args[0];
        }
        ViewGroup.LayoutParams getLayoutParams(){return params;}
        View findViewById(int key){return id.equals(IDS.get(key))?this:null;}
    }
    static class ViewGroup extends View {
        static class LayoutParams { int width,height; LayoutParams(int w,int h){width=w;height=h;} }
        final List<View> children=new ArrayList<>();
        ViewGroup(String id){super(id);}
        <T extends View>T add(T child){child.parent=this;children.add(child);return child;}
        int getChildCount(){return children.size();}
        View getChildAt(int index){return children.get(index);}
        void requestLayout(){}
        View findViewById(int key){
            View own=super.findViewById(key);if(own!=null)return own;
            for(View child:children){View result=child.findViewById(key);if(result!=null)return result;}
            return null;
        }
    }
    static class LinearLayout {
        static class LayoutParams extends ViewGroup.LayoutParams {
            LayoutParams(int w,int h){super(w,h);}
            LayoutParams(LayoutParams p){super(p.width,p.height);}
        }
    }
    static class ImageView extends View {
        enum ScaleType {CENTER,FIT_XY}
        final Drawable drawable=new Drawable();
        ImageView(String id){super(id);}
        Drawable getDrawable(){return drawable;}
        ScaleType getScaleType(){return ScaleType.CENTER;}
    }
    static class PhoneStatusBarView extends ViewGroup { PhoneStatusBarView(){super("status_bar");} }
    static class IconContainer extends ViewGroup {
        public final Set<String> mIgnoredSlots=new HashSet<>(Set.of("alarm_clock"));
        IconContainer(){super("statusIcons");}
        public void updateStates(){}
    }
    static class RadioView extends ViewGroup {
        RadioView(){super("radio");}
        public String getSlot(){return "mobile";}
    }
    static final Class<?> phoneBarClass=PhoneStatusBarView.class;
    static final Map<View,WeakReference<Host>> CHILDREN=new IdentityHashMap<>();
    static Host childOwner(View view){var ref=CHILDREN.get(view);return ref==null?null:ref.get();}
    static final List<String> IDS=new ArrayList<>(List.of("unused"));
    static int resource(String name){if(!IDS.contains(name))IDS.add(name);return IDS.indexOf(name);}
    static final ColorOsLayout.Tree<View> VIEW_TREE=new ColorOsLayout.Tree<>() {
        public View parent(View n){return n.parent;}
        public String id(View n){return n.id;}
        public int childCount(View n){return n instanceof ViewGroup?((ViewGroup)n).children.size():0;}
        public View childAt(View n,int i){return ((ViewGroup)n).children.get(i);}
        public boolean isIconContainer(View n){return n instanceof IconContainer;}
    };
    static class HookRuntime {
        static class Call {Object receiver;Object[] args;Call(Object r,Object[] a){receiver=r;args=a;} }
    }
    static class TrioHooks {static final int LOG_INFO=4;static void log(int i,String s){} }
    static class Config { boolean hideHd; }
    static class TrioConfig {static final Config config=new Config();static Config get(){return config;}static boolean debugLog(){return false;} }
    static class TrioAppearance {
        boolean type;
        boolean ownsType(){return type;}
        boolean foldsMobile(){return false;}
        boolean ownsWifi(){return false;}
    }
    static class State {int wifiLevel=-1,sims=1;DuoPolicy.Radio mobileService=DuoPolicy.Radio.AVAILABLE;}
    static class Extra {boolean hasVisibleRadioNotice(){return false;} }
    static class Host {
        final ViewGroup meter;final State state=new State();
        final Map<View,Integer> nativeVisibility=new IdentityHashMap<>(),visibilityOverrides=new IdentityHashMap<>();
        final Set<View> nativeTypes=Collections.newSetFromMap(new IdentityHashMap<>());
        final ColorOsPolicy.SlotOwnership slots=new ColorOsPolicy.SlotOwnership();
        ImageView image;ViewGroup iconContainer;Drawable nativeDrawable;LinearLayout.LayoutParams nativeParams;
        ImageView.ScaleType nativeScaleType;View clockContainer;Extra extra;String lastWifiHandoff;
        boolean active=true,moduleWriting;
        Host(ViewGroup meter){this.meter=meter;}
        int resourceId(String name){return resource(name);}
        View find(String name){return meter.findViewById(resource(name));}
        void releaseSlots(){updateSlots(Collections.emptySet());}
        void sync(TrioAppearance a){moduleWriting=true;try{syncSlots(a);}finally{moduleWriting=false;}}
        void release(){moduleWriting=true;try{releaseNativeTypes();releaseSlots();}finally{moduleWriting=false;}}
        // HOST_METHODS
    }
    static class Flow {Object value;Flow(Object value){this.value=value;}}
    static class Flows {
        Set<Object> active=Collections.newSetFromMap(new IdentityHashMap<>());
        Object read(Object a,Object b){return a!=null&&active.contains(a)?((Flow)a).value:b!=null&&active.contains(b)?((Flow)b).value:null;}
    }
    static final Flows SIGNAL_FLOWS=new Flows();
    // MOBILE_CLASS
    // WRITE_VISIBILITY
    public static void main(String[] args){
        // Layout shapes from the supplied SystemUI APKs; each row owns a separate tree.
        for(int version:new int[]{15,16,17}) {
            PhoneStatusBarView phone=new PhoneStatusBarView();
            View clock=version==15?phone.add(new View("clock")):phone.add(new ViewGroup("clock_for_fake"));
            if(clock instanceof ViewGroup)((ViewGroup)clock).add(new View("clock"));
            ViewGroup row=phone.add(new ViewGroup("system_icons"));
            IconContainer icons=row.add(new IconContainer());
            ViewGroup meter=row.add(new ViewGroup("battery"));
            meter.add(new ImageView("battery_icon_view"));
            Host h=new Host(meter);h.capture();
            check(h.clockContainer==clock,"OS"+version+" captures its own clock or animation wrapper");
            check(h.nativeVisibility.containsKey(clock),"clock participates in reversible native ownership");
            RadioView radio=icons.add(new RadioView());
            ImageView type=radio.add(new ImageView(version==17?"mobile_type_text":"mobile_type"));
            ImageView alternate=radio.add(new ImageView(version==17?"mobile_type_image":"mobile_type_alone"));
            alternate.visibility=View.GONE;
            ImageView hd=radio.add(new ImageView("mobile_volte"));
            ImageView signal=radio.add(new ImageView("mobile_signal"));
            TrioAppearance a=new TrioAppearance();a.type=true;TrioConfig.config.hideHd=false;h.sync(a);
            check(type.visibility==View.GONE,"module RAT hides the matching native label on OS"+version);
            check(signal.visibility==View.VISIBLE&&hd.visibility==View.VISIBLE,"RAT alone preserves native bars and HD");
            type.setVisibility(View.VISIBLE);
            check(type.visibility==View.GONE,"live native binder cannot flash the owned RAT label");
            a.type=false;TrioConfig.config.hideHd=true;h.sync(a);
            check(type.visibility==View.VISIBLE&&alternate.visibility==View.GONE,"RAT handoff restores each native state");
            check(hd.visibility==View.GONE&&icons.mIgnoredSlots.contains("volte"),"HD switch covers inline and standalone indicators");
            h.release();
            check(hd.visibility==View.VISIBLE&&icons.mIgnoredSlots.equals(Set.of("alarm_clock")),"release preserves unrelated native icon policy");
            check(!CHILDREN.containsKey(type)&&!CHILDREN.containsKey(hd),"released radio views stop intercepting writes");
            ViewGroup qs=new ViewGroup("quick_qs_status_icons");qs.add(new View("clock"));
            ViewGroup qsMeter=qs.add(new ViewGroup("batteryRemainingIcon"));qsMeter.add(new ImageView("battery_icon_view"));
            Host shade=new Host(qsMeter);shade.capture();
            check(shade.clockContainer==null,"a shade meter cannot take over a header clock");
        }
        Mobile mobile=new Mobile();mobile.icon=new Flow(new Object());
        mobile.iconGroup=new Flow(new LegacyIconModel("LTE+"));
        List<Object> wanted=new ArrayList<>();mobile.addFlows(wanted,false,true);
        check(wanted.contains(mobile.iconGroup)&&!wanted.contains(mobile.icon),"type-only layout collects old RAT flow without taking signal ownership");
        check("4G".equals(mobile.networkType("4G")),"uncollected legacy model uses the live radio fallback");
        SIGNAL_FLOWS.active.addAll(wanted);
        check("LTE+".equals(mobile.networkType("4G")),"collected OS15/16 icon group supplies the native RAT");
        ((Flow)mobile.iconGroup).value=new LegacyIconModel("5G_PLUS");
        check("5G+".equals(mobile.networkType("4G")),"native RAT changes do not retain the initial value");
        ((Flow)mobile.iconGroup).value=new LegacyIconModel("Unknown");
        check("4G".equals(mobile.networkType("4G")),"unknown native group falls back without inventing a label");
        mobile.renderedType="5GA";
        check("5GA".equals(mobile.networkType("4G")),"OS17 binder text retains priority");
        wanted.clear();mobile.addFlows(wanted,true,false);
        check(wanted.contains(mobile.icon)&&!wanted.contains(mobile.iconGroup),"signal-only layout leaves RAT collection alone");
        wanted.clear();mobile.addFlows(wanted,false,false);
        check(wanted.isEmpty(),"inactive layout requests no native streams");
        System.out.println("ColorOS 15/16/17 native ownership and network models: "+checks+" checks passed");
    }
    static class LegacyIconModel {final String name;LegacyIconModel(String name){this.name=name;}}
}'''
harness = harness.replace("// HOST_METHODS", host_methods).replace("// MOBILE_CLASS", mobile)
harness = harness.replace("// WRITE_VISIBILITY", write_visibility)
with tempfile.TemporaryDirectory(prefix="colorduo-compat-") as directory:
    root = Path(directory)
    test = root / "CompatTest.java"
    test.write_text(harness)
    sources = [str(SOURCE / name) for name in ("Refl.java", "ColorOsPolicy.java", "ColorOsLayout.java", "DuoPolicy.java")]
    subprocess.run(["javac", "-encoding", "UTF-8", "-d", str(root), *sources, str(test)], check=True)
    subprocess.run(["java", "-cp", str(root), "io.github.yixing233.hyperduo.CompatTest"], check=True)
