/* SPDX-License-Identifier: GPL-3.0-only */
package io.github.yixing233.hyperduo;

/** Wi-Fi Alliance generation names from the connected link's IEEE standard. */
final class WifiStandard {
    private WifiStandard() {}

    // LuckyTool's force_display_wifi_standard supplements 11n/11ac with 4/5.
    // ColorDuo uses the same real WifiInfo standard for its own drawable, including
    // 6 GHz 11ax (6E). Android's value 8 is 11be; value 7 is 11ad, not Wi-Fi 7.
    // Reference: luckyzyx/LuckyTool, WiFiDataIconRelated.kt, ba6ade6e2780c702.
    static String label(int standard, int frequencyMhz) {
        switch (standard) {
            case 4: return "4";
            case 5: return "5";
            case 6: return frequencyMhz >= 5925 && frequencyMhz <= 7125 ? "6E" : "6";
            case 8: return "7";
            default: return "";
        }
    }
}
