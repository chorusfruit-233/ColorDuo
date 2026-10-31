#!/usr/bin/env python3
"""Execute production surface measurement and shared clipping leases with view fixtures."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/src/main/java/io/github/yixing233/hyperduo"
fixtures = {
    "android/view/ViewParent.java": "package android.view; public interface ViewParent { ViewParent getParent(); }",
    "android/util/DisplayMetrics.java": "package android.util; public class DisplayMetrics { public float density=1; }",
    "android/content/res/Resources.java": """package android.content.res;
public class Resources {
    public final android.util.DisplayMetrics metrics=new android.util.DisplayMetrics();
    public android.util.DisplayMetrics getDisplayMetrics(){return metrics;}
    public String getResourceEntryName(int id){return id==1?"system_icons":id==2?"quick_qs_status_icons":"fixture";}
}""",
    "android/view/View.java": """package android.view;
public class View implements ViewParent {
    public static final int NO_ID=-1;
    public ViewParent parent;
    public int width,height,y,id=NO_ID,paddingTop,paddingBottom;
    public final android.content.res.Resources resources=new android.content.res.Resources();
    public ViewParent getParent(){return parent;}
    public int getWidth(){return width;} public int getHeight(){return height;}
    public int getPaddingTop(){return paddingTop;} public int getPaddingBottom(){return paddingBottom;}
    public int getId(){return id;}
    public android.content.res.Resources getResources(){return resources;}
    public void getLocationInWindow(int[] out){out[0]=0;out[1]=y;}
    public void setSize(int width,int height){this.width=width;this.height=height;}
}""",
    "android/view/ViewGroup.java": """package android.view;
public class ViewGroup extends View {
    public boolean clipChildren=true,clipPadding=true;
    public boolean getClipChildren(){return clipChildren;}
    public boolean getClipToPadding(){return clipPadding;}
    public void setClipChildren(boolean value){clipChildren=value;}
    public void setClipToPadding(boolean value){clipPadding=value;}
}""",
    "io/github/yixing233/hyperduo/ColorOsSizingTest.java": """package io.github.yixing233.hyperduo;
import android.view.View;
import android.view.ViewGroup;
public final class ColorOsSizingTest {
    static int checks;
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static class PhoneStatusBarView extends ViewGroup { }
    static class KeyguardStatusBarView extends ViewGroup { }
    static class QuickStatusBarHeader extends ViewGroup { }
    static ViewGroup child(ViewGroup parent){ViewGroup v=new ViewGroup();v.parent=parent;return v;}
    static View icon(ViewGroup parent,float density){
        View v=new View();v.parent=parent;v.height=Math.round(16*density);v.y=Math.round(16*density);
        return v;
    }
    public static void main(String[] args){
        for(float density:new float[]{1.5f,2f,3f,4f})
        for(ViewGroup surface:new ViewGroup[]{new PhoneStatusBarView(),new KeyguardStatusBarView(),new QuickStatusBarHeader()}) {
            surface.height=Math.round(48*density);surface.width=Math.round(420*density);
            ViewGroup row=child(surface),one=child(row),two=child(row);
            one.resources.metrics.density=two.resources.metrics.density=density;
            View imageOne=icon(one,density),imageTwo=icon(two,density);
            one.clipPadding=false;
            TrioSettings config=TrioSettings.defaults();config.glyphScale=150;
            TrioAppearance a=TrioAppearance.of(config);
            ColorOsSizing first=new ColorOsSizing(),second=new ColorOsSizing();
            TrioSizing.Metrics m=first.measure(one,imageOne,a);
            check(m.height>=Math.round(30*density),"150% really requests a larger host on every surface");
            check(!m.limited,"normal enlargement fits the larger status surface");
            check(!one.clipChildren && !row.clipChildren,"intermediate native rows allow the measured drawing");
            check(surface.clipChildren,"the containing surface keeps its own clipping boundary");
            second.measure(two,imageTwo,a);
            first.release();
            check(one.clipChildren && !one.clipPadding,"release restores each original clipping flag");
            check(!row.clipChildren,"one host cannot restore a shared row still used by another");
            second.release();
            check(row.clipChildren && row.clipPadding,"the last host restores shared clipping");
            second.release();
            config.glyphScale=300;
            surface.height=Math.round(24*density);
            imageOne.y=Math.round(4*density);
            m=first.measure(one,imageOne,TrioAppearance.of(config));
            check(m.limited && m.height<=surface.height,"small surfaces constrain 300% without cropping the glyph");
            first.release();
            surface.height=0;
            m=first.measure(one,imageOne,TrioAppearance.of(config));
            check(m.limited && m.height<=Math.round(20*density),"unmeasured hosts use a safe initial bound");
            first.release();
        }
        ViewGroup window=new ViewGroup();window.height=2000;window.width=1000;
        ViewGroup header=child(window);header.height=30;header.width=400;
        ViewGroup row=child(header);row.id=2;
        ViewGroup meter=child(row);View image=icon(meter,1);image.y=7;
        TrioSettings s=TrioSettings.defaults();s.glyphScale=300;
        ColorOsSizing owner=new ColorOsSizing();
        TrioSizing.Metrics limited=owner.measure(meter,image,TrioAppearance.of(s));
        check(limited.height<=30 && limited.limited,"unknown QS headers never borrow the entire shade window");
        check(header.clipChildren && window.clipChildren,"fallback boundaries remain clipped");
        owner.release();
        System.out.println("Production size limits and clipping ownership: "+checks+" checks passed");
    }
}""",
}

with tempfile.TemporaryDirectory(prefix="colorduo-sizing-") as directory:
    temporary = Path(directory)
    sources = []
    for name, content in fixtures.items():
        path = temporary / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
        sources.append(path)
    sources += [p for p in (ROOT / "tests/shims").rglob("*.java")
                if p.relative_to(ROOT / "tests/shims").as_posix() not in fixtures]
    sources += [SOURCE / (name + ".java") for name in (
        "Prefs", "TrioSettings", "TrioAppearance", "TrioGeometry", "TrioZones", "TrioSizing",
        "TrioRenderer", "DuoPolicy", "ColorOsSizing")]
    subprocess.run(["javac", "-encoding", "UTF-8", "-d", directory, *map(str, sources)], check=True)
    subprocess.run(["java", "-Djava.awt.headless=true", "-cp", directory,
                    "io.github.yixing233.hyperduo.ColorOsSizingTest"], check=True)
