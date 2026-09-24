package com.droid.flippy.nfc.master

import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.MifareClassic
import android.nfc.tech.MifareUltralight
import android.nfc.tech.Ndef
import android.nfc.tech.NfcA
import android.nfc.tech.NfcB
import android.nfc.tech.NfcF
import android.nfc.tech.NfcV

/**
 * NFC/RFID technologies the Master Key engine probes.
 *
 * Detection reads the Android tech list already attached to a discovered
 * [Tag]; the probes themselves then talk to the tag through the matching
 * SDK handle (all of which are normal, rootless APIs).
 */
enum class NfcTechnology(
    val displayName: String,
    private val techClassName: String?,
) {
    MIFARE_CLASSIC("MIFARE Classic / Crypto1", "android.nfc.tech.MifareClassic"),
    MIFARE_ULTRALIGHT("MIFARE Ultralight / NTAG", "android.nfc.tech.MifareUltralight"),
    ISO_14443_4("ISO 14443-4 / Type 4 (DESFire)", "android.nfc.tech.IsoDep"),
    ISO_15693("ISO 15693 / Vicinity", "android.nfc.tech.NfcV"),
    ISO_14443_B("ISO 14443-B (PICC)", "android.nfc.tech.NfcB"),
    FELICA("FeliCa / ISO 18092", "android.nfc.tech.NfcF"),
    NDEF("NDEF (NfcA/B/F/V)", "android.nfc.tech.Ndef"),
    NFC_A("ISO 14443-A (generic)", "android.nfc.tech.NfcA"),
    UNKNOWN("Unknown", null);

    val techClass: String? get() = techClassName

    companion object {
        /**
         * Order matters: more specific and more "worth brute-forcing"
         * technologies come first. Returns the distinct technologies present
         * on [tag].
         */
        fun detect(tag: Tag): List<NfcTechnology> {
            val list = tag.techList.orEmpty()
            val present = entries.filter { t ->
                t.techClass?.let { c -> list.contains(c) } == true
            }
            return if (present.isEmpty()) listOf(UNKNOWN) else present
        }

        /** The canonical technology that [tag] claims to be. */
        fun primary(tag: Tag): NfcTechnology {
            val present = detect(tag).filter { it != NDEF && it != UNKNOWN }
            return present.firstOrNull() ?: UNKNOWN
        }

        /** Builds a probe-ready SDK handle for [tech] from [tag], or null. */
        fun handle(tag: Tag, tech: NfcTechnology): Any? = when (tech) {
            MIFARE_CLASSIC -> MifareClassic.get(tag)
            MIFARE_ULTRALIGHT -> MifareUltralight.get(tag)
            ISO_14443_4 -> IsoDep.get(tag)
            ISO_15693 -> NfcV.get(tag)
            ISO_14443_B -> NfcB.get(tag)
            FELICA -> NfcF.get(tag)
            NDEF -> Ndef.get(tag)
            NFC_A -> NfcA.get(tag)
            UNKNOWN -> null
        }
    }
}