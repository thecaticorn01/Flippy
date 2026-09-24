package com.droid.flippy.nfc.master

import android.nfc.Tag

/** A single recovered credential (key / password / "open channel" finding). */
data class CredentialHit(
    val technology: NfcTechnology,
    val label: String,
    val valueHex: String,
    val notes: String = "",
)

/**
 * Probe environment shared by every probe: the discovered tag, a log sink and
 * a cancellation gate. `isRunning` flips to false as soon as the user stops the
 * Master Key session, so probes must check it inside every loop.
 */
class NfcProbeEnv(
    val tag: Tag,
    val log: (String) -> Unit,
    val isRunning: () -> Boolean,
) {
    val uidHex: String
        get() = tag.id?.let { hexOf(it) } ?: ""

    fun hex(bytes: ByteArray): String = hexOf(bytes)

    companion object {
        fun hexOf(bytes: ByteArray): String {
            val sb = StringBuilder(bytes.size * 2)
            bytes.forEach { b ->
                val v = b.toInt() and 0xFF
                if (v < 0x10) sb.append('0')
                sb.append(v.toString(16).uppercase())
            }
            return sb.toString()
        }
    }
}

/** What a single [NfcMasterProbe] found on one tapped tag. */
data class ProbeOutcome(
    val hits: List<CredentialHit> = emptyList(),
    val dumpHex: String = "",
)

/** Thrown by a probe when the tag vanished or the tech handle is unusable. */
class NfcProbeAbort(message: String) : Exception(message)

/**
 * A brute-force / interrogation unit for one NFC technology.
 *
 * Implementations must be rootless (pure SDK transceive/auth), non-blocking
 * through [NfcProbeEnv.isRunning], and safe to run simultaneously — the
 * session runs a single target probe at a time per tap.
 */
interface NfcMasterProbe {
    fun technology(): NfcTechnology
    suspend fun run(env: NfcProbeEnv): ProbeOutcome
}