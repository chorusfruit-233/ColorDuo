/* Copyright (C) 2026 jahn2007 and ColorDuo contributors. SPDX-License-Identifier: GPL-3.0-only */
package io.github.yixing233.hyperduo;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.UserManager;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Both APKs use one private, device-protected store so switching APIs retains all layouts. */
public final class SettingsStore {
    private SettingsStore() {}
    public static synchronized SharedPreferences preferences(Context context) {
        Context device = context.createDeviceProtectedStorageContext();
        SharedPreferences target = device.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE);
        SharedPreferences migration = device.getSharedPreferences("settings_storage", Context.MODE_PRIVATE);
        UserManager users = context.getSystemService(UserManager.class);
        if (!migration.getBoolean("credential_migrated", false) && users != null && users.isUserUnlocked()) {
            SharedPreferences.Editor editor = target.edit();
            for (Map.Entry<String, ?> entry : context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE).getAll().entrySet()) {
                // A value already saved by either flavour always wins over the old mirror.
                if (target.contains(entry.getKey())) continue;
                String key = entry.getKey(); Object value = entry.getValue();
                if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
                else if (value instanceof Integer) editor.putInt(key, (Integer) value);
                else if (value instanceof Long) editor.putLong(key, (Long) value);
                else if (value instanceof Float) editor.putFloat(key, (Float) value);
                else if (value instanceof String) editor.putString(key, (String) value);
                else if (value instanceof Set) {
                    @SuppressWarnings("unchecked") Set<String> strings = (Set<String>) value;
                    editor.putStringSet(key, new HashSet<>(strings));
                }
            }
            if (editor.commit()) migration.edit().putBoolean("credential_migrated", true).commit();
        }
        return target;
    }
}
