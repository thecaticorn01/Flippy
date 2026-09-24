package com.droid.flippy.nfc.master

/**
 * Multi-technology credential dictionaries used by the Master Key engine.
 *
 * Every catalogue here is interrogated through normal Android NFC SDK calls
 * (NfcA/NfcB/NfcV/IsoDep/MifareClassic/MifareUltralight transceive + auth), so
 * none of it technically requires root. Root/Shizuku are only used to force the
 * NFC controller on, never for the crypto itself.
 */
object NfcKeyDictionary {

    /**
     * Well-known MIFARE Classic 6-byte keys. This is a superset of
     * [com.droid.flippy.nfc.MifareClassicKeys.DICTIONARY_HEX] plus the
     * defaults published by common reader vendors, NFC lock kits and the
     * mfoc/mfcuk key tables.
     */
    val MIFARE_CLASSIC_HEX: List<String> = listOf(
        "FFFFFFFFFFFF",
        "000000000000",
        "A0A1A2A3A4A5",
        "B0B1B2B3B4B5",
        "AABBCCDDEEFF",
        "AABBCCDDEE11",
        "AABBCCDDEE22",
        "AABBCCDDEE33",
        "A1B1C1D1E1F1",
        "AFAFAFAFAFAF",
        "0123456789AB",
        "123456789ABC",
        "ABCDEF123456",
        "010203040506",
        "111111111111",
        "222222222222",
        "333333333333",
        "444444444444",
        "555555555555",
        "666666666666",
        "777777777777",
        "888888888888",
        "999999999999",
        "112233445566",
        "121212121212",
        "131313131313",
        "141414141414",
        "0A0A0A0A0A0A",
        "0A0B0C0D0E0F",
        "A0B0C0D0E0F0",
        "AABBCCDDEE12",
        "D3F7D3F7D3F7",
        "4D3A99C351DD",
        "1A982C7E459A",
        "DEADBEEF0000",
        "CAFEBABE0000",
        "FEEDFEEDFEED",
        "C0C1C2C3C4C5",
        "D0D1D2D3D4D5",
        "1A2B3C4D5E6F",
        "000000000001",
        "EE9BD361B01B",
    ).distinct()

    /**
     * MIFARE Ultralight / NTAG 4-byte passwords sent with the NTAG
     * PWD_AUTH command (0x1B). NXP-manufactured NTAG21x cards ship password
     * protection disabled with a 0xFFFFFFFF password; many installed systems
     * use short digit or "MF"-style defaults.
     */
    val ULTRALIGHT_PASSWORDS_HEX: List<String> = listOf(
        "FFFFFFFF",
        "00000000",
        "00000001",
        "12345678",
        "87654321",
        "01234567",
        "11223344",
        "11111111",
        "22222222",
        "33333333",
        "44444444",
        "55555555",
        "66666666",
        "77777777",
        "88888888",
        "99999999",
        "AAAA5555",
        "AAAA5556",
        "DEADBEEF",
        "CAFEBABE",
        "FEDCBA98",
        "4D4D4D4D",
        "4D465847",
        "50415353",
        "4D525048",
    ).distinct()

    /**
     * MIFARE Ultralight-C 8-byte 3DES keys. The factory default is the ASCII
     * string "IEMKAERB"; blank UL-C cards accept it with the fixed 7-eBay
     * random RNG interface used by Android's [android.nfc.tech.MifareUltralight]
     * authenticate() call.
     */
    val ULTRALIGHT_C_KEYS_HEX: List<String> = listOf(
        "49454D4B41455242",
        "0000000000000000",
        "FFFFFFFFFFFFFFFF",
    ).distinct()

    /**
     * DESFire application master keys tried against an AuthenticateISO /
     * legacy Authenticate APDU. Blank DESFire EV1/EV2 cards authenticate the
     * PICC-level application (AID 000000) with an all-zero key.
     */
    val DESFIRE_KEYS_HEX: List<String> = listOf(
        "0000000000000000",
        "FFFFFFFFFFFFFFFF",
        "00000000000000000000000000000000",
        "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF",
    ).distinct()

    /** 4-byte PWD used by the NTAG PWD_AUTH command. */
    fun pwdToBytes(hex: String): ByteArray = parseHex(hex, 4, "4-byte password")

    /** 6-byte MIFARE Classic key. */
    fun classicKeyToBytes(hex: String): ByteArray = parseHex(hex, 6, "MIFARE Classic key")

    /** 8-byte DES / 3DES key. */
    fun desKeyToBytes(hex: String): ByteArray = parseHex(hex, 8, "DES/3DES key")

    /** 16-byte AES-128 key. */
    fun aesKeyToBytes(hex: String): ByteArray = parseHex(hex, 16, "AES-128 key")

    /** Parses a strictly even-length, non-empty hex string into bytes. */
    fun parseHex(hex: String, expectedBytes: Int? = null, what: String = "value"): ByteArray {
        val clean = hex.replace("0x", "").replace(" ", "").trim()
        require(clean.isNotEmpty() && clean.length % 2 == 0) { "$what must be even-length hex: $hex" }
        val bytes = ByteArray(clean.length / 2) { i ->
            ((Character.digit(clean[i * 2], 16) shl 4) or
                Character.digit(clean[i * 2 + 1], 16)).toByte()
        }
        if (expectedBytes != null) require(bytes.size == expectedBytes) {
            "$what must be ${expectedBytes * 2} hex chars, got ${clean.length}"
        }
        return bytes
    }
}