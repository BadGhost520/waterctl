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
 * The frame parser has no counterpart upstream, so its behaviour is pinned down here.
 *
 * Every case an original `handleRxdNotifications` invocation handled has to keep working,
 * plus the split-notification cases that only show up on Android.
 */
class RxdFrameParserTest {

    private val parser = RxdFrameParser { }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun hex(bytes: ByteArray) = WaterUtils.bufferToHexString(bytes)

    @Test
    fun acceptsCompletelyWellFormedFrames() {
        val frames = parser.accept(bytes(0xfd, 0xfd, 0x09, 0xb0, 0x01, 0x01))
        assertEquals(1, frames.size)
        assertEquals("FDFD09B00101", hex(frames[0]))
    }

    @Test
    fun reinsertsTwoMissingLeadingFdBytes() {
        // The documented power-on shape: the first frame loses both leading 0xFD bytes.
        val frames = parser.accept(bytes(0x09, 0xb0, 0x01, 0x01))
        assertEquals(1, frames.size)
        assertEquals("FDFD09B00101", hex(frames[0]))
    }

    @Test
    fun doesNotInventBytesForAMisalignedFrame() {
        // `FD 09 B0 01 01` is not a shape the firmware can produce: the dropped bytes are
        // always the *first* ones, so a surviving `FD` in front of the `09` means the stream
        // is misaligned rather than truncated. The stray byte is discarded, not padded.
        val frames = parser.accept(bytes(0xfd, 0x09, 0xb0, 0x01, 0x01))
        assertTrue(frames.isEmpty())

        // Once resynchronised, a well-formed frame is still recognised.
        parser.reset()
        val good = parser.accept(bytes(0xfd, 0xfd, 0x09, 0xb0, 0x01, 0x01))
        assertEquals(1, good.size)
        assertEquals("FDFD09B00101", hex(good[0]))
    }

    @Test
    fun reassemblesAFrameSplitAcrossNotifications() {
        // An AF (unlock response) is 20 bytes and routinely arrives in two ATT packets.
        val first = bytes(0xfd, 0xfd, 0x09, 0xaf, 0x72, 0x00, 0x00, 0x06, 0xc3, 0x50)
        val second = bytes(0x9a, 0xc4, 0xfe, 0x87, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)

        assertTrue("must not emit a partial frame", parser.accept(first).isEmpty())

        val frames = parser.accept(second)
        assertEquals(1, frames.size)
        assertEquals("FDFD09AF72000006C3509AC4FE87000000000000", hex(frames[0]))
    }

    @Test
    fun reassemblesASplitFrameThatAlsoLostItsLeadingBytes() {
        assertTrue(parser.accept(bytes(0x09, 0xae, 0x38, 0x00)).isEmpty())
        val frames = parser.accept(
            bytes(0x00, 0x05, 0x95, 0x27, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x10, 0x11, 0x12)
        )
        assertEquals(1, frames.size)
        assertEquals("FDFD09AE38000005952703040506070809101112", hex(frames[0]))
    }

    @Test
    fun dropsTheSpuriousAtCommand() {
        val frames = parser.accept("AT+STAS?".toByteArray(Charsets.US_ASCII))
        assertTrue(frames.isEmpty())
    }

    @Test
    fun emitsTwoFramesFromOneNotification() {
        val frames = parser.accept(bytes(0xfd, 0xfd, 0x09, 0xb0, 0x01, 0x01, 0xfd, 0xfd, 0x09, 0xb2, 0x01, 0x01))
        assertEquals(2, frames.size)
        assertEquals("FDFD09B00101", hex(frames[0]))
        assertEquals("FDFD09B20101", hex(frames[1]))
    }

    @Test
    fun skipsLeadingGarbageAndStillFindsTheFrame() {
        val frames = parser.accept(bytes(0x12, 0x34, 0xfd, 0xfd, 0x09, 0xb0, 0x01, 0x01))
        assertEquals(1, frames.size)
        assertEquals("FDFD09B00101", hex(frames[0]))
    }

    @Test
    fun resetDropsPartialState() {
        assertTrue(parser.accept(bytes(0xfd, 0xfd, 0x09, 0xb0, 0x01)).isEmpty())
        parser.reset()
        val frames = parser.accept(bytes(0xfd, 0xfd, 0x09, 0xb0, 0x01, 0x01))
        assertEquals(1, frames.size)
        assertEquals("FDFD09B00101", hex(frames[0]))
    }

    @Test
    fun tenByteTypesAreDispatchedOnArrival() {
        // B3 is the end-prologue acknowledgement; the original reads payload[3] only.
        val frames = parser.accept(bytes(0xfd, 0xfd, 0x09, 0xb3, 0x00, 0x00, 0x01, 0x02, 0x03, 0x04))
        assertEquals(1, frames.size)
        assertEquals(0xb3, frames[0][3].toInt() and 0xFF)
    }

    @Test
    fun unknownTypesAreNotEmitted() {
        // 0x77 is not a data type the controller is known to send, so no frame is produced.
        val frames = parser.accept(bytes(0xfd, 0xfd, 0x09, 0x77, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
        assertTrue(frames.isEmpty())
    }

    @Test
    fun skipsAnUnknownTypeAndStillFindsTheNextFrame() {
        // A single continuous stream: an unknown type followed by a good frame. Feeding it one
        // byte at a time also exercises the most fragmented case BLE can produce.
        val stream = bytes(
            0xfd, 0xfd, 0x09, 0x77, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0xfd, 0xfd, 0x09, 0xb0, 0x01, 0x01,
        )

        val emitted = ArrayList<ByteArray>()
        for (byte in stream) {
            emitted.addAll(parser.accept(byteArrayOf(byte)))
        }

        assertEquals(1, emitted.size)
        assertEquals("FDFD09B00101", hex(emitted[0]))
    }
}
