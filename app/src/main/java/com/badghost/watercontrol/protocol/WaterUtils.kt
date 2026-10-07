/*
 * This file is part of waterctl-android, a Kotlin port of waterctl
 * Copyright (c) 2021-2024 celesWuff
 *
 * Released under the MIT license. See the LICENSE file of the original project
 * <https://github.com/celesWuff/waterctl> for the full text.
 */
package com.badghost.watercontrol.protocol

/**
 * Kotlin port of `src/utils.ts` from <https://github.com/celesWuff/waterctl>.
 */
object WaterUtils {

    private val HEX = "0123456789ABCDEF".toCharArray()

    /**
     * Explains `[0x12, 0x34, 0xAB, 0xCD]` as `"1234ABCD"`.
     *
     * Equivalent to the original `bufferToHexString`, which uppercases the result.
     */
    fun bufferToHexString(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            out[i * 2] = HEX[v ushr 4]
            out[i * 2 + 1] = HEX[v and 0x0F]
        }
        return String(out)
    }

    /**
     * Explains `42` as `0x42`: reinterprets a decimal number's digits as hexadecimal
     * digits, i.e. performs a decimal->BCD conversion.
     *
     * `0` maps to `0`, matching the original's `n <= 0 ? 0 : ...` guard.
     */
    fun decAsHex(n: Int): Int {
        if (n <= 0) return 0
        var out = 0
        var shift = 0
        var value = n
        while (value > 0) {
            out = out or ((value % 10) shl shift)
            value /= 10
            shift += 4
        }
        return out
    }
}
