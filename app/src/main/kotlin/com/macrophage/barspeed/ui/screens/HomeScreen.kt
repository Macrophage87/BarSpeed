package com.macrophage.barspeed.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.macrophage.barspeed.data.OrphanedSet
import com.macrophage.barspeed.model.ConnectionState
import com.macrophage.barspeed.model.WeightUnit
import com.macrophage.barspeed.ui.BarColors
import com.macrophage.barspeed.ui.components.PermissionBanner
import com.macrophage.barspeed.ui.components.SectionCaption
import com.macrophage.barspeed.ui.components.SensorDot
import com.macrophage.barspeed.ui.components.Sparkline
import com.macrophage.barspeed.ui.components.StatTile
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val KG_PER_TONNE = 1000.0

/**
 * Body weight is the base load for pull-ups, dips and other bodyweight work,
 * where the plan prescribes only what was ADDED (or assisted away).
 */
@Composable
private fun BodyWeightDialog(current: Double?, unit: WeightUnit, onDismiss: () -> Unit, onSet: (Double) -> Unit) {
    var text by remember { mutableStateOf(current?.let { unit.inputValue(it) } ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Body weight") },
        text = {
            Column {
                Text(
                    "Used as the base load for pull-ups, dips and other bodyweight work — " +
                        "those plans prescribe only the weight you add or take off.",
                    style = MaterialTheme.typography.bodySmall,
                    color = BarColors.Sub,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Weight (${unit.suffix})") },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { unit.parseToKg(text)?.takeIf { it > 0 }?.let(onSet) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(navController: NavController, viewModel: HomeViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val imuState by viewModel.imuState.collectAsState()
    val hrmState by viewModel.hrmState.collectAsState()
    val interrupted by viewModel.interrupted.collectAsState()
    // Re-scanned every time this screen is composed, not only on first launch:
    // a set interrupted by a crash that left the process alive would otherwise
    // stay invisible until the app was killed and started again.
    LaunchedEffect(Unit) { viewModel.refreshInterrupted() }
    var showBodyWeight by remember { mutableStateOf(false) }
    if (showBodyWeight) {
        BodyWeightDialog(
            current = state.bodyWeightKg,
            unit = state.weightUnit,
            onDismiss = { showBodyWeight = false },
            onSet = {
                viewModel.setBodyWeight(it)
                showBodyWeight = false
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("BarSpeed") },
                actions = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(end = 8.dp),
                    ) {
                        SensorDot(
                            "IMU",
                            imuState is ConnectionState.Connected,
                            connecting = imuState is ConnectionState.Connecting,
                        )
                        SensorDot(
                            "HRM",
                            hrmState is ConnectionState.Connected,
                            connecting = hrmState is ConnectionState.Connecting,
                        )
                        TextButton(onClick = { showBodyWeight = true }) {
                            Text(
                                state.bodyWeightKg?.let { "BW ${state.weightUnit.inputValue(it)}" } ?: "Set BW",
                                color = if (state.bodyWeightKg == null) BarColors.Amber else BarColors.Sub,
                            )
                        }
                        TextButton(onClick = viewModel::toggleWeightUnit) {
                            Text("${state.weightUnit.suffix} ⇄", color = BarColors.Sub)
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
        ) {
            // Above the hero card because this is the first screen of every
            // cold launch and the one the first permission dialog opens over.
            // Deliberately compact: this Column has no verticalScroll and the
            // history LazyColumn below is unweighted, so anything added here
            // comes out of the history list with no way to scroll it back.
            PermissionBanner()
            InterruptedSetNotice(interrupted, viewModel::shareInterrupted, viewModel::discardInterrupted)
            HeroCard(state) { navController.navigate("record") }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                StatTile(
                    label = "Week volume",
                    value = volumeValue(state.weekVolumeKg, state.weightUnit),
                    unit = volumeUnit(state.weightUnit),
                    sub = "last 7 days",
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = "Sessions",
                    value = "${state.weekSessions}",
                    sub = "this week",
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { navController.navigate("devices") }, modifier = Modifier.weight(1f)) {
                    Text("Devices", color = BarColors.Sub)
                }
                TextButton(onClick = { navController.navigate("plans") }, modifier = Modifier.weight(1f)) {
                    Text("Plans", color = BarColors.Sub)
                }
                TextButton(onClick = { navController.navigate("guide") }, modifier = Modifier.weight(1f)) {
                    Text("Guide", color = BarColors.Sub)
                }
            }
            Spacer(Modifier.height(6.dp))
            SectionCaption("History")
            Spacer(Modifier.height(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.history) { row ->
                    HistoryCard(row) { navController.navigate("session/${row.session.id}") }
                }
            }
        }
    }
}

@Composable
private fun HeroCard(state: HomeState, onStart: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.linearGradient(listOf(BarColors.HeroGreen, BarColors.Surface)),
                shape,
            )
            .border(1.dp, BarColors.Volt.copy(alpha = 0.2f), shape)
            .padding(14.dp),
    ) {
        if (state.planName != null) {
            SectionCaption("Active plan", color = BarColors.Volt)
            Text(
                state.planName,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            Text(
                "${state.planSessionCount} sessions · ${state.planExerciseCount} exercises · " +
                    "${state.planSetCount} sets",
                style = MaterialTheme.typography.bodySmall,
                color = BarColors.Sub,
            )
        } else {
            SectionCaption("No active plan", color = BarColors.Volt)
            Text(
                "Train ad-hoc or import a plan",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            Text(
                "Import a JSON plan from Claude on the Plans screen.",
                style = MaterialTheme.typography.bodySmall,
                color = BarColors.Sub,
            )
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Text("START SESSION", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun HistoryCard(row: HistoryRow, onClick: () -> Unit) {
    val formatter = DateTimeFormatter.ofPattern("EEE d MMM")
    val started = Instant.ofEpochMilli(row.session.startedAtMs).atZone(ZoneId.systemDefault())
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(14.dp),
        ) {
            Column {
                Text(
                    row.session.planSessionName ?: "Ad-hoc session",
                    style = MaterialTheme.typography.titleSmall,
                )
                val parts =
                    listOfNotNull(
                        formatter.format(started),
                        "${row.setCount} sets",
                        row.session.hrAvgBpm?.let { "♥ $it avg" },
                        row.session.hrvRmssdMs?.let { "HRV ${it.toInt()}" },
                    )
                Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = BarColors.Sub)
            }
            if (row.sparkline.size >= 2) {
                Sparkline(
                    row.sparkline,
                    color = if (row.session.planSessionName != null) BarColors.Volt else BarColors.Blue,
                )
            }
        }
    }
}

private fun volumeValue(volumeKg: Double, unit: WeightUnit): String = when (unit) {
    WeightUnit.KG -> String.format(Locale.US, "%.1f", volumeKg / KG_PER_TONNE)
    WeightUnit.LB -> String.format(Locale.US, "%.1f", volumeKg * WeightUnit.LB_PER_KG / KG_PER_TONNE)
}

private fun volumeUnit(unit: WeightUnit): String = when (unit) {
    WeightUnit.KG -> "t"
    WeightUnit.LB -> "k lb"
}

/**
 * Sets that were interrupted before they could be stored.
 *
 * Drawn above the hero card, and above everything else on this screen, because
 * a capture the lifter has not ruled on outranks starting a new session. It
 * costs two history rows when it appears: the Column it sits in has no scroll
 * and the history list below is unweighted. That trade is taken deliberately
 * and it is paid only in the rare case, since this draws nothing at all when
 * there is nothing to report.
 *
 * Not a [HistoryCard]. A history row carries a set count and a velocity
 * sparkline; an interrupted set has been through no analysis and has neither,
 * and rendering one as history would put invented figures beside real ones.
 *
 * The two numbers shown are the ones that let the lifter check this against
 * their own memory of what happened: how many sensor samples reached the disk,
 * and when the last of them did. Neither is stated when the sensor was not
 * connected -- a count of zero would otherwise read as a measurement, when what
 * it means is that there was nothing to measure.
 */
@Composable
private fun InterruptedSetNotice(
    interrupted: List<OrphanedSet>,
    onShare: (OrphanedSet) -> Unit,
    onDiscard: (OrphanedSet) -> Unit,
) {
    if (interrupted.isEmpty()) return
    val clock = DateTimeFormatter.ofPattern("HH:mm:ss")
    Column(modifier = Modifier.fillMaxWidth()) {
        for (orphan in interrupted) {
            Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "INTERRUPTED SET",
                        style = MaterialTheme.typography.titleSmall,
                        color = BarColors.Amber,
                        letterSpacing = 2.sp,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(orphan.header.exerciseName, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        interruptedDetail(orphan, clock),
                        style = MaterialTheme.typography.bodySmall,
                        color = BarColors.Sub,
                    )
                    Spacer(Modifier.height(6.dp))
                    SectionCaption("Kept on this phone. Nothing deletes it but you")
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { onShare(orphan) }) {
                            Text("SEND IT TO ME", color = BarColors.Volt)
                        }
                        TextButton(onClick = { onDiscard(orphan) }) {
                            Text("DISCARD", color = BarColors.Red)
                        }
                    }
                }
            }
        }
    }
}

/**
 * What survived, in the terms the field check reads.
 *
 * The sample count and the last sample's wall clock are what distinguish a
 * capture that genuinely reached the filesystem from one the app merely
 * remembered. With no sensor connected there is no count to give and the card
 * says so in words rather than printing a zero.
 */
private fun interruptedDetail(orphan: OrphanedSet, clock: DateTimeFormatter): String {
    val reps = orphan.repMarks.size
    val parts =
        listOfNotNull(
            if (orphan.header.imuConnected) {
                "${orphan.imuSamples.size} sensor samples"
            } else {
                "no sensor connected"
            },
            orphan.imuSamples.lastOrNull()?.let {
                "last at ${clock.format(Instant.ofEpochMilli(it.timestampMs).atZone(ZoneId.systemDefault()))}"
            },
            if (reps > 0) "$reps reps counted" else null,
        )
    return parts.joinToString(" · ")
}
