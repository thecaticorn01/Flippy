package com.droid.flippy.nfc.master

import android.nfc.Tag
import com.droid.flippy.nfc.master.probes.IsoDepProbe
import com.droid.flippy.nfc.master.probes.MifareClassicProbe
import com.droid.flippy.nfc.master.probes.MifareUltralightProbe
import com.droid.flippy.nfc.master.probes.NfcBProbe
import com.droid.flippy.nfc.master.probes.NfcVProbe

/** Complete result of interrogating one tapped tag across every probe. */
data class NfcMasterReport(
    val uidHex: String,
    val detectedTechnologies: List<NfcTechnology>,
    val primaryTechnology: NfcTechnology,
    val hits: List<CredentialHit>,
    val dumpHex: String,
    val logs: List<String>,
)

/**
 * Orchestrates the Master Key interrogation of one discovered tag.
 *
 * Probe selection follows the tech list Android attached to the tag:
 * the Classic / Ultralight / Type-4 / 15693 / 14443-B probes run against
 * their matching handles. A tag exposed only as generic ISO-14443-A is tried
 * through the raw Crypto1 path first, then the Ultralight page/password path,
 * so hidden CK/Crypto1 and NTAG cards are still interrogated.
 *
 * All probes are constructed here so each tap gets an isolated engine; a probe
 * that throws is contained and logged rather than aborting the session.
 */
object NfcMasterSession {

    private val classicProbe = MifareClassicProbe()
    private val ultraProbe = MifareUltralightProbe()
    private val isoDepProbe = IsoDepProbe()
    private val nfcVProbe = NfcVProbe()
    private val nfcBProbe = NfcBProbe()

    suspend fun run(tag: Tag, isRunning: () -> Boolean, log: (String) -> Unit): NfcMasterReport {
        val logs = mutableListOf<String>()
        val sink: (String) -> Unit = { msg ->
            logs += msg
            log(msg)
        }

        val detected = NfcTechnology.detect(tag)
        sink("Technologies: ${detected.joinToString(", ") { it.displayName }}")

        val hits = mutableListOf<CredentialHit>()
        val dump = StringBuilder()

        val env = NfcProbeEnv(tag, sink, isRunning)

        suspend fun runProbe(probe: NfcMasterProbe) {
            if (!isRunning()) return
            try {
                sink("Probing ${probe.technology().displayName}…")
                val outcome = probe.run(env)
                hits += outcome.hits
                if (outcome.dumpHex.isNotBlank()) {
                    dump.append("── ${probe.technology().displayName} ──\n")
                    dump.append(outcome.dumpHex).append('\n')
                }
            } catch (e: Exception) {
                sink("${probe.technology().displayName} error: ${e.message?.take(60)}")
            }
        }

        val isClassicTech = NfcTechnology.MIFARE_CLASSIC in detected
        val isUltraTech = NfcTechnology.MIFARE_ULTRALIGHT in detected
        val onlyGenericNfcA = detected.all {
            it == NfcTechnology.NFC_A || it == NfcTechnology.NDEF || it == NfcTechnology.UNKNOWN
        }

        if (isClassicTech) runProbe(classicProbe)
        if (isUltraTech) runProbe(ultraProbe)

        if (onlyGenericNfcA) {
            // No Classic / UL tech exposed: hunt both families raw.
            sink("No Classic/UL tech exposed — fallback scan")
            runProbe(classicProbe)
            runProbe(ultraProbe)
        }

        if (NfcTechnology.ISO_14443_4 in detected) runProbe(isoDepProbe)
        if (NfcTechnology.ISO_15693 in detected) runProbe(nfcVProbe)
        if (NfcTechnology.ISO_14443_B in detected) runProbe(nfcBProbe)

        val primary = detected.firstOrNull { it != NfcTechnology.NDEF && it != NfcTechnology.UNKNOWN }
            ?: NfcTechnology.UNKNOWN

        return NfcMasterReport(
            uidHex = env.uidHex,
            detectedTechnologies = detected,
            primaryTechnology = primary,
            hits = hits,
            dumpHex = dump.toString().trim(),
            logs = logs,
        )
    }
}