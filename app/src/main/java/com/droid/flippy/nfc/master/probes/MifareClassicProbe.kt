package com.droid.flippy.nfc.master.probes

import android.nfc.tech.MifareClassic
import android.nfc.tech.NfcA
import com.droid.flippy.nfc.master.CredentialHit
import com.droid.flippy.nfc.master.NfcKeyDictionary
import com.droid.flippy.nfc.master.NfcMasterProbe
import com.droid.flippy.nfc.master.NfcProbeEnv
import com.droid.flippy.nfc.master.NfcTechnology
import com.droid.flippy.nfc.master.ProbeOutcome

/**
 * MIFARE Classic (Crypto1) dictionary attack.
 *
 * Primary path uses the public [MifareClassic] SDK, which can authenticate a
 * sector with KeyA/KeyB and afterwards dump the sector's data blocks. A raw
 * [NfcA] fallback (0x60/0x61 AUTH + 0x30 READ) is used on devices that do not
 * expose the MifareClassic tech handle — e.g. when only generic ISO-14443-A is
 * reported — so the dictionary still runs against those tags.
 *
 * All crypto happens in the tag/reader; nothing here needs root.
 */
class MifareClassicProbe : NfcMasterProbe {

    override fun technology(): NfcTechnology = NfcTechnology.MIFARE_CLASSIC

    override suspend fun run(env: NfcProbeEnv): ProbeOutcome {
        val keysHex = NfcKeyDictionary.MIFARE_CLASSIC_HEX
        val keys = keysHex.mapNotNull { runCatching { NfcKeyDictionary.classicKeyToBytes(it) }.getOrNull() }

        val classic = MifareClassic.get(env.tag)
        if (classic != null) return runSdkPath(env, classic, keysHex, keys)
        return runRawPath(env, keysHex, keys)
    }

    private suspend fun runSdkPath(
        env: NfcProbeEnv,
        classic: MifareClassic,
        keysHex: List<String>,
        keys: List<ByteArray>,
    ): ProbeOutcome {
        val hits = mutableListOf<CredentialHit>()
        val dump = StringBuilder()
        try {
            classic.connect()
            val sectors = classic.sectorCount
            env.log("MIFARE Classic: $sectors sectors, ${keys.size} keys")
            for (sector in 0 until sectors) {
                if (!env.isRunning()) break
                var hitKey: ByteArray? = null
                var hitSlot = ""
                for (k in keys) {
                    if (runCatching { classic.authenticateSectorWithKeyA(sector, k) }.getOrDefault(false)) {
                        hitKey = k; hitSlot = "A"; break
                    }
                    if (runCatching { classic.authenticateSectorWithKeyB(sector, k) }.getOrDefault(false)) {
                        hitKey = k; hitSlot = "B"; break
                    }
                }
                val keyHit = hitKey
                if (keyHit != null) {
                    val hex = env.hex(keyHit)
                    hits += CredentialHit(
                        technology = technology(),
                        label = "MIFARE Classic Key$hitSlot",
                        valueHex = hex,
                        notes = "sector $sector",
                    )
                    env.log("Sector $sector: Key$hitSlot $hex ✓")
                    dumpSdkSector(env, classic, sector)?.let { dump.append(it) }
                } else {
                    env.log("Sector $sector: no dictionary key")
                }
            }
        } catch (e: Exception) {
            if (env.isRunning()) env.log("Classic read failed: ${e.message?.take(60)}")
        } finally {
            runCatching { classic.close() }
        }
        return ProbeOutcome(hits = hits, dumpHex = dump.toString().trim())
    }

    /** Auth + dump through a generic ISO-14443-A handle. */
    private suspend fun runRawPath(
        env: NfcProbeEnv,
        keysHex: List<String>,
        keys: List<ByteArray>,
    ): ProbeOutcome {
        val nfcA = NfcA.get(env.tag) ?: return ProbeOutcome()
        val hits = mutableListOf<CredentialHit>()
        try {
            nfcA.connect()
            env.log("Classic tech not exposed — raw ISO-14443-A AUTH, ${keys.size} keys")
            var found = 0
            for (block in 0 until 64) {
                if (!env.isRunning()) break
                for ((i, k) in keys.withIndex()) {
                    if (authRaw(nfcA, block, k, keyB = false)) {
                        hits += CredentialHit(
                            technology = technology(),
                            label = "MIFARE Classic KeyA (raw)",
                            valueHex = keysHex[i],
                            notes = "block $block",
                        )
                        env.log("Block $block: KeyA ${keysHex[i]} ✓")
                        found++
                        break
                    }
                    if (authRaw(nfcA, block, k, keyB = true)) {
                        hits += CredentialHit(
                            technology = technology(),
                            label = "MIFARE Classic KeyB (raw)",
                            valueHex = keysHex[i],
                            notes = "block $block",
                        )
                        env.log("Block $block: KeyB ${keysHex[i]} ✓")
                        found++
                        break
                    }
                }
            }
            if (found == 0) env.log("Raw path: no dictionary key on this card")
        } catch (e: Exception) {
            if (env.isRunning()) env.log("Classic raw path failed: ${e.message?.take(60)}")
        } finally {
            runCatching { nfcA.close() }
        }
        return ProbeOutcome(hits = hits)
    }

    private fun authRaw(nfcA: NfcA, block: Int, key: ByteArray, keyB: Boolean): Boolean {
        val cmd = ByteArray(9)
        cmd[0] = if (keyB) 0x61 else 0x60
        cmd[1] = block.toByte()
        key.copyInto(cmd, 2)
        return runCatching {
            val resp = nfcA.transceive(cmd)
            resp.size >= 4
        }.getOrDefault(false)
    }

    private fun dumpSdkSector(env: NfcProbeEnv, classic: MifareClassic, sector: Int): String? {
        return try {
            val first = classic.sectorToBlock(sector)
            val count = classic.getBlockCountInSector(sector)
            val sb = StringBuilder()
            for (b in 0 until count) {
                if (!env.isRunning()) break
                val data = classic.readBlock(first + b)
                sb.append("blk %02d %s\n".format(first + b, env.hex(data)))
            }
            sb.toString()
        } catch (_: Exception) {
            null
        }
    }
}