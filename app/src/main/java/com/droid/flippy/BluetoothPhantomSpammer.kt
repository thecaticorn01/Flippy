package com.droid.flippy

import kotlinx.coroutines.*
import android.util.Log

class BluetoothPhantomSpammer : Spammer {
    private var isRunning = false
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var blinkRunnable: Runnable? = null
    private var originalName: String? = null

    private val names = listOf(
        "iPhone 15 Pro Max",
        "Tesla Model 3",
        "Sony WH-1000XM5",
        "Flippy Phantom",
        "AirPods Pro",
        "Samsung S24 Ultra",
        "Google Pixel 8",
        "iPad Air",
        "Bose QC45",
        "MacBook Pro"
    )

    override fun isSpamming(): Boolean = isRunning

    override fun start() {
        if (isRunning) return
        isRunning = true
        // Remember the user's real name so stop() can put it back — the old
        // code permanently renamed the phone.
        originalName = try {
            BluetoothHelper.bluetoothAdapter?.name
        } catch (_: Exception) {
            null
        }
        job = scope.launch {
            while (isActive && isRunning) {
                try {
                    val newName = names.random()
                    setName(newName)
                } catch (e: Exception) {
                    Log.e("BluetoothPhantom", "Error rotating name", e)
                }
                delay(1000)
            }
        }
    }

    override fun stop() {
        isRunning = false
        job?.cancel()
        job = null
        originalName?.let { name ->
            runCatching { setName(name) }
            originalName = null
        }
    }

    override fun setBlinkRunnable(runnable: Runnable?) {
        this.blinkRunnable = runnable
    }

    override fun getBlinkRunnable(): Runnable? = blinkRunnable

    private fun setName(name: String) {
        try {
            val adapter = BluetoothHelper.bluetoothAdapter
            if (adapter != null) {
                adapter.name = name
                Log.d("BluetoothPhantom", "Name updated to: $name")
            } else {
                Log.w("BluetoothPhantom", "No Bluetooth adapter")
            }
        } catch (e: SecurityException) {
            Log.e("BluetoothPhantom", "Permission denied for name change", e)
        } catch (e: Exception) {
            Log.e("BluetoothPhantom", "Failed to set name", e)
        }
    }
}

