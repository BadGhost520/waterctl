/*
 * This file is part of waterctl-android, a Kotlin port of waterctl
 * Copyright (c) 2021-2024 celesWuff
 *
 * Released under the MIT license. See the LICENSE file of the original project
 * <https://github.com/celesWuff/waterctl> for the full text.
 */
package com.badghost.watercontrol.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Port of `src/algorithms.spec.ts` and `src/utils.spec.ts`, plus checks that pin down the
 * mapping between the JavaScript originals and their Kotlin equivalents.
 */
class AlgorithmsTest {

    @Test
    fun crc16changgongMatchesUpstreamVector() {
        // Verified against the upstream implementation for the device name used throughout
        // its test suite. The low byte ends up in a start epilogue at offset 5.
        assertEquals(0x9B39, Algorithms.crc16changgong("33982"))
        assertEquals(0x9B39, Algorithms.crc16changgong("Water33982".takeLast(5)))
        // The start prologue carries no checksum at all, which is worth pinning down.
        assertEquals(0x1017, Algorithms.crc16changgong(""))
    }

    @Test
    fun crc16cgaeafMatchesUpstreamVector() {
        // The checksum byte of the first `makeUnlockResponse` vector is 0x72; recomputing it
        // from that vector's checksum input proves both the CRC and the field layout.
        val checksumInput = byteArrayOf(
            0x00, 0x00, 0x06, 0xC3.toByte(), 0x50, 0x9A.toByte(), 0xC4.toByte(),
            0xFE.toByte(), 0x87.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        )
        val value = Algorithms.crc16cgaeaf(checksumInput)
        assertTrue("checksum must fit in one byte", value in 0..0xFF)
        assertEquals(0x72, value)
    }

    @Test
    fun decAsHexReinterpretsDecimalDigitsAsHex() {
        // The upstream doc comment: 42 => 0x42.
        assertEquals(0x42, WaterUtils.decAsHex(42))
        assertEquals(0x00, WaterUtils.decAsHex(0))
        assertEquals(0x09, WaterUtils.decAsHex(9))
        assertEquals(0x10, WaterUtils.decAsHex(10))
        assertEquals(0x99, WaterUtils.decAsHex(99))
        assertEquals(0x1234, WaterUtils.decAsHex(1234))
        assertEquals(0x9999, WaterUtils.decAsHex(9999))
        // Negative and zero inputs are clamped by the original's `n <= 0 ? 0` guard.
        assertEquals(0, WaterUtils.decAsHex(-5))
    }

    @Test
    fun bufferToHexStringUppercases() {
        // The upstream doc comment: [0x12, 0x34, 0xAB, 0xCD] => "1234ABCD".
        val actual = WaterUtils.bufferToHexString(
            byteArrayOf(0x12, 0x34, 0xAB.toByte(), 0xCD.toByte())
        )
        assertEquals("1234ABCD", actual)
        assertEquals("", WaterUtils.bufferToHexString(ByteArray(0)))
        assertEquals("00FF", WaterUtils.bufferToHexString(byteArrayOf(0x00, 0xFF.toByte())))
    }

    @Test
    fun payloadsMatchUpstream() {
        assertEquals("FEFE09B001010000", WaterUtils.bufferToHexString(Payloads.startPrologue))
        assertEquals("FEFE09B30000", WaterUtils.bufferToHexString(Payloads.endPrologue))
        assertEquals("FEFE09B40000", WaterUtils.bufferToHexString(Payloads.endEpilogue))
        assertEquals("FEFE09BC0000", WaterUtils.bufferToHexString(Payloads.offlinebombFix))
        assertEquals("FEFE09BA0000", WaterUtils.bufferToHexString(Payloads.baAck))
    }

    /**
     * The `deputy` table offsets are load-bearing, so the table's size and a few anchor bytes
     * are asserted directly. A wrong offset would otherwise only show up against hardware.
     */
    @Test
    fun deputyTableHasExpectedShape() {
        assertEquals(2096, DeputyTable.DATA.size)
        assertEquals(2096, DeputyTable.SIZE)
        assertEquals(0x00, DeputyTable.u8(0))
        assertEquals(0xC0, DeputyTable.u8(479))
        assertEquals(0x01, DeputyTable.u8(480))
        // The four index tables the key bytes are read from (1938+iA, 1808+iB, 2082+iC, 1952+iD).
        assertEquals(0xCF, DeputyTable.u8(1938))
        assertEquals(0x75, DeputyTable.u8(1808))
        assertEquals(0x31, DeputyTable.u8(2082))
        assertEquals(0xD4, DeputyTable.u8(1952))
    }
}
