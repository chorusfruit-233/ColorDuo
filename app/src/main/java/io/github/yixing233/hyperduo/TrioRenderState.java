/* SPDX-License-Identifier: GPL-3.0-only */
package io.github.yixing233.hyperduo;

import java.util.Arrays;
import java.util.Objects;

/** Draw-input snapshot. Native binding/visibility enforcement must never be skipped with it. */
final class TrioRenderState {
    private final TrioAppearance appearance;
    private final int level,wifi,mobile,dataSlot,foreground,width,height,memory;
    private final int[] levels;
    private final boolean charging,quick,paused,saving,low,wifiPresent,animate;
    private final ChargeSpeed speed;
    private final DuoPolicy.Radio radio;
    private final String type,standard,clock;
    private final float scale;
    TrioRenderState(TrioState s,TrioAppearance a,int width,int height,float scale,boolean animate) {
        appearance=a;level=s.level;wifi=s.wifiLevel;mobile=s.mobileLevel;dataSlot=s.dataSlot;
        foreground=s.foreground();this.width=width;this.height=height;this.scale=scale;this.animate=animate;
        levels=s.slotLevels==null?null:s.slotLevels.clone();charging=s.charging;quick=s.quickCharging;
        paused=s.chargingPaused;saving=s.powerSave;low=s.low;wifiPresent=s.wifiPresent;
        speed=s.chargingSpeed;radio=s.mobileService;type=s.mobileType;standard=s.wifiStandard;
        clock=TrioState.sClockText;memory=TrioState.sMemoryPercent;
    }
    boolean matches(TrioState s,TrioAppearance a,int width,int height,float scale,boolean animate) {
        return appearance==a&&level==s.level&&wifi==s.wifiLevel&&mobile==s.mobileLevel&&dataSlot==s.dataSlot
            &&foreground==s.foreground()&&this.width==width&&this.height==height&&this.scale==scale&&this.animate==animate
            &&Arrays.equals(levels,s.slotLevels)&&charging==s.charging&&quick==s.quickCharging&&paused==s.chargingPaused
            &&saving==s.powerSave&&low==s.low&&wifiPresent==s.wifiPresent&&speed==s.chargingSpeed&&radio==s.mobileService
            &&Objects.equals(type,s.mobileType)&&Objects.equals(standard,s.wifiStandard)
            &&Objects.equals(clock,TrioState.sClockText)&&memory==TrioState.sMemoryPercent;
    }
}
