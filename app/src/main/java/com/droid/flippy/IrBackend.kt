package com.droid.flippy

import android.content.Context
import android.hardware.ConsumerIrManager
import android.os.Looper
import android.util.Log
import com.droid.flippy.ir.usb.UsbIrManager
import com.droid.flippy.plugin.PluginManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

object IrBackend {
    private const val SERVICE_ID = "infrared.transmitter"

    fun isAvailable(context: Context): Boolean {
        val pluginAvailable = PluginManager.invokeServices(SERVICE_ID, "available")
            .any { truthy(it.valueJson) }
        if (pluginAvailable) return true
        val manager = context.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager
        if (manager?.hasIrEmitter() == true) return true
        // USB dongles need no built-in blaster; cached flag, safe on any thread.
        return runCatching { UsbIrManager.isUsbPresentCached() }.getOrDefault(false)
    }

    fun hasBuiltInEmitter(context: Context): Boolean {
        val manager = context.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager
        return manager?.hasIrEmitter() == true
    }

    fun carrierFrequencies(context: Context): List<ConsumerIrManager.CarrierFrequencyRange> {
        val manager = context.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager
        return runCatching { manager?.carrierFrequencies?.toList().orEmpty() }.getOrDefault(emptyList())
    }

    /**
     * Transmit one button. Order: plugin action hooks → plugin transmitter
     * services → USB dongle (unless route is BUILT_IN_ONLY) → built-in
     * ConsumerIrManager.
     *
     * Blocking: USB bulk transfers carry timeouts, so callers must use a
     * background thread (all current UI call sites already dispatch to
     * Dispatchers.IO).
     */
    fun transmit(context: Context, button: IrButton): Boolean {
        if (button.pattern.isEmpty() || button.pattern.any { it <= 0 } || button.frequency <= 0) return false
        var payload = JSONObject()
            .put("name", button.name ?: "")
            .put("frequency", button.frequency)
            .put("pattern", JSONArray(button.pattern.toList()))
            .put("protocol", button.protocol)
            .put("code", button.irCode)
            .put("timings", button.timings)
        val decision = PluginManager.invokeActionHooks("infrared.transmit", payload.toString())
        if (decision.cancelled) return false
        payload = runCatching { JSONObject(decision.payloadJson) }.getOrDefault(payload)
        if (decision.handled) {
            val success = decision.resultJson?.let(::truthy) ?: true
            if (success) trackIrSend(context)
            return success
        }
        PluginManager.invokeServices(SERVICE_ID, "transmit", payload.toString()).forEach { response ->
            val result = parseServiceResult(response.valueJson)
            if (result.first) {
                if (result.second) trackIrSend(context)
                return result.second
            }
        }
        val frequency = payload.optInt("frequency", button.frequency)
        val array = payload.optJSONArray("pattern")
        val pattern = if (array == null) button.pattern else IntArray(array.length()) { array.optInt(it) }
        if (frequency <= 0 || pattern.isEmpty() || pattern.any { it <= 0 }) return false

        if (tryUsbTransmit(frequency, pattern)) {
            trackIrSend(context)
            return true
        }
        val manager = context.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager
            ?: return false
        if (!manager.hasIrEmitter()) return false
        return runCatching {
            manager.transmit(frequency, pattern)
            trackIrSend(context)
            true
        }.onFailure { Log.e("IrBackend", "IR transmission failed", it) }.getOrDefault(false)
    }

    /**
     * Attempts the USB path. Never throws, never touches the main thread's
     * ability to stay responsive beyond the (short) bulk timeouts — callers
     * are already on Dispatchers.IO. Returns false when USB is disabled,
     * absent, or the transfer failed so the caller falls through to the
     * built-in blaster.
     */
    private fun tryUsbTransmit(frequencyHz: Int, pattern: IntArray): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // Defensive: bulkTransfer timeouts must never run on the UI thread.
            Log.w("IrBackend", "USB transmit skipped on main thread")
            return false
        }
        return runCatching {
            runBlocking(Dispatchers.IO) {
                UsbIrManager.transmit(frequencyHz, pattern)
            }
        }.onFailure { Log.w("IrBackend", "USB transmit error", it) }.getOrDefault(false)
    }

    private fun parseServiceResult(raw: String): Pair<Boolean, Boolean> {
        val objectResult = runCatching { JSONObject(raw) }.getOrNull()
        if (objectResult != null) {
            return objectResult.optBoolean("handled", true) to objectResult.optBoolean("ok", true)
        }
        return if (raw.equals("true", true)) true to true else false to false
    }

    private fun truthy(raw: String): Boolean {
        val objectResult = runCatching { JSONObject(raw) }.getOrNull()
        return objectResult?.optBoolean("available", objectResult.optBoolean("ok", false))
            ?: raw.equals("true", true)
    }
}
