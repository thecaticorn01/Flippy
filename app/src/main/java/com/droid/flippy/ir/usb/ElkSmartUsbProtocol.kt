package com.droid.flippy.ir.usb

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.os.SystemClock
import android.util.Log
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * ElkSmart / Ocrustar USB IR protocol (bulk, FC-handshake family).
 *
 * Adapted from iodn/android-ir-blaster `ElkSmartUsbProtocolFormatter`
 * (GPL-3.0); byte-level details cross-checked against the independent
 * reverse-engineering reference deadboy18/Ocrustar-USB-IR (MIT):
 *
 * - Transport: bulk OUT 0x01 / bulk IN 0x81, 64-byte max packets.
 * - Handshake: host sends `FC FC FC FC`, device answers
 *   `FC FC FC FC <typeHi> <typeLo>` with `70 01` (D552, pulse compression
 *   only) or `02 AA` (D226, pulse compression + Huffman).
 * - TX message: `FF FF FF FF` preamble, 3 mangled frequency bytes
 *   (`freq + 0x7FFFF`, order: bits 15:8, 23:16, 7:0), 2 mangled length
 *   bytes, encoded payload; split into 62-byte data frames each followed
 *   by a mangled checksum byte, except the final short frame.
 * - "Mangle" = bit-reverse then bitwise-invert each byte.
 */
class ElkSmartUsbProtocol : UsbWireProtocol {
    override val name: String = "elksmart_bulk"
    override val strictHandshake: Boolean = true
    override val wantsBackgroundReader: Boolean = false
    override val interFrameDelayMs: Long = 2L

    private enum class Subtype { D552, D226 }

    private var subtype: Subtype? = null

    companion object {
        private const val TAG = "ElkSmartUsbProtocol"
        private const val IDENT_PREFIX = 0xFC
        private const val TYPE_D552_HI = 0x70
        private const val TYPE_D552_LO = 0x01
        private const val TYPE_D226_HI = 0x02
        private const val TYPE_D226_LO = 0xAA

        /**
         * Safety gap appended when a pattern ends with a mark (odd length).
         * A zero off-time makes some firmware variants truncate the tail.
         */
        private const val ODD_PATTERN_TRAILING_GAP_US = 10_000

        internal fun mangleByte(v: Int): Byte {
            var value = v and 0xFF
            var reversed = 0
            repeat(8) {
                reversed = (reversed shl 1) or (value and 1)
                value = value ushr 1
            }
            return (reversed.inv() and 0xFF).toByte()
        }

        internal fun checksum62(buf: ByteArray): Byte {
            var sum = 0
            for (i in 0 until 62) sum += (buf[i].toInt() and 0xFF)
            val x = (sum and 0xF0) or ((sum ushr 8) and 0x0F)
            return mangleByte(x)
        }
    }

    override fun openHandshake(
        connection: UsbDeviceConnection,
        inEndpoint: UsbEndpoint,
        outEndpoint: UsbEndpoint
    ): Boolean {
        return try {
            val tmp = ByteArray(maxOf(inEndpoint.maxPacketSize, 64))
            while (true) {
                val r = connection.bulkTransfer(inEndpoint, tmp, tmp.size, 10)
                if (r <= 0) break
            }
            val identify = byteArrayOf(
                IDENT_PREFIX.toByte(), IDENT_PREFIX.toByte(),
                IDENT_PREFIX.toByte(), IDENT_PREFIX.toByte()
            )
            val w = connection.bulkTransfer(outEndpoint, identify, identify.size, 200)
            if (w != identify.size) return false

            val resp = ByteArray(64)
            var got = -1
            val deadline = SystemClock.uptimeMillis() + 450L
            while (SystemClock.uptimeMillis() < deadline) {
                val n = connection.bulkTransfer(inEndpoint, resp, resp.size, 150)
                if (n > 0) {
                    got = n
                    break
                }
            }
            if (got < 6) return false
            val identified = identifySubtype(resp, got)
            if (identified == null) {
                Log.w(TAG, "Unexpected identify response: ${resp.copyOf(got).toHexString()}")
                return false
            }
            subtype = identified
            true
        } catch (t: Throwable) {
            Log.w(TAG, "openHandshake failed: ${t.message}")
            false
        }
    }

    private fun identifySubtype(resp: ByteArray, size: Int): Subtype? {
        if (size < 6) return null
        if (resp[0] != IDENT_PREFIX.toByte() ||
            resp[1] != IDENT_PREFIX.toByte() ||
            resp[2] != IDENT_PREFIX.toByte() ||
            resp[3] != IDENT_PREFIX.toByte()
        ) {
            return null
        }
        val typeHi = resp[4].toInt() and 0xFF
        val typeLo = resp[5].toInt() and 0xFF
        return when {
            typeHi == TYPE_D552_HI && typeLo == TYPE_D552_LO -> {
                Log.i(TAG, "Identified ElkSmart subtype 70 01 (D552)")
                Subtype.D552
            }
            typeHi == TYPE_D226_HI && typeLo == TYPE_D226_LO -> {
                Log.i(TAG, "Identified ElkSmart subtype 02 AA (D226)")
                Subtype.D226
            }
            else -> null
        }
    }

    override fun encode(frequencyHz: Int, patternUs: IntArray): List<ByteArray> {
        val pulses = toPulses(patternUs)
        val rawCompressed = compressPulses(pulses)
        val payload = when (subtype ?: Subtype.D552) {
            Subtype.D552 -> rawCompressed
            Subtype.D226 -> encodeD226Payload(rawCompressed)
        }

        val f = frequencyHz + 0x7FFFF
        val len = payload.size

        val msg = ByteArrayOutput()
        msg.write(0xFF)
        msg.write(0xFF)
        msg.write(0xFF)
        msg.write(0xFF)
        msg.write(mangleByte(f ushr 8).toInt() and 0xFF)
        msg.write(mangleByte(f ushr 16).toInt() and 0xFF)
        msg.write(mangleByte(f).toInt() and 0xFF)
        msg.write(mangleByte(len ushr 8).toInt() and 0xFF)
        msg.write(mangleByte(len).toInt() and 0xFF)
        for (b in payload) msg.write(b.toInt() and 0xFF)

        val message = msg.toByteArray()
        val frames = ArrayList<ByteArray>()
        var offset = 0
        while (offset < message.size) {
            val chunk = min(62, message.size - offset)
            if (chunk == 62) {
                val buf = ByteArray(63)
                System.arraycopy(message, offset, buf, 0, 62)
                buf[62] = checksum62(buf)
                frames.add(buf)
            } else {
                val buf = ByteArray(chunk)
                System.arraycopy(message, offset, buf, 0, chunk)
                frames.add(buf)
            }
            offset += chunk
        }
        return frames
    }

    override fun postTransmitDelayMs(patternUs: IntArray): Long {
        var totalUs = 0L
        for (v in patternUs) totalUs += v.toLong().coerceAtLeast(0L)
        val patternMs = (totalUs.toDouble() / 1000.0).roundToLong()
        return (patternMs + 25L).coerceAtLeast(80L)
    }

    override fun drainAfterTransmit(connection: UsbDeviceConnection, inEndpoint: UsbEndpoint) {
        try {
            val tmp = ByteArray(maxOf(inEndpoint.maxPacketSize, 64))
            val deadline = SystemClock.uptimeMillis() + 120L
            while (SystemClock.uptimeMillis() < deadline) {
                val r = connection.bulkTransfer(inEndpoint, tmp, tmp.size, 20)
                if (r <= 0) break
            }
        } catch (_: Throwable) {
        }
    }

    internal data class Pulse(val onUs: Int, val offUs: Int)

    internal fun toPulses(patternUs: IntArray): List<Pulse> {
        if (patternUs.isEmpty()) return emptyList()
        val out = ArrayList<Pulse>((patternUs.size + 1) / 2)
        var i = 0
        while (i < patternUs.size) {
            val on = patternUs[i].coerceAtLeast(0)
            val off = if (i + 1 < patternUs.size) {
                patternUs[i + 1].coerceAtLeast(0)
            } else {
                ODD_PATTERN_TRAILING_GAP_US
            }
            out.add(Pulse(on, off))
            i += 2
        }
        return out
    }

    internal fun compressPulses(pulses: List<Pulse>): ByteArray {
        if (pulses.isEmpty()) return ByteArray(0)
        val freq = HashMap<Pulse, Int>(pulses.size)
        for (p in pulses) {
            freq[p] = (freq[p] ?: 0) + 1
        }
        val sorted = freq.entries.sortedByDescending { it.value }
        val p1 = sorted.getOrNull(0)?.key ?: pulses[0]
        val p2 = sorted.getOrNull(1)?.key ?: p1

        val out = ByteArrayOutput()
        // Longer pair first, then shorter pair, then 0xFF separator —
        // this order is part of the wire format, not a bug.
        compressValueUs(p2.onUs, out)
        compressValueUs(p2.offUs, out)
        compressValueUs(p1.onUs, out)
        compressValueUs(p1.offUs, out)
        out.write(0xFF)
        out.write(0xFF)
        out.write(0xFF)

        for (p in pulses) {
            when {
                p == p1 -> out.write(0x00)
                p == p2 -> out.write(0x01)
                else -> {
                    compressValueUs(p.onUs, out)
                    compressValueUs(p.offUs, out)
                }
            }
        }
        return out.toByteArray()
    }

    internal fun encodeD226Payload(rawCompressed: ByteArray): ByteArray {
        if (rawCompressed.isEmpty()) return rawCompressed

        val freq = IntArray(256)
        for (b in rawCompressed) {
            freq[b.toInt() and 0xFF]++
        }

        val pq = java.util.PriorityQueue<Node>(compareBy<Node> { it.weight })
        for (symbol in 0..0xFF) {
            val weight = freq[symbol]
            if (weight > 0) {
                pq.offer(Leaf(weight, symbol))
            }
        }
        if (pq.isEmpty()) return rawCompressed

        while (pq.size > 1) {
            val left = pq.poll()
            val right = pq.poll()
            pq.offer(Branch(left, right))
        }

        val codes = ArrayList<CodeEntry>()
        buildCodes(pq.poll(), StringBuilder(), codes)
        codes.sortBy { it.symbol }

        val codeBySymbol = HashMap<Int, String>(codes.size)
        val out = ByteArrayOutput()
        out.write((codes.size ushr 8) and 0xFF)
        out.write(codes.size and 0xFF)

        for (entry in codes) {
            codeBySymbol[entry.symbol] = entry.bits
            out.write(entry.symbol and 0xFF)
            out.write((entry.weight ushr 8) and 0xFF)
            out.write(entry.weight and 0xFF)
        }

        val bitString = StringBuilder()
        for (b in rawCompressed) {
            bitString.append(codeBySymbol[b.toInt() and 0xFF] ?: "")
        }

        var tailBits = bitString.length % 8
        if (tailBits > 0) {
            repeat(8 - tailBits) { bitString.append('0') }
        }
        out.write(tailBits and 0xFF)

        var i = 0
        while (i < bitString.length) {
            val chunk = bitString.substring(i, i + 8)
            out.write(chunk.toInt(2) and 0xFF)
            i += 8
        }
        return out.toByteArray()
    }

    private sealed class Node(val weight: Int)
    private class Leaf(weight: Int, val symbol: Int) : Node(weight)
    private class Branch(val left: Node, val right: Node) : Node(left.weight + right.weight)
    private data class CodeEntry(val symbol: Int, val weight: Int, val bits: String)

    private fun buildCodes(node: Node, prefix: StringBuilder, out: MutableList<CodeEntry>) {
        when (node) {
            is Leaf -> {
                val bits = if (prefix.isEmpty()) "0" else prefix.toString()
                out.add(CodeEntry(node.symbol, node.weight, bits))
            }
            is Branch -> {
                prefix.append('0')
                buildCodes(node.left, prefix, out)
                prefix.deleteCharAt(prefix.lastIndex)
                prefix.append('1')
                buildCodes(node.right, prefix, out)
                prefix.deleteCharAt(prefix.lastIndex)
            }
        }
    }

    /**
     * Timing prescale: small values are divided by 16 with rounding,
     * larger values use LEB128 with 0xFF escaped to 0xFE (0xFF is the
     * dictionary separator). Values 0/1 are dictionary indices and pass
     * through untouched.
     */
    internal fun compressValueUs(valueUs: Int, out: ByteArrayOutput) {
        if (valueUs <= 2032) {
            val q = if (valueUs == 0 || valueUs == 1) valueUs else ((valueUs / 16.0) + 0.5).toInt()
            out.write(q and 0xFF)
            return
        }
        var v = valueUs
        while (true) {
            var b = v and 0x7F
            v = v ushr 7
            if (v != 0) b = b or 0x80
            if ((b and 0xFF) == 0xFF) b = 0xFE
            out.write(b and 0xFF)
            if (v == 0) break
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

    private fun ByteArray.toHexString(): String = joinToString(" ") { b ->
        (b.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase()
    }

    // ------------------------------------------------------------------
    // IR learning (capture from a physical remote).
    //
    // Only the ElkSmart family implements this; built-in blasters and the
    // Tiqiaa family are transmit-only from the app's point of view.
    //
    // Session: host sends START_LEARN, the dongle captures one button press
    // and answers with an echo + big-endian length + raw timing bytes
    // (possibly split over several bulk transfers), host sends STOP_LEARN.
    // Raw timing bytes decode as: b < 0xFF -> b*16+carry µs, b == 0xFF ->
    // carry += 0xFF0 (4080). No carrier frequency is reported, so captures
    // default to 38 kHz (overridable in the UI).
    // ------------------------------------------------------------------

    /**
     * Runs one learn session on [connection]. Blocking; call off the main
     * thread. Returns the captured mark/space pattern in microseconds, or
     * null on timeout/cancel/error. STOP is always sent before returning so
     * the dongle never stays stuck in learn mode.
     */
    fun learnCapture(
        connection: UsbDeviceConnection,
        inEndpoint: UsbEndpoint,
        outEndpoint: UsbEndpoint,
        timeoutMs: Long,
        isCancelled: () -> Boolean
    ): IntArray? {
        try {
            // Drain stale inbound data.
            val drain = ByteArray(maxOf(inEndpoint.maxPacketSize, 64))
            while (true) {
                val r = runCatching {
                    connection.bulkTransfer(inEndpoint, drain, drain.size, 10)
                }.getOrDefault(-1)
                if (r <= 0) break
            }
            val start = byteArrayOf(0xFE.toByte(), 0xFE.toByte(), 0xFE.toByte(), 0xFE.toByte())
            val w = runCatching {
                connection.bulkTransfer(outEndpoint, start, start.size, 500)
            }.getOrDefault(-1)
            if (w != start.size) {
                Log.w(TAG, "learn start not accepted")
                return null
            }
            val message = readLearnMessage(connection, inEndpoint, timeoutMs, isCancelled)
            if (message == null) {
                Log.i(TAG, "learn: nothing captured (timeout/cancel)")
                return null
            }
            val pattern = decodeLearnedMessage(message)
            if (pattern == null || pattern.isEmpty()) {
                Log.w(TAG, "learn: undecodable capture (${message.size} bytes)")
                return null
            }
            Log.i(TAG, "learn: captured ${pattern.size} timings")
            return pattern
        } finally {
            runCatching {
                val stop = byteArrayOf(0xFD.toByte(), 0xFD.toByte(), 0xFD.toByte(), 0xFD.toByte())
                connection.bulkTransfer(outEndpoint, stop, stop.size, 500)
            }
            // Drain any trailing learn-mode bytes so the next TX is clean.
            runCatching {
                val drain = ByteArray(64)
                connection.bulkTransfer(inEndpoint, drain, drain.size, 20)
            }
        }
    }

    private fun readLearnMessage(
        connection: UsbDeviceConnection,
        inEndpoint: UsbEndpoint,
        timeoutMs: Long,
        isCancelled: () -> Boolean
    ): ByteArray? {
        val acc = ByteArrayOutput()
        val buf = ByteArray(maxOf(inEndpoint.maxPacketSize, 64))
        val deadline = SystemClock.uptimeMillis() + timeoutMs.coerceAtLeast(1000L)
        var declaredLen = -1
        while (SystemClock.uptimeMillis() < deadline) {
            if (isCancelled()) return null
            val n = runCatching {
                connection.bulkTransfer(inEndpoint, buf, buf.size, 250)
            }.getOrDefault(-1)
            if (n > 0) {
                for (i in 0 until n) acc.write(buf[i].toInt() and 0xFF)
                val snapshot = acc.toByteArray()
                // Header echo (FE FE FE FE) may itself arrive fragmented.
                val headerAt = indexOfHeader(snapshot)
                if (headerAt >= 0 && snapshot.size >= headerAt + 6) {
                    declaredLen = ((snapshot[headerAt + 4].toInt() and 0xFF) shl 8) or
                        (snapshot[headerAt + 5].toInt() and 0xFF)
                    if (declaredLen < 0 || declaredLen > 4096) {
                        Log.w(TAG, "learn: suspicious length $declaredLen")
                        return null
                    }
                    if (snapshot.size >= headerAt + 6 + declaredLen) {
                        return snapshot.copyOfRange(headerAt, headerAt + 6 + declaredLen)
                    }
                } else if (snapshot.size > 5200) {
                    // Garbage without a header; bail out instead of growing forever.
                    Log.w(TAG, "learn: no header in ${snapshot.size} bytes")
                    return null
                }
            }
        }
        return null
    }

    private fun indexOfHeader(buf: ByteArray): Int {
        for (i in 0 until buf.size - 3) {
            if (buf[i] == 0xFE.toByte() && buf[i + 1] == 0xFE.toByte() &&
                buf[i + 2] == 0xFE.toByte() && buf[i + 3] == 0xFE.toByte()
            ) {
                return i
            }
        }
        return -1
    }

    /**
     * Validates the echo + length header and decodes the raw timing body.
     * Returns null when the message is malformed.
     */
    internal fun decodeLearnedMessage(message: ByteArray): IntArray? {
        if (message.size < 6) return null
        if (indexOfHeader(message) != 0) return null
        val declaredLen = ((message[4].toInt() and 0xFF) shl 8) or (message[5].toInt() and 0xFF)
        if (declaredLen < 0 || declaredLen > 4096) return null
        if (message.size < 6 + declaredLen) return null
        return decodeRawTimings(message.copyOfRange(6, 6 + declaredLen))
    }

    /**
     * Raw timing bytes -> microseconds. Each byte < 0xFF contributes
     * `b * 16 + carry`; each 0xFF adds 4080 to the carry for large gaps.
     * Filters out non-positive results defensively.
     */
    internal fun decodeRawTimings(data: ByteArray): IntArray {
        val out = ArrayList<Int>(data.size)
        var carry = 0
        for (b in data) {
            val v = b.toInt() and 0xFF
            if (v < 0xFF) {
                val timing = v * 16 + carry
                carry = 0
                if (timing > 0) out.add(timing)
            } else {
                carry += 0xFF0
                // Absurd chains of 0xFF would overflow; cap and reset.
                if (carry > 1_000_000) carry = 0
            }
        }
        return out.toIntArray()
    }
}
