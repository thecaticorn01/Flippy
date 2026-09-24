package com.droid.flippy.nfc.master.probes

import android.nfc.tech.MifareUltralight
import android.nfc.tech.NfcA
import com.droid.flippy.nfc.master.CredentialHit
import com.droid.flippy.nfc.master.NfcKeyDictionary
import com.droid.flippy.nfc.master.NfcMasterProbe
import com.droid.flippy.nfc.master.NfcProbeEnv
import com.droid.flippy.nfc.master.NfcTechnology
import com.droid.flippy.nfc.master.ProbeOutcome

/**
 * MIFARE Ultralight / NTAG interrogation.
 *
 * Covers three families through rootless APIs:
 *  - NTAG21x (213/215/216): GET_VERSION ID check plus PWD_AUTH (0x1B) brute of
 *    the 4-byte password against the shared dictionary.
 *  - MIFARE Ultralight-C: detected and reported (3DES key search needs a
 *    native AUTH 0x1A + DESede exchange that SDK surfaces don't expose).
 *  - Plain Ultralight / unprotected NTAG: straight page dump, plus an Auth0
 *    judgement so password-protected config is reported even when unlocked.
 *
 * Everything here is raw NfcA transceive / MifareUltralight — no root.
 */
class MifareUltralightProbe : NfcMasterProbe {

    override fun technology(): NfcTechnology = NfcTechnology.MIFARE_ULTRALIGHT

    override suspend fun run(env: NfcProbeEnv): ProbeOutcome {
        val nfcA = NfcA.get(env.tag) ?: return ProbeOutcome()
        val hits = mutableListOf<CredentialHit>()
        val dump = StringBuilder()
        try {
            nfcA.connect()
            val version = getVersion(nfcA)
            val ntag = layoutFor(version)
            env.log(
                if (ntag != null) "NTAG ${ntag.name} (GET_VERSION OK) — probing password + pages"
                else "MIFARE Ultralight family — probing password + pages",
            )

            val auth0 = readAuth0(nfcA, ntag)
            val protected = auth0 != null &&
                auth0 != 0xFF &&
                (ntag?.let { auth0 <= it.userEndPage } ?: (auth0 <= 0x3F))

            appendPages(env, dump, nfcA, 0, 4)

            if (protected == true) {
                env.log("Password protected (Auth0=${auth0?.let { "0x%02X".format(it.toInt() and 0xFF) } ?: "?"})")
                val unlocked = brutePwdAuth(env, nfcA, hits)
                if (unlocked) {
                    env.log("PWD_AUTH unlocked — dumping user pages")
                    appendPages(env, dump, nfcA, 4, ntag?.userEndPage ?: 40)
                } else {
                    env.log("No dictionary password matched (PWD_AUTH)")
                }
            } else {
                if (protected == false) env.log("Not password protected — free read")
                appendPages(env, dump, nfcA, 4, ntag?.userEndPage ?: 40)
                ultralightCNote(env)
            }
        } catch (e: Exception) {
            if (env.isRunning()) env.log("Ultralight read failed: ${e.message?.take(60)}")
        } finally {
            runCatching { nfcA.close() }
        }
        return ProbeOutcome(hits = hits, dumpHex = dump.toString().trim())
    }

    private data class NtagLayout(
        val name: String,
        val userEndPage: Int,
        val configStartPage: Int,
    )

    private fun layoutFor(version: ByteArray?): NtagLayout? {
        if (version == null || version.size < 8) return null
        val storage = version[6].toInt() and 0xFF
        return when (storage) {
            0x0F -> NtagLayout("NTAG213", 0x27, 0x29)
            0x11 -> NtagLayout("NTAG215", 0x81, 0x83)
            0x13 -> NtagLayout("NTAG216", 0xE1, 0xE3)
            else -> null
        }
    }

    private fun getVersion(nfcA: NfcA): ByteArray? = runCatching {
        val resp = nfcA.transceive(byteArrayOf(0x60.toByte()))
        resp.takeIf { it.isNotEmpty() && (it[0].toInt() and 0xFF) == 0x00 }
    }.getOrNull()

    /** Auth0 lives at the config page's byte 3; unprotected NTAG21x return it openly. */
    private fun readAuth0(nfcA: NfcA, ntag: NtagLayout?): Int? {
        if (ntag == null) return null
        return runCatching {
            val resp = nfcA.transceive(byteArrayOf(0x30.toByte(), ntag.configStartPage.toByte()))
            if (resp.size < 4) return@runCatching null
            resp[3].toInt() and 0xFF
        }.getOrNull()
    }

    private fun appendPages(
        env: NfcProbeEnv,
        dump: StringBuilder,
        nfcA: NfcA,
        from: Int,
        toInclusive: Int,
    ) {
        var page = from
        while (page <= toInclusive) {
            if (!env.isRunning()) break
            val resp = runCatching {
                nfcA.transceive(byteArrayOf(0x30.toByte(), page.toByte()))
            }.getOrNull()
            if (resp != null && resp.size >= 16) {
                for (p in 0 until 4) {
                    val start = p * 4
                    val hex = NfcProbeEnv.hexOf(resp.copyOfRange(start, (start + 4).coerceAtMost(resp.size)))
                    dump.append("pg %02X %s\n".format(page + p, hex))
                }
            } else if (resp != null && resp.size >= 4) {
                val hex = NfcProbeEnv.hexOf(resp.copyOfRange(0, resp.size))
                dump.append("pg %02X %s\n".format(page, hex))
            } else {
                dump.append("pg %02X (unreadable — protected)\n".format(page))
            }
            page += 4
        }
    }

    private suspend fun brutePwdAuth(
        env: NfcProbeEnv,
        nfcA: NfcA,
        hits: MutableList<CredentialHit>,
    ): Boolean {
        val dict = NfcKeyDictionary.ULTRALIGHT_PASSWORDS_HEX
        env.log("PWD_AUTH: ${dict.size} passwords…")
        for (pwdHex in dict) {
            if (!env.isRunning()) break
            val pwd = runCatching { NfcKeyDictionary.pwdToBytes(pwdHex) }.getOrNull() ?: continue
            val success = runCatching {
                val resp = nfcA.transceive(byteArrayOf(0x1B.toByte()) + pwd)
                resp.isNotEmpty() && (resp[0].toInt() and 0xFF) == 0xAF && resp.size >= 5
            }.getOrDefault(false)
            if (success) {
                hits += CredentialHit(
                    technology = technology(),
                    label = "NTAG PWD_AUTH password",
                    valueHex = pwdHex,
                )
                env.log("PWD_AUTH: $pwdHex ✓")
                return true
            }
        }
        return false
    }

    /**
     * MIFARE Ultralight-C detection note. The factory 3DES key set
     * ([NfcKeyDictionary.ULTRALIGHT_C_KEYS_HEX]) is kept for reference, but
     * android-36 exposes no SDK authenticate() for the 3DES exchange (it must be
     * driven with raw NfcA AUTH 0x1A + DESede challenge/response + parity
     * verification, which needs a native implementation to complete honestly).
     */
    private fun ultralightCNote(env: NfcProbeEnv) {
        val mfu = MifareUltralight.get(env.tag) ?: return
        val type = runCatching { mfu.type }.getOrNull()
        if (type == MifareUltralight.TYPE_ULTRALIGHT_C) {
            env.log("Ultralight-C detected — 3DES key search needs a native auth exchange")
        }
    }
}