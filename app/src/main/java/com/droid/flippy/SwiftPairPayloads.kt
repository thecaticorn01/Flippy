package com.droid.flippy

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * Microsoft Swift Pair advertisement payload builders (shared by the
 * whole-mode [SwiftPairSpam] and the per-item [SwiftPairSingleSpam]).
 *
 * Wire format is manufacturer-specific data under Microsoft's company ID
 * `0x0006`: a short header + UTF-8 display name, which is what Windows
 * shows in the "Connect via Swift Pair" notification.
 *
 * Legacy budget: a legacy advertising PDU carries at most 31 bytes, and
 * our Apple fast path already proves a 27-byte manufacturer payload goes
 * out fine via `startAdvertising`. So every payload here MUST stay within
 * 27 bytes of manufacturer data (the headphone header is 11 bytes, which
 * is why headphone names are cut to 16 bytes — the old `take(18)` built
 * 29-byte payloads that fail `startAdvertising` with DATA_TOO_LARGE and
 * die silently while the UI still shows "active").
 */
internal object SwiftPairPayloads {

    const val COMPANY_ID = 0x0006

    /** Max manufacturer-data bytes that still fit a legacy 31-byte PDU. */
    const val MAX_LEGACY_MANUFACTURER_BYTES = 27

    private val NORMAL_HEADER = byteArrayOf(
        0x03,
        0x00,
        0x80.toByte()
    )

    private val HEADPHONE_HEADER = byteArrayOf(
        0x03,
        0x01,
        0x80.toByte(),
        0xD7.toByte(), 0x2F, 0xD2.toByte(),
        0xF4.toByte(), 0x61, 0xE4.toByte(),
        0x04, 0x04, 0x00
    )

    fun normalPayload(displayName: String): ByteArray =
        build(NORMAL_HEADER, displayName)

    fun headphonePayload(displayName: String): ByteArray =
        build(HEADPHONE_HEADER, displayName)

    private fun build(header: ByteArray, displayName: String): ByteArray {
        val maxNameBytes = MAX_LEGACY_MANUFACTURER_BYTES - header.size
        val out = ByteArrayOutputStream(header.size + maxNameBytes)
        out.write(header)
        out.write(truncateUtf8(displayName, maxNameBytes).toByteArray(StandardCharsets.UTF_8))
        return out.toByteArray()
    }

    /** Truncates to a byte budget without splitting a multi-byte char. */
    internal fun truncateUtf8(s: String, maxBytes: Int): String {
        if (s.toByteArray(StandardCharsets.UTF_8).size <= maxBytes) return s
        var end = minOf(s.length, maxBytes)
        while (end > 0 && s.substring(0, end).toByteArray(StandardCharsets.UTF_8).size > maxBytes) {
            end--
        }
        return s.substring(0, end)
    }
}
