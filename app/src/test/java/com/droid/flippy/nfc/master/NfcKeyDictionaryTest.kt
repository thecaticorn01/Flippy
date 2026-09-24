package com.droid.flippy.nfc.master

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NfcKeyDictionaryTest {

    @Test
    fun classicDictionary_isValidSixByteKeys_andKeepsLegacyDefaults() {
        val dict = NfcKeyDictionary.MIFARE_CLASSIC_HEX
        assertTrue(dict.isNotEmpty())
        assertEquals(dict.size, dict.distinct().size)
        for (hex in dict) {
            assertEquals(6, NfcKeyDictionary.classicKeyToBytes(hex).size)
        }
        assertTrue(dict.contains("FFFFFFFFFFFF"))
        assertTrue(dict.contains("000000000000"))
        assertTrue(dict.contains("A0A1A2A3A4A5"))
        // Every legacy dictionary entry must remain covered.
        for (legacy in com.droid.flippy.nfc.MifareClassicKeys.DICTIONARY_HEX) {
            assertTrue("missing legacy key $legacy", dict.contains(legacy))
        }
    }

    @Test
    fun ultralightPasswords_areFourBytes() {
        val dict = NfcKeyDictionary.ULTRALIGHT_PASSWORDS_HEX
        assertTrue(dict.isNotEmpty())
        for (hex in dict) {
            assertEquals(4, NfcKeyDictionary.pwdToBytes(hex).size)
        }
        assertTrue(dict.contains("FFFFFFFF"))
        assertTrue(dict.contains("00000000"))
    }

    @Test
    fun ultralightCKeys_andDesfireKeys_haveCorrectWidths() {
        for (hex in NfcKeyDictionary.ULTRALIGHT_C_KEYS_HEX) {
            assertEquals(8, NfcKeyDictionary.desKeyToBytes(hex).size)
        }
        for (hex in NfcKeyDictionary.DESFIRE_KEYS_HEX) {
            val len = hex.length / 2
            assertTrue("DESFire key width $len must be 8 or 16", len == 8 || len == 16)
            assertEquals(len, NfcKeyDictionary.parseHex(hex).size)
        }
    }

    @Test
    fun parseHex_rejectsMalformedInput() {
        assertThrows(IllegalArgumentException::class.java) { NfcKeyDictionary.parseHex("0") }
        assertThrows(IllegalArgumentException::class.java) { NfcKeyDictionary.parseHex("ABC") }
        assertThrows(IllegalArgumentException::class.java) { NfcKeyDictionary.parseHex("", expectedBytes = 6) }
        assertThrows(IllegalArgumentException::class.java) { NfcKeyDictionary.classicKeyToBytes("AABBCC") }
    }

    @Test
    fun parseHex_handlesUrlStyleAndSpaces() {
        val key = NfcKeyDictionary.parseHex("0x A0 A1 A2 A3 A4 A5   ", expectedBytes = 6)
        assertEquals("A0A1A2A3A4A5", key.toHexString())
    }
}

private fun ByteArray.toHexString(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        if (v < 0x10) sb.append('0')
        sb.append(v.toString(16).uppercase())
    }
    return sb.toString()
}