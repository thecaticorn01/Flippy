package com.droid.flippy.ir.usb

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint

/**
 * USB IR wire protocol abstraction.
 *
 * Each supported dongle family speaks its own framing over bulk endpoints;
 * this interface keeps discovery/permission/connection management
 * ([UsbIrManager], [UsbIrTransmitter]) independent from the byte-level
 * encoding. Protocol implementations are adapted from
 * iodn/android-ir-blaster (GPL-3.0) with the ElkSmart details cross-checked
 * against deadboy18/Ocrustar-USB-IR (MIT).
 */
interface UsbWireProtocol {
    val name: String

    /**
     * True when a failed open-handshake must abort the open attempt
     * (ElkSmart identifies the D226/D552 subtype during the handshake).
     * False for lenient protocols that accept a best-effort handshake.
     */
    val strictHandshake: Boolean

    /** True when a short-lived background reader should drain bulk IN after TX. */
    val wantsBackgroundReader: Boolean

    /** Delay between consecutive bulk frames, if the dongle needs pacing. */
    val interFrameDelayMs: Long

    fun openHandshake(
        connection: UsbDeviceConnection,
        inEndpoint: UsbEndpoint,
        outEndpoint: UsbEndpoint
    ): Boolean

    /** Encodes one (frequency, mark/space pattern) pair into bulk frames. */
    fun encode(frequencyHz: Int, patternUs: IntArray): List<ByteArray>

    /** Mandatory settle delay after the last frame of a transmission. */
    fun postTransmitDelayMs(patternUs: IntArray): Long

    fun drainAfterTransmit(connection: UsbDeviceConnection, inEndpoint: UsbEndpoint) {}
}
