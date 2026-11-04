/* SPDX-License-Identifier: GPL-3.0-only */
package io.github.yixing233.hyperduo;

import org.junit.Test;
import static org.junit.Assert.*;

public class InternetRouteTest {
    @Test public void defaultTransportRatherThanAssociationControlsWifi() {
        assertEquals(InternetRoute.Path.WIFI,InternetRoute.transport(true,false,false));
        assertEquals(InternetRoute.Path.MOBILE,InternetRoute.transport(false,true,false));
        assertEquals(InternetRoute.Path.OTHER,InternetRoute.transport(false,false,true));
        assertEquals(InternetRoute.Path.UNKNOWN,InternetRoute.transport(true,true,false));
        assertEquals(InternetRoute.Path.UNKNOWN,InternetRoute.transport(false,false,false));
        assertTrue(InternetRoute.wifiForDisplay(true,true,InternetRoute.Path.WIFI));
        assertFalse(InternetRoute.wifiForDisplay(true,true,InternetRoute.Path.MOBILE));
        assertFalse(InternetRoute.wifiForDisplay(true,true,InternetRoute.Path.OTHER));
        assertFalse(InternetRoute.wifiForDisplay(false,true,InternetRoute.Path.WIFI));
        // A permission failure or opaque VPN must not be guessed to be mobile.
        assertTrue(InternetRoute.wifiForDisplay(true,true,InternetRoute.Path.UNKNOWN));
        assertTrue(InternetRoute.wifiForDisplay(true,false,InternetRoute.Path.MOBILE));
    }
}
