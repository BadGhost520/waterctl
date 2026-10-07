/*
 * This file is part of waterctl-android, a Kotlin port of waterctl
 * Copyright (c) 2021-2024 celesWuff
 *
 * Released under the MIT license. See the LICENSE file of the original project
 * <https://github.com/celesWuff/waterctl> for the full text.
 */
package com.badghost.watercontrol.protocol

/**
 * Reassembles controller -> app frames from raw RXD notifications.
 *
 * This is the one place where the Android port deliberately does *more* than the original
 * `handleRxdNotifications`, because BLE notification fragmentation is much more visible on
 * Android than in a browser. The observable protocol behaviour is unchanged: the same bytes
 * produce the same dispatch decisions.
 *
 * Two independent firmware quirks have to be survived:
 *
 * 1. **Split notifications.** A single logical frame may arrive as several notifications.
 *    They are accumulated here, and a frame is only emitted once its full, known length has
 *    arrived. Because the reply to `AE` is dispatched by reading fixed offsets out of it, a
 *    truncated frame would produce a wrong unlock response, so completeness matters.
 *
 * 2. **Dropped leading `0xFD` bytes.** A frame normally starts with `FD FD 09`, but the
 *    firmware sometimes emits it with one or two leading `FD` bytes missing. The original
 *    documents this as the power-on state `FD FD FD 09 ...` losing bytes, and detects it by
 *    looking only at the first bytes of a notification. This parser instead tries the
 *    reconstructions that yield a well-formed frame, which accepts every input the original
 *    accepted and stays correct when notifications are split. A reconstruction is only
 *    attempted when the buffer actually starts with the `0x09` those bytes preceded;
 *    otherwise every frame would look like a truncated one.
 *
 * Frames that are obviously junk (for example the spurious `AT+STAS?` that a firmware bug
 * sends, which does not start with `FD FD 09`) are dropped.
 */
class RxdFrameParser(private val log: (String) -> Unit) {

    private val buffer = ArrayList<Byte>(64)

    companion object {
        private const val MAX_BUFFER = 512

        /** A single notification should never carry more than a couple of frames. */
        private const val MAX_FRAMES_PER_NOTIFICATION = 8

        /** The most leading `0xFD` bytes the firmware has been observed to drop. */
        private const val MAX_REPAIR = 2
        private const val FD = 0xFD
        private const val TYPE_START = 0x09
        private const val AT_0 = 0x41 // 'A'
        private const val AT_1 = 0x54 // 'T'
        private const val AT_2 = 0x2B // '+'

        /**
         * Total length of every frame the controller is known to send, keyed by the data type
         * byte at offset 3.
         *
         * The lengths come from the frames the original parses and from the frames this app
         * itself sends in reply:
         *
         * - `B0`/`B1`/`B2` -- 6 bytes, the start-prologue reply and the start acknowledgement.
         * - `B4`/`BA`/`BC` -- 8 bytes, the same shape as [Payloads.endEpilogue] and friends.
         * - `AA`/`B3`/`B5`/`B8`/`C8` -- 10 bytes, the same shape as [Payloads.endPrologue].
         * - `AE`/`AF` -- 20 bytes, the key authentication request and its response; upstream's
         *   own test vectors are 20 bytes long.
         *
         * A type that is not listed is treated as unparseable rather than guessed at, so no
         * truncated frame can ever be dispatched.
         */
        private val EXACT_LENGTHS = mapOf(
            0xB0 to 6,
            0xB1 to 6,
            0xB2 to 6,
            0xB4 to 8,
            0xBA to 8,
            0xBC to 8,
            0xAA to 10,
            0xB3 to 10,
            0xB5 to 10,
            0xB8 to 10,
            0xC8 to 10,
            0xAE to 20,
            0xAF to 20,
        )
    }

    /** Feeds one raw notification and returns every complete frame it completes. */
    fun accept(notification: ByteArray): List<ByteArray> {
        val frames = ArrayList<ByteArray>(1)
        if (notification.isEmpty()) return frames

        // A notification that is exactly the spurious AT command is dropped outright.
        if (looksLikeAtCommand(notification)) {
            log("RXD: dropping spurious AT command (${WaterUtils.bufferToHexString(notification)})")
            return frames
        }

        for (byte in notification) buffer.add(byte)

        if (buffer.size > MAX_BUFFER) {
            log("RXD: buffer overflow (${buffer.size} bytes), resetting")
            buffer.clear()
            return frames
        }

        // `tryExtract` either makes progress (emits a frame or sheds a byte) or reports that
        // the buffer ends mid-frame, so this loop always terminates.
        while (frames.size < MAX_FRAMES_PER_NOTIFICATION) {
            frames.add(tryExtract() ?: break)
        }
        return frames
    }

    /** Drops any partial state, for example when a session is torn down. */
    fun reset() {
        buffer.clear()
    }

    private fun looksLikeAtCommand(bytes: ByteArray): Boolean =
        bytes.size >= 3 &&
            (bytes[0].toInt() and 0xFF) == AT_0 &&
            (bytes[1].toInt() and 0xFF) == AT_1 &&
            (bytes[2].toInt() and 0xFF) == AT_2

    private fun byteAt(index: Int): Int = buffer[index].toInt() and 0xFF

    /** The data type byte of a frame whose first [skipped] leading bytes were dropped. */
    private fun typeAt(skipped: Int): Int? = (3 - skipped).takeIf { it in buffer.indices }?.let { byteAt(it) }

    /**
     * The total length of the frame that [typeAt] reported, or `null` when the type is unknown.
     */
    private fun lengthOf(skipped: Int): Int? {
        if (buffer.size + skipped < 4) return null
        return EXACT_LENGTHS[typeAt(skipped) ?: return null]
    }

    /** Whether the buffer starts with `FD FD 09 <known type>` exactly as it stands. */
    private fun hasVerbatimHeader(): Boolean =
        buffer.size >= 4 &&
            byteAt(0) == FD &&
            byteAt(1) == FD &&
            byteAt(2) == TYPE_START &&
            byteAt(3) in EXACT_LENGTHS

    private fun tryExtract(): ByteArray? {
        // Discard leading bytes that cannot begin a frame. The original simply ignored an
        // unknown first byte and waited for the next notification; discarding is equivalent
        // for well-formed traffic and strictly better when two frames get interleaved.
        while (buffer.isNotEmpty() && byteAt(0) != FD && byteAt(0) != TYPE_START) {
            buffer.removeAt(0)
        }
        if (buffer.size < 4) return null

        // 1. A frame that is already complete always wins: never invent bytes that are there.
        if (hasVerbatimHeader()) {
            val length = EXACT_LENGTHS.getValue(byteAt(3))
            if (buffer.size >= length) return consume(length, skipped = 0)
        }

        // 2. Otherwise the firmware may have dropped the leading 0xFD bytes, which is only
        //    possible when the buffer now starts with the 0x09 they used to precede.
        if (byteAt(0) == TYPE_START) {
            for (skipped in 1..MAX_REPAIR) {
                if (buffer.size + skipped < 4) break
                val length = lengthOf(skipped) ?: continue
                if (buffer.size >= length - skipped) return consume(length, skipped)
            }
        }

        // 3. Nothing parses yet. If a frame could still complete once more bytes arrive, wait
        //    for them; otherwise the leading byte can never start a valid frame, so shed it.
        //    Shedding is not a result, so the caller keeps going rather than stopping.
        if (!isAwaitingMoreBytes()) {
            buffer.removeAt(0)
        }
        return null
    }

    /**
     * Removes one frame from the front of the buffer.
     *
     * [skipped] leading `0xFD` bytes are synthesised because the firmware never sent them, so
     * only `length - skipped` bytes are actually consumed.
     */
    private fun consume(length: Int, skipped: Int): ByteArray {
        val frame = ByteArray(length)
        repeat(skipped) { frame[it] = FD.toByte() }
        for (i in skipped until length) frame[i] = buffer[i - skipped]
        repeat(length - skipped) { buffer.removeAt(0) }
        if (skipped > 0) {
            log("RXD: reassembled a frame with $skipped missing leading 0xFD byte(s)")
        }
        return frame
    }

    /**
     * Whether a complete frame of a known type could still be assembled from more bytes.
     *
     * Only a genuinely truncated frame counts. A four-byte header naming an unknown type is
     * *not* something to wait on -- no amount of extra data makes it parseable -- so it is
     * discarded instead.
     */
    private fun isAwaitingMoreBytes(): Boolean {
        if (hasVerbatimHeader() && buffer.size < EXACT_LENGTHS.getValue(byteAt(3))) return true

        if (byteAt(0) == TYPE_START) {
            for (skipped in 1..MAX_REPAIR) {
                if (buffer.size + skipped < 4) break
                val length = lengthOf(skipped) ?: continue
                if (buffer.size < length - skipped) return true
            }
        }
        return false
    }
}
