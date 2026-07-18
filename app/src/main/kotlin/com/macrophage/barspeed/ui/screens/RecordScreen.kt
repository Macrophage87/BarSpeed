package com.macrophage.barspeed.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.macrophage.barspeed.dsp.SetAnalysis
import com.macrophage.barspeed.model.Phase
import com.macrophage.barspeed.model.Tempo
import com.macrophage.barspeed.model.WeightUnit
import com.macrophage.barspeed.record.PlannedSlot
import com.macrophage.barspeed.record.RecordState
import com.macrophage.barspeed.record.RecordViewModel
import com.macrophage.barspeed.record.SetFeedback
import com.macrophage.barspeed.record.Stage
import com.macrophage.barspeed.ui.BarColors
import com.macrophage.barspeed.ui.components.ChipTone
import com.macrophage.barspeed.ui.components.ProgressRing
import com.macrophage.barspeed.ui.components.RepBars
import com.macrophage.barspeed.ui.components.SectionCaption
import com.macrophage.barspeed.ui.components.SensorDot
import com.macrophage.barspeed.ui.components.TargetLineBars
import com.macrophage.barspeed.ui.components.VerdictChip
import java.util.Locale

private const val DEFAULT_VELOCITY_LOSS_STOP_PCT = 20.0
private const val VEL_LOSS_OK_PCT = 10.0
private const val TEMPO_TOLERANCE_S = 0.5

/** The tempo ring spans 150% of the target so the ghost marker sits at 2/3. */
private const val RING_WINDOW_SCALE = 1.5f

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
                actions = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(end = 16.dp),
                    ) {
                        SensorDot("IMU", state.imuConnected || state.demoMode)
                        SensorDot("HRM", state.hrmConnected)
                    }
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

private fun titleFor(state: RecordState): String = when (state.stage) {
    Stage.SETUP -> "New session"
    Stage.READY -> state.planSessionName ?: "Ad-hoc session"
    Stage.IN_SET -> ""
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
                    color = BarColors.Sub,
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
        SectionCaption("From plan · ${state.planName}")
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
                        "${planSession.exercises.size} exercises · $sets sets",
                        style = MaterialTheme.typography.bodySmall,
                        color = BarColors.Sub,
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
    OutlinedButton(onClick = viewModel::startAdHocSession, modifier = Modifier.fillMaxWidth()) {
        Text("Start ad-hoc session (no plan)")
    }
    Spacer(Modifier.height(8.dp))
    AudioCueChip(state, viewModel)
}

@Composable
private fun AudioCueChip(state: RecordState, viewModel: RecordViewModel) {
    FilterChip(
        selected = state.audioCues,
        onClick = viewModel::toggleAudioCues,
        label = { Text(if (state.audioCues) "Voice count ON" else "Voice count off") },
    )
}

@Composable
private fun ReadyStage(state: RecordState, viewModel: RecordViewModel) {
    val slot = state.currentSlot
    if (slot != null) {
        if (slot.isExerciseChange) {
            MoveSensorCard(slot.exercise.displayName)
        }
        SlotCard(slot, heading = "Up next", unit = state.weightUnit, highlight = true)
    } else {
        AdHocForm(state, viewModel)
    }
    Spacer(Modifier.height(16.dp))
    val canStart = state.imuConnected || state.demoMode
    Button(onClick = viewModel::beginSet, enabled = canStart, modifier = Modifier.fillMaxWidth().height(56.dp)) {
        Text(if (canStart) "START SET" else "Bar sensor not connected", fontWeight = FontWeight.Bold)
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!state.imuConnected) {
            FilterChip(
                selected = state.demoMode,
                onClick = viewModel::toggleDemoMode,
                label = { Text(if (state.demoMode) "Demo mode ON" else "Enable demo mode") },
            )
        }
        AudioCueChip(state, viewModel)
    }
    TextButton(onClick = viewModel::finishSession, modifier = Modifier.fillMaxWidth()) {
        Text("Finish session", color = BarColors.Sub)
    }
}

@Composable
private fun MoveSensorCard(exerciseName: String) {
    Card(Modifier.fillMaxWidth()) {
        Text(
            "New exercise — move the sensor to the $exerciseName bar",
            Modifier.padding(12.dp),
            style = MaterialTheme.typography.titleSmall,
            color = BarColors.Amber,
        )
    }
    Spacer(Modifier.height(8.dp))
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
    val slot = state.currentSlot
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        InSetHeader(state, slot)
        Spacer(Modifier.height(10.dp))
        TempoRing(state, slot)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                String.format(Locale.US, "%+.2f", state.live.velocityMps),
                style = MaterialTheme.typography.displayMedium,
                color = velocityColor(state.live.phase),
            )
            Text(
                " m/s",
                style = MaterialTheme.typography.titleMedium,
                color = BarColors.Sub,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        Spacer(Modifier.height(14.dp))
        LiveRepBars(state, slot)
        Spacer(Modifier.height(24.dp))
        Button(onClick = viewModel::endSet, modifier = Modifier.fillMaxWidth().height(64.dp)) {
            Text("END SET", style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun InSetHeader(state: RecordState, slot: PlannedSlot?) {
    val exerciseName =
        slot?.exercise?.displayName
            ?: state.exerciseOptions.firstOrNull { it.id == state.selectedExerciseId }?.displayName
            ?: "Set"
    val loadKg = slot?.loadKg ?: state.weightUnit.parseToKg(state.loadInput)
    val parts =
        listOfNotNull(
            exerciseName,
            slot?.let { "Set ${it.setIndexInExercise + 1}/${it.setsInExercise}" },
            loadKg?.let { state.weightUnit.format(it) },
        )
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = BarColors.Sub)
        state.hrBpm?.let {
            Text("♥ $it", style = MaterialTheme.typography.bodyMedium, color = BarColors.Red)
        }
    }
}

@Composable
private fun TempoRing(state: RecordState, slot: PlannedSlot?) {
    val tempoText = if (state.adHoc) state.tempoInput.ifBlank { null } else slot?.tempo
    val tempo = tempoText?.let { Tempo.parseOrNull(it) }
    val phase = state.live.phase
    val targetS = tempo?.let { phaseTargetS(it, phase) }
    val elapsed = state.live.currentPhaseElapsedS
    val moving = phase == Phase.ECCENTRIC || phase == Phase.CONCENTRIC

    val (progress, ghost) =
        if (targetS != null && targetS > 0 && moving) {
            val window = targetS * RING_WINDOW_SCALE
            (elapsed / window).toFloat() to (1f / RING_WINDOW_SCALE)
        } else {
            (if (moving) (elapsed / (elapsed + 2.0)).toFloat() else 0f) to null
        }
    val ringColor =
        when {
            targetS != null && moving && elapsed > targetS + TEMPO_TOLERANCE_S -> BarColors.Amber
            else -> BarColors.Volt
        }

    ProgressRing(progress = progress, ghostProgress = ghost, color = ringColor) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                phaseLabel(phase).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = BarColors.Sub,
                letterSpacing = 2.sp,
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    if (moving) String.format(Locale.US, "%.1f", elapsed) else "${state.live.repCount}",
                    style = MaterialTheme.typography.displayLarge,
                )
                Text(
                    if (moving) "s" else " reps",
                    style = MaterialTheme.typography.titleMedium,
                    color = BarColors.Sub,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }
            if (moving && targetS != null) {
                Text(
                    String.format(Locale.US, "target %.1f s", targetS),
                    style = MaterialTheme.typography.bodySmall,
                    color = BarColors.Sub,
                )
            } else if (!moving) {
                Text(
                    "rep ${state.live.repCount + 1} ready",
                    style = MaterialTheme.typography.bodySmall,
                    color = BarColors.Sub,
                )
            }
        }
    }
}

private fun phaseTargetS(tempo: Tempo, phase: Phase): Double? = when (phase) {
    Phase.ECCENTRIC -> tempo.eccentricS
    Phase.CONCENTRIC -> tempo.concentricS
    Phase.BOTTOM_PAUSE -> tempo.bottomPauseS
    Phase.TOP_PAUSE -> tempo.topPauseS
    Phase.IDLE -> null
}

@Composable
private fun LiveRepBars(state: RecordState, slot: PlannedSlot?) {
    val plannedReps = if (state.adHoc) state.repsInput.toIntOrNull() else slot?.reps
    val stopPct = slot?.velocityLossStopPct
    Column(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text("Mean concentric velocity", style = MaterialTheme.typography.bodySmall, color = BarColors.Sub)
            stopPct?.let {
                Text(
                    "stop at −${trim(it)}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = BarColors.Amber,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        val values = state.live.repMeanVelocities
        RepBars(
            values = values,
            plannedSlots = plannedReps,
            colorFor = { _, v -> repVelocityColor(v, values, stopPct) },
        )
    }
}

private fun repVelocityColor(value: Double, all: List<Double>, stopPct: Double?): Color {
    val best = all.maxOrNull() ?: return BarColors.Volt
    if (best <= 0) return BarColors.Volt
    val lossPct = (1.0 - value / best) * 100.0
    val stop = stopPct ?: DEFAULT_VELOCITY_LOSS_STOP_PCT
    return when {
        lossPct >= stop -> BarColors.Red
        lossPct >= VEL_LOSS_OK_PCT -> if (lossPct >= stop * 0.75) BarColors.Amber else BarColors.VoltDim
        else -> BarColors.Volt
    }
}

private fun velocityColor(phase: Phase): Color = when (phase) {
    Phase.CONCENTRIC -> BarColors.Volt
    Phase.ECCENTRIC -> BarColors.Blue
    else -> BarColors.Sub
}

private fun phaseLabel(phase: Phase): String = when (phase) {
    Phase.IDLE -> "Ready"
    Phase.ECCENTRIC -> "Lowering"
    Phase.BOTTOM_PAUSE -> "Bottom pause"
    Phase.CONCENTRIC -> "Driving up"
    Phase.TOP_PAUSE -> "Lockout"
}

@Composable
private fun RestingStage(state: RecordState, viewModel: RecordViewModel) {
    RestHeader(state)
    Spacer(Modifier.height(12.dp))
    state.lastFeedback?.let { RepQualityCard(it) }
    Spacer(Modifier.height(4.dp))

    val next = state.nextSlot
    if (!state.adHoc && next != null) {
        if (next.isExerciseChange) {
            MoveSensorCard(next.exercise.displayName)
        }
        SlotCard(
            next,
            heading = "Up next · Set ${next.setIndexInExercise + 1} of ${next.setsInExercise}",
            unit = state.weightUnit,
            highlight = true,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Adjust next set (deviations are recorded)",
            style = MaterialTheme.typography.bodySmall,
            color = BarColors.Sub,
        )
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
            Text("START NEXT SET", fontWeight = FontWeight.Bold)
        }
    } else if (state.adHoc) {
        AdHocForm(state, viewModel)
        Spacer(Modifier.height(12.dp))
        Button(onClick = viewModel::startNextSet, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text("START NEXT SET", fontWeight = FontWeight.Bold)
        }
    } else {
        Card(Modifier.fillMaxWidth()) {
            Text(
                "That was the last planned set. Great work!",
                Modifier.padding(12.dp),
                style = MaterialTheme.typography.titleSmall,
                color = BarColors.Volt,
            )
        }
    }
    TextButton(onClick = viewModel::finishSession, modifier = Modifier.fillMaxWidth()) {
        Text("Finish session", color = BarColors.Sub)
    }
}

@Composable
private fun RestHeader(state: RecordState) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        val total = state.restTotalS.takeIf { it > 0 } ?: 1
        ProgressRing(
            progress = state.restRemainingS / total.toFloat(),
            diameter = 110.dp,
            strokeWidth = 9.dp,
        ) {
            Text(formatMmSs(state.restRemainingS), style = MaterialTheme.typography.headlineMedium)
        }
        Spacer(Modifier.padding(horizontal = 8.dp))
        Column {
            SectionCaption("Last set")
            state.lastFeedback?.let { feedback ->
                Text(
                    "${feedback.exerciseName} ${feedback.analysis.reps.size} × ${state.weightUnit.format(
                        feedback.loadKg,
                    )}",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(6.dp))
                FeedbackChips(feedback, state.hrBpm)
            }
        }
    }
}

@Composable
private fun FeedbackChips(feedback: SetFeedback, hrBpm: Int?) {
    val analysis = feedback.analysis
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        analysis.tempoCompliance?.let { compliance ->
            val ok = compliance.repsFullyCompliant == compliance.repsEvaluated
            VerdictChip(
                "Tempo ${compliance.repsFullyCompliant}/${compliance.repsEvaluated}" + if (ok) " ✓" else "",
                if (ok) ChipTone.OK else ChipTone.WARN,
            )
        }
        analysis.velocityLossPct?.let { loss ->
            VerdictChip(
                "−${trim(loss)}% vel",
                when {
                    loss >= DEFAULT_VELOCITY_LOSS_STOP_PCT -> ChipTone.BAD
                    loss >= VEL_LOSS_OK_PCT -> ChipTone.WARN
                    else -> ChipTone.OK
                },
            )
        }
        hrBpm?.let { VerdictChip("♥ $it", ChipTone.NEUTRAL) }
    }
}

@Composable
private fun RepQualityCard(feedback: SetFeedback) {
    val analysis = feedback.analysis
    if (analysis.reps.isEmpty()) return
    val tempo = feedback.tempo?.let { Tempo.parseOrNull(it) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            if (tempo != null) {
                EccTempoChart(analysis, tempo.eccentricS)
            } else {
                ConVelocityChart(analysis)
            }
        }
    }
}

@Composable
private fun EccTempoChart(analysis: SetAnalysis, targetEccS: Double) {
    Text(
        "Rep quality — ecc time (bars) vs ${trim(targetEccS)} s target (line)",
        style = MaterialTheme.typography.bodySmall,
        color = BarColors.Sub,
    )
    Spacer(Modifier.height(8.dp))
    val eccTimes = analysis.reps.map { it.eccS }
    TargetLineBars(
        values = eccTimes,
        target = targetEccS,
        colorFor = { _, v ->
            if (kotlin.math.abs(
                    v - targetEccS,
                ) <= TEMPO_TOLERANCE_S
            ) {
                BarColors.Volt
            } else {
                BarColors.Amber
            }
        },
    )
    Spacer(Modifier.height(6.dp))
    val worst = eccTimes.withIndex().maxByOrNull { kotlin.math.abs(it.value - targetEccS) }
    val insight =
        if (worst == null || kotlin.math.abs(worst.value - targetEccS) <= TEMPO_TOLERANCE_S) {
            "All reps on tempo."
        } else {
            val delta = targetEccS - worst.value
            String.format(
                Locale.US,
                "Rep %d eccentric %.1f s — %.1f s too %s.%s",
                worst.index + 1,
                worst.value,
                kotlin.math.abs(delta),
                if (delta > 0) "fast" else "slow",
                if (delta > 0 && worst.index == eccTimes.lastIndex) " Fatigue showing." else "",
            )
        }
    Text(insight, style = MaterialTheme.typography.bodySmall, color = BarColors.Sub)
    analysis.verdicts.take(2).forEach {
        Text("• $it", style = MaterialTheme.typography.bodySmall, color = BarColors.Sub)
    }
}

@Composable
private fun ConVelocityChart(analysis: SetAnalysis) {
    Text("Mean concentric velocity per rep", style = MaterialTheme.typography.bodySmall, color = BarColors.Sub)
    Spacer(Modifier.height(8.dp))
    val velocities = analysis.reps.map { it.meanConVelMps }
    RepBars(
        values = velocities,
        plannedSlots = null,
        colorFor = { _, v -> repVelocityColor(v, velocities, null) },
        barHeight = 64,
    )
    Spacer(Modifier.height(6.dp))
    analysis.velocityLossPct?.let {
        Text(
            "Velocity loss ${trim(it)}% across the set.",
            style = MaterialTheme.typography.bodySmall,
            color = BarColors.Sub,
        )
    }
    analysis.verdicts.take(2).forEach {
        Text("• $it", style = MaterialTheme.typography.bodySmall, color = BarColors.Sub)
    }
}

@Composable
private fun SlotCard(slot: PlannedSlot, heading: String, unit: WeightUnit, highlight: Boolean = false) {
    val shape = RoundedCornerShape(16.dp)
    val border =
        if (highlight) Modifier.border(1.dp, BarColors.Volt.copy(alpha = 0.25f), shape) else Modifier
    Card(Modifier.fillMaxWidth().then(border), shape = shape) {
        Column(Modifier.padding(14.dp)) {
            SectionCaption(heading, color = if (highlight) BarColors.Volt else BarColors.Sub)
            val core =
                listOfNotNull(
                    slot.reps?.let { "$it reps" },
                    slot.loadKg?.let { unit.format(it) },
                    slot.tempo?.let { "tempo $it" },
                ).joinToString(" · ")
            Text(
                "${slot.exercise.displayName} — $core",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            val secondary =
                listOfNotNull(
                    slot.targetMeanConVelMps?.let { "target ${trim(it)} m/s" },
                    slot.velocityLossStopPct?.let { "stop at −${trim(it)}% vel" },
                    slot.restS?.let { "rest ${formatMmSs(it)}" },
                )
            if (secondary.isNotEmpty()) {
                Text(secondary.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = BarColors.Sub)
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
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { Text("View session & export", fontWeight = FontWeight.Bold) }
    }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        onClick = { navController.navigate("home") { popUpTo("home") { inclusive = true } } },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Done") }
}

private fun formatMmSs(totalS: Int): String = String.format(Locale.US, "%d:%02d", totalS / 60, totalS % 60)

private fun trim(value: Double): String = if (value == Math.floor(value)) value.toInt().toString() else value.toString()
