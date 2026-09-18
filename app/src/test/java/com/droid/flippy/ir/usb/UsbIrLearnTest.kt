package com.droid.flippy.ir.usb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validates ElkSmart IR-learn decoding (pure JVM, no dongle needed).
 *
 * Reference: byte < 0xFF contributes b*16 µs; 0xFF adds 4080 to a carry
 * consumed by the next value byte. Messages carry an FE FE FE FE echo plus
 * a big-endian body length.
 */
class UsbIrLearnTest {

    private val protocol = ElkSmartUsbProtocol()

    @Test
    fun decodeRawTimings_basic() {
        // 0x23 = 35 -> 560µs, 0x6A = 106 -> 1696µs.
        assertArrayEquals(
            intArrayOf(560, 1696),
            protocol.decodeRawTimings(byteArrayOf(0x23, 0x6A.toByte()))
        )
    }

    @Test
    fun decodeRawTimings_carryOverflow() {
        // 0xFF banks 4080, resolved by the next value byte.
        assertArrayEquals(
            intArrayOf(4080),
            protocol.decodeRawTimings(byteArrayOf(0xFF.toByte(), 0x00))
        )
        assertArrayEquals(
            intArrayOf(4080 + 4080 + 16),
            protocol.decodeRawTimings(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0x01))
        )
    }

    @Test
    fun decodeRawTimings_empty() {
        assertEquals(0, protocol.decodeRawTimings(byteArrayOf()).size)
    }

    @Test
    fun decodeLearnedMessage_valid() {
        val message = byteArrayOf(
            0xFE.toByte(), 0xFE.toByte(), 0xFE.toByte(), 0xFE.toByte(),
            0x00, 0x02,
            0x23, 0x6A.toByte()
        )
        val pattern = protocol.decodeLearnedMessage(message)
        assertTrue(pattern != null)
        assertArrayEquals(intArrayOf(560, 1696), pattern)
    }

    @Test
    fun decodeLearnedMessage_rejectsGarbage() {
        // Wrong echo.
        assertNull(
            protocol.decodeLearnedMessage(
                byteArrayOf(0xFC.toByte(), 0xFC.toByte(), 0xFC.toByte(), 0xFC.toByte(), 0x00, 0x02, 0x23, 0x6A.toByte())
            )
        )
        // Truncated header.
        assertNull(protocol.decodeLearnedMessage(byteArrayOf(0xFE.toByte(), 0xFE.toByte())))
        // Declared length exceeds the body.
        assertNull(
            protocol.decodeLearnedMessage(
                byteArrayOf(0xFE.toByte(), 0xFE.toByte(), 0xFE.toByte(), 0xFE.toByte(), 0x00, 0x10, 0x23)
            )
        )
    }
}
