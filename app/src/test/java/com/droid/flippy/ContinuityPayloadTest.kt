package com.droid.flippy

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Validates Apple Continuity payload shapes.
 *
 * iOS silently ignores malformed frames, so the length byte must match the
 * bytes that follow (e.g. `07 19` must be followed by exactly 25 bytes).
 * A length mismatch here once made the whole "Device" list do nothing on
 * iPhones while the correctly-sized Action Modal frames worked.
 */
class ContinuityPayloadTest {

    private fun u(b: Byte): Int = b.toInt() and 0xFF

    @Test
    fun devicePayload_isLengthHonest() {
        val payload = ContinuityPayloads.devicePayload("0E20", Random(0))
        // 07 19 + 25 bytes: prefix(1) model(2) status(1) buds(1)
        // charging(1) lid(1) color(1) zero(1) tail(16).
        assertEquals(27, payload.size)
        assertEquals(0x07, u(payload[0]))
        assertEquals(0x19, u(payload[1]))
        assertEquals(0x01, u(payload[2]))
        assertEquals(0x0E, u(payload[3]))
        assertEquals(0x20, u(payload[4]))
        // Declared length matches the bytes that follow the header.
        assertEquals(u(payload[1]), payload.size - 2)
    }

    @Test
    fun devicePayload_airTagUses05Prefix() {
        val payload = ContinuityPayloads.devicePayload("0055", Random(0))
        assertEquals(27, payload.size)
        assertEquals(0x07, u(payload[0]))
        assertEquals(0x19, u(payload[1]))
        assertEquals(0x05, u(payload[2]))
        assertEquals(0x00, u(payload[3]))
        assertEquals(0x55, u(payload[4]))
    }

    @Test
    fun devicePayload_variesPerCall() {
        val first = ContinuityPayloads.devicePayload("0E20", Random(1))
        val second = ContinuityPayloads.devicePayload("0E20", Random(2))
        assertEquals(27, first.size)
        assertEquals(27, second.size)
        assertTrue("payloads should be randomized", !first.contentEquals(second))
    }

    @Test
    fun proximityPairPayload_shapes() {
        val notYours = ContinuityPayloads.proximityPairPayload("01", "0E20", "00", Random(0))
        assertEquals(27, notYours.size)
        assertEquals(0x07, u(notYours[0]))
        assertEquals(0x19, u(notYours[1]))
        assertEquals(0x01, u(notYours[2]))
        assertEquals(u(notYours[1]), notYours.size - 2)

        val airTag = ContinuityPayloads.proximityPairPayload("01", "0055", "00", Random(0))
        assertEquals(27, airTag.size)
        // AirTags are forced onto the 05 prefix.
        assertEquals(0x05, u(airTag[2]))
        assertArrayEquals(
            byteArrayOf(0x00, 0x55),
            byteArrayOf(airTag[3], airTag[4])
        )
    }

    @Test
    fun nearbyActionPayload_shapes() {
        val action = ContinuityPayloads.nearbyActionPayload("01", false, Random(0))
        // 0F 05 + flag(1) action(1) auth(3) = 7 bytes.
        assertEquals(7, action.size)
        assertEquals(0x0F, u(action[0]))
        assertEquals(0x05, u(action[1]))
        assertEquals(0xC0, u(action[2]))
        assertEquals(0x01, u(action[3]))
    }

    @Test
    fun nearbyActionCrashPayload_isOversizedOnPurpose() {
        val crash = ContinuityPayloads.nearbyActionPayload("01", true, Random(0))
        // The 6 extra bytes are intentional: the malformed oversized frame is
        // what used to crash old unpatched iPhones. Modern iOS ignores it.
        assertEquals(13, crash.size)
        assertEquals(0x0F, u(crash[0]))
        assertEquals(0x05, u(crash[1]))
    }
}
