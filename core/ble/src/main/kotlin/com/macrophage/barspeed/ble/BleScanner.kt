// #98: ci.yml runs Android Lint only on :app (`:app:lintDebug`); :core:ble has no lint step
// at all, so this suppression is not holding back a gate -- there is no gate here to hold
// back. It is a deliberate "leave lint off for :core:ble" choice, stated rather than implied
// by omission, not evidence that a lint run has judged the calls below clean.
@file:Suppress("MissingPermission")

package com.macrophage.barspeed.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid
import com.macrophage.barspeed.hrm.HeartRateProfile
import com.macrophage.barspeed.model.ConnectionFailureReason
import com.macrophage.barspeed.witmotion.WitmotionProtocol
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.UUID

/** Wraps the platform LE scanner as a Flow of discovered devices. */
@SuppressLint("MissingPermission")
class BleScanner {
    fun scan(): Flow<DiscoveredDevice> = callbackFlow {
        // Two reasons rather than one "unavailable or disabled" (UX review
        // 2026-11-06, ble-devices F5): a phone with Bluetooth switched off is
        // the common case and has a one-tap fix, which the Devices screen
        // offers only when it can tell that case apart. `bluetoothLeScanner`
        // is null while the adapter is off, so that null reads as off too.
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            close(IllegalStateException(ConnectionFailureReason.BLUETOOTH_UNAVAILABLE))
            return@callbackFlow
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null || !adapter.isEnabled) {
            close(IllegalStateException(ConnectionFailureReason.PHONE_BLUETOOTH_OFF))
            return@callbackFlow
        }
        val callback =
            object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    // This callback is invoked by the framework outside the
                    // callbackFlow builder's own coroutine, so no downstream
                    // .catch and no awaitClose can ever see a throw from here;
                    // it must be closed locally or not at all. scanRecord's
                    // local name needs no permission at all (unlike
                    // device.name, BLUETOOTH_CONNECT-gated from API 31) and is
                    // tried first for that reason, but many devices omit a
                    // local name from their advertising payload, so the
                    // gated fallback is still reached often enough that it
                    // has to be caught too, not just reordered after.
                    val name = try {
                        result.scanRecord?.deviceName ?: result.device.name
                    } catch (e: SecurityException) {
                        null
                    } ?: return
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
