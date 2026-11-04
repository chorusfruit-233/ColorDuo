/* SPDX-License-Identifier: GPL-3.0-only */
package io.github.yixing233.hyperduo;

import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

/** Cached default Internet transport. A connected secondary Wi-Fi is not the route. */
final class InternetRoute {
    enum Path { UNKNOWN, WIFI, MOBILE, OTHER }
    private static volatile Path current = Path.UNKNOWN;

    static Path current() { return current; }

    static Path transport(boolean wifi, boolean mobile, boolean other) {
        // A multipath VPN without one identifiable underlying transport is ambiguous.
        int count=(wifi?1:0)+(mobile?1:0)+(other?1:0);
        if(count!=1) return Path.UNKNOWN;
        return wifi?Path.WIFI:mobile?Path.MOBILE:Path.OTHER;
    }

    static boolean wifiForDisplay(boolean associated, boolean followRoute, Path route) {
        if(!followRoute || route==Path.UNKNOWN) return associated;
        return associated && route==Path.WIFI;
    }

    static void refresh(ConnectivityManager manager) {
        Path next=Path.UNKNOWN;
        if(manager!=null) try {
            Network active=manager.getActiveNetwork();
            if(active!=null) next=read(manager,manager.getNetworkCapabilities(active));
        } catch(RuntimeException unavailable) { /* preserve association fallback */ }
        if(current!=next) {
            current=next;
            TrioHooks.invalidateHosts();
        }
    }

    private static Path read(ConnectivityManager manager, NetworkCapabilities caps) {
        if(caps==null) return Path.UNKNOWN;
        // Android's VPN capabilities often already include the physical transport.
        Path direct=transport(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
        if(!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) || direct!=Path.UNKNOWN) return direct;
        Object underlying=Refl.callByName(caps,"getUnderlyingNetworks");
        if(!(underlying instanceof Iterable<?>)) return Path.UNKNOWN;
        Path result=Path.UNKNOWN;
        for(Object item:(Iterable<?>)underlying) {
            if(!(item instanceof Network)) continue;
            NetworkCapabilities physical=manager.getNetworkCapabilities((Network)item);
            if(physical==null || physical.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
            Path path=transport(physical.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                    physical.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
                    physical.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
            if(path==Path.UNKNOWN) continue;
            if(result!=Path.UNKNOWN && result!=path) return Path.UNKNOWN;
            result=path;
        }
        return result;
    }
}
