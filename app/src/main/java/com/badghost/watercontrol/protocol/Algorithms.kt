/*
 * This file is part of waterctl-android, a Kotlin port of waterctl
 * Copyright (c) 2021-2024 celesWuff
 *
 * Released under the MIT license. See the LICENSE file of the original project
 * <https://github.com/celesWuff/waterctl> for the full text.
 */
package com.badghost.watercontrol.protocol

/**
 * Kotlin port of `src/algorithms.ts` from <https://github.com/celesWuff/waterctl>.
 */
object Algorithms {

    /**
     * CRC-16/ChangGong.
     *
     * width=16 poly=0x8005 init=0xe808 refin=true refout=true xorout=0x0000
     *
     * The original loops over UTF-16 code units of a JavaScript string; this port
     * takes the same code units as an `IntArray` so the behaviour is identical for
     * any device name.
     */
    fun crc16changgong(codeUnits: IntArray): Int {
        var crc = 0x1017
        for (codeUnit in codeUnits) {
            // The original `crc ^= str.charCodeAt(i)` xors the full 16-bit code unit in.
            // The device name is passed through parseInt(..., 16) there, so anything
            // outside Latin-1 has no faithful equivalent -- reject it loudly instead of
            // silently computing a different checksum.
            require(codeUnit <= 0xFF) {
                "crc16changgong expects a Latin-1 device name, got code unit 0x${codeUnit.toString(16)}"
            }
            crc = crc xor codeUnit
            for (j in 0 until 8) {
                crc = if ((crc and 0x0001) == 1) {
                    (crc ushr 1) xor 0xa001
                } else {
                    crc ushr 1
                }
            }
        }
        return crc and 0xFFFF
    }

    /**
     * Convenience overload for the common case where the device name is pure ASCII,
     * which it always is for the water controllers this app targets.
     */
    fun crc16changgong(str: String): Int = crc16changgong(str.toCodeUnits())

    /**
     * CRC-16/CGAEAF (abbrev for ChangGong AE/AF).
     *
     * width=16 poly=0x8005 init=0xf856 refin=true refout=true xorout=0x0075,
     * truncated to the lower 8 bits.
     */
    fun crc16cgaeaf(array: ByteArray): Int {
        var crc = 0x6a1f
        for (byte in array) {
            crc = crc xor (byte.toInt() and 0xFF)
            for (j in 0 until 8) {
                crc = if ((crc and 0x0001) == 1) {
                    (crc ushr 1) xor 0xa001
                } else {
                    crc ushr 1
                }
            }
        }
        crc = (crc xor 0x75) and 0xFF
        return crc
    }
}

/** JavaScript's `String.prototype.charCodeAt` semantics over the whole string. */
internal fun String.toCodeUnits(): IntArray = IntArray(length) { this[it].code }
