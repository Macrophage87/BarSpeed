package com.macrophage.accelerometerlifting.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.macrophage.accelerometerlifting.model.Phase
import com.macrophage.accelerometerlifting.model.WeightUnit
import com.macrophage.accelerometerlifting.record.PlannedSlot
import com.macrophage.accelerometerlifting.record.RecordState
import com.macrophage.accelerometerlifting.record.RecordViewModel
import com.macrophage.accelerometerlifting.record.SetFeedback
import com.macrophage.accelerometerlifting.record.Stage
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordScreen(navController: NavController, viewModel: RecordViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(titleFor(state)) },
                navigationIcon = {
                    TextButton(onClick = { navController.popBackStack() }) { Text("Back") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
        ) {
            when (state.stage) {
                Stage.SETUP -> SetupStage(state, viewModel)
                Stage.READY -> ReadyStage(state, viewModel)
                Stage.IN_SET -> InSetStage(state, viewModel)
                Stage.RESTING -> RestingStage(state, viewModel)
                Stage.FINISHED -> FinishedStage(state, navController)
            }
        }
    }
}

private fun titleFor(state: RecordState): String =
    when (state.stage) {
        Stage.SETUP -> "New session"
        Stage.READY -> state.planSessionName ?: "Ad-hoc session"
        Stage.IN_SET -> "Set in progress"
        Stage.RESTING -> "Rest"
        Stage.FINISHED -> "Session complete"
    }

@Composable
private fun SetupStage(state: RecordState, viewModel: RecordViewModel) {
    if (!state.imuConnected) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Bar sensor not connected", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Pair or power on the WitMotion sensor, or enable demo mode to try the app " +
                        "with synthesized data.",
                    style = MaterialTheme.typography.bodySmall,
                )
                FilterChip(
                    selected = state.demoMode,
                    onClick = viewModel::toggleDemoMode,
                    label = { Text(if (state.demoMode) "Demo mode ON" else "Demo mode off") },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
    }
    if (state.planSessions.isNotEmpty()) {
        Text("From plan: ${state.planName}", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        state.planSessions.forEach { planSession ->
            Card(
                onClick = { viewModel.startPlanSession(planSession) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(planSession.name, style = MaterialTheme.typography.titleSmall)
                    val sets = planSession.exercises.sumOf { it.sets.size }
                    Text(
                        "${planSession.exercises.size} exercises, $sets sets",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
    OutlinedButton(onClick = viewModel::startAdHocSession, modifier = Modifier.fillMaxWidth()) {
        Text("Start ad-hoc session (no plan)")
    }
}

@Composable
private fun ReadyStage(state: RecordState, viewModel: RecordViewModel) {
    val slot = state.currentSlot
    if (slot != null) {
        if (slot.isExerciseChange) {
            Card(Modifier.fillMaxWidth()) {
                Text(
                    "New exercise — move the sensor to the ${slot.exercise.displayName} bar",
                    Modifier.padding(12.dp),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
        SlotCard(slot, heading = "Up next", unit = state.weightUnit)
    } else {
        AdHocForm(state, viewModel)
    }
    Spacer(Modifier.height(16.dp))
    val canStart = state.imuConnected || state.demoMode
    Button(onClick = viewModel::beginSet, enabled = canStart, modifier = Modifier.fillMaxWidth()) {
        Text(if (canStart) "Start set" else "Bar sensor not connected")
    }
    if (!state.imuConnected) {
        FilterChip(
            selected = state.demoMode,
            onClick = viewModel::toggleDemoMode,
            label = { Text(if (state.demoMode) "Demo mode ON" else "Enable demo mode") },
        )
    }
    Spacer(Modifier.height(8.dp))
    TextButton(onClick = viewModel::finishSession, modifier = Modifier.fillMaxWidth()) {
        Text("Finish session")
    }
}

@Composable
private fun AdHocForm(state: RecordState, viewModel: RecordViewModel) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Exercise", style = MaterialTheme.typography.titleSmall)
        FilterChip(
            selected = false,
            onClick = viewModel::toggleWeightUnit,
            label = { Text("Units: ${state.weightUnit.suffix}") },
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        state.exerciseOptions.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { exercise ->
                    FilterChip(
                        selected = state.selectedExerciseId == exercise.id,
                        onClick = { viewModel.selectExercise(exercise.id) },
                        label = { Text(exercise.displayName) },
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = state.loadInput,
            onValueChange = viewModel::updateLoadInput,
            label = { Text("Load (${state.weightUnit.suffix})") },
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = state.repsInput,
            onValueChange = viewModel::updateRepsInput,
            label = { Text("Reps") },
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = state.tempoInput,
            onValueChange = viewModel::updateTempoInput,
            label = { Text("Tempo") },
            placeholder = { Text("4010") },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun InSetStage(state: RecordState, viewModel: RecordViewModel) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text("Rep ${state.live.repCount}", style = MaterialTheme.typography.displayLarge)
        Text(
            String.format(Locale.US, "%+.2f m/s", state.live.velocityMps),
            style = MaterialTheme.typography.displayMedium,
            fontFamily = FontFamily.Monospace,
        )
        Text(phaseLabel(state.live.phase), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Elapsed ${state.setElapsedS}s")
            state.hrBpm?.let { Text("♥ $it bpm") }
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = viewModel::endSet, modifier = Modifier.fillMaxWidth().height(64.dp)) {
            Text("End set", style = MaterialTheme.typography.titleLarge)
        }
    }
}

private fun phaseLabel(phase: Phase): String =
    when (phase) {
        Phase.IDLE -> "Ready"
        Phase.ECCENTRIC -> "Lowering"
        Phase.BOTTOM_PAUSE -> "Bottom pause"
        Phase.CONCENTRIC -> "Driving up"
        Phase.TOP_PAUSE -> "Lockout"
    }

@Composable
private fun RestingStage(state: RecordState, viewModel: RecordViewModel) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text("Rest ${state.restRemainingS}s", style = MaterialTheme.typography.displayMedium)
        state.hrBpm?.let { Text("♥ $it bpm", style = MaterialTheme.typography.titleMedium) }
    }
    Spacer(Modifier.height(12.dp))
    state.lastFeedback?.let { FeedbackCard(it, state.weightUnit) }
    Spacer(Modifier.height(12.dp))

    val next = state.nextSlot
    if (!state.adHoc && next != null) {
        if (next.isExerciseChange) {
            Card(Modifier.fillMaxWidth()) {
                Text(
                    "Next exercise: ${next.exercise.displayName} — move the sensor!",
                    Modifier.padding(12.dp),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
        SlotCard(next, heading = "Next set", unit = state.weightUnit)
        Spacer(Modifier.height(8.dp))
        Text("Adjust next set (deviations are recorded)", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = state.loadInput,
                onValueChange = viewModel::updateLoadInput,
                label = { Text("Load (${state.weightUnit.suffix})") },
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = state.repsInput,
                onValueChange = viewModel::updateRepsInput,
                label = { Text("Reps") },
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = viewModel::startNextSet, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text("Start next set")
        }
    } else if (state.adHoc) {
        AdHocForm(state, viewModel)
        Spacer(Modifier.height(12.dp))
        Button(onClick = viewModel::startNextSet, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text("Start next set")
        }
    } else {
        Card(Modifier.fillMaxWidth()) {
            Text(
                "That was the last planned set. Great work!",
                Modifier.padding(12.dp),
                style = MaterialTheme.typography.titleSmall,
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    TextButton(onClick = viewModel::finishSession, modifier = Modifier.fillMaxWidth()) {
        Text("Finish session")
    }
}

@Composable
private fun SlotCard(slot: PlannedSlot, heading: String, unit: WeightUnit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(heading, style = MaterialTheme.typography.labelMedium)
            Text(
                "${slot.exercise.displayName} — set ${slot.setIndexInExercise + 1} of ${slot.setsInExercise}",
                style = MaterialTheme.typography.titleMedium,
            )
            val parts =
                listOfNotNull(
                    slot.reps?.let { "$it reps" },
                    slot.loadKg?.let { unit.format(it) },
                    slot.tempo?.let { "tempo $it" },
                    slot.targetMeanConVelMps?.let { "target ${trim(it)} m/s" },
                    slot.velocityLossStopPct?.let { "stop at ${trim(it)}% vel loss" },
                    slot.restS?.let { "rest ${it}s" },
                )
            Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun FeedbackCard(feedback: SetFeedback, unit: WeightUnit) {
    val analysis = feedback.analysis
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Last set: ${feedback.exerciseName} @ ${unit.format(feedback.loadKg)}",
                style = MaterialTheme.typography.titleSmall,
            )
            val repsLine =
                "${analysis.reps.size} reps" +
                    (feedback.plannedReps?.let { " of $it planned" } ?: "") +
                    (analysis.velocityLossPct?.let { " · $it% velocity loss" } ?: "")
            Text(repsLine)
            analysis.reps.firstOrNull()?.let {
                val meanCon = analysis.reps.map { r -> r.meanConVelMps }.average()
                Text(String.format(Locale.US, "Mean concentric velocity %.2f m/s", meanCon))
            }
            analysis.tempoCompliance?.let { compliance ->
                Text(
                    "Tempo ${compliance.prescribed.notation()}: ${compliance.repsFullyCompliant}/" +
                        "${compliance.repsEvaluated} reps on tempo (±${compliance.toleranceS}s)",
                )
            }
            if (analysis.reps.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text("rep  ecc   pause  con   vel", fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall)
                analysis.reps.forEach { rep ->
                    Text(
                        String.format(
                            Locale.US,
                            "%2d  %4.1fs  %4.1fs  %4.1fs  %.2f",
                            rep.index + 1, rep.eccS, rep.bottomPauseS, rep.conS, rep.meanConVelMps,
                        ),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            analysis.verdicts.forEach { verdict ->
                Text("• $verdict", style = MaterialTheme.typography.bodySmall)
            }
            if (analysis.verdicts.isEmpty() && analysis.reps.isNotEmpty()) {
                Text("• On target.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun FinishedStage(state: RecordState, navController: NavController) {
    Text("Session saved.", style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(12.dp))
    state.sessionId?.let { id ->
        Button(
            onClick = { navController.navigate("session/$id") { popUpTo("home") } },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("View session & export") }
    }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        onClick = { navController.navigate("home") { popUpTo("home") { inclusive = true } } },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Done") }
}

private fun trim(value: Double): String =
    if (value == Math.floor(value)) value.toInt().toString() else value.toString()
