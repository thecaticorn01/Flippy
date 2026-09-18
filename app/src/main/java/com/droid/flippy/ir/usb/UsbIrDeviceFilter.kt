package com.droid.flippy.ir.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface

/**
 * Known-good USB IR dongles plus generic bulk-candidate detection.
 *
 * Device list combines two open-source analyses:
 * - iodn/android-ir-blaster (GPL-3.0): Tiqiaa/Tview/ZaZa family
 *   (VID 0x10C4 / 0x045E, PID 0x8468) and ElkSmart family
 *   (VID 0x045C, several PIDs), bulk IN/OUT framing.
 * - deadboy18/Ocrustar-USB-IR (MIT): ElkSmart D226 (PID 0x02AA) /
 *   D552 byte-level protocol reference.
 *
 * Devices outside the known list can still be attempted with the generic
 * Tiqiaa-style driver when they expose a bulk IN+OUT pair ([isCandidate]),
 * but that path is opt-in from the UI so unknown hardware (headsets,
 * serial adapters, …) is never claimed automatically.
 */
object UsbIrDeviceFilter {

    // Tiqiaa / Tview / ZaZaRemote family (RLE "ST" bulk protocol).
    const val TIQIAA_VID_1 = 0x10C4
    const val TIQIAA_VID_2 = 0x045E
    const val TIQIAA_PID = 0x8468

    // ElkSmart / Ocrustar family (FC-handshake + Huffman/pulse protocol).
    const val ELKSMART_VID = 0x045C
    val ELKSMART_PIDS: Set<Int> = setOf(
        0x0131, 0x0132, 0x0134, 0x014A, 0x0184, 0x0195, 0x02AA
    )

    fun isTiqiaaFamily(device: UsbDevice): Boolean {
        val vid = device.vendorId
        val pid = device.productId
        return pid == TIQIAA_PID && (vid == TIQIAA_VID_1 || vid == TIQIAA_VID_2)
    }

    fun isElkSmart(device: UsbDevice): Boolean =
        device.vendorId == ELKSMART_VID && device.productId in ELKSMART_PIDS

    /** Strict allow-list used for auto-connect and attach prompts. */
    fun isSupported(device: UsbDevice): Boolean =
        isTiqiaaFamily(device) || isElkSmart(device)

    /**
     * Generic candidate: any interface with a bulk OUT *and* bulk IN endpoint.
     * Used only for the opt-in "try generic driver" flow, never for
     * automatic claiming.
     */
    fun isCandidate(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (hasBulkPair(intf)) return true
        }
        return false
    }

    fun hasBulkPair(intf: UsbInterface): Boolean {
        var outFound = false
        var inFound = false
        for (i in 0 until intf.endpointCount) {
            val ep = intf.getEndpoint(i)
            if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (ep.direction == UsbConstants.USB_DIR_OUT) outFound = true
            if (ep.direction == UsbConstants.USB_DIR_IN) inFound = true
        }
        return outFound && inFound
    }

    fun shortLabel(device: UsbDevice): String {
        val name = device.productName?.takeIf { it.isNotBlank() }
            ?: device.deviceName.substringAfterLast('/')
        return "$name (0x${device.vendorId.toString(16).uppercase()}:" +
            "0x${device.productId.toString(16).uppercase()})"
    }
}
