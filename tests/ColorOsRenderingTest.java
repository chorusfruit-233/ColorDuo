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
        final List<float[]> ellipseArcs = new ArrayList<>();
        final List<float[]> circles = new ArrayList<>();
        final List<Integer> circleColors = new ArrayList<>();
        final List<Integer> pathColors = new ArrayList<>();
        final List<Integer> arcColors = new ArrayList<>();
        final List<Integer> textColors = new ArrayList<>();
        final List<String> texts = new ArrayList<>();
        final List<java.awt.geom.Rectangle2D> textBounds = new ArrayList<>();
        float textSize;

        Frame() { this(new BufferedImage(480, 400, BufferedImage.TYPE_INT_ARGB).createGraphics()); }
        private Frame(Graphics2D graphics) { super(graphics); this.graphics = graphics; }
        @Override public void drawCircle(float x, float y, float radius, Paint paint) {
            java.awt.geom.Point2D point = graphics.getTransform().transform(new java.awt.geom.Point2D.Float(x,y), null);
            java.awt.geom.Point2D size = graphics.getTransform().deltaTransform(new java.awt.geom.Point2D.Float(radius,0), null);
            circles.add(new float[] {(float) point.getX(), (float) point.getY(), (float) Math.hypot(size.getX(),size.getY())});
            circleColors.add(paint.getColor());
            super.drawCircle(x,y,radius,paint);
        }
        @Override public void drawArc(RectF oval, float start, float sweep, boolean center, Paint paint) {
            arcs.add(sweep);
            ellipseArcs.add(new float[]{oval.width(),oval.height(),start,sweep});
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
        stackedSimRows();
        flexiblePairRows();
        alignedSplitRows();
        wholeSignalScale();
        trueEllipseRendering();
        centrePairDuringCharging();
        System.out.println("Capsule fill, live clock handoff and reachable percentage sizing passed: " + checks + " checks");
    }

    private static TrioSettings pairedSettings(int style, int zone, int gap) {
        TrioSettings settings = TrioSettings.defaults();
        settings.trioStyle = style;
        settings.showMobile = settings.showSim1 = settings.showSim2 = true;
        settings.signalMode = Prefs.SIGNAL_IN_RING;
        settings.simStackGap = gap;
        settings.ringZones = Prefs.decodeLayout(zone == 0 ? "9|||" : zone == 1 ? "|9||" : "||9|");
        return settings;
    }

    private static void stackedSimRows() {
        TrioState state = new TrioState();
        state.slotLevels = new int[] {4,1};
        state.mobileService = DuoPolicy.Radio.AVAILABLE;
        state.dataSlot = 0;
        for (int style : new int[] {Prefs.STYLE_RING, Prefs.STYLE_CAPSULE}) {
            for (int zone : new int[] {0,2}) {
                float previousPitch = -1;
                for (int gap : new int[] {0,50,150,300}) {
                    TrioSettings settings = pairedSettings(style,zone,gap);
                    try (Frame pair = new Frame(); Frame switched = new Frame(); Frame reference = new Frame()) {
                        TrioRenderer.drawState(pair,480,400,state,settings);
                        check(pair.circles.size()==8,"two physical SIM rows share one opening");
                        for(int i=0;i<4;i++)check(pair.circleColors.get(i)==state.foreground(),"SIM1 full strength stays on top");
                        check(pair.circleColors.get(4)==state.foreground()
                                && pair.circleColors.get(5)!=state.foreground(),"SIM2 has its own strength");
                        float pitch=pair.circles.get(4)[1]-pair.circles.get(0)[1];
                        check(pitch>previousPitch,"each larger gap increases actual row distance");
                        previousPitch=pitch;
                        check(Math.abs(pitch-2f*pair.circles.get(0)[2]*(1f+gap/100f))<0.05f,
                                "gap percentage changes spacing without shrinking dots");
                        state.dataSlot=1;
                        TrioRenderer.drawState(switched,480,400,state,settings);
                        state.dataSlot=0;
                        check(pair.circleColors.equals(switched.circleColors),"data-SIM switch does not exchange physical rows");
                        TrioSettings single=settings.copy();
                        single.ringZones=Prefs.decodeLayout(zone==0 ? "7|||" : "||8|");
                        TrioRenderer.drawState(reference,480,400,state,single);
                        check(reference.circles.size()==4,"independent signals retain four dots");
                        for(int i=0;i<4;i++) {
                            check(Math.abs(pair.circles.get(i)[0]-pair.circles.get(i+4)[0])<.01f,
                                    "rows share horizontal alignment");
                            check(Math.abs(pair.circles.get(i)[2]-pair.circles.get(i+4)[2])<.01f,
                                    "rows share bead diameter");
                        }
                        for(float[] circle:pair.circles)check(circle[0]-circle[2]>=-0.1f && circle[0]+circle[2]<=480.1f
                                && circle[1]-circle[2]>=-0.1f && circle[1]+circle[2]<=400.1f,"maximum spacing remains inside glyph view");
                    }
                }
                for(int[] levels:new int[][] {{4,-1},{-1,2},{-1,-1}}) {
                    state.slotLevels=levels;
                    try(Frame frame=new Frame()) {
                        TrioRenderer.drawState(frame,480,400,state,pairedSettings(style,zone,300));
                        check(frame.circles.size()==(levels[0]<0&&levels[1]<0?0:4),"absent SIM never creates an empty extra row");
                    }
                }
                state.slotLevels=new int[]{4,1};
                for(int enabled=0;enabled<3;enabled++) {
                    TrioSettings toggled=pairedSettings(style,zone,50);
                    toggled.showSim1=enabled==1;toggled.showSim2=enabled==2;
                    try(Frame frame=new Frame()) {
                        TrioRenderer.drawState(frame,480,400,state,toggled);
                        check(frame.circles.size()==(enabled==0?0:4),"paired placement respects each card's visibility switch");
                        check(TrioAppearance.of(toggled).foldsMobile()==(enabled!=0),"disabled paired cards hand native mobile icon back");
                    }
                }
                for(boolean charging:new boolean[]{false,true}) {
                    state.charging=charging;state.wifiPresent=true;state.wifiLevel=3;
                    try(Frame frame=new Frame()) {
                        TrioRenderer.drawState(frame,480,400,state,pairedSettings(style,zone,50));
                        check(frame.circles.size()==8,"Wi-Fi and charging do not silently remove the second SIM row");
                    }
                }
                state.charging=false;
                state.mobileService=DuoPolicy.Radio.REGISTERING;
                try(Frame frame=new Frame()) {
                    TrioRenderer.drawState(frame,480,400,state,pairedSettings(style,zone,50));
                    check(frame.circles.isEmpty(),"registering radio suppresses stale paired readings");
                }
                state.mobileService=DuoPolicy.Radio.AVAILABLE;
            }
        }
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

    private static void flexiblePairRows() {
        TrioState state=new TrioState();state.slotLevels=new int[]{4,2};
        state.mobileService=DuoPolicy.Radio.AVAILABLE;
        for(int style:new int[]{Prefs.STYLE_RING,Prefs.STYLE_CAPSULE})
        for(int zone:new int[]{0,1,2}) for(int shape:new int[]{0,1}) {
            TrioSettings settings=pairedSettings(style,zone,150);settings.pairShape=shape;
            settings.pairPitch=180;settings.pairBend=20;settings.pairX=30;settings.pairY=-25;
            try(Frame frame=new Frame()) {
                TrioRenderer.drawState(frame,480,400,state,settings);
                check(frame.circles.size()==8,"all three zones support both paired dot styles");
                float[] first=frame.circles.get(0), second=frame.circles.get(1);
                check(shape==1 ? Math.abs(first[1]-second[1])<.01f : Math.abs(first[1]-second[1])>.1f,
                        "horizontal stays flat and curved retains adjustable bend");
                for(float[] dot:frame.circles)check(dot[0]-dot[2]>=-.1f && dot[0]+dot[2]<=480.1f
                    && dot[1]-dot[2]>=-.1f && dot[1]+dot[2]<=400.1f,"offset and spacing fit actual view bounds");
            }
            state.mobileService=DuoPolicy.Radio.REGISTERING;
            try(Frame frame=new Frame()) {
                TrioRenderer.drawState(frame,480,400,state,settings);
                check(frame.circles.isEmpty(),"middle paired signals also suppress stale radio readings");
            }
            state.mobileService=DuoPolicy.Radio.AVAILABLE;
        }
    }

    private static void alignedSplitRows() {
        TrioState state=new TrioState();state.slotLevels=new int[]{4,2};
        state.mobileService=DuoPolicy.Radio.AVAILABLE;
        for(int zone:new int[]{0,2})for(int shape:new int[]{0,1})for(int mirror:new int[]{0,1})
        for(int cards:new int[]{1,2})for(int size:new int[]{50,100,200}) {
            TrioSettings settings=pairedSettings(Prefs.STYLE_RING,zone,150);
            settings.splitMode=1;settings.splitLink=0;settings.splitRightHeight=70;settings.splitRightY=12;
            settings.pairShape=shape;settings.pairMirror=mirror;settings.pairBend=20;settings.signalIconScale=size;
            state.slotLevels=cards==1?new int[]{4,-1}:new int[]{4,2};
            TrioAppearance a=TrioAppearance.of(settings);float[] transform=new float[3];
            TrioRenderer.designTransform(480,400,a,transform);
            try(Frame frame=new Frame()) {
                TrioRenderer.drawState(frame,480,400,state,settings);
                float top=Float.MAX_VALUE,bottom=-Float.MAX_VALUE;
                for(float[] dot:frame.circles){top=Math.min(top,dot[1]-dot[2]);bottom=Math.max(bottom,dot[1]+dot[2]);}
                float expected=(zone==0?TrioGeometry.sideTop(a):TrioGeometry.sideBottom(a))*transform[0]+transform[2];
                check(Math.abs((zone==0?top:bottom)-expected)<.05f,"information ink aligns with side ink for curved, flat and single SIM rows");
            }
        }
    }

    private static void trueEllipseRendering() {
        TrioSettings settings=TrioSettings.defaults();settings.splitMode=1;settings.splitLink=0;
        settings.splitLeftBend=80;settings.splitRightBend=15;
        settings.splitLeftTop=0;settings.splitLeftBottom=40;
        TrioState state=new TrioState();
        for(int level:new int[]{0,25,50,75,100}) {
            state.level=level;
            try(Frame frame=new Frame()) {
                TrioRenderer.drawState(frame,480,400,state,settings);
                check(frame.ellipseArcs.size()>=2,"both tracks use native smooth ellipse arcs");
                check(Math.abs(frame.ellipseArcs.get(0)[0]-160)<.001,"left arc retains configured horizontal axis");
                check(Math.abs(frame.ellipseArcs.get(1)[0]-30)<.001,"right arc has its independent ellipse axis");
                TrioGeometry.SplitRing shape=new TrioGeometry.SplitRing(TrioAppearance.of(settings));
                float actualLength=0;
                for(int side=0;side<Math.max(0,frame.ellipseArcs.size()-2);side++) {
                    float fraction=frame.ellipseArcs.get(side+2)[3]/frame.ellipseArcs.get(side)[3];
                    float n=fraction*TrioGeometry.SplitRing.STEPS;
                    int index=Math.min(TrioGeometry.SplitRing.STEPS-1,(int)n);
                    int first=side*(TrioGeometry.SplitRing.STEPS+1);
                    actualLength+=shape.distance[first+index]-shape.distance[first]
                        +(shape.distance[first+index+1]-shape.distance[first+index])*(n-index);
                }
                check(Math.abs(actualLength-shape.length*level/100f)<.01f,"battery percentage follows actual unequal ellipse arc lengths");
            }
        }
    }

    private static void centrePairDuringCharging() {
        TrioState state=new TrioState();state.level=70;state.slotLevels=new int[]{4,2};
        state.mobileService=DuoPolicy.Radio.AVAILABLE;state.wifiPresent=true;state.wifiLevel=3;
        for(int style:new int[]{Prefs.STYLE_RING,Prefs.STYLE_CAPSULE})for(boolean charging:new boolean[]{false,true}) {
            TrioSettings settings=TrioSettings.defaults();settings.trioStyle=style;
            settings.signalMode=Prefs.SIGNAL_OUT_RING;settings.showMobile=false;
            settings=TrioZones.withPiece(settings,1,TrioZones.Part.SIM_PAIR);
            settings.chargingShowsValue=true;state.charging=charging;
            settings=TrioSettings.fromBundle(settings.toBundle());
            try(Frame frame=new Frame()) {
                TrioRenderer.drawState(frame,480,400,state,settings);
                check(frame.circles.size()==8,"centre placement displays both SIMs after outside mode, during Wi-Fi and charging");
            }
        }
        TrioSettings settings=TrioSettings.defaults();settings.roleColors=true;
        settings=TrioZones.withPiece(settings,0,TrioZones.Part.BOLT);state.charging=true;
        for(int split:new int[]{0,1}) {
            settings.splitMode=split;
            settings.showBolt=true;
            int role=TrioRenderer.roleColor(TrioAppearance.of(settings),70,true,false,false,state.foreground());
            try(Frame shown=new Frame();Frame hidden=new Frame()) {
                TrioRenderer.drawState(shown,480,400,state,settings);
                settings.showBolt=false;
                TrioRenderer.drawState(hidden,480,400,state,settings);
                check(shown.pathColors.size()==hidden.pathColors.size()+1,"hidden mode removes exactly the charging bolt path");
                check(shown.arcColors.contains(role)&&hidden.arcColors.contains(role),"charging ring retains its role color with bolt hidden");
            }
        }
    }

    private static float[] designDot(float[] dot, float[] transform) {
        return new float[]{(dot[0]-transform[1])/transform[0],(dot[1]-transform[2])/transform[0],dot[2]/transform[0]};
    }
    private static void wholeSignalScale() {
        TrioState state=new TrioState();state.level=70;state.slotLevels=new int[]{4,2};
        state.mobileService=DuoPolicy.Radio.AVAILABLE;
        for(int style:new int[]{Prefs.STYLE_RING,Prefs.STYLE_CAPSULE})
        for(int zone:new int[]{0,1,2})for(int shape:new int[]{0,1}) {
            TrioSettings baseline=pairedSettings(style,zone,50);baseline.pairShape=shape;baseline.pairX=15;baseline.pairY=-5;
            for(int size:new int[]{50,150,200}) {
                TrioSettings resized=baseline.copy();resized.signalIconScale=size;
                float[] baseTransform=new float[3],sizeTransform=new float[3];
                TrioRenderer.designTransform(480,400,TrioAppearance.of(baseline),baseTransform);
                TrioRenderer.designTransform(480,400,TrioAppearance.of(resized),sizeTransform);
                try(Frame before=new Frame();Frame after=new Frame()) {
                    TrioRenderer.drawState(before,480,400,state,baseline);
                    TrioRenderer.drawState(after,480,400,state,resized);
                    check(before.circles.size()==8&&after.circles.size()==8,"scaling preserves both SIM identities");
                    float[] b0=designDot(before.circles.get(0),baseTransform),b1=designDot(before.circles.get(1),baseTransform);
                    float[] b4=designDot(before.circles.get(4),baseTransform);
                    float[] a0=designDot(after.circles.get(0),sizeTransform),a1=designDot(after.circles.get(1),sizeTransform);
                    float[] a4=designDot(after.circles.get(4),sizeTransform);
                    float[] b3=designDot(before.circles.get(3),baseTransform),a3=designDot(after.circles.get(3),sizeTransform);
                    check(Math.abs((b0[0]+b3[0])/2-(a0[0]+a3[0])/2)<.02,"position offset is not multiplied by overall icon size");
                    float factor=size/100f;
                    check(Math.abs(a0[2]-b0[2]*factor)<.02,"overall size scales dot diameter");
                    check(Math.abs((a1[0]-a0[0])-(b1[0]-b0[0])*factor)<.02,"overall size scales horizontal spacing");
                    check(Math.abs((a4[1]-a0[1])-(b4[1]-b0[1])*factor)<.02,"overall size scales SIM row spacing");
                    check(Math.abs((a1[1]-a0[1])-(b1[1]-b0[1])*factor)<.02,"overall size scales curved row geometry");
                    for(float[] dot:after.circles)check(dot[0]-dot[2]>=-.1f&&dot[0]+dot[2]<=480.1f
                        &&dot[1]-dot[2]>=-.1f&&dot[1]+dot[2]<=400.1f,"large signal groups fit measured bounds");
                }
            }
            TrioSettings thicker=baseline.copy();thicker.dotScale=150;
            float[] bTransform=new float[3],tTransform=new float[3];
            TrioRenderer.designTransform(480,400,TrioAppearance.of(baseline),bTransform);
            TrioRenderer.designTransform(480,400,TrioAppearance.of(thicker),tTransform);
            try(Frame before=new Frame();Frame after=new Frame()) {
                TrioRenderer.drawState(before,480,400,state,baseline);TrioRenderer.drawState(after,480,400,state,thicker);
                float[] b0=designDot(before.circles.get(0),bTransform),b1=designDot(before.circles.get(1),bTransform);
                float[] a0=designDot(after.circles.get(0),tTransform),a1=designDot(after.circles.get(1),tTransform);
                check(Math.abs(a0[2]-b0[2]*1.5f)<.02,"thickness controls radius separately");
                check(Math.abs((a1[0]-a0[0])-(b1[0]-b0[0]))<.02,"thickness does not scale pair dot spacing");
            }
        }
        for(int style:new int[]{Prefs.STYLE_RING,Prefs.STYLE_CAPSULE}) {
            TrioSettings baseline=TrioSettings.defaults();baseline.trioStyle=style;baseline.signalMode=Prefs.SIGNAL_IN_RING;
            baseline=TrioZones.withPiece(baseline,0,TrioZones.Part.SIM_1);
            TrioSettings resized=baseline.copy();resized.signalIconScale=200;
            float[] bt=new float[3],at=new float[3];TrioRenderer.designTransform(480,400,TrioAppearance.of(baseline),bt);
            TrioRenderer.designTransform(480,400,TrioAppearance.of(resized),at);
            try(Frame before=new Frame();Frame after=new Frame()) {
                TrioRenderer.drawState(before,480,400,state,baseline);TrioRenderer.drawState(after,480,400,state,resized);
                check(before.circles.size()==4&&after.circles.size()==4,"independently placed SIM rows also support overall scale");
                float[] b0=designDot(before.circles.get(0),bt),b1=designDot(before.circles.get(1),bt);
                float[] a0=designDot(after.circles.get(0),at),a1=designDot(after.circles.get(1),at);
                check(Math.abs((a1[0]-a0[0])-2*(b1[0]-b0[0]))<.02,"single-row spacing scales with its dots");
                check(Math.abs(a0[2]-2*b0[2])<.02,"single-row radius scales with whole icon");
            }
        }
    }
}
