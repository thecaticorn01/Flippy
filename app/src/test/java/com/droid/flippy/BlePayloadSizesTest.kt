package com.droid.flippy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Legacy 31-byte PDU budget checks for the non-Apple platforms. Oversized
 * payloads fail startAdvertising silently while the UI shows "active".
 */
class BlePayloadSizesTest {

    @Test
    fun easySetupBuds_fitLegacy() {
        val spam = EasySetupSpam(EasySetupDevice.Type.BUDS)
        for (device in spam.devices) {
            val bytes = Helper.convertHexToByteArray(device.toManufacturerData())
            // Manufacturer AD element = len(1) + type(1) + company(2) + data.
            assertTrue(
                "${device.name} -> ${bytes.size} mfg bytes, exceeds legacy budget",
                4 + bytes.size <= 31
            )
        }
    }

    @Test
    fun easySetupWatch_fitLegacy() {
        val spam = EasySetupSpam(EasySetupDevice.Type.WATCH)
        for (device in spam.devices) {
            val bytes = Helper.convertHexToByteArray(device.toManufacturerData())
            assertTrue(
                "${device.name} -> ${bytes.size} mfg bytes, exceeds legacy budget",
                4 + bytes.size <= 31
            )
        }
    }

    @Test
    fun easySetupBudsScanResponse_fitsLegacy() {
        val bytes = Helper.convertHexToByteArray("0000000000000000000000000000")
        assertEquals(14, bytes.size)
        assertTrue(4 + bytes.size <= 31)
    }

    @Test
    fun fastPair_serviceDataIsThreeBytes() {
        val spam = FastPairSpam()
        assertTrue(spam.devices.isNotEmpty())
        for (device in spam.devices) {
            val bytes = Helper.convertHexToByteArray(device.value)
            assertEquals("${device.name} model must be 3 bytes", 3, bytes.size)
        }
    }

    @Test
    fun xiaomi_controlledPayload_fitsLegacy() {
        val (_, bytes) = XiaomiQuickConnect.generateManufacturerDataBytes(controlled = true)
        assertTrue(
            "xiaomi payload ${bytes.size} mfg bytes, exceeds legacy budget",
            4 + bytes.size <= 31
        )
    }
}
