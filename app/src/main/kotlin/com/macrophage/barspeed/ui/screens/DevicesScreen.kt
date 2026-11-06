package com.macrophage.barspeed.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.macrophage.barspeed.ble.DeviceRole
import com.macrophage.barspeed.ble.KnownDevice
import com.macrophage.barspeed.model.ConnectionState
import com.macrophage.barspeed.model.DeviceLinkRole
import com.macrophage.barspeed.model.DevicePairingPolicy
import com.macrophage.barspeed.model.DualSensorSetup
import com.macrophage.barspeed.model.DualSetupStep
import com.macrophage.barspeed.model.PreferenceControl
import com.macrophage.barspeed.model.SensorRole
import com.macrophage.barspeed.model.SensorStatusCopy
import com.macrophage.barspeed.ui.BarColors
import com.macrophage.barspeed.ui.components.ConnectionChip
import com.macrophage.barspeed.ui.components.PermissionBanner
import com.macrophage.barspeed.ui.components.TurnOnBluetoothButton
import com.macrophage.barspeed.ui.components.rememberArmedDelivery
import kotlinx.coroutines.delay

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
 * Labels are given without asking since UX review 2026-11-06 (ble-devices
 * F3): the ViewModel labels the first paired unit A and the second B, and a
 * chip tap swaps the pair rather than setting one unit alone, so two units can
 * no longer be tapped onto one label. Clearing a label used to be offered here
 * because a wrong one is worse than none; with the swap a wrong label is one
 * tap from right, and with two paired a cleared one would be put straight back,
 * so the clear is gone. `SensorLabelPolicy` holds both rules.
 */
@Composable
private fun SensorRoleRow(assigned: SensorRole?, onTap: (SensorRole) -> Unit) {
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
                onClick = { onTap(role) },
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
private fun DualSetupCard(step: DualSetupStep, rolesLine: String?) {
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
            // WHICH UNIT each label is on, in one line, so the pairing is
            // readable without comparing two cards (#260). Each paired row
            // already carries its own `DevicePairingPolicy.unitTag` beside its
            // name, which pairs a unit with a label a few lines further down
            // the same card; this states the pair together, which is what goes
            // on the stickers. Computed by the caller from the same two inputs
            // [step] is read from, so the two readings answer one setup by one
            // rule -- they arrive as two separate collectAsState values, so a
            // frame during a re-label can still show one updated before the
            // other.
            rolesLine?.let {
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
 * set is ARMED to analyse; since #207 the figures come from that unit only
 * where it streamed.
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
        is PreferenceControl.Offer ->
            OutlinedButton(onClick = onUse, contentPadding = ButtonDefaults.TextButtonContentPadding) {
                Text(control.label)
            }
    }
}

/**
 * How long this screen has watched [state] sit in Connecting, in
 * milliseconds, re-read once a second (ble-devices F7).
 *
 * Measured from when THIS SCREEN first saw the link connecting, not from when
 * the link started trying, which nothing publishes; so on opening Devices a
 * unit that has been missing for an hour gets its words ten seconds late
 * rather than at once. The judgement is `SensorStatusCopy.connecting`'s.
 */
@Composable
private fun rememberConnectingMs(state: ConnectionState?): Long {
    val waited = remember { mutableLongStateOf(0L) }
    val connecting = state is ConnectionState.Connecting
    LaunchedEffect(connecting) {
        waited.longValue = 0L
        if (connecting) {
            val since = System.currentTimeMillis()
            while (true) {
                delay(1_000)
                waited.longValue = System.currentTimeMillis() - since
            }
        }
    }
    return waited.longValue
}

/**
 * What a paired row says about its link in words, under the name (ble-devices
 * F5, F7).
 *
 * The words are `SensorStatusCopy`'s, where a test holds them. The raw reason
 * stays, small and grey beneath, because it is what a bug report needs; it
 * used to be the only line, printed in the error colour, so the lifter read
 * "Service discovery failed (133)" on the screen meant for fixing things.
 */
@Composable
private fun LinkStatusLines(state: ConnectionState?, name: String, isStrap: Boolean, onRetry: () -> Unit) {
    when (state) {
        is ConnectionState.Failed -> {
            val copy = SensorStatusCopy.failed(state, isStrap)
            Text(copy.line, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            copy.detail?.let { Text(it, color = BarColors.Ghost, style = MaterialTheme.typography.labelSmall) }
            if (copy.offerRetry || copy.offerTurnOnBluetooth) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (copy.offerRetry) {
                        OutlinedButton(onClick = onRetry, contentPadding = ButtonDefaults.TextButtonContentPadding) {
                            Text("Retry")
                        }
                    }
                    if (copy.offerTurnOnBluetooth) TurnOnBluetoothButton()
                }
            }
        }
        is ConnectionState.Connecting ->
            SensorStatusCopy.connecting(name, rememberConnectingMs(state))?.let {
                Text(it, color = BarColors.Amber, style = MaterialTheme.typography.bodySmall)
            }
        is ConnectionState.Connected, ConnectionState.Disconnected, null -> Unit
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
    val actionError by viewModel.actionError.collectAsState()
    val imuState by viewModel.imuState.collectAsState()
    val imuStateB by viewModel.imuStateB.collectAsState()
    val hrmState by viewModel.hrmState.collectAsState()
    val imuFrameAtMs by viewModel.imuFrameAtMs.collectAsState()
    val imuFrameAtMsB by viewModel.imuFrameAtMsB.collectAsState()
    val imuArmedAtMs by viewModel.imuArmedAtMs.collectAsState()
    val imuArmedAtMsB by viewModel.imuArmedAtMsB.collectAsState()
    val links by viewModel.linkAddresses.collectAsState()
    val roles by viewModel.sensorRoles.collectAsState()
    val rolesLine by viewModel.dualRolesLine.collectAsState()
    // The device a Forget tap is asking about, or null (ble-devices F11): a
    // sweaty mis-tap used to unpair the unit and drop its link at once.
    var forgetting by remember { mutableStateOf<KnownDevice?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Devices") },
                navigationIcon = {
                    // Kept a TextButton (#201): the app bar's navigation slot, the same act as the
                    // system back gesture.
                    TextButton(onClick = { navController.popBackStack() }) { Text("Back") }
                },
            )
        },
    ) { padding ->
        // ONE scrolling list for the whole screen (ble-devices F4). It used to
        // be a fixed Column with only the found list scrolling in whatever
        // height was left, so with two sensors and a strap paired the found
        // list was squeezed to nothing, and on a small phone the Scan button
        // could not be reached at all. Keys carry a prefix because a paired
        // unit a running scan also sights is in both lists.
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
        ) {
            item(key = "header") {
                Column {
                    // The troubleshooting screen. The per-device lines below
                    // report one link's last failure and are driven by
                    // ConnectionState, where this is driven by the gate. They
                    // disagree for up to one backoff period after a grant,
                    // because the client keeps its stale Failed until the next
                    // connect attempt. No Spacer here: PermissionBanner
                    // carries its own trailing gap so one is not paid when the
                    // banner draws nothing (GRANTED).
                    PermissionBanner()
                    Text("Paired sensors", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    if (known.isEmpty()) {
                        Text(
                            "None yet. Scan below and pair your bar sensor and heart-rate strap once — " +
                                "the app auto-connects from then on.",
                        )
                    }
                    DualSetupCard(setupStep, rolesLine)
                }
            }
            items(known, key = { "paired-${it.address}" }) { device ->
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
                // Whether THIS row's link is delivering, keyed off the same
                // linkRole the state is (#213). A tick meant the app had
                // issued a notification subscribe and nothing more, which is
                // what field-37's lifter read as capture on thirteen sets.
                //
                // Null for the strap and for an unlinked row: nothing observes
                // a strap's frame arrivals, and a row no link is pointed at has
                // no delivery to report. Both render exactly as before.
                val delivery =
                    when (linkRole) {
                        DeviceLinkRole.ANALYSED ->
                            rememberArmedDelivery(imuState, imuFrameAtMs, imuArmedAtMs)
                        DeviceLinkRole.SECOND ->
                            rememberArmedDelivery(imuStateB, imuFrameAtMsB, imuArmedAtMsB)
                        DeviceLinkRole.HEART_RATE, DeviceLinkRole.NOT_LINKED -> null
                    }
                val isStrap = device.role == DeviceRole.HRM
                // "Sensor A", "Sensor B", "Bar sensor" or "Heart-rate strap" in
                // the sentences; the chip gets the short form, "A ✓ 64%", or
                // the dots' own "Bar" / "HR" (ble-devices F7, copy F4: the
                // chip used to print the role enum, "IMU …").
                val name = SensorStatusCopy.deviceName(isStrap, roles[device.address])
                val chipLabel = roles[device.address]?.takeUnless { isStrap }?.name ?: roleChip(device.role)
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Row(horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "${device.name} · ${DevicePairingPolicy.unitTag(device.address)}",
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    "${roleName(device.role)} · ${device.address}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                DevicePairingPolicy.signalLine(rssi[device.address])?.let {
                                    // Only while a scan is running. A unit
                                    // that stops advertising mid-scan keeps
                                    // its last reading until the scan is
                                    // restarted -- nothing here dates it.
                                    // Absent rather than weak when there is no
                                    // reading at all.
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = BarColors.Sub)
                                }
                                LinkStatusLines(state, name, isStrap, onRetry = viewModel::retryNow)
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
                                ConnectionChip(chipLabel, state, delivery = delivery)
                            }
                            // Kept a TextButton (#201): it unpairs the unit, so it stays quieter
                            // than the controls that pair one. It asks first (ble-devices F11).
                            TextButton(onClick = { forgetting = device }) { Text("Forget") }
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
                                onTap = { viewModel.tapSensorLabel(device.address, it) },
                            )
                        }
                    }
                }
            }
            item(key = "scan") {
                Column {
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = viewModel::toggleScan, modifier = Modifier.fillMaxWidth()) {
                        Text(if (scanning) "Stop scanning" else "Scan for sensors")
                    }
                    scanError?.let { raw ->
                        // Plain words, the raw text small beneath, and the
                        // one-tap fix when the phone's Bluetooth is what is
                        // off (ble-devices F5).
                        val copy = SensorStatusCopy.scanFailed(raw)
                        Text(
                            copy.line,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        copy.detail?.let {
                            Text(it, color = BarColors.Ghost, style = MaterialTheme.typography.labelSmall)
                        }
                        if (copy.offerTurnOnBluetooth) TurnOnBluetoothButton()
                    }
                    // A pair, forget, label or switch that did not take (#50): before
                    // it was caught, the same failure killed the process.
                    actionError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
            items(found, key = { "found-${it.device.address}" }) { entry ->
                val device = entry.device
                // Keyed by address, so a re-sighting redraws the row it is
                // already in rather than being treated as a new one. The
                // ORDER is decided by DeviceScanListPolicy, which does not
                // read the signal: a device must not move because a packet
                // arrived (#183).
                Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "${device.name} · ${DevicePairingPolicy.unitTag(device.address)}",
                            style = MaterialTheme.typography.titleSmall,
                            color = if (entry.row.alreadyPaired) BarColors.Sub else BarColors.Text,
                        )
                        Text(
                            "${device.address} · ${device.rssi} dBm" +
                                (device.likelyRole?.let { " · looks like a ${roleName(it).lowercase()}" } ?: ""),
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
                                    Text("Pair as heart-rate strap")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    forgetting?.let { device ->
        val name = SensorStatusCopy.deviceName(device.role == DeviceRole.HRM, roles[device.address])
        AlertDialog(
            onDismissRequest = { forgetting = null },
            title = { Text("Forget ${name.replaceFirstChar { it.lowercase() }}?") },
            text = { Text("BarSpeed stops connecting to it. To use it again, scan and pair it.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.forget(device)
                        forgetting = null
                    },
                ) { Text("Forget") }
            },
            dismissButton = { TextButton(onClick = { forgetting = null }) { Text("Cancel") } },
        )
    }
}

/** The role as the lifter calls it: never the enum's IMU/HRM. */
private fun roleName(role: DeviceRole): String = when (role) {
    DeviceRole.IMU -> "Bar sensor"
    DeviceRole.HRM -> "Heart-rate strap"
}

/** The short form on a connection chip, matching the top-bar dots. */
private fun roleChip(role: DeviceRole): String = when (role) {
    DeviceRole.IMU -> "Bar"
    DeviceRole.HRM -> "HR"
}
