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
    public String getResourceEntryName(int id){return id==1?"system_icons":id==2?"quick_qs_status_icons":id==3?"qs_status_bar_container_layout":id==4?"qs_fake_status_layout":id==5?"header_container":id==6?"simple_qs_fake_status_layout":id==7?"status_bar_end_side_container_for_fake":"fixture";}
}""",
    "android/view/View.java": """package android.view;
public class View implements ViewParent {
    public static final int NO_ID=-1;
    public ViewParent parent;
    public int width,height,y,top,id=NO_ID,paddingTop,paddingBottom,minimumHeight;
    public int measuredWidth,measuredHeight,paddingLeft,paddingRight,visibility,layer;
    public float alpha=1,transitionAlpha=1,scaleX=1,scaleY=1;
    public boolean attached=true;
    public ViewGroup.LayoutParams params=new ViewGroup.MarginLayoutParams(-2,-2);
    public android.graphics.Rect clip;
    public final android.content.res.Resources resources=new android.content.res.Resources();
    public ViewParent getParent(){return parent;}
    public View getRootView(){View v=this;while(v.parent instanceof View)v=(View)v.parent;return v;}
    public ViewGroup.LayoutParams getLayoutParams(){return params;}
    public static java.util.function.BiFunction<View,ViewGroup.LayoutParams,ViewGroup.LayoutParams> layoutFilter=(view,value)->value;
    public static java.util.function.BiFunction<View,Integer,Integer> minimumFilter=(view,value)->value;
    public static java.util.function.BiFunction<View,android.graphics.Rect,android.graphics.Rect> clipFilter=(view,value)->value;
    public void setLayoutParams(ViewGroup.LayoutParams value){params=layoutFilter.apply(this,value);}
    public int getMinimumHeight(){return minimumHeight;}
    public void setMinimumHeight(int value){minimumHeight=minimumFilter.apply(this,value);}
    public android.graphics.Rect getClipBounds(){return clip==null?null:new android.graphics.Rect(clip.left,clip.top,clip.right,clip.bottom);}
    public void setClipBounds(android.graphics.Rect value){value=clipFilter.apply(this,value);clip=value==null?null:new android.graphics.Rect(value.left,value.top,value.right,value.bottom);}
    public int getWidth(){return width;} public int getHeight(){return height;}
    public int getPaddingTop(){return paddingTop;} public int getPaddingBottom(){return paddingBottom;}
    public boolean isAttachedToWindow(){return attached;}
    public int getMeasuredWidth(){return measuredWidth;} public int getMeasuredHeight(){return measuredHeight;}
    public int getPaddingLeft(){return paddingLeft;} public int getPaddingRight(){return paddingRight;}
    public boolean getClipToOutline(){return false;} public int getLayerType(){return layer;}
    public int getVisibility(){return visibility;} public boolean isShown(){return visibility==0;}
    public float getAlpha(){return alpha;} public float getTransitionAlpha(){return transitionAlpha;}
    public float getScaleX(){return scaleX;} public float getScaleY(){return scaleY;}
    public float getTranslationX(){return 0;} public boolean isLayoutRequested(){return false;}
    public int getTop(){return top;} public float getTranslationY(){return 0;} public int getScrollY(){return 0;}
    public static class MeasureSpec {
        public static final int UNSPECIFIED=0, EXACTLY=0x40000000, AT_MOST=0x80000000;
        public static int getMode(int s){return s&0xc0000000;}
        public static int getSize(int s){return s&0x3fffffff;}
        public static int makeMeasureSpec(int size,int mode){return size|mode;}
    }
    public int getId(){return id;}
    public android.content.res.Resources getResources(){return resources;}
    public void getLocationInWindow(int[] out){out[0]=0;out[1]=y;}
    public void setSize(int width,int height){this.width=width;this.height=height;}
}""",
    "android/view/ViewGroup.java": """package android.view;
public class ViewGroup extends View {
    public static class LayoutParams {
        public static final int WRAP_CONTENT=-2;
        public int width,height;
        public LayoutParams(int width,int height){this.width=width;this.height=height;}
    }
    public static class MarginLayoutParams extends LayoutParams {
        public int leftMargin,topMargin,rightMargin,bottomMargin;
        public MarginLayoutParams(int width,int height){super(width,height);}
    }
    public boolean clipChildren=true,clipPadding=true;
    public boolean getClipChildren(){return clipChildren;}
    public boolean getClipToPadding(){return clipPadding;}
    public void setClipChildren(boolean value){clipChildren=value;}
    public void setClipToPadding(boolean value){clipPadding=value;}
}""",
    "androidx/constraintlayout/widget/ConstraintLayout.java": """package androidx.constraintlayout.widget;
public class ConstraintLayout extends android.view.ViewGroup {
    public int widgetMinimumHeight=20;
    public int getMinHeight(){return widgetMinimumHeight;}
    public static java.util.function.BiFunction<android.view.View,Integer,Integer> widgetMinimumFilter=(view,value)->value;
    public void setMinHeight(int value){widgetMinimumHeight=widgetMinimumFilter.apply(this,value);}
    // The header manually calls measure(AT_MOST), so LayoutParams.height and
    // View.minimumHeight cannot replace ConstraintLayout's own solver minimum.
    public int measureHeaderRow(int cap){return Math.min(cap,Math.max(20,widgetMinimumHeight));}
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
    static class OplusQSSimpleHeader extends ViewGroup { }
    static ViewGroup child(ViewGroup parent){ViewGroup v=new ViewGroup();v.parent=parent;return v;}
    static View icon(ViewGroup parent,float density){
        View v=new View();v.parent=parent;v.height=Math.round(16*density);v.y=Math.round(16*density);
        return v;
    }
    public static void main(String[] args){
        View.layoutFilter=ColorOsSizing::retainLayoutParams;
        View.minimumFilter=ColorOsSizing::retainMinimumHeight;
        androidx.constraintlayout.widget.ConstraintLayout.widgetMinimumFilter=ColorOsSizing::retainWidgetMinimumHeight;
        View.clipFilter=ColorOsSizing::retainClipBounds;
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
        // The reference ROM has a 26dp header, 10dp top padding, a wrap-content
        // ConstraintLayout row and a 0dp constrained battery meter.
        ViewGroup shade=new ViewGroup();shade.height=800;shade.width=420;
        QuickStatusBarHeader fixed=new QuickStatusBarHeader();fixed.parent=shade;
        fixed.height=26;fixed.width=420;fixed.paddingTop=10;fixed.params.height=26;
        fixed.setClipBounds(new android.graphics.Rect(0,0,420,26));
        androidx.constraintlayout.widget.ConstraintLayout fixedRow=new androidx.constraintlayout.widget.ConstraintLayout();
        fixedRow.parent=fixed;fixedRow.id=2;fixedRow.height=20;fixedRow.minimumHeight=20;
        ViewGroup battery=child(fixedRow);battery.height=20;battery.params.height=0;
        View batteryImage=icon(battery,1);((ViewGroup.MarginLayoutParams)batteryImage.params).bottomMargin=1;
        ColorOsSizing small=new ColorOsSizing();
        s.glyphScale=150;
        TrioSizing.Metrics enlarged=small.measure(battery,batteryImage,TrioAppearance.of(s));
        check(!enlarged.limited && enlarged.height==30,"the old 26dp QS header does not cap a 150% glyph");
        check(fixedRow.minimumHeight>=31,"constrained rows reserve the glyph plus its native margin");
        check(fixedRow.params.height>=31,"ConstraintLayout wrap-content rows get explicit height rather than an ineffective View minimum");
        check(fixedRow.measureHeaderRow(fixedRow.params.height)>=31,
              "a manual AT_MOST header measurement must honor the glyph's height in the constraint solver");
        check(battery.params.height==0 && battery.minimumHeight>=31,"battery matching constraints are preserved");
        check(fixed.params.height>=41,"QS header grows with padding and actual glyph occupation");
        check(fixed.getClipBounds().bottom>=41,"full-height cached header clips follow the new height");
        check(fixed.clipChildren,"the outer header keeps a real clipping boundary");
        fixed.height=fixed.params.height;
        ViewGroup.LayoutParams reset=new ViewGroup.LayoutParams(-1,26);
        fixed.setLayoutParams(reset);
        check(fixed.params.height>=41,"native layout writes cannot reintroduce the fixed header crop");
        fixedRow.setMinHeight(20);
        check(fixedRow.getMinHeight()>=31,"native solver-minimum writes cannot collapse an active glyph");
        fixedRow.setMinimumHeight(20);
        check(fixedRow.minimumHeight>=31,"native minimum-height writes retain the active glyph allocation");
        fixed.setClipBounds(new android.graphics.Rect(0,0,420,26));
        check(fixed.getClipBounds().bottom>=41,"native full-height clip writes follow the retained allocation immediately");
        fixed.setLayoutParams(fixed.getLayoutParams());
        fixedRow.setLayoutParams(fixedRow.getLayoutParams());
        fixedRow.setMinimumHeight(fixedRow.getMinimumHeight());
        fixedRow.setMinHeight(fixedRow.getMinHeight());
        fixed.setClipBounds(fixed.getClipBounds());
        ViewGroup otherBattery=child(fixedRow);otherBattery.height=20;otherBattery.params.height=0;
        View otherImage=icon(otherBattery,1);
        ColorOsSizing large=new ColorOsSizing();s.glyphScale=300;
        large.measure(otherBattery,otherImage,TrioAppearance.of(s));
        check(fixed.params.height>=70,"shared headers reserve the largest active glyph");
        check(fixedRow.getMinHeight()>=60,"shared constraint solvers reserve the largest owner's glyph");
        s.glyphScale=150;large.measure(otherBattery,otherImage,TrioAppearance.of(s));
        check(fixedRow.getMinHeight()>=31 && fixedRow.getMinHeight()<60,"shrinking a glyph does not treat its owned minimum as native");
        s.glyphScale=300;large.measure(otherBattery,otherImage,TrioAppearance.of(s));
        large.release();
        check(fixedRow.getMinHeight()==31,"releasing the largest owner restores the remaining solver allocation");
        check(fixed.params.height>=41 && fixed.params.height<70,"releasing the larger owner shrinks to the remaining request");
        fixed.params.height=28;fixedRow.minimumHeight=22;
        fixedRow.setMinHeight(23);
        s.glyphScale=150;small.measure(battery,batteryImage,TrioAppearance.of(s));
        fixed.setClipBounds(new android.graphics.Rect(0,0,420,28));
        check(fixed.getClipBounds().bottom>=41,"configuration changes keep the new full-height native clip expanded");
        small.release();
        check(fixed.params.height==28 && fixedRow.minimumHeight==22,"disable restores the latest native header height and row minimum");
        check(fixedRow.params.height==-2 && fixedRow.widgetMinimumHeight==23,"handoff restores the latest native wrap-content and solver minimum independently");
        fixedRow.setMinHeight(20);
        check(battery.params.height==0 && battery.minimumHeight==0,"native constrained meter height is restored");
        check(fixed.getClipBounds().bottom==28,"the latest cached native clip is restored after the last owner exits");
        fixed.setClipBounds(new android.graphics.Rect(0,5,420,18));
        small.measure(battery,batteryImage,TrioAppearance.of(s));
        check(fixed.getClipBounds().top==5 && fixed.getClipBounds().bottom==18,"partial animation clips remain under system control");
        small.release();
        ViewGroup separate=child(shade);separate.id=3;separate.height=20;separate.width=420;separate.params.height=20;
        fixed.parent=separate;fixed.params.height=26;fixed.height=26;fixed.setClipBounds(null);
        small.measure(battery,batteryImage,TrioAppearance.of(s));
        check(separate.params.height>=41 && fixed.params.height>=41,"separate control-centre containers reserve the same full header height");
        small.release();
        check(separate.params.height==20 && fixed.params.height==26,"separate control-centre heights restore exactly");
        ViewGroup fake=child(shade);fake.id=4;fake.height=26;fake.width=420;fake.params.height=26;
        fixed.parent=fake;
        small.measure(battery,batteryImage,TrioAppearance.of(s));
        check(fake.params.height>=41,"notification animation status wrappers reserve their enlarged contents");
        PhoneStatusBarView mainSurface=new PhoneStatusBarView();mainSurface.height=48;mainSurface.width=420;
        ViewGroup mainRow=child(mainSurface);battery.parent=mainRow;batteryImage.y=16;
        small.measure(battery,batteryImage,TrioAppearance.of(s));
        check(fake.params.height==26 && fixed.params.height==26,"reparenting back to main releases old QS height ownership");
        small.release();
        // Notification centre: public header inside its own fixed 40dp row.
        OplusQSSimpleHeader notification=new OplusQSSimpleHeader();notification.parent=shade;
        notification.height=800;notification.width=420;notification.params.height=-1;
        ViewGroup notificationColumn=child(notification);notificationColumn.height=100;
        ViewGroup notificationRow=child(notificationColumn);notificationRow.id=5;
        notificationRow.height=40;notificationRow.width=420;notificationRow.params.height=40;
        QuickStatusBarHeader notificationHeader=new QuickStatusBarHeader();notificationHeader.parent=notificationRow;
        notificationHeader.height=26;notificationHeader.params.height=26;notificationHeader.paddingTop=10;
        ViewGroup notificationIcons=child(notificationHeader);notificationIcons.id=2;notificationIcons.height=20;
        ViewGroup notificationBattery=child(notificationIcons);View notificationImage=icon(notificationBattery,1);
        s.glyphScale=300;
        ColorOsSizing notificationOwner=new ColorOsSizing();
        TrioSizing.Metrics notificationSize=notificationOwner.measure(notificationBattery,notificationImage,TrioAppearance.of(s));
        check(!notificationSize.limited && notificationRow.params.height>=70,
              "notification centre reserves its outer 40dp row as well as the inner 26dp header");
        check(notification.params.height==-1 && notificationColumn.params.height==-2,
              "notification centre does not grow the full-screen surface or unrelated button column");
        notificationRow.setLayoutParams(new ViewGroup.LayoutParams(-1,40));
        check(notificationRow.params.height>=70,"native notification resource updates retain the enlarged row immediately");
        notificationOwner.release();
        check(notificationRow.params.height==40 && notificationHeader.params.height==26,
              "notification centre restores both independent native height limits");
        ViewGroup notificationFake=child(notification);notificationFake.id=6;
        notificationFake.height=40;notificationFake.width=420;notificationFake.params.height=40;
        notificationHeader.parent=notificationFake;
        notificationOwner.measure(notificationBattery,notificationImage,TrioAppearance.of(s));
        check(notificationFake.params.height>=70,"notification centre transition wrapper reserves enlarged glyphs");
        notificationOwner.release();
        check(notificationFake.params.height==40,"notification transition wrapper restores its original size");
        // Generic header_container IDs outside OplusQSSimpleHeader are not expandable.
        notificationRow.parent=shade;notificationHeader.parent=notificationRow;
        notificationOwner.measure(notificationBattery,notificationImage,TrioAppearance.of(s));
        check(notificationRow.params.height==40,"unrelated generic header containers keep their original bounds");
        notificationOwner.release();
        // A seamless frame replays the main row with no battery descendant of its own.
        PhoneStatusBarView replaySourceBar=new PhoneStatusBarView();
        replaySourceBar.parent=shade;replaySourceBar.height=48;replaySourceBar.width=420;
        ViewGroup replaySource=child(replaySourceBar);replaySource.height=20;replaySource.id=7;
        ViewGroup replayContent=child(replaySource);replayContent.height=20;
        ViewGroup replayIcons=child(replayContent);replayIcons.id=1;replayIcons.height=20;
        ViewGroup replayBattery=child(replayIcons);replayBattery.height=20;
        View replayImage=icon(replayBattery,1);replayImage.height=16;replayImage.top=2;
        ViewGroup replayRoot=child(shade);replayRoot.id=3;replayRoot.height=34;
        replayRoot.width=420;replayRoot.params.height=34;
        ViewGroup replayHolder=child(replayRoot);replayHolder.params.height=-2;
        ViewGroup replayFrame=child(replayHolder);replayFrame.height=20;replayFrame.params.height=20;
        ColorOsSizing replayOwner=new ColorOsSizing();s.glyphScale=150;
        check(ColorOsSizing.trackMirror(replayFrame,replaySource),"a seamless binding installs its listener once");
        check(!ColorOsSizing.trackMirror(replayFrame,replaySource),"native reset does not duplicate the mirror listener");
        replayOwner.measure(replayBattery,replayImage,TrioAppearance.of(s));
        check(replayFrame.params.height==30 && replayHolder.minimumHeight>=30,
              "the first animation frame reserves glyph ink without reparenting the real battery");
        check(ColorOsSizing.mirrorOffset(replayFrame)==5,"negative source ink is moved inside the expanded replay frame");
        check(replaySourceBar.params.height==-2 && replaySource.minimumHeight==0,
              "mirror allocation does not resize the real fixed status bar");
        int cached=View.MeasureSpec.makeMeasureSpec(20,View.MeasureSpec.EXACTLY);
        int retained=ColorOsSizing.retainMeasureSpec(replayFrame,cached);
        check(View.MeasureSpec.getSize(retained)==30 && View.MeasureSpec.getMode(retained)==View.MeasureSpec.EXACTLY,
              "a cached first-frame exact measure cannot shrink the replay allocation");
        int enough=View.MeasureSpec.makeMeasureSpec(40,View.MeasureSpec.AT_MOST);
        check(ColorOsSizing.retainMeasureSpec(replayFrame,enough)==enough,"sufficient parent measure constraints are unchanged");
        check(ColorOsSizing.retainMeasureSpec(replaySourceBar,cached)==cached,"unowned status-window measurement is unchanged");
        replayFrame.setLayoutParams(new ViewGroup.LayoutParams(-1,20));
        check(replayFrame.params.height==30,"native replay resets keep the owned height before drawing");
        java.awt.image.BufferedImage replayPixels=new java.awt.image.BufferedImage(60,30,java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D graphics=replayPixels.createGraphics();
        graphics.translate(0,ColorOsSizing.mirrorOffset(replayFrame));
        graphics.setColor(java.awt.Color.WHITE);graphics.fillRect(10,-5,40,30);graphics.dispose();
        int paintedRows=0;
        for(int rowIndex=0;rowIndex<30;rowIndex++) if((replayPixels.getRGB(20,rowIndex)>>>24)!=0) paintedRows++;
        check(paintedRows==30,"top and bottom ink survive replay inside the frame's own pixel bounds");
        ColorOsSizing.mirrorAttached(replayFrame,false);
        replayOwner.measure(replayBattery,replayImage,TrioAppearance.of(s));
        check(replayFrame.params.height==20 && ColorOsSizing.mirrorOffset(replayFrame)==0,
              "detaching a replay restores its height and offset immediately");
        ColorOsSizing.mirrorAttached(replayFrame,true);
        replayOwner.measure(replayBattery,replayImage,TrioAppearance.of(s));
        check(replayFrame.params.height==30,"reattachment reserves the first frame synchronously");
        ColorOsSizing.trackMirror(replayFrame,new View());
        replayOwner.measure(replayBattery,replayImage,TrioAppearance.of(s));
        check(replayFrame.params.height==20 && ColorOsSizing.mirrorOffset(replayFrame)==0,
              "rebinding to an unrelated native source releases all old ownership");
        ColorOsSizing.trackMirror(replayFrame,replaySource);
        replayOwner.measure(replayBattery,replayImage,TrioAppearance.of(s));
        replayOwner.release();
        check(replayFrame.params.height==20 && replayHolder.minimumHeight==0 && ColorOsSizing.mirrorOffset(replayFrame)==0,
              "disabling restores the real and replay trees together");
        check(ColorOsSizing.retainMeasureSpec(replayFrame,cached)==cached,"released replay measurement is fully native");
        ColorOsSizing.clearMirrors();
        check(ColorOsSizing.trackedMirrors().isEmpty(),"retirement can remove every tracked replay listener");
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
        "TrioRenderer", "DuoPolicy", "ColorOsSizing", "ChargeSpeed")]
    subprocess.run(["javac", "-encoding", "UTF-8", "-d", directory, *map(str, sources)], check=True)
    subprocess.run(["java", "-Djava.awt.headless=true", "-cp", directory,
                    "io.github.yixing233.hyperduo.ColorOsSizingTest"], check=True)
