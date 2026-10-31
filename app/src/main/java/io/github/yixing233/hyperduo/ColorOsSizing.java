/* SPDX-License-Identifier: GPL-3.0-only */
package io.github.yixing233.hyperduo;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/** Measures against the containing surface and restores shared ancestor clipping on exit. */
final class ColorOsSizing {
    private static final Map<ViewGroup, ClipState> CLIPS = new WeakHashMap<>();
    private final Set<ViewGroup> held = Collections.newSetFromMap(new IdentityHashMap<>());

    private static final class ClipState {
        final boolean children, padding;
        final Set<ColorOsSizing> owners = Collections.newSetFromMap(new WeakHashMap<>());
        ClipState(ViewGroup view) {
            children=view.getClipChildren(); padding=view.getClipToPadding();
        }
    }

    TrioSizing.Metrics measure(ViewGroup meter, View image, TrioAppearance appearance) {
        float density=meter.getResources().getDisplayMetrics().density;
        ViewGroup surface=surface(meter);
        int maxHeight=Math.round(20f*density), maxWidth=Math.round(180f*density);
        if(surface!=null && surface.getHeight()>0) {
            int content=surface.getHeight()-surface.getPaddingTop()-surface.getPaddingBottom();
            maxHeight=Math.max(1,content);
            if(image.getHeight()>0) {
                int[] origin=new int[2], position=new int[2];
                surface.getLocationInWindow(origin); image.getLocationInWindow(position);
                int centre=position[1]-origin[1]+image.getHeight()/2;
                int symmetric=2*Math.min(centre-surface.getPaddingTop(),
                        surface.getHeight()-surface.getPaddingBottom()-centre);
                maxHeight=Math.min(maxHeight,Math.max(1,symmetric));
            }
            if(surface.getWidth()>0) maxWidth=Math.max(Math.round(24f*density),surface.getWidth()/2);
        }
        TrioSizing.Metrics metrics=TrioSizing.measure(appearance,density,maxHeight,maxWidth);
        Set<ViewGroup> needed=Collections.newSetFromMap(new IdentityHashMap<>());
        if(surface!=null && metrics.height>Math.round(20f*density)) {
            for(ViewParent p=image.getParent();p instanceof ViewGroup && p!=surface;p=p.getParent())
                needed.add((ViewGroup)p);
        }
        for(ViewGroup group:held.toArray(new ViewGroup[0])) if(!needed.contains(group)) release(group);
        for(ViewGroup group:needed) {
            ClipState state=CLIPS.get(group);
            if(state==null) { state=new ClipState(group);CLIPS.put(group,state); }
            state.owners.add(this);held.add(group);
            if(group.getClipChildren()) group.setClipChildren(false);
            if(group.getClipToPadding()) group.setClipToPadding(false);
        }
        return metrics;
    }

    private static ViewGroup surface(ViewGroup meter) {
        ViewGroup fallback=null;
        for(ViewParent p=meter.getParent();p instanceof ViewGroup;p=p.getParent()) {
            ViewGroup group=(ViewGroup)p;
            String type=group.getClass().getSimpleName();
            if(type.contains("PhoneStatusBarView") || type.contains("KeyguardStatusBarView")
                    || type.contains("QuickStatusBarHeader")) return group;
            int id=group.getId();
            if(id!=View.NO_ID) {
                try {
                    String name=group.getResources().getResourceEntryName(id);
                    if("status_bar".equals(name) || "keyguard_header".equals(name)
                            || "keyguard_status_bar".equals(name) || "quick_status_bar_header".equals(name))
                        return group;
                    if("system_icons".equals(name) || "quick_qs_status_icons".equals(name)) {
                        fallback=group.getParent() instanceof ViewGroup?(ViewGroup)group.getParent():group;
                    }
                } catch(RuntimeException ignored) { /* A generated view need not have a resource name. */ }
            }
        }
        // Unknown surfaces retain their own clipping and never borrow the full shade window.
        return fallback!=null?fallback:meter.getParent() instanceof ViewGroup?(ViewGroup)meter.getParent():null;
    }

    private void release(ViewGroup group) {
        held.remove(group);
        ClipState state=CLIPS.get(group);
        if(state==null) return;
        state.owners.remove(this);
        if(state.owners.isEmpty()) {
            group.setClipChildren(state.children);group.setClipToPadding(state.padding);CLIPS.remove(group);
        }
    }

    void release() { for(ViewGroup group:held.toArray(new ViewGroup[0])) release(group); }
}
