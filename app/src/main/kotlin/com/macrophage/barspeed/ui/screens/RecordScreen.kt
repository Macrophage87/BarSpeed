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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.macrophage.barspeed.dsp.CoachingRules
import com.macrophage.barspeed.dsp.PhaseTempoTarget
import com.macrophage.barspeed.dsp.SetAnalysis
import com.macrophage.barspeed.dsp.VelocityLoss
import com.macrophage.barspeed.dsp.liftDirection
import com.macrophage.barspeed.model.BlePermissionStep
import com.macrophage.barspeed.model.BodyweightLoadDisplay
import com.macrophage.barspeed.model.ConnectionState
import com.macrophage.barspeed.model.EffortCorrectionPolicy
import com.macrophage.barspeed.model.ExerciseKind
import com.macrophage.barspeed.model.ExitAction
import com.macrophage.barspeed.model.ExitPrompt
import com.macrophage.barspeed.model.ImplementLoad
import com.macrophage.barspeed.model.LeadInPolicy
import com.macrophage.barspeed.model.Phase
import com.macrophage.barspeed.model.PlateMath
import com.macrophage.barspeed.model.RecordExitPolicy
import com.macrophage.barspeed.model.RestControl
import com.macrophage.barspeed.model.RestControlPolicy
import com.macrophage.barspeed.model.SensorAdvice
import com.macrophage.barspeed.model.SensorAdvicePolicy
import com.macrophage.barspeed.model.SetDeviationSummary
import com.macrophage.barspeed.model.SetEndControl
import com.macrophage.barspeed.model.SetEndControlPolicy
import com.macrophage.barspeed.model.SetLoadPolicy
import com.macrophage.barspeed.model.SetWriteState
import com.macrophage.barspeed.model.Stage
import com.macrophage.barspeed.model.Tempo
import com.macrophage.barspeed.model.TempoAdjustPolicy
import com.macrophage.barspeed.model.TempoDigit
import com.macrophage.barspeed.model.TimedSetEndPolicy
import com.macrophage.barspeed.model.WeightUnit
import com.macrophage.barspeed.record.PlannedSlot
import com.macrophage.barspeed.record.RecordState
import com.macrophage.barspeed.record.RecordViewModel
import com.macrophage.barspeed.record.SetFeedback
import com.macrophage.barspeed.record.SetRating
import com.macrophage.barspeed.ui.BarColors
import com.macrophage.barspeed.ui.components.ChipTone
import com.macrophage.barspeed.ui.components.ExpandableNote
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
        // Everything the lifter can change about set one now lives behind one
        // button, and the change itself is stated above it. READY renders at
        // most once per session -- startNextSet writes READY and calls
        // beginSet in the same frame -- so this is set one's only chance to
        // say anything, and until this button it could say only the load.
        DeviationLine(state, slot)
        ChangeSetButton(state, viewModel, slot, next = false)
        // Kept in place and NOT moved into the dialog: it is one line, it
        // opens a chooser of its own, and a dialog inside a dialog is a shape
        // this app has never used. It already reached READY, which is half of
        // what the owner asked for when every rack is busy on arrival; the
        // other half is the button above.
        SwitchExerciseSection(state, viewModel)
    } else {
        AdHocForm(state, viewModel)
        // Plan sets take their prep from the dialog. The ad-hoc layout keeps
        // its inline form, so it keeps its inline prep too.
        PrepAdjuster(state, viewModel)
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

/**
 * How much of a step one tap of the prep control is worth.
 *
 * Five, not one. The two cases this control exists for are a strap-up (5 -> 20)
 * and a machine that is ready instantly (5 -> 0), three taps and one tap away;
 * at single seconds the strap case is fifteen taps taken out of a rest period,
 * with chalk on the lifter's hands.
 */
private const val PREP_STEP_S = 5

/**
 * The prep before the next guided set, and the taps that change it.
 *
 * Shown only when the set coming up will actually play a prep -- a control that
 * changed nothing would be worse than no control. The predicate is
 * [LeadInPolicy.playsPrep], reached through `state.upcomingPlaysPrep`. It is the
 * same function the import gate warns an inert `prep_s` against.
 *
 * It is asked here of the slot AS DECLARED, and `beginSet` asks it of the slot
 * the lifter is about to run. Those two used to disagree: `restingState` seeded
 * `tempoInput` from the set just finished whenever the coming one declared no
 * tempo, and `advancedState` baked that in, so an exercise declaring none that
 * followed one that does ran guided with a prep and got no control here.
 * Reachable on the plan this repo publishes -- Upper A runs
 * dumbbell_bench_press (3010) into single_arm_dumbbell_row, which declares
 * none. Closed by #148: nothing now displaces the declaration except a tempo
 * the lifter set on the tempo control for THIS block, and the tempo control
 * can neither clear a tempo nor add one, so the adjustment cannot move
 * [LeadInPolicy.playsPrep]'s answer either way.
 *
 * Rendered on READY and again on the rest screen because READY is drawn at most
 * once per session: `startNextSet` writes READY and calls `beginSet` in the same
 * frame, so from set two onwards the rest screen is the only place the lifter
 * can change anything.
 *
 * The adjustment is stored against the exercise, so it holds for the rest of
 * that exercise's sets and for the same exercise next week. What it is being
 * changed from is named beside it whenever the two differ -- the plan's
 * declaration where it made one, the app's default otherwise.
 */
@Composable
private fun PrepAdjuster(state: RecordState, viewModel: RecordViewModel) {
    if (!state.upcomingPlaysPrep) return
    val slot = state.upcomingSlot
    val prepS = state.prepSecondsFor(slot)
    val plannedS = state.plannedPrepSecondsFor(slot)
    Spacer(Modifier.height(8.dp))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "Prep ${prepS}s",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                when {
                    prepS == plannedS -> "Time to get set before the set begins"
                    slot != null -> "Plan says ${plannedS}s - your change is recorded in the export"
                    else -> "Default is ${plannedS}s - your change is recorded in the export"
                },
                style = MaterialTheme.typography.bodySmall,
                color = BarColors.Sub,
            )
        }
        OutlinedButton(
            onClick = { viewModel.adjustPrep(-PREP_STEP_S) },
            enabled = prepS > LeadInPolicy.MIN_S,
        ) {
            Text("-${PREP_STEP_S}s")
        }
        OutlinedButton(
            onClick = { viewModel.adjustPrep(PREP_STEP_S) },
            enabled = prepS < LeadInPolicy.MAX_S,
        ) {
            Text("+${PREP_STEP_S}s")
        }
    }
}

/** How wide a digit's value sits, so four rows line their steppers up. */
private const val STEPPER_VALUE_DP = 32

/** One tap of a stepper, in places along that digit's own choices. */
private const val STEPPER_STEP = 1

/**
 * The tempo of the next set, as one stepper row per digit.
 *
 * DUMB. Every decision here is [TempoAdjustPolicy]'s: which word and which
 * phase each digit is for this lift, which values it may take, whether this
 * tempo can be shown as four single characters at all, what one tap produces
 * and whether a tap would move anything. This renders the answer and reports a
 * digit change.
 *
 * Shown only when the coming set already has a tempo four single-character
 * digits can show. It cannot CLEAR one and cannot ADD one: the ask was to
 * adjust a tempo, and either of those moves the set across
 * [LeadInPolicy.prepCase]'s boundary, taking the prep, the voice pacing, the
 * guide's rep count and the compliance verdict with it. A set that declares no
 * tempo, or one written with a fraction or a two-character component, gets no
 * control rather than a rewritten one.
 *
 * **Nothing here scrolls, and that is the change #154 asked for.** This was
 * four scrolling wheels whose offset was never read back, so a drag left a
 * wheel resting between two numbers with the selection unchanged: the control
 * showed one thing and the app believed another, and what the metronome played
 * was the latter. #148's stray-flick property -- only a deliberate tap changes
 * a prescription -- is not merely preserved but made structural, because there
 * is no scrollable surface left in the control for a flick to catch.
 *
 * One row per digit rather than four abreast, and that is load-bearing too: the
 * wheels fixed their caption box at 36dp because "after the eccentric" needs
 * two lines in a quarter-width column, and that constant is retired here rather
 * than carried into a container it was not measured in. A full-width row gives
 * the caption the room to be one line.
 *
 * The plan's own declaration is named beside the total whenever the two differ,
 * as [PrepAdjuster] names it for the prep.
 *
 * Not drawn on READY, so the first set of a session cannot be adjusted -- the
 * same gap the load field had before READY got one of its own. Reaching it
 * needs `beginSet`, `endSet`, the in-set ring and `upcomingPlaysPrep` to read
 * the stated tempo as well as the slot's, which is four more reads in a module
 * whose one test file cannot run a composable, for a case the ask did not name.
 * Every other set of a
 * session is reachable, because the rest screen is drawn before each of them.
 */
@Composable
private fun TempoAdjuster(state: RecordState, viewModel: RecordViewModel) {
    val slot = state.upcomingSlot ?: return
    val tempo = state.statedTempo ?: slot.tempo
    val values = TempoAdjustPolicy.wheelValues(tempo) ?: return
    // The compact form of the same tempo, and what the steppers are asked
    // about: `tempo` may be the dash form a plan wrote, which is the same
    // prescription and a different string.
    val shown = values.joinToString("")
    val planned = slot.plannedTempo
    Spacer(Modifier.height(10.dp))
    Text(
        "Tempo $shown",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
    )
    // NOT "your change is recorded in the export", which is what PrepAdjuster
    // says one control down and is FALSE here. That line is true of the prep
    // and of the load because both publish a planned/actual pair -- prep_s
    // beside plannedPrep_s, load_kg beside plannedLoad_kg -- so a reader can
    // see the deviation afterwards. Tempo has no such pair: session.json
    // carries one field, tempoPrescribed, and it holds what RAN, so an
    // adjusted set is indistinguishable from one the plan prescribed that way.
    // #151. What is said instead is what the lifter can act on: how far this
    // change reaches.
    Text(
        when {
            planned == null || tempo == planned -> "Seconds per phase for the coming set"
            else -> "Plan says $planned - the rest of this exercise runs $tempo unless the plan changes it"
        },
        style = MaterialTheme.typography.bodySmall,
        color = BarColors.Sub,
    )
    TempoAdjustPolicy.digits(slot.exercise.concentricUp, slot.exercise.horizontal)
        .forEachIndexed { index, digit ->
            TempoDigitStepper(digit, shown, values[index]) { value ->
                viewModel.adjustTempoDigit(digit.position, value)
            }
        }
}

/**
 * One digit's stepper: its word, what it is for this lift, its value, and a
 * button either side.
 *
 * The same Row as [PrepAdjuster] one control down -- a weighted column holding
 * a bold title and a caption, then the controls -- so the two adjusters on this
 * screen are one idiom and not two.
 *
 * Both buttons ask [TempoAdjustPolicy] what a tap would produce and whether it
 * would move anything; nothing here knows that "X" is the top of the up stroke
 * or that a stroke floors at one second. A disabled button rather than a tap
 * that does nothing is the whole point of asking twice.
 *
 * [onPick] hands back the digit's new VALUE, not a tempo, because
 * `adjustTempoDigit` routes it through `TempoAdjustPolicy.withDigit` -- which
 * refuses anything the control could not have drawn. The refusal stays in one
 * place whichever control is on screen.
 */
@Composable
private fun TempoDigitStepper(digit: TempoDigit, tempo: String, selected: String, onPick: (String) -> Unit) {
    Spacer(Modifier.height(4.dp))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                digit.label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                digit.caption,
                style = MaterialTheme.typography.bodySmall,
                color = BarColors.Sub,
            )
        }
        OutlinedButton(
            onClick = {
                TempoAdjustPolicy.steppedValue(tempo, digit.position, -STEPPER_STEP)?.let(onPick)
            },
            enabled = TempoAdjustPolicy.canStep(tempo, digit.position, -STEPPER_STEP),
        ) {
            Text("-")
        }
        Text(
            selected,
            Modifier.width(STEPPER_VALUE_DP.dp),
            style = MaterialTheme.typography.titleMedium,
            color = BarColors.Volt,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        OutlinedButton(
            onClick = {
                TempoAdjustPolicy.steppedValue(tempo, digit.position, STEPPER_STEP)?.let(onPick)
            },
            enabled = TempoAdjustPolicy.canStep(tempo, digit.position, STEPPER_STEP),
        ) {
            Text("+")
        }
    }
}

/**
 * What the lifter has changed about the coming set, under the card that still
 * states the plan.
 *
 * DUMB: every word of it is [SetDeviationSummary.parts], which is pinned in
 * :core:model. This draws the answer and draws nothing when the answer is
 * empty -- absence is absence, and a line reading "no changes" on every rest
 * screen of every session is a line that stops being read before the one rest
 * period where it matters.
 *
 * This is the compensation that makes [ChangeSetButton] safe. The "Up next"
 * card goes on stating the PLAN's load and tempo, deliberately, and the load
 * box that used to sit under it -- where the lifter reconciled the two -- is
 * now behind a tap. Without this line, "Up next — 90 kg" would sit above a set
 * that records 100 with nothing on screen saying so.
 */
@Composable
private fun DeviationLine(state: RecordState, slot: PlannedSlot) {
    val parts =
        SetDeviationSummary.parts(
            kind = slot.exercise.kind,
            bodyweight = slot.exercise.bodyweight,
            unit = state.weightUnit,
            plannedLoadKg = slot.plannedLoadKg,
            statedLoadKg = state.statedLoadKg,
            plannedReps = if (slot.isTimed) null else slot.reps,
            statedReps = if (slot.isTimed) null else state.repsInput.toIntOrNull(),
            plannedDurationS = if (slot.isTimed) slot.durationS else null,
            statedDurationS = if (slot.isTimed) state.durationInput.toIntOrNull() else null,
            plannedTempo = slot.plannedTempo,
            tempo = state.statedTempo ?: slot.tempo,
            plannedPrepS = state.plannedPrepSecondsFor(slot),
            prepS = state.prepSecondsFor(slot),
        )
    if (parts.isEmpty()) return
    Spacer(Modifier.height(4.dp))
    Text(
        "Your changes: ${parts.joinToString(" · ")}",
        style = MaterialTheme.typography.bodySmall,
        color = BarColors.Volt,
    )
}

/**
 * The one control that reaches every upcoming-set change, and the dialog it
 * opens.
 *
 * #152: the load box, the reps box, four tempo rows and the prep row sat
 * between the countdown and START, and the owner's report is that the sum
 * crowds the screen out of usefulness. They are all here now, behind one
 * outlined button.
 *
 * Drawn on the rest screen AND on READY. READY renders at most once per
 * session and used to offer only the load box, so set one was the one set of a
 * plan session whose reps, tempo and prep could not be touched. The owner's
 * case for closing that is the gym itself: "What if someone is using all the
 * squat racks when you walk in." Set one is exactly when rerouting matters,
 * and it is where the app was least flexible. `RecordViewModel.beginSet`'s
 * first statement is what makes the three new controls actually reach that
 * set -- see `startedFromReadyState`, and note that until it existed they
 * would have accepted the tap and changed nothing.
 *
 * Outlined, never filled: START is the only filled button on either screen,
 * and two full-width filled buttons is how a thumb picks the wrong one. A
 * mis-tap here opens a dialog and costs one tap to close; a mis-tap on START
 * begins a set that can only be ended by recording it.
 */
@Composable
private fun ChangeSetButton(state: RecordState, viewModel: RecordViewModel, slot: PlannedSlot, next: Boolean) {
    var open by remember(state.queueIndex, state.stage) { mutableStateOf(false) }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
        Text(if (next) "CHANGE NEXT SET" else "CHANGE THIS SET")
    }
    if (open) {
        ChangeSetDialog(state, viewModel, slot, next) { open = false }
    }
}

/**
 * Every change the coming set can take, in one scrolling dialog.
 *
 * A LENS, NOT A FORM. Every control binds to the same ViewModel entry point it
 * bound to when it sat on the screen -- `updateLoadInput`, `updateRepsInput`,
 * `updateDurationInput`, `adjustTempoDigit`, `adjustPrep` -- and this holds no
 * local copy of any of them. So there is one button, "Done", and no Cancel:
 * the scrim, the system back gesture and Done are the same action because
 * nothing is buffered for either of them to discard.
 *
 * That is a decision and not an omission. A Cancel here would promise a
 * rollback nothing can perform -- `adjustPrep` writes through to the DataStore
 * on `appScope` and is read back through a flow, and the load and tempo
 * statements feed carry rules whose semantics #152 says must not change -- and
 * a Cancel that silently discarded instead would be the worse failure: the
 * lifter types 100, dismisses by scrim, and lifts 90 believing they changed
 * it. The consequence, stated rather than hidden: dismissing by scrim is
 * indistinguishable from Done, which is correct precisely because nothing is
 * thrown away.
 *
 * The caption stays attached to the load and reps row rather than being
 * promoted to the dialog's subtitle, and that is the one thing here it would
 * be easy to get wrong. "deviations are recorded" is a claim about the export,
 * and it is TRUE of load, reps and prep -- each publishes a planned/actual
 * pair -- and FALSE of tempo, which publishes one field holding what ran
 * (#151). The tempo control carries its own caption saying something different
 * for that reason; a subtitle would silently extend the true claim over the
 * control it is false for.
 *
 * Plan sets only. The ad-hoc rest and READY layouts keep their inline form:
 * `AdHocForm` carries an exercise grid, a unit toggle and side chips, which is
 * a different problem, and the owner's report is about the plan path.
 */
@Composable
private fun ChangeSetDialog(
    state: RecordState,
    viewModel: RecordViewModel,
    slot: PlannedSlot,
    next: Boolean,
    onDone: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(if (next) "Change next set" else "Change this set") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    if (next) {
                        "Adjust next set (deviations are recorded)"
                    } else {
                        "Adjust this set (deviations are recorded)"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = BarColors.Sub,
                )
                // The load box's floated label wraps to two lines at font
                // scale 2 in a 360dp dialog and the second line is drawn above
                // the box, into the caption's line. Shortening the label was
                // not enough on its own to separate them.
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = state.loadInput,
                        onValueChange = viewModel::updateLoadInput,
                        label = { Text(loadFieldLabel(slot, state.weightUnit)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                    if (slot.isTimed) {
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
                PerImplementEcho(state, slot)
                LoadSignHint(slot)
                TempoAdjuster(state, viewModel)
                PrepAdjuster(state, viewModel)
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text("Done") } },
    )
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

/**
 * "Total load (lb)" once an exercise declares more than one implement, so the
 * box says what it takes before anything is typed into it.
 *
 * The box goes on taking the TOTAL at every site, unchanged. That is the whole
 * reason this feature performs no multiplication anywhere: if the field's
 * meaning flipped to per-implement, every read and every re-seed of
 * `loadInput` would carry a divide-or-multiply obligation forever, in the one
 * module with no test source set.
 *
 * DUMB. The wording is [BodyweightLoadDisplay.fieldLabel]'s, which is in a
 * module where a test can read it; this reads the slot and asks.
 */
private fun loadFieldLabel(slot: PlannedSlot?, unit: WeightUnit): String = BodyweightLoadDisplay.fieldLabel(
    bodyweight = slot?.exercise?.bodyweight == true,
    implementCount = slot?.implementCount,
    unit = unit,
)

/**
 * "= 2 × 45 lb each" under the load box, recomputed on every keystroke.
 *
 * DERIVED and not typeable, so no per-implement figure can reach
 * `statedLoadKg`: this reads the box, it is not a second box. It exists so the
 * arithmetic the lifter is doing in their head is visible BEFORE the set
 * rather than discoverable afterwards in an export.
 *
 * A plain Text rather than the field's `supportingText` slot, which is used
 * nowhere else in this app -- a rendering that ships tonight should not depend
 * on an API this codebase has never exercised.
 */
@Composable
private fun PerImplementEcho(state: RecordState, slot: PlannedSlot?) {
    val typedAddedKg = state.weightUnit.parseToKg(state.loadInput)
    val split = ImplementLoad.decomposition(typedAddedKg, slot?.implementCount, state.weightUnit)
    if (split != null) {
        Text(
            "= $split each",
            style = MaterialTheme.typography.bodySmall,
            color = BarColors.Sub,
        )
    }
}

/**
 * Which way the sign runs, under a load box that takes one.
 *
 * DUMB, and drawn on exactly the sets whose box accepts a negative:
 * [BodyweightLoadDisplay.fieldHint] answers null for loaded work, and a null
 * here draws nothing rather than an empty line holding space open. The
 * population is every `bodyweight: true` exercise with no subset -- the same
 * population `PlanFile.validate` already passes `allowNegativeLoad` for -- so
 * a dead hang can be assisted and a push-up can take a plate whether or not
 * the plan declared a load for them. #160.
 */
@Composable
private fun LoadSignHint(slot: PlannedSlot?) {
    val hint = BodyweightLoadDisplay.fieldHint(slot?.exercise?.bodyweight == true) ?: return
    Text(hint, style = MaterialTheme.typography.bodySmall, color = BarColors.Sub)
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
    val carry = slot?.exercise?.kind == ExerciseKind.CARRY
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        InSetHeader(state, slot)
        Spacer(Modifier.height(10.dp))
        if (state.timedPrepRunning) TimedPrepRing(state, carry) else TimedClockRing(state, carry)
        Spacer(Modifier.height(14.dp))
        Text(
            "Elapsed ${formatMmSs(state.setElapsedS)}",
            style = MaterialTheme.typography.titleMedium,
            color = BarColors.Sub,
        )
        Spacer(Modifier.height(24.dp))
        EndSetControl(state, viewModel)
    }
}

/**
 * The prep before a hold or a carry: the seconds left before the clock starts,
 * on the ring whether or not the prep is spoken.
 *
 * A ring of its own rather than a branch inside the one after it, because the
 * two count opposite things -- this one down to the start, that one up from it
 * -- and one expression drawing both is how a set that has not begun ends up
 * looking like one that has.
 *
 * The numbers come from the same three state fields the guided cadence pushes:
 * they carry whatever a running voice guide is saying, and a prep before a hold
 * is one.
 */
@Composable
private fun TimedPrepRing(state: RecordState, carry: Boolean) {
    val total = state.guidedPhaseTotal.coerceAtLeast(1)
    ProgressRing(progress = 1f - state.guidedCountdown / total.toFloat(), color = BarColors.Sub) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "GET READY",
                style = MaterialTheme.typography.labelMedium,
                color = BarColors.Sub,
                letterSpacing = 2.sp,
            )
            Text("${state.guidedCountdown}", style = MaterialTheme.typography.displayLarge)
            Text(
                if (carry) "until the carry starts" else "until the hold starts",
                style = MaterialTheme.typography.bodySmall,
                color = BarColors.Sub,
            )
        }
    }
}

/**
 * The hold itself, counting down to the target the set now ends at.
 *
 * "and then past it" until #168, when the clock ran on and the screen called
 * the overrun bonus time. It is not bonus time any more and saying so would be
 * false: the set ends at the target and records the target, so seconds past it
 * are not measured and not scored. A genuine overage is stated afterwards on
 * the rest screen.
 *
 * A negative remainder is still handled rather than assumed away. The tick
 * loop runs on `delay(1_000)` in a process Android may pause, so a skipped
 * second can push the displayed remainder below zero for the one frame before
 * `endsNow` fires on the following tick. It shows zero for that frame instead
 * of a negative, and no longer changes colour or wording -- the set is about
 * to end, which is not a state worth a label of its own.
 */
@Composable
private fun TimedClockRing(state: RecordState, carry: Boolean) {
    val targetS = state.currentTimedTargetS
    val elapsed = state.setElapsedS
    val remaining = TimedSetEndPolicy.remainingS(elapsed, targetS)
    val ringColor = BarColors.Volt
    ProgressRing(
        progress = targetS?.let { (elapsed / it.toFloat()) } ?: 0f,
        color = ringColor,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (carry) "CARRY" else "HOLD",
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
                if (targetS == null) "elapsed" else "of ${targetS}s target",
                style = MaterialTheme.typography.bodySmall,
                color = BarColors.Sub,
            )
        }
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
            // The canonical total keeps its position and the split follows it
            // in brackets, never instead of it. `loadKg` above is
            // SetLoadPolicy.resolve's answer, which is the ADDED load -- the
            // one figure here that may be divided.
            if (state.currentExercise.bodyweight) {
                // The same notation the "Up next" card used a moment ago, so
                // the number the lifter checked against the bar is the number
                // still on screen under it. #160.
                val split = ImplementLoad.decomposition(loadKg, slot?.implementCount, state.weightUnit)
                BodyweightLoadDisplay.label(loadKg, state.weightUnit) + (split?.let { " ($it)" } ?: "")
            } else {
                loadKg.takeIf { it > 0 }?.let { added ->
                    val split = ImplementLoad.decomposition(added, slot?.implementCount, state.weightUnit)
                    state.weightUnit.format(added) + (split?.let { " ($it)" } ?: "")
                } ?: "bodyweight".takeIf { state.currentIsTimed }
            },
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
 * The effort grid is drawn either way, with END SET EARLY under it on a set
 * that came up short. Rating an abandoned set does not log it as a good one:
 * the shortfall is derived at the write from the rep or second count and lands
 * as `failed` whether or not a tile was tapped, so "solid, had more in me"
 * three reps into a five-rep set now records an effort AND a failure, which is
 * the pair a reader of the export needs and could not get. Which controls
 * belong to which case is [SetEndControlPolicy]'s decision, in a module with
 * tests; this draws what it is told.
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
        SetWriteState.NONE -> {
            val controls = SetEndControlPolicy.controls(state.setTargetMet)
            if (SetEndControl.EFFORT_GRID in controls) {
                EndSetRpeGrid(state, viewModel, failedTile = SetEndControl.FAILED_TILE in controls)
            }
            if (SetEndControl.END_UNRATED in controls) EndSetEarlyButton(viewModel)
        }
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
 * Ends the set with NO rating attached. It is the SKIP beside the effort grid
 * rather than the only way out: a lifter walking away mid-set must not be made
 * to rate the set before it will end, so absence stays one tap away.
 *
 * It does not tap the failure, and the grid above it withholds the "failed the
 * set" tile for the same reason. A shortfall is DERIVED at the write, so it can
 * be re-derived -- correcting a miscounted rep total on the rest screen clears
 * it. A TAPPED failure is one no later REP CORRECTION can clear: `correctReps`
 * re-derives the derived half and leaves the tapped half standing. Re-rating
 * the set does overwrite it, so it is not permanent -- it is out of reach of
 * the one repair that fits the mistake. A lifter who did all five reps but only
 * tapped "+1 REP" three times reaches exactly this control, and a tapped
 * verdict here would defeat correcting the count, which is what they would
 * actually go and do.
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
    SectionCaption("Ends the set with no rating · a set that came up short logs as failed")
}

/**
 * The effort grid IS the end-set control. Tapping how the set felt ends the set
 * and logs the rating in one action, while the set is still fresh -- there is no
 * separate page between lifting and resting.
 *
 * [failedTile] draws the lifter's own "failed the set" verdict. It is false on a
 * set that came up short, where the failure is already derived: the tile would
 * store a TAPPED one, which no later rep correction can clear, and it would sit
 * on the path a miscounted rep total arrives on.
 */
@Composable
private fun EndSetRpeGrid(state: RecordState, viewModel: RecordViewModel, failedTile: Boolean) {
    val options =
        rpeOptions(timed = state.currentIsTimed, explosive = currentKind(state) == ExerciseKind.EXPLOSIVE)
            .filter { failedTile || !it.failed }
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
 * for (#153), and [RestHeader] now puts them in the same row: [StartNextSetButton]
 * sits beside the ring rather than at the foot of [NextSetBlock], because they
 * are the only two facts a lifter needs before the phone comes off the floor.
 * Everything about the set that had just finished -- the effort line, the
 * rep-correction row and a rep-quality card carrying a 64dp chart -- still sits
 * below the next-set block, in [LastSetDetail]. The field report for v0.1.37 is
 * "try to get the rest dialog to fit on one page without scrolling"; that
 * report, not this arithmetic, is the evidence it did not fit, because `:app`
 * has no reachable test seam for a Compose layout and nothing here has been
 * measured on a device -- see the bench-harness evidence in the commit that
 * moved START.
 *
 * The screen still scrolls: a per-rep chart and a rest countdown do not both
 * fit above the fold on a phone. What is below the fold is the detail rather
 * than the control.
 *
 * [SessionCloseControls] does NOT follow [StartNextSetButton] to the top. It
 * stays after [NextSetBlock], where it has sat since the next-set block was
 * consolidated (#152), because in `SessionCloseState.FAILED` it draws THIS
 * SESSION DID NOT FINISH and the retry, and putting a session-ending control
 * directly beside a repeatedly-tapped START would recreate the stacked-target
 * hazard #137 removed elsewhere on this screen. Keeping it separated by the
 * whole next-set block, rather than adjacent to START, is the point.
 */
@Composable
private fun RestingStage(state: RecordState, viewModel: RecordViewModel) {
    RestHeader(state, viewModel)
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
        // The change first, then the way into it. Never the other way round:
        // the deviation has to be readable without a tap, because the card
        // above still states the plan's numbers and the box that used to
        // reconcile them is now behind the button.
        DeviationLine(state, next)
        ChangeSetButton(state, viewModel, next, next = true)
        SwitchExerciseSection(state, viewModel)
    } else if (state.adHoc) {
        AdHocForm(state, viewModel)
        PrepAdjuster(state, viewModel)
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
    state.lastFeedback?.let { HoldCorrectionRow(it, viewModel) }
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
 *
 * `heightIn(min = ...)`, not a fixed `height`, since #153 (issue #153): the
 * button now sits in the narrow column beside the 110dp ring rather than the
 * full screen width, so "START NEXT SET" wraps to two lines at font scale 2.
 * A fixed 52dp height clipped the second line; a minimum lets the button grow
 * to fit its own text while keeping the 52dp floor as the tap target.
 */
@Composable
private fun StartNextSetButton(state: RecordState, viewModel: RecordViewModel) {
    if (RestControl.START_NEXT_SET !in RestControlPolicy.controls(state.sessionClose)) return
    Button(
        onClick = viewModel::startNextSet,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
    ) { Text("START NEXT SET", textAlign = TextAlign.Center) }
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

/**
 * Correction grid on the rest screen, for a mistapped effort rating.
 *
 * What is pre-lit is [EffortCorrectionPolicy]'s decision, not this file's. The
 * rule is a decision about attribution -- whose verdict a lit tile claims to be
 * -- and it lives in `:core:model` where a test runs on it every push; `:app`
 * has one test file and none of it reaches a Compose screen.
 */
@Composable
private fun RpeSelector(state: RecordState, viewModel: RecordViewModel, onPicked: () -> Unit) {
    val feedback = state.lastFeedback
    val options =
        rpeOptions(
            timed = feedback?.actualDurationS != null,
            explosive = feedback?.explosive == true,
        )
    // lastSetFailed is the OR of both facts, so the derived one is recovered by
    // subtracting the tap. Where BOTH are true this hands the policy false for
    // derivedFailed, which is a value the policy cannot act on differently: its
    // only use of the argument is `derivedFailed && !tappedFailed`, false in
    // that case either way.
    val selection =
        EffortCorrectionPolicy.selection(
            rpe = state.lastSetRpe,
            warmup = state.lastSetWarmup,
            tappedFailed = state.lastSetTappedFailed,
            derivedFailed = state.lastSetFailed && !state.lastSetTappedFailed,
        )
    SectionCaption("Change the effort logged for that set")
    if (selection.derivedShortfall) {
        // Without this the grid can pre-light nothing at all, which reads as
        // the app having lost the rating. It is true of the code beneath it:
        // SetRatingTracker.rate ORs the derived flag back in on every
        // correction, so no tap here can clear the shortfall.
        Spacer(Modifier.height(4.dp))
        Text(
            "This set is already recorded as short of target. Rating it does not change that.",
            style = MaterialTheme.typography.bodySmall,
            color = BarColors.Sub,
        )
    }
    Spacer(Modifier.height(6.dp))
    options.chunked(2).forEach { row ->
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        ) {
            row.forEach { option ->
                // One tile per fact, and EffortSelection guarantees at most one
                // fact is set -- otherwise two tiles light up at once and say
                // two contradictory things about one set.
                val selected =
                    when {
                        option.warmup -> selection.warmup
                        option.failed -> selection.failed
                        else -> selection.rpe != null && option.rpe == selection.rpe
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

/**
 * Rest-screen reminder of the effort recorded for the finished set.
 *
 * When an RPE or warm-up tile was tapped, its own label is shown, with
 * "short of target" appended if the set also fell short by rep count or
 * duration -- both are real, distinct facts and neither replaces the other.
 *
 * When neither was tapped, [RecordState.lastSetRpe] is null and
 * [RecordState.lastSetWarmup] is false whether the lifter tapped the grid's
 * own "Failed the set" tile or was offered the grid and declined it --
 * [EndSetEarlyButton] is the skip beside the grid on a set that came up
 * short, and ends it with no rating, landing on exactly the same two flags.
 * [RecordState.lastSetTappedFailed] now tells a tapped failure from a
 * derived one. This line still prints the shared word "Failed" for both,
 * by #139's deliberate choice, not because the state is incapable.
 *
 * Issue #137 makes the declined case the common one and the tapped-tile case
 * rarer, because the failed tile is no longer drawn on a short set at all. This
 * line gets more conservative, not less.
 */
@Composable
private fun LoggedEffortLine(state: RecordState, onChange: () -> Unit) {
    val feedback = state.lastFeedback ?: return
    // No early return for a set carrying no rating, since #168. A hold now
    // ends on its clock rather than on a tap of the effort grid, so every
    // timed set that meets its target arrives here unrated -- and this line is
    // what carries the "Change" button that is the only way into the grid.
    // Returning early took that button with it, so the lifter saw no effort
    // and had nothing to tap to supply one. RPE is captured once, at set end,
    // and no reprocessing of the stream rebuilds how hard a hold felt.
    val options =
        rpeOptions(timed = feedback.actualDurationS != null, explosive = feedback.explosive)
    val tapped =
        options.firstOrNull {
            if (state.lastSetWarmup) it.warmup else !it.warmup && !it.failed && it.rpe == state.lastSetRpe
        }?.description
    // The wording is EffortCorrectionPolicy's, including the named absence for
    // a set with nothing logged; `:app` cannot test a composable, so the
    // decision lives one module over where every case is a literal in a test.
    val text = EffortCorrectionPolicy.lineText(tapped, state.lastSetFailed)
    val unrated = text == EffortCorrectionPolicy.NOT_RATED
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        SectionCaption(
            "Effort · $text",
            color =
            when {
                state.lastSetFailed -> BarColors.Red
                // Amber rather than Volt: nothing is wrong, but there is
                // something for the lifter to do, and the rest period is the
                // only window in which it can be done.
                unrated -> BarColors.Amber
                else -> BarColors.Volt
            },
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onChange) { Text(if (unrated) "Rate" else "Change", color = BarColors.Sub) }
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

/**
 * Held it longer than the app stopped you at? State it here (#168).
 *
 * A hold or a carry now ends when its clock reaches the prescription, so the
 * recorded figure is the announced one and the phone-retrieval walk is no
 * longer inside it. The rare genuine overage -- the lifter deliberately
 * carrying on past the word -- is stated on the rest screen and nowhere else,
 * because the owner does not look at the phone mid-set: "There are rare
 * instances I even look at the phone mid set." A mid-set control would be
 * exercised never, and the rest screen is already where every other post-set
 * correction lives.
 *
 * The mirror of [RepCorrectionRow], and exactly one of the two is ever drawn:
 * that one returns for a timed set, this one for anything else. The step is
 * [TimedSetEndPolicy.CORRECTION_STEP_S] rather than one second, because what
 * is being added is a walk back to the phone.
 *
 * The corrected figure is amber and labelled as corrected, the same way a
 * corrected rep count is: what is stored is no longer what was measured, and
 * the screen has to say which it is showing. What the EXPORT cannot say is the
 * same thing -- `set_records` has `repsManual` for reps and no such column for
 * seconds -- and that gap is named in the commit body rather than papered
 * over here.
 */
@Composable
private fun HoldCorrectionRow(feedback: SetFeedback, viewModel: RecordViewModel) {
    val seconds = feedback.effectiveDurationS ?: return
    val corrected = feedback.durationOverrideS != null
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            if (corrected) "Held (corrected)" else "Held",
            style = MaterialTheme.typography.bodySmall,
            color = BarColors.Sub,
        )
        TextButton(onClick = { viewModel.addLastSetSeconds(-TimedSetEndPolicy.CORRECTION_STEP_S) }) {
            Text("−", style = MaterialTheme.typography.titleMedium)
        }
        Text(
            "${seconds}s",
            style = MaterialTheme.typography.titleMedium,
            color = if (corrected) BarColors.Amber else BarColors.Text,
        )
        TextButton(onClick = { viewModel.addLastSetSeconds(TimedSetEndPolicy.CORRECTION_STEP_S) }) {
            Text("+", style = MaterialTheme.typography.titleMedium)
        }
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
private fun RestHeader(state: RecordState, viewModel: RecordViewModel) {
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
        Column(Modifier.weight(1f)) {
            // The two things a resting lifter acts on -- how long is left, and
            // go -- are the first things on screen. StartNextSetButton is
            // unconditional here rather than gated on lastFeedback below: the
            // button's own visibility already comes from RestControlPolicy, so
            // nothing extra is needed to keep it hidden in SessionClose states
            // where starting is not allowed.
            StartNextSetButton(state, viewModel)
            Spacer(Modifier.height(6.dp))
            SectionCaption("Last set")
            state.lastFeedback?.let { feedback ->
                val loadText =
                    feedback.loadKg.takeIf { it > 0 }?.let { state.weightUnit.format(it) } ?: "BW"
                // addedKg, NEVER loadKg. feedback.loadKg is
                // SetLoadPolicy.totalKg -- the lifter's own mass included on
                // body-weight work -- so halving it would read "2 x 50 kg" for
                // a 20 kg weighted dip at 80 kg body weight. The total on
                // screen stays loadKg; only the split comes off addedKg.
                val split =
                    ImplementLoad.decomposition(feedback.addedKg, feedback.implementCount, state.weightUnit)
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
                        // "8 × 2 × 40 lb" would put two different meanings on
                        // one glyph, so the rep joiner becomes "reps @" when
                        // and only when a split renders. That is the shape the
                        // timed branch directly above already uses, rather
                        // than a new punctuation invented for this case; with
                        // no split the line is byte-identical to before.
                        feedback.actualDurationS?.let { "$name ${it}s @ $loadText" }
                            ?: split?.let { "$name ${feedback.effectiveReps} reps @ $loadText ($it)" }
                            ?: "$name ${feedback.effectiveReps} × $loadText",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
    }
    // Moved out of the column beside the ring so the pills get the whole
    // screen width rather than what is left after the ring and the button's
    // column -- see FeedbackChips' own KDoc for the arithmetic this changes.
    state.lastFeedback?.let { feedback ->
        Spacer(Modifier.height(6.dp))
        FeedbackChips(feedback, state.hrBpm, state.hrvMs)
    }
}

/**
 * The verdict pills for the set just finished.
 *
 * A [FlowRow], because the pill count is variable and the width they are given
 * is not. Five can be emitted at once -- held, tempo, velocity loss, heart rate,
 * HRV. [RestHeader] draws these full width, below the ring/START row rather than
 * inside its column (#153): the screen width less the 16dp screen padding either
 * side, with no ring or button column to subtract. On a 411dp-wide phone that
 * arithmetic gives 379dp and on a 360dp one 328dp, up from the 253dp/202dp this
 * had when the pills shared the column beside the ring, and a plain `Row` does
 * not wrap: children that do not fit are placed past its own width rather than
 * moved to a second line. The field report for v0.1.37 is "the heart rate
 * marker doesn't fit on the page and runs to the side"; heart rate is the
 * fourth pill of the five, and HRV the fifth.
 *
 * Wrapping rather than widening, because no width is safe: the pill text is
 * data-dependent (a three-digit heart rate, a two-digit rep count either side of
 * the tempo ratio) and nothing bounds it. A third pill row, or a title long
 * enough to wrap in the column above, only grows the header -- it does not
 * narrow the chips, which now have the whole row to themselves.
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
        // Asked of the reps rather than read off analysis.velocityLossPct, so
        // this chip and the exported velocityLoss_pct are answered by one
        // function and cannot drift apart.
        when (val loss = VelocityLoss.of(analysis.reps)) {
            is VelocityLoss.Measured ->
                VerdictChip(
                    "−${trim(loss.pct)}% vel",
                    when {
                        loss.pct >= DEFAULT_VELOCITY_LOSS_STOP_PCT -> ChipTone.BAD
                        loss.pct >= VEL_LOSS_OK_PCT -> ChipTone.WARN
                        else -> ChipTone.OK
                    },
                )
            // Drawn, not dropped. A chip that simply vanishes is
            // indistinguishable from a set with fewer than two reps, and this
            // is a set the sensor DID resolve reps for: the lifter is being
            // told the figure is unavailable, not that nothing was measured.
            VelocityLoss.TerminalRepIsFastest -> VerdictChip("Vel loss n/a", ChipTone.NEUTRAL)
            VelocityLoss.NotEnoughReps, VelocityLoss.NoReference -> Unit
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
    VelocityLoss.of(analysis.reps).pctOrNull?.let {
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
                    // On body-weight work the slot's load is what was ADDED, so
                    // it is said as an addition to the lifter rather than as a
                    // weight on its own -- and the assisted case, which the
                    // takeIf below drops on the floor, is said at all. #160.
                    if (slot.exercise.bodyweight) {
                        BodyweightLoadDisplay.label(slot.loadKg, unit)
                    } else {
                        slot.loadKg?.takeIf { it > 0 }?.let { unit.format(it) }
                            ?: "bodyweight".takeIf { slot.isTimed }
                    },
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
            // The cue the plan wrote, split by whether the lifter has to touch
            // the phone to read it. The split itself is decided in :core:model
            // (PlanNoteDisplay); this draws what it decided, and draws a
            // labelled control rather than an ellipsis whenever anything is
            // hidden. Nothing auto-expands.
            if (slot.exerciseNotes != null || slot.exerciseNotesBehindTap != null) {
                Spacer(Modifier.height(4.dp))
                ExpandableNote(slot.exerciseNotes, slot.exerciseNotesBehindTap, BarColors.Amber)
            }
            // The plate line is an INSTRUCTION, not a description: the title
            // above keeps stating what the plan asked for, but telling the
            // lifter to load 100 while they have said 90 would be telling them
            // to do the wrong thing. This also opens a case that could not
            // arise before -- a barbell slot the plan gave no load for now
            // draws a line once the lifter states one -- which is wanted:
            // there was nothing to compute from before, and there is now.
            //
            // "Pick up" REPLACES the plate line rather than sitting beside it,
            // and a DECLARED count beats an INFERRED bar, which is the
            // precedence used everywhere else here. You cannot load plates per
            // side onto two dumbbells, and usesBarbell is a guess from the
            // exercise id while the count is not guessed at all.
            //
            // Both lines read the same load, which is the ADDED load: the
            // slot's own number, or what the lifter has stated in its place.
            // Never a body-weight-inclusive total -- see ImplementLoad.
            //
            // No plate line on body-weight work, whatever usesBarbell says.
            // That flag is inferred from the exercise id where the plan does
            // not declare it, and "pull_up" carries none of the non-barbell
            // hints, so a weighted pull-up currently draws "Plates/side: 5
            // (20 kg bar)" -- an instruction to load a bar that is not in the
            // movement. The "Pick up" line survives, because a plate held on a
            // dip belt or a pair of dumbbells on a weighted dip is a real
            // thing to pick up. #160.
            val instructionKg = (plateLoadKgOverride ?: slot.loadKg)?.takeIf { it > 0 }
            val instruction =
                if (ImplementLoad.count(slot.implementCount) > 1) {
                    ImplementLoad.decomposition(instructionKg, slot.implementCount, unit)
                        ?.let { "Pick up: $it" }
                } else if (slot.exercise.usesBarbell && !slot.exercise.bodyweight) {
                    instructionKg?.let { plateLine(it, unit) }
                } else {
                    null
                }
            instruction?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = BarColors.Blue)
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
