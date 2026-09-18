package com.droid.flippy

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class FastPairSingleSpam(private val device: FastPairDevice) : Spammer {

    private var isSpamming = false
    private var blinkRunnable: Runnable? = null
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    override fun start() {
        executor.execute {
            isSpamming = true
            val advertiser = BluetoothAdvertiser()
            // Fast Pair fast path: legacy-only, service UUID 0xFE2C +
            // model service data + Tx Power. Built once, held stable.
            val serviceData = Helper.convertHexToByteArray(device.value)

            while (isSpamming) {
                try {
                    advertiser.advertiseFastPair(serviceData)
                } catch (_: Exception) {
                }
                blinkRunnable?.run()
                try {
                    Thread.sleep(Helper.delay.coerceIn(20, 60).toLong())
                } catch (_: InterruptedException) {
                    break
                }
            }
            try {
                advertiser.stopFastPair()
            } catch (_: Exception) {
            }
            executor.shutdown()
        }
    }

    override fun stop() {
        isSpamming = false
    }

    override fun isSpamming(): Boolean = isSpamming

    override fun setBlinkRunnable(runnable: Runnable?) {
        blinkRunnable = runnable
    }

    override fun getBlinkRunnable(): Runnable? = blinkRunnable
}

