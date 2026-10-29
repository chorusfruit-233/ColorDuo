package io.github.yixing233.hyperduo;

import android.graphics.Canvas;
import android.os.Bundle;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Arrays;

/** Executes real settings transport, ownership and rendering with the existing desktop shims. */
public final class ColorOsSettingsTest {
    public static void main(String[] args) {
        adjustableGeometry();
        signalIconScaleSettings();
        ellipticalSides();
        centreOwnershipAndBoltVisibility();
        TrioSettings fresh = TrioSettings.defaults();
        check(fresh.simStackGap==Prefs.DEF_SIM_STACK_GAP,"paired spacing has a stable upgrade default");
        TrioSettings stacked=fresh.copy();stacked.simStackGap=300;
        check(stacked.copy().simStackGap==300 && !stacked.equals(fresh),"spacing survives copy and change detection");
        check(TrioSettings.fromBundle(stacked.toBundle()).simStackGap==300,"spacing survives full reload transport");
        Bundle gap=new Bundle();gap.putInt(Prefs.KEY_SIM_STACK_GAP,-10);
        check(TrioSettings.fromBundle(gap).simStackGap==0,"negative spacing clamps to touching rows");
        gap.putInt(Prefs.KEY_SIM_STACK_GAP,999);
        check(TrioSettings.fromBundle(gap).simStackGap==300,"spacing upper limit is shared by transports");
        check(fresh.applyKeyFrom(stacked,Prefs.KEY_SIM_STACK_GAP)&&fresh.simStackGap==300,"remote preference key applies immediately");
        fresh.simStackGap=Prefs.DEF_SIM_STACK_GAP;
        for(int style:new int[]{Prefs.STYLE_RING,Prefs.STYLE_CAPSULE}) {
            TrioSettings placed=fresh.copy();placed.trioStyle=style;placed.ringZones=Prefs.decodeLayout("7||8|");
            placed=TrioZones.withPiece(placed,0,TrioZones.Part.SIM_PAIR);
            check(placed.ringZones[0][0]==Prefs.PART_SIM_PAIR&&placed.ringZones[2].length==0,"pair replaces individually placed cards");
            check(TrioAppearance.of(placed).foldsMobile(),"paired rows take over the native mobile icon");
            check(TrioZones.assign(TrioZones.Zone.CENTRE,false,TrioZones.Part.SIM_PAIR,placed,2)!=null,"paired signals may occupy the middle");
            check(TrioZones.assign(TrioZones.Zone.LEFT,false,TrioZones.Part.SIM_PAIR,placed,2)==null,"capsule body cannot swallow a paired opening");
            check(TrioSettings.fromBundle(placed.toBundle()).ringZones[0][0]==Prefs.PART_SIM_PAIR,"new part ID survives configuration transport");
            placed=TrioZones.withPiece(placed,2,TrioZones.Part.SIM_1);
            check(placed.ringZones[0].length==0&&placed.ringZones[2][0]==Prefs.PART_SIM_1,"independent placement removes the paired component");
        }

        check(!fresh.pausedGrayAll, "paused charge defaults to battery ring only");
        TrioSettings pausedSettings = fresh.copy();
        pausedSettings.pausedGrayAll = true;
        check(!pausedSettings.equals(fresh), "grayscale preference participates in change detection");
        check(pausedSettings.copy().pausedGrayAll, "grayscale preference survives snapshots");
        check(TrioSettings.fromBundle(pausedSettings.toBundle()).pausedGrayAll, "grayscale preference survives reload transport");
        check(fresh.applyKeyFrom(pausedSettings, Prefs.KEY_PAUSED_GRAY_ALL) && fresh.pausedGrayAll,
                "remote single-key updates apply the preference");
        fresh.pausedGrayAll = false;
        for (int[] zone : fresh.ringZones) check(zone.length == 0, "upstream starts with empty component zones");
        check(!fresh.showSim1 && !fresh.showSim2, "per-card rows are opt-in upstream");
        check(!TrioAppearance.of(fresh).foldsMobile(), "unplaced signal remains native");
        check(!TrioAppearance.of(fresh).ownsWifi(), "unplaced Wi-Fi remains native");
        check(TrioSettings.fromBundle(fresh.toBundle()).equals(fresh), "full settings transport");
        check(Arrays.deepEquals(TrioSettings.fromBundle(new Bundle()).ringZones, fresh.ringZones),
                "first connection without stored settings uses the same layout");

        Bundle cleared = new Bundle();
        cleared.putString(Prefs.KEY_RING_LAYOUT, Prefs.EMPTY_LAYOUT);
        TrioSettings empty = TrioSettings.fromBundle(cleared);
        for (int[] zone : empty.ringZones) check(zone.length == 0, "explicitly cleared layout survives");
        check(!TrioAppearance.of(empty).ownsWifi() && !TrioAppearance.of(empty).foldsMobile(),
                "clearing components restores native signal ownership");
        check(Arrays.deepEquals(TrioSettings.fromBundle(empty.toBundle()).ringZones, empty.ringZones),
                "empty layout survives a second transport");

        Bundle legacy = new Bundle();
        legacy.putInt(Prefs.KEY_RING_SLOTS_V2, Prefs.packRingParts(Prefs.PART_VALUE, Prefs.PART_WIFI, Prefs.PART_SIM_1));
        TrioSettings migrated = TrioSettings.fromBundle(legacy);
        check(Arrays.equals(migrated.ringZones[0], new int[] {Prefs.PART_VALUE}),
                "existing packed layouts retain upstream migration");

        TrioSettings dual = fresh.copy();
        dual.showSim1 = true;
        dual.showSim2 = true;
        dual.ringZones = Prefs.decodeLayout("7|1|8");
        TrioAppearance.Ring layout = TrioAppearance.of(dual).ring(65, false, 3, "5G", 2);
        check(layout.parts[0] == Prefs.PART_SIM_1 && layout.parts[2] == Prefs.PART_SIM_2,
                "separately placed cards display simultaneously");
        check(Arrays.deepEquals(TrioSettings.fromBundle(dual.toBundle()).ringZones, dual.ringZones),
                "explicit dual-card placement is preserved");
        int[] reading = new int[2];
        TrioRenderer.outSignalLevels(false, 4, new int[] {4, -1}, 0, reading);
        check(Arrays.equals(reading, new int[] {4, -1}), "single SIM uses one full reading");
        TrioRenderer.outSignalLevels(false, 2, new int[] {-1, 2}, 1, reading);
        check(Arrays.equals(reading, new int[] {2, -1}), "a lone SIM in slot 2 is not duplicated");
        TrioRenderer.outSignalLevels(false, 4, new int[] {4, 2}, 0, reading);
        check(Arrays.equals(reading, new int[] {4, 2}), "dual readings retain each card's level");
        TrioRenderer.outSignalLevels(true, 2, new int[] {4, 2}, 1, reading);
        check(Arrays.equals(reading, new int[] {2, -1}), "data-SIM-only selects the actual data slot");
        TrioRenderer.outSignalLevels(false, 2, new int[] {4, 2}, 1, reading);
        check(Arrays.equals(reading, new int[] {2, 4}), "automatic rows put the data line first");

        BufferedImage image = new BufferedImage(240, 200, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            TrioRenderer.drawInto(new Canvas(graphics), 240, 200, 65, false, false,
                    false, false, 4, 4, new int[] {4, 2}, "5G", 0xFFFFFFFF, dual, true, true);
        } finally { graphics.dispose(); }
        int ink = 0;
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
            if ((image.getRGB(x, y) >>> 24) != 0) ink++;
        }
        check(ink > 1000, "configured upstream dual-card ring paints visible artwork");
        TrioState live = new TrioState();
        live.level = 65;
        live.wifiPresent = false;
        live.slotLevels = new int[] {4, 1};
        live.mobileLevel = 1;
        live.dataSlot = 1;
        TrioSettings automatic = fresh.copy();
        automatic.trioStyle = Prefs.STYLE_RECT;
        automatic.dualSim = true;
        BufferedImage dataSecond = render(live, automatic);
        live.slotLevels = new int[] {1, 4};
        live.dataSlot = 0;
        check(equal(dataSecond, render(live, automatic)), "live rendering follows data line, not physical index");

        live.slotLevels = new int[] {4, 1};
        live.dataSlot = 0;
        BufferedImage namedRing = render(live, dual);
        live.dataSlot = 1;
        check(equal(namedRing, render(live, dual)), "explicit ring SIM components never swap identities");
        TrioSettings capsule = dual.copy();
        capsule.trioStyle = Prefs.STYLE_CAPSULE;
        TrioAppearance.Capsule cap = TrioAppearance.of(capsule).capsule(65, false, -1, "", 2);
        check(TrioRenderer.capsuleSignalLevel(cap, TrioZones.Zone.TOP, new int[]{4,1}, 1) == 4,
                "named SIM 1 capsule band keeps its physical reading");
        check(TrioRenderer.capsuleSignalLevel(cap, TrioZones.Zone.BOTTOM, new int[]{4,1}, 4) == 1,
                "named SIM 2 capsule band keeps its physical reading");
        BufferedImage namedCapsule = render(live, capsule);
        live.dataSlot = 0;
        check(equal(namedCapsule, render(live, capsule)), "manual capsule rows survive a data-line switch");

        live.charging = true;
        live.quickCharging = true;
        BufferedImage charging = render(live, dual);
        live.chargingPaused = true;
        BufferedImage paused = render(live, dual);
        int gray = 0;
        for (int y=0; y<paused.getHeight(); y++) for (int x=0; x<paused.getWidth(); x++) {
            if (paused.getRGB(x,y) == 0xff8e8e93) gray++;
        }
        check(gray > 100 && !equal(charging, paused), "paused state actually paints neutral gray");
        live.chargingPaused = false;
        check(equal(charging, render(live, dual)), "resume restores the exact charging appearance");
        live.charging = false;
        live.quickCharging = false;
        for(TrioSettings style : new TrioSettings[]{dual,capsule,automatic}) {
            live.mobileService = DuoPolicy.Radio.AVAILABLE;
            live.slotLevels = new int[]{0,0};
            BufferedImage zeroSignal = render(live, style);
            live.mobileService = DuoPolicy.Radio.REGISTERING;
            BufferedImage registering = render(live, style);
            check(ink(registering) < ink(zeroSignal), "registration removes signal dots rather than drawing an unlit placeholder");
            live.slotLevels = new int[]{4,1};
            check(equal(registering, render(live, style)), "registration cannot leak stale SIM signal readings");
            live.mobileService = DuoPolicy.Radio.AVAILABLE;
            check(!equal(registering, render(live, style)), "successful registration restores the saved signal components");
        }
        System.out.println("Settings migration, named SIM identity, data-line order and paused rendering passed");
    }

    private static BufferedImage render(TrioState state, TrioSettings settings) {
        BufferedImage result = new BufferedImage(240,200,BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = result.createGraphics();
        try { TrioRenderer.drawState(new Canvas(graphics),240,200,state,settings); }
        finally { graphics.dispose(); }
        return result;
    }

    private static boolean equal(BufferedImage left, BufferedImage right) {
        return Arrays.equals(left.getRGB(0,0,240,200,null,0,240), right.getRGB(0,0,240,200,null,0,240));
    }

    private static int ink(BufferedImage image) {
        int count=0;
        for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++) {
            if((image.getRGB(x,y) >>> 24) != 0)count++;
        }
        return count;
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void adjustableGeometry() {
        TrioSettings source=TrioSettings.defaults();
        source.splitMode=1;
        check(source.copy().splitMode==1,"splitMode copy");
        check(TrioSettings.fromBundle(source.toBundle()).splitMode==1,"splitMode transport");
        TrioSettings targetsplitMode=TrioSettings.defaults();
        check(targetsplitMode.applyKeyFrom(source,Prefs.KEY_SPLIT_MODE) && targetsplitMode.splitMode==1,"splitMode hot reload");
        source.splitLink=1;
        check(source.copy().splitLink==1,"splitLink copy");
        check(TrioSettings.fromBundle(source.toBundle()).splitLink==1,"splitLink transport");
        TrioSettings targetsplitLink=TrioSettings.defaults();
        check(targetsplitLink.applyKeyFrom(source,Prefs.KEY_SPLIT_LINK) && targetsplitLink.splitLink==1,"splitLink hot reload");
        source.splitCap=1;
        check(source.copy().splitCap==1,"splitCap copy");
        check(TrioSettings.fromBundle(source.toBundle()).splitCap==1,"splitCap transport");
        TrioSettings targetsplitCap=TrioSettings.defaults();
        check(targetsplitCap.applyKeyFrom(source,Prefs.KEY_SPLIT_CAP) && targetsplitCap.splitCap==1,"splitCap hot reload");
        source.pairShape=1;
        check(source.copy().pairShape==1,"pairShape copy");
        check(TrioSettings.fromBundle(source.toBundle()).pairShape==1,"pairShape transport");
        TrioSettings targetpairShape=TrioSettings.defaults();
        check(targetpairShape.applyKeyFrom(source,Prefs.KEY_PAIR_SHAPE) && targetpairShape.pairShape==1,"pairShape hot reload");
        source.pairMirror=1;
        check(source.copy().pairMirror==1,"pairMirror copy");
        check(TrioSettings.fromBundle(source.toBundle()).pairMirror==1,"pairMirror transport");
        TrioSettings targetpairMirror=TrioSettings.defaults();
        check(targetpairMirror.applyKeyFrom(source,Prefs.KEY_PAIR_MIRROR) && targetpairMirror.pairMirror==1,"pairMirror hot reload");
        source.pairReverse=1;
        check(source.copy().pairReverse==1,"pairReverse copy");
        check(TrioSettings.fromBundle(source.toBundle()).pairReverse==1,"pairReverse transport");
        TrioSettings targetpairReverse=TrioSettings.defaults();
        check(targetpairReverse.applyKeyFrom(source,Prefs.KEY_PAIR_REVERSE) && targetpairReverse.pairReverse==1,"pairReverse hot reload");
        source.pairSingle=1;
        check(source.copy().pairSingle==1,"pairSingle copy");
        check(TrioSettings.fromBundle(source.toBundle()).pairSingle==1,"pairSingle transport");
        TrioSettings targetpairSingle=TrioSettings.defaults();
        check(targetpairSingle.applyKeyFrom(source,Prefs.KEY_PAIR_SINGLE) && targetpairSingle.pairSingle==1,"pairSingle hot reload");
        source.pairPitch=250;
        check(source.copy().pairPitch==250,"pairPitch copy");
        check(TrioSettings.fromBundle(source.toBundle()).pairPitch==250,"pairPitch transport");
        TrioSettings targetpairPitch=TrioSettings.defaults();
        check(targetpairPitch.applyKeyFrom(source,Prefs.KEY_PAIR_PITCH) && targetpairPitch.pairPitch==250,"pairPitch hot reload");
        source.pairBend=30;
        check(source.copy().pairBend==30,"pairBend copy");
        check(TrioSettings.fromBundle(source.toBundle()).pairBend==30,"pairBend transport");
        TrioSettings targetpairBend=TrioSettings.defaults();
        check(targetpairBend.applyKeyFrom(source,Prefs.KEY_PAIR_BEND) && targetpairBend.pairBend==30,"pairBend hot reload");
        source.pairX=40;
        check(source.copy().pairX==40,"pairX copy");
        check(TrioSettings.fromBundle(source.toBundle()).pairX==40,"pairX transport");
        TrioSettings targetpairX=TrioSettings.defaults();
        check(targetpairX.applyKeyFrom(source,Prefs.KEY_PAIR_X) && targetpairX.pairX==40,"pairX hot reload");
        source.pairY=40;
        check(source.copy().pairY==40,"pairY copy");
        check(TrioSettings.fromBundle(source.toBundle()).pairY==40,"pairY transport");
        TrioSettings targetpairY=TrioSettings.defaults();
        check(targetpairY.applyKeyFrom(source,Prefs.KEY_PAIR_Y) && targetpairY.pairY==40,"pairY hot reload");
        source.splitLeftDistance=80;
        check(source.copy().splitLeftDistance==80,"splitLeftDistance copy");
        check(TrioSettings.fromBundle(source.toBundle()).splitLeftDistance==80,"splitLeftDistance transport");
        TrioSettings targetsplitLeftDistance=TrioSettings.defaults();
        check(targetsplitLeftDistance.applyKeyFrom(source,Prefs.KEY_SPLIT_LEFT_DISTANCE) && targetsplitLeftDistance.splitLeftDistance==80,"splitLeftDistance hot reload");
        source.splitLeftBend=80;
        check(source.copy().splitLeftBend==80,"splitLeftBend copy");
        check(TrioSettings.fromBundle(source.toBundle()).splitLeftBend==80,"splitLeftBend transport");
        TrioSettings targetsplitLeftBend=TrioSettings.defaults();
        check(targetsplitLeftBend.applyKeyFrom(source,Prefs.KEY_SPLIT_LEFT_BEND) && targetsplitLeftBend.splitLeftBend==80,"splitLeftBend hot reload");
        source.splitLeftHeight=110;
        check(source.copy().splitLeftHeight==110,"splitLeftHeight copy");
        check(TrioSettings.fromBundle(source.toBundle()).splitLeftHeight==110,"splitLeftHeight transport");
        TrioSettings targetsplitLeftHeight=TrioSettings.defaults();
        check(targetsplitLeftHeight.applyKeyFrom(source,Prefs.KEY_SPLIT_LEFT_HEIGHT) && targetsplitLeftHeight.splitLeftHeight==110,"splitLeftHeight hot reload");
        source.splitLeftTop=80;
        check(source.copy().splitLeftTop==80,"splitLeftTop copy");
        check(TrioSettings.fromBundle(source.toBundle()).splitLeftTop==80,"splitLeftTop transport");
        TrioSettings targetsplitLeftTop=TrioSettings.defaults();
        check(targetsplitLeftTop.applyKeyFrom(source,Prefs.KEY_SPLIT_LEFT_TOP) && targetsplitLeftTop.splitLeftTop==80,"splitLeftTop hot reload");
        source.splitLeftBottom=80;
        check(source.copy().splitLeftBottom==80,"splitLeftBottom copy");
        check(TrioSettings.fromBundle(source.toBundle()).splitLeftBottom==80,"splitLeftBottom transport");
        TrioSettings targetsplitLeftBottom=TrioSettings.defaults();
        check(targetsplitLeftBottom.applyKeyFrom(source,Prefs.KEY_SPLIT_LEFT_BOTTOM) && targetsplitLeftBottom.splitLeftBottom==80,"splitLeftBottom hot reload");
        source.splitLeftY=40;
        check(source.copy().splitLeftY==40,"splitLeftY copy");
        check(TrioSettings.fromBundle(source.toBundle()).splitLeftY==40,"splitLeftY transport");
        TrioSettings targetsplitLeftY=TrioSettings.defaults();
        check(targetsplitLeftY.applyKeyFrom(source,Prefs.KEY_SPLIT_LEFT_Y) && targetsplitLeftY.splitLeftY==40,"splitLeftY hot reload");
        source.splitRightDistance=80;
        check(source.copy().splitRightDistance==80,"splitRightDistance copy");
        check(TrioSettings.fromBundle(source.toBundle()).splitRightDistance==80,"splitRightDistance transport");
        TrioSettings targetsplitRightDistance=TrioSettings.defaults();
        check(targetsplitRightDistance.applyKeyFrom(source,Prefs.KEY_SPLIT_RIGHT_DISTANCE) && targetsplitRightDistance.splitRightDistance==80,"splitRightDistance hot reload");
        source.splitRightBend=80;
        check(source.copy().splitRightBend==80,"splitRightBend copy");
        check(TrioSettings.fromBundle(source.toBundle()).splitRightBend==80,"splitRightBend transport");
        TrioSettings targetsplitRightBend=TrioSettings.defaults();
        check(targetsplitRightBend.applyKeyFrom(source,Prefs.KEY_SPLIT_RIGHT_BEND) && targetsplitRightBend.splitRightBend==80,"splitRightBend hot reload");
        source.splitRightHeight=110;
        check(source.copy().splitRightHeight==110,"splitRightHeight copy");
        check(TrioSettings.fromBundle(source.toBundle()).splitRightHeight==110,"splitRightHeight transport");
        TrioSettings targetsplitRightHeight=TrioSettings.defaults();
        check(targetsplitRightHeight.applyKeyFrom(source,Prefs.KEY_SPLIT_RIGHT_HEIGHT) && targetsplitRightHeight.splitRightHeight==110,"splitRightHeight hot reload");
        source.splitRightTop=80;
        check(source.copy().splitRightTop==80,"splitRightTop copy");
        check(TrioSettings.fromBundle(source.toBundle()).splitRightTop==80,"splitRightTop transport");
        TrioSettings targetsplitRightTop=TrioSettings.defaults();
        check(targetsplitRightTop.applyKeyFrom(source,Prefs.KEY_SPLIT_RIGHT_TOP) && targetsplitRightTop.splitRightTop==80,"splitRightTop hot reload");
        source.splitRightBottom=80;
        check(source.copy().splitRightBottom==80,"splitRightBottom copy");
        check(TrioSettings.fromBundle(source.toBundle()).splitRightBottom==80,"splitRightBottom transport");
        TrioSettings targetsplitRightBottom=TrioSettings.defaults();
        check(targetsplitRightBottom.applyKeyFrom(source,Prefs.KEY_SPLIT_RIGHT_BOTTOM) && targetsplitRightBottom.splitRightBottom==80,"splitRightBottom hot reload");
        source.splitRightY=40;
        check(source.copy().splitRightY==40,"splitRightY copy");
        check(TrioSettings.fromBundle(source.toBundle()).splitRightY==40,"splitRightY transport");
        TrioSettings targetsplitRightY=TrioSettings.defaults();
        check(targetsplitRightY.applyKeyFrom(source,Prefs.KEY_SPLIT_RIGHT_Y) && targetsplitRightY.splitRightY==40,"splitRightY hot reload");
        TrioSettings a=TrioSettings.defaults(); a.splitMode=1;
        TrioGeometry.SplitRing original=new TrioGeometry.SplitRing(TrioAppearance.of(a));
        a.splitLeftDistance+=20;
        TrioGeometry.SplitRing moved=new TrioGeometry.SplitRing(TrioAppearance.of(a));
        check(moved.width()>original.width(),"separation grows the actual host width");
        check(Math.abs(moved.length-original.length)<.01f,"translation does not change curvature or ink length");
        a.splitLink=0;a.splitRightBend=0;
        TrioGeometry.SplitRing asymmetric=new TrioGeometry.SplitRing(TrioAppearance.of(a));
        check(asymmetric.length<moved.length,"independent right curvature changes actual path length");
        for(int i=0;i<asymmetric.x.length;i++)check(asymmetric.x[i]>=asymmetric.minX && asymmetric.x[i]<=asymmetric.maxX
            && asymmetric.y[i]>=asymmetric.minY && asymmetric.y[i]<=asymmetric.maxY,"all side path points fit measured bounds");
    }

    private static void ellipticalSides() {
        for(int bend:new int[]{1,5,20,40,80})for(int height:new int[]{20,60,110})
        for(int opening:new int[]{0,16,80}) {
            TrioSettings settings=TrioSettings.defaults();settings.splitMode=1;
            settings.splitLeftBend=bend;settings.splitLeftHeight=height;
            settings.splitLeftTop=opening;settings.splitLeftBottom=80-opening;
            TrioGeometry.SplitRing shape=new TrioGeometry.SplitRing(TrioAppearance.of(settings));
            for(int side=0;side<2;side++) {
                TrioGeometry.EllipseSide ellipse=shape.sides[side];
                check(ellipse.sweep>0 && ellipse.sweep<=(float)Math.PI+.00001f,"trimmed interval is at most half an ellipse");
                for(int n=0;n<=TrioGeometry.SplitRing.STEPS;n++) {
                    int i=side*(TrioGeometry.SplitRing.STEPS+1)+n;
                    double x=(shape.x[i]-ellipse.cx)/ellipse.rx,y=(shape.y[i]-ellipse.cy)/ellipse.ry;
                    check(Math.abs(x*x+y*y-1)<.00003,"every curve sample lies on the ellipse, including extreme eccentricities");
                    check(side==0?shape.x[i]<=ellipse.cx+.001:shape.x[i]>=ellipse.cx-.001,"each side stays on its own half ellipse");
                }
            }
        }
        TrioSettings circle=TrioSettings.defaults();circle.splitLeftBend=48;circle.splitLeftHeight=96;
        circle.splitLeftTop=circle.splitLeftBottom=0;
        TrioGeometry.EllipseSide side=new TrioGeometry.EllipseSide(TrioAppearance.of(circle),0);
        check(Math.abs(side.rx-side.ry)<.001 && Math.abs(side.sweep-Math.PI)<.00001,"equal axes recover an exact half circle");
        circle.splitLeftBend=0;
        side=new TrioGeometry.EllipseSide(TrioAppearance.of(circle),0);
        check(side.x(0)==side.x(.5f)&&side.x(1)==side.x(.5f),"zero bend degenerates into a straight line");
    }

    private static void centreOwnershipAndBoltVisibility() {
        for(int style:new int[]{Prefs.STYLE_RING,Prefs.STYLE_CAPSULE}) {
            TrioSettings old=TrioSettings.defaults();old.trioStyle=style;
            old.signalMode=Prefs.SIGNAL_OUT_RING;old.showMobile=false;
            old=TrioZones.withPiece(old,TrioZones.Zone.CENTRE.ordinal(),TrioZones.Part.SIM_PAIR);
            TrioSettings loaded=TrioSettings.fromBundle(old.toBundle());
            check(loaded.signalMode==Prefs.SIGNAL_IN_RING && loaded.showMobile,"placing paired SIMs takes ownership back from system");
            check(loaded.ringZones[1][0]==Prefs.PART_SIM_PAIR,"centre priority survives framework transport");
            TrioAppearance a=TrioAppearance.of(loaded);
            check(a.drawsSimPair(),"centre pair has ink after an outside-signal configuration");
            if(style==Prefs.STYLE_RING)check(a.ring(70,true,3,"5G",2,true).parts[1]==Prefs.PART_SIM_PAIR,
                    "charging percentage override must not replace a centre pair");
            loaded.showBolt=false;
            a=TrioAppearance.of(loaded);
            check(!a.drawsBolt() && a.hidesNativeBolt(),"hidden bolt suppresses both module and native icons");
            check(!TrioSettings.fromBundle(loaded.toBundle()).showBolt,"bolt visibility survives transport in every style");
        }
    }

    private static void signalIconScaleSettings() {
        TrioSettings defaults=TrioSettings.defaults();
        check(defaults.signalIconScale==100,"existing installs retain their original signal geometry");
        Bundle old=new Bundle();old.putInt(Prefs.KEY_DOT_SCALE,140);
        TrioSettings loaded=TrioSettings.fromBundle(old);
        check(loaded.dotScale==140&&loaded.signalIconScale==100,"renaming thickness preserves its key and value without migration");
        loaded.signalIconScale=175;
        check(loaded.copy().signalIconScale==175&&!loaded.equals(defaults),"icon scale survives copy and change detection");
        check(TrioSettings.fromBundle(loaded.toBundle()).signalIconScale==175,"icon scale survives full configuration reload");
        check(defaults.applyKeyFrom(loaded,Prefs.KEY_SIGNAL_ICON_SCALE)&&defaults.signalIconScale==175,"icon scale supports immediate remote preference updates");
        old.putInt(Prefs.KEY_SIGNAL_ICON_SCALE,-1);
        check(TrioSettings.fromBundle(old).signalIconScale==50,"undersized icon scales clamp safely");
        old.putInt(Prefs.KEY_SIGNAL_ICON_SCALE,999);
        check(TrioSettings.fromBundle(old).signalIconScale==200,"oversized icon scales clamp safely");
    }
}
