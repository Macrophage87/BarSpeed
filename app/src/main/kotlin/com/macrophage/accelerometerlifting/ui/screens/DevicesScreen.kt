package com.macrophage.accelerometerlifting.ui.screens

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(navController: NavController, viewModel: DevicesViewModel = viewModel()) {
    val known by viewModel.knownDevices.collectAsState()
    val discovered by viewModel.discovered.collectAsState()
    val scanning by viewModel.scanning.collectAsState()
    val scanError by viewModel.scanError.collectAsState()
    val imuState by viewModel.imuState.collectAsState()
    val hrmState by viewModel.hrmState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Devices") },
                navigationIcon = {
                    TextButton(onClick = { navController.popBackStack() }) { Text("Back") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text("Paired sensors", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            if (known.isEmpty()) {
                Text(
                    "None yet. Scan below and pair your bar sensor and heart rate strap once — " +
                        "the app auto-connects from then on.",
                )
            }
            known.forEach { device ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(
                        Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(device.name, style = MaterialTheme.typography.titleSmall)
                            Text("${device.role} · ${device.address}", style = MaterialTheme.typography.bodySmall)
                        }
                        ConnectionChip(
                            device.role.name,
                            if (device.role == DeviceRole.IMU) imuState else hrmState,
                        )
                        TextButton(onClick = { viewModel.forget(device) }) { Text("Forget") }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = viewModel::toggleScan, modifier = Modifier.fillMaxWidth()) {
                Text(if (scanning) "Stop scanning" else "Scan for sensors")
            }
            scanError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(discovered) { device ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(device.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "${device.address} · ${device.rssi} dBm" +
                                    (device.likelyRole?.let { " · looks like $it" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { viewModel.pair(device, DeviceRole.IMU) }) {
                                    Text("Pair as bar sensor")
                                }
                                OutlinedButton(onClick = { viewModel.pair(device, DeviceRole.HRM) }) {
                                    Text("Pair as HRM")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
