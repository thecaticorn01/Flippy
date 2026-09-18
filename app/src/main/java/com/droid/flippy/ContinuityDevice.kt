package com.droid.flippy

enum class ContinuityType { DEVICE, ACTION, NOTYOURDEVICE }

data class ContinuityDevice(
    val value: String,
    val name: String,
    val deviceType: ContinuityType
)

