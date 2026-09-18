package com.droid.flippy.ir.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * App-wide USB IR dongle manager: discovery, permission, open/close and
 * transmit, shared by [com.droid.flippy.IrBackend], the IR screens and the
 * plugin bridge.
 *
 * Android 14–16 notes (targetSdk 36):
 * - USB permission uses a MUTABLE PendingIntent on API 31+ because the
 *   system fills in the granted-device extras at send time.
 * - The permission/attach/detach receiver is registered dynamically with
 *   RECEIVER_NOT_EXPORTED on API 33+ (required since Android 13, still
 *   required on 16). All broadcasts involved are either explicit to our
 *   package or protected system broadcasts, so non-exported is correct.
 * - No extra runtime permission is needed for USB host; [UsbManager.deviceList]
 *   is callable without permission (permission is per-device, for open).
 * - All blocking USB I/O runs on Dispatchers.IO under a Mutex; the UI only
 *   observes [state].
 */
object UsbIrManager {

    const val ACTION_USB_PERMISSION = "com.droid.flippy.ir.usb.USB_PERMISSION"

    private const val TAG = "UsbIrManager"
    private const val PREFS = "DolphyPrefs"
    private const val KEY_ROUTE = "ir_transmitter_route"
    private const val KEY_ALLOW_GENERIC = "ir_usb_allow_generic"

    enum class Route { AUTO, USB_ONLY, BUILT_IN_ONLY }

    data class DeviceInfo(
        val device: UsbDevice,
        val label: String,
        val isSupported: Boolean,
        val isCandidate: Boolean,
        val hasPermission: Boolean
    ) {
        val vidPid: String =
            "0x${device.vendorId.toString(16).uppercase()}:" +
                "0x${device.productId.toString(16).uppercase()}"
    }

    data class State(
        val devices: List<DeviceInfo> = emptyList(),
        val connectedLabel: String? = null,
        val connectedProtocol: String? = null,
        val isOpen: Boolean = false,
        val route: Route = Route.AUTO,
        val allowGeneric: Boolean = false,
        val lastError: String? = null
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var cachedUsbPresent: Boolean = false

    private val mutex = Mutex()
    private var transmitter: UsbIrTransmitter? = null
    private var receiverRegistered = false

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val device: UsbDevice? =
                        if (Build.VERSION.SDK_INT >= 33) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }
                    val granted =
                        intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    Log.i(TAG, "USB permission granted=$granted device=${device?.deviceName}")
                    refresh()
                    if (!granted) {
                        _state.update {
                            it.copy(lastError = "USB permission denied for ${device?.deviceName}")
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    Log.i(TAG, "USB device attached")
                    refresh()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    Log.i(TAG, "USB device detached")
                    val detached: UsbDevice? =
                        if (Build.VERSION.SDK_INT >= 33) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }
                    onDeviceDetached(detached)
                    refresh()
                }
            }
        }
    }

    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        loadPrefs(app)
        registerReceiverOnce(app)
        refresh()
    }

    /** Call from MainActivity.onNewIntent for USB_DEVICE_ATTACHED launches. */
    fun onDeviceAttachedIntent() {
        refresh()
    }

    fun setRoute(route: Route) {
        _state.update { it.copy(route = route, lastError = null) }
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()?.putString(KEY_ROUTE, route.name)?.apply()
    }

    fun setAllowGeneric(allow: Boolean) {
        _state.update { it.copy(allowGeneric = allow, lastError = null) }
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()?.putBoolean(KEY_ALLOW_GENERIC, allow)?.apply()
    }

    fun clearError() {
        _state.update { it.copy(lastError = null) }
    }

    // ------------------------------------------------------------------
    // Discovery / permission (safe on any thread; no blocking USB calls)
    // ------------------------------------------------------------------

    fun refresh() {
        val ctx = appContext ?: return
        val usb = ctx.getSystemService(Context.USB_SERVICE) as? UsbManager
        if (usb == null) {
            _state.update { it.copy(devices = emptyList()) }
            cachedUsbPresent = false
            return
        }
        val devices = try {
            usb.deviceList.values.map { dev ->
                DeviceInfo(
                    device = dev,
                    label = UsbIrDeviceFilter.shortLabel(dev),
                    isSupported = UsbIrDeviceFilter.isSupported(dev),
                    isCandidate = UsbIrDeviceFilter.isCandidate(dev),
                    hasPermission = runCatching { usb.hasPermission(dev) }.getOrDefault(false)
                )
            }.sortedWith(
                compareByDescending<DeviceInfo> { it.isSupported }
                    .thenByDescending { it.isCandidate }
                    .thenBy { it.label }
            )
        } catch (t: Throwable) {
            Log.w(TAG, "refresh failed", t)
            emptyList()
        }
        val open = transmitter != null
        cachedUsbPresent = open || devices.any { it.isSupported }
        _state.update {
            it.copy(
                devices = devices,
                isOpen = open,
                connectedLabel = if (open) it.connectedLabel else null,
                connectedProtocol = if (open) it.connectedProtocol else null
            )
        }
    }

    /** Fast cached check for [com.droid.flippy.IrBackend.isAvailable]. */
    fun isUsbPresentCached(): Boolean = cachedUsbPresent

    fun hasPermission(device: UsbDevice): Boolean {
        val ctx = appContext ?: return false
        val usb = ctx.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
        return runCatching { usb.hasPermission(device) }.getOrDefault(false)
    }

    fun requestPermission(device: UsbDevice) {
        val ctx = appContext ?: return
        val usb = ctx.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return
        if (runCatching { usb.hasPermission(device) }.getOrDefault(false)) {
            refresh()
            return
        }
        try {
            val intent = Intent(ACTION_USB_PERMISSION).setPackage(ctx.packageName)
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= 31) {
                // System must be able to attach the device/grant extras.
                flags = flags or PendingIntent.FLAG_MUTABLE
            }
            // Distinct request code per device so concurrent requests for
            // two dongles do not overwrite each other.
            val pi = PendingIntent.getBroadcast(
                ctx, device.deviceName.hashCode(), intent, flags
            )
            usb.requestPermission(device, pi)
        } catch (t: Throwable) {
            Log.w(TAG, "requestPermission failed", t)
            _state.update { it.copy(lastError = "USB permission request failed: ${t.message}") }
        }
    }

    // ------------------------------------------------------------------
    // Open / close / transmit (suspending, Dispatchers.IO)
    // ------------------------------------------------------------------

    suspend fun connect(device: UsbDevice? = null): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val ctx = appContext ?: return@withContext false
            val usb = ctx.getSystemService(Context.USB_SERVICE) as? UsbManager
                ?: return@withContext false
            closeLocked()
            val target = device ?: pickTargetLocked(usb)
            if (target == null) {
                _state.update {
                    it.copy(lastError = "No supported USB IR dongle found")
                }
                return@withContext false
            }
            if (!runCatching { usb.hasPermission(target) }.getOrDefault(false)) {
                requestPermission(target)
                _state.update {
                    it.copy(lastError = "USB permission requested — tap Connect again after granting")
                }
                refresh()
                return@withContext false
            }
            val opened = openInternalLocked(usb, target, _state.value.allowGeneric)
            if (opened == null) {
                _state.update {
                    it.copy(lastError = "Could not open ${UsbIrDeviceFilter.shortLabel(target)}")
                }
                refresh()
                return@withContext false
            }
            transmitter = opened
            _state.update {
                it.copy(
                    isOpen = true,
                    connectedLabel = UsbIrDeviceFilter.shortLabel(target),
                    connectedProtocol = opened.protocolName,
                    lastError = null
                )
            }
            cachedUsbPresent = true
            refresh()
            true
        }
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        mutex.withLock { closeLocked() }
        refresh()
    }

    /**
     * Transmits via USB when the current [Route] allows it.
     * Returns false when USB is not usable so the caller (IrBackend) can
     * fall through to the built-in blaster.
     */
    suspend fun transmit(frequencyHz: Int, patternUs: IntArray): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (_state.value.route == Route.BUILT_IN_ONLY) return@withContext false
                if (patternUs.isEmpty()) return@withContext false
                val ctx = appContext ?: return@withContext false
                val usb = ctx.getSystemService(Context.USB_SERVICE) as? UsbManager
                    ?: return@withContext false

                var tx = transmitter
                if (tx == null) {
                    val target = pickTargetLocked(usb) ?: return@withContext false
                    if (!runCatching { usb.hasPermission(target) }.getOrDefault(false)) {
                        requestPermission(target)
                        _state.update {
                            it.copy(lastError = "USB permission needed for ${UsbIrDeviceFilter.shortLabel(target)}")
                        }
                        return@withContext false
                    }
                    tx = openInternalLocked(usb, target, _state.value.allowGeneric)
                    if (tx == null) return@withContext false
                    transmitter = tx
                    _state.update {
                        it.copy(
                            isOpen = true,
                            connectedLabel = UsbIrDeviceFilter.shortLabel(target),
                            connectedProtocol = tx.protocolName,
                            lastError = null
                        )
                    }
                    cachedUsbPresent = true
                }
                val ok = try {
                    tx.transmit(frequencyHz, patternUs)
                } catch (t: Throwable) {
                    Log.w(TAG, "USB transmit failed", t)
                    false
                }
                if (!ok) {
                    // Drop a possibly stale handle (e.g. unplugged without a
                    // detach broadcast); the next press re-opens cleanly.
                    closeLocked()
                    refresh()
                }
                ok
            }
        }

    /** Outcome of one [learnButton] attempt. */
    sealed interface LearnResult {
        /** A button press was captured; pattern is mark/space µs. */
        data class Captured(val patternUs: IntArray) : LearnResult
        /** Nothing arrived before the timeout or the user cancelled. */
        data object Timeout : LearnResult
        /** No open dongle, or the dongle family cannot learn. */
        data class Unavailable(val reason: String) : LearnResult
        /** USB I/O failed mid-session. */
        data class Error(val message: String) : LearnResult
    }

    /** True when the currently open dongle implements IR learning. */
    fun isLearningSupported(): Boolean =
        transmitter?.supportsLearning == true

    /**
     * Captures one button press from a physical remote via a learning
     * capable USB dongle (ElkSmart family). The open transmitter is used
     * as-is — connect first. Cancellation cooperates through the calling
     * coroutine (the UI Cancel button cancels the scope).
     */
    suspend fun learnButton(timeoutMs: Long): LearnResult =
        withContext(Dispatchers.IO) {
            val ioJob = coroutineContext[Job]
            mutex.withLock {
                val tx = transmitter
                    ?: return@withContext LearnResult.Unavailable("no dongle connected")
                val protocol = tx.wireProtocol
                if (protocol !is ElkSmartUsbProtocol) {
                    return@withContext LearnResult.Unavailable("dongle cannot learn")
                }
                if (tx.isClosed) {
                    closeLocked()
                    refresh()
                    return@withContext LearnResult.Unavailable("dongle disconnected")
                }
                try {
                    ensureActive()
                    val pattern = protocol.learnCapture(
                        tx.usbConnection,
                        tx.usbInEndpoint,
                        tx.usbOutEndpoint,
                        timeoutMs,
                        isCancelled = { ioJob?.isCancelled != false }
                    )
                    if (pattern == null || pattern.isEmpty()) {
                        LearnResult.Timeout
                    } else {
                        LearnResult.Captured(pattern)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w(TAG, "USB learn failed", t)
                    closeLocked()
                    refresh()
                    LearnResult.Error(t.message ?: "USB error")
                }
            }
        }

    fun describeJson(): String {
        val s = _state.value
        val devices = s.devices.joinToString(",") { d ->
            "{\"label\":${jsonStr(d.label)}," +
                "\"vid\":${d.device.vendorId},\"pid\":${d.device.productId}," +
                "\"supported\":${d.isSupported},\"candidate\":${d.isCandidate}," +
                "\"permission\":${d.hasPermission}}"
        }
        return "{\"open\":${s.isOpen}," +
            "\"connected\":${jsonStr(s.connectedLabel ?: "")}," +
            "\"protocol\":${jsonStr(s.connectedProtocol ?: "")}," +
            "\"route\":${jsonStr(s.route.name)}," +
            "\"devices\":[$devices]}"
    }

    // ------------------------------------------------------------------
    // Internals (mutex must be held)
    // ------------------------------------------------------------------

    private fun pickTargetLocked(usb: UsbManager): UsbDevice? {
        val all = runCatching { usb.deviceList.values.toList() }.getOrNull().orEmpty()
        if (all.isEmpty()) return null
        val allowGeneric = _state.value.allowGeneric
        // Prefer: permitted + supported → supported → permitted candidate → candidate.
        all.firstOrNull {
            UsbIrDeviceFilter.isSupported(it) &&
                runCatching { usb.hasPermission(it) }.getOrDefault(false)
        }?.let { return it }
        all.firstOrNull { UsbIrDeviceFilter.isSupported(it) }?.let { return it }
        if (allowGeneric) {
            all.firstOrNull {
                UsbIrDeviceFilter.isCandidate(it) &&
                    runCatching { usb.hasPermission(it) }.getOrDefault(false)
            }?.let { return it }
            all.firstOrNull { UsbIrDeviceFilter.isCandidate(it) }?.let { return it }
        }
        return null
    }

    private fun openInternalLocked(
        usb: UsbManager,
        device: UsbDevice,
        allowGeneric: Boolean
    ): UsbIrTransmitter? {
        val knownSupported = UsbIrDeviceFilter.isSupported(device)
        if (!knownSupported && !(allowGeneric && UsbIrDeviceFilter.isCandidate(device))) {
            Log.w(TAG, "Refusing to open unknown device ${device.deviceName}")
            return null
        }
        val protocol: UsbWireProtocol = when {
            UsbIrDeviceFilter.isElkSmart(device) -> ElkSmartUsbProtocol()
            else -> TiqiaaUsbProtocol
        }
        Log.i(
            TAG, "open ${UsbIrDeviceFilter.shortLabel(device)} " +
                "ifCount=${device.interfaceCount} protocol=${protocol.name}"
        )
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            for (pair in findEndpointPairs(intf)) {
                val conn: UsbDeviceConnection =
                    runCatching { usb.openDevice(device) }.getOrNull() ?: run {
                        Log.w(TAG, "openDevice() returned null")
                        return null
                    }
                val claimed = runCatching { conn.claimInterface(intf, true) }.getOrDefault(false)
                if (!claimed) {
                    Log.w(TAG, "claimInterface failed for if=${intf.id}")
                    runCatching { conn.close() }
                    continue
                }
                val tx = UsbIrTransmitter.create(
                    device = device,
                    connection = conn,
                    claimedInterface = intf,
                    outEndpoint = pair.outEp,
                    inEndpoint = pair.inEp,
                    protocol = protocol
                )
                if (tx != null) {
                    Log.i(TAG, "Opened USB IR on if=${intf.id} ep=${pair.number}")
                    return tx
                }
                runCatching { conn.releaseInterface(intf) }
                runCatching { conn.close() }
            }
        }
        Log.w(TAG, "No usable bulk interface on ${device.deviceName}")
        return null
    }

    private data class EndpointPair(val outEp: UsbEndpoint, val inEp: UsbEndpoint, val number: Int)

    private fun findEndpointPairs(intf: UsbInterface): List<EndpointPair> {
        fun collect(transferType: Int): List<EndpointPair> {
            val outByNum = HashMap<Int, UsbEndpoint>()
            val inByNum = HashMap<Int, UsbEndpoint>()
            for (i in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(i)
                if (ep.type != transferType) continue
                val num = ep.address and UsbConstants.USB_ENDPOINT_NUMBER_MASK
                if (ep.direction == UsbConstants.USB_DIR_OUT) outByNum[num] = ep
                if (ep.direction == UsbConstants.USB_DIR_IN) inByNum[num] = ep
            }
            return (outByNum.keys intersect inByNum.keys).sorted().mapNotNull { n ->
                val out = outByNum[n]
                val inn = inByNum[n]
                if (out != null && inn != null) EndpointPair(out, inn, n) else null
            }
        }
        // Strict preference: matched bulk IN+OUT pair on the same endpoint
        // number (what the known dongles expose)…
        collect(UsbConstants.USB_ENDPOINT_XFER_BULK).takeIf { it.isNotEmpty() }?.let { return it }
        // …then any bulk OUT + any bulk IN on the same interface…
        val bulkOut = ArrayList<UsbEndpoint>()
        val bulkIn = ArrayList<UsbEndpoint>()
        for (i in 0 until intf.endpointCount) {
            val ep = intf.getEndpoint(i)
            if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (ep.direction == UsbConstants.USB_DIR_OUT) bulkOut.add(ep)
            if (ep.direction == UsbConstants.USB_DIR_IN) bulkIn.add(ep)
        }
        if (bulkOut.isNotEmpty() && bulkIn.isNotEmpty()) {
            return listOf(
                EndpointPair(
                    bulkOut.first(),
                    bulkIn.first(),
                    bulkOut.first().address and UsbConstants.USB_ENDPOINT_NUMBER_MASK
                )
            )
        }
        // …finally interrupt pairs (some clones use interrupt transfers).
        return collect(UsbConstants.USB_ENDPOINT_XFER_INT)
    }

    private fun closeLocked() {
        try {
            transmitter?.close()
        } catch (_: Throwable) {
        }
        transmitter = null
        _state.update { it.copy(isOpen = false, connectedLabel = null, connectedProtocol = null) }
    }

    private fun onDeviceDetached(detached: UsbDevice?) {
        val tx = transmitter ?: return
        if (detached == null || detached.deviceName == tx.device.deviceName) {
            try {
                tx.close()
            } catch (_: Throwable) {
            }
            transmitter = null
        }
    }

    private fun loadPrefs(app: Context) {
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val route = runCatching { Route.valueOf(prefs.getString(KEY_ROUTE, Route.AUTO.name)!!) }
            .getOrDefault(Route.AUTO)
        val allowGeneric = prefs.getBoolean(KEY_ALLOW_GENERIC, false)
        _state.update { it.copy(route = route, allowGeneric = allowGeneric) }
    }

    private fun registerReceiverOnce(app: Context) {
        if (receiverRegistered) return
        receiverRegistered = true
        try {
            val filter = IntentFilter().apply {
                addAction(ACTION_USB_PERMISSION)
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
            ContextCompat.registerReceiver(
                app, permissionReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (t: Throwable) {
            Log.w(TAG, "USB receiver registration failed", t)
            receiverRegistered = false
        }
    }

    private fun jsonStr(s: String): String {
        val escaped = s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r")
        return "\"$escaped\""
    }
}
