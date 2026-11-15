/*
 * Copyright (C) 2026 yixing233 and HyperDuo contributors
 *
 * This file is part of HyperDuo, released under the GNU General Public
 * License v3.0. See the LICENSE file at the repository root, or
 * <https://www.gnu.org/licenses/gpl-3.0.html>.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */

package android.graphics;

/** Minimal desktop stand-in for {@code android.graphics.Typeface}. */
public class Typeface {

    public static final int NORMAL = 0;
    public static final int BOLD = 1;
    public static final int ITALIC = 2;
    public static boolean ignoreNumericWeights;
    private static final String DEFAULT_FAMILY = System.getProperty("colorduo.test.font", "SansSerif");

    public static final Typeface DEFAULT = new Typeface(DEFAULT_FAMILY, NORMAL);
    public static final Typeface DEFAULT_BOLD = new Typeface(DEFAULT_FAMILY, BOLD);
    public static final Typeface SANS_SERIF = DEFAULT;

    private final String family;
    private final int style;
    private final boolean condensed;

    private Typeface(String family, int style) {
        this(family,style,false);
    }

    private Typeface(String family,int style,boolean condensed) {
        this.family=family;this.style=style;this.condensed=condensed;
    }

    /**
     * Maps the Android family names used by the renderer onto desktop fonts.
     * MIUI ships Roboto; a metric-compatible substitute is close enough for a
     * geometry check.
     */
    public static Typeface create(String family, int style) {
        String mapped = DEFAULT_FAMILY;
        if (family != null && family.startsWith("sans-serif")) {
            mapped = DEFAULT_FAMILY;
        }
        return new Typeface(mapped, style, family != null && family.contains("condensed"));
    }

    /**
     * Numeric-weight overload (API 28+). The desktop substitute only has a
     * plain and a bold face, so the 100..900 range is bucketed: the renderer
     * exposes nine weights but a geometry check only needs to see that heavier
     * text is bolder and therefore measured wider.
     */
    public static Typeface create(Typeface base, int weight, boolean italic) {
        int style = !ignoreNumericWeights && weight >= 600 ? BOLD : NORMAL;
        if (italic) {
            style |= ITALIC;
        }
        return new Typeface(base == null ? DEFAULT_FAMILY : base.family, style, base != null && base.condensed);
    }

    /**
     * Loading a font file, as the custom-font feature does.
     *
     * <p>The desktop stand-in cannot read a real font, and should not: the offline
     * probes check glyph geometry, not the user's file. It answers a plain face so
     * the renderer takes the "a custom font is in use" path - which is the branch
     * worth exercising, because that is where the synthetic emboldening and the
     * cache invalidation live. The file is still required to exist, so a caller
     * that gets its path wrong is not silently accepted.
     */
    public static Typeface createFromFile(String path) {
        Typeface face = createFromFileOrNull(path);
        if (face == null) {
            throw new RuntimeException("cannot open font: " + path);
        }
        return face;
    }

    /** The same load, answering null instead of throwing. */
    public static Typeface createFromFileOrNull(String path) {
        if (path == null || !new java.io.File(path).isFile()) {
            return null;
        }
        return new Typeface(DEFAULT_FAMILY, NORMAL);
    }

    String family() { return family; }
    float width() { return condensed ? 0.75f : 1f; }

    int style() {
        int out = java.awt.Font.PLAIN;
        if ((style & BOLD) != 0) {
            out |= java.awt.Font.BOLD;
        }
        if ((style & ITALIC) != 0) {
            out |= java.awt.Font.ITALIC;
        }
        return out;
    }
}
