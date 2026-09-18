package com.droid.flippy.scooter

data class ScooterDevice(
    val address: String,
    val name: String,
    val rssi: Int,
    val batteryPercent: Int? = null,
    val isConnecting: Boolean = false,
    val isConnected: Boolean = false,
    val statusMessage: String? = null,
)

