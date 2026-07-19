package com.macrophage.barspeed.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.macrophage.barspeed.dsp.SetAnalysis
import com.macrophage.barspeed.model.ExerciseKind
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
import com.macrophage.barspeed.ui.components.velocityLossColor
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
        SwitchExerciseSection(state, viewModel)
    } else {
        AdHocForm(state, viewModel)
    }
    Spacer(Modifier.height(16.dp))
    // Timed sets (planks, carries) don't need the bar sensor at all.
    val canStart = state.currentIsTimed || state.imuConnected || state.demoMode
    Button(onClick = viewModel::beginSet, enabled = canStart, modifier = Modifier.fillMaxWidth().height(56.dp)) {
        Text(if (canStart) "START SET" else "Bar sensor not connected", fontWeight = FontWeight.Bold)
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!state.imuConnected && !state.currentIsTimed) {
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

/** Equipment busy? Offer the session's other remaining exercises out of order. */
@Composable
private fun SwitchExerciseSection(state: RecordState, viewModel: RecordViewModel) {
    val choices = state.exerciseChoices
    if (choices.isEmpty()) return
    var showChooser by remember { mutableStateOf(false) }
    TextButton(onClick = { showChooser = true }) {
        Text("Equipment busy? Switch exercise", color = BarColors.Blue)
    }
    if (showChooser) {
        AlertDialog(
            onDismissRequest = { showChooser = false },
            title = { Text("Do another exercise next") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Its remaining sets move to the front; everything else keeps its order.",
                        style = MaterialTheme.typography.bodySmall,
                        color = BarColors.Sub,
                    )
                    choices.forEach { choice ->
                        TextButton(
                            onClick = {
                                showChooser = false
                                viewModel.jumpToExercise(choice.exerciseId)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                "${choice.displayName} — ${choice.setsLeft} " +
                                    if (choice.setsLeft == 1) "set left" else "sets left",
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showChooser = false }) { Text("Cancel") }
            },
        )
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
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Side", style = MaterialTheme.typography.bodySmall, color = BarColors.Sub)
        listOf(null to "Both", "left" to "Left", "right" to "Right").forEach { (value, label) ->
            FilterChip(
                selected = state.sideInput == value,
                onClick = { viewModel.selectSide(value) },
                label = { Text(label) },
            )
        }
    }
    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = state.loadInput,
            onValueChange = viewModel::updateLoadInput,
            label = {
                val suffix = state.weightUnit.suffix
                Text(if (state.currentIsTimed) "Load ($suffix, 0 = BW)" else "Load ($suffix)")
            },
            modifier = Modifier.weight(1f),
        )
        if (state.currentIsTimed) {
            OutlinedTextField(
                value = state.durationInput,
                onValueChange = viewModel::updateDurationInput,
                label = { Text("Hold (s)") },
                modifier = Modifier.weight(1f),
            )
        } else {
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
}

private fun currentKind(state: RecordState): ExerciseKind = state.currentSlot?.exercise?.kind
    ?: state.exerciseOptions.firstOrNull { it.id == state.selectedExerciseId }?.kind
    ?: ExerciseKind.DYNAMIC

@Composable
private fun InSetStage(state: RecordState, viewModel: RecordViewModel) {
    val slot = state.currentSlot
    if (state.currentIsTimed) {
        TimedSetStage(state, viewModel, slot)
        return
    }
    if (currentKind(state) == ExerciseKind.EXPLOSIVE) {
        ExplosiveSetStage(state, viewModel, slot)
        return
    }
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

/**
 * In-set display for explosive lifts (snatch, clean): no tempo — the ring fills
 * with completed reps and the headline number is the last rep's PEAK velocity.
 */
@Composable
private fun ExplosiveSetStage(state: RecordState, viewModel: RecordViewModel, slot: PlannedSlot?) {
    val peaks = state.live.repPeakVelocities
    val plannedReps = if (state.adHoc) state.repsInput.toIntOrNull() else slot?.reps
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        InSetHeader(state, slot)
        Spacer(Modifier.height(10.dp))
        val repProgress =
            plannedReps?.takeIf { it > 0 }?.let { state.live.repCount / it.toFloat() } ?: 0f
        ProgressRing(progress = repProgress) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "PEAK",
                    style = MaterialTheme.typography.labelMedium,
                    color = BarColors.Sub,
                    letterSpacing = 2.sp,
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        peaks.lastOrNull()?.let { String.format(Locale.US, "%.2f", it) } ?: "—",
                        style = MaterialTheme.typography.displayLarge,
                        color = BarColors.Volt,
                    )
                    Text(
                        " m/s",
                        style = MaterialTheme.typography.titleMedium,
                        color = BarColors.Sub,
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                }
                Text(
                    "rep ${state.live.repCount}" + (plannedReps?.let { " of $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = BarColors.Sub,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        // Cadence matters for cyclical ballistic work (kettlebell swings).
        val cadence =
            if (state.live.repCount >= 2 && state.setElapsedS > 0) {
                state.live.repCount * 60 / state.setElapsedS
            } else {
                null
            }
        Text(
            listOfNotNull(
                "Elapsed ${formatMmSs(state.setElapsedS)}",
                cadence?.let { "$it reps/min" },
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = BarColors.Sub,
        )
        Spacer(Modifier.height(14.dp))
        Column(Modifier.fillMaxWidth()) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("Peak velocity per rep", style = MaterialTheme.typography.bodySmall, color = BarColors.Sub)
                slot?.velocityLossStopPct?.let {
                    Text(
                        "stop at −${trim(it)}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = BarColors.Amber,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            RepBars(
                values = peaks,
                plannedSlots = plannedReps,
                colorFor = { _, v -> velocityLossColor(v, peaks, slot?.velocityLossStopPct) },
            )
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = viewModel::endSet, modifier = Modifier.fillMaxWidth().height(64.dp)) {
            Text("END SET", style = MaterialTheme.typography.titleLarge)
        }
    }
}

/** In-set display for holds and carries: big countdown ring, no velocity metrics. */
@Composable
private fun TimedSetStage(state: RecordState, viewModel: RecordViewModel, slot: PlannedSlot?) {
    val targetS = state.currentTimedTargetS
    val elapsed = state.setElapsedS
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        InSetHeader(state, slot)
        Spacer(Modifier.height(10.dp))
        val remaining = targetS?.let { it - elapsed }
        val ringColor = if (remaining != null && remaining < 0) BarColors.Amber else BarColors.Volt
        ProgressRing(
            progress = targetS?.let { (elapsed / it.toFloat()) } ?: 0f,
            color = ringColor,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (slot?.exercise?.kind == ExerciseKind.CARRY) "CARRY" else "HOLD",
                    style = MaterialTheme.typography.labelMedium,
                    color = BarColors.Sub,
                    letterSpacing = 2.sp,
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        (remaining?.coerceAtLeast(0) ?: elapsed).toString(),
                        style = MaterialTheme.typography.displayLarge,
                    )
                    Text(
                        "s",
                        style = MaterialTheme.typography.titleMedium,
                        color = BarColors.Sub,
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                }
                Text(
                    when {
                        targetS == null -> "elapsed"
                        remaining != null && remaining < 0 -> "target ${targetS}s — bonus time!"
                        else -> "of ${targetS}s target"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = BarColors.Sub,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            "Elapsed ${formatMmSs(elapsed)}",
            style = MaterialTheme.typography.titleMedium,
            color = BarColors.Sub,
        )
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
    val side = if (state.adHoc) state.sideInput else slot?.side
    val parts =
        listOfNotNull(
            exerciseName,
            side?.replaceFirstChar { it.uppercase() },
            slot?.let { "Set ${it.setIndexInExercise + 1}/${it.setsInExercise}" },
            loadKg?.takeIf { it > 0 }?.let { state.weightUnit.format(it) }
                ?: "bodyweight".takeIf { state.currentIsTimed },
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
            colorFor = { _, v -> velocityLossColor(v, values, stopPct) },
        )
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
        SwitchExerciseSection(state, viewModel)
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
            if (next.isTimed) {
                OutlinedTextField(
                    value = state.durationInput,
                    onValueChange = viewModel::updateDurationInput,
                    label = { Text("Hold (s)") },
                    modifier = Modifier.weight(1f),
                )
            } else {
                OutlinedTextField(
                    value = state.repsInput,
                    onValueChange = viewModel::updateRepsInput,
                    label = { Text("Reps") },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        RpeSelector(state, viewModel, startsNext = true)
    } else if (state.adHoc) {
        AdHocForm(state, viewModel)
        Spacer(Modifier.height(12.dp))
        RpeSelector(state, viewModel, startsNext = true)
    } else {
        Card(Modifier.fillMaxWidth()) {
            Text(
                "That was the last planned set. Great work!",
                Modifier.padding(12.dp),
                style = MaterialTheme.typography.titleSmall,
                color = BarColors.Volt,
            )
        }
        Spacer(Modifier.height(8.dp))
        RpeSelector(state, viewModel, startsNext = false)
    }
    TextButton(onClick = viewModel::finishSession, modifier = Modifier.fillMaxWidth()) {
        Text("Finish session", color = BarColors.Sub)
    }
}

/** One tile of the effort grid: what gets stored plus the gym-facing wording. */
private data class RpeOption(
    val rpe: Int?,
    val warmup: Boolean,
    val failed: Boolean,
    val description: String,
    val color: Color,
)

/** Narrative wording per set type — "reps left" means nothing for a plank or a snatch. */
private fun rpeOptions(timed: Boolean, explosive: Boolean): List<RpeOption> {
    val effort =
        when {
            timed ->
                listOf(
                    6 to "Easy — plenty of time left",
                    7 to "Solid — had more in me",
                    8 to "Hard — a little left",
                    9 to "Very hard — seconds left",
                    10 to "Max — hit my limit",
                )
            explosive ->
                listOf(
                    6 to "Easy — bar was flying",
                    7 to "Solid — fast and crisp",
                    8 to "Hard — speed dropping",
                    9 to "Very hard — grindy",
                    10 to "Max — barely made it",
                )
            else ->
                listOf(
                    6 to "Easy — 4+ reps left",
                    7 to "Solid — 3 reps left",
                    8 to "Hard — 2 reps left",
                    9 to "Very hard — 1 rep left",
                    10 to "Max — nothing left",
                )
        }
    val failText =
        when {
            timed -> "Broke early — failed"
            explosive -> "Missed the lift"
            else -> "Failed the set"
        }
    return listOf(RpeOption(null, true, false, "Warm-up — barely work", BarColors.Blue)) +
        effort.map { (rpe, text) -> RpeOption(rpe, false, false, text, rpeColor(rpe)) } +
        RpeOption(null, false, true, failText, BarColors.Red)
}

private fun rpeColor(rpe: Int): Color = when {
    rpe <= 7 -> BarColors.Volt
    rpe == 8 -> BarColors.VoltDim
    else -> BarColors.Amber
}

/**
 * Rest-screen effort grid: tapping a narrative saves it on the finished set
 * (warm-ups get a flag instead of an RPE, keeping effort data clean) and,
 * when another set is queued, starts the next set in the same tap.
 */
@Composable
private fun RpeSelector(state: RecordState, viewModel: RecordViewModel, startsNext: Boolean) {
    val feedback = state.lastFeedback
    val options =
        rpeOptions(
            timed = feedback?.actualDurationS != null,
            explosive = feedback?.explosive == true,
        )
    val rated = state.lastSetRpe != null || state.lastSetFailed || state.lastSetWarmup
    val ratedText =
        options.firstOrNull {
            when {
                state.lastSetWarmup -> it.warmup
                state.lastSetFailed -> it.failed
                else -> !it.warmup && !it.failed && it.rpe == state.lastSetRpe
            }
        }?.description
    SectionCaption(
        when {
            rated -> "Logged · ${ratedText ?: ""}"
            startsNext -> "How was that set? Tap to log & start next set"
            else -> "How was that set? Tap to log"
        },
    )
    Spacer(Modifier.height(6.dp))
    options.chunked(2).forEach { row ->
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        ) {
            row.forEach { option ->
                val selected =
                    when {
                        option.warmup -> state.lastSetWarmup
                        option.failed -> state.lastSetFailed
                        else -> !state.lastSetWarmup && !state.lastSetFailed && state.lastSetRpe == option.rpe
                    }
                RpeTile(option, selected, modifier = Modifier.weight(1f)) {
                    viewModel.rateLastSetAndContinue(option.rpe, failed = option.failed, warmup = option.warmup)
                }
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
    if (startsNext) {
        TextButton(onClick = viewModel::startNextSet, modifier = Modifier.fillMaxWidth()) {
            Text("Start next set without logging", color = BarColors.Sub)
        }
    }
}

@Composable
private fun RpeTile(option: RpeOption, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
        modifier
            .clip(shape)
            .background(if (selected) option.color.copy(alpha = 0.2f) else BarColors.Surface, shape)
            .border(1.dp, if (selected) option.color else BarColors.Track, shape)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 6.dp),
    ) {
        Text(
            option.description,
            style = MaterialTheme.typography.titleSmall,
            color = option.color,
        )
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
                val loadText =
                    feedback.loadKg.takeIf { it > 0 }?.let { state.weightUnit.format(it) } ?: "BW"
                val name =
                    feedback.exerciseName +
                        (feedback.side?.let { " (${it.replaceFirstChar { c -> c.uppercase() }})" } ?: "")
                Text(
                    feedback.actualDurationS?.let { "$name ${it}s @ $loadText" }
                        ?: "$name ${feedback.analysis.reps.size} × $loadText",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(6.dp))
                FeedbackChips(feedback, state.hrBpm, state.hrvMs)
            }
        }
    }
}

@Composable
private fun FeedbackChips(feedback: SetFeedback, hrBpm: Int?, hrvMs: Int? = null) {
    val analysis = feedback.analysis
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        feedback.actualDurationS?.let { actual ->
            val planned = feedback.plannedDurationS
            VerdictChip(
                if (planned != null) "Held $actual/${planned}s" else "Held ${actual}s",
                when {
                    planned == null || actual >= planned -> ChipTone.OK
                    actual >= (planned * 0.9).toInt() -> ChipTone.WARN
                    else -> ChipTone.BAD
                },
            )
        }
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
        hrvMs?.let { VerdictChip("HRV ${it}ms", ChipTone.NEUTRAL) }
    }
}

@Composable
private fun RepQualityCard(feedback: SetFeedback) {
    val analysis = feedback.analysis
    // Timed sets have no reps; surface the hold verdicts instead of a chart.
    if (feedback.actualDurationS != null) {
        if (analysis.verdicts.isEmpty()) return
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                analysis.verdicts.forEach {
                    Text("• $it", style = MaterialTheme.typography.bodySmall, color = BarColors.Sub)
                }
            }
        }
        return
    }
    if (analysis.reps.isEmpty()) return
    val tempo = feedback.tempo?.let { Tempo.parseOrNull(it) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            when {
                feedback.explosive -> PeakVelocityChart(analysis)
                tempo != null -> EccTempoChart(analysis, tempo.eccentricS)
                else -> ConVelocityChart(analysis)
            }
        }
    }
}

@Composable
private fun PeakVelocityChart(analysis: SetAnalysis) {
    Text("Peak velocity per rep (m/s)", style = MaterialTheme.typography.bodySmall, color = BarColors.Sub)
    Spacer(Modifier.height(8.dp))
    val peaks = analysis.reps.map { it.peakConVelMps }
    RepBars(
        values = peaks,
        plannedSlots = null,
        colorFor = { _, v -> velocityLossColor(v, peaks, null) },
        barHeight = 64,
    )
    Spacer(Modifier.height(6.dp))
    PowerLine(analysis)
    val best = peaks.maxOrNull()
    if (best != null && best > 0) {
        val lastLossPct = (1.0 - peaks.last() / best) * 100.0
        Text(
            String.format(Locale.US, "Best %.2f m/s · last rep −%.0f%% off best.", best, lastLossPct),
            style = MaterialTheme.typography.bodySmall,
            color = BarColors.Sub,
        )
    }
    analysis.verdicts.take(2).forEach {
        Text("• $it", style = MaterialTheme.typography.bodySmall, color = BarColors.Sub)
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
    PowerLine(analysis)
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
        colorFor = { _, v -> velocityLossColor(v, velocities, null) },
        barHeight = 64,
    )
    Spacer(Modifier.height(6.dp))
    PowerLine(analysis)
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

/** Drive power summary — shown wherever a loaded set's analysis appears. */
@Composable
private fun PowerLine(analysis: SetAnalysis) {
    powerSummary(analysis)?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = BarColors.Sub)
    }
}

private fun powerSummary(analysis: SetAnalysis): String? {
    val peak = analysis.reps.mapNotNull { it.peakPowerW }.maxOrNull() ?: return null
    val avg = analysis.reps.mapNotNull { it.meanConPowerW }.takeIf { it.isNotEmpty() }?.average()
    return "Drive power: peak ${peak.toInt()} W" + (avg?.let { " · avg ${it.toInt()} W" } ?: "")
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
                    slot.side?.replaceFirstChar { it.uppercase() },
                    slot.reps?.let { "$it reps" },
                    slot.durationS?.let {
                        "${it}s " + if (slot.exercise.kind == ExerciseKind.CARRY) "carry" else "hold"
                    },
                    slot.loadKg?.takeIf { it > 0 }?.let { unit.format(it) }
                        ?: "bodyweight".takeIf { slot.isTimed },
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
            slot.exerciseNotes?.let { notes ->
                Spacer(Modifier.height(4.dp))
                Text("“$notes”", style = MaterialTheme.typography.bodySmall, color = BarColors.Amber)
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
