#!/usr/bin/env python3
"""Exercise the real host watchdog when native signal views no longer repaint."""
from pathlib import Path
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
hooks = (ROOT / "app/src/main/java/io/github/yixing233/hyperduo/ColorOsHooks.java").read_text()
watchdog = re.search(r"    private static boolean hostWatchdogQueued;.*?(?=    private static Class<\?> meterClass;)", hooks, re.S)
if watchdog is None:
    raise RuntimeError("Production host watchdog missing")
harness = r'''
package io.github.yixing233.hyperduo;
import java.util.*;

class ColorOsHooks {
    static final Object context = new Object();
    static final Queue MAIN = new Queue();
    static int nativeLevel = 4, renderedLevel = 4, refreshes;
    static void refreshHosts() { renderedLevel = nativeLevel; refreshes++; }
    static class ColorOsReload { static boolean retiring; }
    static class PowerSampling { static boolean active = true; static boolean active(Object c) { return active; } }
    static class TrioAppearance {
        boolean mobile, type;
        boolean foldsMobile() { return mobile; }
        boolean ownsType() { return type; }
    }
    static class TrioConfig {
        static final TrioAppearance config = new TrioAppearance();
        static TrioAppearance appearance() { return config; }
    }
    static class Queue {
        record Task(Runnable runnable, long due) {}
        long now;
        final List<Task> tasks = new ArrayList<>();
        void removeCallbacks(Runnable r) { tasks.removeIf(t -> t.runnable == r); }
        void postDelayed(Runnable r, long delay) { tasks.add(new Task(r, now + delay)); }
        void advance(long delta) {
            now += delta;
            for (;;) {
                Task next = tasks.stream().filter(t -> t.due <= now)
                        .min(Comparator.comparingLong(Task::due)).orElse(null);
                if (next == null) return;
                tasks.remove(next); next.runnable.run();
            }
        }
    }
@WATCHDOG@
    static int checks;
    static void check(boolean condition, String message) {
        checks++; if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        // The existing native collector updates its StateFlow without a repaint callback.
        // Model that hidden-view case: only the production watchdog can consume this value.
        TrioConfig.config.mobile = true;
        syncHostWatchdog(); nativeLevel = 1;
        MAIN.advance(999);
        check(renderedLevel == 4, "sampling remains bounded to one second");
        syncHostWatchdog(); MAIN.advance(1);
        check(renderedLevel == 1, "hidden native signal changes still appear within one second");
        check(MAIN.tasks.size() == 1, "refreshes retain one watchdog callback");

        TrioConfig.config.mobile = false;
        TrioConfig.config.type = true;
        syncHostWatchdog(); nativeLevel = 2; MAIN.advance(1000);
        check(renderedLevel == 2, "type-only native flow retains its fallback cadence");

        TrioConfig.config.type = false;
        syncHostWatchdog(); int before = refreshes;
        MAIN.advance(9999);
        check(refreshes == before, "native-owned mobile layouts use the ten-second watchdog");
        MAIN.advance(1);
        check(refreshes == before + 1, "slow host recovery still executes");

        TrioConfig.config.mobile = true;
        syncHostWatchdog(); nativeLevel = 3; MAIN.advance(1000);
        check(renderedLevel == 3, "taking over signals replaces an already queued slow deadline");
        check(MAIN.tasks.size() == 1, "changing demand does not duplicate watchdogs");
        PowerSampling.active = false; syncHostWatchdog(); before = refreshes;
        check(MAIN.tasks.isEmpty(), "inactive sampling cancels the watchdog");
        MAIN.advance(30000);
        check(refreshes == before, "screen-off or disabled module does not poll hosts");
        PowerSampling.active = true; syncHostWatchdog(); MAIN.advance(1000);
        check(refreshes == before + 1, "active mobile layout resumes its fallback");
        System.out.println("Host watchdog and hidden native signal fallback: " + checks + " checks passed");
    }
}
'''.replace("@WATCHDOG@", watchdog.group())
with tempfile.TemporaryDirectory(prefix="colorduo-watchdog-") as directory:
    source = Path(directory) / "ColorOsHooks.java"
    source.write_text(harness)
    subprocess.run(["javac", "-d", directory, str(source)], check=True)
    subprocess.run(["java", "-cp", directory, "io.github.yixing233.hyperduo.ColorOsHooks"], check=True)
