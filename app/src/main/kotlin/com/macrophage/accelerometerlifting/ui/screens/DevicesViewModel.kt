package com.macrophage.accelerometerlifting.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.macrophage.accelerometerlifting.LiftingApp
import com.macrophage.accelerometerlifting.ble.DeviceRole
import com.macrophage.accelerometerlifting.ble.DiscoveredDevice
import com.macrophage.accelerometerlifting.ble.KnownDevice
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DevicesViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as LiftingApp).container

    val knownDevices =
        container.deviceRegistry.knownDevices.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            emptyList(),
        )
    val imuState = container.autoConnect.imuState
    val hrmState = container.autoConnect.hrmState

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
                        scanError.value = e.message
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
