/*
 * Copyright (C) 2026 yixing233 and HyperDuo contributors
 *
 * This file is part of HyperDuo, released under the GNU General Public
 * License v3.0. See the LICENSE file at the repository root, or
 * <https://www.gnu.org/licenses/gpl-3.0.html>.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */

package io.github.yixing233.hyperduo

import android.app.Application
import android.util.Log
import io.github.yixing233.hyperduo.ui.SettingsRepository
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Holds the connection to the Xposed framework and keeps the module's remote
 * preferences in step with the settings the user picks here.
 *
 * <p>The service can bind late (the framework process comes up whenever it
 * likes) and can die at any moment, so everything that wants to write has to go
 * through {@link #xposedService} rather than capture a reference once.
 */
class HyperDuoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        runCatching {
            FrameworkBridge.start(this) { service ->
                xposedService = service
                if (service != null && FrameworkBridge.hasRemotePreferences) runCatching { SettingsRepository(this).syncAllToFramework() }
                    .onFailure { Log.w(TAG, "cannot push settings to framework", it) }
                listeners.forEach { it(service) }
            }
        }.onFailure { Log.w(TAG, "cannot start framework connection", it) }
    }

    companion object {
        private const val TAG = "HyperDuo"

        @Volatile
        var xposedService: FrameworkService? = null
            private set

        /** Notified with the current service (or null) whenever the binding changes. */
        private val listeners = CopyOnWriteArraySet<(FrameworkService?) -> Unit>()

        fun addServiceListener(listener: (FrameworkService?) -> Unit) {
            listeners.add(listener)
            listener(xposedService)
        }

        fun removeServiceListener(listener: (FrameworkService?) -> Unit) {
            listeners.remove(listener)
        }
    }
}
