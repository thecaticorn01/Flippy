package com.droid.flippy

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SwiftPairPayloadTest {

    @Test
    fun normalPayload_shape() {
        val payload = SwiftPairPayloads.normalPayload("Test Mouse")
        assertTrue(
            "normal payload must fit legacy 31-byte PDU, was ${payload.size}",
            payload.size <= SwiftPairPayloads.MAX_LEGACY_MANUFACTURER_BYTES
        )
        assertArrayEquals(
            byteArrayOf(0x03, 0x00, 0x80.toByte()),
            payload.copyOfRange(0, 3)
        )
    }

    @Test
    fun headphonePayload_shape() {
        val payload = SwiftPairPayloads.headphonePayload("BT Headset")
        assertTrue(
            "headphone payload must fit legacy 31-byte PDU, was ${payload.size}",
            payload.size <= SwiftPairPayloads.MAX_LEGACY_MANUFACTURER_BYTES
        )
        assertArrayEquals(
            byteArrayOf(
                0x03, 0x01, 0x80.toByte(),
                0xD7.toByte(), 0x2F, 0xD2.toByte(),
                0xF4.toByte(), 0x61, 0xE4.toByte(),
                0x04, 0x04, 0x00
            ),
            payload.copyOfRange(0, 12)
        )
    }

    @Test
    fun allSpamNames_fitLegacy() {
        val spam = SwiftPairSpam()
        for (name in spam.normalNames) {
            val payload = SwiftPairPayloads.normalPayload(name)
            assertTrue(
                "normal '$name' -> ${payload.size} bytes, exceeds legacy budget",
                payload.size <= SwiftPairPayloads.MAX_LEGACY_MANUFACTURER_BYTES
            )
        }
        for (name in spam.headphoneNames) {
            val payload = SwiftPairPayloads.headphonePayload(name)
            assertTrue(
                "headphone '$name' -> ${payload.size} bytes, exceeds legacy budget",
                payload.size <= SwiftPairPayloads.MAX_LEGACY_MANUFACTURER_BYTES
            )
        }
    }
}
