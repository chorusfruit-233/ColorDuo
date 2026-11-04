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
        final List<Path> pathObjects = new ArrayList<>();
        final List<java.awt.geom.Rectangle2D> pathBounds = new ArrayList<>();
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
            pathObjects.add(path);
            RectF ink=new RectF();path.computeBounds(ink,true);
            pathBounds.add(graphics.getTransform().createTransformedShape(new java.awt.geom.Rectangle2D.Float(
                    ink.left,ink.top,ink.width(),ink.height())).getBounds2D());
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
        adaptiveRingOpenings();
        physicalSizing();
        activeNetworkTypePolicy();
        automaticWifiUsesTypePosition();
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
        splitDefaultTextClearance();
        capsulePairClockClearance();
        mirroredPairRows();
        System.out.println("Capsule fill, live clock handoff and reachable percentage sizing passed: " + checks + " checks");
    }


    private static float renderedOpening(int part,int valueSize,int valueWeight,int boltScale) {
        TrioSettings settings=TrioSettings.defaults();
        settings.trioStyle=Prefs.STYLE_RING;settings.splitMode=0;
        settings.ringZones=Prefs.decodeLayout(part+"|||");
        settings.showValue=settings.showBolt=true;
        settings.chargingShowsValue=false;
        settings.valueSize=valueSize;settings.valueWeight=valueWeight;
        settings.boltScale=boltScale;
        TrioState state=new TrioState();state.level=64;
        state.charging=part==Prefs.PART_BOLT;
        try(Frame frame=new Frame()) {
            TrioRenderer.drawState(frame,480,400,state,settings);
            check(frame.arcs.size()>=2,"an occupied top opening draws both track segments");
            return 360f-frame.arcs.get(0)-frame.arcs.get(1);
        }
    }

    private static void adaptiveRingOpenings() {
        String clock=TrioState.sClockText;
        try {
            TrioState.sClockText="19:41";
            float digits=renderedOpening(Prefs.PART_VALUE,36,700,100);
            float time=renderedOpening(Prefs.PART_CLOCK,36,700,100);
            check(digits<time-10f,"issue #10: two battery digits must not reuse the clock's wide opening: digits="+digits+" clock="+time);
            check(renderedOpening(Prefs.PART_VALUE,18,700,100)<digits,"the opening follows a smaller configured percentage font");
            check(renderedOpening(Prefs.PART_VALUE,40,700,100)>=digits,"the opening makes room for a larger percentage font");
            check(renderedOpening(Prefs.PART_VALUE,36,900,100)>=renderedOpening(Prefs.PART_VALUE,36,100,100),
                    "a heavier percentage face cannot leave a narrower opening");
            float bolt=renderedOpening(Prefs.PART_BOLT,36,700,100);
            check(bolt<digits && bolt<time,"the bolt uses its own ink width instead of a text notch");
            check(renderedOpening(Prefs.PART_BOLT,36,700,60)<bolt
                    && renderedOpening(Prefs.PART_BOLT,36,700,160)>bolt,"bolt scale immediately changes the arc endpoints");
            for(int zone:new int[]{0,2}) for(int part:new int[]{Prefs.PART_VALUE,Prefs.PART_BOLT,Prefs.PART_CLOCK,Prefs.PART_TYPE})
            for(int stroke:new int[]{4,14,16}) for(int weight:new int[]{100,700,900}) for(int level:new int[]{8,64,100}) {
                TrioSettings settings=TrioSettings.defaults();settings.splitMode=0;settings.ringStroke=stroke;
                settings.ringZones=Prefs.decodeLayout("|||");settings.ringZones[zone]=new int[]{part};
                settings.showValue=settings.showBolt=true;settings.chargingShowsValue=false;
                settings.mobileTypeMode=Prefs.MOBILE_TYPE_IN_RING;settings.typeHideOnWifi=false;
                settings.valueWeight=settings.clockWeight=settings.typeWeight=weight;
                settings.valueSize=settings.clockSize=settings.typeSize=weight==100?18:weight==700?36:60;
                settings.typeSuffixScale=60;settings.boltScale=weight==100?60:weight==700?100:160;
                TrioState state=new TrioState();state.level=level;state.mobileType="5GA";
                state.charging=part==Prefs.PART_BOLT;
                TrioAppearance a=TrioAppearance.of(settings);
                float[] transform=new float[3];TrioRenderer.designTransform(480,400,a,transform);
                try(Frame frame=new Frame()) {
                    TrioRenderer.drawState(frame,480,400,state,settings);
                    int trackSegments=zone==0?2:1;
                    float track=0,lit=0;
                    for(int n=0;n<frame.arcs.size();n++) { if(n<trackSegments)track+=frame.arcs.get(n);else lit+=frame.arcs.get(n); }
                    check(track>0 && Math.abs(lit/track-level/100f)<.0001,"percentage fill is measured over the newly visible arc length");
                    java.awt.geom.Rectangle2D ink=null;
                    for(var box:part==Prefs.PART_BOLT?frame.pathBounds:frame.textBounds)
                        ink=ink==null?box:ink.createUnion(box);
                    check(ink!=null,"the selected opening component actually paints ink");
                    float first=frame.ellipseArcs.get(0)[2];
                    float end=first+frame.ellipseArcs.get(0)[3];
                    double closest=Double.POSITIVE_INFINITY;
                    for(float angle:zone==0?new float[]{end,frame.ellipseArcs.get(1)[2]}:new float[]{first,end}) {
                        double x=(TrioGeometry.B_CX+TrioGeometry.B_R*Math.cos(Math.toRadians(angle)))*transform[0]+transform[1];
                        double y=(TrioGeometry.B_CY+TrioGeometry.B_R*Math.sin(Math.toRadians(angle)))*transform[0]+transform[2];
                        double dx=Math.max(0,Math.max(ink.getMinX()-x,x-ink.getMaxX()));
                        double dy=Math.max(0,Math.max(ink.getMinY()-y,y-ink.getMaxY()));
                        double clearance=Math.hypot(dx,dy)/transform[0]-stroke/2f;
                        check(clearance>=1.9,"arc caps clear actual weighted text/path ink: zone="+zone+" part="+part+" stroke="+stroke+" clearance="+clearance);
                        closest=Math.min(closest,clearance);
                    }
                    if(part==Prefs.PART_VALUE && weight==700 && level==64)
                        check(closest<4,"ordinary percentage digits keep a compact visible clearance");
                    float top=zone==0?360f-track:0f,bottom=zone==2?360f-track:0f;
                    float angle=zone==0?270f+top/2f+2f:90f+bottom/2f+2f;
                    float x=(TrioGeometry.B_CX+TrioGeometry.B_R*(float)Math.cos(Math.toRadians(angle)))*transform[0]+transform[1];
                    float y=(TrioGeometry.B_CY+TrioGeometry.B_R*(float)Math.sin(Math.toRadians(angle)))*transform[0]+transform[2];
                    check(TrioZones.batteryRingAt(transform,a,top,bottom,x,y),"preview hit testing follows the visible arc near its measured endpoint");
                    x=TrioGeometry.B_CX*transform[0]+transform[1];
                    y=(TrioGeometry.B_CY+(zone==0?-TrioGeometry.B_R:TrioGeometry.B_R))*transform[0]+transform[2];
                    check(!TrioZones.batteryRingAt(transform,a,top,bottom,x,y),"the component's open centre remains editable instead of selecting the battery arc");
                }
            }
            TrioSettings signal=TrioSettings.defaults();signal.ringZones=Prefs.decodeLayout("3||7|");
            signal.showSim1=signal.showMobile=true;signal.signalMode=Prefs.SIGNAL_IN_RING;
            TrioState state=new TrioState();state.level=64;state.sims=1;state.slotLevels=new int[]{4,-1};state.mobileLevel=4;
            try(Frame frame=new Frame()) {
                TrioRenderer.drawState(frame,480,400,state,signal);
                check(Math.abs((frame.ellipseArcs.get(0)[2]-90f)*2f-96f)<.001,"the opposite signal notch keeps its established bead geometry");
                check(frame.circles.size()==4,"measuring a text opening leaves all four signal beads visible");
            }
        } finally { TrioState.sClockText=clock; }
    }

    private static void splitDefaultTextClearance() {
        TrioSettings settings=TrioSettings.defaults();settings.splitMode=1;
        settings.ringZones=Prefs.decodeLayout("3|||");
        for(int value:new int[]{8,84,100}) {
            TrioState state=new TrioState();state.level=value;
            TrioAppearance appearance=TrioAppearance.of(settings);
            float[] transform=new float[3];TrioRenderer.designTransform(480,400,appearance,transform);
            TrioGeometry.EllipseSide left=new TrioGeometry.EllipseSide(appearance,0);
            TrioGeometry.EllipseSide right=new TrioGeometry.EllipseSide(appearance,1);
            try(Frame frame=new Frame()) {
                TrioRenderer.drawState(frame,480,400,state,settings);
                var ink=frame.textBounds.get(0);
                float start=(left.x(1)+appearance.ringStroke/2f+1)*transform[0]+transform[1];
                float end=(right.x(0)-appearance.ringStroke/2f-1)*transform[0]+transform[1];
                check(ink.getMinX()>=start-.1 && ink.getMaxX()<=end+.1,
                        "default split opening clears battery digits: "+value+" ink="+ink+" gap="+start+":"+end+" font="+frame.textSize);
                check(Math.abs(frame.textSize-Prefs.DEF_VALUE_SIZE)<.05,
                        "default split opening fits the actual default battery font size: value="+value+" actual="+frame.textSize+" ink="+ink+" opening="+TrioGeometry.splitOpeningWidth(appearance,0));
            }
        }
        for(int link:new int[]{0,1})for(int distance:new int[]{10,24})for(int opening:new int[]{16,48}) {
            settings.splitLink=link;settings.splitLeftDistance=distance;settings.splitLeftTop=opening;
            TrioState state=new TrioState();state.level=100;
            TrioAppearance appearance=TrioAppearance.of(settings);float[] transform=new float[3];
            TrioRenderer.designTransform(480,400,appearance,transform);
            var left=new TrioGeometry.EllipseSide(appearance,0);var right=new TrioGeometry.EllipseSide(appearance,1);
            try(Frame frame=new Frame()) {
                TrioRenderer.drawState(frame,480,400,state,settings);
                var ink=frame.textBounds.get(0);
                float from=(left.x(1)+appearance.ringStroke/2f+1)*transform[0]+transform[1];
                float to=(right.x(0)-appearance.ringStroke/2f-1)*transform[0]+transform[1];
                check(ink.getMinX()>=from-.1 && ink.getMaxX()<=to+.1,
                        "narrow split opening fits actual centred glyph overhang: d="+distance+" o="+opening+" ink="+ink+" gap="+from+":"+to+" font="+frame.textSize);
            }
        }
    }

    private static void capsulePairClockClearance() {
        TrioState state=new TrioState();state.level=84;state.slotLevels=new int[]{4,2};
        state.sims=2;state.mobileService=DuoPolicy.Radio.AVAILABLE;TrioState.sClockText="09:41";
        for(int length:new int[]{-30,0,60})for(int size:new int[]{50,100,200}) {
            TrioSettings settings=pairedSettings(Prefs.STYLE_CAPSULE,1,50);
            settings.capsuleLength=length;settings.signalIconScale=size;
            settings.ringZones[TrioZones.Zone.LEFT.ordinal()]=new int[]{Prefs.PART_CLOCK};
            try(Frame frame=new Frame()) {
                TrioRenderer.drawState(frame,480,400,state,settings);
                check(frame.circles.size()==8,"capsule centre keeps both rows beside the clock");
                float pairLeft=Float.MAX_VALUE;
                for(float[] dot:frame.circles)pairLeft=Math.min(pairLeft,dot[0]-dot[2]);
                var clock=frame.textBounds.get(0);
                check(clock.getMaxX()+.1<pairLeft,
                        "capsule centre pair reserves the clock's real ink: length="+length+", size="+size);
            }
        }
    }


    private static void mirroredPairRows() {
        TrioState state=new TrioState();state.level=84;state.mobileService=DuoPolicy.Radio.AVAILABLE;
        for(int style:new int[]{Prefs.STYLE_RING,Prefs.STYLE_CAPSULE})
        for(int zone:new int[]{0,1,2})for(int[] levels:new int[][]{{4,-1},{-1,2},{4,2}})
        for(int reverse:new int[]{0,1}) {
            state.slotLevels=levels;state.sims=levels[0]>=0&&levels[1]>=0?2:1;
            TrioSettings off=pairedSettings(style,zone,50);off.pairBend=30;off.pairReverse=reverse;
            off.splitMode=style==Prefs.STYLE_RING?1:0;
            TrioSettings on=off.copy();on.pairMirror=1;
            float[] a=new float[3],b=new float[3];
            TrioRenderer.designTransform(480,400,TrioAppearance.of(off),a);
            TrioRenderer.designTransform(480,400,TrioAppearance.of(on),b);
            try(Frame before=new Frame();Frame after=new Frame()) {
                TrioRenderer.drawState(before,480,400,state,off);
                TrioRenderer.drawState(after,480,400,state,on);
                check(before.circles.size()==state.sims*4&&after.circles.size()==state.sims*4,
                        "mirroring never creates a missing SIM row");
                check(before.circleColors.equals(after.circleColors),"mirroring retains each physical SIM's own level");
                for(int row=0;row<state.sims;row++) {
                    float[] p=designDot(before.circles.get(row*4),a),q=designDot(before.circles.get(row*4+1),a);
                    float[] r=designDot(after.circles.get(row*4),b),t=designDot(after.circles.get(row*4+1),b);
                    check(q[1]-p[1]>0&&Math.abs((q[1]-p[1])+(t[1]-r[1]))<.05,
                            "every visible row flips its curvature, including a lone SIM1");
                    check(Math.abs((q[0]-p[0])-(t[0]-r[0]))<.05&&Math.abs(p[2]-r[2])<.05,
                            "mirror changes neither dot pitch nor diameter");
                }
                if(state.sims==2) {
                    float rowPitch=designDot(after.circles.get(4),b)[1]-designDot(after.circles.get(0),b)[1];
                    check((reverse==0?rowPitch:-rowPitch)>0,"mirror keeps the selected SIM order");
                    for(int dot=0;dot<4;dot++) {
                        float[] upper=designDot(after.circles.get(dot),b),lower=designDot(after.circles.get(dot+4),b);
                        check(Math.abs((lower[1]-upper[1])-rowPitch)<.05,"mirrored rows remain parallel rather than crossing");
                        check(Math.abs(rowPitch)+.05>=upper[2]+lower[2],"rows leave room for their actual bead diameter");
                    }
                }
            }
        }
        state.slotLevels=new int[]{4,2};state.sims=2;
        TrioSettings thick=pairedSettings(Prefs.STYLE_RING,1,0);thick.dotScale=200;thick.pairMirror=1;
        float[] transform=new float[3];TrioRenderer.designTransform(480,400,TrioAppearance.of(thick),transform);
        try(Frame frame=new Frame()) {
            TrioRenderer.drawState(frame,480,400,state,thick);
            float[] one=designDot(frame.circles.get(1),transform),two=designDot(frame.circles.get(5),transform);
            check(Math.abs(two[1]-one[1])+.05>=one[2]+two[2],"zero requested gap still prevents thick rows overlapping");
        }
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
        settings.pausedGrayAll = false;
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

    private static void activeNetworkTypePolicy() {
        for (int style : new int[]{Prefs.STYLE_RING,Prefs.STYLE_CAPSULE}) {
            for (boolean ownsWifi : new boolean[]{false,true}) {
                TrioSettings settings=TrioSettings.defaults();settings.trioStyle=style;
                settings.typeHideOnWifi=true;settings.mobileTypeMode=Prefs.MOBILE_TYPE_IN_RING;
                settings.showWifi=ownsWifi;settings.chargingShowsValue=false;
                settings.ringZones=Prefs.decodeLayout("1|5||");
                TrioState state=new TrioState();state.level=70;state.mobileType="5G";
                state.mobileService=DuoPolicy.Radio.AVAILABLE;
                for(boolean wifiInUse:new boolean[]{true,false,true,false}) {
                    state.wifiPresent=wifiInUse;state.wifiLevel=3;
                    try(Frame frame=new Frame()) {
                        TrioRenderer.drawState(frame,480,400,state,settings);
                        check(String.join("",frame.texts).contains("5G")==!wifiInUse,
                                "active route controls network type even in an otherwise empty cell");
                    }
                    TrioAppearance a=TrioAppearance.of(settings);
                    check(a.visibleMobileType("5G",wifiInUse).equals(wifiInUse?"":"5G"),
                            "outer and inner labels share the same suppression policy");
                }
                settings.typeHideOnWifi=false;
                check(TrioAppearance.of(settings).visibleMobileType("5G",true).equals("5G"),
                        "disabling route following preserves independent mobile type display");
            }
        }
    }

    private static void automaticWifiUsesTypePosition() throws Exception {
        java.lang.reflect.Field field=TrioRenderer.class.getDeclaredField("WIFI_DOT");
        field.setAccessible(true);Path wifiDot=(Path)field.get(null);
        for (int style : new int[]{Prefs.STYLE_RING,Prefs.STYLE_CAPSULE}) {
            TrioSettings settings=TrioSettings.defaults();settings.trioStyle=style;
            settings.mobileTypeMode=Prefs.MOBILE_TYPE_IN_RING;settings.typeHideOnWifi=true;
            settings.showWifi=false;settings.chargingShowsValue=false;
            settings.ringZones=Prefs.decodeLayout("|5||");
            int[][] saved=TrioSettings.copyZones(settings.ringZones);
            TrioAppearance a=TrioAppearance.of(settings);
            check(a.ownsWifi(),"automatic inner TYPE takes native Wi-Fi ownership without manual Wi-Fi placement");
            for(boolean wifi:new boolean[]{true,false,true,false}) {
                int[] parts=style==Prefs.STYLE_RING?a.ring(70,false,wifi?3:-1,"5G",2,wifi).parts:
                        a.capsule(70,false,wifi?3:-1,"5G",2,wifi).parts;
                check(parts[TrioZones.Zone.CENTRE.ordinal()]==(wifi?Prefs.PART_WIFI:Prefs.PART_TYPE),
                        "Wi-Fi replaces TYPE in exactly the same cell and restores on mobile data");
                TrioState state=new TrioState();state.level=70;state.mobileType="5G";
                state.wifiPresent=wifi;state.wifiLevel=3;state.mobileService=DuoPolicy.Radio.AVAILABLE;
                try(Frame frame=new Frame()) {
                    TrioRenderer.drawState(frame,480,400,state,settings);
                    check(String.join("",frame.texts).contains("5G")==!wifi,"actual pixels follow the selected route");
                    check(frame.pathObjects.contains(wifiDot)==wifi,"the module itself paints the Wi-Fi symbol at the automatic slot");
                }
            }
            check(java.util.Arrays.deepEquals(saved,settings.ringZones)&&!settings.showWifi,
                    "route changes never mutate persisted layout or manual Wi-Fi preference");
            settings.showWifi=true;settings.ringZones=Prefs.decodeLayout("1|5||");a=TrioAppearance.of(settings);
            int[] parts=style==Prefs.STYLE_RING?a.ring(70,false,3,"5G",2,true).parts:
                    a.capsule(70,false,3,"5G",2,true).parts;
            check(parts[0]!=Prefs.PART_WIFI&&parts[1]==Prefs.PART_WIFI,"explicit Wi-Fi elsewhere cannot duplicate the automatic icon");
            settings.showWifi=false;settings.typeHideOnWifi=false;
            check(!TrioAppearance.of(settings).ownsWifi(),"disabling auto mode returns native Wi-Fi ownership");
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
                "text fitting retains its maximum opening budget");
        check(Math.abs(360f - topText.sweep() - 96f) < 0.01f,
                "signal beads keep their original opening and spacing");
        settings.ringZones = Prefs.decodeLayout("7|6|3|");
        TrioAppearance.Ring bottomText = TrioAppearance.of(settings).ring(88, false, -1, "5G", 2, false);
        check(Math.abs(360f - bottomText.sweep() - 128f) < 0.01f,
                "top and bottom text share the same maximum fitting budget");
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
    private static void physicalSizing() {
        for(int style:new int[]{Prefs.STYLE_RING,Prefs.STYLE_CAPSULE})
        for(float density:new float[]{1.5f,2f,3f,4f}) {
            TrioSettings s=TrioSettings.defaults();s.enabled=true;s.trioStyle=style;
            s=TrioZones.withPiece(s,1,TrioZones.Part.VALUE);
            TrioState state=new TrioState();state.level=84;state.useTint=true;state.tintColor=0xffffffff;
            double initial=0, initialInk=0;
            for(int percent:new int[]{100,150,200,300}) {
                s.glyphScale=percent;
                TrioSizing.Metrics m=TrioSizing.measure(TrioAppearance.of(s),density,2000,3000);
                check(!m.limited,"unbounded physical scale remains available");
                if(percent==100) initial=m.scale;
                check(Math.abs(m.scale/initial-percent/100.0)<.001,"overall scaling is applied exactly once");
                try(Frame frame=new Frame(new BufferedImage(m.width,m.height,BufferedImage.TYPE_INT_ARGB).createGraphics())) {
                    TrioRenderer.drawSizedState(frame,m.width,m.height,state,s,m.scale,false);
                    double height=frame.textBounds.get(0).getHeight();
                    if(percent==100) initialInk=height;
                    check(Math.abs(height/initialInk-percent/100.0)<.01,
                            "actual painted digits enlarge by the requested percentage without double scaling");
                    check(frame.texts.contains("84"),"physical-size renderer paints the configured percentage");
                    for(java.awt.geom.Rectangle2D ink:frame.textBounds)
                        check(ink.getMinX()>=-1 && ink.getMinY()>=-1 && ink.getMaxX()<=m.width+1 && ink.getMaxY()<=m.height+1,
                                "measured physical host contains the actual text");
                }
            }
            s.glyphScale=150;
            s=TrioZones.withPiece(s,2,TrioZones.Part.SIM_PAIR);
            TrioSizing.Metrics before=TrioSizing.measure(TrioAppearance.of(s),density,2000,3000);
            s.signalIconScale=200;s.simStackGap=300;s.pairBend=30;
            TrioSizing.Metrics after=TrioSizing.measure(TrioAppearance.of(s),density,2000,3000);
            check(Math.abs(before.scale-after.scale)<.0001,"larger signal does not shrink battery text when space exists");
            check(after.height>=before.height && after.width>=before.width,"signal growth reserves additional host space");
            s.glyphScale=300;
            int capacity=Math.round(24*density);
            TrioSizing.Metrics bounded=TrioSizing.measure(TrioAppearance.of(s),density,capacity,2000);
            check(bounded.limited && bounded.height<=capacity,"surface-height limit is explicit and respected");
            TrioSizing.Bounds bounds=TrioSizing.bounds(TrioAppearance.of(s));
            check(bounds.height*bounded.scale<=capacity+.01,"limited scale fits the actual full ink bounds");
            float[] granted=new float[3];
            check(TrioSizing.transform(18,12,TrioAppearance.of(s),bounded.scale,granted),
                    "a temporarily smaller vendor drawable still has a transform");
            check(bounds.width*granted[0]<=18.01 && bounds.height*granted[0]<=12.01,
                    "the final drawable bounds cannot crop a larger requested scale");
            TrioSizing.Metrics narrow=TrioSizing.measure(TrioAppearance.of(s),density,2000,30);
            check(narrow.limited && narrow.width<=30,"horizontal space is bounded too");
            s.glyphScale=300;
            check(TrioSettings.fromBundle(s.toBundle()).glyphScale==300,"300 percent survives framework transport");
        }
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
