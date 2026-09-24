package com.droid.flippy.nfc.master.probes

import android.nfc.tech.NfcB
import com.droid.flippy.nfc.master.CredentialHit
import com.droid.flippy.nfc.master.NfcMasterProbe
import com.droid.flippy.nfc.master.NfcProbeEnv
import com.droid.flippy.nfc.master.NfcTechnology
import com.droid.flippy.nfc.master.ProbeOutcome

/**
 * ISO 14443-B interrogation.
 *
 * Type B cards are used by Calypso / transit and several government ID
 * families. The Android stack performs anticollision, so a Type B discovery
 * already yields the PUPI/UID. This probe reports the discovered token and
 * attempts a lightweight "system info" read if the card exposes one; deeper
 * PICC-level auth is application-specific and left to plugins.
 *
 * Rootless NfcB handle — nothing privileged.
 */
class NfcBProbe : NfcMasterProbe {

    override fun technology(): NfcTechnology = NfcTechnology.ISO_14443_B

    override suspend fun run(env: NfcProbeEnv): ProbeOutcome {
        val nfcB = NfcB.get(env.tag) ?: return ProbeOutcome()
        val hits = mutableListOf<CredentialHit>()
        try {
            nfcB.connect()
            env.log("ISO 14443-B PICC (PUPI/UID ${env.uidHex.ifBlank { "?" }})")
            val appData = readApplicationData(nfcB)
            if (appData != null) {
                hits += CredentialHit(
                    technology = technology(),
                    label = "ISO 14443-B PUPI",
                    valueHex = env.uidHex,
                    notes = "open application data",
                )
                env.log("ISO 14443-B: application data ${env.hex(appData)}")
            } else {
                env.log("ISO 14443-B: no public application-data response")
            }
        } catch (e: Exception) {
            if (env.isRunning()) env.log("ISO 14443-B probe failed: ${e.message?.take(60)}")
        } finally {
            runCatching { nfcB.close() }
        }
        return ProbeOutcome(hits = hits)
    }

    private fun readApplicationData(nfcB: NfcB): ByteArray? = runCatching {
        // Best-effort: many ISO14443B PICCs tolerate a null-length command and
        // echo system/application data; failures are silently ignored.
        val resp = nfcB.transceive(byteArrayOf(0x00))
        resp.takeIf { it.isNotEmpty() && it.size >= 2 }
    }.getOrNull()
}