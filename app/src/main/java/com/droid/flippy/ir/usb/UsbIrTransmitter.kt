package com.droid.flippy.ir.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * An open USB IR dongle: claimed interface + bulk endpoints + wire protocol.
 *
 * Adapted from iodn/android-ir-blaster `UsbIrTransmitter` (GPL-3.0).
 *
 * Threading contract: [transmit] is blocking (bulkTransfer with timeouts)
 * and must be called off the main thread (IrBackend/UsbIrManager already
 * dispatch to Dispatchers.IO). Concurrent transmits are serialized with an
 * internal lock. [close] is idempotent and safe from any thread.
 */
class UsbIrTransmitter private constructor(
    val device: UsbDevice,
    private val connection: UsbDeviceConnection,
    private val claimedInterface: UsbInterface,
    private val outEndpoint: UsbEndpoint,
    private val inEndpoint: UsbEndpoint,
    private val protocol: UsbWireProtocol
) {
    private val tag = "UsbIrTransmitter"

    @Volatile
    private var closed: Boolean = false

    private val readUntilMs = AtomicLong(0)
    private var readerJob: Job? = null
    private val txLock = Any()

    val protocolName: String get() = protocol.name

    /** True when the active protocol implements IR learning. */
    val supportsLearning: Boolean get() = protocol is ElkSmartUsbProtocol

    internal val usbConnection: UsbDeviceConnection get() = connection
    internal val usbInEndpoint: UsbEndpoint get() = inEndpoint
    internal val usbOutEndpoint: UsbEndpoint get() = outEndpoint
    internal val wireProtocol: UsbWireProtocol get() = protocol
    internal val isClosed: Boolean get() = closed

    companion object {
        fun create(
            device: UsbDevice,
            connection: UsbDeviceConnection,
            claimedInterface: UsbInterface,
            outEndpoint: UsbEndpoint,
            inEndpoint: UsbEndpoint,
            protocol: UsbWireProtocol
        ): UsbIrTransmitter? {
            val tx = UsbIrTransmitter(
                device = device,
                connection = connection,
                claimedInterface = claimedInterface,
                outEndpoint = outEndpoint,
                inEndpoint = inEndpoint,
                protocol = protocol
            )
            val handshakeOk = try {
                protocol.openHandshake(connection, inEndpoint, outEndpoint)
            } catch (t: Throwable) {
                Log.w("UsbIrTransmitter", "openHandshake error: ${t.message}")
                false
            }
            if (!handshakeOk && protocol.strictHandshake) {
                tx.close()
                return null
            }
            return tx
        }
    }

    fun transmit(frequencyHz: Int, patternUs: IntArray): Boolean {
        synchronized(txLock) {
            if (closed) return false
            if (patternUs.isEmpty()) return false

            val frames = try {
                protocol.encode(frequencyHz, patternUs)
            } catch (t: Throwable) {
                Log.w(tag, "encode failed: ${t.message}")
                return false
            }

            for (frame in frames) {
                val ok = sendFrame(frame) ?: return false
                if (!ok) return false
                val pacing = protocol.interFrameDelayMs
                if (pacing > 0) SystemClock.sleep(pacing)
            }

            val post = protocol.postTransmitDelayMs(patternUs)
            if (post > 0) SystemClock.sleep(post)

            try {
                protocol.drainAfterTransmit(connection, inEndpoint)
            } catch (_: Throwable) {
            }
            return true
        }
    }

    fun close() {
        if (closed) return
        closed = true
        try {
            readerJob?.cancel()
        } catch (_: Throwable) {
        }
        readerJob = null
        try {
            connection.releaseInterface(claimedInterface)
        } catch (_: Throwable) {
        }
        try {
            connection.close()
        } catch (_: Throwable) {
        }
    }

    private fun sendFrame(frame: ByteArray): Boolean? {
        if (closed) return null
        val rc = try {
            connection.bulkTransfer(outEndpoint, frame, frame.size, 400)
        } catch (t: Throwable) {
            Log.e(tag, "bulkTransfer(out) exception: ${t.message}", t)
            return false
        }
        if (rc <= 0) {
            Log.w(tag, "bulkTransfer(out) failed rc=$rc len=${frame.size}")
            return false
        }
        if (protocol.wantsBackgroundReader) {
            readUntilMs.set(System.currentTimeMillis() + 1000L)
            ensureReader()
        }
        return true
    }

    private fun ensureReader() {
        if (readerJob?.isActive == true) return
        readerJob = CoroutineScope(Dispatchers.IO).launch {
            val buf = ByteArray(inEndpoint.maxPacketSize.coerceAtLeast(64))
            while (isActive && !closed) {
                val until = readUntilMs.get()
                if (until <= System.currentTimeMillis()) break
                try {
                    connection.bulkTransfer(inEndpoint, buf, buf.size, 300)
                    delay(1)
                } catch (t: Throwable) {
                    Log.e(tag, "Background reader error", t)
                    delay(5)
                }
            }
        }
    }
}
