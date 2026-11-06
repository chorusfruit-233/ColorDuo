package io.github.yixing233.hyperduo;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ReleaseVariantTest {
    @Test public void updaterKeepsTheInstalledApiInEitherAssetOrder() {
        for (String flavor : new String[] {"modern", "legacy"}) {
            for (String[] names : new String[][] {
                    {"ColorDuo-0.4.0-modern.apk", "ColorDuoLegacy-0.4.0.apk"},
                    {"ColorDuoLegacy-0.4.0.apk", "ColorDuo-0.4.0-modern.apk"}}) {
                String chosen = null; int best = 0;
                for (String name : names) {
                    int score = ReleaseVariant.score(name, "0.4.0", flavor);
                    if (score > best) { best = score; chosen = name; }
                }
                assertEquals("legacy".equals(flavor) ? "ColorDuoLegacy-0.4.0.apk" : "ColorDuo-0.4.0-modern.apk", chosen);
            }
        }
    }
    @Test public void onlyModernAcceptsOldUnsuffixedReleases() {
        assertEquals(1, ReleaseVariant.score("ColorDuo-0.3.18.apk", "0.3.18", "modern"));
        assertEquals(0, ReleaseVariant.score("ColorDuo-0.3.18.apk", "0.3.18", "legacy"));
        assertTrue(ReleaseVariant.score("ColorDuo-0.4.0-modern.apk", "0.4.0", "modern")
                > ReleaseVariant.score("ColorDuo-0.4.0.apk", "0.4.0", "modern"));
    }
    @Test public void unrelatedApksAndOtherVersionsCannotWin() {
        for (String flavor : new String[] {"modern", "legacy"}) {
            for (String name : new String[] {"SystemUI.apk", "ColorDuo-0.3.18-" + flavor + ".apk",
                    "ColorDuo-0.4.0-" + flavor + "-debug.apk", "ColorDuo-0.4.0-other.apk",
                    "ColorDuoLegacy-0.3.18.apk", "ColorDuoLegacy-0.4.0-debug.apk"})
                assertEquals(0, ReleaseVariant.score(name, "0.4.0", flavor));
        }
        assertEquals(0, ReleaseVariant.score("ColorDuo-0.4.0.apk", "0.4.0", ""));
    }
}
