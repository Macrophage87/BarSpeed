package com.macrophage.barspeed.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.macrophage.barspeed.dsp.CoachingRules
import com.macrophage.barspeed.dsp.PhaseTempoTarget
import com.macrophage.barspeed.dsp.SetAnalysis
import com.macrophage.barspeed.dsp.liftDirection
import com.macrophage.barspeed.model.BlePermissionStep
import com.macrophage.barspeed.model.ConnectionState
import com.macrophage.barspeed.model.ExerciseKind
import com.macrophage.barspeed.model.ExitAction
import com.macrophage.barspeed.model.ExitPrompt
import com.macrophage.barspeed.model.Phase
import com.macrophage.barspeed.model.PlateMath
import com.macrophage.barspeed.model.RecordExitPolicy
import com.macrophage.barspeed.model.RestControl
import com.macrophage.barspeed.model.RestControlPolicy
import com.macrophage.barspeed.model.SensorAdvice
import com.macrophage.barspeed.model.SensorAdvicePolicy
import com.macrophage.barspeed.model.SetLoadPolicy
import com.macrophage.barspeed.model.SetWriteState
import com.macrophage.barspeed.model.Stage
import com.macrophage.barspeed.model.Tempo
import com.macrophage.barspeed.model.WeightUnit
import com.macrophage.barspeed.record.PlannedSlot
import com.macrophage.barspeed.record.RecordState
import com.macrophage.barspeed.record.RecordViewModel
import com.macrophage.barspeed.record.SetFeedback
import com.macrophage.barspeed.record.SetRating
import com.macrophage.barspeed.ui.BarColors
import com.macrophage.barspeed.ui.components.ChipTone
import com.macrophage.barspeed.ui.components.LocalBlePermissionUi
import com.macrophage.barspeed.ui.components.PermissionBanner
import com.macrophage.barspeed.ui.components.PermissionBannerBody
import com.macrophage.barspeed.ui.components.ProgressRing
import com.macrophage.barspeed.ui.components.RepBars
import com.macrophage.barspeed.ui.components.SectionCaption
import com.macrophage.barspeed.ui.components.SensorDot
import com.macrophage.barspeed.ui.components.SideArrow
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

    // Back has two routes out of this screen — the top bar's button and the
    // system gesture — and the lifter uses whichever is nearer the thumb, so a
    // guard on one is not a guard. Both ask [RecordExitPolicy] the same
    // question about the same stage, which is what stops them drifting apart.
    val prompt = RecordExitPolicy.promptFor(state.stage, state.setWrite, state.sessionClose)
    // Whether the gate is open, not which gate it is. Storing the prompt here
    // latched the policy's answer at the moment Back was pressed, and the answer
    // moves underneath it: Back during a set write raises SET_SAVING, which says
    // the set "will finish saving even if you leave"; if that write then fails,
    // the policy switches to SET_UNSAVED, whose actions do not include leaving
    // the session open at all. A latched copy kept drawing the old wording AND
    // the old buttons, so the lifter left on a promise that had stopped being
    // true and was never offered the retry that still had the set in memory.
    // Reading the live value means the dialog cannot describe a state the app is
    // no longer in.
    var exitOpen by remember { mutableStateOf(false) }
    val onExitAction: (ExitAction) -> Unit = { action ->
        exitOpen = false
        when (action) {
            ExitAction.STAY -> Unit
            // Closes the session row and stays put. Not because popping would
            // race the write — it no longer can, the close runs on appScope —
            // but because FINISHED is where the lifter asked to go, and it is
            // the screen that offers the session and its export.
            ExitAction.FINISH_SESSION -> viewModel.finishSession()
            ExitAction.DISCARD_SET_AND_LEAVE,
            ExitAction.LEAVE_SESSION_OPEN,
            // Same navigation, a different promise. The close runs on a scope
            // this pop cannot cancel, so leaving is safe and the session
            // finishes either way; only the label the lifter read differs.
            ExitAction.LEAVE_SESSION_CLOSING,
            -> {
                navController.popBackStack()
            }
        }
    }
    val onBack: () -> Unit = {
        if (prompt == ExitPrompt.NONE) {
            navController.popBackStack()
        } else {
            exitOpen = true
        }
    }
    BackHandler(enabled = prompt != ExitPrompt.NONE, onBack = onBack)
    // A prompt that becomes NONE while the dialog is open closes it, which is
    // the right outcome: NONE is the policy saying nothing is at risk any more.
    ExitDialog(if (exitOpen) prompt else ExitPrompt.NONE, onExitAction)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(titleFor(state)) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back") }
                },
                actions = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(end = 16.dp),
                    ) {
                        SensorDot("IMU", state.imuState, demoActive = state.demoMode)
                        SensorDot("HRM", state.hrmState)
                    }
                },
            )
        },
    ) { padding ->
        // One scroll position is shared by every stage, and the in-set screen is
        // now tall enough to scroll — without this reset, a set would open at the
        // rest screen's offset, with the tempo ring and velocity readout off-screen.
        val scroll = rememberScrollState()
        LaunchedEffect(state.stage) { scroll.scrollTo(0) }
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(scroll),
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

/**
 * The gate on the way out, drawn from whatever [ExitPrompt] the policy named.
 *
 * The buttons come from `prompt.actions`, so the offered exits are the ones
 * that module's tests pin; only the wording and the layout are decided here.
 * Material3's `AlertDialog` has two button slots, not three, which is what
 * splits the layout in two:
 *
 *  - One way out: it takes `confirmButton`, in red when it destroys something,
 *    and the way back takes `dismissButton`. This is the delete-session
 *    dialog's shape (`SessionDetailScreen`).
 *  - Several ways out: they go in the body column and `confirmButton` carries
 *    the way back, which is the switch-exercise dialog's shape above.
 *
 * `onDismissRequest` — the scrim tap and the back press that dismisses the
 * dialog itself — maps to [ExitAction.STAY] in both shapes. Nothing here is
 * covered by a test at any commit: `:app` has no test source set, so this
 * mapping was verified by reading it, and by nothing else.
 */
@Composable
private fun ExitDialog(prompt: ExitPrompt, onAction: (ExitAction) -> Unit) {
    if (prompt == ExitPrompt.NONE) return
    val leaves = prompt.actions.filter { it != ExitAction.STAY }
    val single = leaves.singleOrNull()
    if (single != null) {
        AlertDialog(
            onDismissRequest = { onAction(ExitAction.STAY) },
            title = { Text(exitTitle(prompt)) },
            text = { Text(exitBody(prompt), style = MaterialTheme.typography.bodySmall, color = BarColors.Sub) },
            confirmButton = { ExitButton(prompt, single, onAction) },
            dismissButton = { ExitButton(prompt, ExitAction.STAY, onAction) },
        )
    } else {
        AlertDialog(
            onDismissRequest = { onAction(ExitAction.STAY) },
            title = { Text(exitTitle(prompt)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(exitBody(prompt), style = MaterialTheme.typography.bodySmall, color = BarColors.Sub)
                    leaves.forEach { action ->
                        ExitButton(prompt, action, onAction, Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = { ExitButton(prompt, ExitAction.STAY, onAction) },
        )
    }
}

@Composable
private fun ExitButton(
    prompt: ExitPrompt,
    action: ExitAction,
    onAction: (ExitAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    TextButton(onClick = { onAction(action) }, modifier = modifier) {
        Text(exitLabel(prompt, action), color = exitColor(action))
    }
}

private fun exitTitle(prompt: ExitPrompt): String = when (prompt) {
    ExitPrompt.NONE -> ""
    ExitPrompt.SET_IN_PROGRESS -> "Discard this set?"
    ExitPrompt.SESSION_OPEN -> "Leave the session?"
    ExitPrompt.SET_SAVING -> "This set is still saving"
    ExitPrompt.SET_UNSAVED -> "This set did not save"
    ExitPrompt.SESSION_CLOSING -> "Finishing this session"
    ExitPrompt.SESSION_NOT_CLOSED -> "This session did not finish"
}

private fun exitBody(prompt: ExitPrompt): String = when (prompt) {
    ExitPrompt.NONE -> ""
    // Names the save-and-end control that is already on this screen, below the
    // fold: without it the choice reads as "lose the set or stay", which is a
    // false pair while a control that ends the set properly sits underneath.
    ExitPrompt.SET_IN_PROGRESS ->
        "Nothing about this set has been saved — the reps, the sensor data and the effort rating all go. " +
            "To keep it, tap Keep recording and end the set with the effort grid (or END SET EARLY) at the " +
            "bottom of this screen."
    // No word here may imply the session can be picked up again: nothing reads
    // an open session row back into this screen, and the next set would open a
    // second one.
    ExitPrompt.SESSION_OPEN ->
        "Every completed set is already saved. Finishing writes the session's end time and its heart-rate " +
            "and HRV summary. Leaving without finishing leaves the session open, and nothing can finish it " +
            "later. Either way the rest of the planned sets are dropped."
    // Now says leaving is safe for the set, because as of this commit it is:
    // the write runs on a scope the pop cannot cancel. That sentence was
    // deliberately absent while the prompt was unreachable and the claim would
    // have been false. It is the session, not the set, that is still at risk
    // here, and the wording points at that instead.
    ExitPrompt.SET_SAVING ->
        "This set is being written to your history now, and it will finish saving even if you leave. " +
            "What will not finish is the session: closing it while a set is still saving leaves that set " +
            "out of the session's heart-rate and HRV summary, and nothing rewrites it afterwards."
    ExitPrompt.SET_UNSAVED ->
        "This set could not be written to your history. It is still held in memory, so tapping SAVE THIS " +
            "SET AGAIN on this screen can still store it — freeing some space on the phone first if that " +
            "is what stopped it. Leaving now loses the reps, the sensor data and the effort rating."
    // Says the finish lands either way, because it does: the close runs on a
    // scope the pop cannot cancel. Nothing here offers to call it off — nothing
    // can, and offering it would be a race dressed up as a choice.
    ExitPrompt.SESSION_CLOSING ->
        "You asked to finish this session and it is being written now. It will finish even if you leave, " +
            "including the end time and the heart-rate and HRV summary. There is nothing left to decide here."
    // The one prompt that must not undersell what is at stake. Session HRV is
    // computed from beat-to-beat intervals collected across the whole session,
    // rests included, and those are held in memory and nowhere else — the
    // per-set heart-rate streams keep the in-set beats and nothing keeps these.
    ExitPrompt.SESSION_NOT_CLOSED ->
        "This session could not be closed, so it has no end time and no heart-rate or HRV summary. Tapping " +
            "FINISH SESSION AGAIN on this screen can still write them — freeing some space on the phone " +
            "first if that is what stopped it. Every set is already saved either way, but the session HRV " +
            "is not held anywhere else and leaving now loses it."
}

private fun exitLabel(prompt: ExitPrompt, action: ExitAction): String = when (action) {
    ExitAction.DISCARD_SET_AND_LEAVE -> "Discard set"
    ExitAction.FINISH_SESSION -> "Finish session"
    ExitAction.LEAVE_SESSION_OPEN -> "Leave without finishing"
    ExitAction.LEAVE_SESSION_CLOSING -> "Leave"
    // Per prompt, not a two-way test with a fallback. The fallback read "Keep
    // resting" for anything that was not a set in progress, so a new prompt got
    // the rest screen's wording by default -- offered to a lifter standing over
    // a loaded bar, with no compile error, no lint and no test to catch it.
    ExitAction.STAY ->
        when (prompt) {
            ExitPrompt.NONE -> ""
            ExitPrompt.SET_IN_PROGRESS -> "Keep recording"
            ExitPrompt.SESSION_OPEN -> "Keep resting"
            ExitPrompt.SET_SAVING -> "Stay here"
            ExitPrompt.SET_UNSAVED -> "Keep this set"
            ExitPrompt.SESSION_CLOSING -> "Wait here"
            ExitPrompt.SESSION_NOT_CLOSED -> "Stay and retry"
        }
}

private fun exitColor(action: ExitAction): Color = when (action) {
    ExitAction.DISCARD_SET_AND_LEAVE -> BarColors.Red
    ExitAction.LEAVE_SESSION_OPEN, ExitAction.LEAVE_SESSION_CLOSING -> BarColors.Sub
    else -> Color.Unspecified
}

@Composable
private fun SetupStage(state: RecordState, viewModel: RecordViewModel) {
    if (!state.imuConnected) {
        // The permission banner replaces this card's advice rather than sitting
        // beside it. The demo chip below is a sibling in this Column and stays
        // either way: it is the only demo toggle on this screen.
        val permissionHeld by LocalBlePermissionUi.current.step.collectAsState()
        // Latched, not read straight from state.imuState: AutoConnectManager's
        // else branch retries immediately after every Failed -- calls
        // connect(), which sets Connecting, before any backoff delay runs --
        // so a direct read would flip this text back to the generic advice on
        // every single retry.
        //
        // rememberSaveable, not remember: the sensor's own state has not
        // changed across a rotation or a transient process death, so the
        // advice earned before the recreation still applies, and this is
        // deliberately kept rather than reset by either. It does not survive
        // leaving this `if` block -- state.stage moving past SETUP, or
        // navigating away from Record -- which discards it the same way any
        // composable's local state is discarded; the next time this block is
        // entered it reinitializes from whatever state.imuState holds then.
        //
        // The effect only reacts to Failed, deliberately: imuConnected and
        // imuState are written from the same source in the same
        // RecordState.copy(), so the instant imuState becomes Connected this
        // whole block -- rememberSaveable included -- has already left
        // composition, one recomposition before a Connected arm here could
        // run. What resets imuAdvice on a real connect is the block being
        // gone, not this effect.
        //
        // Exposure this trades for: once latched to UNRESPONSIVE it stays
        // UNRESPONSIVE across Connecting and Disconnected, clearing only on a
        // Failed with linkEstablished = false or on leaving this block. A
        // sensor that answered and then went flat mid-session reports
        // Disconnected, and this still reads "the sensor answered and then
        // stopped responding" instead of naming the battery.
        var imuAdvice by rememberSaveable { mutableStateOf(SensorAdvicePolicy.forState(state.imuState)) }
        LaunchedEffect(state.imuState) {
            val s = state.imuState
            if (s is ConnectionState.Failed) imuAdvice = SensorAdvicePolicy.forState(s)
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Bar sensor not connected", style = MaterialTheme.typography.titleSmall)
                if (permissionHeld == BlePermissionStep.GRANTED) {
                    Text(
                        imuAdviceText(imuAdvice),
                        style = MaterialTheme.typography.bodySmall,
                        color = BarColors.Sub,
                    )
                } else {
                    PermissionBannerBody(step = permissionHeld, demoMode = state.demoMode)
                }
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

/**
 * Prose for [SensorAdvice]. PAIR_OR_POWER covers more than its name says:
 * `GattClient.connect()` also fails with "Bluetooth unavailable" (no adapter
 * on this device) and "Bad device address" (the stored pairing is corrupted)
 * -- neither of which pairing or powering the sensor fixes, which is why the
 * text below also names checking the phone's own Bluetooth. Both are
 * provable preconditions read straight off the three early returns in
 * `GattClient.connect()`, not a claim about which cause a lifter hits more
 * often; this repository has never measured that.
 */
private fun imuAdviceText(advice: SensorAdvice): String = when (advice) {
    SensorAdvice.PAIR_OR_POWER ->
        "Pair or power on the WitMotion sensor, or check the phone's own Bluetooth. " +
            "You can also enable demo mode to try the app with synthesized data."
    SensorAdvice.UNRESPONSIVE ->
        "The sensor answered and then stopped responding -- pairing again is unlikely to help. " +
            "Power-cycle it, or enable demo mode to try the app with synthesized data."
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
    // READY carries START SET, so it is the last screen before a set that would
    // record nothing. It renders at most once per session -- startNextSet writes
    // READY and calls beginSet in the same frame -- which is why RESTING carries
    // this too.
    PermissionBanner(demoMode = state.demoMode)
    val slot = state.currentSlot
    if (slot != null) {
        if (slot.isExerciseChange) {
            MoveSensorCard(slot.exercise.displayName)
        }
        SlotCard(
            slot,
            heading = "Up next",
            unit = state.weightUnit,
            highlight = true,
            plateLoadKgOverride = state.statedLoadKg,
        )
        SwitchExerciseSection(state, viewModel)
        Spacer(Modifier.height(8.dp))
        // The only load input a plan set gets. READY renders at most once per
        // session -- startNextSet writes READY and calls beginSet in the same
        // frame -- so without this, set 1 is the one set of a plan session the
        // lifter cannot say anything about, and it records the prescription
        // whatever went on the bar.
        Text(
            "Adjust this set (deviations are recorded)",
            style = MaterialTheme.typography.bodySmall,
            color = BarColors.Sub,
        )
        OutlinedTextField(
            value = state.loadInput,
            onValueChange = viewModel::updateLoadInput,
            label = { Text("Load (${state.weightUnit.suffix})") },
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        AdHocForm(state, viewModel)
    }
    Spacer(Modifier.height(12.dp))
    // The bar sensor is record-only for standard lifts: the lifter (or the
    // voice guide) counts; explosive lifts stay sensor-counted.
    val kind = state.currentSlot?.exercise?.kind
        ?: state.exerciseOptions.firstOrNull { it.id == state.selectedExerciseId }?.kind
    val manual = !state.currentIsTimed && !state.demoMode &&
        (kind != ExerciseKind.EXPLOSIVE || !state.imuConnected)
    Button(onClick = viewModel::beginSet, modifier = Modifier.fillMaxWidth().height(56.dp)) {
        Text(if (manual) "START SET — you count" else "START SET", fontWeight = FontWeight.Bold)
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
    SessionCloseControls(state, viewModel)
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
    if (state.guidedSet) {
        GuidedSetStage(state, viewModel, slot)
        return
    }
    if (state.manualSet) {
        ManualSetStage(state, viewModel, slot)
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
        EndSetControl(state, viewModel)
    }
}

/**
 * Voice-guided cadence set: the app calls the tempo ("Down… one… two… three…
 * Up") and counts reps; the screen mirrors the voice with a phase countdown.
 */
@Composable
private fun GuidedSetStage(state: RecordState, viewModel: RecordViewModel, slot: PlannedSlot?) {
    val plannedReps = if (state.adHoc) state.repsInput.toIntOrNull() else slot?.reps
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        InSetHeader(state, slot)
        Spacer(Modifier.height(10.dp))
        val total = state.guidedPhaseTotal.coerceAtLeast(1)
        val phaseColor =
            when (state.guidedLabel) {
                "DOWN" -> BarColors.Blue
                "UP" -> BarColors.Volt
                "HOLD" -> BarColors.Amber
                "DONE" -> BarColors.Volt
                else -> BarColors.Sub
            }
        ProgressRing(
            progress = 1f - state.guidedCountdown / total.toFloat(),
            color = phaseColor,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    state.guidedLabel.ifBlank { "GUIDED" },
                    style = MaterialTheme.typography.labelMedium,
                    color = phaseColor,
                    letterSpacing = 2.sp,
                )
                Text(
                    if (state.guidedLabel == "DONE") "✓" else "${state.guidedCountdown}",
                    style = MaterialTheme.typography.displayLarge,
                )
                Text(
                    "rep ${state.manualReps}" + (plannedReps?.let { " of $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = BarColors.Sub,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            "Follow the voice — the app counts the reps.",
            style = MaterialTheme.typography.bodySmall,
            color = BarColors.Sub,
        )
        Spacer(Modifier.height(24.dp))
        EndSetControl(state, viewModel)
    }
}

/** Sensorless set: the lifter taps to count reps; the ring tracks planned progress. */
@Composable
private fun ManualSetStage(state: RecordState, viewModel: RecordViewModel, slot: PlannedSlot?) {
    val plannedReps = if (state.adHoc) state.repsInput.toIntOrNull() else slot?.reps
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        InSetHeader(state, slot)
        Spacer(Modifier.height(10.dp))
        val progress = plannedReps?.takeIf { it > 0 }?.let { state.manualReps / it.toFloat() } ?: 0f
        ProgressRing(progress = progress) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "MANUAL COUNT",
                    style = MaterialTheme.typography.labelMedium,
                    color = BarColors.Sub,
                    letterSpacing = 2.sp,
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${state.manualReps}", style = MaterialTheme.typography.displayLarge)
                    Text(
                        " reps",
                        style = MaterialTheme.typography.titleMedium,
                        color = BarColors.Sub,
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                }
                Text(
                    plannedReps?.let { "of $it planned" } ?: "Elapsed ${formatMmSs(state.setElapsedS)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = BarColors.Sub,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        // Gone once the set has ended, because the set is over and the count is
        // frozen into the write. addManualRep already ignores taps from that
        // moment, so leaving the button drawn would be a 72dp target that
        // silently does nothing, right where the lifter's thumb already is.
        if (state.setWrite == SetWriteState.NONE) {
            Button(onClick = viewModel::addManualRep, modifier = Modifier.fillMaxWidth().height(72.dp)) {
                Text("+1 REP", style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.height(10.dp))
        }
        EndSetControl(state, viewModel)
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
        EndSetControl(state, viewModel)
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
        EndSetControl(state, viewModel)
    }
}

@Composable
private fun InSetHeader(state: RecordState, slot: PlannedSlot?) {
    val exerciseName =
        slot?.exercise?.displayName
            ?: state.exerciseOptions.firstOrNull { it.id == state.selectedExerciseId }?.displayName
            ?: "Set"
    // The same rule endSet records by, called rather than restated: this header
    // is on screen while the set that will carry that number is being done, so
    // a second reading of the load here would be a number the row does not
    // contain. `slot` is state.currentSlot at all five call sites.
    val loadKg =
        SetLoadPolicy.resolve(
            adHoc = state.adHoc,
            plannedAddedKg = slot?.loadKg,
            typedAddedKg = state.weightUnit.parseToKg(state.loadInput),
            statedAddedKg = state.statedLoadKg,
        )
    val side = if (state.adHoc) state.sideInput else slot?.side
    val parts =
        listOfNotNull(
            exerciseName,
            side?.replaceFirstChar { it.uppercase() },
            slot?.let { "Set ${it.setIndexInExercise + 1}/${it.setsInExercise}" },
            loadKg.takeIf { it > 0 }?.let { state.weightUnit.format(it) }
                ?: "bodyweight".takeIf { state.currentIsTimed },
        )
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            // The same value the word in `parts` carries, drawn as a shape
            // rather than a word. See [SideArrow].
            SideArrow(side, Modifier.padding(end = 8.dp))
            Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = BarColors.Sub)
        }
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
    // Which digit a phase is charged against is a property of the (tempo, lift)
    // PAIR, and it is resolved in :core:dsp against the same direction beginSet
    // handed the tracker. `tempo` is null in every state that reaches here --
    // InSetStage routes a set with a tempo to the guided branch first, and
    // beginSet derives guidedSet from this same expression -- so this line
    // changes nothing on screen. It is here so the decision has one home
    // rather than three. #127.
    val targetS = tempo?.let { PhaseTempoTarget.secondsFor(it, state.currentExercise.liftDirection(), phase) }
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

/**
 * The set-end control, which changes with what the set has actually delivered.
 *
 * Until the set has met its target the only way out is to stop early, and that
 * is a failed set — offering "solid, had more in me" three reps into a five-rep
 * set would let an abandoned set be logged as a good one. Once the target is
 * met the effort grid takes over and rating IS ending.
 *
 * Once the set HAS ended, neither of those is the control any more, and the
 * write's state decides what is. This is the single place all five in-set
 * layouts route through, which is why the branch lives here rather than at each
 * of them.
 */
@Composable
private fun EndSetControl(state: RecordState, viewModel: RecordViewModel) {
    when (state.setWrite) {
        SetWriteState.IN_FLIGHT -> SavingSetNotice()
        SetWriteState.FAILED -> UnsavedSetNotice(viewModel)
        SetWriteState.NONE ->
            if (state.setTargetMet) EndSetRpeGrid(state, viewModel) else EndSetEarlyButton(viewModel)
    }
}

/**
 * Shown for the few hundred milliseconds the set takes to analyse, gzip and
 * store. Deliberately not a control: there is nothing useful to tap, and the
 * one thing a lifter might reach for — ending the set again — is already
 * blocked in the ViewModel.
 */
@Composable
private fun SavingSetNotice() {
    Text(
        "SAVING SET…",
        style = MaterialTheme.typography.titleMedium,
        color = BarColors.Sub,
        letterSpacing = 2.sp,
    )
    Spacer(Modifier.height(6.dp))
    SectionCaption("Keeping this screen open is not required — the set finishes saving either way")
}

/**
 * The set ended and could not be stored. The reps, the sensor stream and the
 * effort rating are all still in memory, so this offers the one action that can
 * still save them.
 *
 * It replaces the effort grid rather than sitting under it. The grid ends a set,
 * and this set is already over; leaving it drawn would offer a control that does
 * nothing and hide the one that does.
 */
@Composable
private fun UnsavedSetNotice(viewModel: RecordViewModel) {
    Text("THIS SET DID NOT SAVE", style = MaterialTheme.typography.titleMedium, color = BarColors.Red)
    Spacer(Modifier.height(6.dp))
    SectionCaption("Still in memory. Freeing space on the phone may be what it needs")
    Spacer(Modifier.height(10.dp))
    Button(
        onClick = viewModel::retrySetWrite,
        modifier = Modifier.fillMaxWidth().height(64.dp),
    ) {
        Text("SAVE THIS SET AGAIN", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

/**
 * Deliberately ends the set with NO rating attached. The set still lands as a
 * failure — this button only appears when the set is short of its target, which
 * is exactly what [RecordViewModel.endSet] auto-fails on — but it lands as a
 * DERIVED failure rather than a tapped one. Tapping the verdict would make it
 * stick: a lifter who did all five reps but only tapped "+1 REP" three times
 * gets this button as their only exit, and a tapped failure is one that
 * correcting the rep count afterwards can never clear.
 */
@Composable
private fun EndSetEarlyButton(viewModel: RecordViewModel) {
    OutlinedButton(
        onClick = { viewModel.endSet() },
        modifier = Modifier.fillMaxWidth().height(64.dp),
    ) {
        Text("END SET EARLY", style = MaterialTheme.typography.titleLarge, color = BarColors.Red)
    }
    Spacer(Modifier.height(6.dp))
    SectionCaption("Stopping short logs a failed set · finish it to rate the effort")
}

/**
 * The effort grid IS the end-set control once the set is complete. Tapping how
 * the set felt ends the set and logs the rating in one action, while the set is
 * still fresh — there is no separate page between lifting and resting.
 */
@Composable
private fun EndSetRpeGrid(state: RecordState, viewModel: RecordViewModel) {
    val options = rpeOptions(timed = state.currentIsTimed, explosive = currentKind(state) == ExerciseKind.EXPLOSIVE)
    SectionCaption("Tap how that set felt to end it")
    Spacer(Modifier.height(6.dp))
    options.chunked(2).forEach { row ->
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        ) {
            row.forEach { option ->
                RpeTile(option, selected = false, modifier = Modifier.weight(1f)) {
                    viewModel.endSet(SetRating(option.rpe, failed = option.failed, warmup = option.warmup))
                }
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

/**
 * The screen between sets, ordered by what the lifter has to reach rather than
 * by when it happened.
 *
 * The rest countdown and START NEXT SET are the two things this screen exists
 * for, and they were at opposite ends of it. Everything about the set that had
 * just finished -- the effort line, the rep-correction row and a rep-quality
 * card carrying a 64dp chart -- sat between the countdown and the next-set
 * block, so the button that starts the set was below all of it. The field
 * report for v0.1.37 is "try to get the rest dialog to fit on one page without
 * scrolling"; that report, not this arithmetic, is the evidence it did not fit,
 * because `:app` has no test source set and nothing here has been measured on a
 * device.
 *
 * So the next-set half now comes first and the last-set half second. The
 * distance the button moves up is exactly the height of the block that used to
 * precede it -- summing the declared and default heights of that block gives
 * roughly 270dp with a chart and roughly 90dp without -- and it is bought
 * without removing a control, hiding one behind a disclosure or shrinking any
 * text. The screen still scrolls: a per-rep chart and a rest countdown do not
 * both fit above the fold on a phone. What is below the fold is now the detail
 * rather than the control.
 *
 * [SessionCloseControls] stays where it has always been, directly under
 * [StartNextSetButton], and that is deliberate rather than incidental. In
 * `SessionCloseState.FAILED` it draws THIS SESSION DID NOT FINISH and the retry;
 * moving it to the foot of the screen would put the one control that recovers an
 * unclosed session underneath the chart, which is the defect this change is
 * fixing, one control over.
 */
@Composable
private fun RestingStage(state: RecordState, viewModel: RecordViewModel) {
    RestHeader(state)
    Spacer(Modifier.height(6.dp))
    // Sets two onwards start from here, not from READY, and this is the screen
    // where the lifter has a rest period to spend fixing it.
    PermissionBanner(demoMode = state.demoMode)
    NextSetBlock(state, viewModel)
    SessionCloseControls(state, viewModel)
    Spacer(Modifier.height(16.dp))
    LastSetDetail(state, viewModel)
}

/** What happens next: the prescription, the deviations, and the way into it. */
@Composable
private fun NextSetBlock(state: RecordState, viewModel: RecordViewModel) {
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
            plateLoadKgOverride = state.statedLoadKg,
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
        StartNextSetButton(state, viewModel)
    } else if (state.adHoc) {
        AdHocForm(state, viewModel)
        Spacer(Modifier.height(12.dp))
        StartNextSetButton(state, viewModel)
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
}

/**
 * How the set just finished went: the effort logged for it, the rep count and
 * the per-rep chart.
 *
 * Below the next-set block rather than above it. The one-line summary of all
 * three is already at the top of the screen in [RestHeader] -- the exercise, the
 * rep count and the load in words, the tempo ratio, the velocity loss and the
 * heart rate as chips -- so what moves down here is the detail behind a summary
 * that stays above the fold, not the summary itself.
 *
 * The corrections stay one scroll away for the whole rest period rather than
 * being hidden: `changingEffort` moved down with the line it belongs to, still
 * keyed on `setsCompleted`, so an open effort grid closes when the next set ends
 * rather than carrying a stale set's selection into the following rest.
 */
@Composable
private fun LastSetDetail(state: RecordState, viewModel: RecordViewModel) {
    // The effort tile is tapped mid-workout to end the set, so give a mistap
    // somewhere to go rather than baking it into the record.
    var changingEffort by remember(state.setsCompleted) { mutableStateOf(false) }
    LoggedEffortLine(state) { changingEffort = !changingEffort }
    if (changingEffort) {
        RpeSelector(state, viewModel) { changingEffort = false }
    }
    state.lastFeedback?.let { RepCorrectionRow(it, viewModel) }
    Spacer(Modifier.height(6.dp))
    state.lastFeedback?.let { RepQualityCard(it) }
}

/**
 * Begin the next set, when beginning one is a thing that may be done.
 *
 * Both rest layouts route through this rather than drawing the button twice,
 * which is what makes the gate a gate: a second copy is a second place for it to
 * be missing from.
 *
 * `startNextSet` writes READY and calls `beginSet` in the same frame — the
 * foreground service starts, the collectors attach, the stage becomes IN_SET. A
 * close landing on top of that overwrites the stage with FINISHED and stops the
 * service while the lifter is under a loaded bar with the buffers filling, on a
 * screen that has no way to end a set.
 */
@Composable
private fun StartNextSetButton(state: RecordState, viewModel: RecordViewModel) {
    if (RestControl.START_NEXT_SET !in RestControlPolicy.controls(state.sessionClose)) return
    Button(
        onClick = viewModel::startNextSet,
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) { Text("START NEXT SET") }
}

/**
 * The controls that close the session, drawn from whatever [RestControlPolicy]
 * says may be operated while the close is where it is.
 *
 * Asked of the policy rather than written as an `if` here, for the reason the
 * exit gate is: `:app` has no test source set, so a condition written beside its
 * caller cannot be tested at all. This is the same surface the gate covers, one
 * layer in — guarding the way out while leaving these live is not a guard.
 */
@Composable
private fun SessionCloseControls(state: RecordState, viewModel: RecordViewModel) {
    val controls = RestControlPolicy.controls(state.sessionClose)
    if (RestControl.FINISH_SESSION in controls) {
        TextButton(onClick = viewModel::finishSession, modifier = Modifier.fillMaxWidth()) {
            Text("Finish session", color = BarColors.Sub)
        }
    }
    if (RestControl.RETRY_FINISH in controls) {
        UnclosedSessionNotice(viewModel)
    }
}

/**
 * The session did not close: it has no end time, no heart-rate summary and no
 * HRV. Every set is already stored, so what is at risk here is smaller than an
 * unsaved set and is not nothing — the R-R intervals the session HRV is computed
 * from are held in memory here and in no durable place at all.
 *
 * It replaces Finish session rather than sitting beside it, as
 * [UnsavedSetNotice] replaces the effort grid: two controls that both close the
 * session would launch the same work from different inputs, and one of them
 * would be the wrong one.
 */
@Composable
private fun UnclosedSessionNotice(viewModel: RecordViewModel) {
    Text("THIS SESSION DID NOT FINISH", style = MaterialTheme.typography.titleMedium, color = BarColors.Red)
    Spacer(Modifier.height(6.dp))
    SectionCaption("Your sets are saved. The end time, heart rate and HRV are not")
    Spacer(Modifier.height(10.dp))
    Button(
        onClick = viewModel::retrySessionClose,
        modifier = Modifier.fillMaxWidth().height(64.dp),
    ) {
        Text("FINISH SESSION AGAIN", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
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

/** Correction grid on the rest screen, for a mistapped effort rating. */
@Composable
private fun RpeSelector(state: RecordState, viewModel: RecordViewModel, onPicked: () -> Unit) {
    val feedback = state.lastFeedback
    val options =
        rpeOptions(
            timed = feedback?.actualDurationS != null,
            explosive = feedback?.explosive == true,
        )
    SectionCaption("Change the effort logged for that set")
    Spacer(Modifier.height(6.dp))
    options.chunked(2).forEach { row ->
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        ) {
            row.forEach { option ->
                // A set can now carry BOTH an effort rating and the auto-fail
                // flag, so these have to be a precedence chain rather than three
                // independent predicates — otherwise two tiles light up at once.
                val selected =
                    when {
                        state.lastSetWarmup -> option.warmup
                        state.lastSetFailed -> option.failed
                        else -> !option.warmup && !option.failed && option.rpe == state.lastSetRpe
                    }
                RpeTile(option, selected, modifier = Modifier.weight(1f)) {
                    viewModel.rateLastSet(option.rpe, failed = option.failed, warmup = option.warmup)
                    onPicked()
                }
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

/** Rest-screen reminder of what was logged for the finished set (incl. auto-fail on early stop). */
@Composable
private fun LoggedEffortLine(state: RecordState, onChange: () -> Unit) {
    val feedback = state.lastFeedback ?: return
    if (state.lastSetRpe == null && !state.lastSetFailed && !state.lastSetWarmup) return
    val options =
        rpeOptions(timed = feedback.actualDurationS != null, explosive = feedback.explosive)
    // Show what the lifter tapped, and mark the failure alongside it rather than
    // replacing it — a set can be both "very hard" and short of its target.
    val tapped =
        options.firstOrNull {
            when {
                state.lastSetWarmup -> it.warmup
                state.lastSetRpe != null -> !it.warmup && !it.failed && it.rpe == state.lastSetRpe
                else -> it.failed
            }
        }?.description
    val text =
        when {
            tapped == null -> null
            state.lastSetFailed && state.lastSetRpe == null && !state.lastSetWarmup -> tapped
            state.lastSetFailed -> "$tapped · short of target"
            else -> tapped
        }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        SectionCaption(
            "Effort · ${text ?: ""}",
            color = if (state.lastSetFailed) BarColors.Red else BarColors.Volt,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onChange) { Text("Change", color = BarColors.Sub) }
    }
    Spacer(Modifier.height(4.dp))
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

/** Sensor miscount (or manual set)? Adjust the recorded rep count with − / +. */
@Composable
private fun RepCorrectionRow(feedback: SetFeedback, viewModel: RecordViewModel) {
    if (feedback.actualDurationS != null) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            if (feedback.repsOverride != null) "Reps (corrected)" else "Reps counted",
            style = MaterialTheme.typography.bodySmall,
            color = BarColors.Sub,
        )
        TextButton(onClick = { viewModel.overrideLastSetReps(feedback.effectiveReps - 1) }) {
            Text("−", style = MaterialTheme.typography.titleMedium)
        }
        Text(
            "${feedback.effectiveReps}",
            style = MaterialTheme.typography.titleMedium,
            color = if (feedback.repsOverride != null) BarColors.Amber else BarColors.Text,
        )
        TextButton(onClick = { viewModel.overrideLastSetReps(feedback.effectiveReps + 1) }) {
            Text("+", style = MaterialTheme.typography.titleMedium)
        }
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // The rest screen can hold two arrows at once -- this one
                    // and "Up next" -- and they must not read as one signal.
                    // Colour separates last from next; direction alone
                    // separates left from right.
                    SideArrow(feedback.side, Modifier.padding(end = 6.dp), color = BarColors.Text)
                    Text(
                        feedback.actualDurationS?.let { "$name ${it}s @ $loadText" }
                            ?: "$name ${feedback.effectiveReps} × $loadText",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Spacer(Modifier.height(6.dp))
                FeedbackChips(feedback, state.hrBpm, state.hrvMs)
            }
        }
    }
}

/**
 * The verdict pills for the set just finished.
 *
 * A [FlowRow], because the pill count is variable and the width they are given
 * is not. Five can be emitted at once -- held, tempo, velocity loss, heart rate,
 * HRV -- and they are laid out inside [RestHeader]'s column, which is measured
 * with what is left of the row: the screen width less the 16dp screen padding
 * either side, less the 110dp ring, less the 16dp the gap Spacer contributes.
 * On a 411dp-wide phone that arithmetic gives 253dp and on a 360dp one 202dp,
 * and a plain `Row` does not wrap: children that do not fit are placed past its
 * own width rather than moved to a second line. The field report for v0.1.37 is
 * "the heart rate marker doesn't fit on the page and runs to the side"; heart
 * rate is the fourth pill of the five, and HRV the fifth.
 *
 * Wrapping rather than widening, because no width is safe: the pill text is
 * data-dependent (a three-digit heart rate, a two-digit rep count either side of
 * the tempo ratio) and nothing bounds it. Wrapping usually costs the header no
 * height at all: the ring is 110dp and the column beside it comes to about
 * 100dp with a single-line title and two pill rows, so the ring is still what
 * sets the row's height. A third pill row, or a title long enough to wrap,
 * grows it -- which is why the header keeps the ring and gives the pills the
 * whole of the remaining column.
 *
 * The `@OptIn` is on this function alone, not the file or the module, which is
 * how `PlanDetailScreen` already carries the same import for the same reason.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FeedbackChips(feedback: SetFeedback, hrBpm: Int?, hrvMs: Int? = null) {
    val analysis = feedback.analysis
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
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
        // No gradeable rep means no ratio to show. Drawing it anyway printed
        // "Tempo 0/0 ✓" in the OK tone, because 0 == 0 -- a green tick over a
        // set nothing graded. On the history screen that tick sits in the same
        // Card as the "No reps detected" verdict; here the verdict text does
        // not render for a rep set, so the tick appears beside a "0 ×" header
        // with nothing to contradict it.
        analysis.tempoCompliance?.takeIf { it.repsEvaluated > 0 }?.let { compliance ->
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
    // The prescription this set's eccentric was GRADED against, read off the
    // analysis rather than resolved from the digits a second time, so the chart
    // cannot state a target the compliance chip beside it was not scored on.
    // Null means the set carried no prescription or its eccentric stroke was
    // explosive, and neither has a target line to draw -- #56.
    val targetEccS = analysis.tempoCompliance?.eccentricPrescribedS
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            when {
                feedback.explosive -> PeakVelocityChart(analysis)
                targetEccS != null -> EccTempoChart(analysis, targetEccS)
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
    // Reps whose eccentric was never measured are left out rather than charted as 0.
    val eccTimes = analysis.reps.mapNotNull { it.eccS }
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
    // The wording lives in :core:dsp beside the verdicts rendered below it, so
    // that it is reachable by a test. This module has no test source set.
    val insight = CoachingRules.eccentricTempoInsight(analysis.reps, targetEccS, TEMPO_TOLERANCE_S)
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
private fun SlotCard(
    slot: PlannedSlot,
    heading: String,
    unit: WeightUnit,
    highlight: Boolean = false,
    plateLoadKgOverride: Double? = null,
) {
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 4.dp),
            ) {
                SideArrow(slot.side, Modifier.padding(end = 8.dp))
                Text(
                    "${slot.exercise.displayName} — $core",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
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
            // The plate line is an INSTRUCTION, not a description: the title
            // above keeps stating what the plan asked for, but telling the
            // lifter to load 100 while they have said 90 would be telling them
            // to do the wrong thing. This also opens a case that could not
            // arise before -- a barbell slot the plan gave no load for now
            // draws a line once the lifter states one -- which is wanted:
            // there was nothing to compute from before, and there is now.
            if (slot.exercise.usesBarbell) {
                (plateLoadKgOverride ?: slot.loadKg)?.takeIf { it > 0 }?.let { loadKg ->
                    plateLine(loadKg, unit)?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = BarColors.Blue)
                    }
                }
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

/** "Plates/side: 45 + 25 + 2.5 (45 lb bar)" for barbell lifts. */
private fun plateLine(loadKg: Double, unit: WeightUnit): String? {
    val breakdown = PlateMath.perSide(loadKg, unit)
    val barText = "${trim(breakdown.barWeight)} ${unit.suffix} bar"
    return when {
        breakdown.belowBar -> "Below bar weight ($barText)"
        breakdown.platesPerSide.isEmpty() && breakdown.leftoverPerSide == 0.0 -> "Empty bar ($barText)"
        else -> {
            val plates = breakdown.platesPerSide.joinToString(" + ") { trim(it) }
            val leftover =
                breakdown.leftoverPerSide.takeIf { it > 0 }
                    ?.let { " (+${trim(it)} short)" } ?: ""
            "Plates/side: $plates$leftover ($barText)"
        }
    }
}

private fun formatMmSs(totalS: Int): String = String.format(Locale.US, "%d:%02d", totalS / 60, totalS % 60)

private fun trim(value: Double): String = if (value == Math.floor(value)) value.toInt().toString() else value.toString()
