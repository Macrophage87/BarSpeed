package com.macrophage.barspeed.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.macrophage.barspeed.LiftingApp
import com.macrophage.barspeed.ble.DeviceRole
import com.macrophage.barspeed.ble.DiscoveredDevice
import com.macrophage.barspeed.ble.KnownDevice
import com.macrophage.barspeed.model.SensorRole
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Which paired device each row shows the state of.
 *
 * [imu] and [hrm] ARE the addresses their links are maintaining. [imuB] is
 * not: it is the first paired IMU that is not the preferred one, and the
 * second link is pointed at no address until both units carry distinct labels,
 * so that row can show a Disconnected link beside a perfectly healthy unit.
 * `linkAddresses` states the mechanism once; this type does not restate it.
 */
data class LinkAddresses(
    val imu: String? = null,
    val imuB: String? = null,
    val hrm: String? = null,
)

class DevicesViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as LiftingApp).container

    val knownDevices =
        container.deviceRegistry.knownDevices.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            emptyList(),
        )
    val imuState = container.autoConnect.imuState
    val imuStateB = container.autoConnect.imuStateB
    val hrmState = container.autoConnect.hrmState

    /**
     * Which paired device each of the three rows shows the state of.
     *
     * The screen used to pick a link by ROLE, and its own comment said what
     * that costs: with two saved devices of one role every row showed the same
     * link. That was latent while nobody paired two IMUs and issue #156 makes
     * it real, so the rows are keyed by ADDRESS here instead.
     *
     * The analysed link follows `preferred_imu`; the second row follows the
     * first paired IMU that is not the preferred one. This is NOT the address
     * the second link is maintaining. `SensorCapturePolicy.roster` returns a
     * null `secondaryAddress` under every `DualShortfall`, and that is the
     * only value `setSecondaryImuAddress` is ever given, so until both units
     * carry distinct labels the second link is pointed at nothing and this
     * row's chip reads Disconnected for a healthy unit.
     */
    val linkAddresses =
        combine(
            container.deviceRegistry.knownDevices,
            container.deviceRegistry.preferred(DeviceRole.IMU),
            container.deviceRegistry.preferred(DeviceRole.HRM),
        ) { known, imu, hrm ->
            val paired = known.filter { it.role == DeviceRole.IMU }.map { it.address }
            LinkAddresses(
                imu = imu?.address,
                imuB = paired.firstOrNull { it != imu?.address },
                hrm = hrm?.address,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LinkAddresses())

    /** Which accelerometer the lifter has labelled which, by address. */
    val sensorRoles =
        container.settings.sensorRoles.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            emptyMap(),
        )

    fun setSensorRole(address: String, role: SensorRole?) {
        viewModelScope.launch { container.settings.setSensorRole(address, role) }
    }

    val discovered = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val scanning = MutableStateFlow(false)
    val scanError = MutableStateFlow<String?>(null)

    private var scanJob: Job? = null

    fun toggleScan() {
        if (scanning.value) {
            scanJob?.cancel()
            scanning.value = false
            return
        }
        discovered.value = emptyList()
        scanError.value = null
        scanning.value = true
        scanJob =
            viewModelScope.launch {
                container.bleScanner.scan()
                    .catch { e ->
                        // A null message renders nothing through DevicesScreen's
                        // scanError?.let{}, so a scan that failed looks identical
                        // to one that simply found no devices.
                        scanError.value = e.message ?: "Scan failed (no reason reported)"
                        scanning.value = false
                    }
                    .collect { device ->
                        val current = discovered.value.filterNot { it.address == device.address }
                        discovered.value = (current + device).sortedByDescending { it.rssi }
                    }
            }
    }

    fun pair(device: DiscoveredDevice, role: DeviceRole) {
        viewModelScope.launch {
            container.autoConnect.pairAndConnect(KnownDevice(device.address, device.name, role))
        }
    }

    fun forget(device: KnownDevice) {
        viewModelScope.launch { container.deviceRegistry.forget(device.address) }
    }

    override fun onCleared() {
        scanJob?.cancel()
    }
}
