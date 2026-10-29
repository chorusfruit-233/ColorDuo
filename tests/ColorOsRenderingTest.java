package io.github.yixing233.hyperduo;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Regression scenes for upstream #58/#54 and live clock handoff. Runs production drawing code. */
public final class ColorOsRenderingTest {
    private static int checks;

    private static final class Frame extends Canvas implements AutoCloseable {
        final Graphics2D graphics;
        final List<Float> arcs = new ArrayList<>();
        final List<Integer> pathColors = new ArrayList<>();
        final List<Integer> arcColors = new ArrayList<>();
        final List<Integer> textColors = new ArrayList<>();
        final List<String> texts = new ArrayList<>();
        final List<java.awt.geom.Rectangle2D> textBounds = new ArrayList<>();
        float textSize;

        Frame() { this(new BufferedImage(480, 400, BufferedImage.TYPE_INT_ARGB).createGraphics()); }
        private Frame(Graphics2D graphics) { super(graphics); this.graphics = graphics; }
        @Override public void drawArc(RectF oval, float start, float sweep, boolean center, Paint paint) {
            arcs.add(sweep);
            arcColors.add(paint.getColor());
            super.drawArc(oval, start, sweep, center, paint);
        }
        @Override public void drawPath(Path path, Paint paint) {
            pathColors.add(paint.getColor());
            super.drawPath(path, paint);
        }
        @Override public void drawText(String text, float x, float y, Paint paint) {
            texts.add(text);
            textColors.add(paint.getColor());
            textSize = paint.getTextSize();
            android.graphics.Rect ink = new android.graphics.Rect();
            paint.getTextBounds(text, 0, text.length(), ink);
            float left = x - (paint.getTextAlign() == Paint.Align.CENTER ? paint.measureText(text) * 0.5f
                    : paint.getTextAlign() == Paint.Align.RIGHT ? paint.measureText(text) : 0f);
            textBounds.add(graphics.getTransform().createTransformedShape(new java.awt.geom.Rectangle2D.Float(
                    left + ink.left, y + ink.top, ink.right - ink.left, ink.bottom - ink.top)).getBounds2D());
            super.drawText(text, x, y, paint);
        }
        @Override public void close() { graphics.dispose(); }
    }

    public static void main(String[] args) throws Exception {
        fullCapsule();
        clockHandoff();
        readableDefaults();
        percentageSlider();
        wifiTypeHandoff();
        pausedGrayscale();
        System.out.println("Capsule fill, live clock handoff and reachable percentage sizing passed: " + checks + " checks");
    }

    private static void pausedGrayscale() {
        TrioSettings settings = TrioSettings.defaults();
        settings.mobileTypeMode = Prefs.MOBILE_TYPE_IN_RING;
        settings.typeHideOnWifi = false;
        settings.ringZones = Prefs.decodeLayout("1|5|7");
        TrioState state = new TrioState();
        state.level = 70;
        state.mobileType = "5G";
        state.useTint = true;
        state.tintColor = 0xff44ccff;
        state.chargingPaused = true;
        try (Frame ring = new Frame(); Frame all = new Frame()) {
            TrioRenderer.drawState(ring, 480, 400, state, settings);
            settings.pausedGrayAll = true;
            TrioRenderer.drawState(all, 480, 400, state, settings);
            check(ring.arcColors.contains(DuoPolicy.pausedColor(state.tintColor)), "paused battery arc is gray");
            check(ring.textColors.contains(state.tintColor), "ring-only preference retains network text color");
            check(!all.textColors.contains(state.tintColor)
                    && all.textColors.contains(DuoPolicy.pausedColor(state.tintColor)), "all-icons preference grays network text");
        }
        state.chargingPaused = false;
        state.charging = true;
        try (Frame ring = new Frame(); Frame all = new Frame()) {
            settings.pausedGrayAll = false;
            TrioRenderer.drawState(ring, 480, 400, state, settings);
            settings.pausedGrayAll = true;
            TrioRenderer.drawState(all, 480, 400, state, settings);
            check(ring.arcColors.equals(all.arcColors) && ring.textColors.equals(all.textColors),
                    "grayscale range never changes normal charging");
        }
    }

    private static void wifiTypeHandoff() {
        TrioSettings settings = TrioSettings.defaults();
        settings.trioStyle = Prefs.STYLE_RING;
        settings.mobileTypeMode = Prefs.MOBILE_TYPE_IN_RING;
        settings.showWifi = true;
        settings.typeHideOnWifi = true;
        settings.ringZones = Prefs.decodeLayout("|1,5||");
        TrioState state = new TrioState();
        state.level = 70;
        state.mobileType = "5G";
        state.mobileService = DuoPolicy.Radio.AVAILABLE;
        for (boolean connected : new boolean[] {true, false, true, false}) {
            state.wifiPresent = connected;
            state.wifiLevel = connected ? 3 : -1;
            try (Frame frame = new Frame()) {
                TrioRenderer.drawState(frame, 480, 400, state, settings);
                boolean typeDrawn = String.join("", frame.texts).contains("5G");
                check(typeDrawn == !connected, "middle Wi-Fi/RAT priority follows connection: " + connected);
            }
        }
    }

    private static void fullCapsule() throws Exception {
        Method fill = TrioRenderer.class.getDeclaredMethod("drawCapsuleFill", Canvas.class,
                float.class, float.class, float.class, float.class);
        fill.setAccessible(true);
        TrioSettings settings = TrioSettings.defaults();
        settings.trioStyle = Prefs.STYLE_CAPSULE;
        settings.roleColors = false;
        settings.showSim1 = settings.showSim2 = true;
        settings.ringZones = Prefs.decodeLayout("7|3|8|");
        TrioState state = new TrioState();
        state.level = 100;
        state.slotLevels = new int[]{4, 2};
        for (int length : new int[]{-30, -21, -20, 0, 60}) {
            settings.capsuleLength = length;
            for (int width : new int[]{Prefs.MIN_RING_STROKE, Prefs.DEF_RING_STROKE, Prefs.MAX_RING_STROKE}) {
                settings.ringStroke = width;
                // The live 100% frame repaints exactly the complete track, including asymmetric gaps.
                try (Frame frame = new Frame()) {
                    TrioRenderer.drawState(frame, 480, 400, state, settings);
                    check(frame.pathColors.size() >= 2 && frame.pathColors.get(0) != frame.pathColors.get(1),
                            "full battery repaints the entire track in foreground: length=" + length);
                    check(frame.pathColors.get(1) == state.foreground(), "full track uses battery ink");
                }
                float stroke = width * TrioGeometry.CAP_STROKE_RATIO;
                float reach = TrioGeometry.capDotReach(stroke);
                // At 100%, each half-circle must be complete even when straight runs reverse direction.
                try (Frame frame = new Frame()) {
                    fill.invoke(null, frame, stroke, 1f, reach, reach);
                    check(frame.arcs.size() == 2, "both caps draw");
                    check(Math.abs(frame.arcs.get(0) - 180f) < 0.001f
                                    && Math.abs(frame.arcs.get(1) - 180f) < 0.001f,
                            "full budget reaches both complete caps: length=" + length + " stroke=" + width);
                }
                // With matching gaps, half the charge must finish the left cap before entering the right side.
                try (Frame frame = new Frame()) {
                    fill.invoke(null, frame, stroke, 0.5f, reach, reach);
                    check(!frame.arcs.isEmpty() && Math.abs(frame.arcs.get(0) - 180f) < 0.001f,
                            "50% reaches the end of the first cap at every permitted length");
                }
                try (Frame frame = new Frame()) {
                    fill.invoke(null, frame, stroke, 0f, reach, reach);
                    check(frame.arcs.isEmpty(), "empty battery never lights a cap");
                }
            }
        }
    }

    private static void clockHandoff() {
        TrioSettings settings = TrioSettings.defaults();
        settings.trioStyle = Prefs.STYLE_CAPSULE;
        TrioState state = new TrioState();
        TrioState.sClockText = "19:41";
        assertClock(state, settings, true, "untouched capsule keeps its shipped clock");
        settings.ringZones = Prefs.decodeLayout("|3||");
        assertClock(state, settings, false, "empty left with a right-side percentage hands time back");
        settings.ringZones = Prefs.decodeLayout("|||1,6");
        state.wifiPresent = true;
        state.wifiLevel = 3;
        assertClock(state, settings, false, "connected Wi-Fi wins the left slot");
        state.wifiPresent = false;
        assertClock(state, settings, true, "Wi-Fi disconnect resolves the clock fallback");
        state.wifiPresent = true;
        assertClock(state, settings, false, "reconnect restores the native clock");
        settings.ringZones = Prefs.decodeLayout("|||4,6");
        state.charging = true;
        assertClock(state, settings, false, "charging bolt replaces the clock");
        state.charging = false;
        state.chargingPaused = true;
        assertClock(state, settings, false, "paused layout agrees with live painting");
        state.chargingPaused = false;
        assertClock(state, settings, true, "unplugging restores the clock fallback");
        settings.ringZones = Prefs.decodeLayout("|||5,6");
        settings.mobileTypeMode = Prefs.MOBILE_TYPE_IN_RING;
        settings.typeHideOnWifi = false;
        state.mobileType = "5G";
        assertClock(state, settings, false, "available network type wins the left slot");
        state.mobileService = DuoPolicy.Radio.REGISTERING;
        assertClock(state, settings, true, "registration clears the type before resolving fallback");
        settings.ringZones = Prefs.decodeLayout("6|||");
        assertClock(state, settings, true, "clock in a capsule opening also owns native time");
        settings.trioStyle = Prefs.STYLE_RING;
        settings.ringZones = Prefs.decodeLayout("|3,6||");
        assertClock(state, settings, false, "clock behind a visible value does not hide native time");
        settings.ringZones = Prefs.decodeLayout("|6||");
        settings.chargingShowsValue = true;
        state.charging = true;
        assertClock(state, settings, false, "charging percentage override releases native time");
        state.charging = false;
        assertClock(state, settings, true, "ring clock resumes after charging");
        TrioState.sClockText = "";
        assertClock(state, settings, false, "missing clock text cannot hide native time");
        TrioState.sClockText = "19:41";
        settings.enabled = false;
        check(!TrioRenderer.drawsClock(state, TrioAppearance.of(settings)), "master off releases the clock");
    }

    private static void assertClock(TrioState state, TrioSettings settings, boolean expected, String scene) {
        try (Frame frame = new Frame()) {
            TrioRenderer.drawState(frame, 480, 400, state, settings);
            boolean painted = frame.texts.contains(TrioState.sClockText) && !TrioState.sClockText.isEmpty();
            check(painted == expected, "scene actually paints the expected clock: " + scene);
            check(TrioRenderer.drawsClock(state, TrioAppearance.of(settings)) == painted,
                    "native clock handoff matches the painted frame: " + scene);
        }
    }

    private static void percentageSlider() {
        TrioSettings settings = TrioSettings.defaults();
        settings.trioStyle = Prefs.STYLE_RING;
        TrioState state = new TrioState();
        state.level = 88;
        for (int zone : new int[]{0, 2}) for (int stroke : new int[]{4, 14, 16}) {
            settings.ringStroke = stroke;
            settings.ringZones = Prefs.decodeLayout("|||");
            settings.ringZones[zone] = new int[]{Prefs.PART_VALUE};
            int maximum = TrioRenderer.maxValueSize(TrioAppearance.of(settings));
            check(maximum >= Prefs.MIN_VALUE_SIZE && maximum < Prefs.MAX_RING_VALUE_SIZE,
                    "opening-specific ceiling removes the inactive slider range");
            settings.valueSize = maximum - 1;
            float before;
            try (Frame frame = new Frame()) {
                TrioRenderer.drawState(frame, 480, 400, state, settings);
                before = frame.textSize;
            }
            settings.valueSize = maximum;
            try (Frame frame = new Frame()) {
                TrioRenderer.drawState(frame, 480, 400, state, settings);
                check(frame.textSize > before + 0.1f, "last slider step still enlarges the percentage");
            }
        }
        settings.ringZones = Prefs.decodeLayout("|3||");
        check(TrioRenderer.maxValueSize(TrioAppearance.of(settings)) >= Prefs.DEF_VALUE_SIZE,
                "centre range includes the default percentage");
        settings.trioStyle = Prefs.STYLE_CAPSULE;
        check(TrioRenderer.maxValueSize(TrioAppearance.of(settings)) == Prefs.MAX_CAPSULE_VALUE_SIZE,
                "capsule size range is unchanged");
    }

    private static void readableDefaults() {
        TrioSettings settings = TrioSettings.fromBundle(new android.os.Bundle());
        settings.trioStyle = Prefs.STYLE_RING;
        TrioState state = new TrioState();
        check(settings.valueSize == 36, "first install and empty remote snapshots use the documented default");
        for (int zone : new int[]{0, 2}) for (int stroke : new int[]{4, 14, 16}) {
            settings.ringStroke = stroke;
            settings.ringZones = Prefs.decodeLayout("|||");
            settings.ringZones[zone] = new int[]{Prefs.PART_VALUE};
            TrioAppearance appearance = TrioAppearance.of(settings);
            check(TrioRenderer.maxValueSize(appearance) >= settings.valueSize,
                    "default fits both end slots: zone=" + zone + " stroke=" + stroke
                            + " max=" + TrioRenderer.maxValueSize(appearance));
            for (int level : new int[]{8, 88, 100}) {
                state.level = level;
                try (Frame frame = new Frame()) {
                    TrioRenderer.drawState(frame, 480, 400, state, settings);
                    check(Math.abs(frame.textSize - settings.valueSize) < 0.01f,
                            "default 36 is actually drawn at 36: zone=" + zone + " level=" + level
                                    + " stroke=" + stroke + " actual=" + frame.textSize);
                }
            }
            state.level = 88;
            settings.valueSize = 40;
            try (Frame frame = new Frame()) {
                TrioRenderer.drawState(frame, 480, 400, state, settings);
                check(frame.textSize > 36f, "both end slots can grow beyond the default");
            }
            settings.valueSize = Prefs.DEF_VALUE_SIZE;
        }
        settings.ringZones = Prefs.decodeLayout("3|6|7|");
        settings.showSim1 = true;
        TrioAppearance.Ring topText = TrioAppearance.of(settings).ring(88, false, -1, "5G", 2, false);
        check(Math.abs((topText.gapEnd() - topText.gapStart()) * topText.sweep() - 128f) < 0.01f,
                "text widens the top opening to clear the digits");
        check(Math.abs(360f - topText.sweep() - 96f) < 0.01f,
                "signal beads keep their original opening and spacing");
        settings.ringZones = Prefs.decodeLayout("7|6|3|");
        TrioAppearance.Ring bottomText = TrioAppearance.of(settings).ring(88, false, -1, "5G", 2, false);
        check(Math.abs(360f - bottomText.sweep() - 128f) < 0.01f,
                "bottom text has the same opening width as top text");
        check(TrioGeometry.zoneTextRoomH(1, bottomText.parts)
                        <= 2f * (TrioGeometry.ZONE_CUT_BOTTOM - TrioGeometry.ZONE_CY[1] - 2.5f),
                "middle text cannot consume an occupied bottom band");
        settings.ringZones = Prefs.decodeLayout("3|6|5|");
        settings.mobileTypeMode = Prefs.MOBILE_TYPE_IN_RING;
        settings.valueSize = Prefs.MAX_RING_VALUE_SIZE;
        settings.clockSize = Prefs.MAX_CLOCK_SIZE;
        settings.typeSize = Prefs.MAX_TYPE_SIZE;
        state.mobileType = "5G";
        try (Frame frame = new Frame()) {
            TrioRenderer.drawState(frame, 480, 400, state, settings);
            check(frame.textBounds.size() == 3, "top, middle and bottom all paint their selected text");
            for (int a = 0; a < frame.textBounds.size(); a++) for (int b = a + 1; b < frame.textBounds.size(); b++) {
                check(!frame.textBounds.get(a).intersects(frame.textBounds.get(b)),
                        "enlarged text never consumes an occupied neighbouring band");
            }
        }
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
