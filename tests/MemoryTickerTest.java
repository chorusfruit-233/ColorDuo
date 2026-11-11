package io.github.yixing233.hyperduo;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

/** Real MemoryTicker and layout settings; the fixture only controls Android services and time. */
public final class MemoryTickerTest {
    static int checks;
    static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        Context context = new Context();
        TrioSettings settings = TrioConfig.settings;
        settings.enabled = true;
        settings.trioStyle = Prefs.STYLE_RING;
        settings.ringZones = new int[][]{{Prefs.PART_MEMORY}, {}, {}, {}};
        TrioState.sMemoryPercent = TrioState.MEMORY_UNKNOWN;
        MemoryTicker.start(context);
        MemoryTicker.start(context);
        Handler.advance(0);
        check(context.memory.calls == 1 && TrioState.sMemoryPercent == 68 && TrioHooks.repaints == 1,
                "initial reading publishes once; starting twice cannot duplicate the ticker");
        switch (args[0]) {
            case "sampling":
                Handler.advance(3000);
                check(context.memory.calls == 2 && TrioHooks.repaints == 1, "unchanged percentage does not repaint");
                context.memory.available = 249;
                Handler.advance(3000);
                check(TrioState.sMemoryPercent == 75 && TrioHooks.repaints == 2, "changed reading rounds and repaints");
                context.memory.available = 2000;
                Handler.advance(3000);
                check(TrioState.sMemoryPercent == 0, "available memory above total clamps to zero");
                context.memory.available = -1;
                Handler.advance(3000);
                check(TrioState.sMemoryPercent == 100, "percentage cannot exceed 100");
                break;
            case "unavailable":
                context.memory.broken = true;
                Handler.advance(3000);
                check(TrioState.sMemoryPercent == TrioState.MEMORY_UNKNOWN && TrioHooks.repaints == 2,
                        "a failed read clears the previous percentage and repaints the empty slot");
                Handler.advance(3000);
                check(TrioHooks.repaints == 2, "repeated failures do not repaint");
                context.memory.broken = false;
                context.memory.total = 0;
                Handler.advance(3000);
                check(TrioState.sMemoryPercent == TrioState.MEMORY_UNKNOWN, "zero total remains unknown");
                context.memory.total = 1000;
                Handler.advance(3000);
                check(TrioState.sMemoryPercent == 68 && TrioHooks.repaints == 3, "sampling recovers after a failure");
                break;
            case "sleep":
                context.power.interactive = false;
                context.memory.available = 100;
                Handler.advance(9000);
                check(context.memory.calls == 1 && TrioHooks.repaints == 1, "sleep never samples or refreshes hosts");
                break;
            case "aod":
                ColorOsHooks.aod = true;
                context.memory.available = 100;
                Handler.advance(3000);
                check(context.memory.calls == 1 && TrioHooks.repaints == 1, "native AOD blocks sampling even while interactive");
                break;
            case "rectangle":
                settings.trioStyle = Prefs.STYLE_RECT;
                Handler.advance(3000);
                check(context.memory.calls == 1, "stored ring placements do not sample in the rectangle");
                break;
            case "unused-left":
                settings.ringZones = new int[][]{{}, {}, {}, {Prefs.PART_MEMORY}};
                Handler.advance(3000);
                check(context.memory.calls == 1, "a ring cannot display the capsule-only left slot");
                settings.trioStyle = Prefs.STYLE_CAPSULE;
                MemoryTicker.refreshNow();
                check(context.memory.calls == 2, "the capsule left slot resumes sampling immediately");
                break;
            case "disabled":
                settings.enabled = false;
                Handler.advance(3000);
                MemoryTicker.refreshNow();
                check(context.memory.calls == 1 && TrioHooks.repaints == 1, "disabled master blocks IPC and repaint");
                settings.enabled = true;
                MemoryTicker.refreshNow();
                check(context.memory.calls == 2, "reenabling samples immediately");
                break;
            case "removed":
                settings.ringZones[0] = new int[0];
                Handler.advance(3000);
                check(context.memory.calls == 1, "removing memory stops sampling");
                break;
            case "throttle":
                for (int i = 0; i < 20; i++) MemoryTicker.refreshNow();
                check(context.memory.calls == 1, "host refreshes cannot bypass the three-second sample interval");
                Handler.advance(2999);
                MemoryTicker.refreshNow();
                check(context.memory.calls == 1, "still throttled just before the deadline");
                Handler.advance(1);
                check(context.memory.calls == 2, "the next scheduled reading is not delayed by refresh calls");
                break;
            case "retired":
                ColorOsReload.retiring = true;
                ColorOsReload.watched.removeCallbacksAndMessages(null);
                MemoryTicker.refreshNow();
                MemoryTicker.start(context);
                Handler.advance(3000);
                check(context.memory.calls == 1 && Handler.tasks.isEmpty(), "retired generations cannot sample or requeue");
                break;
            case "wake":
                context.power.interactive = false;
                MemoryTicker.refreshNow();
                context.power.interactive = true;
                context.memory.available = 100;
                MemoryTicker.refreshNow();
                check(context.memory.calls == 2 && TrioState.sMemoryPercent == 90, "waking refreshes without waiting for a tick");
                break;
            case "thread":
                Handler.advance(3000);
                context.power.interactive = false;
                MemoryTicker.refreshNow();
                context.power.interactive = true;
                Looper.onMain = false;
                MemoryTicker.refreshNow();
                check(context.memory.calls == 2, "off-main refresh must not run binder work or repaint inline");
                Looper.onMain = true;
                Handler.advance(0);
                check(context.memory.calls == 3, "queued refresh executes on the main looper");
                break;
            case "missing-power":
                context.power = null;
                Handler.advance(3000);
                check(context.memory.calls == 1, "unknown power state avoids background sampling");
                break;
            default: throw new AssertionError("unknown scene");
        }
        System.out.println(args[0] + ": " + checks + " checks passed");
    }
}
