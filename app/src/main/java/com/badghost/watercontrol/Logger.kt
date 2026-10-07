/*
 * This file is part of waterctl-android, a Kotlin port of waterctl
 * Copyright (c) 2021-2024 celesWuff
 *
 * Released under the MIT license. See the LICENSE file of the original project
 * <https://github.com/celesWuff/waterctl> for the full text.
 */
package com.badghost.watercontrol

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Kotlin port of `src/logger.ts` from <https://github.com/celesWuff/waterctl>.
 *
 * The original keeps a plain in-memory list that the error dialog renders as "调试信息".
 * On Android the same list drives the expandable debug panel, and it is also mirrored to
 * logcat so a session can be captured over `adb logcat` when something misbehaves.
 */
object Logger {

    private const val TAG = "waterctl"
    private const val MAX_ENTRIES = 500

    private val entries = ArrayList<String>(64)
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val listeners = LinkedHashSet<(List<String>) -> Unit>()

    @Synchronized
    fun log(message: String) {
        val line = "${timeFormat.format(Date())}  $message"
        entries.add(line)
        if (entries.size > MAX_ENTRIES) entries.removeAt(0)
        android.util.Log.d(TAG, message)
        val snapshot = ArrayList(entries)
        listeners.forEach { it(snapshot) }
    }

    @Synchronized
    fun clear() {
        entries.clear()
        val snapshot = emptyList<String>()
        listeners.forEach { it(snapshot) }
    }

    @Synchronized
    fun getLogs(): List<String> = ArrayList(entries)

    @Synchronized
    fun isEmpty(): Boolean = entries.isEmpty()

    fun addListener(listener: (List<String>) -> Unit) {
        synchronized(this) { listeners.add(listener) }
        listener(getLogs())
    }

    fun removeListener(listener: (List<String>) -> Unit) {
        synchronized(this) { listeners.remove(listener) }
    }
}
