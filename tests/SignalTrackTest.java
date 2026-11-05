package io.github.yixing233.hyperduo;

import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Bundle;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Upgrade persistence, live transport, and production signal drawing paths. */
public final class SignalTrackTest {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static final class Memory implements SharedPreferences {
        final Map<String, Object> values = new HashMap<>();
        int commits;
        public boolean contains(String key) { return values.containsKey(key); }
        public boolean getBoolean(String key, boolean fallback) { return (Boolean) values.getOrDefault(key, fallback); }
        public int getInt(String key, int fallback) { return (Integer) values.getOrDefault(key, fallback); }
        public String getString(String key, String fallback) { return (String) values.getOrDefault(key, fallback); }
        public Editor edit() { return new Editor() {
            final Map<String, Object> pending = new HashMap<>();
            public Editor putBoolean(String key, boolean value) { pending.put(key, value); return this; }
            public Editor putInt(String key, int value) { pending.put(key, value); return this; }
            public Editor putString(String key, String value) { pending.put(key, value); return this; }
            public boolean commit() { commits++; values.putAll(pending); return true; }
        }; }
    }

    private static void upgradeAndTransport() {
        Memory old = new Memory();
        old.values.put(Prefs.KEY_TRACK_ALPHA, 53);
        check(TrioSettings.from(old).signalTrackAlpha == 53, "old remote preferences retain opacity before app opens");
        check(TrioSettings.migrateSignalTrackAlpha(old), "upgrade persists independent value");
        old.values.put(Prefs.KEY_TRACK_ALPHA, 255);
        check(TrioSettings.migrateSignalTrackAlpha(old) && old.commits == 1, "migration only runs once");
        TrioSettings upgraded = TrioSettings.from(old);
        check(upgraded.trackAlpha == 255 && upgraded.signalTrackAlpha == 53,
                "battery edits after upgrade cannot change signal opacity");
        for (int alpha : new int[] {0, 56, 180, 255}) {
            old.values.put(Prefs.KEY_SIGNAL_TRACK_ALPHA, alpha);
            check(TrioSettings.migrateSignalTrackAlpha(old) && old.commits == 1,
                    "explicit values, including zero, survive another app launch");
            for (int style : new int[] {Prefs.STYLE_RING, Prefs.STYLE_CAPSULE}) {
                TrioSettings settings = TrioSettings.from(old, 1f, style);
                check(settings.signalTrackAlpha == alpha && settings.trackAlpha == 255, "independent persisted values");
                check(settings.copy().equals(settings), "copy retains new field");
                check(TrioSettings.fromBundle(settings.toBundle()).equals(settings), "full broadcast retains new field");
                TrioSettings changed = settings.copy();
                changed.signalTrackAlpha = alpha == 0 ? 1 : 0;
                check(!changed.equals(settings), "signal-only edit invalidates cached appearance");
                Memory remote = new Memory();
                SharedPreferences.Editor editor = remote.edit();
                settings.writeTo(editor); editor.commit();
                TrioSettings restarted = TrioSettings.from(remote, 1f, style);
                check(restarted.signalTrackAlpha == alpha && restarted.trackAlpha == 255,
                        "framework reconnect and SystemUI restart retain both values");
                check(TrioAppearance.of(settings).signalTrackAlpha == alpha, "appearance consumes independent value");
            }
        }
        TrioSettings base = upgraded.copy();
        Bundle delta = new Bundle(); delta.putInt(Prefs.KEY_TRACK_ALPHA, 11);
        TrioSettings merged = TrioSettings.fromBundle(delta, base);
        check(merged.trackAlpha == 11 && merged.signalTrackAlpha == 53, "battery-only broadcast leaves signal unchanged");
        delta = new Bundle(); delta.putInt(Prefs.KEY_SIGNAL_TRACK_ALPHA, 180);
        merged = TrioSettings.fromBundle(delta, base);
        check(merged.trackAlpha == 255 && merged.signalTrackAlpha == 180, "signal-only broadcast leaves battery unchanged");
        check(base.applyKeyFrom(merged, Prefs.KEY_SIGNAL_TRACK_ALPHA) && base.signalTrackAlpha == 180
                && base.trackAlpha == 255, "remote callback applies only its own key");
        Bundle legacy = new Bundle(); legacy.putInt(Prefs.KEY_TRACK_ALPHA, 53);
        check(TrioSettings.fromBundle(legacy).signalTrackAlpha == 53, "old full bundle preserves appearance");
        for (int invalid : new int[] {-20, 400}) {
            int expected = invalid < 0 ? 0 : 255;
            Memory input = new Memory(); input.values.put(Prefs.KEY_SIGNAL_TRACK_ALPHA, invalid);
            check(TrioSettings.from(input).signalTrackAlpha == expected, "preferences clamp opacity");
            Bundle bundle = new Bundle(); bundle.putInt(Prefs.KEY_SIGNAL_TRACK_ALPHA, invalid);
            check(TrioSettings.fromBundle(bundle).signalTrackAlpha == expected, "broadcasts clamp opacity");
            Memory inherited = new Memory(); inherited.values.put(Prefs.KEY_TRACK_ALPHA, invalid);
            check(TrioSettings.migrateSignalTrackAlpha(inherited)
                    && inherited.getInt(Prefs.KEY_SIGNAL_TRACK_ALPHA, -1) == expected, "migration clamps old opacity");
        }
        Memory fresh = new Memory();
        check(TrioSettings.migrateSignalTrackAlpha(fresh)
                && TrioSettings.from(fresh).signalTrackAlpha == Prefs.DEF_SIGNAL_TRACK_ALPHA, "new installation default");
    }

    private record Dot(float x, float y, float radius) {}
    private static final class Frame extends Canvas implements AutoCloseable {
        final Graphics2D graphics;
        final List<Dot> dots = new ArrayList<>();
        final List<Integer> signal = new ArrayList<>();
        final List<Integer> battery = new ArrayList<>();
        Frame() { this(new BufferedImage(480, 400, BufferedImage.TYPE_INT_ARGB).createGraphics()); }
        private Frame(Graphics2D graphics) { super(graphics); this.graphics = graphics; }
        @Override public void drawCircle(float x, float y, float radius, Paint paint) {
            dots.add(new Dot(x, y, radius)); signal.add(paint.getColor());
            super.drawCircle(x, y, radius, paint);
        }
        @Override public void drawRoundRect(RectF rect, float rx, float ry, Paint paint) {
            signal.add(paint.getColor()); super.drawRoundRect(rect, rx, ry, paint);
        }
        @Override public void drawArc(RectF oval, float start, float sweep, boolean center, Paint paint) {
            battery.add(paint.getColor()); super.drawArc(oval, start, sweep, center, paint);
        }
        @Override public void drawPath(Path path, Paint paint) {
            battery.add(paint.getColor()); super.drawPath(path, paint);
        }
        public void close() { graphics.dispose(); }
    }

    private static int inactive(int foreground, int alpha) { return (foreground & 0xffffff) | (alpha << 24); }

    private static void ringAndCapsule() {
        for (int foreground : new int[] {0xff000000, 0xffffffff}) {
            TrioState state = new TrioState();
            state.level = 64; state.wifiPresent = false;
            state.mobileLevel = 2; state.slotLevels = new int[] {2, 1}; state.dataSlot = 0;
            state.mobileService = DuoPolicy.Radio.AVAILABLE;
            state.useTint = true; state.tintColor = foreground;
            for (int arrangement = 0; arrangement < 3; arrangement++) {
                for (int part : new int[] {Prefs.PART_MOBILE, Prefs.PART_SIM_1, Prefs.PART_SIM_2, Prefs.PART_SIM_PAIR}) {
                    for (int zone : new int[] {0, 2}) {
                        TrioSettings settings = TrioSettings.defaults();
                        settings.enabled = true;
                        settings.trioStyle = arrangement == 2 ? Prefs.STYLE_CAPSULE : Prefs.STYLE_RING;
                        settings.splitMode = arrangement == 1 ? 1 : 0;
                        settings.showMobile = settings.showSim1 = settings.showSim2 = true;
                        settings.signalMode = Prefs.SIGNAL_IN_RING;
                        settings.ringZones = new int[][] {{}, {}, {}, {}};
                        settings.ringZones[zone] = new int[] {part};
                        settings.trackAlpha = 53; settings.signalTrackAlpha = 56;
                        try (Frame original = new Frame(); Frame batteryChanged = new Frame()) {
                            TrioRenderer.drawState(original, 480, 400, state, settings);
                            check(original.signal.contains(foreground) && original.signal.contains(inactive(foreground, 56)),
                                    "partial signal has lit and unlit segments: " + arrangement + "/" + part + "/" + zone);
                            settings.trackAlpha = 180;
                            TrioRenderer.drawState(batteryChanged, 480, 400, state, settings);
                            check(original.signal.equals(batteryChanged.signal) && original.dots.equals(batteryChanged.dots),
                                    "battery-only change preserves signal colors and geometry");
                            check(!original.battery.equals(batteryChanged.battery), "battery track follows its own control");
                            settings.trackAlpha = 53;
                            for (int alpha : new int[] {0, 56, 180, 255}) {
                                settings.signalTrackAlpha = alpha;
                                try (Frame changed = new Frame()) {
                                    TrioRenderer.drawState(changed, 480, 400, state, settings);
                                    check(original.battery.equals(changed.battery) && original.dots.equals(changed.dots),
                                            "signal-only change preserves battery and dot positions");
                                    List<Integer> expected = original.signal.stream()
                                            .map(color -> color == foreground ? foreground : inactive(foreground, alpha)).toList();
                                    check(changed.signal.equals(expected), "only unlit segments change; lit level and system tint survive");
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private static void externalBars() {
        for (int foreground : new int[] {0xff000000, 0xffffffff}) {
            for (int alpha : new int[] {0, 56, 180, 255}) {
                for (int bars = 0; bars <= 4; bars++) {
                    for (int dots : new int[] {-1, 0, 1, 4}) {
                        TrioSettings settings = TrioSettings.defaults();
                        settings.signalTrackAlpha = alpha;
                        try (Frame original = new Frame(); Frame changed = new Frame()) {
                            check(TrioRenderer.drawOutSignal(original, 80, 60, bars, dots, foreground, TrioAppearance.of(settings)),
                                    "external signal paints");
                            List<Integer> expected = new ArrayList<>();
                            for (int i = 0; i < 4; i++) {
                                expected.add(i < bars ? foreground : inactive(foreground, alpha));
                                if (dots >= 0) expected.add(i < dots ? foreground : inactive(foreground, alpha));
                            }
                            check(original.signal.equals(expected), "external bars and second SIM dots use independent opacity");
                            settings.trackAlpha = 255;
                            TrioRenderer.drawOutSignal(changed, 80, 60, bars, dots, foreground, TrioAppearance.of(settings));
                            check(original.signal.equals(changed.signal), "battery edit leaves external signal unchanged");
                        }
                    }
                }
            }
        }
    }

    public static void main(String[] args) {
        upgradeAndTransport();
        ringAndCapsule();
        externalBars();
        System.out.println("Signal track opacity checks passed: " + checks);
    }
}
