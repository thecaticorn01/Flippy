package com.droid.flippy.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuManagerTest {

    @Test
    fun shellResult_ok() {
        assertTrue(ShizukuManager.ShellResult(0, "1", "").ok)
        assertFalse(ShizukuManager.ShellResult(1, "", "err").ok)
        assertFalse(ShizukuManager.ShellResult(124, "", "timeout").ok)
    }

    @Test
    fun parseSettingsFlag_values() {
        val manager = ShizukuManager
        assertEquals(true, manager.parseSettingsFlag("1"))
        assertEquals(true, manager.parseSettingsFlag("1\n"))
        assertEquals(false, manager.parseSettingsFlag("0"))
        assertEquals(false, manager.parseSettingsFlag("0\n"))
        assertNull(manager.parseSettingsFlag(""))
        assertNull(manager.parseSettingsFlag("null"))
        assertNull(manager.parseSettingsFlag("2"))
    }
}
