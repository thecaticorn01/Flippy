package com.droid.flippy

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Stable identity for one toggleable item on a BLE platform screen.
 * section = BleSection.route, group = index inside the section, item =
 * device name. Title strings are localized so they must NOT be used as keys.
 */
data class BleSpamKey(val section: String, val group: Int, val item: String)

/** Outcome of the pre-transmit checks in [BleSpamController.preflight]. */
enum class BlePreflight {
    OK,
    NO_ADAPTER,
    BT_DISABLED,
    NO_PERMISSION,
    NO_ADVERTISER,
    FAILED,
}

/**
 * App-scoped (ViewModel-held) state for the per-item toggles on the BLE
 * platform screens (BleSectionScreen).
 *
 * Previously each screen kept its own `remember` map and stopped everything
 * in `DisposableEffect.onDispose`, so selections vanished on back navigation
 * and toggles kept running nowhere. This controller survives navigation and
 * rotation; spammers are stopped only when toggled off, via stop-all, or
 * when the ViewModel is cleared.
 *
 * Threading: all public methods are safe from any thread; [start] blocks
 * only for the (fast) pre-checks, the spammer itself runs on its own thread.
 */
class BleSpamController {

    private val lock = Any()
    private val spammers = mutableMapOf<BleSpamKey, Spammer>()

    private val _active = MutableStateFlow<Set<BleSpamKey>>(emptySet())
    val active: StateFlow<Set<BleSpamKey>> = _active.asStateFlow()

    private val _expanded = MutableStateFlow<Set<String>>(emptySet())
    val expanded: StateFlow<Set<String>> = _expanded.asStateFlow()

    fun isActive(key: BleSpamKey): Boolean = synchronized(lock) {
        spammers.containsKey(key)
    }

    fun activeInSection(sectionRoute: String): Set<BleSpamKey> =
        _active.value.filterTo(mutableSetOf()) { it.section == sectionRoute }

    /**
     * Verifies that advertising can actually start *before* the UI is
     * flipped to "active". Returns non-OK when the toggle must not be
     * marked active (caller shows the reason to the user).
     */
    fun preflight(context: Context): BlePreflight = check(context)

    companion object {
        /**
         * Static version of [preflight] for callers that don't own a
         * controller (e.g. the whole-mode toggles on the BLE home screen).
         */
        fun check(context: Context): BlePreflight {
            return try {
                val adapter = BluetoothHelper.bluetoothAdapter ?: return BlePreflight.NO_ADAPTER
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val adv = ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE)
                    val conn = ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                    if (adv != PackageManager.PERMISSION_GRANTED || conn != PackageManager.PERMISSION_GRANTED) {
                        return BlePreflight.NO_PERMISSION
                    }
                }
                val enabled = try {
                    adapter.isEnabled
                } catch (e: SecurityException) {
                    return BlePreflight.NO_PERMISSION
                }
                if (!enabled) return BlePreflight.BT_DISABLED
                try {
                    adapter.bluetoothLeAdvertiser ?: return BlePreflight.NO_ADVERTISER
                } catch (e: SecurityException) {
                    return BlePreflight.NO_PERMISSION
                }
                if (!adapter.isMultipleAdvertisementSupported) return BlePreflight.NO_ADVERTISER
                BlePreflight.OK
            } catch (t: Throwable) {
                Log.w("BleSpamController", "preflight failed", t)
                BlePreflight.FAILED
            }
        }
    }

    /** Starts [key] if not already running. Only call after [preflight] == OK. */
    fun start(key: BleSpamKey, factory: () -> Spammer): BlePreflight {
        synchronized(lock) {
            if (spammers.containsKey(key)) return BlePreflight.OK
        }
        val spammer = factory()
        try {
            spammer.start()
        } catch (t: Throwable) {
            Log.w("BleSpamController", "spammer start failed for $key", t)
            return BlePreflight.FAILED
        }
        synchronized(lock) {
            spammers[key] = spammer
            _active.value = spammers.keys.toSet()
        }
        return BlePreflight.OK
    }

    fun stop(key: BleSpamKey) {
        val spammer = synchronized(lock) {
            val removed = spammers.remove(key)
            _active.value = spammers.keys.toSet()
            removed
        }
        runCatching { spammer?.stop() }
    }

    fun stopSection(sectionRoute: String) {
        val removed = synchronized(lock) {
            val keys = spammers.keys.filter { it.section == sectionRoute }
            keys.mapNotNull { spammers.remove(it) }.also {
                _active.value = spammers.keys.toSet()
            }
        }
        removed.forEach { runCatching { it.stop() } }
    }

    fun stopAll() {
        val removed = synchronized(lock) {
            val all = spammers.values.toList()
            spammers.clear()
            _active.value = emptySet()
            all
        }
        removed.forEach { runCatching { it.stop() } }
    }

    fun setExpanded(id: String, expanded: Boolean) {
        _expanded.value = if (expanded) {
            _expanded.value + id
        } else {
            _expanded.value - id
        }
    }

    /** Called from ViewModel.onCleared: never leave the radio blasting. */
    fun shutdown() {
        stopAll()
    }
}
