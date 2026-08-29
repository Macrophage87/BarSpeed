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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.macrophage.barspeed.ble.DeviceRole
import com.macrophage.barspeed.model.ConnectionState
import com.macrophage.barspeed.model.DevicePairingPolicy
import com.macrophage.barspeed.model.SensorRole
import com.macrophage.barspeed.ui.BarColors
import com.macrophage.barspeed.ui.components.ConnectionChip
import com.macrophage.barspeed.ui.components.PermissionBanner

/**
 * Which physical accelerometer this one is, issue #156.
 *
 * A and B, never left and right: the owner's ruling is that units get flipped
 * constantly and are corrected in post-processing, so the label is a unit's
 * identity and makes no claim about which end of a bar it was on.
 *
 * Assignment is by ADDRESS and survives power cycles and reconnection order,
 * which is the whole point -- a positional default would change meaning the
 * next time either unit was re-paired, since pairing makes a device its role's
 * preferred one. Two units may advertise identical names, so telling the rows
 * apart takes a ritual -- and only the PREFERRED unit's row can go green
 * before labelling. The second link is pointed at no address until both units
 * carry distinct labels, and it is pointed there by the Record screen, not
 * this one. So switch both units on, note which single row is green, and label
 * the OTHER row by elimination.
 *
 * Clearing a label is offered because a wrong one is worse than none: an
 * unlabelled pair records one stream and says so, a mislabelled pair records
 * two under the wrong names.
 */
@Composable
private fun SensorRoleRow(assigned: SensorRole?, onAssign: (SensorRole?) -> Unit) {
    Spacer(Modifier.height(4.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (assigned == null) "Not labelled" else "Sensor ${assigned.name}",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        SensorRole.entries.forEach { role ->
            FilterChip(
                selected = assigned == role,
                onClick = { onAssign(if (assigned == role) null else role) },
                label = { Text(role.name) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(navController: NavController, viewModel: DevicesViewModel = viewModel()) {
    val known by viewModel.knownDevices.collectAsState()
    val found by viewModel.foundDevices.collectAsState()
    val scanning by viewModel.scanning.collectAsState()
    val scanError by viewModel.scanError.collectAsState()
    val imuState by viewModel.imuState.collectAsState()
    val imuStateB by viewModel.imuStateB.collectAsState()
    val hrmState by viewModel.hrmState.collectAsState()
    val links by viewModel.linkAddresses.collectAsState()
    val roles by viewModel.sensorRoles.collectAsState()

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
                // Keyed on ADDRESS, not on role. Keyed on role, two saved
                // devices of one role showed the same link on both rows --
                // latent while nobody paired two IMUs, and real the moment #156
                // asks them to. `links.imu` and `links.hrm` are the addresses
                // their links are maintaining; `links.imuB` is not -- it is the
                // first non-preferred paired IMU, and the second link is
                // pointed at no address until both units carry distinct labels,
                // so that row shows a link maintaining nothing and reads
                // Disconnected for a healthy unit. See `LinkAddresses`.
                val state =
                    when (device.address) {
                        links.imu -> imuState
                        links.imuB -> imuStateB
                        links.hrm -> hrmState
                        else -> ConnectionState.Disconnected
                    }
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Row(horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(device.name, style = MaterialTheme.typography.titleSmall)
                                Text("${device.role} · ${device.address}", style = MaterialTheme.typography.bodySmall)
                                if (state is ConnectionState.Failed) {
                                    // This screen is the only surface that shows the reason string.
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
                        if (device.role == DeviceRole.IMU) {
                            SensorRoleRow(
                                assigned = roles[device.address],
                                onAssign = { viewModel.setSensorRole(device.address, it) },
                            )
                        }
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
                items(found, key = { it.device.address }) { entry ->
                    val device = entry.device
                    // Keyed by address, so a re-sighting redraws the row it is
                    // already in rather than being treated as a new one. The
                    // ORDER is decided by DeviceScanListPolicy, which does not
                    // read the signal: a device must not move because a packet
                    // arrived (#183).
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                "${device.name} · ${DevicePairingPolicy.unitTag(device.address)}",
                                style = MaterialTheme.typography.titleSmall,
                                color = if (entry.row.alreadyPaired) BarColors.Sub else BarColors.Text,
                            )
                            Text(
                                "${device.address} · ${device.rssi} dBm" +
                                    (device.likelyRole?.let { " · looks like $it" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = BarColors.Sub,
                            )
                            if (entry.row.alreadyPaired) {
                                // Shown rather than hidden: a missing row reads
                                // as "the scan did not find it", and while the
                                // second unit is being paired this row is the
                                // proof the first one is on and in range. The
                                // pair buttons are what must not be here --
                                // pairing again would move the analysed link.
                                Text(
                                    "Already paired · listed above",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = BarColors.Sub,
                                )
                            } else {
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
}
