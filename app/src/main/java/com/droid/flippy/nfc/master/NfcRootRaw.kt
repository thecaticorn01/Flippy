package com.droid.flippy.nfc.master

import android.content.Context
import android.nfc.NfcAdapter
import com.droid.flippy.RootUtils
import com.droid.flippy.shizuku.ShizukuManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Root / Shizuku "controller" operations for the Master Key engine.
 *
 * IMPORTANT — temporary-root safe by design:
 *  - Everything here runs `svc` / `cmd nfc` / `settings` only. Those write
 *    through the normal system-service settings database (user-data backed)
 *    and touch no system/vendor/product partition images, so a locked
 *    bootloader with a *temporary* root drop (e.g. a custom-recovery or
 *    adb-root session that is not persisted) will not be reverted or trip the
 *    bootloader's partition verification.
 *  - All operations are best-effort and reversible: quitting the Master Key
 *    screen or toggling the service off returns to stock behaviour.
 *  - Nothing here ever remounts or patches a partition.
 *
 * The cryptographic interrogation itself (see the probes) is pure SDK API and
 * needs no privilege. Elevation only buys us: (1) NFC forced on while the
 * radio is commander-disabled, (2) payment/routing conflicts cleared so the
 * reader isn't hijacked by the payment stack, (3) visibility of the raw NCI
 * kernel node when one exists (informational — drives the "Controller direct"
 * badge in the UI).
 */
object NfcRootRaw {

    enum class Elevation { ROOT, SHIZUKU, NONE }

    data class RootContext(
        val via: Elevation,
        val nfcEnabled: Boolean,
        val rawNciNode: String?,
        val notes: List<String>,
    ) {
        val rawAccess: Boolean get() = rawNciNode != null
    }

    suspend fun prepare(app: android.content.Context): RootContext = withContext(Dispatchers.IO) {
        val elevation = detectElevation()
        val notes = mutableListOf<String>()
        val adapter = runCatching { NfcAdapter.getDefaultAdapter(app) }.getOrNull()

        when (elevation) {
            Elevation.ROOT -> {
                runPrivileged(elevation, notes, "svc nfc enable")
                runPrivileged(elevation, notes, "cmd nfc enable")
                runPrivileged(elevation, notes, "settings put global nfc_on 1")
                runPrivileged(elevation, notes, "settings put secure nfc_payment_default_component null")
                runPrivileged(elevation, notes, "cmd nfc set-controller-always-on enabled", includeOut = false)
            }
            Elevation.SHIZUKU -> {
                runPrivileged(elevation, notes, "svc nfc enable")
                runPrivileged(elevation, notes, "cmd nfc enable")
                runPrivileged(elevation, notes, "settings put global nfc_on 1")
                runPrivileged(elevation, notes, "settings put secure nfc_payment_default_component null")
            }
            Elevation.NONE -> {
                notes += "No root or Shizuku — NFC state left to the system"
            }
        }

        var enabled = adapter?.isEnabled == true
        if (!enabled && elevation != Elevation.NONE) {
            repeat(8) {
                if (runCatching { adapter?.isEnabled == true }.getOrDefault(false)) {
                    enabled = true
                    return@repeat
                }
                delay(250)
            }
        }

        val nciNode: String? = if (elevation == Elevation.ROOT) scanNciNode() else null
        nciNode?.let { notes += "Raw NCI node visible: $it" }

        RootContext(
            via = elevation,
            nfcEnabled = enabled,
            rawNciNode = nciNode,
            notes = notes,
        )
    }

    /** UI helper: current raw NCI node, or null when absent/unreachable. */
    fun currentNode(): String? = if (detectElevation() == Elevation.ROOT) {
        runCatching { scanNciNode() }.getOrNull()
    } else {
        null
    }

    private fun detectElevation(): Elevation {
        val rooted = runCatching { RootUtils.isRooted() }.getOrDefault(false)
        if (rooted) return Elevation.ROOT
        val shizuku = runCatching {
            ShizukuManager.refresh()
            ShizukuManager.isReady()
        }.getOrDefault(false)
        return if (shizuku) Elevation.SHIZUKU else Elevation.NONE
    }

    private suspend fun runPrivileged(
        elevation: Elevation,
        notes: MutableList<String>,
        command: String,
        includeOut: Boolean = true,
    ) {
        if (elevation == Elevation.ROOT) {
            val (code, out) = runCatching { RootUtils.executeRootCommand(command) }
                .getOrDefault(Pair(-1, "root runner unavailable"))
            notes += "root: $command → $code${if (includeOut && out.isNotBlank()) " ${out.take(40)}" else ""}"
        } else {
            val result = ShizukuManager.exec(command, timeoutSec = 15)
            notes += "shizuku: $command → ${result.code}${if (includeOut && result.out.isNotBlank()) " ${result.out.take(40)}" else ""}"
        }
    }

    /** Best-effort scan for the NXP/Samsung NCI character device. */
    private fun scanNciNode(): String? {
        val candidates = listOf(
            "/dev/pn544",
            "/dev/nfc",
            "/dev/nfc0",
            "/dev/nci0",
            "/dev/nfc/nxp",
        )
        return runCatching {
            RootUtils.executeRootCommand("ls -l ${candidates.joinToString(" ")}").second
                .lineSequence()
                .mapNotNull { line ->
                    val tokens = line.trim().split(Regex("\\s+"))
                    val path = tokens.lastOrNull()?.removeSuffix(":")
                    path?.takeIf { p ->
                        p.startsWith("/dev/") &&
                            (p.endsWith("pn544") || p.contains("nfc") || p.contains("nci"))
                    }
                }
                .firstOrNull()
        }.getOrNull()
    }
}