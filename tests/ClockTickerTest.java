package io.github.yixing233.hyperduo;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.SystemClock;
import android.text.format.DateFormat;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Locale;
import java.util.TimeZone;

/** Android services are fake; production scheduling and formatter logic run unchanged. */
public final class ClockTickerTest {
    static final long NOW = Instant.parse("2026-11-15T19:41:30.250Z").toEpochMilli();
    static Method refresh;
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static void at(long wall) throws Exception { refresh.invoke(null, wall); }
    static void text(String expected) { check(expected.equals(TrioState.sClockText), "clock: " + TrioState.sClockText + " != " + expected); }
    static void deadline(long delay) {
        check(Handler.tasks.size() == 1, "only one timer: " + Handler.tasks.size());
        check(Handler.tasks.get(0).due() - SystemClock.now == delay, "boundary delay: " + Handler.tasks.get(0).due());
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.US); TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        refresh = ClockTicker.class.getDeclaredMethod("refreshAt", long.class); refresh.setAccessible(true);
        Context context = new Context();
        TrioSettings s = TrioConfig.settings;
        s.enabled = true; s.trioStyle = Prefs.STYLE_CAPSULE;
        s.ringZones = Prefs.decodeLayout("|3||6");
        ClockTicker.start(context); ClockTicker.start(context); Handler.advance(0);
        at(NOW); text("19:41"); deadline(29_750);
        s.clockSeconds = true;
        switch (args[0]) {
            case "boundaries":
                for (long wall : new long[]{-1001, -1, 0, 1, 999, 1000, NOW, NOW + 999_999})
                    for (long interval : new long[]{1000, 60_000}) {
                        long delay = ClockTicker.boundaryDelay(wall, interval);
                        check(delay > 0 && delay <= interval && Math.floorMod(wall + delay, interval) == 0,
                                "future aligned boundary across clock changes");
                    }
                break;
            case "minute":
                s.clockSeconds = false;
                int count = TrioHooks.repaints; at(NOW + 1000); at(NOW + 2000);
                text("19:41"); check(TrioHooks.repaints == count, "minute display is not republished per second");
                at(NOW + 60_000); text("19:42"); break;
            case "seconds":
                at(NOW); text("19:41:30"); deadline(750);
                int formats = DateFormat.formats; count = TrioHooks.repaints;
                for (int i = 0; i < 100; i++) at(NOW);
                check(DateFormat.formats == formats && TrioHooks.repaints == count, "host refresh reuses format and reading");
                deadline(750); SystemClock.now += 1000; at(NOW + 1000); text("19:41:31"); deadline(750);
                at(NOW + 62_000); text("19:42:32"); deadline(750);
                at(NOW - 2000); text("19:41:28"); deadline(750); break;
            case "right":
                s.ringZones = Prefs.decodeLayout("|6||3"); at(NOW); text("19:41:30"); deadline(750); break;
            case "middle":
                s.capsuleColumns = 3; s.ringZones = Prefs.decodeLayout("|3||5|6");
                at(NOW); text("19:41:30"); deadline(750); break;
            case "hidden-middle":
                s.ringZones = Prefs.decodeLayout("|3||5|6"); at(NOW);
                check(Handler.tasks.isEmpty(), "hidden middle clock must not sample"); break;
            case "disabled":
                s.enabled = false; at(NOW); check(Handler.tasks.isEmpty(), "disabled module has no timer");
                s.enabled = true; s.trioStyle = Prefs.STYLE_RECT; at(NOW);
                check(Handler.tasks.isEmpty(), "rectangle has no clock"); break;
            case "format":
                DateFormat.twentyFour = false; at(NOW); text("7:41:30 PM");
                TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai")); at(NOW); text("3:41:30 AM");
                s.clockSeconds = false; at(NOW); text("3:41 AM"); break;
            case "sleep":
                at(NOW); context.power.interactive = false; context.send(Intent.ACTION_SCREEN_OFF); Handler.advance(0);
                at(NOW); text("19:41"); deadline(29_750); break;
            case "aod":
                at(NOW); ColorOsHooks.aod = true; PowerSampling.changed(); Handler.advance(0);
                at(NOW); text("19:41"); deadline(29_750); break;
            case "wake":
                context.power.interactive = false; at(NOW); text("19:41");
                context.power.interactive = true; context.send(Intent.ACTION_SCREEN_ON); Handler.advance(0);
                at(NOW); text("19:41:30"); deadline(750); break;
            case "config":
                TrioConfig.changed(); Handler.advance(0); at(NOW); text("19:41:30");
                s.clockSeconds = false; TrioConfig.changed(); Handler.advance(0); at(NOW); text("19:41");
                s.ringZones = Prefs.decodeLayout("|3||5"); TrioConfig.changed(); Handler.advance(0);
                check(Handler.tasks.isEmpty(), "removing clock cancels timer"); break;
            case "time-event":
                at(NOW); context.send(Intent.ACTION_TIME_CHANGED); Handler.advance(0);
                at(NOW - 60_000); text("19:40:30"); deadline(750); break;
            case "retire":
                at(NOW); ColorOsReload.stop(); check(Handler.tasks.isEmpty(), "retire cancels timers");
                ColorOsReload.resume(); Handler.advance(0); at(NOW); text("19:41:30"); deadline(750); break;
            case "unavailable":
                DateFormat.broken = true; ClockTicker.refreshNow(); deadline(60_000);
                DateFormat.broken = false; at(NOW); text("19:41:30"); deadline(750); break;
            default: throw new AssertionError(args[0]);
        }
    }
}
