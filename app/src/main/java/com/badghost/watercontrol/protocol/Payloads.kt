/*
 * This file is part of waterctl-android, a Kotlin port of waterctl
 * Copyright (c) 2021-2024 celesWuff
 *
 * Released under the MIT license. See the LICENSE file of the original project
 * <https://github.com/celesWuff/waterctl> for the full text.
 */
package com.badghost.watercontrol.protocol

/**
 * Kotlin port of `src/payloads.ts` from <https://github.com/celesWuff/waterctl>.
 *
 * These are the fixed, checksum-free frames the controller expects.
 */
object Payloads {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    /** Send this first, then wait for B0, B1, or AE. */
    val startPrologue = bytes(0xFE, 0xFE, 0x09, 0xB0, 0x01, 0x01, 0x00, 0x00)

    /** When ending the session, send this then wait for B3. */
    val endPrologue = bytes(0xFE, 0xFE, 0x09, 0xB3, 0x00, 0x00)

    /** After receiving B3, send this then disconnect. */
    val endEpilogue = bytes(0xFE, 0xFE, 0x09, 0xB4, 0x00, 0x00)
    /**
     * Clears the previous unfinished offline (BB) session.
     *
     * Receiving BC is a strong signal that sending this is necessary.
     */
    val offlinebombFix = bytes(0xFE, 0xFE, 0x09, 0xBC, 0x00, 0x00)

    /** Send this if BA is received; BA is related to user info uploading. */
    val baAck = bytes(0xFE, 0xFE, 0x09, 0xBA, 0x00, 0x00)
}
