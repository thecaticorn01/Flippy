package com.droid.flippy

enum class ContinuityType { DEVICE, ACTION, NOTYOURDEVICE, KEYBOARD }

data class ContinuityDevice(
    val value: String,
    val name: String,
    val deviceType: ContinuityType
)

