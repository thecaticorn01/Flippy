package com.droid.flippy.nfc.master.probes

import android.nfc.tech.NfcV
import com.droid.flippy.nfc.master.CredentialHit
import com.droid.flippy.nfc.master.NfcMasterProbe
import com.droid.flippy.nfc.master.NfcProbeEnv
import com.droid.flippy.nfc.master.NfcTechnology
import com.droid.flippy.nfc.master.ProbeOutcome

/**
 * ISO 15693 "Vicinity" card read.
 *
 * Vicinity tags are common in library / asset / access lanyard systems and are
 * frequently left with public (unauthenticated) reads. This probe maps which
 * blocks are readable without any credential and reports an "open channel"
 * finding — the closest analogue to a master-key hit on RFC 15693 gear.
 *
 * Plain NfcV transceive, no root.
 */
class NfcVProbe : NfcMasterProbe {

    override fun technology(): NfcTechnology = NfcTechnology.ISO_15693

    override suspend fun run(env: NfcProbeEnv): ProbeOutcome {
        val nfcV = NfcV.get(env.tag) ?: return ProbeOutcome()
        val hits = mutableListOf<CredentialHit>()
        val dump = StringBuilder()
        try {
            nfcV.connect()
            env.log("ISO 15693: probing system info + block access")
            systemInfo(env, nfcV)
            var open = 0
            val blocksToProbe = 24
            for (block in 0 until blocksToProbe) {
                if (!env.isRunning()) break
                val data = readBlock(nfcV, block)
                if (data != null) {
                    dump.append("blk %02d %s\n".format(block, env.hex(data)))
                    open++
                } else {
                    break
                }
            }
            if (open > 0) {
                hits += CredentialHit(
                    technology = technology(),
                    label = "Vicinity tag (public read)",
                    valueHex = "$open blocks",
                    notes = "no authentication barrier",
                )
                env.log("ISO 15693: $open/addressable blocks readable without auth")
            } else {
                env.log("ISO 15693: no public blocks readable")
            }
        } catch (e: Exception) {
            if (env.isRunning()) env.log("ISO 15693 probe failed: ${e.message?.take(60)}")
        } finally {
            runCatching { nfcV.close() }
        }
        return ProbeOutcome(hits = hits, dumpHex = dump.toString().trim())
    }

    private fun systemInfo(env: NfcProbeEnv, nfcV: NfcV) {
        runCatching {
            val resp = nfcV.transceive(byteArrayOf(0x01, 0x2B.toByte(), 0x03, 0x00, 0x00))
            val status = resp.getOrNull(1)?.toInt()?.and(0xFF)
            if (status == 0 && resp.size >= 5) {
                env.log("DSFID=0x%02X AFI=0x%02X IC=0x%04X".format(
                    resp[2].toInt() and 0xFF,
                    resp[3].toInt() and 0xFF,
                    ((resp[4].toInt() and 0xFF) shl 8) or (resp.getOrNull(5)?.toInt()?.and(0xFF) ?: 0),
                ))
            }
        }
    }

    /** READ_SINGLE_BLOCK (non-addressed). Returns block payload or null on NAK. */
    private fun readBlock(nfcV: NfcV, block: Int): ByteArray? = runCatching {
        val resp = nfcV.transceive(byteArrayOf(0x00, 0x20, block.toByte()))
        val status = resp.getOrNull(1)?.toInt()?.and(0xFF)
        if (status != 0 || resp.size < 6) return@runCatching null
        resp.copyOfRange(2, 6)
    }.getOrNull()
}