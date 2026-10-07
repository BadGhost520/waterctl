/*
 * This file is part of waterctl-android, a Kotlin port of waterctl
 * Copyright (c) 2021-2024 celesWuff
 *
 * Released under the MIT license. See the LICENSE file of the original project
 * <https://github.com/celesWuff/waterctl> for the full text.
 */
package com.badghost.watercontrol.protocol

import java.util.Calendar

/**
 * Kotlin port of `src/solvers.ts` from <https://github.com/celesWuff/waterctl>.
 *
 * The only intentional difference from the original is that [makeDatetimeArray] uses the
 * device's local time zone instead of hard-coding `Asia/Shanghai`. A water controller is
 * always used in the time zone it physically stands in, and an offline Android device may
 * well be set to something else, so local time is the correct choice here.
 */
object Solvers {

    /**
     * Explains `XYZW` (decimal) as `[0xXY, 0xZW]`.
     *
     * The original draws `1..9999`; `10000` produces the same two bytes as `0` because
     * only the low two BCD digits survive, which is harmless and kept bug-for-bug.
     */
    internal fun makeRandomUserId(random: java.util.Random = java.util.Random()): ByteArray {
        val randomIdNumber = random.nextInt(9999) + 1 // 1..9999
        val high = WaterUtils.decAsHex(randomIdNumber shr 8)
        val low = WaterUtils.decAsHex(randomIdNumber and 0xFF)
        return byteArrayOf(high.toByte(), low.toByte())
    }

    /**
     * Explains `2013/1/11 12:34:56` as `[0x13, 0x01, 0x11, 0x12, 0x34, 0x56]`.
     */
    internal fun makeDatetimeArray(calendar: Calendar = Calendar.getInstance()): ByteArray {
        val year = calendar.get(Calendar.YEAR) % 100
        val month = calendar.get(Calendar.MONTH) + 1
        val day = calendar.get(Calendar.DAY_OF_MONTH)
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)
        val second = calendar.get(Calendar.SECOND)
        val decimal = intArrayOf(year, month, day, hour, minute, second)
        return ByteArray(decimal.size) { WaterUtils.decAsHex(decimal[it]).toByte() }
    }

    /** The raw key derivation, extracted straight from the `deputy` WebAssembly module. */
    internal fun makeUnlockKey(array: ByteArray): ByteArray {
        require(array.size == 4) { "Bad unlock request" }
        return Deputy.makeKey(
            array[0].toInt() and 0xFF,
            array[1].toInt() and 0xFF,
            array[2].toInt() and 0xFF,
            array[3].toInt() and 0xFF,
        )
    }

    /**
     * Builds the `AF` response to a `AE` key authentication request.
     *
     * This is needed by newer firmwares, which perform a challenge/response handshake
     * before accepting a start command.
     */
    fun makeUnlockResponse(unlockRequest: ByteArray, deviceName: String): ByteArray {
        require(unlockRequest.size >= 10) { "Bad unlock request" }

        val unknownByte = unlockRequest[5].toInt() and 0xFF // unknown, but we echo it back
        val nonceBytes = byteArrayOf(unlockRequest[6], unlockRequest[7])
        val mac = byteArrayOf(unlockRequest[8], unlockRequest[9])

        // [0x00, 0x01] => 0x0001 => (add 1) => 0x0002 => [0x00, 0x02]
        val nonce = ((nonceBytes[0].toInt() and 0xFF) shl 8) or (nonceBytes[1].toInt() and 0xFF)
        val newNonce = nonce + 1
        // bug-for-bug compatible: 0xffff wraps to 0x0100, not 0x0000
        val newNonceBytes = if (nonce == 0xffff) {
            byteArrayOf(0x01, 0x00)
        } else {
            byteArrayOf(((newNonce shr 8) and 0xFF).toByte(), (newNonce and 0xFF).toByte())
        }

        val rawKey = makeUnlockKey(nonceBytes + mac) // 2 bytes of nonce, 2 bytes of MAC

        val mask = maskFromDeviceName(deviceName)
        // Byte is signed in Kotlin, so both operands are widened before the xor to keep the
        // result identical to the JavaScript version (which works on 0..255 throughout).
        val key = ByteArray(4) {
            ((rawKey[it].toInt() and 0xFF) xor mask[it]).toByte()
        }

        val checksumInput = byteArrayOf(
            unknownByte.toByte(),
            newNonceBytes[0], newNonceBytes[1], // 2 bytes of nonce
            key[0], key[1], key[2], key[3],     // 4 bytes of key
            0xFE.toByte(), 0x87.toByte(), 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
        )

        val checksum = Algorithms.crc16cgaeaf(checksumInput)

        return byteArrayOf(
            0xFE.toByte(), 0xFE.toByte(), 0x09, 0xAF.toByte(),
            checksum.toByte(),
        ) + checksumInput // 1 byte of checksum, 15 bytes of the rest
    }

    /**
     * The original derives the key mask from the last four characters of the device name
     * with each character's code point shifted down by `0x30` (the ASCII code of `'0'`).
     * Non-digit characters in that position would produce garbage there too; this port
     * keeps the identical arithmetic so that it stays bug-for-bug compatible.
     */
    private fun maskFromDeviceName(deviceName: String): IntArray {
        val tail = deviceName.takeLast(4)
        val mask = IntArray(4)
        for (i in 0 until 4) {
            val code = if (i < tail.length) tail[i].code else 0
            mask[i] = (code - 0x30) and 0xFF
        }
        return mask
    }

    /**
     * The one true command to begin a session.
     *
     * @param isKeyAuthPresent `true` for newer firmwares that performed the AE/AF handshake.
     */
    fun makeStartEpilogue(deviceName: String, isKeyAuthPresent: Boolean = false): ByteArray {
        val checksum = Algorithms.crc16changgong(deviceName.takeLast(5))
        val mn = if (isKeyAuthPresent) 0x0b else 0xff // magic number
        val ri = makeRandomUserId()
        val dt = makeDatetimeArray()

        return byteArrayOf(
            0xFE.toByte(), 0xFE.toByte(), 0x09, 0xB2.toByte(),
            0x01, (checksum and 0xFF).toByte(), ((checksum shr 8) and 0xFF).toByte(), mn.toByte(),
            0x00, ri[0], ri[1], dt[0], dt[1], dt[2], dt[3], dt[4], dt[5], // 2 bytes of random user id, 6 bytes of datetime
            0x0F, 0x27, 0x00,
        )
    }

    /**
     * Offlinebomb exploit.
     *
     * Deprecated upstream, ported for reference only and not reachable from the UI.
     */
    fun makeStartEpilogueOfflinebomb(): ByteArray {
        val ri = makeRandomUserId()
        val dt = makeDatetimeArray()
        return byteArrayOf(
            0xFE.toByte(), 0xFE.toByte(), 0x09, 0xBB.toByte(),
            0x01, 0x01, 0x0D, 0x00,
            0x50, ri[0], ri[1], dt[0], dt[1], dt[2], dt[3], dt[4], dt[5],
            0x00, 0x20, 0x00,
        )
    }
}
