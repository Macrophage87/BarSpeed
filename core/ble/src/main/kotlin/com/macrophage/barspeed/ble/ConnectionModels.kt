package com.macrophage.barspeed.ble

import kotlinx.serialization.Serializable

/** The two device roles the app manages. Each role has one preferred device. */
enum class DeviceRole { IMU, HRM }

@Serializable
data class KnownDevice(
    val address: String,
    val name: String,
    val role: DeviceRole,
)

data class DiscoveredDevice(
    val address: String,
    val name: String,
    val rssi: Int,
    val likelyRole: DeviceRole?,
)
