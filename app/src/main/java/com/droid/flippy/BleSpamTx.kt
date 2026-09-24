package com.droid.flippy

import android.Manifest
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import androidx.annotation.RequiresPermission
import com.droid.flippy.plugin.PluginBluetoothHooks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Single, callback-synchronized advertising transmitter for every BLE-spam
 * mode in the app.
 *
 * WHY THIS EXISTS
 * --------------
 * The platform `BluetoothLeAdvertiser` has ONE legacy slot and processes
 * start/stop requests asynchronously. The old code issued a `stop → start`
 * cycle on every loop iteration (20–60 ms) and counted each call as a
 * "sent packet", even though the radio:
 *   - drops a start that races the previous stop (`ADVERTISE_FAILED_ALREADY_STARTED`),
 *   - quietly idles while the UI shows "active", and
 *   - fragments every popup frame into overlapping stubs, so iOS never sees
 *     a stable model and popups appear late or not at all.
 *
 * WHAT THIS PROVIDES
 * ------------------
 *  1. ONE owner: every spammer talks to the same transmitter, so two modes
 *     can never own separate advertiser callbacks and stomp each other.
 *  2. Stable-frame short circuit: emitting the payload that is already on
 *     air is a no-op — no radio churn. Modes that hold one frame (single
 *     items, dwell loops) keep a rock-solid ADV on air instead of
 *     stop/start thrash, so target popups appear in 1–3 s.
 *  3. Callback handshake: a payload change does exactly ONE stop → start,
 *     serialized on a dedicated thread with a small settle pause, and a
 *     newer payload that arrives while a restart is pending is queued and
 *     swapped in after the radio confirms — requests are never piled up.
 *  4. Honest status: [state] reflects what the radio actually reported via
 *     onStartSuccess/onStartFailure (and a runtime permission re-check), so
 *     the UI can show "Transmitting" vs "Radio error / idle" instead of a
 *     phantom "active".
 *
 * Thread-safety: `emit`/`stop` are safe from any caller thread; callbacks
 * arrive on a binder thread and synchronize on [lock]. The platform can only
 * advertise when the system dialogs/permissions are granted, which other
 * preflight code ([BleGate]) already guarantees before spammers start.
 */
object BleSpamTx {

    private data class Pending(
        val signature: String,
        val connectable: Boolean,
        val data: AdvertiseData,
        val scanResponse: AdvertiseData?,
    )

    /** Live radio state, surfaced to the UI so toggles show real TX. */
    data class TxState(
        val onAir: Boolean = false,
        val signature: String? = null,
        val lastError: String? = null,
    )

    private val lock = Any()

    @Volatile
    private var inFlight: Pending? = null
    @Volatile
    private var queued: Pending? = null
    @Volatile
    private var onAir: Pending? = null

    private val _state = MutableStateFlow(TxState())
    val state: StateFlow<TxState> = _state.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Default)
    private val radioExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ble-spam-tx").apply { priority = Thread.MAX_PRIORITY }
    }

    private fun advertiser() = BluetoothHelper.bluetoothAdapter?.bluetoothLeAdvertiser

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            val nowOnAir = synchronized(lock) {
                val start = inFlight ?: return
                inFlight = null
                onAir = start
                start
            }
            Helper.log("BLE spam on air: ${nowOnAir.signature.take(80)}")
            publish()
            drain()
        }

        override fun onStartFailure(errorCode: Int) {
            synchronized(lock) {
                // Drop the queue too: a payload that fails to start once will
                // keep failing, and retrying in a tight loop only burn the
                // radio thread. The router (runtime/controller) will retry on
                // a fresh user toggle.
                queued = null
                inFlight = null
                onAir = null
            }
            Helper.log("BLE spam radio failure: $errorCode")
            publish(errorCodeToText(errorCode))
        }
    }

    /**
     * Requests [data] to be transmitted under [signature]. Returns true when
     * the request was accepted (became, or already was, the on-air frame).
     * Identical payloads while that payload is already transmitting are
     * short-circuited so loops never churn the radio.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    fun emit(
        signature: String,
        connectable: Boolean,
        data: AdvertiseData,
        scanResponse: AdvertiseData? = null,
    ): Boolean {
        val decision = PluginBluetoothHooks.interceptAdvertising(
            data,
            scanResponse,
            JSONObject().put("source", "ble_spam_tx").put("signature", signature),
        )
        if (decision.skipNative) return true
        val effectiveData = decision.advertiseData
        val effectiveScan = decision.scanResponse
        val effectiveSignature = "$signature|${advertiseSigLeaf(effectiveData)}"

        synchronized(lock) {
            // Short-circuit when the same frame is already on air OR already
            // being put on air (in-flight restart counts: it will be current
            // in a moment). Without the in-flight check, a dwell loop that
            // keeps emitting while a restart is pending would queue the same
            // payload and restart AGAIN after success — the exact churn this
            // class exists to kill.
            val active = inFlight ?: onAir
            if (active != null &&
                active.signature == effectiveSignature &&
                active.connectable == connectable
            ) {
                return true
            }
            val pending = Pending(effectiveSignature, connectable, effectiveData, effectiveScan)
            if (inFlight != null) {
                queued = pending
                return true
            }
            startRadio(pending)
        }
        return true
    }

    /** Halts all advertising and drops any queued restart. */
    fun stop() {
        synchronized(lock) {
            queued = null
            inFlight = null
            onAir = null
        }
        radioExecutor.execute {
            runCatching { advertiser()?.stopAdvertising(advertiseCallback) }
            Helper.log("BLE spam radio stopped")
        }
        publish()
    }

    private fun startRadio(pending: Pending) {
        inFlight = pending
        radioExecutor.execute {
            val adv = advertiser()
            if (adv == null) {
                synchronized(lock) {
                    if (inFlight === pending) {
                        inFlight = null
                        onAir = null
                    }
                }
                publish("BluetoothLeAdvertiser unavailable")
                return@execute
            }
            // One clean stop → start. The settle pause lets the stack release
            // the previous slot so the next start isn't dropped with
            // ADVERTISE_FAILED_ALREADY_STARTED.
            runCatching { adv.stopAdvertising(advertiseCallback) }
            try {
                Thread.sleep(RESTART_PAUSE_MS)
            } catch (_: InterruptedException) {
                return@execute
            }
            // A stop() may have landed while we slept: only take the slot if
            // this pending is still the intended owner, or the radio would
            // resurrect advertising after the user toggled it off.
            val stillOwner = synchronized(lock) { inFlight === pending }
            if (!stillOwner) return@execute
            try {
                adv.startAdvertising(makeSettings(pending), pending.data, pending.scanResponse, advertiseCallback)
            } catch (e: SecurityException) {
                Helper.log("BLE spam permission lost: ${e.message}")
                synchronized(lock) {
                    if (inFlight === pending) {
                        inFlight = null
                        onAir = null
                    }
                }
                publish("Permission/radio error")
                return@execute
            } catch (e: Exception) {
                Helper.log("BLE spam start threw: ${e.message}")
                synchronized(lock) {
                    if (inFlight === pending) {
                        inFlight = null
                        onAir = null
                    }
                }
                publish(e.message ?: "start failed")
                return@execute
            }
            BleSpamRuntime.trackSentPackets()
        }
    }

    private fun makeSettings(pending: Pending): AdvertiseSettings =
        AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(pending.connectable)
            .setTimeout(0)
            .build()

    /** Starts the next queued payload, if any, after a cycle completes. */
    private fun drain() {
        val next = synchronized(lock) {
            val p = queued
            if (p != null && inFlight == null) {
                queued = null
                p
            } else {
                null
            }
        } ?: return
        synchronized(lock) {
            if (inFlight == null) startRadio(next)
        }
    }

    private fun publish(error: String? = null) {
        val s = synchronized(lock) {
            TxState(
                onAir = onAir != null || inFlight != null,
                signature = onAir?.signature ?: inFlight?.signature,
                lastError = error,
            )
        }
        scope.launch { _state.value = s }
    }

    private fun errorCodeToText(code: Int): String = when (code) {
        AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "Already started"
        AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "Data too large"
        AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "Unsupported"
        AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "Internal error"
        AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "Too many advertisers"
        else -> "BLE error $code"
    }

    /** Leaf digesting every record in [data], values included, so two payloads
    that share a manufacturer/company ID (e.g. every Apple frame uses 0x4C)
    produce DIFFERENT leaves and never false-short-circuit. */
    private fun advertiseSigLeaf(data: AdvertiseData): String {
        val flags = buildList {
            if (data.includeDeviceName) add("name")
            if (data.includeTxPowerLevel) add("txpower")
        }.joinToString("|")
        val uuids = data.serviceUuids
            ?.sortedBy { it.toString() }
            ?.joinToString(",")
            .orEmpty()
        val serviceData = data.serviceData.entries
            .sortedBy { it.key.toString() }
            .joinToString(",") { entry -> "${entry.key}:${hex(entry.value)}" }
        val manufacturerMfg = buildList {
            val sparse = data.manufacturerSpecificData
            for (i in 0 until sparse.size()) {
                add("${sparse.keyAt(i)}:${hex(sparse.valueAt(i))}")
            }
        }.sorted().joinToString(",")
        return "$flags|$uuids|$serviceData|$manufacturerMfg"
    }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02X".format(it.toInt() and 0xFF) }

    private const val RESTART_PAUSE_MS = 40L
}