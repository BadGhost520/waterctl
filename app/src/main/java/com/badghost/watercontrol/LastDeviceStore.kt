/*
 * This file is part of waterctl-android, a Kotlin port of waterctl
 *
 * Released under the MIT license. See the LICENSE file of the original project
 * <https://github.com/celesWuff/waterctl> for the full text.
 */
package com.badghost.watercontrol

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Remembers the controller that was last connected to successfully.
 *
 * The name matters as much as the address: the unlock key is derived from the last four
 * characters of the device name and the start epilogue is checksummed over the last five, so
 * a reconnection that got the name wrong would be rejected by the controller. Storing both
 * means the reconnect path cannot lose it.
 *
 * Only [save] is called on success -- "last *valid* connection" is the whole point, so a
 * failed or rejected attempt must never overwrite a controller that did work.
 */
class LastDeviceStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** A previously connected controller. */
    data class Entry(val address: String, val name: String) {
        val isValid: Boolean get() = address.isNotBlank() && name.isNotBlank()
    }

    /** The remembered controller, or `null` when there is none. */
    fun load(): Entry? {
        val address = prefs.getString(KEY_ADDRESS, null)
        val name = prefs.getString(KEY_NAME, null)
        if (address.isNullOrBlank() || name.isNullOrBlank()) return null
        return Entry(address, name).takeIf { it.isValid }
    }

    /** Remembers a controller that was just connected to successfully. */
    fun save(address: String, name: String) {
        if (address.isBlank() || name.isBlank()) return
        prefs.edit {
            putString(KEY_ADDRESS, address)
            putString(KEY_NAME, name)
        }
    }

    /** Forgets the controller, for example when it turns out to no longer be reachable. */
    fun clear() {
        prefs.edit {
            remove(KEY_ADDRESS)
            remove(KEY_NAME)
        }
    }

    private companion object {
        const val PREFS_NAME = "waterctl_prefs"
        const val KEY_ADDRESS = "last_device_address"
        const val KEY_NAME = "last_device_name"
    }
}
