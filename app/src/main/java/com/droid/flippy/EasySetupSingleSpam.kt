package com.droid.flippy

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class EasySetupSingleSpam(private val device: EasySetupDevice) : Spammer {

    private var isSpamming = false
    private var blinkRunnable: Runnable? = null
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    override fun start() {
        executor.execute {
            isSpamming = true
            val advertiser = BluetoothAdvertiser()
            // Samsung fast path: legacy-only (+ buds scan response), not
            // extended. Rebuilt per packet is unnecessary — payloads are
            // static — so build once and hold stable.
            val payload = Helper.convertHexToByteArray(device.toManufacturerData())
            val scan: ByteArray? =
                if (device.deviceType == EasySetupDevice.Type.BUDS) {
                    Helper.convertHexToByteArray("0000000000000000000000000000")
                } else {
                    null
                }

            while (isSpamming) {
                try {
                    advertiser.advertiseSamsung(payload, scan)
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
                advertiser.stopSamsung()
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

