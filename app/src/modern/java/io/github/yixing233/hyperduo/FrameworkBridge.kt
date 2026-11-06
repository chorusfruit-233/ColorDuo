/* SPDX-License-Identifier: GPL-3.0-only */
package io.github.yixing233.hyperduo

import android.content.Context
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

internal object FrameworkBridge {
    const val hasRemotePreferences = true
    private var listener: XposedServiceHelper.OnServiceListener? = null
    fun start(context: Context, changed: (FrameworkService?) -> Unit) {
        var current: XposedService? = null
        listener = object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                current = service
                changed(object : FrameworkService {
                    override val description: String
                        get() = "${service.frameworkName} ${service.frameworkVersion} (${service.frameworkVersionCode}) API ${service.apiVersion}"
                    override val scope: List<String> get() = service.scope
                    override fun getRemotePreferences(name: String) = service.getRemotePreferences(name)
                })
            }
            override fun onServiceDied(service: XposedService) {
                if (current === service) { current = null; changed(null) }
            }
        }
        XposedServiceHelper.registerListener(listener!!)
    }
}
