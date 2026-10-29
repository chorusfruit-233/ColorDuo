package io.github.yixing233.hyperduo;

import android.graphics.Canvas;
import android.os.Bundle;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Arrays;

/** Executes real settings transport, ownership and rendering with the existing desktop shims. */
public final class ColorOsSettingsTest {
    public static void main(String[] args) {
        TrioSettings fresh = TrioSettings.defaults();
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
}
