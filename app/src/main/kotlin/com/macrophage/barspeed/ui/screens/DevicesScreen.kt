package com.macrophage.barspeed.ui.screens

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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.macrophage.barspeed.ble.DeviceRole
import com.macrophage.barspeed.ui.components.ConnectionChip

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
