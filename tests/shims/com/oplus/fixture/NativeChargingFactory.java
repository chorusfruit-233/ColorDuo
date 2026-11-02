/* SPDX-License-Identifier: GPL-3.0-only */
package com.oplus.fixture;

public final class NativeChargingFactory {
    public static Object sample() { return new NativeFlags(); }
}

// R8 can leave a model class non-public even when its getter methods are public.
final class NativeFlags {
    public boolean getSuperVoocCharging() { return true; }
    public boolean getFastCharging() { return true; }
}
