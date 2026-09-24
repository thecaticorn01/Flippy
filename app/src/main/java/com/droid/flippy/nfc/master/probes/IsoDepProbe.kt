package com.droid.flippy.nfc.master.probes

import android.nfc.tech.IsoDep
import com.droid.flippy.nfc.master.CredentialHit
import com.droid.flippy.nfc.master.NfcKeyDictionary
import com.droid.flippy.nfc.master.NfcMasterProbe
import com.droid.flippy.nfc.master.NfcProbeEnv
import com.droid.flippy.nfc.master.NfcTechnology
import com.droid.flippy.nfc.master.ProbeOutcome

/**
 * ISO 14443-4 / Type 4 interrogation including DESFire default-key checks.
 *
 * Two families are probed, both with rootless IsoDep APDUs:
 *  - MIFARE DESFire (EV1/EV2): GET VERSION (0x60) identification, SELECT
 *    APPLICATION of the PICC-level AID 000000, then AuthenticateISO / AES
 *    against the shared DESFire default-key table. Factory blank cards accept
 *    the all-zero key at the PICC level, which is how many access systems are
 *    left configured.
 *  - Generic Type 4 (NDEF smart cards): NDEF application SELECT so the dump
 *    can still be characterized without credentials.
 */
class IsoDepProbe : NfcMasterProbe {

    override fun technology(): NfcTechnology = NfcTechnology.ISO_14443_4

    override suspend fun run(env: NfcProbeEnv): ProbeOutcome {
        val isoDep = IsoDep.get(env.tag) ?: return ProbeOutcome()
        val hits = mutableListOf<CredentialHit>()
        val dump = StringBuilder()
        try {
            isoDep.connect()
            isoDep.timeout = 1200
            env.log("ISO 14443-4: identifying…")

            val desfire = identifyDesfire(env, isoDep)
            if (desfire != null) {
                env.log("MIFARE DESFire $desfire detected")
                tryDesfireDefaults(env, isoDep, hits)
            } else {
                val isNdefType4 = selectNdefApp(isoDep)
                if (isNdefType4) env.log("Type 4 NDEF smart card — no credential barrier found")
                else env.log("Type 4 card: no default-credential shortcut found")
            }
        } catch (e: Exception) {
            if (env.isRunning()) env.log("Type 4 probe failed: ${e.message?.take(60)}")
        } finally {
            runCatching { isoDep.close() }
        }
        return ProbeOutcome(hits = hits, dumpHex = dump.toString().trim())
    }

    private fun identifyDesfire(env: NfcProbeEnv, isoDep: IsoDep): String? {
        return runCatching {
            val resp = isoDep.transceive(byteArrayOf(0x90.toByte(), 0x60, 0x00, 0x00, 0x00))
            // ISO-mapped DESFire GET VERSION: leading length/status 0x00 then
            // "04 05 48 00 <major> <minor> <hw> <sw>".
            if (resp.size < 8 || (resp[0].toInt() and 0xFF) != 0x00) return@runCatching null
            if ((resp[2].toInt() and 0xFF) != 0x48) return@runCatching null
            "EV${resp[5].toInt() and 0xFF}.${resp[6].toInt() and 0xFF}"
        }.getOrNull()
    }

    private suspend fun tryDesfireDefaults(
        env: NfcProbeEnv,
        isoDep: IsoDep,
        hits: MutableList<CredentialHit>,
    ) {
        val selected = selectApp(isoDep, byteArrayOf(0x00, 0x00, 0x00))
        if (!selected) {
            env.log("DESFire: PICC AID 000000 not selectable")
            return
        }
        env.log("DESFire: PICC AID 000000 selected — trying default keys")
        for (keyHex in NfcKeyDictionary.DESFIRE_KEYS_HEX) {
            if (!env.isRunning()) break
            val key = runCatching { NfcKeyDictionary.parseHex(keyHex) }.getOrNull() ?: continue
            if (key.size == 8 && authDesfireIso(isoDep, key)) {
                hits += CredentialHit(
                    technology = technology(),
                    label = "DESFire AuthenticateISO key",
                    valueHex = keyHex,
                    notes = "AID 000000",
                )
                env.log("DESFire: 3DES key $keyHex ✓")
                return
            }
            if (key.size == 16 && authDesfireAes(isoDep, key)) {
                hits += CredentialHit(
                    technology = technology(),
                    label = "DESFire AES-128 key",
                    valueHex = keyHex,
                    notes = "AID 000000",
                )
                env.log("DESFire: AES-128 key ${keyHex} ✓")
                return
            }
        }
        env.log("DESFire: no default key matched app 000000")
    }

    /** Select application APDU: 90 5A 00 00 03 <aid3> 00. */
    private fun selectApp(isoDep: IsoDep, aid: ByteArray): Boolean = runCatching {
        val resp = isoDep.transceive(byteArrayOf(0x90.toByte(), 0x5A, 0x00, 0x00, 0x03) + aid + byteArrayOf(0x00))
        resp.isNotEmpty() && (resp[0].toInt() and 0xFF) == 0x00
    }.getOrDefault(false)

    /** AuthenticateISO (legacy 3DES): 90 0A 00 00 08 <key8> 00. */
    private fun authDesfireIso(isoDep: IsoDep, key: ByteArray): Boolean = runCatching {
        val resp = isoDep.transceive(byteArrayOf(0x90.toByte(), 0x0A, 0x00, 0x00, 0x08) + key + byteArrayOf(0x00))
        resp.size >= 3 && (resp[0].toInt() and 0xFF) == 0x00
    }.getOrDefault(false)

    /** AuthenticateAES (EV2+): 90 71 00 00 10 <key16> 00. */
    private fun authDesfireAes(isoDep: IsoDep, key: ByteArray): Boolean = runCatching {
        val resp = isoDep.transceive(byteArrayOf(0x90.toByte(), 0x71, 0x00, 0x00, 0x10) + key + byteArrayOf(0x00))
        resp.size >= 3 && (resp[0].toInt() and 0xFF) == 0x00
    }.getOrDefault(false)

    private fun selectNdefApp(isoDep: IsoDep): Boolean = runCatching {
        val select = byteArrayOf(
            0x00, 0xA4.toByte(), 0x04, 0x00, 0x07,
            0xD2.toByte(), 0x76, 0x00, 0x00, 0x85.toByte(), 0x01, 0x01, 0x00,
        )
        val resp = isoDep.transceive(select)
        val sw1 = resp.getOrNull(resp.size - 2)?.toInt()?.and(0xFF) ?: 0
        val sw2 = resp.getOrNull(resp.size - 1)?.toInt()?.and(0xFF) ?: 0
        sw1 == 0x90 && sw2 == 0x00
    }.getOrDefault(false)
}