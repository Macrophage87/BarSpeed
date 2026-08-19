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
import com.macrophage.barspeed.model.ConnectionState
import com.macrophage.barspeed.ui.components.ConnectionChip
import com.macrophage.barspeed.ui.components.PermissionBanner

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
            // The troubleshooting screen. The per-device reason line below is
            // left alone rather than replaced: it reports one link's last
            // failure and carries five other reasons besides this one, and it
            // is driven by ConnectionState where this is driven by the gate.
            // They disagree for up to one backoff period after a grant, because
            // the client keeps its stale Failed until the next connect attempt.
            // No Spacer here: PermissionBanner carries its own trailing gap so
            // one is not paid when the banner draws nothing (GRANTED).
            PermissionBanner()
            Text("Paired sensors", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            if (known.isEmpty()) {
                Text(
                    "None yet. Scan below and pair your bar sensor and heart rate strap once — " +
                        "the app auto-connects from then on.",
                )
            }
            known.forEach { device ->
                // Keyed on role, not on device: with two saved devices of one
                // role every row shows the same link. Pre-existing, and not
                // touched here.
                val state = if (device.role == DeviceRole.IMU) imuState else hrmState
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(
                        Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(device.name, style = MaterialTheme.typography.titleSmall)
                            Text("${device.role} · ${device.address}", style = MaterialTheme.typography.bodySmall)
                            if (state is ConnectionState.Failed) {
                                // This screen is the only surface that *does*
                                // show why, which is a choice rather than a
                                // constraint: Home and Record draw
                                // SensorDot(label, connected, connecting), and
                                // widening it to carry ConnectionState is four
                                // call sites plus the view model, not done here.
                                Text(
                                    state.reason,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        ConnectionChip(device.role.name, state)
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
