package com.macrophage.barspeed.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.macrophage.barspeed.dsp.CoachingRules
import com.macrophage.barspeed.dsp.PhaseTempoTarget
import com.macrophage.barspeed.dsp.SetAnalysis
import com.macrophage.barspeed.dsp.VelocityLoss
import com.macrophage.barspeed.dsp.liftDirection
import com.macrophage.barspeed.model.AddSetControl
import com.macrophage.barspeed.model.ArmedSilencePolicy
import com.macrophage.barspeed.model.BlePermissionStep
import com.macrophage.barspeed.model.BodyWeightPromptPolicy
import com.macrophage.barspeed.model.BodyweightLoadDisplay
import com.macrophage.barspeed.model.ConnectionState
import com.macrophage.barspeed.model.DualSensorSetup
import com.macrophage.barspeed.model.EffortClaim
import com.macrophage.barspeed.model.EffortCorrectionPolicy
import com.macrophage.barspeed.model.EffortScale
import com.macrophage.barspeed.model.ExerciseKind
import com.macrophage.barspeed.model.ExitAction
import com.macrophage.barspeed.model.ExitPrompt
import com.macrophage.barspeed.model.ImplementLoad
import com.macrophage.barspeed.model.LeadInPolicy
import com.macrophage.barspeed.model.Phase
import com.macrophage.barspeed.model.PlanSessionDef
import com.macrophage.barspeed.model.PlanValueCaption
import com.macrophage.barspeed.model.PlateMath
import com.macrophage.barspeed.model.PreviewBlock
import com.macrophage.barspeed.model.RecordExitPolicy
import com.macrophage.barspeed.model.RemoveSetControl
import com.macrophage.barspeed.model.RestControl
import com.macrophage.barspeed.model.RestControlPolicy
import com.macrophage.barspeed.model.RestControls
import com.macrophage.barspeed.model.SensorAdvice
import com.macrophage.barspeed.model.SensorAdvicePolicy
import com.macrophage.barspeed.model.SensorCapturePolicy
import com.macrophage.barspeed.model.SensorRoster
import com.macrophage.barspeed.model.SessionPreview
import com.macrophage.barspeed.model.SessionPreviewPolicy
import com.macrophage.barspeed.model.SessionRpe
import com.macrophage.barspeed.model.SetCardValue
import com.macrophage.barspeed.model.SetCardValues
import com.macrophage.barspeed.model.SetEndControl
import com.macrophage.barspeed.model.SetEndControlPolicy
import com.macrophage.barspeed.model.SetLimiter
import com.macrophage.barspeed.model.SetLimiterGroup
import com.macrophage.barspeed.model.SetLimiterPagePlacement
import com.macrophage.barspeed.model.SetLimiterPolicy
import com.macrophage.barspeed.model.SetLimiterScale
import com.macrophage.barspeed.model.SetLimiterTile
import com.macrophage.barspeed.model.SetLoadPolicy
import com.macrophage.barspeed.model.SetRepsPolicy
import com.macrophage.barspeed.model.SetWriteState
import com.macrophage.barspeed.model.SideChoicePolicy
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
import com.macrophage.barspeed.record.previewSet
import com.macrophage.barspeed.record.soleSilenceOver
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
import com.macrophage.barspeed.ui.components.rememberArmedDelivery
import com.macrophage.barspeed.ui.components.velocityLossColor
import kotlinx.coroutines.delay
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
            //
            // UNRATED, stated at the call site rather than defaulted (#159).
            // This route is a lifter pressing Back and being asked what to do
            // about an open session; interposing a rating panel would leave
            // them on the record screen with the dialog gone and the session
            // still open, which is not what the button they tapped promised.
            // So it closes immediately and records no rating, and the absence
            // is a real absence rather than a midpoint. Whether the Back route
            // is common enough that it should ask too is a [Field] question,
            // not something this file can answer.
            ExitAction.FINISH_SESSION -> viewModel.finishSession(null)
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
                        // Two dots only when a second sensor is really in play:
                        // two paired, both labelled, and this set armed for
                        // both. Otherwise the bar would carry a permanently
                        // grey dot for every single-sensor lifter, which reads
                        // as something being broken. Labelled with the ROLE
                        // rather than "IMU"/"IMU B" so the dot that goes amber
                        // names the unit to go and look at -- and the armed
                        // one is not always A (#156).
                        val roster = state.roster
                        // Volt means FRAMES ARE ARRIVING on these two, not
                        // that a link is up (#213). The strap passes nothing,
                        // because nothing publishes its frame arrivals and a
                        // dot that went amber because nobody looked would be
                        // the same false claim the other way round.
                        val deliveryA =
                            rememberArmedDelivery(state.imuState, state.imuFrameAtMs, state.imuArmedAtMs)
                        SensorDot(
                            roster.analysed?.name ?: "IMU",
                            state.imuState,
                            demoActive = state.demoMode,
                            delivery = deliveryA,
                        )
                        roster.secondary?.let {
                            val deliveryB =
                                rememberArmedDelivery(state.imuStateB, state.imuFrameAtMsB, state.imuArmedAtMsB)
                            SensorDot(it.name, state.imuStateB, delivery = deliveryB)
                        }
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
                Stage.PREVIEW -> PreviewStage(state, viewModel)
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
    Stage.PREVIEW -> state.previewSession?.name ?: "Session"
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
 * covered by a test at any commit: no test on the CI path can render a
 * `@Composable`, so this mapping was verified by reading it, and by nothing
 * else.
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
    // It may NOT name the effort grid or END SET EARLY by name, because since
    // #186 neither is drawn on a running tempo-guided or timed set, and
    // RecordExitPolicyTest states the rule for the sibling prompt: naming a
    // control that is not on screen is worse than naming none.
    ExitPrompt.SET_IN_PROGRESS ->
        "Nothing about this set has been saved — the reps, the sensor data and the effort rating all go. " +
            "To keep it, tap Keep recording and end the set with the control at the bottom of this screen. " +
            "On a tempo-guided or timed set that is under way that control ends the set as a failure, and " +
            "you can still rate the set while you rest."
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
        "Part of this session was not written — the end time and the heart-rate and HRV summary, or the " +
            "rest recorded after your last set. Tapping FINISH SESSION AGAIN on this screen can still " +
            "write what is missing — freeing some space on the phone first if that is what stopped it. " +
            "Every set is already saved either way. Whatever has not been written is held only here, and " +
            "leaving now loses it."
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

/**
 * Ask for a body weight at the one moment it is about to matter (#181).
 *
 * Raised by [RecordViewModel.requestPlanSession] and only when
 * [BodyWeightPromptPolicy.shouldPrompt] says so, which is never on a session
 * with no bodyweight exercise in it. Every sentence in it comes from that
 * policy rather than from here, so the threshold and the wording are pinned by
 * tests that run on every push.
 *
 * SKIP is a first-class button and not a dismissal in disguise: it starts the
 * session, and nothing asks again until this session closes. The dialog also
 * refuses to be dismissed by an outside tap or a back press -- onDismissRequest
 * skips explicitly rather than doing nothing, so there is no way to leave it on
 * screen or to lose the session start behind it.
 */
@Composable
private fun BodyWeightPromptDialog(
    session: PlanSessionDef,
    state: RecordState,
    onSkip: () -> Unit,
    onSet: (Double) -> Unit,
) {
    val unit = state.weightUnit
    // One clock reading for the life of the dialog. Not an inline
    // System.currentTimeMillis(): a Composable's body runs again on every
    // recomposition, so an inline read would let the age line tick over while
    // the lifter is typing into it. The decision to show this at all was taken
    // in the ViewModel against its own reading; this one only formats.
    val nowMs = rememberSaveable { System.currentTimeMillis() }
    val stored = BodyWeightPromptPolicy.stateOf(state.bodyWeightKg, state.bodyWeightSetAtMs, nowMs)
    var text by rememberSaveable { mutableStateOf(state.bodyWeightKg?.let { unit.inputValue(it) } ?: "") }
    AlertDialog(
        onDismissRequest = onSkip,
        title = { Text("Body weight for ${session.name}") },
        text = {
            Column {
                Text(
                    BodyWeightPromptPolicy.storedLine(
                        stored,
                        state.bodyWeightKg,
                        BodyWeightPromptPolicy.ageDays(state.bodyWeightSetAtMs, nowMs),
                        unit,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    BodyWeightPromptPolicy.WHY_IT_MATTERS,
                    style = MaterialTheme.typography.bodySmall,
                    color = BarColors.Sub,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Body weight (${unit.suffix})") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { unit.parseToKg(text)?.takeIf { it > 0 }?.let(onSet) }) {
                Text("Save and start")
            }
        },
        dismissButton = { TextButton(onClick = onSkip) { Text("Skip", color = BarColors.Sub) } },
    )
}

/**
 * Raise [BodyWeightRequiredDialog] when a set has been refused, and nothing
 * otherwise. The exercise it names is `currentExercise`, the same definition
 * `beginSet` asked [SetLoadPolicy.blocksSetStart] about, so the dialog cannot
 * name one movement while the refusal was about another.
 */
@Composable
private fun BodyWeightRefusal(state: RecordState, viewModel: RecordViewModel) {
    if (!state.bodyWeightRequiredForSet) return
    BodyWeightRequiredDialog(
        exerciseName = state.currentExercise.displayName,
        unit = state.weightUnit,
        onCancel = { viewModel.answerBodyWeightForSet(null) },
        onSet = { viewModel.answerBodyWeightForSet(it) },
    )
}

/**
 * A body-weight set was asked to start and there is no body weight to record
 * it against, so it did not start (#61).
 *
 * NOT [BodyWeightPromptDialog], and the difference is the whole point. That
 * one is an ASK raised before a session, it has a SKIP, and skipping starts
 * the session anyway (#181). This one is a REFUSAL raised by
 * `RecordViewModel.beginSet`, and its second button is CANCEL: it takes the
 * lifter back to the screen they were on with no set started, because there is
 * no answer that would let the app record a load it does not have.
 *
 * SAVE is disabled until the box parses to a positive number, so the one
 * control that ends the refusal cannot end it with another absence.
 *
 * The two sentences that explain the refusal come from
 * [SetLoadPolicy.BODY_WEIGHT_REQUIRED] and [BodyWeightPromptPolicy.WHY_IT_MATTERS],
 * so a test on the CI path runs on that wording. The title, the field label
 * and both button words are written here and nothing tests them.
 */
@Composable
private fun BodyWeightRequiredDialog(
    exerciseName: String,
    unit: WeightUnit,
    onCancel: () -> Unit,
    onSet: (Double) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    val parsed = unit.parseToKg(text)?.takeIf { it > 0 }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Body weight needed for $exerciseName") },
        text = {
            Column {
                Text(SetLoadPolicy.BODY_WEIGHT_REQUIRED, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    BodyWeightPromptPolicy.WHY_IT_MATTERS,
                    style = MaterialTheme.typography.bodySmall,
                    color = BarColors.Sub,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Body weight (${unit.suffix})") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { parsed?.let(onSet) }, enabled = parsed != null) {
                Text("Save and start")
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel", color = BarColors.Sub) } },
    )
}

/**
 * The whole of the upcoming session, read before anything starts (#202).
 *
 * WHAT IT DRAWS IS THE QUEUE. `state.queue` is the list the record flow walks,
 * built once by `flattenPlan` when the session card was tapped and handed
 * straight to the Start press; this composable projects each slot with
 * `previewSet()` and phrases it with `SessionPreviewPolicy.setLine`, whose base
 * text `SlotCard` shares: "Up next" draws `struckLine` over `SetCardValues.of`,
 * and `setLine` is that same `of` rendered by `SetCardValues.plain`, so an
 * unrun set reads the same string on both surfaces. Nothing here reads
 * `PlanSessionDef`, so there is no second rendering of the plan to disagree
 * with the set the lifter lands on.
 *
 * NOTHING IS STARTED WHILE THIS IS ON SCREEN. `RecordExitPolicy.promptFor`
 * answers `NONE` for this stage, and it can, because `previewState` writes a
 * stage, a name and a queue and nothing else -- see its own KDoc for the list
 * of things that deliberately do not happen until START.
 *
 * The load, count and tempo shown are the slot's STANDING values, which before
 * the first set are the plan's own; if a correction were ever standing when
 * this drew, it would show what the flow will run rather than what the plan
 * asked for, because that is the question the lifter is answering.
 */
@Composable
private fun PreviewStage(state: RecordState, viewModel: RecordViewModel) {
    // Drawn HERE and no longer on SETUP, because the prompt is raised by
    // `requestPlanSession` and the only tap that reaches it is this screen's
    // START. Left on SETUP it would have been a dialog nothing could open --
    // the body-weight question would simply have stopped being asked, and the
    // load recorded for every pull-up and dip of the session would have been
    // whatever weight was last stored, with nothing on screen to say so.
    state.pendingBodyWeightSession?.let { pending ->
        BodyWeightPromptDialog(
            session = pending,
            state = state,
            onSkip = { viewModel.answerBodyWeightPrompt(null) },
            onSet = { viewModel.answerBodyWeightPrompt(it) },
        )
    }
    val preview = SessionPreviewPolicy.of(state.queue.map { it.previewSet() })
    SectionCaption("Before you start", color = BarColors.Volt)
    Spacer(Modifier.height(4.dp))
    Text(state.previewSession?.name ?: "Session", style = MaterialTheme.typography.headlineSmall)
    Text(
        previewSummary(preview),
        style = MaterialTheme.typography.bodySmall,
        color = BarColors.Sub,
    )
    Spacer(Modifier.height(12.dp))
    preview.blocks.forEach { block ->
        PreviewBlockCard(block, state.weightUnit)
        Spacer(Modifier.height(8.dp))
    }
    Spacer(Modifier.height(4.dp))
    // Disabled rather than hidden on an empty session: a plan session with no
    // sets is a plan defect, and a START that silently vanished would read as
    // the app being broken rather than the plan being empty.
    // START is the SAME call the session picker card used to make. The preview
    // did not add a second way into a session; it moved the one that exists
    // behind a screen the lifter has read.
    Button(
        onClick = { state.previewSession?.let(viewModel::requestPlanSession) },
        enabled = !preview.isEmpty && state.previewSession != null,
        modifier = Modifier.fillMaxWidth().height(56.dp),
    ) {
        Text("START", fontWeight = FontWeight.Bold)
    }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = viewModel::abandonSetup, modifier = Modifier.fillMaxWidth()) {
        Text("Choose another session")
    }
}

/**
 * The one line under the session name.
 *
 * Warm-ups are named rather than netted out. "8 sets, 3 of them warm-ups" and
 * "5 sets" describe different afternoons, and the lifter deciding whether they
 * have time for this is reading for the first.
 *
 * [SessionPreview.blockCount] is worded "exercises" here to match the session
 * picker card, which renders `planSession.exercises.size` the same way; the
 * count is BLOCKS, not distinct movements (two blocks of the same lift count
 * twice), but changing the word on this surface alone would create the
 * disagreement between preview and picker this whole feature exists to
 * prevent.
 */
private fun previewSummary(preview: SessionPreview): String {
    if (preview.isEmpty) return "This session has no sets in it."
    val parts =
        listOfNotNull(
            "${preview.blockCount} exercises",
            "${preview.totalSets} sets",
            preview.warmupSets.takeIf { it > 0 }?.let { "$it warm-up" },
        )
    return parts.joinToString(" · ")
}

/** One exercise block of the preview: its name, then every set of it in order. */
@Composable
private fun PreviewBlockCard(block: PreviewBlock, unit: WeightUnit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(block.exerciseName, style = MaterialTheme.typography.titleSmall)
            block.sets.forEach { set ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                ) {
                    Text(
                        // The same numbering the record flow shows on the set
                        // itself, so a lifter can find the line they are on.
                        "Set ${set.setIndexInExercise + 1}/${set.setsInExercise}",
                        style = MaterialTheme.typography.bodySmall,
                        color = BarColors.Sub,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Text(
                        SessionPreviewPolicy.setLine(set, unit),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (set.warmup) {
                    Text(
                        "warm-up",
                        style = MaterialTheme.typography.bodySmall,
                        color = BarColors.Amber,
                    )
                }
            }
        }
    }
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
                onClick = { viewModel.openPreview(planSession) },
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
    //
    // The refusal is drawn HERE and only here, and it covers both doors:
    // `startNextSet` runs `advancedState` -- which writes Stage.READY on the
    // planned and the ad-hoc branch alike -- before it calls `beginSet`, so a
    // set refused from the rest screen is refused with this stage already
    // showing. Without that, a refusal on the rest path would be a set that
    // silently failed to start.
    BodyWeightRefusal(state, viewModel)
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
            side = SideChoicePolicy.carriedIntoNextSet(declaredSide = slot.side, statedSide = state.statedSide),
            values = cardValues(state, slot),
            prep = cardPrep(state, slot),
            highlight = true,
            plateLoadKgOverride = state.statedLoadKg,
        )
        // Everything the lifter can change about set one now lives behind one
        // button, and every change it makes is struck into the card above it.
        // READY renders at most once per session -- startNextSet writes READY
        // and calls beginSet in the same frame -- so this is set one's only
        // chance to say anything, and until this button it could say only the
        // load.
        ChangeSetButton(state, viewModel, slot, next = false)
        // Kept in place and NOT moved into the dialog: it is one line, it
        // opens a chooser of its own, and a dialog inside a dialog is a shape
        // this app has never used. It already reached READY, which is half of
        // what the owner asked for when every rack is busy on arrival; the
        // other half is the button above.
        SwitchExerciseSection(state, viewModel)
        AddSetSection(state, viewModel)
        RemoveSetSection(state, viewModel)
    } else {
        AdHocForm(state, viewModel)
        // Plan sets take their prep from the dialog. The ad-hoc layout keeps
        // its inline form, so it keeps its inline prep too.
        PrepAdjuster(state, viewModel)
    }
    SensorCaptureLine(state)
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
/**
 * What the next set will record from, issue #198.
 *
 * A LINE rather than a control. It offered 1 and 2 as chips until #198, stored
 * against the exercise the way the prep adjustment is; the hardware now
 * decides rather than the plan -- *"If you've got one use one, if you've got
 * two, use both."* -- so there is nothing left to choose and a control that
 * changed nothing would be worse than none.
 *
 * Drawn only when a second IMU is paired. One sensor is the ordinary setup for
 * every exercise, and a permanent line about it in front of a lifter who owns
 * one unit is the same complaint the dissolved `ONE_SENSOR_PAIRED` sentence
 * used to make.
 *
 * When two are paired but cannot be told apart it says WHICH gap, in
 * [DualSensorSetup]'s words, before the set rather than only in the export
 * afterwards -- the one thing here the lifter can still act on.
 */
@Composable
private fun SensorCaptureLine(state: RecordState) {
    ArmedSilenceCard(state)
    if (state.pairedImuAddresses.size < SensorCapturePolicy.MAX_COUNT) return
    val roster = state.roster
    val detail = sensorCaptureDetail(roster) ?: return
    Spacer(Modifier.height(8.dp))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                if (roster.isDual) "Two sensors" else "One sensor",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = if (roster.shortfall == null) BarColors.Sub else BarColors.Amber,
            )
        }
    }
}

/**
 * What that line says, as a pure function of the roster, or null for nothing
 * to say.
 *
 * The shortfall sentences live in [DualSensorSetup.recordLine] in
 * `:core:model`, so the Devices screen reads the SAME copy rather than a
 * second phrasing of the same gap (#184), and the exhaustive `when` that makes
 * a new `DualShortfall` a compile error is over there with them and pinned by
 * a test.
 *
 * Null is the third answer and it is reachable: two units paired and labelled
 * apart while the registry's preferred address names neither of them arms one
 * stream with no gap to report. That state is a stale registry entry rather
 * than something the lifter can fix from here, and it used to draw "Fewer than
 * two sensors are paired" over a setup with two.
 */
private fun sensorCaptureDetail(roster: SensorRoster): String? {
    roster.shortfall?.let { return DualSensorSetup.recordLine(it) }
    return if (roster.isDual) {
        "Both streams are recorded from whichever units are delivering; nothing is derived from the second one yet"
    } else {
        null
    }
}

/**
 * The armed unit that is delivering nothing, named BEFORE the set starts
 * (#213).
 *
 * field-37 armed two units on thirteen sets, received one, and told nobody:
 * the archive records `present: ["a"]` and the screens read paired throughout.
 * READY and the rest screen are the windows that run for seconds with both
 * units armed and the lifter's hands free to switch one on, re-seat it or
 * decide to lift with one -- so those are where it is said, and it is a card
 * rather than a toast because a toast that appears while the lifter is under a
 * bar is a message nobody reads.
 *
 * WHAT IT SAYS is `ArmedSilencePolicy.advice`'s, in `:core:model` where a test
 * runs on it, and the sentence names the most specific state the BLE stack can
 * report -- no link, a device answering with the wrong profile, or a link with
 * nothing coming down it. Those have three different remedies and the lifter
 * was offered none of them.
 *
 * It re-answers once a second because the answer changes with time and nothing
 * else would drive a recomposition, and it says NOTHING for three seconds
 * after a link is pointed at a device: `ArmedSilencePolicy.SILENT_AFTER_MS` is
 * the grace, and accusing a link two seconds into a connect is how a warning
 * becomes something the lifter learns to ignore. EACH LINK ANSWERS TO ITS OWN
 * ARMING since #225 -- before that both were floored by the later of the two
 * instants, so re-pointing one link excused the other.
 *
 * IT SAYS NOTHING IN DEMO MODE (#225 item 7). `startDemoStream` fabricates
 * samples with no sensor present, so the set records and the sentence this
 * card draws is the one claim demo mode makes false; `SensorDot` above already
 * takes `demoActive` for that reason.
 *
 * Drawn on READY and on RESTING. READY is drawn once per session --
 * `startNextSet` writes READY and calls `beginSet` in the same frame -- so a
 * card on READY alone names a silent unit before set one and never again,
 * while RESTING precedes every later set. SETUP draws no card: the roster IS
 * answerable there, so the omission is a DECISION and not a limit, made
 * because the bar-sensor card already on that screen covers the analysed
 * link being DOWN and does not cover a link that is up and silent.
 *
 * IT DRAWS FOR ONE UNIT TOO, since #224. `RecordState.armedDelivery` is empty
 * on a set that armed no role -- one paired bar sensor, or two the app cannot
 * tell apart -- and until this issue that emptiness was the whole story: a
 * single connected-and-silent unit got the delivery dot and nothing else, on
 * the configuration this app is used in most. `RecordState.soleSilenceOver`
 * answers the same question about the one link the app holds, with no role to
 * key it by, and `ArmedSilencePolicy.message` is handed both. The two are
 * mutually exclusive by construction, so the card still shows one situation.
 *
 * NOTHING IN THIS REPOSITORY CAN RUN THIS. `:app` has no test that composes
 * anything, and whether a real WT901 left switched off produces the state this
 * reads is a [Field] question. The rule it draws is pinned; the drawing is
 * compile- and lint-gated only.
 */
@Composable
private fun ArmedSilenceCard(state: RecordState) {
    // remember + LaunchedEffect rather than produceState, for the reason
    // `rememberArmedDelivery` gives: lint reds the produceState form.
    val nowMs = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs.longValue = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val message =
        ArmedSilencePolicy.message(
            state.armedDelivery(nowMs.longValue),
            state.soleSilenceOver(state.imuArmedAtMs, nowMs.longValue),
            state.demoMode,
        ) ?: return
    Spacer(Modifier.height(8.dp))
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("A sensor is not sending data", style = MaterialTheme.typography.titleSmall)
            Text(message, style = MaterialTheme.typography.bodySmall, color = BarColors.Amber)
        }
    }
}

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
 * the stated tempo as well as the slot's, which is four more reads in code no
 * test on the CI path can render, for a case the ask did not name.
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
 * The values the card states for the coming set, each carrying the plan's
 * figure when the lifter has changed it.
 *
 * DUMB: every string of it is [SetCardValues.of], which is pinned in
 * :core:model. This resolves the facts that function compares and draws what
 * comes back.
 *
 * The FROZEN declarations on the planned side and the lifter's statement on
 * the other. Until #174 froze them the rest screen read slot.reps and
 * slot.durationS, which advancedState bakes the edit into: on READY the bake
 * has already run, so the comparison was a number against itself and a rep
 * count changed and changed back read as no change. #170 item 6.
 *
 * The stated side falls back to the slot's LIVE value rather than to its
 * declaration, which is what the card drew before #204 and what keeps an
 * appended set -- prescribed by nothing, so every frozen field on it is null
 * -- drawing its own load.
 */
private fun cardValues(state: RecordState, slot: PlannedSlot): List<SetCardValue> = SetCardValues.of(
    kind = slot.exercise.kind,
    bodyweight = slot.exercise.bodyweight,
    timed = slot.isTimed,
    unit = state.weightUnit,
    // The side the set will WORK, by the same rule the bake applies at START,
    // and the plan's own declaration beside it. Read through the policy rather
    // than off the slot: on the rest screen the slot has NOT been baked yet, so
    // reading slot.side would show the prescription while the lifter has
    // already chosen the other arm -- the card-versus-record disagreement #45
    // and #124 are both about.
    side =
    SideChoicePolicy.carriedIntoNextSet(
        declaredSide = slot.side,
        statedSide = state.statedSide,
    ),
    plannedSide = slot.plannedSide,
    plannedLoadKg = slot.plannedLoadKg,
    statedLoadKg = state.statedLoadKg,
    declaredLoadKg = slot.loadKg,
    plannedReps = if (slot.isTimed) null else slot.plannedReps,
    reps = if (slot.isTimed) slot.reps else state.statedReps ?: slot.reps,
    plannedDurationS = if (slot.isTimed) slot.plannedDurationS else null,
    durationS = if (slot.isTimed) state.statedDurationS ?: slot.durationS else slot.durationS,
    plannedTempo = slot.plannedTempo,
    tempo = state.statedTempo ?: slot.tempo,
)

/**
 * The prep pair for the card's secondary line, or null for nothing to say.
 *
 * Gated on `upcomingPlaysPrep`, which the line this replaces was not: a prep
 * adjustment is stored against the EXERCISE and outlives the set it was made
 * on, so a set that plays no prep at all -- an untempoed rep set, where
 * [LeadInPolicy.playsPrep] is false -- could be told its prep had changed from
 * a countdown it will never run. The control that changes it is gated the same
 * way and always has been.
 */
private fun cardPrep(state: RecordState, slot: PlannedSlot): SetCardValue? = if (!state.upcomingPlaysPrep) {
    null
} else {
    SetCardValues.prep(
        plannedPrepS = state.plannedPrepSecondsFor(slot),
        prepS = state.prepSecondsFor(slot),
    )
}

/**
 * One line of card values, with the plan's figure struck through wherever the
 * lifter has changed it and what the set will record beside it.
 *
 * The strike is the whole of #204: the card used to state the plan's numbers
 * with no sign they had moved, and a sentence under it -- "Your changes: 100
 * kg" -- said in prose what the number could have carried itself. A lifter
 * reading the card had to reconcile two places.
 *
 * The struck half is drawn in [BarColors.Sub] and the standing half in
 * [BarColors.Volt], which is the colour the removed line used, so the pair
 * reads as "not this, that". The words around a figure are outside both spans
 * and drawn once.
 */
private fun struckLine(lead: String, values: List<SetCardValue>): AnnotatedString = buildAnnotatedString {
    append(lead)
    values.forEachIndexed { index, value ->
        if (index > 0) append(" · ")
        if (value.prefix.isNotEmpty()) append("${value.prefix} ")
        val planned = value.planned
        if (planned == null) {
            append(value.stated)
        } else {
            withStyle(
                SpanStyle(color = BarColors.Sub, textDecoration = TextDecoration.LineThrough),
            ) {
                append(planned)
            }
            append(" ")
            withStyle(SpanStyle(color = BarColors.Volt)) { append(value.stated) }
        }
        if (value.suffix.isNotEmpty()) append(" ${value.suffix}")
    }
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
 * Outlined, never filled: START is the only filled button on either screen
 * while a set is queued -- after the last planned set START is withheld and
 * FINISH SESSION takes the filled place (#195) -- and two full-width filled
 * buttons is how a thumb picks the wrong one. A
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
internal fun ChangeSetDialog(
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
                PlanValueCaptions(state, slot)
                PerImplementEcho(state, slot)
                LoadSignHint(slot)
                if (next) SideAdjuster(state, viewModel, slot)
                TempoAdjuster(state, viewModel)
                PrepAdjuster(state, viewModel)
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text("Done") } },
    )
}

/**
 * "Add another set" — one more set of the exercise just FINISHED, at the
 * values standing for it (#177, #188).
 *
 * The exercise named is `currentSlot`'s, which during rest is the set that has
 * happened and on READY is the set about to. It used to be `upcomingSlot`'s,
 * so after the last set of an exercise the button offered another set of the
 * exercise coming up -- the wrong name, and the wrong set -- at the exact
 * moment the lifter is thinking about the one they just did. Where the two
 * differ [AddSetControl.label] says where the added set lands, and that is the
 * only case where it says so.
 *
 * Drawn on the last-set branch of [NextSetBlock] as well, so the session's
 * final set has the control too.
 *
 * BESIDE "Equipment busy? Switch exercise" and in the same form, on BOTH
 * surfaces, which is #152's consolidated change surface: the rest screen and
 * READY. The owner required switch-exercise in both places for the reason that
 * applies here unchanged -- set one is when a wrong load first shows itself, and
 * READY is the only screen set one has.
 *
 * NOT inside the change-set dialog. Everything in that dialog changes the set
 * coming up; this changes the SESSION, and folding it in would put it under a
 * subtitle reading "Adjust next set (deviations are recorded)" -- which would
 * be a false description of it, and would also make it read as a deviation of
 * the upcoming set. It is not one: the upcoming set is untouched, its card
 * is unchanged, and the appended
 * set is a set the plan does not contain rather than a change to a set it does.
 *
 * A TextButton, not an OutlinedButton, for [SwitchExerciseSection]'s reason:
 * START is the only filled button on either screen while a set is queued --
 * after the last planned set START is withheld and FINISH SESSION takes the
 * filled place (#195) -- and this line sits beside one that already looks
 * like this. A mis-tap here used to be survivable only because the plan's
 * remaining sets are dropped whenever a session is finished early; since #206
 * it is survivable directly, because [RemoveSetSection] -- called immediately
 * below this at all three of the sites that call it, and drawn on the two
 * where an appended set of the block can still be queued -- takes the set
 * back out again.
 *
 * Repeatable: every tap appends one more, and nothing here or in
 * [RecordViewModel.addSetOfCurrentExercise] assumes at most one.
 */
@Composable
private fun AddSetSection(state: RecordState, viewModel: RecordViewModel) {
    if (state.adHoc) return
    val anchor = state.currentSlot ?: return
    TextButton(onClick = viewModel::addSetOfCurrentExercise) {
        Text(
            AddSetControl.label(anchor.exercise.displayName, state.upcomingSlot?.exercise?.displayName),
            color = BarColors.Blue,
        )
    }
}

/**
 * "Remove the set you added" -- #177's named remainder, and the other half of
 * the pair (#206).
 *
 * CALLED IMMEDIATELY BELOW [AddSetSection], at all three of the sites that
 * call it: the rest screen's next-set block, the rest screen's last-set
 * branch, and READY. Only the first and the third ever DRAW it. In the
 * last-set branch [RecordState.nextSlot] is null, so
 * [RecordState.upcomingIndex] -- `queueIndex + 1` throughout rest -- is
 * already past the queue's last index; every candidate
 * [RemoveSetControl.target] considers comes from [AddSetControl.blockRange],
 * whose indices never reach that far, so its `it >= upcomingIndex` filter is
 * empty, [RecordState.removeSetTarget] is always null on that branch, and
 * this function returns before drawing anything. The call stays there
 * anyway: the moment [AddSetSection] appends a set, [RecordState.nextSlot]
 * stops being null and the same state renders through the next-set block's
 * copy of this call instead, where the set just added is eligible. #206
 * requirement 3 asks the pair to read as one decision rather than two
 * unrelated controls; on the two surfaces where this can render, calling it
 * directly under the add is what makes that true.
 *
 * DRAWN ONLY WHEN THERE IS SOMETHING TO REMOVE, which is what makes the
 * eligibility rule visible instead of merely enforced. A plan-prescribed set
 * never produces a target, and neither does an appended set that has already
 * RUN: once the set is recorded it is a row of training history with its
 * samples, its raw stream and its export entry, and the control is simply not
 * there. The lifter never taps a target that refuses.
 *
 * WHAT IT NAMES is the slot the tap will actually take -- one lookup, into the
 * same [RecordState.removeSetTarget] the ViewModel acts on, so the words and
 * the act cannot name different sets. With more than one appended set of the
 * anchor's block the label says it takes the LAST, because "remove the set you
 * added" is ambiguous the moment there are two.
 *
 * A TextButton for [AddSetSection]'s reason. This one is not destructive of
 * anything recorded, so it takes no confirmation: the set it removes has not
 * happened, and the lifter can add it again with the button above.
 */
@Composable
private fun RemoveSetSection(state: RecordState, viewModel: RecordViewModel) {
    val target = state.removeSetTarget ?: return
    val slot = state.queue.getOrNull(target.removeAt) ?: return
    TextButton(onClick = viewModel::removeAddedSetOfCurrentExercise) {
        Text(
            RemoveSetControl.label(
                slot.exercise.displayName,
                setNumber = slot.setIndexInExercise + 1,
                several = target.removableCount > 1,
            ),
            color = BarColors.Blue,
        )
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

/**
 * "Total load (lb)" once an exercise declares more than one implement, so the
 * box says what it takes before anything is typed into it.
 *
 * The box goes on taking the TOTAL at every site, unchanged. That is the whole
 * reason this feature performs no multiplication anywhere: if the field's
 * meaning flipped to per-implement, every read and every re-seed of
 * `loadInput` would carry a divide-or-multiply obligation forever, in code no
 * test on the CI path can reach.
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
 * What the plan prescribed for the load and for the reps or hold, drawn under
 * the pair of boxes that change them.
 *
 * DUMB, the way [cardValues] is: every word is [PlanValueCaption]'s and is
 * pinned in :core:model. This resolves the three facts each caption needs and
 * draws whatever comes back, including nothing.
 *
 * The REACH each caption claims is not asserted here -- it is the carry policy's
 * own answer about this slot and the one after it. Asking
 * SetLoadPolicy.standingStatedAddedKg and SetRepsPolicy whether the value on
 * screen would still stand for the set after this one is the same call
 * restingState will make when that set actually arrives, with one argument
 * different -- this passes the parsed box where restingState passes
 * state.statedLoadKg / statedReps / statedDurationS. The two agree wherever a
 * caption is drawn at all, because a caption is drawn only when the box's
 * rendered text differs from the plan's and an untouched box is re-seeded from
 * that same declaration. So the sentence and the behaviour cannot disagree: on
 * the last set of a block, the answer is null for all three boxes. Reps and
 * hold also answer null wherever the plan prescribes a different number next
 * -- SetRepsPolicy stops the carry there. The load does not: since b3c649e7
 * it carries a correction across a stepping plan as a distance rather than
 * stopping, so [SetLoadPolicy.standingStatedAddedKg] stays non-null there
 * too. What withholds the load's reach sentence is not a policy null but
 * [nextDeclaredAddedKg], passed to [PlanValueCaption.load] separately and
 * compared against the planned figure inside it -- the caption says the
 * change is recorded rather than claiming a reach it does not have.
 *
 * The PLANNED side is the FROZEN declaration and the SHOWN side is the box, so
 * the caption names the plan's prescription and the lifter's standing
 * statement as two different things -- which is the point of it, once a value
 * can differ from the plan because of something said several sets ago.
 */
@Composable
private fun PlanValueCaptions(state: RecordState, slot: PlannedSlot) {
    val after = state.slotAfterUpcoming
    val sameBlock =
        SetLoadPolicy.sameExerciseBlock(
            lastExerciseId = slot.exercise.id,
            nextExerciseId = after?.exercise?.id,
            nextSetIndexInExercise = after?.setIndexInExercise,
        )
    val shownAddedKg = state.weightUnit.parseToKg(state.loadInput)
    val captions =
        listOfNotNull(
            PlanValueCaption.load(
                adHoc = state.adHoc,
                added = slot.isAddedSet,
                bodyweight = slot.exercise.bodyweight,
                unit = state.weightUnit,
                plannedAddedKg = slot.plannedLoadKg,
                nextDeclaredAddedKg = after?.plannedLoadKg,
                shownAddedKg = shownAddedKg,
                standsForLaterSets =
                SetLoadPolicy.standingStatedAddedKg(
                    statedAddedKg = shownAddedKg,
                    sameExerciseBlock = sameBlock,
                    lastDeclaredAddedKg = slot.plannedLoadKg,
                    nextDeclaredAddedKg = after?.plannedLoadKg,
                    bodyweight = after?.exercise?.bodyweight ?: false,
                    finishedWarmup = slot.warmup,
                    nextWarmup = after?.warmup == true,
                ) != null,
            ),
            if (slot.isTimed) {
                PlanValueCaption.hold(
                    adHoc = state.adHoc,
                    added = slot.isAddedSet,
                    plannedDurationS = slot.plannedDurationS,
                    nextDeclaredDurationS = after?.plannedDurationS,
                    shownDurationS = state.durationInput.trim().toIntOrNull(),
                    standsForLaterSets =
                    SetRepsPolicy.standingStatedDurationS(
                        statedDurationS = state.durationInput.trim().toIntOrNull(),
                        sameExerciseBlock = sameBlock,
                        lastDeclaredDurationS = slot.plannedDurationS,
                        nextDeclaredDurationS = after?.plannedDurationS,
                    ) != null,
                )
            } else {
                PlanValueCaption.reps(
                    adHoc = state.adHoc,
                    added = slot.isAddedSet,
                    plannedReps = slot.plannedReps,
                    nextDeclaredReps = after?.plannedReps,
                    shownReps = state.repsInput.trim().toIntOrNull(),
                    standsForLaterSets =
                    SetRepsPolicy.standingStatedReps(
                        statedReps = state.repsInput.trim().toIntOrNull(),
                        sameExerciseBlock = sameBlock,
                        lastDeclaredReps = slot.plannedReps,
                        nextDeclaredReps = after?.plannedReps,
                    ) != null,
                )
            },
        )
    captions.forEach {
        Text(it, style = MaterialTheme.typography.bodySmall, color = BarColors.Sub)
    }
}

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
 * population is every exercise that RESOLVES to body-weight work, declared
 * or seeded, with no subset -- the same
 * population `PlanFile.validate` already passes `allowNegativeLoad` for -- so
 * a dead hang can be assisted and a push-up can take a plate whether or not
 * the plan declared a load for them. #160.
 */
@Composable
private fun LoadSignHint(slot: PlannedSlot?) {
    val hint = BodyweightLoadDisplay.fieldHint(slot?.exercise?.bodyweight == true) ?: return
    Text(hint, style = MaterialTheme.typography.bodySmall, color = BarColors.Sub)
}

/**
 * Which arm the NEXT set works, on a unilateral set (#215).
 *
 * DRAWN ONLY WHERE THE SET IS UNILATERAL. [SideChoicePolicy.offersChoice]
 * decides, in the module a test runs in, and a bilateral set gets no control at
 * all -- the same silent-no-draw rule [SideArrow] applies to the arrow. A
 * Both/Left/Right selector here would let a lifter put a limb on a set that
 * used two, and unlike the ad-hoc selector one field over, what is chosen here
 * is RECORDED against a prescription.
 *
 * NEXT SET ONLY, which is #215's own scope and the owner's words -- "adjust the
 * arrow on one-sided exercises in the adjust next set". READY's "Change this
 * set" therefore does not offer it, so set one of a unilateral block cannot be
 * swapped. That is a real gap and it is named rather than folded in: the
 * mechanism would carry over unchanged, `startedFromReadyState` bakes through
 * the same function, and the only thing missing is this line without its
 * `next` guard.
 *
 * NO ARROW IN THE CHIPS, deliberately. #129 is open on [SideArrow] being sized
 * in dp beside text sized in sp, so it shrinks against its neighbours at large
 * font scales; a preview arrow here would be a second site of that defect on
 * the surface the lifter reads while deciding. The card behind the dialog
 * redraws its own arrow the moment Done closes this.
 *
 * The caption states the prescription rather than the deviation, because the
 * chip already says what will happen and the plan's own word is the thing the
 * lifter cannot otherwise see once they have changed it.
 */
@Composable
private fun SideAdjuster(state: RecordState, viewModel: RecordViewModel, slot: PlannedSlot) {
    if (!SideChoicePolicy.offersChoice(slot.side)) return
    val chosen = SideChoicePolicy.carriedIntoNextSet(declaredSide = slot.side, statedSide = state.statedSide)
    Spacer(Modifier.height(8.dp))
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Side", style = MaterialTheme.typography.bodySmall, color = BarColors.Sub)
        SideChoicePolicy.CHOICES.forEach { value ->
            FilterChip(
                selected = chosen == value,
                onClick = { viewModel.stateNextSetSide(value) },
                label = { Text(value.replaceFirstChar { it.uppercase() }) },
            )
        }
    }
    slot.plannedSide?.let {
        Text(
            "The plan asks for ${it.replaceFirstChar { c -> c.uppercase() }}",
            style = MaterialTheme.typography.bodySmall,
            color = BarColors.Sub,
        )
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

/**
 * What the movement in front of the lifter is.
 *
 * Delegates to [RecordState.currentExerciseKind] rather than restating its
 * three lines: the same question was answered here and in the view model, and
 * only one of the two copies is reachable by a test.
 */
private fun currentKind(state: RecordState): ExerciseKind = state.currentExerciseKind

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
        if (state.leadInRunning) TimedPrepRing(state, carry) else TimedClockRing(state, carry)
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
            // "added" rather than a count on an appended set: the plan asked
            // for none of it, and "Set 4/4" beside a card of prescribed sets
            // reading "of 3" is the reading #177 exists to stop (#177).
            slot?.let {
                if (it.isAddedSet) {
                    "Set ${it.setIndexInExercise + 1} · added"
                } else {
                    "Set ${it.setIndexInExercise + 1}/${it.setsInExercise}"
                }
            },
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
 * WHAT IS DRAWN IS [SetEndControlPolicy]'S DECISION and this function only
 * paints it. On a hand-counted or explosive set the effort grid is drawn
 * either way, with END SET EARLY under it on a set that came up short. On a
 * tempo-guided or timed set the grid is withheld until the app can say the set
 * is over, and the one control before that is the standalone failure button
 * (#186). During either kind's lead-in, before its clock or its cadence has
 * started, the one control is END SET EARLY, which stores no TAPPED verdict;
 * the write still derives a shortfall from the near-zero count, so a set with
 * a prescription is recorded `failed` with `tappedFailed` false.
 *
 * Rating an abandoned set does not log it as a good one: the shortfall is
 * derived at the write from the rep or second count and lands as `failed`
 * whether or not a tile was tapped, so "3 reps left" three reps into a
 * five-rep set records an effort AND a failure, which is the pair a reader of
 * the export needs and could not get.
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
            val controls =
                SetEndControlPolicy.controls(
                    kind = state.setEndKind,
                    targetMet = state.setTargetMet,
                    complete = state.setComplete,
                    started = state.setStarted,
                )
            if (SetEndControl.EFFORT_GRID in controls) {
                EndSetRpeGrid(state, viewModel, failedTile = SetEndControl.FAILED_TILE in controls)
            }
            if (SetEndControl.END_FAILED in controls) FailSetButton(state, viewModel)
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
 * The one control a guided or timed set that is UNDER WAY offers before it is
 * complete (#186).
 *
 * The effort grid is not drawn yet, because how the set went is not a fact
 * until the set is over -- the owner's "earlier than that the only option
 * available should be fail". So once the set is running this is the whole
 * exit, and it stores the lifter's own TAPPED failure: [SetRating] with no
 * RPE and `failed = true`, the same value the grid's failure tile writes.
 *
 * WHAT IT COSTS, and it is not hidden from the lifter: every early exit on a
 * set that is UNDER WAY now records a failure, including the ones that are not
 * -- a rack taken, a cramp. The caption says the set is rateable afterwards,
 * because that is the half that keeps #137 from coming back: the rest screen's
 * row for a Fail-ended set reads EFFORT -- FAILED and carries a Change action,
 * and re-rating overwrites the tapped verdict. (EFFORT -- NOT RATED with a
 * Rate action is the OTHER row, for a set carrying no verdict at all, such as
 * an auto-ended hold; both open the same grid.)
 *
 * This control is not drawn during the set's lead-in. A set whose clock or
 * cadence had not begun has not failed at anything, so that window offers
 * [EndSetEarlyButton] instead (#186).
 *
 * Deliberately not the shape of [EndSetEarlyButton]. That one ends a set with
 * no verdict at all and is the SKIP beside a grid; this one is the only way
 * out of a set that is already running, and it states a verdict, so it is a
 * filled button rather than an outlined afterthought.
 */
@Composable
private fun FailSetButton(state: RecordState, viewModel: RecordViewModel) {
    // The failure wording of the set's own ladder, so the button and the tile
    // that replaces it after completion say the same word. A hold breaks
    // early; a snatch is missed.
    //
    // Named, never counted to. `.last()` was right only because EffortScale
    // happens to append the failure tile last, and that order is pinned in a
    // different module: reordering the tiles there would put a headroom
    // caption on a red destructive button and nothing in `:app` would catch
    // it.
    val label =
        rpeOptions(state.currentIsTimed, currentKind(state) == ExerciseKind.EXPLOSIVE, state.weightUnit)
            .first { it.failed }.description
    Button(
        onClick = { viewModel.endSet(SetRating(null, failed = true)) },
        modifier = Modifier.fillMaxWidth().height(64.dp),
        colors = ButtonDefaults.buttonColors(containerColor = BarColors.Red),
    ) {
        Text(label.uppercase(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
    Spacer(Modifier.height(6.dp))
    SectionCaption("How that set felt is asked once it is finished · you can still rate this one while you rest")
}

/**
 * Ends the set with NO rating attached. Beside the effort grid it is the SKIP
 * rather than the only way out: a lifter walking away mid-set must not be made
 * to rate the set before it will end, so absence stays one tap away. During a
 * guided or timed set's lead-in it is drawn alone and IS the only way out
 * (#186) -- a set that has not begun has not failed at anything.
 *
 * It does not tap the failure, and where a grid is drawn above it that grid
 * withholds the "failed the set" tile for the same reason. A shortfall is
 * DERIVED at the write, so it can be re-derived -- correcting a miscounted
 * rep total on the rest screen clears
 * it. A TAPPED failure is one no later REP CORRECTION can clear: `correctReps`
 * re-derives the derived half and leaves the tapped half standing. Re-rating
 * the set does overwrite it, so it is not permanent -- it is out of reach of
 * the one repair that fits the mistake. A lifter who did all five reps but only
 * tapped "+1 REP" three times reaches exactly this control, and a tapped
 * verdict here would defeat correcting the count, which is what they would
 * actually go and do.
 */
@Composable
internal fun EndSetEarlyButton(viewModel: RecordViewModel) {
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
        rpeOptions(state.currentIsTimed, currentKind(state) == ExerciseKind.EXPLOSIVE, state.weightUnit)
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
                    viewModel.endSet(SetRating(option.rpe, failed = option.failed))
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
 * After the last planned set, [StartNextSetButton] draws nothing in this row
 * and [SessionCloseControls] becomes the filled, primary control below instead
 * (#195); adding a set through [AddSetSection] brings START back to this row,
 * since there is a slot to run again.
 * Almost everything about the set that had just finished -- the effort line,
 * the reason row, the warm-up row, the rep- and hold-correction rows and a
 * rep-quality card carrying a 64dp chart -- sits below the next-set block, in
 * [LastSetDetail]. The one exception is the reason page the app opens BY
 * ITSELF, drawn here under the header: the screen scrolls to 0 on entering
 * RESTING, so a question drawn below the fold is a question the lifter starts
 * the next set without seeing. See [SetLimiterPagePlacement]. The field report for v0.1.37 is
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
internal fun RestingStage(state: RecordState, viewModel: RecordViewModel) {
    // Both keyed on setsCompleted, as `changingEffort` is: an open page must
    // close when the next set ends rather than carrying a stale set's answer
    // into the following rest.
    //
    // Hoisted to the STAGE rather than kept beside the row it used to sit in,
    // because the page has two places it can be drawn -- see
    // [SetLimiterPagePlacement] -- and a copy of `dismissed` in each would be
    // two answers to one question.
    //
    // `dismissed` is not in RecordState, and the distinction is the one #189
    // turns on: it is not a fact about the set, it is whether this screen has
    // already offered the page. A skip stores nothing -- absence is already
    // what the row carries -- so there is nothing for the record to remember,
    // and the row stays reachable either way because
    // [SetLimiterPolicy.offersCorrection] never reads it.
    var dismissed by remember(state.setsCompleted) { mutableStateOf(false) }
    var changing by remember(state.setsCompleted) { mutableStateOf(false) }
    val timed = state.lastFeedback?.actualDurationS != null
    val placement =
        if (state.lastFeedback != null &&
            SetLimiterPolicy.offersCorrection(state.lastSetFailed, state.lastSetLimiter)
        ) {
            SetLimiterPolicy.placement(
                failed = state.lastSetFailed,
                limiter = state.lastSetLimiter,
                dismissed = dismissed,
                changing = changing,
            )
        } else {
            SetLimiterPagePlacement.NONE
        }
    RestHeader(state, viewModel)
    Spacer(Modifier.height(6.dp))
    // Drawn HERE, above everything but the header, because the screen
    // scrolls to 0 on entering RESTING. A question below the fold is a
    // question the lifter starts the next set without seeing, and starting
    // the next set clears the answer it was asking for. Drawing it here
    // also keeps it from inserting itself between the lifter and a control
    // they are already tapping, which is the stacked-target hazard #137
    // removed elsewhere on this screen: a rep correction crossing the
    // planned count can flip the set to failed mid-rest, and eight tiles
    // appearing directly above the +/- under the finger would store a
    // reason nobody gave.
    //
    // What it does NOT do is stop the insertion reflowing the column: this is
    // one verticalScroll Column, so a page opening above the fold still moves
    // every control below it while the lifter's finger is on one. The mistap
    // that follows lands on a neighbouring row rather than on a reason tile,
    // so it can no longer store an answer nobody gave. Reasoned from Compose's
    // scroll contract, not measured -- the bench performed no rep correction
    // across the planned count.
    if (placement == SetLimiterPagePlacement.PROMPT) {
        LimiterPage(state, timed, viewModel, onSkip = { dismissed = true }) { changing = false }
        Spacer(Modifier.height(10.dp))
    }
    // Sets two onwards start from here, not from READY, and this is the screen
    // where the lifter has a rest period to spend fixing it.
    PermissionBanner(demoMode = state.demoMode)
    // Same reason as the banner above it, for the sensor rather than the
    // permission: READY renders at most once per session, so a card drawn only
    // there names a silent unit before set one and never again. Every set from
    // the second onwards is armed from this screen, and the rest period is the
    // window in which switching a unit on or re-seating it still costs the
    // lifter nothing.
    ArmedSilenceCard(state)
    NextSetBlock(state, viewModel)
    // Directly after the card it changes, and above SessionCloseControls,
    // which keeps its distance from START for #137's reason. Four of its
    // inputs are written by rest-screen controls, and they are not all on the
    // same side of it: three are BELOW, inside LastSetDetail -- the warm-up
    // toggle, the effort re-rating, and the rep and duration corrections --
    // while the fourth, AddSetSection, is ABOVE, inside the NextSetBlock call
    // on the line before this one. So this row can appear or vanish part-way
    // through a rest and shift what is under it, and an append reflows what
    // is under the row rather than moving the control that was tapped.
    // Whether that reaches #137's stacked-target hazard is unmeasured and is
    // a [Field] question. Decides nothing itself; see [NextSetNudgeSection].
    NextSetNudgeSection(state, viewModel)
    SessionCloseControls(state, viewModel)
    Spacer(Modifier.height(16.dp))
    LastSetDetail(
        state = state,
        viewModel = viewModel,
        placement = placement,
        timed = timed,
        onChangeLimiter = { changing = !changing },
        onSkipLimiter = { dismissed = true },
        onLimiterDone = { changing = false },
    )
}

/** What happens next: the prescription with the changes struck into it, and the way in. */
@Composable
private fun NextSetBlock(state: RecordState, viewModel: RecordViewModel) {
    val next = state.nextSlot
    if (!state.adHoc && next != null) {
        if (next.isExerciseChange) {
            MoveSensorCard(next.exercise.displayName)
        }
        SlotCard(
            next,
            heading =
            if (next.isAddedSet) {
                "Up next · Set ${next.setIndexInExercise + 1} · you added this one"
            } else {
                "Up next · Set ${next.setIndexInExercise + 1} of ${next.setsInExercise}"
            },
            unit = state.weightUnit,
            side = SideChoicePolicy.carriedIntoNextSet(declaredSide = next.side, statedSide = state.statedSide),
            values = cardValues(state, next),
            prep = cardPrep(state, next),
            highlight = true,
            plateLoadKgOverride = state.statedLoadKg,
        )
        // The change is IN the card above, not under it. It has to be
        // readable without a tap, because the box that used to reconcile the
        // plan's number with the lifter's is now behind this button.
        ChangeSetButton(state, viewModel, next, next = true)
        SwitchExerciseSection(state, viewModel)
        AddSetSection(state, viewModel)
        RemoveSetSection(state, viewModel)
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
        // The session's last set is when "that one was too light, give me one
        // more" is likeliest, and until #188 this branch offered nothing: the
        // control sat inside the `next != null` arm only. START is withheld
        // on this screen until a set is appended (#195), and the append is
        // what gives it a slot to run: the queue has a next slot again and
        // START comes back on the same pass.
        AddSetSection(state, viewModel)
        RemoveSetSection(state, viewModel)
    }
}

/**
 * The rest screen's controls, asked once per control that draws one.
 *
 * One projection of [RecordState] onto [RestControlPolicy.restScreen]'s four
 * inputs, here rather than at each call site, so two controls on one screen
 * cannot ask the policy different questions about the same state. `nextSlot`
 * is the slot START would run: null after the last planned set, non-null
 * again the moment the lifter appends one (#188).
 */
private fun restControls(state: RecordState): RestControls = RestControlPolicy.restScreen(
    close = state.sessionClose,
    askedToFinish = state.askingSessionRpe,
    hasNextSlot = state.nextSlot != null,
    adHoc = state.adHoc,
)

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
    if (RestControl.START_NEXT_SET !in restControls(state).controls) return
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
 * exit gate is: no test on the CI path can render a `@Composable`, so a
 * condition written beside its caller cannot be tested at all. This is the same surface the gate covers, one
 * layer in — guarding the way out while leaving these live is not a guard.
 */
@Composable
private fun SessionCloseControls(state: RecordState, viewModel: RecordViewModel) {
    // Both blocks now ask [restControls], which carries askedToFinish for
    // everything: the rating panel still takes the finish control's place, and
    // the START membership it returns is the same the one-argument form gave
    // -- `asking to finish never changes whether a set may be started` pins
    // that equality in :core:model, which is what made the move safe.
    val screen = restControls(state)
    val controls = screen.controls
    if (RestControl.FINISH_SESSION in controls) {
        if (screen.primary == RestControl.FINISH_SESSION) {
            // Filled, because there is no next set to start and finishing is
            // what is left to do. Drawn from the same answer that withheld
            // START, so the two cannot disagree about which one that is.
            Button(
                onClick = viewModel::askSessionRpe,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) { Text("FINISH SESSION", textAlign = TextAlign.Center) }
        } else {
            TextButton(onClick = viewModel::askSessionRpe, modifier = Modifier.fillMaxWidth()) {
                Text("Finish session", color = BarColors.Sub)
            }
        }
    }
    if (RestControl.RATE_SESSION in controls) {
        SessionRpePanel(viewModel)
    }
    if (RestControl.RETRY_FINISH in controls) {
        UnclosedSessionNotice(viewModel)
    }
}

/**
 * How was the whole session? One tap answers it, one tap skips it, and either
 * way the session closes (#159).
 *
 * DELIBERATELY NOT THE EFFORT GRID'S VOCABULARY. That grid says "3 reps left"
 * or "Could have added 10-15 lb" about ONE set on 1-10; this is the whole
 * workout on 1-10 and there are no reps left in a session. The two scales now
 * span the same range, so nothing but the wording keeps them apart. Bare
 * numbers with the two ends labelled, so nothing here can be read as the other
 * scale.
 * The numbers come from [SessionRpe.VALUES] rather than a literal `1..10`, so
 * the control cannot offer a value the stored column and the published schema
 * refuse.
 *
 * Two rows of five rather than one row of ten. At 360dp with font scale 2 a
 * ten-across row gives each number about 32dp of width before padding, which is
 * under the 48dp tap-target floor; five across is roughly 64dp. `heightIn(min =
 * 52.dp)` rather than a fixed height for the reason [StartNextSetButton] uses
 * one: a fixed height clips at large font scales instead of growing.
 *
 * SKIPPABLE WITHOUT FRICTION, which is the owner's rule and is why the skip is
 * a labelled button of its own rather than a dismiss gesture or a corner X. It
 * says what it does -- the session finishes either way -- so a lifter who does
 * not want to rate is not choosing between answering and not finishing.
 *
 * Nothing here has been in front of a lifter. What the panel looks like at
 * 411dp/fs1.0 and 360dp/fs2.0 is bench-harness evidence in this commit's body;
 * whether the wording reads right at the end of a real workout is a [Field]
 * item and is not claimed.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRpePanel(viewModel: RecordViewModel) {
    // The whole panel is one bring-into-view target, requested once when it is
    // composed. The rest screen scrolls and the panel is drawn after the
    // next-set block, so at the scroll position a lifter reaches the finish
    // control from, the numbers can be on screen while "Finish without rating"
    // is below the fold. Ten buttons that each write a rating, reachable, with
    // the skip not reachable, is the arrangement this requester exists to stop.
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(Unit) { requester.bringIntoView() }
    Column(modifier = Modifier.fillMaxWidth().bringIntoViewRequester(requester)) {
        SectionCaption("How did the whole session feel?")
        Spacer(Modifier.height(2.dp))
        Text(
            "1 = easy · 10 = all you had",
            style = MaterialTheme.typography.bodySmall,
            color = BarColors.Sub,
        )
        Spacer(Modifier.height(8.dp))
        SessionRpe.VALUES.chunked(5).forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            ) {
                row.forEach { rating ->
                    OutlinedButton(
                        onClick = { viewModel.finishSession(rating) },
                        modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp),
                    ) {
                        Text("$rating", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    }
                }
            }
        }
        // Null at the call site, not a default on the ViewModel: an unrated
        // session is a decision the lifter made here, and it is recorded as an
        // absence rather than as a number nobody said.
        TextButton(onClick = { viewModel.finishSession(null) }, modifier = Modifier.fillMaxWidth()) {
            Text("Finish without rating", color = BarColors.Sub)
        }
    }
}

/**
 * The session did not close: either the end time and the heart-rate and HRV
 * summary never landed, or that write landed and only the rest recorded after
 * the last set did not (#109). Every set is already stored, so what is at risk
 * here is smaller than an unsaved set and is not nothing — whichever half is
 * still missing is held in memory here and in no durable place at all.
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
    SectionCaption("Your sets are saved. Part of the session's own record is not")
    Spacer(Modifier.height(10.dp))
    Button(
        onClick = viewModel::retrySessionClose,
        modifier = Modifier.fillMaxWidth().height(64.dp),
    ) {
        Text("FINISH SESSION AGAIN", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

/** One tile of the effort grid: what gets stored plus the gym-facing wording. */
internal data class RpeOption(
    val rpe: Int?,
    val failed: Boolean,
    val description: String,
    val color: Color,
)

/**
 * The tiles of the effort grid, with the colour this screen paints them.
 *
 * WHAT IS DRAWN IS [EffortScale]'S DECISION, not this file's. Which rungs
 * exist, what each says and which `rpe` it stores are pinned in `:core:model`
 * where a test runs on them every push; no test on the CI path reaches a
 * composable. This function is the paint: an [EffortTile] carries
 * no colour, because a colour is not a fact about effort.
 *
 * [unit] reaches the scale because the low rungs name a WEIGHT the lifter
 * could have added, and that figure is authored per unit rather than
 * converted -- "+10-15 lb" and "+5 kg", never "+4.5 kg".
 */
internal fun rpeOptions(timed: Boolean, explosive: Boolean, unit: WeightUnit): List<RpeOption> =
    EffortScale.tiles(timed = timed, explosive = explosive, unit = unit).map { tile ->
        RpeOption(
            rpe = tile.rpe,
            failed = tile.claim == EffortClaim.FAILED,
            description = tile.label,
            color =
            when (tile.claim) {
                EffortClaim.FAILED -> BarColors.Red
                EffortClaim.HEADROOM -> BarColors.Blue
                EffortClaim.PROXIMITY -> rpeColor(tile.rpe!!)
            },
        )
    }

/**
 * Colour for a proximity rung only.
 *
 * The headroom rungs take [BarColors.Blue] at the call site rather than an
 * extension of this ramp: they are not a lighter shade of "close to failure",
 * they are a different question, and the colour says so before the words are
 * read.
 */
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
 * -- and it lives in `:core:model` where a test runs on it every push; no test
 * on the CI path reaches a Compose screen.
 */
@Composable
internal fun RpeSelector(state: RecordState, viewModel: RecordViewModel, onPicked: () -> Unit) {
    val feedback = state.lastFeedback
    val options =
        rpeOptions(
            timed = feedback?.actualDurationS != null,
            explosive = feedback?.explosive == true,
            unit = state.weightUnit,
        )
    // lastSetFailed is the OR of both facts, so the derived one is recovered by
    // subtracting the tap. Where BOTH are true this hands the policy false for
    // derivedFailed, which is a value the policy cannot act on differently: its
    // only use of the argument is `derivedFailed && !tappedFailed`, false in
    // that case either way.
    val selection =
        EffortCorrectionPolicy.selection(
            rpe = state.lastSetRpe,
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
                        option.failed -> selection.failed
                        else -> selection.rpe != null && option.rpe == selection.rpe
                    }
                RpeTile(option, selected, modifier = Modifier.weight(1f)) {
                    viewModel.rateLastSet(option.rpe, failed = option.failed)
                    onPicked()
                }
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

/** What the rest screen says the set ended for, and the way back into it. */
@Composable
internal fun LimiterLine(state: RecordState, timed: Boolean, onChange: () -> Unit) {
    // The wording is SetLimiterPolicy's, including the named absence: a blank
    // here would read as the app having lost the answer rather than as a
    // question nobody has answered.
    val text = SetLimiterPolicy.lineText(state.lastSetLimiter, state.lastSetLimiterNote, timed)
    val unanswered = state.lastSetLimiter == null
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        SectionCaption(
            "Ended · $text",
            // Amber for an unanswered failure, as the unrated effort line is:
            // nothing is wrong, but there is something the lifter can do and
            // the rest period is the only window it can be done in.
            color = if (unanswered) BarColors.Amber else BarColors.Sub,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onChange) {
            Text(if (unanswered) "Say why" else "Change", color = BarColors.Sub)
        }
    }
    Spacer(Modifier.height(4.dp))
}

/**
 * The tiles themselves, grouped, with pain drawn apart from the performance
 * answers.
 *
 * IT IS A PAGE OF TILES, NOT A FORM. The lifter is standing over a dropped
 * bar. One tap answers and closes it. The free text is reachable ONLY behind
 * Other, so the ordinary answer never costs a keyboard.
 *
 * THE FOOT DEPENDS ON WHETHER AN ANSWER STANDS, which is
 * [SetLimiterPolicy.leavesPageAsSkip]'s decision. With none, leaving is a SKIP
 * and writes nothing at all -- absence is already what the row carries. With
 * one, leaving is a CLEAR and writes null, because a button captioned
 * "records no reason" over a stored answer describes something the app does
 * not do: the answer would stay in the row and stay in the export, and a
 * lifter who tapped to retract a mark would have the mark survive them.
 *
 * WHICH TILES EXIST AND WHICH GROUP EACH IS IN IS [SetLimiterScale]'S
 * DECISION. This function is the paint. The grouping is read off the tiles
 * rather than counted to by index, because an order counted to here is an
 * order that breaks silently when the scale is reordered one module over --
 * which is the exact trap [FailSetButton] already carries a comment about.
 */
@Composable
internal fun LimiterPage(
    state: RecordState,
    timed: Boolean,
    viewModel: RecordViewModel,
    onSkip: () -> Unit,
    onDone: () -> Unit,
) {
    var typing by remember(state.setsCompleted) { mutableStateOf(false) }
    // Seeded from what is STORED, not from "". Reopening this page over an
    // answer the lifter is correcting must show them the words they are
    // correcting: an empty box over a stored note is a note one SAVE away from
    // being replaced with nothing, and it exists in no other artifact.
    var words by
        remember(state.setsCompleted) { mutableStateOf(state.lastSetLimiterNote ?: "") }
    SectionCaption("Why did that set end? · optional")
    Spacer(Modifier.height(6.dp))
    if (typing) {
        LimiterWords(
            words = words,
            onWords = { words = it },
            onSave = {
                viewModel.limitLastSet(SetLimiter.OTHER, words)
                typing = false
                onDone()
            },
            onBack = { typing = false },
        )
        return
    }
    var group: SetLimiterGroup? = null
    for (tile in SetLimiterScale.tiles(timed)) {
        // A gap between groups, so pain is drawn apart from the performance
        // answers -- which #189 asks for in as many words, because a coach
        // scanning an export must not have to read carefully to notice it.
        if (group != null && tile.group != group) Spacer(Modifier.height(10.dp))
        group = tile.group
        LimiterTile(tile) {
            if (tile.limiter == SetLimiter.OTHER) {
                typing = true
            } else {
                viewModel.limitLastSet(tile.limiter)
                onDone()
            }
        }
    }
    Spacer(Modifier.height(2.dp))
    if (SetLimiterPolicy.leavesPageAsSkip(state.lastSetLimiter)) {
        OutlinedButton(
            onClick = {
                onSkip()
                onDone()
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("SKIP", color = BarColors.Sub) }
        Spacer(Modifier.height(6.dp))
        SectionCaption("Skipping records no reason · you can still say why while you rest")
    } else {
        // Dismissed as well as cleared. Without it the set is failed and
        // unanswered again the instant the write lands, so the page the lifter
        // just left would reopen at the top of the screen and ask again. The
        // row stays, with SAY WHY on it, because offersCorrection never reads
        // the dismissal.
        OutlinedButton(
            onClick = {
                viewModel.limitLastSet(null)
                onSkip()
                onDone()
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("CLEAR", color = BarColors.Sub) }
        Spacer(Modifier.height(6.dp))
        SectionCaption("Clearing removes the reason from this set")
    }
}

/**
 * The free-text box, reachable only behind Other.
 *
 * The field and the write apply different transforms, for a reason that is
 * not tidiness: this box is value-driven, so whatever it applies is applied
 * to every PREFIX of the note in turn, and a rule that trims would delete
 * each space at the moment it is typed.
 *
 * A character dropped at save is a character the lifter believes they
 * recorded, and the characters this drops are not cosmetic: the manifest is
 * assembled as text and escapes nothing.
 */
@Composable
private fun LimiterWords(words: String, onWords: (String) -> Unit, onSave: () -> Unit, onBack: () -> Unit) {
    OutlinedTextField(
        value = words,
        onValueChange = { onWords(SetLimiter.sanitizeForTyping(it)) },
        label = { Text("In your words") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Button(onClick = onSave, modifier = Modifier.weight(1f)) { Text("SAVE") }
        OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("BACK") }
    }
}

/** One reason tile. Welfare answers are drawn in red; the rest are not. */
@Composable
private fun LimiterTile(tile: SetLimiterTile, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    // A colour is not a fact about a reason, so it is chosen here rather than
    // carried on the tile -- but WHICH answers are set apart is carried, as
    // [SetLimiterGroup], because that is a fact about the answer.
    val color =
        when (tile.group) {
            SetLimiterGroup.WELFARE -> BarColors.Red
            SetLimiterGroup.CONTEXT -> BarColors.Sub
            else -> BarColors.Text
        }
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(shape)
            .background(BarColors.Surface, shape)
            .border(1.dp, if (tile.group == SetLimiterGroup.WELFARE) BarColors.Red else BarColors.Track, shape)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 12.dp),
    ) {
        Text(tile.label, style = MaterialTheme.typography.titleSmall, color = color)
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
internal fun RestHeader(state: RecordState, viewModel: RecordViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        val total = state.restTotalS.takeIf { it > 0 } ?: 1
        ProgressRing(
            progress = state.restRemainingS / total.toFloat(),
            diameter = 110.dp,
            strokeWidth = 9.dp,
        ) {
            // Words at zero, digits above it. Since #172 a rest can already be
            // over when this screen first draws -- the period runs from the
            // instant the set ended, so a long rating stop can spend all of it
            // -- and "0:00" then reads as a countdown that has not started
            // rather than one that has finished. It says the same thing at the
            // end of an ordinary period, where it is also what the lifter
            // wants to know. Nothing is SPOKEN here that was not spoken
            // before: the voice cue for the end of the rest is the countdown
            // loop's, and a period seeded at zero never enters that loop.
            if (state.restRemainingS == 0) {
                Text("REST OVER", style = MaterialTheme.typography.titleMedium, maxLines = 1)
            } else {
                Text(formatMmSs(state.restRemainingS), style = MaterialTheme.typography.headlineMedium)
            }
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
                // effectiveLoadKg / effectiveAddedKg, not loadKg / addedKg:
                // a load corrected on this screen (#205) moves this line the
                // way a corrected rep count and a corrected hold already move
                // the ones below it. Both come off the same correction, so the
                // total and the split cannot disagree about which set they are
                // describing.
                val loadText =
                    feedback.effectiveLoadKg.takeIf { it > 0 }?.let { state.weightUnit.format(it) } ?: "BW"
                // The ADDED load, NEVER the total. feedback.effectiveLoadKg is
                // SetLoadPolicy.totalKg -- the lifter's own mass included on
                // body-weight work -- so halving it would read "2 x 50 kg" for
                // a 20 kg weighted dip at 80 kg body weight. The total on
                // screen stays the total; only the split comes off the added
                // load.
                val split =
                    ImplementLoad.decomposition(
                        feedback.effectiveAddedKg,
                        feedback.implementCount,
                        state.weightUnit,
                    )
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
                        // effectiveDurationS, not actualDurationS: a hold
                        // corrected on this screen moves this line the way the
                        // rep branch below already moves for a rep correction.
                        feedback.effectiveDurationS?.let { "$name ${it}s @ $loadText" }
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
        // What the tempo ratio in the pills above does not cover, when it does
        // not cover everything the set prescribed. #56.
        TempoCoverageNote(feedback.analysis)
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
        // effectiveDurationS, not actualDurationS: this chip's tone is the
        // shortfall signal, and a hold corrected up past its target left it
        // red against a record that says the target was met.
        feedback.effectiveDurationS?.let { actual ->
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
        // The wording, the tick and the tone are TempoScoreLabel's in
        // :core:model, so this screen and the history screen cannot drift and
        // the decision is reachable by a test. #56.
        tempoScoreOf(analysis)?.let { VerdictChip(it.text, it.tone.chipTone()) }
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
internal fun PeakVelocityChart(analysis: SetAnalysis) {
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
internal fun EccTempoChart(analysis: SetAnalysis, targetEccS: Double) {
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
    // that it is reachable by a test. No test on the CI path reaches this file.
    val insight = CoachingRules.eccentricTempoInsight(analysis.reps, targetEccS, TEMPO_TOLERANCE_S)
    Text(insight, style = MaterialTheme.typography.bodySmall, color = BarColors.Sub)
    analysis.verdicts.take(2).forEach {
        Text("• $it", style = MaterialTheme.typography.bodySmall, color = BarColors.Sub)
    }
}

@Composable
internal fun ConVelocityChart(analysis: SetAnalysis) {
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
    /**
     * The side the set will WORK -- the lifter's choice where they made one,
     * the plan's prescription otherwise -- and never `slot.side`, which on the
     * rest screen is the unbaked declaration. Passed in rather than resolved
     * here so the arrow and the word beside it come from one reading (#215).
     */
    side: String?,
    values: List<SetCardValue>,
    prep: SetCardValue?,
    highlight: Boolean = false,
    plateLoadKgOverride: Double? = null,
) {
    val shape = RoundedCornerShape(16.dp)
    val border =
        if (highlight) Modifier.border(1.dp, BarColors.Volt.copy(alpha = 0.25f), shape) else Modifier
    Card(Modifier.fillMaxWidth().then(border), shape = shape) {
        Column(Modifier.padding(14.dp)) {
            SectionCaption(heading, color = if (highlight) BarColors.Volt else BarColors.Sub)
            // The values, and the plan's figures struck through wherever
            // the lifter has changed them (#204). What each figure SAYS is
            // [SetCardValues.of]'s -- including the body-weight notation it
            // keeps on both sides of a strike, and including the stated zero
            // that a truthiness guard would drop on the floor -- and this
            // draws it. The line wraps rather than truncating: a struck pair
            // is roughly twice the width of the figure alone, and at 360dp
            // with font scale 2 there is not room for both on one line.
            //
            // #202 needs the PREVIEW to say this same set the same way, and
            // two copies of one vocabulary drift. So the base text -- the
            // words and figures with nothing struck -- has ONE source, and
            // [SessionPreviewPolicy.setLine] is that source rendered plain;
            // see its KDoc for which way the delegation runs. The strike is
            // drawn on top of that base, here and only here, because the
            // preview draws sets no lifter has deviated from yet.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 4.dp),
            ) {
                SideArrow(side, Modifier.padding(end = 8.dp))
                Text(
                    struckLine("${slot.exercise.displayName} — ", values),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            // Prep joins the rest clock here, and only when it deviates.
            // It is the one change with no figure of its own on the card --
            // the card states what the set IS, and the seconds before it
            // starts are not part of that -- so it is drawn beside the other
            // duration rather than given a line of its own, which is the line
            // #204 removes.
            val secondary =
                listOfNotNull(
                    slot.targetMeanConVelMps?.let { SetCardValue(stated = "target ${trim(it)} m/s") },
                    slot.velocityLossStopPct?.let { SetCardValue(stated = "stop at −${trim(it)}% vel") },
                    slot.restS?.let { SetCardValue(stated = "rest ${formatMmSs(it)}") },
                    prep,
                )
            if (secondary.isNotEmpty()) {
                Text(
                    struckLine("", secondary),
                    style = MaterialTheme.typography.bodySmall,
                    color = BarColors.Sub,
                )
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
