/* SPDX-License-Identifier: GPL-3.0-only */
package io.github.yixing233.hyperduo;

import android.graphics.Canvas;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Opt-in on-device geometry evidence; never changes layout or rendering. */
final class ColorOsDiagnostics {
    private static final ThreadLocal<ViewGroup> MIRROR = new ThreadLocal<>();
    private final Map<String,Long> gates=new LinkedHashMap<>();
    private int sequence;

    static ViewGroup enterMirror(ViewGroup frame) {
        ViewGroup previous=MIRROR.get();
        MIRROR.set(frame);
        return previous;
    }

    static void leaveMirror(ViewGroup previous) {
        if(previous==null) MIRROR.remove(); else MIRROR.set(previous);
    }

    void layout(View meter,View image,int width,int height,float scale,java.util.List<ViewGroup> mirrors,long now,Consumer<String> sink) {
        if(!permit("layout",null,now,1000)) return;
        String trace=begin("layout",meter,now,sink);
        sink.accept(trace+" requested="+width+"x"+height+" rendererScale="+scale+" mirrors="+mirrors.size());
        chain(trace,"image",image,sink);
        for(int index=0;index<mirrors.size();index++) chain(trace,"mirror-layout-"+index,mirrors.get(index),sink);
    }

    void draw(View meter,View image,Canvas canvas,Rect bounds,float scale,long now,Consumer<String> sink) {
        Rect clip=new Rect();
        canvas.getClipBounds(clip);
        boolean cut=clip.left>bounds.left || clip.top>bounds.top
                || clip.right<bounds.right || clip.bottom<bounds.bottom;
        ViewGroup mirror=MIRROR.get();
        if(!permit(cut?"draw-cut":"draw-full",mirror,now,cut?1000:2000)) return;
        String trace=begin(cut?"draw-cut":"draw-full",meter,now,sink);
        float[] matrix=new float[9];
        canvas.getMatrix().getValues(matrix);
        sink.accept(trace+" drawable="+rect(bounds)+" canvasClip="+rect(clip)
                +" rendererScale="+scale+" matrix="+Arrays.toString(matrix)
                +" mirror="+label(mirror));
        chain(trace,"image",image,sink);
        if(mirror!=null) chain(trace,"mirror",mirror,sink);
    }

    private boolean permit(String kind,View mirror,long now,long interval) {
        String key=kind+"@"+System.identityHashCode(mirror);
        Long next=gates.get(key);
        if(next!=null && now<next) return false;
        if(next==null && gates.size()>=16) gates.remove(gates.keySet().iterator().next());
        gates.put(key,now+interval);
        return true;
    }

    private String begin(String kind,View meter,long now,Consumer<String> sink) {
        String trace="ColorDuoTrace host="+label(meter)+" sample="+(++sequence);
        sink.accept(trace+" kind="+kind+" uptime="+now);
        return trace;
    }

    private static void chain(String trace,String role,View first,Consumer<String> sink) {
        View view=first;
        for(int depth=0;view!=null && depth<24;depth++) {
            int[] position=new int[2];
            view.getLocationInWindow(position);
            ViewGroup.LayoutParams params=view.getLayoutParams();
            String clipping="";
            if(view instanceof ViewGroup) {
                ViewGroup group=(ViewGroup)view;
                clipping=" clipChildren="+group.getClipChildren()+" clipPadding="+group.getClipToPadding();
            }
            String margins="";
            if(params instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams margin=(ViewGroup.MarginLayoutParams)params;
                margins=" margins="+margin.leftMargin+","+margin.topMargin+","+margin.rightMargin+","+margin.bottomMargin;
            }
            sink.accept(trace+" "+role+"["+depth+"]="+label(view)
                    +" size="+view.getWidth()+"x"+view.getHeight()
                    +" measured="+view.getMeasuredWidth()+"x"+view.getMeasuredHeight()
                    +" lp="+(params==null?"none":params.width+"x"+params.height)
                    +" minH="+view.getMinimumHeight()+" xy="+position[0]+","+position[1]
                    +" padding="+view.getPaddingLeft()+","+view.getPaddingTop()+","+view.getPaddingRight()+","+view.getPaddingBottom()
                    +margins+" clip="+rect(view.getClipBounds())+clipping
                    +" outline="+view.getClipToOutline()+" layer="+view.getLayerType()
                    +" visibility="+view.getVisibility()+" shown="+view.isShown()
                    +" alpha="+view.getAlpha()+" transitionAlpha="+view.getTransitionAlpha()
                    +" scale="+view.getScaleX()+","+view.getScaleY()
                    +" translation="+view.getTranslationX()+","+view.getTranslationY()
                    +" layoutPending="+view.isLayoutRequested()+" "+ColorOsSizing.describe(view));
            view=view.getParent() instanceof View?(View)view.getParent():null;
        }
    }

    private static String label(View view) {
        if(view==null) return "none";
        String name="no-id";
        if(view.getId()!=View.NO_ID) try {
            name=view.getResources().getResourceEntryName(view.getId());
        } catch(RuntimeException ignored) { name=Integer.toHexString(view.getId()); }
        return view.getClass().getSimpleName()+"#"+name+"@"+Integer.toHexString(System.identityHashCode(view));
    }

    private static String rect(Rect rect) {
        return rect==null?"none":rect.left+","+rect.top+","+rect.right+","+rect.bottom;
    }
}
