package com.droid.flippy.ir.usb

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.os.SystemClock
import android.util.Log

/**
 * Tiqiaa / Tview / ZaZaRemote bulk protocol ("ST" framing).
 *
 * Adapted from iodn/android-ir-blaster `UsbProtocolFormatter` (GPL-3.0).
 *
 * Wire format per frame:
 *   [0x02][len][e][total][index][payload…]
 * where payload is `ST <seq> D 0x00 <RLE body> E N`, fragmented into
 * chunks of at most 0x38 bytes. RLE body: each mark/space duration is
 * divided into 16 µs units, split into 7-bit chunks; mark chunks carry
 * the 0x80 bit.
 */
object TiqiaaUsbProtocol : UsbWireProtocol {
    override val name: String = "legacy_bulk_st"
    override val strictHandshake: Boolean = false
    override val wantsBackgroundReader: Boolean = true
    override val interFrameDelayMs: Long = 0L

    private const val TAG = "TiqiaaUsbProtocol"
    private const val MAX_CHUNK = 0x38

    private var e: Int = 1
    private var f: Int = 0

    @Synchronized
    private fun nextE(): Byte {
        e = if (e < 0x0F) (e + 1) else 0x01
        return e.toByte()
    }

    @Synchronized
    private fun nextF(): Byte {
        f = if (f < 0x7F) (f + 1) else 0x01
        return f.toByte()
    }

    override fun openHandshake(
        connection: UsbDeviceConnection,
        inEndpoint: UsbEndpoint,
        outEndpoint: UsbEndpoint
    ): Boolean {
        return try {
            // Drain any stale inbound data first.
            val tmp = ByteArray(maxOf(inEndpoint.maxPacketSize, 64))
            while (true) {
                val r = connection.bulkTransfer(inEndpoint, tmp, tmp.size, 10)
                if (r <= 0) break
            }
            val eVal = nextE()
            val fVal = nextF()
            val frame = byteArrayOf(
                0x02, 0x09, eVal, 0x01, 0x01,
                0x53, 0x54, fVal, 0x53, 0x45, 0x4E
            )
            val rc = connection.bulkTransfer(outEndpoint, frame, frame.size, 250)
            if (rc <= 0) return false
            // Best-effort drain of the handshake reply; absence is not fatal.
            val deadline = SystemClock.uptimeMillis() + 250L
            while (SystemClock.uptimeMillis() < deadline) {
                val r = connection.bulkTransfer(inEndpoint, tmp, tmp.size, 20)
                if (r <= 0) break
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "openHandshake failed: ${t.message}")
            false
        }
    }

    override fun encode(frequencyHz: Int, patternUs: IntArray): List<ByteArray> {
        // Carrier is fixed by this dongle family's firmware; kept in the
        // signature for interface uniformity.
        return encode(normalizePattern(patternUs))
    }

    override fun postTransmitDelayMs(patternUs: IntArray): Long {
        var totalUs = 0L
        for (v in patternUs) totalUs += v.toLong()
        return (totalUs.coerceAtLeast(0L) / 1000L) + 2L
    }

    /**
     * Dongle quirk: for even-length patterns (ending with a space) the final
     * gap must be shortened, otherwise the tail is swallowed. Odd-length
     * patterns (trailing mark) are passed through untouched.
     */
    internal fun normalizePattern(input: IntArray): IntArray {
        if (input.isEmpty()) return input
        if (input.size % 2 != 0) return input
        val out = input.copyOf()
        val last = out[out.lastIndex]
        out[out.lastIndex] = if (last > 3000) (last - 3000) else 10
        return out
    }

    private fun encode(patternUs: IntArray): List<ByteArray> {
        val payload = ByteArrayOutput()
        payload.write(0x53) // 'S'
        payload.write(0x54) // 'T'
        payload.write(nextF().toInt() and 0xFF)
        payload.write(0x44) // 'D'
        payload.write(0x00)
        encodeBodyInto(payload, patternUs)
        payload.write(0x45) // 'E'
        payload.write(0x4E) // 'N'

        val payloadBytes = payload.toByteArray()
        val total = ((payloadBytes.size + MAX_CHUNK - 1) / MAX_CHUNK).coerceAtLeast(1)
        val eVal = nextE()
        val frames = ArrayList<ByteArray>(total)
        var offset = 0
        var index = 1
        while (offset < payloadBytes.size) {
            val take = minOf(MAX_CHUNK, payloadBytes.size - offset)
            val frame = ByteArray(5 + take)
            frame[0] = 0x02
            frame[1] = (take + 3).toByte()
            frame[2] = eVal
            frame[3] = total.toByte()
            frame[4] = index.toByte()
            System.arraycopy(payloadBytes, offset, frame, 5, take)
            frames.add(frame)
            offset += take
            index++
        }
        return frames
    }

    private fun encodeBodyInto(out: ByteArrayOutput, patternUs: IntArray) {
        for (i in patternUs.indices) {
            var units = patternUs[i] / 16
            if (units <= 0) units = 1
            val isMark = (i % 2 == 0)
            while (units > 0) {
                val chunk = minOf(units, 0x7F)
                units -= chunk
                var b = chunk
                if (isMark) b = b or 0x80
                out.write(b)
            }
        }
    }

    internal class ByteArrayOutput {
        private var buf = ByteArray(256)
        private var size = 0

        fun write(v: Int) {
            ensure(1)
            buf[size++] = v.toByte()
        }

        fun toByteArray(): ByteArray = buf.copyOf(size)

        private fun ensure(n: Int) {
            val need = size + n
            if (need <= buf.size) return
            var newCap = buf.size * 2
            while (newCap < need) newCap *= 2
            buf = buf.copyOf(newCap)
        }
    }
}
