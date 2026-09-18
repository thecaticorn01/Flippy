package com.droid.flippy.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiGroupTest {

    private fun ap(ssid: String, bssid: String, level: Int, active: Boolean = false) =
        HubWifiNetwork(
            ssid = ssid,
            bssid = bssid,
            level = level,
            frequencyMhz = 2412,
            capabilities = "[WPA2-PSK-CCMP]",
            isActive = active
        )

    @Test
    fun groupsBySsid() {
        val groups = groupWifiNetworks(
            listOf(
                ap("Home", "AA:BB:CC:DD:EE:01", -60),
                ap("Home", "AA:BB:CC:DD:EE:02", -72),
                ap("Cafe", "AA:BB:CC:DD:EE:03", -80),
            )
        )
        assertEquals(2, groups.size)
        val home = groups.first { it.ssid == "Home" }
        assertEquals(2, home.aps.size)
        // Strongest AP first.
        assertEquals("AA:BB:CC:DD:EE:01", home.aps[0].bssid)
        assertFalse(home.isActive)
    }

    @Test
    fun activeGroupSortsFirst() {
        val groups = groupWifiNetworks(
            listOf(
                ap("Strong", "AA:BB:CC:DD:EE:01", -40),
                ap("Mine", "AA:BB:CC:DD:EE:02", -80, active = true),
            )
        )
        assertEquals("Mine", groups[0].ssid)
        assertTrue(groups[0].isActive)
    }

    @Test
    fun emptyInput() {
        assertTrue(groupWifiNetworks(emptyList()).isEmpty())
    }
}
