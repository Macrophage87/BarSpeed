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
import com.macrophage.barspeed.model.DeviceLinkRole
import com.macrophage.barspeed.model.DevicePairingPolicy
import com.macrophage.barspeed.model.DualSensorSetup
import com.macrophage.barspeed.model.DualSetupStep
import com.macrophage.barspeed.model.PreferenceControl
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
 * which is the whole point -- the preferred address is movable at any time, by
 * "Use this one for analysis" (`DeviceRegistry.setPreferred`) and by forgetting
 * the analysed unit, so a positional default would change meaning under the
 * lifter. An earlier draft said it would change the next time either unit was
 * re-paired; `DeviceRegistry.pair` no longer moves a preference that names a
 * still-paired device, and that clause is deleted rather than reworded.
 *
 * Two units may advertise identical names. The ritual that used to live here
 * -- switch both on, note which single row is green, label the OTHER by
 * elimination -- is gone in both senses: it was in a KDoc, where the person
 * holding two identical sensors cannot read it, and since #184 it is also
 * wrong, because pairing no longer moves the preference and there is no longer
 * a moment when exactly one row is green. What replaces it is on the screen:
 * every row carries its address tail as a tag, and a row sighted by a running
 * scan shows its live signal, so holding one unit against the phone names it.
 * `DualSensorSetup.identifyHint` says so where it is needed.
 *
 * The second link is pointed at an address by this screen's own ViewModel
 * since #192, so a labelled second unit is armed here rather than waiting for
 * a set on the Record screen. An earlier draft said the Record screen and not
 * this one pointed it; #192 made that false and it is deleted rather than
 * reworded. A unit no link is maintaining still reads "Not linked", which is
 * said as absence rather than drawn as a failed connection.
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

/**
 * What to do next about a two-accelerometer setup, issue #184.
 *
 * The lifter doing the pairing is on THIS screen, and until #184 the only
 * sentence saying that labelling is a required step drew on the Record screen.
 * The wording is `DualSensorSetup`'s, shared with that screen rather than
 * written twice.
 *
 * Draws nothing at the two steps with nothing to fix -- one sensor is the
 * ordinary setup and a line that is always there is a line nobody reads.
 */
@Composable
private fun DualSetupCard(step: DualSetupStep) {
    val line = DualSensorSetup.devicesLine(step) ?: return
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(
                line,
                style = MaterialTheme.typography.bodySmall,
                color = if (step == DualSetupStep.READY) BarColors.Sub else BarColors.Amber,
            )
            DualSensorSetup.identifyHint(step)?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = BarColors.Sub)
            }
        }
    }
}

/**
 * Which unit of a role the app actually reads, and how to move it.
 *
 * Pairing used to move this silently, which is what made the flow read as one
 * sensor knocking the other off (#184). It is now an act with its own control
 * and its own words. For a bar sensor the second unit's stream is still
 * recorded when both are labelled; what "analysed" decides is which one the
 * figures come from.
 *
 * The words are `DevicePairingPolicy.preferenceControl`'s, not this file's,
 * because no test on the CI path can render a `@Composable`, and which rows
 * offer to move a preference is a rule `:core:model` can hold.
 */
@Composable
private fun PreferenceRow(control: PreferenceControl, onUse: () -> Unit) {
    Spacer(Modifier.height(4.dp))
    when (control) {
        is PreferenceControl.InUse ->
            Text(control.line, style = MaterialTheme.typography.bodySmall, color = BarColors.Sub)
        is PreferenceControl.Offer -> TextButton(onClick = onUse) { Text(control.label) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(navController: NavController, viewModel: DevicesViewModel = viewModel()) {
    val known by viewModel.knownDevices.collectAsState()
    val found by viewModel.foundDevices.collectAsState()
    val setupStep by viewModel.dualSetupStep.collectAsState()
    val rssi by viewModel.sightedRssi.collectAsState()
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
            DualSetupCard(setupStep)
            known.forEach { device ->
                // Keyed on ADDRESS, not on role. Keyed on role, two saved
                // devices of one role showed the same link on both rows --
                // latent while nobody paired two IMUs, and real the moment #156
                // asks them to. All three addresses in `links` are addresses a
                // link is actually maintaining, since #184; a row matching none
                // of them is NOT_LINKED, which is a different fact from a link
                // that tried and failed and is drawn differently below.
                val linkRole =
                    DevicePairingPolicy.linkRoleFor(device.address, links.imu, links.imuB, links.hrm)
                val state =
                    when (linkRole) {
                        DeviceLinkRole.ANALYSED -> imuState
                        DeviceLinkRole.SECOND -> imuStateB
                        DeviceLinkRole.HEART_RATE -> hrmState
                        DeviceLinkRole.NOT_LINKED -> null
                    }
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Row(horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "${device.name} · ${DevicePairingPolicy.unitTag(device.address)}",
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text("${device.role} · ${device.address}", style = MaterialTheme.typography.bodySmall)
                                DevicePairingPolicy.signalLine(rssi[device.address])?.let {
                                    // Only while a scan is running. A unit
                                    // that stops advertising mid-scan keeps
                                    // its last reading until the scan is
                                    // restarted -- nothing here dates it.
                                    // Absent rather than weak when there is no
                                    // reading at all.
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = BarColors.Sub)
                                }
                                if (state is ConnectionState.Failed) {
                                    // This screen is the only surface that shows the reason string.
                                    Text(
                                        state.reason,
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                            if (state == null) {
                                // NOT a Disconnected chip. No link is pointed
                                // at this unit, so there is no connection to
                                // report the state of, and the chip would be
                                // reporting a failure that never happened.
                                Text(
                                    "Not linked",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = BarColors.Sub,
                                )
                            } else {
                                ConnectionChip(device.role.name, state)
                            }
                            TextButton(onClick = { viewModel.forget(device) }) { Text("Forget") }
                        }
                        // Outside the IMU block: which unit of a role the app
                        // reads is a question every role has, and the pairing
                        // rule that made the control necessary is role-generic
                        // (`DeviceRegistry.pair` keys off `keyFor(role)`).
                        // Whether a strap row draws anything is
                        // `preferenceControl`'s answer, not this `if`'s.
                        val ownedLink =
                            if (device.role == DeviceRole.IMU) {
                                DeviceLinkRole.ANALYSED
                            } else {
                                DeviceLinkRole.HEART_RATE
                            }
                        DevicePairingPolicy.preferenceControl(ownedLink, linkRole)?.let {
                            PreferenceRow(control = it, onUse = { viewModel.setPreferred(device) })
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
                                // pairing again could only re-file the unit
                                // under the other role, which would strand
                                // preferred_imu on an address preferred(IMU)
                                // no longer matches.
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
