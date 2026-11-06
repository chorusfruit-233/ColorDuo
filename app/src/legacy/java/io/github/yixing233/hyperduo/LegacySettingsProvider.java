/* Copyright (C) 2026 jahn2007 and ColorDuo contributors. SPDX-License-Identifier: GPL-3.0-only */
package io.github.yixing233.hyperduo;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;

/** SystemUI can read persisted snapshots; remote callers cannot modify the settings. */
public final class LegacySettingsProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (Binder.getCallingUid() != Process.myUid()) {
            String[] packages = getContext().getPackageManager().getPackagesForUid(Binder.getCallingUid());
            boolean allowed = packages != null && java.util.Arrays.asList(packages).contains(Prefs.SYSTEMUI_PACKAGE);
            if (!allowed) throw new SecurityException("ColorDuo settings are restricted to SystemUI");
        }
        if (!"snapshot".equals(method)) throw new IllegalArgumentException("Unknown method");
        if (Binder.getCallingUid() != Process.myUid())
            LegacySettings.heartbeat(extras == null ? 0 : extras.getInt("api", 0));
        return TrioSettings.from(SettingsStore.preferences(getContext()),
                getContext().getResources().getDisplayMetrics().density).toBundle();
    }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String order) { throw new UnsupportedOperationException(); }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
}
