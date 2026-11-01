/* SPDX-License-Identifier: GPL-3.0-only */
package io.github.yixing233.hyperduo;

import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/** Measures glyphs and reserves expandable QS header space with reversible shared ownership. */
final class ColorOsSizing {
    private static final Map<ViewGroup, ClipState> CLIPS = new WeakHashMap<>();
    private static final Map<ViewGroup, HeightState> HEIGHTS = new WeakHashMap<>();
    // A seamless frame redraws a source view; it does not contain that source's meter.
    private static final Map<ViewGroup, Mirror> MIRRORS = new WeakHashMap<>();
    private final Set<ViewGroup> held = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<ViewGroup> heights = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<ViewGroup> mirrors = Collections.newSetFromMap(new IdentityHashMap<>());

    private static final class Ink {
        final int top,bottom;
        Ink(int top,int bottom) { this.top=top;this.bottom=bottom; }
    }

    private static final class Mirror {
        WeakReference<View> source=new WeakReference<>(null);
        boolean attached;
        final Map<ColorOsSizing,Ink> owners=new WeakHashMap<>();
        int top() { int top=0;for(Ink ink:owners.values()) top=Math.min(top,ink.top);return top; }
        int height(View source) {
            int bottom=source.getHeight();
            for(Ink ink:owners.values()) bottom=Math.max(bottom,ink.bottom);
            return bottom-top();
        }
    }

    /** Return true only once, so the caller can install one reversible attach listener. */
    static boolean trackMirror(ViewGroup frame,View source) {
        Mirror mirror=MIRRORS.get(frame);
        boolean fresh=mirror==null;
        if(fresh) { mirror=new Mirror();MIRRORS.put(frame,mirror); }
        if(mirror.source.get()!=source) mirror.owners.clear();
        mirror.source=new WeakReference<>(source);
        mirror.attached=frame.isAttachedToWindow();
        return fresh;
    }

    static void mirrorAttached(ViewGroup frame,boolean attached) {
        Mirror mirror=MIRRORS.get(frame);
        if(mirror!=null) mirror.attached=attached;
    }

    static float mirrorOffset(ViewGroup frame) {
        Mirror mirror=MIRRORS.get(frame);
        return mirror==null || !mirror.attached?0:-mirror.top();
    }

    java.util.List<ViewGroup> mirrorFrames() { return new ArrayList<>(mirrors); }
    static java.util.List<ViewGroup> trackedMirrors() { return new ArrayList<>(MIRRORS.keySet()); }
    static void clearMirrors() { MIRRORS.clear(); }

    static String describe(View view) {
        HeightState height=HEIGHTS.get(view);
        ClipState clip=CLIPS.get(view);
        return (height==null?"allocation=native":"nativeLpH="+height.nativeHeight
                +" nativeMinH="+height.nativeMinimum+" allocatedH="+height.writtenHeight
                +" allocatedMinH="+height.writtenMinimum
                +" nativeWidgetMinH="+height.nativeWidgetMinimum
                +" allocatedWidgetMinH="+height.writtenWidgetMinimum)
                +(clip==null?"":" nativeClip="+clip.nativeClip+" nativeClipH="+clip.nativeClipHeight
                +" overflowLease="+clip.owners.containsValue(Boolean.TRUE));
    }

    private static final class Surface {
        final ViewGroup view;
        final boolean expandable;
        Surface(ViewGroup view,boolean expandable) { this.view=view;this.expandable=expandable; }
    }

    private static final class HeightState {
        int nativeHeight,nativeMinimum,nativeMeasured,writtenHeight,writtenMinimum;
        int nativeWidgetMinimum,writtenWidgetMinimum;
        boolean writing;
        final java.lang.reflect.Method widgetMinimum,widgetMinimumSetter;
        final boolean exactWrap;
        final Map<ColorOsSizing,Integer> owners=new WeakHashMap<>();
        HeightState(ViewGroup view) {
            nativeHeight=writtenHeight=view.getLayoutParams().height;
            nativeMinimum=writtenMinimum=view.getMinimumHeight();
            nativeMeasured=nativeHeight>0?nativeHeight:view.getHeight();
            java.lang.reflect.Method getter=null;
            try { getter=view.getClass().getMethod("getMinHeight");getter.setAccessible(true); }
            catch(ReflectiveOperationException | RuntimeException ignored) { }
            widgetMinimum=getter;
            java.lang.reflect.Method setter=null;
            if(getter!=null) try {
                setter=view.getClass().getMethod("setMinHeight",int.class);setter.setAccessible(true);
            } catch(ReflectiveOperationException | RuntimeException ignored) { }
            widgetMinimumSetter=setter;
            nativeWidgetMinimum=writtenWidgetMinimum=readWidgetMinimum(view);
            boolean constrained=getter!=null;
            for(Class<?> type=view.getClass();type!=null && !constrained;type=type.getSuperclass())
                constrained="androidx.constraintlayout.widget.ConstraintLayout".equals(type.getName());
            exactWrap=constrained;
        }
        int readWidgetMinimum(View view) {
            if(widgetMinimum==null) return 0;
            try { return ((Number)widgetMinimum.invoke(view)).intValue(); }
            catch(ReflectiveOperationException | RuntimeException ignored) { return 0; }
        }
        int heightFor(int minimum) {
            if(owners.isEmpty()) return nativeHeight;
            if(nativeHeight>0) return Math.max(nativeHeight,minimum);
            // ConstraintLayout's own minHeight is distinct from View.minimumHeight.
            // Keep an explicit height for parents which do honor LayoutParams.
            if(nativeHeight==ViewGroup.LayoutParams.WRAP_CONTENT && exactWrap)
                return Math.max(minimum,Math.max(nativeMinimum,nativeWidgetMinimum));
            return nativeHeight;
        }
        void readNative(ViewGroup view) {
            if(view.getLayoutParams().height!=writtenHeight) {
                nativeHeight=view.getLayoutParams().height;
                if(nativeHeight>0) nativeMeasured=nativeHeight;
            }
            if(view.getMinimumHeight()!=writtenMinimum) nativeMinimum=view.getMinimumHeight();
            int widget=readWidgetMinimum(view);
            if(widget!=writtenWidgetMinimum) nativeWidgetMinimum=widget;
        }
        int floor() { return Math.max(nativeWidgetMinimum,Math.max(nativeMinimum,nativeHeight>0?nativeHeight:exactWrap?0:nativeMeasured)); }
        void apply(ViewGroup view) {
            int required=nativeMinimum;
            for(int value:owners.values()) required=Math.max(required,value);
            writtenMinimum=required;
            // Preserve MATCH_PARENT and ConstraintLayout's 0dp matching semantics.
            writtenHeight=heightFor(required);
            int widgetRequired=owners.isEmpty()?nativeWidgetMinimum:Math.max(nativeWidgetMinimum,required);
            writing=true;
            try {
                if(view.getMinimumHeight()!=writtenMinimum) view.setMinimumHeight(writtenMinimum);
                // OplusQuickStatusBarHeader measures this row directly with AT_MOST;
                // that bypasses LayoutParams.height. Its 0dp children depend on the
                // ConstraintLayout solver minimum, not View.minimumHeight.
                if(widgetMinimumSetter!=null && readWidgetMinimum(view)!=widgetRequired) {
                    try { widgetMinimumSetter.invoke(view,widgetRequired); }
                    catch(ReflectiveOperationException | RuntimeException ignored) { }
                }
                writtenWidgetMinimum=readWidgetMinimum(view);
                ViewGroup.LayoutParams params=view.getLayoutParams();
                if(params.height!=writtenHeight) { params.height=writtenHeight;view.setLayoutParams(params); }
            } finally { writing=false; }
        }
    }

    private static final class ClipState {
        final boolean children,padding;
        Rect nativeClip,writtenClip;
        int nativeClipHeight;
        boolean writing;
        final Map<ColorOsSizing,Boolean> owners=new WeakHashMap<>();
        ClipState(ViewGroup view) {
            children=view.getClipChildren();padding=view.getClipToPadding();
            nativeClip=copy(view.getClipBounds());writtenClip=copy(nativeClip);nativeClipHeight=view.getHeight();
        }
        void apply(ViewGroup view) {
            Rect latest=view.getClipBounds();
            if(!same(latest,writtenClip)) { nativeClip=copy(latest);nativeClipHeight=nativeClipHeight(view); }
            boolean relax=owners.containsValue(Boolean.TRUE);
            boolean clipChildren=relax?false:children,clipPadding=relax?false:padding;
            if(view.getClipChildren()!=clipChildren) view.setClipChildren(clipChildren);
            if(view.getClipToPadding()!=clipPadding) view.setClipToPadding(clipPadding);
            Rect target=copy(nativeClip);
            // A full-height cached clip must follow the resized header; partial animation clips stay native.
            if(target!=null && target.top<=0 && target.bottom>=nativeClipHeight) {
                int height=Math.max(view.getHeight(),view.getMinimumHeight());
                if(view.getLayoutParams()!=null) height=Math.max(height,view.getLayoutParams().height);
                target.bottom=Math.max(target.bottom,height);
            }
            writtenClip=copy(target);
            if(!same(latest,target)) {
                writing=true;
                try { view.setClipBounds(target); } finally { writing=false; }
            }
        }
    }

    TrioSizing.Metrics measure(ViewGroup meter,View image,TrioAppearance appearance) {
        float density=meter.getResources().getDisplayMetrics().density;
        Surface found=surface(meter);
        ViewGroup surface=found.view;
        int maxHeight=Math.round(20f*density),maxWidth=Math.round(180f*density);
        if(surface!=null) {
            if(surface.getWidth()>0) maxWidth=Math.max(Math.round(24f*density),surface.getWidth()/2);
            if(found.expandable) {
                View root=surface.getRootView();
                int[] origin=new int[2],position=new int[2];
                root.getLocationInWindow(origin);surface.getLocationInWindow(position);
                int overhead=image.getPaddingTop()+image.getPaddingBottom()+margins(image);
                for(ViewParent p=image.getParent();p instanceof ViewGroup;p=p.getParent()) {
                    ViewGroup group=(ViewGroup)p;
                    overhead+=group.getPaddingTop()+group.getPaddingBottom()+margins(group);
                    if(group==surface) break;
                }
                if(root.getHeight()>0) maxHeight=Math.max(1,root.getHeight()-root.getPaddingBottom()
                        -Math.max(0,position[1]-origin[1])-overhead);
            } else if(surface.getHeight()>0) {
                int content=surface.getHeight()-surface.getPaddingTop()-surface.getPaddingBottom();
                ViewGroup.LayoutParams params=surface.getLayoutParams();
                if(params!=null && params.height>0) content=Math.min(content,
                        params.height-surface.getPaddingTop()-surface.getPaddingBottom());
                maxHeight=Math.max(1,content);
                if(image.getHeight()>0) {
                    int[] origin=new int[2],position=new int[2];
                    surface.getLocationInWindow(origin);image.getLocationInWindow(position);
                    int centre=position[1]-origin[1]+image.getHeight()/2;
                    maxHeight=Math.min(maxHeight,Math.max(1,2*Math.min(centre-surface.getPaddingTop(),
                            surface.getHeight()-surface.getPaddingBottom()-centre)));
                }
            }
        }
        TrioSizing.Metrics metrics=TrioSizing.measure(appearance,density,maxHeight,maxWidth);
        Set<ViewGroup> requiredHeights=Collections.newSetFromMap(new IdentityHashMap<>());
        Map<ViewGroup,Boolean> clips=new IdentityHashMap<>();
        if(found.expandable) reservePath((ViewGroup)image.getParent(),surface,
                metrics.height+image.getPaddingTop()+image.getPaddingBottom()+margins(image),requiredHeights);
        if(surface!=null && (found.expandable || metrics.height>Math.round(20f*density)))
            clipPath((ViewGroup)image.getParent(),found,clips);

        Set<ViewGroup> requiredMirrors=Collections.newSetFromMap(new IdentityHashMap<>());
        for(Map.Entry<ViewGroup,Mirror> entry:MIRRORS.entrySet()) {
            ViewGroup frame=entry.getKey();Mirror mirror=entry.getValue();View source=mirror.source.get();
            Ink ink=source==null || !mirror.attached?null:sourceInk(image,source,metrics.height);
            Surface target=surface(frame);
            if(ink==null || !target.expandable) continue;
            mirror.owners.put(this,ink);requiredMirrors.add(frame);mirrors.add(frame);
            reservePath(frame,target.view,mirror.height(source),requiredHeights);
            clipPath(frame,target,clips);
        }
        for(ViewGroup frame:mirrors.toArray(new ViewGroup[0]))
            if(!requiredMirrors.contains(frame)) releaseMirror(frame);
        for(ViewGroup group:heights.toArray(new ViewGroup[0]))
            if(!requiredHeights.contains(group)) releaseHeight(group);

        for(ViewGroup group:held.toArray(new ViewGroup[0])) if(!clips.containsKey(group)) releaseClip(group);
        for(Map.Entry<ViewGroup,Boolean> entry:clips.entrySet()) {
            ViewGroup group=entry.getKey();
            ClipState state=CLIPS.get(group);
            if(state==null) { state=new ClipState(group);CLIPS.put(group,state); }
            state.owners.put(this,entry.getValue());held.add(group);state.apply(group);
        }
        return metrics;
    }

    private static Ink sourceInk(View image,View source,int height) {
        float top=(image.getHeight()-height)/2f;
        View view=image;
        while(view!=source) {
            if(!(view.getParent() instanceof View)) return null;
            top+=view.getTop()+view.getTranslationY()-((View)view.getParent()).getScrollY();
            view=(View)view.getParent();
        }
        return new Ink(Math.min(0,(int)Math.floor(top)),Math.max(source.getHeight(),(int)Math.ceil(top+height)));
    }

    private void reservePath(ViewGroup first,ViewGroup surface,int required,Set<ViewGroup> groups) {
        for(ViewGroup group=first;group!=null;) {
            groups.add(group);
            required+=group.getPaddingTop()+group.getPaddingBottom();
            required=acquireHeight(group,required)+margins(group);
            if(group==surface) break;
            group=group.getParent() instanceof ViewGroup?(ViewGroup)group.getParent():null;
        }
    }

    private static void clipPath(ViewGroup first,Surface surface,Map<ViewGroup,Boolean> clips) {
        for(ViewGroup group=first;group!=null && group!=surface.view;) {
            clips.put(group,true);
            group=group.getParent() instanceof ViewGroup?(ViewGroup)group.getParent():null;
        }
        if(surface.expandable) clips.put(surface.view,false);
    }

    /** Native seamless frames can pass a cached small spec even after LayoutParams grew. */
    static int retainMeasureSpec(View view,int requested) {
        HeightState state=HEIGHTS.get(view);
        if(state==null || state.owners.isEmpty()) return requested;
        state.readNative((ViewGroup)view);state.apply((ViewGroup)view);
        int required=Math.max(state.writtenMinimum,state.writtenHeight);
        if(View.MeasureSpec.getMode(requested)==View.MeasureSpec.UNSPECIFIED
                || View.MeasureSpec.getSize(requested)>=required) return requested;
        return View.MeasureSpec.makeMeasureSpec(required,View.MeasureSpec.EXACTLY);
    }

    /** Retain the current allocation when native configuration/animation code rewrites it. */
    static ViewGroup.LayoutParams retainLayoutParams(View view,ViewGroup.LayoutParams requested) {
        HeightState state=HEIGHTS.get(view);
        if(state==null || state.writing || state.owners.isEmpty() || requested==null) return requested;
        // Margin/width-only writes often pass the already-owned height back unchanged.
        if(requested.height!=state.writtenHeight) {
            state.nativeHeight=requested.height;
            if(state.nativeHeight>0) state.nativeMeasured=state.nativeHeight;
        }
        int minimum=Math.max(state.nativeMinimum,state.nativeWidgetMinimum);
        for(int value:state.owners.values()) minimum=Math.max(minimum,value);
        state.writtenHeight=state.heightFor(minimum);
        requested.height=state.writtenHeight;
        return requested;
    }

    static int retainMinimumHeight(View view,int requested) {
        HeightState state=HEIGHTS.get(view);
        if(state==null || state.writing || state.owners.isEmpty()) return requested;
        if(requested!=state.writtenMinimum) state.nativeMinimum=requested;
        for(int value:state.owners.values()) requested=Math.max(requested,value);
        state.writtenMinimum=requested;
        return requested;
    }

    static int retainWidgetMinimumHeight(View view,int requested) {
        HeightState state=HEIGHTS.get(view);
        if(state==null || state.writing || state.owners.isEmpty()) return requested;
        if(requested!=state.writtenWidgetMinimum) state.nativeWidgetMinimum=requested;
        for(int value:state.owners.values()) requested=Math.max(requested,value);
        state.writtenWidgetMinimum=requested;
        return requested;
    }

    static Rect retainClipBounds(View view,Rect requested) {
        ClipState state=CLIPS.get(view);
        if(state==null || state.writing || state.owners.isEmpty()) return requested;
        if(same(requested,state.writtenClip)) return requested;
        Rect nativeRequest=copy(requested);
        if(nativeRequest!=null && state.nativeClip!=null && state.writtenClip!=null
                && nativeRequest.top==state.writtenClip.top && nativeRequest.bottom==state.writtenClip.bottom) {
            nativeRequest.top=state.nativeClip.top;nativeRequest.bottom=state.nativeClip.bottom;
        }
        state.nativeClip=nativeRequest;state.nativeClipHeight=nativeClipHeight((ViewGroup)view);
        Rect target=copy(nativeRequest);
        if(target!=null && target.top<=0 && target.bottom>=state.nativeClipHeight) {
            int height=Math.max(view.getHeight(),view.getMinimumHeight());
            if(view.getLayoutParams()!=null) height=Math.max(height,view.getLayoutParams().height);
            target.bottom=Math.max(target.bottom,height);
        }
        state.writtenClip=copy(target);
        return target;
    }

    private static int nativeClipHeight(ViewGroup view) {
        HeightState state=HEIGHTS.get(view);
        if(state==null) return view.getHeight();
        if(state.nativeHeight>0) return state.nativeHeight;
        int minimum=Math.max(state.nativeMinimum,state.nativeWidgetMinimum);
        return state.exactWrap && minimum>0?minimum:Math.max(state.nativeMeasured,minimum);
    }

    private int acquireHeight(ViewGroup view,int required) {
        if(view.getLayoutParams()==null) return required;
        HeightState state=HEIGHTS.get(view);
        if(state==null) { state=new HeightState(view);HEIGHTS.put(view,state); }
        state.readNative(view);
        int ownRequired=Math.max(required,state.floor());
        state.owners.put(this,ownRequired);heights.add(view);state.apply(view);
        return ownRequired;
    }

    private void releaseHeight(ViewGroup view) {
        heights.remove(view);
        HeightState state=HEIGHTS.get(view);
        if(state==null) return;
        state.readNative(view);state.owners.remove(this);state.apply(view);
        if(state.owners.isEmpty()) HEIGHTS.remove(view);
    }

    private static int margins(View view) {
        ViewGroup.LayoutParams params=view.getLayoutParams();
        if(!(params instanceof ViewGroup.MarginLayoutParams)) return 0;
        ViewGroup.MarginLayoutParams margins=(ViewGroup.MarginLayoutParams)params;
        return Math.max(0,margins.topMargin)+Math.max(0,margins.bottomMargin);
    }

    private static Surface surface(ViewGroup meter) {
        ViewGroup fixed=null,header=null,fallback=null,notificationRow=null;
        for(ViewParent p=meter.getParent();p instanceof ViewGroup;p=p.getParent()) {
            ViewGroup group=(ViewGroup)p;
            String type=group.getClass().getSimpleName(),name="";
            if(group.getId()!=View.NO_ID) try {
                name=group.getResources().getResourceEntryName(group.getId());
            } catch(RuntimeException ignored) { }
            if("qs_status_bar_container_layout".equals(name) || "qs_fake_status_layout".equals(name)
                    || "separateqs_fake_status_layout".equals(name)
                    || "simple_qs_fake_status_layout".equals(name)) return new Surface(group,true);
            if("header_container".equals(name)) notificationRow=group;
            if(notificationRow!=null && ("OplusQSSimpleHeader".equals(type) || "simple_qs_footer".equals(name)))
                return new Surface(notificationRow,true);
            if(type.contains("QuickStatusBarHeader") || "quick_status_bar_header".equals(name)) header=group;
            if(fixed==null && (type.contains("PhoneStatusBarView") || type.contains("KeyguardStatusBarView")
                    || "status_bar".equals(name) || "keyguard_header".equals(name)
                    || "keyguard_status_bar".equals(name))) fixed=group;
            if("system_icons".equals(name) || "quick_qs_status_icons".equals(name))
                fallback=group.getParent() instanceof ViewGroup?(ViewGroup)group.getParent():group;
        }
        if(header!=null) return new Surface(header,true);
        if(fixed!=null) return new Surface(fixed,false);
        return new Surface(fallback!=null?fallback:meter.getParent() instanceof ViewGroup
                ?(ViewGroup)meter.getParent():null,false);
    }

    private void releaseClip(ViewGroup group) {
        held.remove(group);
        ClipState state=CLIPS.get(group);
        if(state==null) return;
        state.owners.remove(this);
        if(state.owners.isEmpty()) {
            Rect current=group.getClipBounds();
            if(!same(current,state.writtenClip)) state.nativeClip=copy(current);
            CLIPS.remove(group);
            group.setClipChildren(state.children);group.setClipToPadding(state.padding);
            group.setClipBounds(state.nativeClip);
        } else state.apply(group);
    }

    private void releaseMirror(ViewGroup frame) {
        mirrors.remove(frame);
        Mirror mirror=MIRRORS.get(frame);
        if(mirror!=null) mirror.owners.remove(this);
    }

    private static Rect copy(Rect r) { return r==null?null:new Rect(r.left,r.top,r.right,r.bottom); }
    private static boolean same(Rect a,Rect b) {
        return a==b || (a!=null && b!=null && a.left==b.left && a.top==b.top && a.right==b.right && a.bottom==b.bottom);
    }

    void release() {
        for(ViewGroup frame:mirrors.toArray(new ViewGroup[0])) releaseMirror(frame);
        for(ViewGroup group:heights.toArray(new ViewGroup[0])) releaseHeight(group);
        for(ViewGroup group:held.toArray(new ViewGroup[0])) releaseClip(group);
    }
}
