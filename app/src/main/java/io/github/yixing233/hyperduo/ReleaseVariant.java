/* SPDX-License-Identifier: GPL-3.0-only */
package io.github.yixing233.hyperduo;

/** Match the installed API, regardless of the order GitHub returns release assets. */
public final class ReleaseVariant {
    private ReleaseVariant() {}
    public static int score(String name, String version, String flavor) {
        if (!"modern".equals(flavor) && !"legacy".equals(flavor)) return 0;
        String stem = "ColorDuo-" + version;
        if ((stem + "-" + flavor + ".apk").equalsIgnoreCase(name)) return 2;
        // Releases before API flavours were all modern. Legacy must never use this fallback.
        return "modern".equals(flavor) && (stem + ".apk").equalsIgnoreCase(name) ? 1 : 0;
    }
}
