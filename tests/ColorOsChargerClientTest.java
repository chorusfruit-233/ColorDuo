/* SPDX-License-Identifier: GPL-3.0-only */
package io.github.yixing233.hyperduo;

import android.os.IBinder;
import android.os.ServiceManager;
import java.io.IOException;
import vendor.oplus.hardware.charger.ICharger;

/** Exercise the production reflection adapter against an independent AIDL-shaped service. */
public final class ColorOsChargerClientTest {
    private static int checks;
    private static final String PAYLOAD = "battery_current_now=-1314\nbattery_voltage_now=4474000";

    public static final class Binder implements IBinder, ICharger {
        boolean alive = true;
        boolean failed;
        String payload = PAYLOAD;
        int queries;
        public boolean isBinderAlive() { return alive; }
        public String queryChargeInfo() {
            queries++;
            if (failed) throw new IllegalStateException("charger died");
            return payload;
        }
    }

    interface Task { void run() throws Exception; }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static void expect(Class<? extends Exception> type, Task task) throws Exception {
        checks++;
        try { task.run(); }
        catch (Exception failure) {
            if (type.isInstance(failure)) return;
            throw new AssertionError("Expected " + type + " but got " + failure, failure);
        }
        throw new AssertionError("Expected " + type);
    }

    public static void main(String[] args) throws Exception {
        ClassLoader loader = ColorOsChargerClientTest.class.getClassLoader();
        ColorOsChargerClient client = new ColorOsChargerClient(loader, () -> ColorOsCharger.Topology.SERIES);
        ServiceManager.binder = null;
        expect(IOException.class, client::read);
        Binder first = new Binder();
        ServiceManager.binder = first;
        ColorOsCharger.Input sample = client.read();
        check(sample.properties.equals(PAYLOAD) && sample.topology == ColorOsCharger.Topology.SERIES,
                "the real adapter resolves the AIDL Stub and queries the service");
        int checksBefore = ServiceManager.lookups;
        client.read();
        check(ServiceManager.lookups == checksBefore && first.queries == 2,
                "a healthy connection reuses the binder rather than looking up the service each time");
        first.failed = true;
        expect(IllegalStateException.class, client::read);
        first.failed = false;
        client.read();
        check(ServiceManager.lookups == checksBefore + 1, "a remote failure clears the cached connection");
        first.alive = false;
        Binder second = new Binder();
        ServiceManager.binder = second;
        client.read();
        check(second.queries == 1 && ServiceManager.lookups == checksBefore + 2,
                "a dead binder reconnects to the replacement service");
        second.payload = null;
        expect(IOException.class, client::read);
        second.payload = PAYLOAD;
        check(ColorOsCharger.parse(client.read()) != null, "a missing response can recover without a restart");
        ServiceManager.denied = true;
        expect(SecurityException.class, new ColorOsChargerClient(loader)::read);
        ServiceManager.denied = false;
        ClassLoader missing = new ClassLoader(loader) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("vendor.oplus.hardware.charger.")) throw new ClassNotFoundException(name);
                return super.loadClass(name, resolve);
            }
        };
        expect(ClassNotFoundException.class, new ColorOsChargerClient(missing)::read);
        ColorOsCharger.Input noConfig = new ColorOsChargerClient(loader).read();
        check(noConfig.topology == ColorOsCharger.Topology.UNKNOWN && noConfig.properties.equals(PAYLOAD),
                "an inaccessible ODM config does not discard a valid voltage/current pair");
        System.out.println("Oplus AIDL lookup, permission failure, binder death and reconnect passed: " + checks + " checks");
    }
}
