@file:Suppress("MissingPermission")

package com.macrophage.barspeed.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid
import com.macrophage.barspeed.hrm.HeartRateProfile
import com.macrophage.barspeed.witmotion.WitmotionProtocol
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.UUID

/** Wraps the platform LE scanner as a Flow of discovered devices. */
@SuppressLint("MissingPermission")
class BleScanner {
    fun scan(): Flow<DiscoveredDevice> = callbackFlow {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null || !adapter.isEnabled) {
            close(IllegalStateException("Bluetooth unavailable or disabled"))
            return@callbackFlow
        }
        val callback =
            object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val name = result.device.name ?: result.scanRecord?.deviceName ?: return
                    trySend(
                        DiscoveredDevice(
                            address = result.device.address,
                            name = name,
                            rssi = result.rssi,
                            likelyRole = guessRole(result, name),
                        ),
                    )
                }
            }
        scanner.startScan(
            null,
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            callback,
        )
        awaitClose { runCatching { scanner.stopScan(callback) } }
    }

    private fun guessRole(result: ScanResult, name: String): DeviceRole? {
        val uuids = result.scanRecord?.serviceUuids.orEmpty()
        return when {
            ParcelUuid(UUID.fromString(HeartRateProfile.SERVICE_UUID)) in uuids -> DeviceRole.HRM
            ParcelUuid(UUID.fromString(WitmotionProtocol.SERVICE_UUID)) in uuids -> DeviceRole.IMU
            name.startsWith("WT", ignoreCase = true) -> DeviceRole.IMU
            name.contains("HRM", ignoreCase = true) -> DeviceRole.HRM
            else -> null
        }
    }
}
