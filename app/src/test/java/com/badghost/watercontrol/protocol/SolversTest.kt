/*
 * This file is part of waterctl-android, a Kotlin port of waterctl
 * Copyright (c) 2021-2024 celesWuff
 *
 * Released under the MIT license. See the LICENSE file of the original project
 * <https://github.com/celesWuff/waterctl> for the full text.
 */
package com.badghost.watercontrol.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

/**
 * Port of `src/solvers.spec.ts` from <https://github.com/celesWuff/waterctl>.
 *
 * The expected byte strings are copied verbatim from the upstream test file. If any of these
 * fail, the Kotlin port of the `deputy` WebAssembly module has diverged from the original,
 * which would make the app unable to unlock a controller.
 */
class SolversTest {

    private val deviceName = "Water33982"

    private fun hex(bytes: ByteArray): String = WaterUtils.bufferToHexString(bytes)

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun formatDateToString(calendar: Calendar): String {
        val year = calendar.get(Calendar.YEAR) % 100
        val month = calendar.get(Calendar.MONTH) + 1
        val day = calendar.get(Calendar.DAY_OF_MONTH)
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)
        val second = calendar.get(Calendar.SECOND)
        return "%02d%02d%02d%02d%02d%02d".format(year, month, day, hour, minute, second)
    }

    @Test
    fun makeUnlockResponse() {
        // All seven vectors from the upstream test, in order.
        val cases = listOf(
            bytes(0xfd, 0xfd, 0x09, 0xae, 0x38, 0x00, 0x00, 0x05, 0x95, 0x27, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x10, 0x11, 0x12) to
                bytes(0xfe, 0xfe, 0x09, 0xaf, 0x72, 0x00, 0x00, 0x06, 0xc3, 0x50, 0x9a, 0xc4, 0xfe, 0x87, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00),
            bytes(0xfd, 0xfd, 0x09, 0xae, 0xa2, 0x00, 0x9b, 0x05, 0x95, 0x27, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x10, 0x11, 0x12) to
                bytes(0xfe, 0xfe, 0x09, 0xaf, 0x44, 0x00, 0x9b, 0x06, 0xf1, 0x30, 0x9a, 0xd3, 0xfe, 0x87, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00),
            bytes(0xfd, 0xfd, 0x09, 0xae, 0x5b, 0x39, 0x9b, 0x05, 0x95, 0x27, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x10, 0x11, 0x12) to
                bytes(0xfe, 0xfe, 0x09, 0xaf, 0xbd, 0x39, 0x9b, 0x06, 0xf1, 0x30, 0x9a, 0xd3, 0xfe, 0x87, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00),
            bytes(0xfd, 0xfd, 0x09, 0xae, 0x9f, 0x00, 0xff, 0xff, 0x95, 0x27, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x10, 0x11, 0x12) to
                bytes(0xfe, 0xfe, 0x09, 0xaf, 0xd9, 0x00, 0x01, 0x00, 0xd6, 0xac, 0x3f, 0x3b, 0xfe, 0x87, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00),
            bytes(0xfd, 0xfd, 0x09, 0xae, 0xb3, 0x00, 0x34, 0x64, 0x80, 0x14, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x10, 0x11, 0x12) to
                bytes(0xfe, 0xfe, 0x09, 0xaf, 0xb1, 0x00, 0x34, 0x65, 0xd6, 0x28, 0x9a, 0xc1, 0xfe, 0x87, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00),
            bytes(0xfd, 0xfd, 0x09, 0xae, 0x01, 0x00, 0x81, 0xff, 0x68, 0x30, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x10, 0x11, 0x12) to
                bytes(0xfe, 0xfe, 0x09, 0xaf, 0x68, 0x00, 0x82, 0x00, 0xf1, 0x12, 0xb2, 0x21, 0xfe, 0x87, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00),
            bytes(0xfd, 0xfd, 0x09, 0xae, 0xe5, 0x00, 0x01, 0xff, 0x73, 0x24, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x10, 0x11, 0x12) to
                bytes(0xfe, 0xfe, 0x09, 0xaf, 0x4b, 0x00, 0x02, 0x00, 0xcc, 0xca, 0x9a, 0x20, 0xfe, 0x87, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00),
        )

        // Pinned so that dropping or short-circuiting a vector cannot silently "pass".
        assertEquals("upstream src/solvers.spec.ts has 7 makeUnlockResponse vectors", 7, cases.size)

        for ((index, case) in cases.withIndex()) {
            val (request, expected) = case
            val actual = Solvers.makeUnlockResponse(request, deviceName)
            assertEquals("vector #$index", hex(expected), hex(actual))
        }
    }

    @Test
    fun makeUnlockKeyRejectsWrongLength() {
        try {
            Solvers.makeUnlockKey(bytes(0x00, 0x05, 0x95))
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun makeStartEpilogueOldFirmware() {
        val calendar = Calendar.getInstance()
        val response = Solvers.makeStartEpilogue(deviceName, isKeyAuthPresent = false)

        assertArrayEquals(bytes(0xfe, 0xfe, 0x09, 0xb2, 0x01), response.copyOfRange(0, 5))

        val checksum = (response[5].toInt() and 0xFF) or ((response[6].toInt() and 0xFF) shl 8)
        assertEquals(Algorithms.crc16changgong(deviceName.takeLast(5)), checksum)

        assertArrayEquals(bytes(0xff, 0x00), response.copyOfRange(7, 9))

        val randomUserId = response.copyOfRange(9, 11)
        assertEquals(2, randomUserId.size)

        val datetimeString = hex(response.copyOfRange(11, 17))
        assertEquals(formatDateToString(calendar), datetimeString)

        assertArrayEquals(bytes(0x0f, 0x27, 0x00), response.copyOfRange(17, 20))
    }

    @Test
    fun makeStartEpilogueNewFirmware() {
        val calendar = Calendar.getInstance()
        val response = Solvers.makeStartEpilogue(deviceName, isKeyAuthPresent = true)

        assertArrayEquals(bytes(0xfe, 0xfe, 0x09, 0xb2, 0x01), response.copyOfRange(0, 5))

        val checksum = (response[5].toInt() and 0xFF) or ((response[6].toInt() and 0xFF) shl 8)
        assertEquals(Algorithms.crc16changgong(deviceName.takeLast(5)), checksum)

        assertArrayEquals(bytes(0x0b, 0x00), response.copyOfRange(7, 9))

        val datetimeString = hex(response.copyOfRange(11, 17))
        assertEquals(formatDateToString(calendar), datetimeString)

        assertArrayEquals(bytes(0x0f, 0x27, 0x00), response.copyOfRange(17, 20))
    }

    @Test
    fun makeStartEpilogueOfflinebomb() {
        val calendar = Calendar.getInstance()
        val response = Solvers.makeStartEpilogueOfflinebomb()

        assertArrayEquals(
            bytes(0xfe, 0xfe, 0x09, 0xbb, 0x01, 0x01, 0x0d, 0x00, 0x50),
            response.copyOfRange(0, 9),
        )

        assertEquals(2, response.copyOfRange(9, 11).size)

        val datetimeString = hex(response.copyOfRange(11, 17))
        assertEquals(formatDateToString(calendar), datetimeString)

        assertArrayEquals(bytes(0x00, 0x20, 0x00), response.copyOfRange(17, 20))
    }

    /**
     * The start epilogue is exactly 20 bytes and lays its fields out at fixed offsets, so a
     * regression in the concatenation order is caught here even without a controller.
     */
    @Test
    fun startEpilogueHasFixedLayout() {
        val response = Solvers.makeStartEpilogue("Water33982")
        assertEquals(20, response.size)
        assertEquals(0xFE, response[0].toInt() and 0xFF)
        assertEquals(0xFE, response[1].toInt() and 0xFF)
        assertEquals(0x09, response[2].toInt() and 0xFF)
        assertEquals(0xB2, response[3].toInt() and 0xFF)
        assertEquals(0x01, response[4].toInt() and 0xFF)
        assertEquals(0x00, response[8].toInt() and 0xFF)
    }
}
