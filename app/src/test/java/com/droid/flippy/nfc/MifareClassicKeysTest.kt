package com.droid.flippy.nfc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MifareClassicKeysTest {

    @Test
    fun dictionary_isValidSixByteKeys() {
        assertTrue(MifareClassicKeys.DICTIONARY_HEX.isNotEmpty())
        assertEquals(
            MifareClassicKeys.DICTIONARY_HEX.size,
            MifareClassicKeys.DICTIONARY_HEX.distinct().size,
        )
        for (hex in MifareClassicKeys.DICTIONARY_HEX) {
            val bytes = MifareClassicKeys.toKeyBytes(hex)
            assertEquals(6, bytes.size)
        }
        // Well-known defaults must be present for rootless hits.
        assertTrue(MifareClassicKeys.DICTIONARY_HEX.contains("FFFFFFFFFFFF"))
        assertTrue(MifareClassicKeys.DICTIONARY_HEX.contains("000000000000"))
        assertTrue(MifareClassicKeys.DICTIONARY_HEX.contains("A0A1A2A3A4A5"))
    }
}
