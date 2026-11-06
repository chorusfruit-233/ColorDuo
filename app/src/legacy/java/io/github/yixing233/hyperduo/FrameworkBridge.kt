/* SPDX-License-Identifier: GPL-3.0-only */
package io.github.yixing233.hyperduo

import android.content.Context
import android.os.Handler
import android.os.Looper

internal object FrameworkBridge {
    const val hasRemotePreferences = false
    fun start(context: Context, changed: (FrameworkService?) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        var previousApi = -1
        main.post(object : Runnable {
            override fun run() {
                val status = LegacySettings.status()
                val api = if (status.getBoolean("active")) status.getInt("api") else 0
                if (api != previousApi) {
                    previousApi = api
                    changed(if (api == 0) null else object : FrameworkService {
                        override val description = "Legacy Xposed API $api"
                        // The provider handshake proves this process is actually hooked.
                        override val scope = listOf(Prefs.SYSTEMUI_PACKAGE)
                        override fun getRemotePreferences(name: String) = SettingsStore.preferences(context)
                    })
                }
                main.postDelayed(this, 5_000)
            }
        })
    }
}
