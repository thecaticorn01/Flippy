package com.droid.flippy

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class SwiftPairSingleSpam(
    private val name: String,
    private val isHeadphone: Boolean
) : Spammer {

    private var isSpamming = false
    private var blinkRunnable: Runnable? = null
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    override fun start() {
        executor.execute {
            isSpamming = true
            val advertiser = BluetoothAdvertiser()
            // Swift Pair fast path: legacy-only + connectable (see
            // BluetoothAdvertiser.advertiseSwiftPair). Payload is built once
            // and held stable — one name, one radio, no flapping.
            val payload = if (isHeadphone) {
                SwiftPairPayloads.headphonePayload(name)
            } else {
                SwiftPairPayloads.normalPayload(name)
            }

            while (isSpamming) {
                try {
                    advertiser.advertiseSwiftPair(payload)
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
                advertiser.stopSwiftPair()
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

