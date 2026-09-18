package com.droid.flippy.nfc

/**
 * Shared Mifare Classic dictionary for the rootless Master Key.
 *
 * Android's [android.nfc.tech.MifareClassic] API can authenticate sectors
 * with KeyA/KeyB with NO root and NO Shizuku — it is a normal SDK call.
 * Root/Shizuku are only useful for forcing NFC on (`svc nfc enable`),
 * never for the crypto itself.
 */
object MifareClassicKeys {
    val DICTIONARY_HEX: List<String> = listOf(
        "FFFFFFFFFFFF",
        "000000000000",
        "A0A1A2A3A4A5",
        "B0B1B2B3B4B5",
        "C0C1C2C3C4C5",
        "D0D1D2D3D4D5",
        "4D3A99C351DD",
        "1A2B3C4D5E6F",
        "AABBCCDDEEFF",
        "AABBCCDDEE11",
        "D3F7D3F7D3F7",
        "A0B0C0D0E0F0",
        "A1B1C1D1E1F1",
        "0123456789AB",
        "010203040506",
        "111111111111",
        "222222222222",
        "123456789ABC",
        "ABCDEF123456",
        "AFAFAFAFAFAF",
        "000000000001",
        "FEEDFEEDFEED",
        "DEADBEEF0000",
        "CAFEBABE0000",
        "1A982C7E459A",
        "EE9BD361B01B",
    ).distinct()

    fun toKeyBytes(hex: String): ByteArray {
        val clean = hex.replace("0x", "").replace(" ", "")
        require(clean.length == 12) { "Mifare key must be 6 bytes hex: $hex" }
        return ByteArray(6) { i ->
            ((Character.digit(clean[i * 2], 16) shl 4) or Character.digit(clean[i * 2 + 1], 16)).toByte()
        }
    }

    val DICTIONARY_BYTES: List<ByteArray> by lazy { DICTIONARY_HEX.map { toKeyBytes(it) } }
}
