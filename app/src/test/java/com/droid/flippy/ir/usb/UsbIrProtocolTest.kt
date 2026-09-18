package com.droid.flippy.ir.usb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the USB IR wire encoders.
 *
 * These cover [TiqiaaUsbProtocol] and [ElkSmartUsbProtocol] message framing
 * against the Ocrustar/ElkSmart protocol reference (mangle vectors, checksum
 * rule, 62+1 framing, LEB128 prescale, pulse-compression header order) so
 * regressions are caught without physical dongles attached.
 *
 * Only [UsbWireProtocol.encode] and the internal helpers are exercised here;
 * the bulk-transfer handshake paths need a real [android.hardware.usb]
 * connection and are intentionally not touched (Android stubs throw on JVM).
 */
class UsbIrProtocolTest {

    private fun u(b: Byte): Int = b.toInt() and 0xFF

    // ---- ElkSmart byte mangling (reference: mangle(0x38) == 0xE3) ----

    @Test
    fun mangle_referenceVector() {
        assertEquals(0xE3, u(ElkSmartUsbProtocol.mangleByte(0x38)))
    }

    @Test
    fun mangle_edgeCases() {
        assertEquals(0xFF, u(ElkSmartUsbProtocol.mangleByte(0x00)))
        assertEquals(0x00, u(ElkSmartUsbProtocol.mangleByte(0xFF)))
    }

    @Test
    fun mangle_isInvolution() {
        for (v in 0..255) {
            assertEquals(v, u(ElkSmartUsbProtocol.mangleByte(u(ElkSmartUsbProtocol.mangleByte(v)))))
        }
    }

    @Test
    fun checksum62_allZeros() {
        // sum == 0 -> raw == 0 -> mangle(0) == 0xFF.
        assertEquals(0xFF, u(ElkSmartUsbProtocol.checksum62(ByteArray(62))))
    }

    @Test
    fun checksum62_matchesRule() {
        val buf = ByteArray(62) { it.toByte() }
        var sum = 0
        for (i in 0 until 62) sum += i
        val raw = (sum and 0xF0) or ((sum ushr 8) and 0x0F)
        assertEquals(
            u(ElkSmartUsbProtocol.mangleByte(raw)),
            u(ElkSmartUsbProtocol.checksum62(buf))
        )
    }

    // ---- ElkSmart value compression ----

    @Test
    fun compressValue_prescaleSmallValues() {
        // Reference prescale examples: 560µs -> 35 (0x23), 1690µs -> 106 (0x6A).
        val proto = ElkSmartUsbProtocol()
        val out = ElkSmartUsbProtocol.ByteArrayOutput()
        proto.compressValueUs(560, out)
        proto.compressValueUs(1690, out)
        assertArrayEquals(byteArrayOf(0x23, 0x6A), out.toByteArray())
    }

    @Test
    fun compressValue_dictionaryIndicesPassThrough() {
        val proto = ElkSmartUsbProtocol()
        val out = ElkSmartUsbProtocol.ByteArrayOutput()
        proto.compressValueUs(0, out)
        proto.compressValueUs(1, out)
        assertArrayEquals(byteArrayOf(0x00, 0x01), out.toByteArray())
    }

    @Test
    fun compressValue_largeValueLeb128() {
        // 40000 = 0x9C40 -> LEB128 groups LE with continuation bits:
        // 0x40|0x80, 0x38|0x80, 0x02.
        val proto = ElkSmartUsbProtocol()
        val out = ElkSmartUsbProtocol.ByteArrayOutput()
        proto.compressValueUs(40000, out)
        assertArrayEquals(
            byteArrayOf(0xC0.toByte(), 0xB8.toByte(), 0x02),
            out.toByteArray()
        )
    }

    @Test
    fun compressValue_neverEmitsSeparator() {
        val proto = ElkSmartUsbProtocol()
        for (v in listOf(2033, 4096, 16383, 16384, 40000, 100000, 200000)) {
            val out = ElkSmartUsbProtocol.ByteArrayOutput()
            proto.compressValueUs(v, out)
            for (b in out.toByteArray()) {
                assertTrue("value $v emitted reserved 0xFF", u(b) != 0xFF)
            }
        }
    }

    // ---- ElkSmart pulse handling ----

    @Test
    fun toPulses_oddPatternGetsTrailingGap() {
        val proto = ElkSmartUsbProtocol()
        val pulses = proto.toPulses(intArrayOf(9000, 4500, 560))
        assertEquals(2, pulses.size)
        assertEquals(ElkSmartUsbProtocol.Pulse(9000, 4500), pulses[0])
        assertEquals(ElkSmartUsbProtocol.Pulse(560, 10_000), pulses[1])
    }

    @Test
    fun toPulses_empty() {
        assertTrue(ElkSmartUsbProtocol().toPulses(intArrayOf()).isEmpty())
    }

    @Test
    fun compressPulses_referenceHeaderOrder() {
        // Worked NEC example: top pairs (560,560) x3 and (560,1690) x1.
        // Header must be: longer pair first, shorter second, then FF FF FF.
        val proto = ElkSmartUsbProtocol()
        val pulses = listOf(
            ElkSmartUsbProtocol.Pulse(560, 560),
            ElkSmartUsbProtocol.Pulse(560, 560),
            ElkSmartUsbProtocol.Pulse(560, 560),
            ElkSmartUsbProtocol.Pulse(560, 1690)
        )
        val compressed = proto.compressPulses(pulses)
        val header = compressed.copyOf(7)
        assertArrayEquals(
            byteArrayOf(0x23, 0x6A, 0x23, 0x23, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()),
            header
        )
        // Body: three short-pair refs then one long-pair ref.
        val body = compressed.copyOfRange(7, compressed.size)
        assertArrayEquals(byteArrayOf(0x00, 0x00, 0x00, 0x01), body)
    }

    // ---- ElkSmart message framing ----

    @Test
    fun encode_startsWithPreamble() {
        val frames = ElkSmartUsbProtocol().encode(38000, intArrayOf(9000, 4500, 560, 560))
        assertTrue(frames.isNotEmpty())
        val first = frames.first()
        assertEquals(0xFF, u(first[0]))
        assertEquals(0xFF, u(first[1]))
        assertEquals(0xFF, u(first[2]))
        assertEquals(0xFF, u(first[3]))
    }

    @Test
    fun encode_lengthFieldsMatchPayload() {
        val proto = ElkSmartUsbProtocol()
        val frames = proto.encode(38000, intArrayOf(9000, 4500, 560, 1690, 560, 560))
        // Reassemble the message the way the framing splits it.
        val reassembled = ArrayList<Byte>()
        for ((index, frame) in frames.withIndex()) {
            if (frame.size == 63 && index != frames.lastIndex) {
                // Full frame: 62 data bytes + checksum.
                assertEquals(
                    u(ElkSmartUsbProtocol.checksum62(frame.copyOf(62))),
                    u(frame[62])
                )
                frame.copyOf(62).forEach { reassembled.add(it) }
            } else {
                frame.forEach { reassembled.add(it) }
            }
        }
        val msg = reassembled.toByteArray()
        assertTrue(msg.size > 9)
        // mangle is an involution, so applying it again decodes.
        val lenHi = u(ElkSmartUsbProtocol.mangleByte(u(msg[7])))
        val lenLo = u(ElkSmartUsbProtocol.mangleByte(u(msg[8])))
        val payloadLen = (lenHi shl 8) or lenLo
        assertEquals(msg.size - 9, payloadLen)
    }

    @Test
    fun encode_longSignalUsesChecksummedFrames() {
        // A long raw pattern must span several 63-byte (62+checksum) frames.
        val pattern = IntArray(400) { if (it % 2 == 0) 560 else 1690 }
        val frames = ElkSmartUsbProtocol().encode(38000, pattern)
        assertTrue("expected multiple frames, got ${frames.size}", frames.size > 1)
        for (i in 0 until frames.size - 1) {
            assertEquals(63, frames[i].size)
            assertEquals(
                u(ElkSmartUsbProtocol.checksum62(frames[i].copyOf(62))),
                u(frames[i][62])
            )
        }
        assertTrue(frames.last().size <= 62)
    }

    @Test
    fun encode_isDeterministicForD552() {
        val proto = ElkSmartUsbProtocol()
        val pattern = intArrayOf(9000, 4500, 560, 1690, 560, 560, 560, 1690)
        val first = proto.encode(38000, pattern).map { it.copyOf() }
        val second = proto.encode(38000, pattern).map { it.copyOf() }
        assertEquals(first.size, second.size)
        for (i in first.indices) assertArrayEquals(first[i], second[i])
    }

    // ---- Tiqiaa protocol ----

    @Test
    fun tiqiaa_normalizeTailGap() {
        assertArrayEquals(
            intArrayOf(1000, 2000, 500, 37000),
            TiqiaaUsbProtocol.normalizePattern(intArrayOf(1000, 2000, 500, 40000))
        )
        assertArrayEquals(
            intArrayOf(1000, 2000, 500, 10),
            TiqiaaUsbProtocol.normalizePattern(intArrayOf(1000, 2000, 500, 2000))
        )
    }

    @Test
    fun tiqiaa_normalizeLeavesOddAndEmptyAlone() {
        assertArrayEquals(intArrayOf(9000, 4500, 560), TiqiaaUsbProtocol.normalizePattern(intArrayOf(9000, 4500, 560)))
        assertArrayEquals(intArrayOf(), TiqiaaUsbProtocol.normalizePattern(intArrayOf()))
    }

    @Test
    fun tiqiaa_framesHaveHeaderAndBounds() {
        val frames = TiqiaaUsbProtocol.encode(38000, intArrayOf(9000, 4500, 560, 1690, 560, 560))
        assertTrue(frames.isNotEmpty())
        for (frame in frames) {
            assertEquals(0x02, u(frame[0]))
            assertTrue("frame too large: ${frame.size}", frame.size <= 5 + 0x38)
        }
        // Payload of the first frame starts with 'S','T'.
        val first = frames.first()
        assertEquals(0x53, u(first[5]))
        assertEquals(0x54, u(first[6]))
    }

    @Test
    fun tiqiaa_markBytesCarryHighBit() {
        // [160µs mark, 4000µs space] -> tail normalized to 4000-3000=1000µs:
        // 10 units mark (0x8A), 62 units space (0x3E).
        val frames = TiqiaaUsbProtocol.encode(38000, intArrayOf(160, 4000))
        val body = ArrayList<Byte>()
        for (frame in frames) {
            // Strip 5-byte frame header; first frame payload starts with 5 header bytes.
            val payload = frame.copyOfRange(5, frame.size)
            payload.forEach { body.add(it) }
        }
        val bytes = body.toByteArray()
        // Header: S T <seq> D 0x00, then body, then E N.
        assertEquals(0x53, u(bytes[0]))
        assertEquals(0x54, u(bytes[1]))
        assertEquals(0x44, u(bytes[3]))
        assertEquals(0x00, u(bytes[4]))
        assertEquals(0x8A, u(bytes[5]))
        assertEquals(0x3E, u(bytes[6]))
        assertEquals(0x45, u(bytes[bytes.size - 2]))
        assertEquals(0x4E, u(bytes[bytes.size - 1]))
    }

    @Test
    fun tiqiaa_longPatternFragments() {
        val pattern = IntArray(600) { if (it % 2 == 0) 560 else 1690 }
        val frames = TiqiaaUsbProtocol.encode(38000, pattern)
        assertTrue("expected fragmentation, got ${frames.size} frame(s)", frames.size > 1)
        // Sequence indices increment across frames.
        for (i in frames.indices) {
            assertEquals(i + 1, u(frames[i][4]))
        }
    }
}
