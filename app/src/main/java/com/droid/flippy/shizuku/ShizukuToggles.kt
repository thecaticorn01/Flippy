package com.droid.flippy.shizuku

import android.content.Context
import android.nfc.NfcAdapter
import android.util.Log
import kotlinx.coroutines.delay

/**
 * Radio/system toggles implemented with shell commands that the shell UID
 * is allowed to run — i.e. they work with adb-started Shizuku on
 * non-rooted devices. No root-only operations are used anywhere here
 * (no remounts, no iptables, no direct sysfs writes, no `su`).
 *
 * Every setter verifies the new state by re-reading it and reports the
 * *verified* outcome, so the UI never shows a toggle that didn't happen.
 */
object ShizukuToggles {

    private const val TAG = "ShizukuToggles"

    // ------------------------------------------------------------------
    // Bluetooth: `svc` flips the switch and applies it, no dialog needed.
    // ------------------------------------------------------------------

    suspend fun bluetoothEnabled(): Boolean? {
        val r = ShizukuManager.exec("settings get global bluetooth_on", 10)
        if (!r.ok) {
            Log.w(TAG, "bt get failed: ${r.err}")
            return null
        }
        return ShizukuManager.parseSettingsFlag(r.out)
    }

    suspend fun setBluetooth(enabled: Boolean): Boolean {
        val want = enabled
        val current = bluetoothEnabled()
        if (current == want) return true
        val cmd = if (want) "svc bluetooth enable" else "svc bluetooth disable"
        val r = ShizukuManager.exec(cmd, 15)
        if (!r.ok) {
            Log.w(TAG, "bt set failed: ${r.err}")
            return false
        }
        return pollUntil(timeoutMs = 8000) { bluetoothEnabled() == want }
    }

    // ------------------------------------------------------------------
    // Wi-Fi: `cmd wifi` on modern releases, `svc wifi` fallback.
    // ------------------------------------------------------------------

    suspend fun wifiEnabled(): Boolean? {
        val r = ShizukuManager.exec("settings get global wifi_on", 10)
        if (!r.ok) return null
        return ShizukuManager.parseSettingsFlag(r.out)
    }

    suspend fun setWifi(enabled: Boolean): Boolean {
        val want = enabled
        if (wifiEnabled() == want) return true
        val arg = if (want) "enable" else "disable"
        // `cmd wifi` may be absent on old releases; fall back to `svc wifi`.
        val r = ShizukuManager.exec(
            "(cmd wifi set-wifi-enabled $arg || svc wifi $arg) 2>&1",
            15
        )
        if (!r.ok) {
            Log.w(TAG, "wifi set failed: ${r.err.ifBlank { r.out }}")
            return false
        }
        return pollUntil(timeoutMs = 8000) { wifiEnabled() == want }
    }

    // ------------------------------------------------------------------
    // NFC: `svc nfc` toggles; state is read with the platform API.
    // ------------------------------------------------------------------

    fun nfcEnabled(context: Context): Boolean? {
        return try {
            NfcAdapter.getDefaultAdapter(context.applicationContext)?.isEnabled
        } catch (t: Throwable) {
            Log.w(TAG, "nfc get", t)
            null
        }
    }

    suspend fun setNfc(context: Context, enabled: Boolean): Boolean {
        val want = enabled
        if (nfcEnabled(context) == want) return true
        val arg = if (want) "enable" else "disable"
        val r = ShizukuManager.exec("svc nfc $arg", 15)
        if (!r.ok) {
            Log.w(TAG, "nfc set failed: ${r.err.ifBlank { r.out }}")
            return false
        }
        return pollUntil(timeoutMs = 8000) { nfcEnabled(context) == want }
    }

    // ------------------------------------------------------------------
    // Airplane mode: secure setting + broadcast, then verify by re-read.
    // ------------------------------------------------------------------

    suspend fun airplaneEnabled(): Boolean? {
        val r = ShizukuManager.exec("settings get global airplane_mode_on", 10)
        if (!r.ok) return null
        return ShizukuManager.parseSettingsFlag(r.out)
    }

    suspend fun setAirplane(enabled: Boolean): Boolean {
        val want = enabled
        if (airplaneEnabled() == want) return true
        val stateInt = if (want) 1 else 0
        val r = ShizukuManager.exec(
            "settings put global airplane_mode_on $stateInt && " +
                "am broadcast -a android.intent.action.AIRPLANE_MODE --ez state $want",
            15
        )
        if (!r.ok) {
            Log.w(TAG, "airplane set failed: ${r.err.ifBlank { r.out }}")
            return false
        }
        return pollUntil(timeoutMs = 8000) { airplaneEnabled() == want }
    }

    // ------------------------------------------------------------------

    private suspend fun pollUntil(timeoutMs: Long, check: suspend () -> Boolean): Boolean {
        val deadline = android.os.SystemClock.uptimeMillis() + timeoutMs
        do {
            try {
                if (check()) return true
            } catch (t: Throwable) {
                Log.w(TAG, "poll check", t)
            }
            delay(400)
        } while (android.os.SystemClock.uptimeMillis() < deadline)
        return try {
            check()
        } catch (_: Throwable) {
            false
        }
    }
}
