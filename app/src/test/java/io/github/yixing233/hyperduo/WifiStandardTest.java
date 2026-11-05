/* SPDX-License-Identifier: GPL-3.0-only */
package io.github.yixing233.hyperduo;

import org.junit.Test;
import static org.junit.Assert.*;

public class WifiStandardTest {
    @Test public void androidStandardsUseTheirActualGeneration() {
        assertEquals("4", WifiStandard.label(4, 2412));
        assertEquals("5", WifiStandard.label(5, 5180));
        assertEquals("6", WifiStandard.label(6, 5180));
        assertEquals("7", WifiStandard.label(8, 6115));
        assertEquals("", WifiStandard.label(7, 60480)); // 11ad is not Wi-Fi 7.
    }

    @Test public void sixERequiresBothAxAndTheSixGhzBand() {
        assertEquals("6E", WifiStandard.label(6, 5955));
        assertEquals("6E", WifiStandard.label(6, 7115));
        assertEquals("6", WifiStandard.label(6, 0));
        assertEquals("6", WifiStandard.label(6, 5825));
        assertEquals("5", WifiStandard.label(5, 6115));
        assertEquals("7", WifiStandard.label(8, 5180));
    }

    @Test public void unsupportedAndLegacyLinksDoNotInventAStandard() {
        for (int standard : new int[] {-1, 0, 1, 2, 3, 9, 99}) {
            assertEquals("", WifiStandard.label(standard, 6115));
        }
    }
}
