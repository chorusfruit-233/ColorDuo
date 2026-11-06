/* SPDX-License-Identifier: GPL-3.0-only */
package io.github.yixing233.hyperduo

import android.content.SharedPreferences

/** Information available from the selected framework transport. */
interface FrameworkService {
    val description: String
    val scope: List<String>
    fun getRemotePreferences(name: String): SharedPreferences
}
