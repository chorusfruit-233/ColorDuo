#!/usr/bin/env python3
"""Run production binder transactions/interceptors, observing every intermediate write."""
from pathlib import Path
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
hooks = (ROOT / 'app/src/main/java/io/github/yixing233/hyperduo/ColorOsHooks.java').read_text()
names = ['writeDrawable', 'readDrawable', 'writeScaleType', 'writeVisibility',
         'writeLayoutParams', 'readLayoutParams', 'nativeUpdate']
methods = []
for name in names:
    match = re.search(r'    private static Object ' + name + r'\(.*?^    \}', hooks, re.M | re.S)
    assert match, name
    methods.append(match.group())
    if name != 'nativeUpdate':
        assert 'ColorOsHooks::' + name in hooks, 'Production interceptor must be registered'

harness = r'''
import java.util.*;
class BindingTest {
    static int checks;
    static void check(boolean v,String m){checks++;if(!v)throw new AssertionError(m);}
    interface Action {Object run(Object[] args) throws Throwable;}
    static class XposedInterface {
        static class Chain {
            final Object object;final Object[] args;final Action action;
            Chain(Object o,Action a,Object... arguments){object=o;action=a;args=arguments;}
            Object getThisObject(){return object;}
            Object getArg(int i){return args[i];}
            Object proceed() throws Throwable{return action.run(args);}
            Object proceed(Object[] replacement) throws Throwable{return action.run(replacement);}
        }
    }
    static class Drawable { }
    static class View {
        static final int VISIBLE=0,GONE=8;
        int visibility=VISIBLE;
        ViewGroup.LayoutParams params=new LinearLayout.LayoutParams(18,16);
        void setVisibility(int v) throws Throwable {
            writeVisibility(new XposedInterface.Chain(this,a->{visibility=(Integer)a[0];return null;},v));
        }
        ViewGroup.LayoutParams getLayoutParams() throws Throwable {
            return (ViewGroup.LayoutParams)readLayoutParams(new XposedInterface.Chain(this,a->params));
        }
        void setLayoutParams(ViewGroup.LayoutParams v) throws Throwable {
            writeLayoutParams(new XposedInterface.Chain(this,a->{params=(ViewGroup.LayoutParams)a[0];return null;},v));
        }
    }
    static class ViewGroup extends View {
        ImageView child;
        static class LayoutParams {int width,height;LayoutParams(int w,int h){width=w;height=h;}}
    }
    static class LinearLayout {
        static class LayoutParams extends ViewGroup.LayoutParams {
            LayoutParams(int w,int h){super(w,h);}
            LayoutParams(LayoutParams p){super(p.width,p.height);}
        }
    }
    static class ImageView extends View {
        enum ScaleType {CENTER,FIT_XY}
        Drawable drawable=new Drawable();ScaleType scale=ScaleType.CENTER;
        Drawable getDrawable() throws Throwable {
            return (Drawable)readDrawable(new XposedInterface.Chain(this,a->drawable));
        }
        void setImageDrawable(Drawable d) throws Throwable {
            writeDrawable(new XposedInterface.Chain(this,a->{drawable=(Drawable)a[0];return null;},d));
        }
        void setScaleType(ScaleType s) throws Throwable {
            writeScaleType(new XposedInterface.Chain(this,a->{scale=(ScaleType)a[0];return null;},s));
        }
    }
    static final Map<View,Host> owners=new IdentityHashMap<>();
    static Host childOwner(View v){return owners.get(v);}
    static class Host {
        final ViewGroup meter=new ViewGroup();
        ImageView image;
        final View nativeIcon=new View();
        final Drawable glyph=new Drawable();
        Drawable nativeDrawable;
        LinearLayout.LayoutParams nativeParams;
        ImageView.ScaleType nativeScaleType;
        final Map<View,Integer> nativeVisibility=new IdentityHashMap<>(),visibilityOverrides=new IdentityHashMap<>();
        int nativeDepth,ownedWidth=50,ownedHeight=20,captures,restores;
        boolean active,moduleWriting,enabled=true,failed;
        Host() throws Throwable {meter.child=new ImageView();capture();apply();}
        View find(String id){return meter.child;}
        void capture() throws Throwable {
            captures++;image=meter.child;if(image==null)return;
            nativeDrawable=image.getDrawable();nativeParams=new LinearLayout.LayoutParams((LinearLayout.LayoutParams)image.params);
            nativeScaleType=image.scale;owners.put(image,this);owners.put(nativeIcon,this);
            nativeVisibility.put(image,image.visibility);nativeVisibility.put(nativeIcon,nativeIcon.visibility);
        }
        void apply() throws Throwable {
            if(nativeDepth!=0)return;
            if(!enabled||failed){restoreBattery();return;}
            if(image==null)return;
            moduleWriting=true;
            try {
                active=true;image.setImageDrawable(glyph);image.setScaleType(ImageView.ScaleType.FIT_XY);
                image.setLayoutParams(new LinearLayout.LayoutParams(ownedWidth,ownedHeight));
                visibilityOverrides.put(image,View.VISIBLE);visibilityOverrides.put(nativeIcon,View.GONE);
                image.setVisibility(View.VISIBLE);nativeIcon.setVisibility(View.GONE);
            }finally{moduleWriting=false;}
        }
        void enforceImageSize() throws Throwable {
            moduleWriting=true;
            try {image.setLayoutParams(new LinearLayout.LayoutParams(ownedWidth,ownedHeight));}
            finally{moduleWriting=false;}
        }
        void restoreBattery() throws Throwable {
            restores++;moduleWriting=true;
            try {
                if(active&&image!=null){image.setImageDrawable(nativeDrawable);image.setScaleType(nativeScaleType);
                    image.setLayoutParams(new LinearLayout.LayoutParams(nativeParams));
                    for(var e:nativeVisibility.entrySet())e.getKey().setVisibility(e.getValue());}
                active=false;
            }finally{moduleWriting=false;}
        }
        void fail(Throwable error) throws Throwable {failed=true;restoreBattery();}
    }
    @METHODS@
    static void visible(Host h){
        check(h.active&&h.image.drawable==h.glyph,"owned drawable must stay installed throughout binder updates");
        check(h.nativeIcon.visibility==View.GONE,"native icons must never become visible between writes");
        check(h.image.params.width==h.ownedWidth&&h.image.params.height==h.ownedHeight,"native dimensions must not reach the displayed view");
    }
    public static void main(String[] ignored) throws Throwable {
        Host h=new Host();int captures=h.captures,restores=h.restores;
        Drawable next=new Drawable();
        nativeUpdate(h,new XposedInterface.Chain(h.meter,args->{
            visible(h);
            check(h.image.getDrawable()==h.nativeDrawable,"vendor code sees the native drawable for safe type casts");
            ViewGroup.LayoutParams editable=h.image.getLayoutParams();editable.width=31;editable.height=17;
            visible(h);
            h.image.setLayoutParams(editable);visible(h);
            h.image.setImageDrawable(next);visible(h);
            check(h.image.getDrawable()==next,"native drawable shadow follows the newest battery state");
            h.nativeIcon.setVisibility(View.VISIBLE);visible(h);
            h.image.setScaleType(ImageView.ScaleType.CENTER);visible(h);
            nativeUpdate(h,new XposedInterface.Chain(h.meter,nested->{visible(h);return null;}));
            check(h.nativeDepth==1,"nested binder preserves outer transaction");
            return null;
        }));
        check(h.captures==captures&&h.restores==restores,"ordinary battery updates never restore or recapture native UI");
        check(h.nativeDepth==0&&h.image.getDrawable()==h.glyph,"outside vendor binding reads remain normal");
        h.enabled=false;h.apply();
        check(h.image.drawable==next&&h.image.params.width==31&&h.image.params.height==17,"disable restores the latest native drawable and dimensions");
        check(h.nativeIcon.visibility==View.VISIBLE,"disable restores the latest native visibility");
        h.enabled=true;h.apply();
        try {nativeUpdate(h,new XposedInterface.Chain(h.meter,a->{visible(h);throw new IllegalStateException("vendor");}));
            throw new AssertionError("vendor exception was swallowed");}
        catch(IllegalStateException expected){check(h.nativeDepth==0,"failed vendor call unwinds depth");}
        ImageView previous=h.image,replacement=new ImageView();Drawable replacementNative=replacement.drawable;
        nativeUpdate(h,new XposedInterface.Chain(h.meter,a->{h.meter.child=replacement;return null;}));
        check(h.image==replacement&&replacement.drawable==h.glyph,"real view replacement captures and reclaims the new child");
        check(previous.drawable==next,"replaced child releases the owned drawable");
        h.enabled=false;h.apply();check(replacement.drawable==replacementNative,"new child retains its own restoration snapshot");
        ImageView other=new ImageView();Drawable otherNative=new Drawable();other.setImageDrawable(otherNative);
        check(other.drawable==otherNative,"unowned image views are untouched");
        System.out.println("Stable native binder transactions passed: "+checks+" checks");
    }
}
'''.replace('@METHODS@', '\n'.join(methods))
with tempfile.TemporaryDirectory(prefix='colorduo-binding-') as tmp:
    source = Path(tmp) / 'BindingTest.java'
    source.write_text(harness, encoding='utf-8')
    subprocess.run(['java', str(source)], check=True)
