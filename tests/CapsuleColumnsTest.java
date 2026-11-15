package io.github.yixing233.hyperduo;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Bundle;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.geom.Rectangle2D;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Arrays;

/** Production persistence, arbitration, hit regions and rendered ink for issue #17. */
public final class CapsuleColumnsTest {
    static int checks;
    static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
    static final class Frame extends Canvas {
        final Map<String, Rectangle2D> texts = new LinkedHashMap<>();
        Frame(Graphics2D g) { super(g); }
        @Override public void drawText(String text, float x, float y, Paint p) {
            Rect ink = new Rect();
            float left = x - (p.getTextAlign() == Paint.Align.CENTER ? p.measureText(text) / 2
                    : p.getTextAlign() == Paint.Align.RIGHT ? p.measureText(text) : 0);
            Rectangle2D bounds = null;
            float pen = left;
            for (int i = 0; i < text.length(); i++) {
                String glyph = text.substring(i, i + 1); p.getTextBounds(glyph, 0, 1, ink);
                Rectangle2D next = new Rectangle2D.Float(pen + ink.left, y + ink.top, ink.width(), ink.height());
                if (bounds == null) bounds = next; else bounds = bounds.createUnion(next);
                pen += p.measureText(glyph) + p.getLetterSpacing() * p.getTextSize();
            }
            texts.put(text, graphics().getTransform().createTransformedShape(bounds).getBounds2D());
            super.drawText(text, x, y, p);
        }
    }
    static TrioSettings settings(int columns, String layout) {
        TrioSettings s = TrioSettings.defaults(); s.enabled = true;
        s.trioStyle = Prefs.STYLE_CAPSULE; s.capsuleColumns = columns;
        s.clockSeconds = true; s.mobileTypeMode = Prefs.MOBILE_TYPE_IN_RING;
        s.ringZones = Prefs.decodeLayout(layout); return s;
    }
    static TrioState state() {
        TrioState state = new TrioState(); state.level = 100; state.mobileType = "5G";
        state.useTint = true; state.tintColor = 0xffeeeeee;
        state.wifiLevel = -1; state.mobileService = DuoPolicy.Radio.AVAILABLE;
        state.slotLevels = new int[]{4, 2}; state.sims = 2; return state;
    }
    static Frame render(TrioSettings s, BufferedImage image) {
        Frame frame = new Frame(image.createGraphics());
        TrioRenderer.drawState(frame, image.getWidth(), image.getHeight(), state(), s);
        frame.graphics().dispose(); return frame;
    }
    static BufferedImage image() { return new BufferedImage(480, 200, BufferedImage.TYPE_INT_ARGB); }

    public static void main(String[] args) throws Exception {
        TrioSettings old = TrioSettings.fromBundle(new Bundle());
        check(old.capsuleColumns == 2 && !old.clockSeconds, "upgrade keeps two cells and minutes");
        TrioSettings s = settings(3, "|3||6|5");
        check(s.equals(s.copy()) && s.equals(TrioSettings.fromBundle(s.toBundle())), "new settings survive copies and reload");
        TrioSettings patched = old.copy();
        check(patched.applyKeyFrom(s, Prefs.KEY_CLOCK_SECONDS) && patched.clockSeconds, "seconds patch");
        check(patched.applyKeyFrom(s, Prefs.KEY_CAPSULE_COLUMNS) && patched.capsuleColumns == 3, "column patch");
        check(!old.equals(patched), "new keys take part in change detection");
        check(Prefs.encodeLayout(Prefs.decodeLayout("1|3|9|6")).equals("1|3|9|6"), "old serialization remains stable");
        check(Prefs.encodeLayout(s.ringZones).equals("|3||6|5"), "middle is appended, old indexes stay stable");
        int[][] saved = TrioSettings.copyZones(s.ringZones);
        s.capsuleColumns = 2;
        check(!TrioAppearance.of(s).hasRingPart(Prefs.PART_TYPE), "hidden middle is not active content");
        check(TrioZones.boxes(TrioZones.Zone.MIDDLE, TrioAppearance.of(s)).length == 0, "hidden middle has no hit region");
        s.capsuleColumns = 3;
        check(Arrays.deepEquals(saved, s.ringZones), "switching modes preserves all priority lists");
        check(TrioAppearance.of(s).hasRingPart(Prefs.PART_TYPE), "middle returns when enabled");
        check(TrioZones.withPiece(s, TrioZones.Zone.MIDDLE.ordinal(), TrioZones.Part.CLOCK)
                .ringZones[TrioZones.Zone.MIDDLE.ordinal()][0] == Prefs.PART_CLOCK, "middle accepts clock");
        TrioState.sClockText = "19:41:30";
        TrioState.sMemoryPercent = 68;
        for (int length : new int[]{Prefs.MIN_CAPSULE_LENGTH, 0, Prefs.MAX_CAPSULE_LENGTH}) for (int size : new int[]{36, 96}) {
            s = settings(3, "|3||6|5"); s.capsuleLength = length; s.clockSize = s.valueSize = s.typeSize = size;
            Frame f = render(s, image());
            check(f.texts.keySet().containsAll(Arrays.asList("19:41:30", "100", "5G")), "three independently visible readings");
            Rectangle2D clock = f.texts.get("19:41:30"), middle = f.texts.get("5G"), value = f.texts.get("100");
            check(clock.getMaxX() < middle.getMinX() && middle.getMaxX() < value.getMinX(), "thirds never overlap");
            TrioAppearance a = TrioAppearance.of(s); float[] transform = new float[3];
            check(TrioRenderer.designTransform(480, 200, a, transform), "valid transform");
            for (TrioZones.Zone zone : TrioZones.zonesFor(a)) {
                float[] box = TrioZones.boxes(zone, a)[0];
                check(TrioZones.zoneAt(transform, a, (box[0] + box[2]) / 2 * transform[0] + transform[1],
                        (box[1] + box[3]) / 2 * transform[0] + transform[2]) == zone, "tap selects " + zone);
            }
            s.capsuleColumns = 2;
            f = render(s, image()); check(!f.texts.containsKey("5G"), "two columns suppress middle ink");
            clock = f.texts.get("19:41:30"); value = f.texts.get("100");
            check(clock.getMaxX() < value.getMinX(), "two-cell seconds fit beside battery, length=" + length + " size=" + size + " clock=" + clock + " value=" + value);
        }
        s = settings(3, "|3||5|6");
        check(TrioRenderer.drawsClock(state(), TrioAppearance.of(s)), "middle clock owns native time");
        s.capsuleColumns = 2;
        check(!TrioRenderer.drawsClock(state(), TrioAppearance.of(s)), "hidden clock releases native time");
        s = settings(2, "||||6");
        check(!TrioRenderer.drawsClock(state(), TrioAppearance.of(s)), "hidden-only layout cannot restore legacy clock");
        for (String time : new String[]{"19:41:30", "11:59:59 PM"}) {
            TrioState.sClockText = time;
            for (String layout : new String[]{"|6||3", "|10||6", "|6||10", "|5||6", "|6||5"}) {
                Frame f = render(settings(2, layout), image());
                check(f.texts.size() == 2, "both body readings remain visible");
                Rectangle2D[] readings = f.texts.values().toArray(new Rectangle2D[0]);
                check(!readings[0].intersects(readings[1]), "seconds fit with battery, memory or network on either side");
            }
        }
        TrioState.sClockText = "19:41:30";
        s = settings(2, "|3||6|1");
        check(!TrioAppearance.of(s).ownsWifi(), "hidden Wi-Fi releases native icon");
        s.capsuleColumns = 3;
        check(TrioAppearance.of(s).ownsWifi(), "visible Wi-Fi owns native icon again");
        s = settings(2, "|3||6|5");
        check(!TrioAppearance.of(s).ownsType(), "hidden network type releases native icon");
        s = settings(2, "|5||6|3");
        check(TrioAppearance.of(s).handsBackPercentage(), "hidden percentage releases native reading");
        if (args.length > 0) preview(args[0]);
        System.out.println("Capsule columns, migration, editing and clock ink: " + checks + " checks passed");
    }

    static void preview(String output) throws Exception {
        BufferedImage board = new BufferedImage(1000, 550, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = board.createGraphics(); g.setColor(new java.awt.Color(0x19191c)); g.fillRect(0, 0, 1000, 550);
        String[] titles = {"Two cells / minutes (existing)", "Two cells / seconds", "Three cells / clock + network + battery", "Three cells / longer capsule"};
        for (int i = 0; i < 4; i++) {
            TrioSettings s = settings(i < 2 ? 2 : 3, "|3||6|5"); s.clockSeconds = i != 0;
            if (i == 3) s.capsuleLength = Prefs.MAX_CAPSULE_LENGTH;
            TrioState.sClockText = i == 0 ? "19:41" : "19:41:30";
            BufferedImage icon = image(); render(s, icon);
            int x = i % 2 * 500, y = i / 2 * 260;
            g.drawImage(icon, x + 10, y + 20, null); g.setColor(java.awt.Color.WHITE);
            g.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 18)); g.drawString(titles[i], x + 22, y + 240);
        }
        g.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 13));
        g.drawString("Production renderer; simulated readings, not a phone screenshot.", 22, 537);g.dispose();
        javax.imageio.ImageIO.write(board, "png", new java.io.File(output));
    }
}
