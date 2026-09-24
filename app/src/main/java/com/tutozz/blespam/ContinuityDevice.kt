package com.tutozz.blespam

enum class ContinuityType { DEVICE, ACTION, NOTYOURDEVICE, KEYBOARD }

data class ContinuityDevice(
    val value: String,
    val name: String,
    val deviceType: ContinuityType
)
