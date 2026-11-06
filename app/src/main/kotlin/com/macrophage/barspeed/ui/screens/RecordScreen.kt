package com.macrophage.barspeed.ui.screens

import android.os.SystemClock
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.macrophage.barspeed.dsp.AccelArtefact
import com.macrophage.barspeed.dsp.CoachingRules
import com.macrophage.barspeed.dsp.SetAnalysis
import com.macrophage.barspeed.dsp.VelocityLoss
import com.macrophage.barspeed.model.AdHocPicker
import com.macrophage.barspeed.model.AddSetControl
import com.macrophage.barspeed.model.ArmedSilencePolicy
import com.macrophage.barspeed.model.BlePermissionStep
import com.macrophage.barspeed.model.BodyWeightPromptPolicy
import com.macrophage.barspeed.model.BodyweightLoadDisplay
import com.macrophage.barspeed.model.ConnectionState
import com.macrophage.barspeed.model.CountingPolicy
import com.macrophage.barspeed.model.DualSensorSetup
import com.macrophage.barspeed.model.EffortAsk
import com.macrophage.barspeed.model.EffortClaim
import com.macrophage.barspeed.model.EffortGridSlipGuard
import com.macrophage.barspeed.model.EffortScale
import com.macrophage.barspeed.model.ExerciseDef
import com.macrophage.barspeed.model.ExerciseKind
import com.macrophage.barspeed.model.ExitAction
import com.macrophage.barspeed.model.ExitPrompt
import com.macrophage.barspeed.model.ForegroundStartFailure
import com.macrophage.barspeed.model.GuidedRepCaption
import com.macrophage.barspeed.model.HistoryTarget
import com.macrophage.barspeed.model.Implement
import com.macrophage.barspeed.model.ImplementLoad
import com.macrophage.barspeed.model.LatestSession
import com.macrophage.barspeed.model.LeadInPolicy
import com.macrophage.barspeed.model.LiveCountReadout
import com.macrophage.barspeed.model.NextSetInputs
import com.macrophage.barspeed.model.Phase
import com.macrophage.barspeed.model.PlanSessionDef
import com.macrophage.barspeed.model.PlanValueCaption
import com.macrophage.barspeed.model.PreviewBlock
import com.macrophage.barspeed.model.RecordExitPolicy
import com.macrophage.barspeed.model.RemoveSetControl
import com.macrophage.barspeed.model.RepCallNotePolicy
import com.macrophage.barspeed.model.RestControl
import com.macrophage.barspeed.model.RestControlPolicy
import com.macrophage.barspeed.model.RestControls
import com.macrophage.barspeed.model.ScreenOnPolicy
import com.macrophage.barspeed.model.SensorAdvice
import com.macrophage.barspeed.model.SensorAdvicePolicy
import com.macrophage.barspeed.model.SensorCapturePolicy
import com.macrophage.barspeed.model.SensorLossPolicy
import com.macrophage.barspeed.model.SensorRoster
import com.macrophage.barspeed.model.SessionFinishedCopy
import com.macrophage.barspeed.model.SessionNotClosedCopy
import com.macrophage.barspeed.model.SessionPreview
import com.macrophage.barspeed.model.SessionPreviewPolicy
import com.macrophage.barspeed.model.SessionResumePolicy
import com.macrophage.barspeed.model.SessionRpe
import com.macrophage.barspeed.model.SetCardValue
import com.macrophage.barspeed.model.SetCardValues
import com.macrophage.barspeed.model.SetEndControl
import com.macrophage.barspeed.model.SetEndControlPolicy
import com.macrophage.barspeed.model.SetEndKind
import com.macrophage.barspeed.model.SetGeometryPolicy
import com.macrophage.barspeed.model.SetLimiter
import com.macrophage.barspeed.model.SetLimiterGroup
import com.macrophage.barspeed.model.SetLimiterPagePlacement
import com.macrophage.barspeed.model.SetLimiterPolicy
import com.macrophage.barspeed.model.SetLimiterScale
import com.macrophage.barspeed.model.SetLimiterTile
import com.macrophage.barspeed.model.SetLoadPolicy
import com.macrophage.barspeed.model.SetWriteState
import com.macrophage.barspeed.model.SideChoicePolicy
import com.macrophage.barspeed.model.SkipSetControl
import com.macrophage.barspeed.model.Stage
import com.macrophage.barspeed.model.StartCue
import com.macrophage.barspeed.model.StartCuePolicy
import com.macrophage.barspeed.model.Tempo
import com.macrophage.barspeed.model.TempoAdjustPolicy
import com.macrophage.barspeed.model.TempoDigit
import com.macrophage.barspeed.model.TimedSetEndPolicy
import com.macrophage.barspeed.model.UnwrittenClose
import com.macrophage.barspeed.model.VoiceCueControl
import com.macrophage.barspeed.model.WeightUnit
import com.macrophage.barspeed.record.PlannedSlot
import com.macrophage.barspeed.record.RecordEntry
import com.macrophage.barspeed.record.RecordState
import com.macrophage.barspeed.record.RecordViewModel
import com.macrophage.barspeed.record.SetFeedback
import com.macrophage.barspeed.record.SetRating
import com.macrophage.barspeed.record.adHocLoadRefused
import com.macrophage.barspeed.record.applyHomeEntry
import com.macrophage.barspeed.record.cardInstruction
import com.macrophage.barspeed.record.nextSetSlot
import com.macrophage.barspeed.record.nextSetStatements
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
import com.macrophage.barspeed.ui.components.VoiceWarningBanner
import com.macrophage.barspeed.ui.components.confirmTap
import com.macrophage.barspeed.ui.components.rememberArmedDelivery
import com.macrophage.barspeed.ui.components.velocityLossColor
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val DEFAULT_VELOCITY_LOSS_STOP_PCT = 20.0
private const val VEL_LOSS_OK_PCT = 10.0
private const val TEMPO_TOLERANCE_S = 0.5

/**
 * The keyboards Record's number boxes open (UX review 2026-11-06,
 * settings-onboarding F5, record-setup F4): a decimal pad for a load or a body
 * weight, a digit pad for reps and hold seconds, and Done on both so the
 * keyboard can be closed without system Back. They opened the full text
 * keyboard, which costs a "?123" tap per entry with chalked thumbs.
 */
private val DecimalKeys = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done)
private val WholeNumberKeys = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordScreen(
    navController: NavController,
    entry: RecordEntry? = null,
    planSession: String? = null,
    viewModel: RecordViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    // Home's hero said what this visit is for; the offer and the plan both
    // arrive after entry, so this re-asks as each lands. See homeEntryStep.
    // Saveable, so a rotation does not act twice; after a process death the
    // entry is spent and the dialog asks as on a plain entry.
    var entryDone by rememberSaveable { mutableStateOf(entry == null) }
    LaunchedEffect(state.resumeOffer, state.resumeChecked, state.planSessions, state.stage) {
        if (!entryDone) entryDone = viewModel.applyHomeEntry(entry, planSession)
    }
    // CONTINUE from Home answers the dialog itself; drawing it meanwhile
    // would flash a question the lifter has already answered.
    val hideResumeDialog = !entryDone && entry == RecordEntry.CONTINUE

    // The phone sits on a bench through the set and the rest; the system
    // display timeout would blank the readout and the rest clock mid-glance
    // (UX review 2026-11-06, record-live F1). Which stages hold it is
    // ScreenOnPolicy's. The flag is on this screen's View, so leaving Record
    // releases it with the dispose; no wake lock is taken.
    val view = LocalView.current
    val keepOn = ScreenOnPolicy.keepsScreenOn(state.stage)
    DisposableEffect(view, keepOn) {
        view.keepScreenOn = keepOn
        onDispose { view.keepScreenOn = false }
    }

    // Back has two routes out of this screen — the top bar's button and the
    // system gesture — and the lifter uses whichever is nearer the thumb, so a
    // guard on one is not a guard. Both ask [RecordExitPolicy] the same
    // question about the same stage, which is what stops them drifting apart.
    // Told whether CANCEL SET could still take the set back, so Discard on a
    // set with no rep counted stays on Record (UX review 2026-11-06,
    // record-live F6) and a set with reps is discarded by leaving, as before.
    val prompt = RecordExitPolicy.promptFor(state.stage, state.setWrite, state.sessionClose, state.mayCancelSet)
    // Whether the gate is open, not which gate it is. Storing the prompt here
    // latched the policy's answer at the moment Back was pressed, and the answer
    // moves underneath it: Back during a set write raises SET_SAVING, which says
    // the set "will finish saving even if you leave"; if that write then fails,
    // the policy switches to SET_UNSAVED, whose actions do not include leaving
    // the session open at all. A latched copy kept drawing the old wording AND
    // the old buttons, so the lifter left on a promise that had stopped being
    // true and was never offered the retry that still had the set in memory.
    // Reading the live value means the dialog cannot describe a state the app is
    // no longer in. Since the record-live F6 review it does not redraw in place
    // either: the dialog remembers which prompt it was opened for and CLOSES
    // when the live one changes (RecordExitPolicy.dialogFor), so no button
    // turns into another under a moving thumb. Back asks the new question.
    var exitOpenFor by remember { mutableStateOf<ExitPrompt?>(null) }
    LaunchedEffect(prompt) { if (exitOpenFor != prompt) exitOpenFor = null }
    val onExitAction: (ExitAction) -> Unit = { action ->
        exitOpenFor = null
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
            // The landed CANCEL SET path: nothing written, the journal
            // discarded, READY or the rest screen back with the same set next.
            // cancelSet itself refuses if the first rep landed after the
            // dialog opened; the prompt is live, so it would already read
            // SET_IN_PROGRESS by then.
            ExitAction.DISCARD_SET_AND_STAY -> viewModel.cancelSet()
            ExitAction.DISCARD_SET_AND_LEAVE,
            ExitAction.LEAVE_SESSION_OPEN,
            // Same navigation, a different promise. The close runs on a scope
            // this pop cannot cancel, so leaving is safe and the session
            // finishes either way; only the label the lifter read differs.
            ExitAction.LEAVE_SESSION_CLOSING,
            // The session is closed; only the rest window after its last set
            // is lost by leaving (#333). The same pop, a third promise.
            ExitAction.LEAVE_FINISHED_SESSION,
            -> {
                navController.popBackStack()
            }
        }
    }
    val onBack: () -> Unit = {
        if (prompt == ExitPrompt.NONE) {
            navController.popBackStack()
        } else {
            exitOpenFor = prompt
        }
    }
    BackHandler(enabled = prompt != ExitPrompt.NONE, onBack = onBack)
    // Any change of prompt while the dialog is open closes it, NONE included:
    // NONE is the policy saying nothing is at risk any more.
    ExitDialog(RecordExitPolicy.dialogFor(exitOpenFor, prompt), state.closeUnwritten, onExitAction)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(titleFor(state), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    // Kept a TextButton (#201): the app bar's navigation slot, the same act as the
                    // system back gesture. Leaving mid-set is the loss the exit gate guards, so it
                    // should not invite.
                    TextButton(onClick = onBack) { Text("Back") }
                },
                actions = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(end = 16.dp),
                    ) {
                        // The voice switch, during a set and the rest: the two
                        // stages that had none, so from set 2 on the voice
                        // could not be muted or unmuted (settings-onboarding
                        // F1). SETUP and READY draw it inline with its caption.
                        if (VoiceCueControl.inTopBar(state.stage)) AudioCueChip(state, viewModel)
                        // Two dots only when a second sensor is really in play:
                        // two paired, both labelled, and this set armed for
                        // both. Otherwise the bar would carry a permanently
                        // grey dot for every single-sensor lifter, which reads
                        // as something being broken. Labelled with the ROLE
                        // rather than "IMU"/"IMU B" so the dot that goes amber
                        // names the unit to go and look at -- and the armed
                        // one is not always A (#156). DURING A SET it is the
                        // roster the set was armed with, not the live one
                        // (#171 item 3). The set is stored as what it was
                        // armed with; unlabelling or forgetting B mid-set
                        // still disconnects B's link, so its dot stays and
                        // shows that link down rather than vanishing.
                        val roster = state.barRoster
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
            // Above every stage, not inside one: the refusal lands at
            // beginSet, so the set it leaves unprotected is IN_SET by the
            // time the service reports, and the rest after it is no safer.
            state.foregroundFailure?.let { ForegroundRefusalBanner(it) }
            VoiceWarningBanner(state.voiceStatus, state.mediaVolumeZero, state.audioCues || state.guidedSet)
            when (state.stage) {
                Stage.SETUP -> SetupStage(state, viewModel, hideResumeDialog)
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
private fun ExitDialog(prompt: ExitPrompt, unwritten: UnwrittenClose?, onAction: (ExitAction) -> Unit) {
    if (prompt == ExitPrompt.NONE) return
    val leaves = prompt.actions.filter { it != ExitAction.STAY }
    val single = leaves.singleOrNull()
    if (single != null) {
        AlertDialog(
            onDismissRequest = { onAction(ExitAction.STAY) },
            title = { Text(exitTitle(prompt, unwritten)) },
            text = {
                Text(exitBody(prompt, unwritten), style = MaterialTheme.typography.bodySmall, color = BarColors.Sub)
            },
            confirmButton = { ExitButton(prompt, single, onAction) },
            dismissButton = { ExitButton(prompt, ExitAction.STAY, onAction) },
        )
    } else {
        AlertDialog(
            onDismissRequest = { onAction(ExitAction.STAY) },
            title = { Text(exitTitle(prompt, unwritten)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(exitBody(prompt, unwritten), style = MaterialTheme.typography.bodySmall, color = BarColors.Sub)
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
    // Kept a TextButton (#201): every use is an exit dialog's action, and that dialog exists to
    // make leaving deliberate.
    TextButton(onClick = { onAction(action) }, modifier = modifier) {
        Text(exitLabel(prompt, action), color = exitColor(action))
    }
}

/**
 * What a failed close is taken to have left unwritten when the state carries
 * no answer: the larger loss, so the prompt never undersells it. Unreachable
 * in practice -- `SessionCloser` reports the two together -- and named rather
 * than left as a `!!`.
 */
private val UNKNOWN_UNWRITTEN =
    UnwrittenClose(sessionRow = true, sessionRating = true, skippedSets = true, finalRest = true)

private fun exitTitle(prompt: ExitPrompt, unwritten: UnwrittenClose?): String = when (prompt) {
    ExitPrompt.NONE -> ""
    ExitPrompt.SET_IN_PROGRESS -> "Discard this set?"
    ExitPrompt.SET_NOT_STARTED -> "Discard this set?"
    ExitPrompt.SESSION_OPEN -> "Leave the session?"
    ExitPrompt.SET_SAVING -> "This set is still saving"
    ExitPrompt.SET_UNSAVED -> "This set did not save"
    ExitPrompt.SESSION_CLOSING -> "Finishing this session"
    // Worded by cause, in :core:model (#333).
    ExitPrompt.SESSION_NOT_CLOSED,
    ExitPrompt.FINAL_REST_UNSAVED,
    -> SessionNotClosedCopy.title(unwritten ?: UNKNOWN_UNWRITTEN)
}

/**
 * The exit prompt's body for [prompt]. `internal` rather than private so
 * `ShortOfTargetCopyTest` in `app/src/test` can read the words without a
 * composable; nothing else here changes.
 */
internal fun exitBody(prompt: ExitPrompt, unwritten: UnwrittenClose?): String = when (prompt) {
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
            "To keep it, tap Keep recording and end the set with its end control on this screen. " +
            "On a tempo-guided or timed set that is under way that control ends the set short of target, and " +
            "you can still rate the set while you rest."
    // The discard that stays (record-live F6): CANCEL SET's path. "Nothing has
    // been recorded" rather than "no rep counted", because a hold or a carry
    // in its lead-in reaches this prompt too.
    ExitPrompt.SET_NOT_STARTED ->
        "Nothing has been recorded yet. Discarding saves nothing and keeps you here, with this same set next."
    // Says the session can be picked up again, and for how long, because as
    // of #48 it can: coming back to Record asks "Continue this session?" while
    // SessionResumePolicy.offer would (an open row with sets, its last set
    // within RESUME_WINDOW_MS). The HRV clause is conditional because a
    // continued session's close withholds it (SessionResumePolicy.closeRrMs).
    ExitPrompt.SESSION_OPEN ->
        "Every completed set is already saved. Finishing writes the session's end time and its heart-rate " +
            "summary, and its HRV unless it was continued from an earlier visit. Leaving without finishing " +
            "leaves the session open: coming back to Record within three hours of your last set offers to " +
            "continue it at your next set, and after that it stays open with no end time."
    // Now says leaving is safe for the set, because as of this commit it is:
    // the write runs on a scope the pop cannot cancel. That sentence was
    // deliberately absent while the prompt was unreachable and the claim would
    // have been false. It is the session, not the set, that is still at risk
    // here, and the wording points at that instead.
    //
    // The heart-rate summary only, never "and HRV" (#333). endSession
    // averages the heart rate over the set ROWS, so a set whose row has not
    // landed is missing from it. The HRV is computed over sessionRrMs, which
    // the passive collector fills in READY, IN_SET and RESTING alike, so the
    // set's beats are in it whether or not the row has landed. Read from
    // source, not observed on a device.
    ExitPrompt.SET_SAVING ->
        "This set is being written to your history now, and it will finish saving even if you leave. " +
            "What will not finish is the session: closing it while a set is still saving leaves that set " +
            "out of the session's heart-rate summary, and nothing rewrites it afterwards."
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
    // The one prompt that must not undersell what is at stake. Its words live
    // in :core:model, where SessionNotClosedCopyTest pins them (#331).
    ExitPrompt.SESSION_NOT_CLOSED,
    ExitPrompt.FINAL_REST_UNSAVED,
    -> SessionNotClosedCopy.body(unwritten ?: UNKNOWN_UNWRITTEN)
}

private fun exitLabel(prompt: ExitPrompt, action: ExitAction): String = when (action) {
    // "and leave" since record-live F6, because Discard alone now also
    // names the discard that stays (DISCARD_SET_AND_STAY).
    ExitAction.DISCARD_SET_AND_LEAVE -> "Discard and leave"
    ExitAction.DISCARD_SET_AND_STAY -> "Discard set"
    ExitAction.FINISH_SESSION -> "Finish session"
    ExitAction.LEAVE_SESSION_OPEN -> "Leave without finishing"
    ExitAction.LEAVE_SESSION_CLOSING -> "Leave"
    ExitAction.LEAVE_FINISHED_SESSION -> "Leave without it"
    // Per prompt, not a two-way test with a fallback. The fallback read "Keep
    // resting" for anything that was not a set in progress, so a new prompt got
    // the rest screen's wording by default -- offered to a lifter standing over
    // a loaded bar, with no compile error, no lint and no test to catch it.
    ExitAction.STAY ->
        when (prompt) {
            ExitPrompt.NONE -> ""
            ExitPrompt.SET_IN_PROGRESS -> "Keep recording"
            ExitPrompt.SET_NOT_STARTED -> "Keep recording"
            ExitPrompt.SESSION_OPEN -> "Keep resting"
            ExitPrompt.SET_SAVING -> "Stay here"
            ExitPrompt.SET_UNSAVED -> "Keep this set"
            ExitPrompt.SESSION_CLOSING -> "Wait here"
            ExitPrompt.SESSION_NOT_CLOSED -> "Stay and retry"
            ExitPrompt.FINAL_REST_UNSAVED -> "Stay and retry"
        }
}

private fun exitColor(action: ExitAction): Color = when (action) {
    ExitAction.DISCARD_SET_AND_LEAVE, ExitAction.DISCARD_SET_AND_STAY -> BarColors.Red
    ExitAction.LEAVE_SESSION_OPEN, ExitAction.LEAVE_SESSION_CLOSING, ExitAction.LEAVE_FINISHED_SESSION -> BarColors.Sub
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
    val parsed = unit.parseToKg(text)?.takeIf { it > 0 }
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
                    keyboardOptions = DecimalKeys,
                )
            }
        },
        // Kept TextButtons (#201): this dialog's two actions; the dialog's frame already marks them
        // as its answers. Save is disabled until the box parses, as BodyWeightRequiredDialog's is:
        // it used to accept the tap and do nothing (record-setup F10).
        confirmButton = {
            TextButton(onClick = { parsed?.let(onSet) }, enabled = parsed != null) {
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
 * `RecordViewModel.beginSet`, and its second button is CANCEL: no set starts
 * and nothing durable is written. On the START-button door the lifter is left
 * where they were. On the rest-screen door `startNextSet` has already
 * advanced the queue and cancelled the rest clock, so CANCEL leaves them on
 * READY for the next set.
 *
 * SAVE is disabled until the box parses to a positive number, so the one
 * control that ends the refusal cannot end it with another absence.
 *
 * [BodyWeightPromptPolicy.WHY_IT_MATTERS] is pinned on the CI path by
 * `BodyWeightStalenessTest`; [SetLoadPolicy.BODY_WEIGHT_REQUIRED] is pinned by
 * nothing. The title, the field label and both button words are written here
 * and nothing tests them.
 *
 * [BodyWeightPromptPolicy.WHY_IT_MATTERS] IS THE STALENESS ASK'S SENTENCE,
 * reused here for the cost it names; its "out-of-date figure" clause does not
 * apply on this door, where nothing is stored at all.
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
                    keyboardOptions = DecimalKeys,
                )
            }
        },
        // Kept TextButtons (#201): this dialog's two actions; the dialog's frame already marks them
        // as its answers.
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
    // Disabled rather than hidden on an empty session: a plan session with no
    // sets is a plan defect, and a START that silently vanished would read as
    // the app being broken rather than the plan being empty.
    // START is the SAME call the session picker card used to make. The preview
    // did not add a second way into a session; it moved the one that exists
    // behind a screen the lifter has read.
    // ABOVE the session list, not after it (UX review 2026-11-06,
    // record-setup F6): a five-exercise session's list is longer than a
    // screen, and every visit had to scroll to the bottom to start. The
    // screen's scroll resets to the top on each stage change, so this is on
    // screen when the preview opens.
    Button(
        onClick = { state.previewSession?.let(viewModel::requestPlanSession) },
        enabled = !preview.isEmpty && state.previewSession != null,
        modifier = Modifier.fillMaxWidth().height(56.dp),
    ) {
        Text("START", fontWeight = FontWeight.Bold)
    }
    // Withheld only while a continued session's body-weight prompt is up
    // (owner's ruling 2026-11-06): that row takes only its own plan session.
    if (state.resumedAtOrderIdx == null || state.pendingBodyWeightSession == null) {
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = viewModel::abandonSetup, modifier = Modifier.fillMaxWidth()) {
            Text("Choose another session")
        }
    }
    Spacer(Modifier.height(12.dp))
    preview.blocks.forEach { block ->
        PreviewBlockCard(block, state.weightUnit)
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * "Continue this session?" (#48), raised on entry when [SessionResumePolicy]
 * found a session an earlier visit left open.
 *
 * Both answers are buttons, and only the buttons answer. An outside tap or a
 * back press does nothing and the dialog stays up (UX review 2026-11-06,
 * home-nav F7, reliability F3): it used to count as "Start a new one", so a
 * stray tap on the scrim while the phone was being put down split one workout
 * across two rows, and the offer is made on entry only, so it never came back.
 * The words come from the policy, where a test reaches them;
 * the start time is rendered here, in the phone's zone, because that is where
 * the lifter is reading it.
 */
@Composable
private fun ResumeSessionDialog(offer: LatestSession, viewModel: RecordViewModel) {
    val startedAt =
        Instant.ofEpochMilli(offer.startedAtMs)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()))
    AlertDialog(
        // Deliberately empty, with both dismissals switched off below: only
        // the two buttons decide.
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(SessionResumePolicy.TITLE) },
        text = {
            Text(
                SessionResumePolicy.body(offer, startedAt),
                style = MaterialTheme.typography.bodySmall,
                color = BarColors.Sub,
            )
        },
        confirmButton = {
            TextButton(onClick = viewModel::continueSession) { Text(SessionResumePolicy.CONTINUE_LABEL) }
        },
        dismissButton = {
            TextButton(onClick = viewModel::declineResume) { Text(SessionResumePolicy.START_NEW_LABEL) }
        },
    )
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

/**
 * One exercise block of the preview: its name, which dimension it steps up on,
 * then every set of it in order.
 *
 * THE DIMENSION IS ON THE HEADER AND NOT ON A SET LINE (#235). `progression`
 * is declared on the exercise, so every slot of a block carries the same
 * value; repeating it per set would state one fact three times and read as
 * three facts. `ProgressionKind.phrase` is the only thing that turns the kind
 * into words, here and on the plan detail screen both, so the session the
 * lifter approves and the session they are about to run cannot be described
 * two ways.
 *
 * An exercise whose plan omitted the key reads "steps up by weight", which is
 * what the omission means; the pair this makes readable before START is an
 * omitted key against a declared "none", which shows no post-set grid and so
 * could not be read at any point of the session before.
 *
 * OBSERVED RENDERING, and the canonical record of it for both surfaces --
 * `PlanDetailScreen.ExerciseCard` points here rather than repeating it. No
 * test on the CI path renders a composable, so this was read off a screen:
 * emulator `barspeed-api35`, headless, debug APK built from the commit whose
 * subject is "Say which dimension each exercise steps up, before the session
 * starts" -- named by subject, not by SHA, because a rebase moves the SHA and
 * has already moved this one -- with a five-exercise plan declaring
 * weight / reps / time / none and one omitting the key. Every header named
 * one dimension on both screens, and the omitted key read
 * "steps up by weight". At `font_scale 2.0` each phrase still
 * renders in full on one line, neither wrapped nor clipped -- read on this
 * screen's preview, with all four phrases on screen together. The TopAppBar
 * title above them does clip at that scale; that is pre-existing and
 * untouched here. What that does NOT establish: nothing at that scale on
 * the plan detail screen, nothing about a real phone's display, and nothing
 * a lifter judged.
 */
@Composable
private fun PreviewBlockCard(block: PreviewBlock, unit: WeightUnit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(block.exerciseName, style = MaterialTheme.typography.titleSmall)
            Text(
                block.progression.phrase(),
                style = MaterialTheme.typography.bodySmall,
                color = BarColors.Sub,
            )
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
private fun SetupStage(state: RecordState, viewModel: RecordViewModel, hideResumeDialog: Boolean = false) {
    if (!hideResumeDialog) state.resumeOffer?.let { ResumeSessionDialog(it, viewModel) }
    // Why "Continue it" started nothing: the row had no set left to do, or its
    // session is gone from the plan (SessionResumePolicy.landing).
    state.resumeNotice?.let {
        SectionCaption(it, color = BarColors.Amber)
        Spacer(Modifier.height(8.dp))
    }
    if (!state.imuConnected) {
        // The permission banner replaces this card's advice rather than sitting
        // beside it. Since #262 the card is the whole of what a missing sensor
        // adds to this page.
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
                    PermissionBannerBody(step = permissionHeld)
                }
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
    // Clear of the button above, record-setup F9's reason on READY.
    Spacer(Modifier.height(24.dp))
    AudioCueChipWithCaption(state, viewModel)
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
 *
 * What PAIR_OR_POWER no longer covers is the phone's Bluetooth being off when
 * `connectGatt` opened no link (#103): `connect()` now reads the adapter
 * there and the card says TURN_ON_PHONE_BLUETOOTH instead. The phone's
 * Bluetooth is still named under PAIR_OR_POWER for the two early returns
 * above and for Connecting and Disconnected, which read no adapter. The
 * advice is latched by the effect above until the next Failed, so after the
 * lifter turns Bluetooth on the card can keep this wording through the
 * reconnect's Connecting, until the block leaves composition on Connected.
 */
private fun imuAdviceText(advice: SensorAdvice): String = when (advice) {
    SensorAdvice.PAIR_OR_POWER ->
        "Pair or power on the WitMotion sensor, or check the phone's own Bluetooth."
    SensorAdvice.UNRESPONSIVE ->
        "The sensor answered and then stopped responding -- pairing again is unlikely to help. " +
            "Power-cycle it."
    SensorAdvice.TURN_ON_PHONE_BLUETOOTH ->
        "Your phone's Bluetooth is off. Turn it on; the app keeps retrying the sensor on its own."
}

/**
 * The one place a refused foreground start reaches the lifter (#53).
 *
 * Before this, every refusal ended in `RecordingService` with no foreground
 * service and nothing on screen, so the set ran as killable as any background
 * app and a screen-off kill would take it with no warning. The wording says
 * what the code knows -- the platform refused, and which family of refusal --
 * and hedges what it does not: whether this device actually stops the app at
 * screen-off is [Field], never measured here, hence "can".
 */
@Composable
private fun ForegroundRefusalBanner(failure: ForegroundStartFailure) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "Recording is not protected if the screen turns off",
                style = MaterialTheme.typography.titleSmall,
                color = BarColors.Amber,
            )
            Text(foregroundRefusalText(failure), style = MaterialTheme.typography.bodySmall, color = BarColors.Sub)
        }
    }
    Spacer(Modifier.height(12.dp))
}

/** Prose for [ForegroundStartFailure]; the families are `ForegroundStartPolicy.classify`'s. */
private fun foregroundRefusalText(failure: ForegroundStartFailure): String {
    val why =
        when (failure) {
            ForegroundStartFailure.PERMISSION_REFUSED ->
                "Android refused the recording service because a permission it needs is not granted."
            ForegroundStartFailure.START_REFUSED ->
                "Android would not start the recording service."
            ForegroundStartFailure.TYPE_REJECTED ->
                "Android rejected the kind of recording service this build asks for."
        }
    return "$why The app can be stopped while the screen is off, and the set with it. " +
        "Keep the screen on until this set is saved; the next set tries again."
}

/** The voice switch; its words and where it is drawn are [VoiceCueControl]'s. */
@Composable
private fun AudioCueChip(state: RecordState, viewModel: RecordViewModel) {
    FilterChip(
        selected = state.audioCues,
        onClick = viewModel::toggleAudioCues,
        label = { Text(VoiceCueControl.label(state.audioCues)) },
    )
}

/**
 * The inline switch on SETUP and READY, with the one line that says what it
 * leaves on: a guided cadence speaks whatever the switch says
 * (settings-onboarding F2).
 */
@Composable
private fun AudioCueChipWithCaption(state: RecordState, viewModel: RecordViewModel) {
    AudioCueChip(state, viewModel)
    Text(VoiceCueControl.TEMPO_CAPTION, style = MaterialTheme.typography.bodySmall, color = BarColors.Sub)
}

@Composable
private fun ReadyStage(state: RecordState, viewModel: RecordViewModel) {
    // READY carries START SET wherever the close allows one
    // (RestControlPolicy.readyMayStartSet, below), so it is the last screen
    // before a set that would record nothing. It renders on the way to a
    // session's first set, on the way to a continued session's first set of
    // this visit (#48), and again after a body-weight refusal taken from the
    // rest screen -- see SkipSetSection -- which is why RESTING carries this too.
    //
    // The refusal is drawn HERE and only here, and it covers both doors:
    // `startNextSet` runs `advancedState` -- which writes Stage.READY on the
    // planned and the ad-hoc branch alike -- before it calls `beginSet`, so a
    // set refused from the rest screen is refused with this stage already
    // showing. Without that, a refusal on the rest path would be a set that
    // silently failed to start.
    BodyWeightRefusal(state, viewModel)
    PermissionBanner()
    val slot = state.currentSlot
    if (slot != null) {
        if (slot.isExerciseChange) {
            MoveSensorCard(slot.exercise.displayName, slot.implement)
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
        // READY renders on the way to a session's first set, on the way to a
        // continued session's first set of this visit (#48), and again
        // after a body-weight refusal taken from the rest screen -- see
        // SkipSetSection -- so this is not always the ONLY chance to say
        // anything, but until this button it could say only the load either
        // time.
        // Switch exercise is kept OUT of the dialog: it opens a chooser of
        // its own, and a dialog inside a dialog is a shape this app has never
        // used. It already reached READY, which is half of what the owner
        // asked for when every rack is busy on arrival; the other half is the
        // change button beside it.
        NextSetControlsRow(state, viewModel, slot, next = false)
    } else {
        AdHocForm(state, viewModel)
        // Plan sets take their prep from the dialog. The ad-hoc layout keeps
        // its inline form, so it keeps its inline prep too.
        PrepAdjuster(state, viewModel)
    }
    SensorCaptureLine(state)
    Spacer(Modifier.height(12.dp))
    // WHO IS ABOUT TO COUNT, from the same decision RecordViewModel.beginSet
    // makes (#286). The expression here read
    // `!state.currentIsTimed && (kind != EXPLOSIVE || !state.imuConnected)` and
    // said "you count" whenever it was true -- which included every tempo'd set,
    // where the METRONOME counts, because this copy of the rule never looked at
    // the tempo. The label is CountingPolicy's now, and the whole point of
    // reading it from there is that a second copy cannot be wrong on its own.
    val kind = state.currentSlot?.exercise?.kind
        ?: state.exerciseOptions.firstOrNull { it.id == state.selectedExerciseId }?.kind
    val tempoText = if (state.adHoc) state.tempoInput.ifBlank { null } else state.currentSlot?.tempo
    val counter =
        CountingPolicy.counterFor(
            hasTempo = tempoText?.let { Tempo.parseOrNull(it) } != null,
            isTimed = state.currentIsTimed,
            kind = kind ?: ExerciseKind.DYNAMIC,
            imuConnected = state.imuConnected,
        )
    // The voice switch sits ABOVE START, 24 dp clear of it. It sat 8 dp under
    // the 56 dp button, where a thumb that missed START by a few millimetres
    // silenced the set (record-setup F9).
    AudioCueChipWithCaption(state, viewModel)
    Spacer(Modifier.height(24.dp))
    // Drawn only where RestControlPolicy says READY may start a set while the
    // close is where it is (#49, #333). READY draws SessionCloseControls below,
    // so a close can be asked for here; beginSet asks the same question, so a
    // tap that reaches it anyway starts nothing.
    if (RestControlPolicy.readyMayStartSet(state.sessionClose)) {
        // Held while the ad-hoc load box would record a 0 it was not given;
        // AdHocForm says "Enter a load" under the box. beginSet refuses too.
        Button(
            onClick = confirmTap(viewModel::beginSet),
            enabled = !state.adHocLoadRefused(),
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            Text(CountingPolicy.startSetLabel(counter), fontWeight = FontWeight.Bold)
        }
    }
    Spacer(Modifier.height(8.dp))
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
 * Rendered on READY and again on the rest screen because READY is drawn only
 * before the first set of a visit (a session's, or a continued session's, #48)
 * and after a body-weight refusal: `startNextSet` writes READY and calls
 * `beginSet` in the same frame, so from the visit's second set onwards the rest
 * screen is the only place the lifter can change anything.
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
            // WHICH UNIT each label is on, before the set (#260). The lifter
            // holding two identical magnet-mounted units cannot tell them apart
            // -- "I'm not really sure. Will check each time, they're likely to
            // get mixed up a lot." -- and the four characters here are what goes
            // on the sticker. The phrase is `DualSensorSetup.rolesLine`'s, in
            // `:core:model` where a test runs on it and where the Devices screen
            // reads the same copy; null wherever the pair cannot be named, which
            // is every state the sentence above is already explaining.
            DualSensorSetup.rolesLine(state.pairedImuAddresses, state.sensorRoles)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = BarColors.Sub)
            }
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
 * ONLY THE DELIVERY STATE SUPPRESSES IT NOW. It said nothing in demo mode
 * (#225 item 7), because that mode fabricated samples with no sensor present,
 * so the set did record and this card's sentence was the one claim it made
 * false. #262 removed the mode and that escape with it. What is left is the
 * states themselves -- `ArmedSilencePolicy.advice` answers null for
 * DELIVERING and for TOO_SOON, the three-second grace named above -- so a
 * unit still sending nothing once the grace has passed is named on every
 * set.
 *
 * Drawn on READY and on RESTING. READY is drawn before the first set of a
 * visit (a session's, or a continued session's, #48) and after a body-weight
 * refusal -- `startNextSet` writes READY and calls `beginSet` in the same
 * frame -- so a card on READY alone names a silent unit before the visit's
 * first set and almost never again,
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
    // The way back to the plan's prep (settings-onboarding F4). An adjustment
    // is stored per exercise and beats every later plan's prep_s, so it is
    // offered whenever one is stored -- including one that equals the plan's
    // number today, written before a tap back stopped storing copies. The tap
    // lands on the plan's value, which LeadInPolicy.overrideAfterTap clears.
    if (state.prepOverrides.containsKey(state.prepExerciseId(slot))) {
        // Kept a TextButton (#201): a quiet reset under the steppers it undoes; outlined, it would
        // read as a third stepper.
        TextButton(onClick = { viewModel.adjustPrep(plannedS - prepS) }) {
            Text(if (slot != null) "Use plan prep (${plannedS}s)" else "Use default prep (${plannedS}s)")
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
    // What is said here is what the lifter can act on: how far this change
    // reaches. From export 1.23 the change is also on the record --
    // `plannedTempo` beside `tempoPrescribed`, which holds what RAN (#151,
    // #157) -- on every set whose plan declared a tempo. The sentence that
    // stood here said tempo had no such pair and an adjusted set was
    // indistinguishable from a prescribed one; 1.23 made it false and it is
    // deleted. The caption is unchanged.
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
 * would move anything; nothing here knows which stroke is the drive, that the
 * drive alone is offered "X", that "X" sits below one second, or that a stroke
 * floors there. The digit it is handed carries all four answers -- #251 moved
 * them onto it precisely so this composable could not hold a fifth opinion. A
 * disabled button rather than a tap that does nothing is the whole point of
 * asking twice.
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
                TempoAdjustPolicy.steppedValue(tempo, digit, -STEPPER_STEP)?.let(onPick)
            },
            enabled = TempoAdjustPolicy.canStep(tempo, digit, -STEPPER_STEP),
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
                TempoAdjustPolicy.steppedValue(tempo, digit, STEPPER_STEP)?.let(onPick)
            },
            enabled = TempoAdjustPolicy.canStep(tempo, digit, STEPPER_STEP),
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
internal fun struckLine(lead: String, values: List<SetCardValue>): AnnotatedString = buildAnnotatedString {
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
 * The three controls that change what happens next, in one row (#238).
 *
 * The owner's ask, from the rest screen in a session: "Switch exercise and add
 * a set should be buttons." They were text links stacked under the one real
 * button, and the report is that they read as afterthoughts and were hard to
 * find and hit between sets.
 *
 * A [FlowRow], not a [Row] of weighted thirds. Three equal thirds would have
 * to be measured against a label the width of "SWITCH EXERCISE" at whatever
 * font scale the phone is set to, and nothing here can measure that; a flow
 * row puts them side by side where they fit and wraps to a second line where
 * they do not, which is what #238 asks for in as many words.
 *
 * WHAT EACH ONE DOES IS UNCHANGED. Every one binds to the handler it bound to
 * as a link: [ChangeSetButton] opens the same dialog, [SwitchExerciseSection]
 * the same chooser, [AddSetSection] calls the same
 * [RecordViewModel.addSetOfCurrentExercise].
 *
 * [RemoveSetSection] stays below the row and stays a text control -- see its
 * own KDoc for why.
 *
 * NOT RENDERED. No device or emulator has drawn this row; whether three
 * buttons fit one line on the phone is a [Field] question and is written up as
 * one.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NextSetControlsRow(state: RecordState, viewModel: RecordViewModel, slot: PlannedSlot, next: Boolean) {
    Spacer(Modifier.height(8.dp))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ChangeSetButton(state, viewModel, slot, next, Modifier)
        SwitchExerciseSection(state, viewModel, Modifier)
        AddSetSection(state, viewModel, Modifier)
    }
    AddSetCaption(state)
    RemoveSetSection(state, viewModel)
    SkipSetSection(state, viewModel)
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
 * Drawn on the rest screen AND on READY. READY renders on the way to a
 * session's first set, on the way to a continued session's first set of this
 * visit (#48), and again after a body-weight refusal taken from the
 * rest screen -- see SkipSetSection -- and used to offer only the load box,
 * so set one was the set whose reps, tempo and prep could not be touched. The owner's
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
 *
 * Since #238 it is the first button in [NextSetControlsRow] and takes its
 * width from the caller. The spacer above it moved there with it. The
 * `modifier` carries no default: that one call site is the only one, so a
 * default would be dead code reading as a second rendering.
 */
@Composable
private fun ChangeSetButton(
    state: RecordState,
    viewModel: RecordViewModel,
    slot: PlannedSlot,
    next: Boolean,
    modifier: Modifier,
) {
    var open by remember(state.queueIndex, state.stage) { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = modifier) {
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
 * nothing is buffered for either of them to discard. All three are held while
 * the load box holds text that is not a number, which would state nothing
 * and leave the plan's load running under the lifter's figure
 * ([SetLoadPolicy.loadEntryAccepted]); clearing the box releases them.
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
 * be easy to get wrong. "deviations are recorded" is a claim about the export.
 * From export 1.23 it is true of load, reps, hold and tempo -- each publishes
 * its planned figure beside the working one (#157) -- and of prep, which
 * publishes a planned/actual pair. The tempo control carries its own caption,
 * which names how far the change reaches. The sentence that stood here said
 * the claim is FALSE of tempo because tempo published one field holding what
 * ran (#151); `plannedTempo` made it false at 1.23 and it is deleted.
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
    // A box that does not parse states nothing, so the plan's load would run
    // while the box shows the lifter's figure (record-setup F4). The dialog
    // stays open and says so until the box reads as a number or is cleared.
    val loadRefused =
        !SetLoadPolicy.loadEntryAccepted(
            adHoc = false,
            typedText = state.loadInput,
            unit = state.weightUnit,
            bodyweight = slot.exercise.bodyweight,
        )
    AlertDialog(
        onDismissRequest = { if (!loadRefused) onDone() },
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
                        isError = loadRefused,
                        supportingText = if (loadRefused) {
                            { Text(SetLoadPolicy.ENTER_A_LOAD) }
                        } else {
                            null
                        },
                        singleLine = true,
                        keyboardOptions = DecimalKeys,
                        modifier = Modifier.weight(1f),
                    )
                    if (slot.isTimed) {
                        OutlinedTextField(
                            value = state.durationInput,
                            onValueChange = viewModel::updateDurationInput,
                            label = { Text("Hold (s)") },
                            singleLine = true,
                            keyboardOptions = WholeNumberKeys,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        OutlinedTextField(
                            value = state.repsInput,
                            onValueChange = viewModel::updateRepsInput,
                            label = { Text("Reps") },
                            singleLine = true,
                            keyboardOptions = WholeNumberKeys,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                PlanValueCaptions(state, slot)
                PerImplementEcho(state, slot)
                LoadSignHint(slot)
                SideAdjuster(state, viewModel, slot)
                TempoAdjuster(state, viewModel)
                PrepAdjuster(state, viewModel)
            }
        },
        // Kept a TextButton (#201): this dialog's one action; the dialog's frame already marks it
        // as its answer.
        confirmButton = { TextButton(onClick = onDone, enabled = !loadRefused) { Text("Done") } },
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
 * only case where it says so. Since #238 those words are [AddSetCaption]
 * under the row rather than the button's own label.
 *
 * Drawn on the last-set branch of [NextSetBlock] as well, so the session's
 * final set has the control too. There it keeps the full width the row does
 * not give it.
 *
 * BESIDE SWITCH EXERCISE in [NextSetControlsRow] and in the same form since
 * #238, on BOTH surfaces, which is #152's consolidated change surface: the
 * rest screen and READY. The owner required switch-exercise in both places for
 * the reason that applies here unchanged -- set one is when a wrong load first
 * shows itself, and READY is the only screen set one has.
 *
 * Which of the three the row holds is decided per render by each one's own
 * guard, not by the row. [SwitchExerciseSection] draws on every plan session
 * since record-setup F2 (its chooser always holds "Different exercise...").
 *
 * NOT inside the change-set dialog. Everything in that dialog changes the set
 * coming up; this changes the SESSION, and folding it in would put it under a
 * subtitle reading "Adjust next set (deviations are recorded)" -- which would
 * be a false description of it, and would also make it read as a deviation of
 * the upcoming set. It is not one: the upcoming set is untouched, its card
 * is unchanged, and the appended
 * set is a set the plan does not contain rather than a change to a set it does.
 *
 * AN OUTLINED BUTTON SINCE #238 and never a filled one: START is the only
 * filled button on either screen while a set is queued -- after the last
 * planned set START is withheld and FINISH SESSION takes the filled place
 * (#195). A mis-tap here used to be survivable only because the plan's
 * remaining sets are dropped whenever a session is finished early; since #206
 * it is survivable directly, because [RemoveSetSection] -- called below this
 * on all three surfaces that offer it, and drawn on the two where an
 * appended set of the block can still be queued -- takes the set back out
 * again.
 *
 * Repeatable: every tap appends one more, and nothing here or in
 * [RecordViewModel.addSetOfCurrentExercise] assumes at most one.
 */
@Composable
private fun AddSetSection(
    state: RecordState,
    viewModel: RecordViewModel,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    if (addSetLabel(state) == null) return
    OutlinedButton(onClick = viewModel::addSetOfCurrentExercise, modifier = modifier) {
        Text("ADD SET")
    }
}

/**
 * The words the add control dropped when it became a button (#238).
 *
 * [AddSetControl.label] names the exercise the added set belongs to, and
 * where the anchor and the upcoming exercise differ it names where the set
 * lands. A third of a row does not hold that sentence. Dropping it instead
 * would leave "ADD SET" naming no exercise on the one surface where two are
 * in play.
 */
@Composable
private fun AddSetCaption(state: RecordState) {
    SectionCaption(addSetLabel(state) ?: return)
}

/**
 * The add control's words, or null where no add control is drawn.
 *
 * One function for the button's guard and the caption's, so the two cannot
 * disagree about whether the control exists.
 */
private fun addSetLabel(state: RecordState): String? {
    if (state.adHoc) return null
    val anchor = state.currentSlot ?: return null
    return AddSetControl.label(anchor.exercise.displayName, state.upcomingSlot?.exercise?.displayName)
}

/**
 * "Remove the set you added" -- #177's named remainder, and the other half of
 * the pair (#206).
 *
 * CALLED BELOW [AddSetSection] on all three surfaces that offer it, from
 * two call sites since #238: [NextSetControlsRow], which the rest screen's
 * next-set block and READY both go through, and the rest screen's last-set
 * branch. Only the first two ever DRAW it. In the
 * last-set branch [RecordState.nextSlot] is null, so
 * [RecordState.upcomingIndex] -- `queueIndex + 1` throughout rest -- is
 * already past the queue's last index; every candidate
 * [RemoveSetControl.target] considers comes from [AddSetControl.blockRange],
 * whose indices never reach that far, so its `it >= upcomingIndex` filter is
 * empty, [RecordState.removeSetTarget] is always null on that branch, and
 * this function returns before drawing anything. The call stays there
 * anyway: the moment [AddSetSection] appends a set, [RecordState.nextSlot]
 * stops being null and the same state renders through [NextSetControlsRow]
 * instead, where the set just added is eligible. #206
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
 * STILL A TEXT CONTROL after #238 made its pair a button, which #238 asks to
 * be decided and stated. It is drawn only when there is something to remove,
 * so in the row it would change the row's arity between renders and move the
 * other buttons under a finger already reaching for one -- the stacked-target
 * hazard #137 removed elsewhere on this screen. It is also the destructive
 * half of the pair. Not destructive of anything RECORDED, so it still takes
 * no confirmation: the set it removes has not happened, and the lifter can
 * add it again with the button above.
 */
@Composable
private fun RemoveSetSection(state: RecordState, viewModel: RecordViewModel) {
    val target = state.removeSetTarget ?: return
    val slot = state.queue.getOrNull(target.removeAt) ?: return
    // Kept a TextButton (#201): the KDoc's STILL A TEXT CONTROL says why.
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

/**
 * "Not doing it? Skip Set 4 of Back squat" -- dropping an upcoming PRESCRIBED
 * set without ending the session (#300).
 *
 * THE OWNER'S ASK, in their words: *"I'd also like a mechanism to remove an
 * upcoming set."* Before this the only way to be rid of an unwanted prescribed
 * set was FINISH SESSION, which drops every remaining exercise. A lifter who
 * wanted to leave the last squat set and go on to the deadlifts had no control
 * for it.
 *
 * THE THIRD OF THE CLUSTER, drawn directly under [RemoveSetSection] and under
 * the same caption, so the three read as one decision about the queue rather
 * than as three unrelated controls. Not IN the [NextSetControlsRow] FlowRow, for
 * the reason [RemoveSetSection] gives for staying out of it: it is drawn only
 * when there is something to skip, so in the row it would change the row's arity
 * between renders and move the other buttons under a finger already reaching for
 * one -- #137's stacked-target hazard.
 *
 * DRAWN ONLY WHERE A SET IS SKIPPABLE, which is what makes the eligibility
 * visible rather than merely enforced. [SkipSetControl] refuses an APPENDED
 * upcoming slot, which [RemoveSetSection] above takes back instead, and refuses
 * a block OPENER, because dropping the first set of a block is dropping the
 * exercise rather than a set of it. The lifter never taps a control that
 * refuses.
 *
 * WHICH SET IT NAMES ON READY, and it is not always the session's first.
 * `upcomingIndex` is `queueIndex + 1` only while the stage is RESTING and is
 * `queueIndex` otherwise. On the session's own READY the coming set IS the
 * first, a block opener, so nothing is drawn. READY also renders after a
 * body-weight refusal taken from the rest screen -- [BodyWeightRequiredDialog]'s
 * KDoc states that door: `startNextSet` has already advanced the queue, so
 * CANCEL leaves the lifter on READY with `upcomingIndex` naming the set that
 * did not start. The control draws there, and skipping it drops that set. The
 * rest screen's own last-set branch draws no [NextSetControlsRow] at all, so
 * this section is not reached from it.
 *
 * CONFIRMED, WHERE ITS PAIR IS NOT, and the difference is what a mis-tap costs.
 * Removing an appended set can be undone exactly -- ADD SET rebuilds it from the
 * same standing values -- while a prescribed set's own prescription goes with
 * it: ADD SET can queue another set of the exercise, but as an APPENDED set,
 * carrying no `plannedReps` and publishing `added: true`. That is what
 * [SkipSetControl.confirmBody] says, in the one place the lifter can read it,
 * and it is the only thing they cannot work out from the screen. One tap to ask
 * and one to confirm.
 *
 * WHAT IT NAMES is the slot the tap will take, out of the same
 * [RecordState.skipSetTarget] the ViewModel acts on, so the words and the act
 * cannot name different sets -- [RemoveSetSection]'s arrangement and #45's
 * class of defect.
 *
 * NOT DESTRUCTIVE OF ANYTHING RECORDED, and this is where it differs from the
 * void mark one screen over. The slot has not run: no row, no raw stream, no
 * export entry. What the skip DOES write, at the session close, is the deviation
 * itself, so a coach reading the export sees that the plan asked for four sets
 * and three were recorded BECAUSE one was dropped on purpose.
 */
@Composable
private fun SkipSetSection(state: RecordState, viewModel: RecordViewModel) {
    val target = state.skipSetTarget ?: return
    val slot = state.queue.getOrNull(target.skipAt) ?: return
    var confirming by remember(state.queueIndex, state.stage) { mutableStateOf(false) }
    // Kept a TextButton (#201): it drops a prescribed set, so it is available rather than invited,
    // like REMOVE above it.
    TextButton(onClick = { confirming = true }) {
        Text(
            SkipSetControl.label(slot.exercise.displayName, target.setNumber, target.lastOfBlock),
            color = BarColors.Blue,
        )
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(SkipSetControl.confirmTitle(slot.exercise.displayName, target.setNumber)) },
            text = {
                Text(
                    SkipSetControl.confirmBody(slot.exercise.displayName),
                    style = MaterialTheme.typography.bodySmall,
                    color = BarColors.Sub,
                )
            },
            // Kept TextButtons (#201): this dialog's two actions; the dialog's frame already marks
            // them as its answers.
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        viewModel.skipUpcomingSet()
                    },
                ) {
                    Text("SKIP IT")
                }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("KEEP IT") } },
        )
    }
}

/**
 * Offer the session's other remaining exercises out of order.
 *
 * A BUTTON SINCE #238, where it was a text link reading "Equipment busy?
 * Switch exercise". The "Equipment busy?" framing is not dropped: it moves
 * to the chooser's title, which is what the tap opens.
 *
 * DRAWN ON EVERY PLAN SESSION since the UX review of 2026-11-06
 * (record-setup F2), where it used to draw nothing once
 * `RecordState.exerciseChoices` was empty. Its chooser ends in "Different
 * exercise...", which opens the exercise picker and puts ONE off-plan set of
 * the pick next, marked as added and taking no plan slot -- every decision is
 * `offPlanAddedState`'s. That entry is there even on the session's last
 * remaining exercise and after its last planned set, where a finisher or a
 * rack taken is likeliest; the remaining-exercise entries are listed only
 * when there are some. An ad-hoc session has no plan to leave and draws
 * nothing.
 *
 * The `modifier` carries no default: its two call sites, [NextSetControlsRow]
 * and the last-set branch of [NextSetBlock], each size it differently (one
 * place in a FlowRow, full width under the last set).
 */
@Composable
private fun SwitchExerciseSection(state: RecordState, viewModel: RecordViewModel, modifier: Modifier) {
    if (state.adHoc) return
    val choices = state.exerciseChoices
    var showChooser by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { showChooser = true }, modifier = modifier) {
        Text("SWITCH EXERCISE")
    }
    if (showChooser) {
        AlertDialog(
            onDismissRequest = { showChooser = false },
            title = { Text("Equipment busy? Do another exercise next") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (choices.isNotEmpty()) {
                        Text(
                            "Its remaining sets move to the front; everything else keeps its order.",
                            style = MaterialTheme.typography.bodySmall,
                            color = BarColors.Sub,
                        )
                    }
                    choices.forEach { choice ->
                        OutlinedButton(
                            onClick = {
                                showChooser = false
                                viewModel.jumpToExercise(choice.exerciseId)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = ButtonDefaults.TextButtonContentPadding,
                        ) {
                            Text(
                                "${choice.displayName} — ${choice.setsLeft} " +
                                    if (choice.setsLeft == 1) "set left" else "sets left",
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            showChooser = false
                            showPicker = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = ButtonDefaults.TextButtonContentPadding,
                    ) {
                        Text("Different exercise…")
                    }
                }
            },
            // Kept a TextButton (#201): this dialog's one action; the dialog's frame already marks
            // it as its answer.
            confirmButton = {
                TextButton(onClick = { showChooser = false }) { Text("Cancel") }
            },
        )
    }
    if (showPicker) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text("Different exercise") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "One set of it next, marked as added. The plan's sets stay queued.",
                        style = MaterialTheme.typography.bodySmall,
                        color = BarColors.Sub,
                    )
                    Spacer(Modifier.height(8.dp))
                    // Without the lift of the block in progress: ADD SET adds a set of it.
                    val offPlan = AdHocPicker.offPlanChoices(state.exerciseOptions, state.currentSlot?.exercise?.id)
                    ExerciseChips(offPlan, selectedId = null) { id ->
                        showPicker = false
                        viewModel.addOffPlanExercise(id)
                    }
                }
            },
            // Kept a TextButton (#201): this dialog's one action; the dialog's frame already marks
            // it as its answer.
            confirmButton = {
                TextButton(onClick = { showPicker = false }) { Text("Cancel") }
            },
        )
    }
}

/** How many exercise chips the picker shows before "More exercises". */
private const val PICKER_SHOWN_FIRST = 8

/**
 * The exercise picker's chips, in `RecordState.exerciseOptions` order -- the
 * lifter's recent lifts, then the active plan's, then the built-in seeds
 * (`AdHocPicker.options`, record-setup F3). The first [PICKER_SHOWN_FIRST] and
 * the one picked are shown; the rest wait behind "More exercises", so the
 * grid no longer pushes START below the fold.
 *
 * Shared by the ad-hoc form and the off-plan picker, so the two offer one
 * list; the off-plan picker hands it that list without the lift of the block
 * in progress (`AdHocPicker.offPlanChoices`).
 */
@Composable
private fun ExerciseChips(options: List<ExerciseDef>, selectedId: String?, onPick: (String) -> Unit) {
    var showAll by remember { mutableStateOf(false) }
    val first = options.take(PICKER_SHOWN_FIRST)
    val shown =
        when {
            showAll -> options
            first.any { it.id == selectedId } -> first
            else -> first + options.filter { it.id == selectedId }
        }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        shown.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { exercise ->
                    FilterChip(
                        selected = selectedId == exercise.id,
                        onClick = { onPick(exercise.id) },
                        label = { Text(exercise.displayName) },
                    )
                }
            }
        }
        if (options.size > first.size) {
            // Kept a TextButton (#201): a disclosure toggle under the chips it opens.
            TextButton(onClick = { showAll = !showAll }) {
                Text(if (showAll) "Fewer exercises" else "More exercises")
            }
        }
    }
}

@Composable
private fun MoveSensorCard(exerciseName: String, implement: Implement) {
    // Worded off the plan's declared implement (#253's key), not "bar" for
    // every movement (#269): a machine, a cable or a single dumbbell has
    // nothing to move to a bar, and telling a lifter to find one that is not
    // part of the exercise is a wrong instruction, not a cosmetic one.
    // OTHER — the omitted-key default — falls back to naming the exercise
    // alone, which is the neutral case #269 asked for.
    val where = when (implement) {
        Implement.BARBELL -> "$exerciseName bar"
        Implement.DUMBBELL -> "$exerciseName dumbbells"
        Implement.OTHER -> exerciseName
    }
    Card(Modifier.fillMaxWidth()) {
        Text(
            "New exercise — move the sensor to the $where",
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
 * The REACH each caption claims is not asserted here -- it is
 * [NextSetInputs.of]'s answer about this slot and the one after it, the SAME
 * call, on the same pair and the same statements, that restingState makes when
 * this set has run. Only restingState's LastRanSet differs, and that feeds the
 * boxes, never a standing statement; `NextSetInputsTest` pins the two answers
 * equal. The statements are the state's stated values, not the parsed boxes:
 * those are what the rest transition carries, so a box holding a seeded number
 * nobody stated can no longer be promised a carry it does not get (code-health
 * review 2026-11-06, F2). On the last set of a block the answer is null for all
 * three boxes. Reps and hold also answer null wherever the plan prescribes a
 * different number next -- SetRepsPolicy stops the carry there. The load does
 * not: it carries a correction across a stepping plan as a distance rather
 * than stopping, so the load's answer stays non-null there too. What withholds
 * the load's reach sentence is not a policy null but `nextDeclaredAddedKg`,
 * passed to [PlanValueCaption.load] separately and compared against the
 * planned figure inside it -- the caption says the change is recorded rather
 * than claiming a reach it does not have.
 *
 * The PLANNED side is the FROZEN declaration and the SHOWN side is the box, so
 * the caption names the plan's prescription and the lifter's standing
 * statement as two different things -- which is the point of it, once a value
 * can differ from the plan because of something said several sets ago.
 */
@Composable
private fun PlanValueCaptions(state: RecordState, slot: PlannedSlot) {
    val after = state.slotAfterUpcoming
    // The same call the rest transition after this set makes, on the same pair
    // and the same statements: what stands for later sets is read off it rather
    // than decided a second time here.
    val reach =
        NextSetInputs.of(
            last = slot.nextSetSlot(),
            next = after?.nextSetSlot(),
            stated = state.nextSetStatements,
        )
    val captions =
        listOfNotNull(
            PlanValueCaption.load(
                adHoc = state.adHoc,
                added = slot.isAddedSet,
                bodyweight = slot.exercise.bodyweight,
                unit = state.weightUnit,
                plannedAddedKg = slot.plannedLoadKg,
                nextDeclaredAddedKg = after?.plannedLoadKg,
                shownAddedKg = state.weightUnit.parseToKg(state.loadInput),
                standsForLaterSets = reach.statedKg != null,
            ),
            if (slot.isTimed) {
                PlanValueCaption.hold(
                    adHoc = state.adHoc,
                    added = slot.isAddedSet,
                    plannedDurationS = slot.plannedDurationS,
                    nextDeclaredDurationS = after?.plannedDurationS,
                    shownDurationS = state.durationInput.trim().toIntOrNull(),
                    standsForLaterSets = reach.statedDurationS != null,
                )
            } else {
                PlanValueCaption.reps(
                    adHoc = state.adHoc,
                    added = slot.isAddedSet,
                    plannedReps = slot.plannedReps,
                    nextDeclaredReps = after?.plannedReps,
                    shownReps = state.repsInput.trim().toIntOrNull(),
                    standsForLaterSets = reach.statedReps != null,
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
 * Which arm the set being set up works, on a unilateral set (#215).
 *
 * DRAWN ONLY WHERE THE SET IS UNILATERAL. [SideChoicePolicy.offersChoice]
 * decides, in the module a test runs in, and a bilateral set gets no control at
 * all -- the same silent-no-draw rule [SideArrow] applies to the arrow. A
 * Both/Left/Right selector here would let a lifter put a limb on a set that
 * used two, and unlike the ad-hoc selector one field over, what is chosen here
 * is RECORDED against a prescription.
 *
 * ON BOTH SURFACES since the UX review of 2026-11-06 (record-setup F7). #215
 * scoped it to the next set, so READY's "Change this set" did not offer it and
 * set one of a unilateral block could not be swapped -- the plan's side was
 * recorded against what the lifter did. The mechanism carries over unchanged:
 * the card on READY already draws `carriedIntoNextSet(slot.side, statedSide)`,
 * and `startedFromReadyState` bakes the statement through the same function
 * the rest screen's START uses. The `next` guard at the call site is gone.
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
        // Drawn SELECTED (record-setup F12): it shows the unit in force and
        // switches it, which converts the typed load. Unselected it read as a
        // filter that was off.
        FilterChip(
            selected = true,
            onClick = viewModel::toggleWeightUnit,
            label = { Text("Units: ${state.weightUnit.suffix}") },
        )
    }
    ExerciseChips(state.exerciseOptions, state.selectedExerciseId, viewModel::selectExercise)
    // Both/Left/Right only under a lift that can be done one side at a time
    // (AdHocPicker.offersSide, record-setup F12); under a squat it was noise.
    if (AdHocPicker.offersSide(state.selectedExerciseId, state.recentLifts, state.planSessions)) {
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
    }
    Spacer(Modifier.height(4.dp))
    // A blank or unparseable box used to start the set at 0 kg (record-setup
    // F4). START is held and this says why; an explicit 0 still starts.
    val loadRefused = state.adHocLoadRefused()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = state.loadInput,
            onValueChange = viewModel::updateLoadInput,
            label = {
                val suffix = state.weightUnit.suffix
                Text(if (state.currentIsTimed) "Load ($suffix, 0 = BW)" else "Load ($suffix)")
            },
            isError = loadRefused,
            supportingText = if (loadRefused) {
                { Text(SetLoadPolicy.ENTER_A_LOAD) }
            } else {
                null
            },
            singleLine = true,
            keyboardOptions = DecimalKeys,
            modifier = Modifier.weight(1f),
        )
        if (state.currentIsTimed) {
            OutlinedTextField(
                value = state.durationInput,
                onValueChange = viewModel::updateDurationInput,
                label = { Text("Hold (s)") },
                singleLine = true,
                keyboardOptions = WholeNumberKeys,
                modifier = Modifier.weight(1f),
            )
        } else {
            OutlinedTextField(
                value = state.repsInput,
                onValueChange = viewModel::updateRepsInput,
                label = { Text("Reps") },
                singleLine = true,
                keyboardOptions = WholeNumberKeys,
                modifier = Modifier.weight(1f),
            )
            // Text, not a number pad: a tempo takes X for an explosive
            // stroke (#258). It still closes the keyboard on Done.
            OutlinedTextField(
                value = state.tempoInput,
                onValueChange = viewModel::updateTempoInput,
                label = { Text("Tempo") },
                placeholder = { Text("4010") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
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
    // WHAT REACHES HERE. Nothing did, until #286. Every non-timed, non-guided
    // set was `manualSet` and took the branch above, and the only set that was
    // not was an explosive lift with a sensor, which takes the one before it --
    // so this velocity readout and its per-rep bars were unreachable in the
    // shipped app. The straight-reps set the SENSOR counts lands here now, which
    // is why the count and the correction button are added below rather than a
    // fourth stage being written: velocity and the per-rep bars are exactly what
    // a max-intent set wants on screen beside the count.
    SensorCountedSetStage(state, viewModel, slot)
}

/**
 * The only (or both) bar sensor has stopped delivering, so the count has
 * frozen: said once out loud and drawn large enough to read from the bench
 * (UX review 2026-11-06, BLE F1). Nothing while the sensor delivers. Both
 * sensor-counted stages draw it, since the voice says it on both;
 * [SensorLossPolicy] is the rule.
 */
@Composable
private fun SensorLostBanner(state: RecordState) {
    if (!state.sensorLoss.lost) return
    Spacer(Modifier.height(10.dp))
    Card(Modifier.fillMaxWidth()) {
        Text(
            SensorLossPolicy.BANNER,
            Modifier.padding(12.dp),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = BarColors.Amber,
        )
    }
}

/**
 * In-set display for a set the SENSOR is counting: the count the voice is
 * saying, the live velocity, one button for the rep the detector missed, the
 * end controls, and the per-rep bars.
 *
 * No ring (UX review 2026-11-06, record-live F4/F5). The tempo ring that sat
 * here never had a target -- a set with a tempo is routed to the guided stage
 * -- so its 64 sp centre was a phase stopwatch measuring nothing, the count
 * showed twice, and its 230 dp pushed the effort grid below the fold. The
 * count is now shown once, at 64 sp, and the end controls come before the rep
 * bars so ending the set needs no scroll.
 */
@Composable
private fun SensorCountedSetStage(state: RecordState, viewModel: RecordViewModel, slot: PlannedSlot?) {
    val plannedReps = if (state.adHoc) state.repsInput.toIntOrNull() else slot?.reps
    // When +1 was last tapped, for the grid's slip guard (EffortGridSlipGuard).
    var lastRepTapAtMs by remember { mutableStateOf<Long?>(null) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        InSetHeader(state, slot)
        SensorLostBanner(state)
        Spacer(Modifier.height(10.dp))
        // The count. The SAME field the voice was handed, so the number on
        // screen is the number that was spoken (#252).
        Text(
            "SENSOR COUNT",
            style = MaterialTheme.typography.labelMedium,
            color = BarColors.Sub,
            letterSpacing = 2.sp,
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                LiveCountReadout.countLabel(state.sensorReps, state.liveCountWithheld),
                style = MaterialTheme.typography.displayLarge,
            )
            Text(
                plannedReps?.let { " of $it" } ?: " reps",
                style = MaterialTheme.typography.titleMedium,
                color = BarColors.Sub,
                modifier = Modifier.padding(bottom = 10.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                LiveCountReadout.velocityLabel(state.live.velocityMps, state.liveTrackerDropped),
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
        Spacer(Modifier.height(16.dp))
        // The rep the detector missed. A CORRECTION of the sensor's count rather
        // than a count of its own -- `CountingPolicy.tapMeaning` is what makes
        // that true, and the wording says so, because a button reading "+1 REP"
        // beside a number the sensor is already moving reads as a second
        // counter. Gone once the set has ended, ManualSetStage's reason: the
        // count is frozen into the write and addManualRep already ignores taps
        // from that moment, so a live-looking 72dp target would do nothing.
        if (state.setWrite == SetWriteState.NONE) {
            OutlinedButton(
                onClick = confirmTap {
                    lastRepTapAtMs = SystemClock.uptimeMillis()
                    viewModel.addManualRep()
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Text("+1 REP THE SENSOR MISSED", style = MaterialTheme.typography.titleMedium)
            }
            // Clear space between the counting button and the grid whose
            // every tile ends the set (record-live F2); ManualSetStage's too.
            Spacer(Modifier.height(24.dp))
        }
        EndSetControl(state, viewModel, lastRepTapAtMs)
        Spacer(Modifier.height(16.dp))
        LiveRepBars(state, slot)
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
                // What the ring calls the rep is GuidedRepCaption's decision,
                // in :core:model where a test runs on every push. The screen
                // holds the three facts it takes and none of the rule: which
                // reps are finished, how many were asked for, and which phase
                // of the set is on screen.
                GuidedRepCaption.forRing(
                    finishedReps = state.manualReps,
                    plannedReps = plannedReps,
                    leadIn = state.leadInRunning,
                    finished = state.guidedFinished,
                )?.let { caption ->
                    Text(
                        caption,
                        style = MaterialTheme.typography.bodySmall,
                        color = BarColors.Sub,
                    )
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        // The prep is the only moment this can be read: once the cadence
        // starts the lifter is moving, and the line the voice is about to say
        // is no use after it has said it.
        if (state.leadInRunning) {
            StartCueBlock(startCueFor(state, slot), RepCallNotePolicy.noteFor(state.repCallAtDriveEnd))
        } else {
            Text(
                "Follow the voice — the app counts the reps.",
                style = MaterialTheme.typography.bodySmall,
                color = BarColors.Sub,
            )
        }
        Spacer(Modifier.height(24.dp))
        EndSetControl(state, viewModel)
    }
}

/**
 * Where the coming rep starts, drawn under the ring while the prep counts down
 * (#241).
 *
 * Headline type rather than the body style the line it replaces uses, because
 * the lifter reading it is standing at the bar with the phone on the floor or a
 * bench. [StartCue.marker] is drawn only when it is present and in the warning
 * colour: it says the app GUESSED the start from words in the exercise id, so
 * the plan is worth checking before the first rep goes the wrong way.
 *
 * It decides nothing. Every word comes from [StartCuePolicy], in `:core:model`,
 * where a test runs on every push; nothing in this file is reachable by one.
 *
 * [repCallNote] is `RepCallNotePolicy`'s decision, also in `:core:model`, drawn
 * under the phrase and only on a set whose plan says the rep number at the end of
 * each drive (#266). Body type and the subdued colour: it confirms a habit the
 * lifter already has rather than telling them which way to move.
 *
 * `[Field]` -- NO DEVICE HAS DRAWN THIS. The lane that added it could not run
 * the emulator: free physical memory was polled every 60 s for 30 minutes and
 * never rose above 1.52 GB against the bench-test recipe's ~3 GB floor, so the
 * AVD was never booted and no screenshot exists. The lane that added the rep-call
 * note could not run one either -- free RAM under 1 GB -- so the block now has
 * three lines and none of them has been rendered. What is unverified is
 * everything only a renderer can answer, and the longest phrase is the one to
 * check: `"Start at the BOTTOM, first movement UP"` is 38 characters at
 * `headlineSmall`, and whether it wraps to two lines, wraps mid-word, or clips
 * on a narrow phone at a large font scale has not been observed. Next time the
 * emulator is free: import a plan declaring one `"start": "top"` and one
 * `"start": "bottom"` exercise, and one `1010` slot on a concentric-first lift so
 * the note is drawn, START each, and screenshot the countdown at
 * `wm size 360x800` and at `font_scale 2.0` as well as at the defaults.
 */
@Composable
private fun StartCueBlock(cue: StartCue, repCallNote: String?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(
            cue.phrase,
            style = MaterialTheme.typography.headlineSmall,
            color = BarColors.Volt,
            textAlign = TextAlign.Center,
        )
        cue.marker?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, color = BarColors.Amber)
        }
        // Under the #241 line, and only on the sets whose plan counts at the end
        // of each drive (#266). Body type rather than the headline the line above
        // uses: it confirms a habit the lifter already has -- "people are used to
        // the reps being counted at lockout" -- rather than telling them which
        // way to move, which is the one thing a wrong guess ruins the set over.
        repCallNote?.let {
            Spacer(Modifier.height(4.dp))
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = BarColors.Sub,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * The start cue for the set now being prepped.
 *
 * The geometry comes off the slot the plan resolved, so what is shown is what
 * `SetAnalyzer` will be handed and what the export will publish -- never a
 * second resolution of the same question. An ad-hoc set has no slot and no
 * plan, so its provenance is asked of [SetGeometryPolicy.describe] with no
 * declaration, which is the same call `RecordViewModel` makes when it freezes
 * an ad-hoc set's geometry into the pending write.
 *
 * Whether the tempo's digit 3 is `X` comes off [RecordState.explosiveUpStroke],
 * and whether its digit 1 is comes off [RecordState.explosiveDownStroke] (plan
 * 1.15, #258), both set by `RecordViewModel` from the tempo it hands the voice,
 * so a set whose voice opens on an explosive `Drive` is shown `DRIVE` (#264).
 */
private fun startCueFor(state: RecordState, slot: PlannedSlot?): StartCue {
    val exercise = state.currentExercise
    val source =
        slot?.geometry?.sources?.startsWith
            ?: SetGeometryPolicy.describe(exercise, declared = null).sources.startsWith
    return StartCuePolicy.of(
        exercise.startsWith,
        exercise.concentricUp,
        exercise.horizontal,
        source,
        explosiveUpStroke = state.explosiveUpStroke,
        explosiveDownStroke = state.explosiveDownStroke,
    )
}

/** Sensorless set: the lifter taps to count reps; the ring tracks planned progress. */
@Composable
private fun ManualSetStage(state: RecordState, viewModel: RecordViewModel, slot: PlannedSlot?) {
    val plannedReps = if (state.adHoc) state.repsInput.toIntOrNull() else slot?.reps
    // When +1 was last tapped, for the grid's slip guard (EffortGridSlipGuard).
    var lastRepTapAtMs by remember { mutableStateOf<Long?>(null) }
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
            Button(
                onClick = confirmTap {
                    lastRepTapAtMs = SystemClock.uptimeMillis()
                    viewModel.addManualRep()
                },
                modifier = Modifier.fillMaxWidth().height(72.dp),
            ) {
                Text("+1 REP", style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.height(24.dp))
        }
        EndSetControl(state, viewModel, lastRepTapAtMs)
    }
}

/**
 * In-set display for explosive lifts (snatch, clean): no tempo — the ring fills
 * with completed reps and the headline number is the last rep's PEAK velocity.
 */
@Composable
private fun ExplosiveSetStage(state: RecordState, viewModel: RecordViewModel, slot: PlannedSlot?) {
    // One entry per rep the count stands at, from the counter's own calls --
    // not `live.repPeakVelocities`, the tracker's retired pairing rule
    // (dsp-core F1). Null is a rep with no figure, so the headline of a rep the
    // lifter added by hand is a dash, not an earlier rep's peak.
    val peaks = state.sensorRepFigures.map { it?.peakMps }
    val plannedReps = if (state.adHoc) state.repsInput.toIntOrNull() else slot?.reps
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        InSetHeader(state, slot)
        SensorLostBanner(state)
        Spacer(Modifier.height(10.dp))
        // The SPOKEN count, not `live.repCount` (#286). The tracker's own count
        // is a second statement of the pairing rule and the voice no longer says
        // it; drawing it here would put a different number on screen from the
        // one the lifter just heard, and the row stores the spoken one.
        val repProgress = LiveCountReadout.repProgress(state.sensorReps, plannedReps, state.liveCountWithheld)
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
                    "rep " + LiveCountReadout.countLabel(state.sensorReps, state.liveCountWithheld) +
                        (plannedReps?.let { " of $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = BarColors.Sub,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        // Cadence matters for cyclical ballistic work (kettlebell swings).
        val cadence =
            LiveCountReadout.repsPerMin(state.sensorReps, state.setElapsedS, state.liveCountWithheld)
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
                colorFor = { _, v -> velocityLossColor(v, peaks.filterNotNull(), slot?.velocityLossStopPct) },
            )
        }
        Spacer(Modifier.height(24.dp))
        // The banner above tells the lifter to count by hand, and this stage
        // has no correction button otherwise, so it is offered while the
        // sensor is lost: the same `+1 REP` correction, through the same
        // `addManualRep`, as the straight-reps stage (BLE F1).
        if (state.sensorLoss.lost && state.setWrite == SetWriteState.NONE) {
            OutlinedButton(
                onClick = viewModel::addManualRep,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Text("+1 REP THE SENSOR MISSED", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(10.dp))
        }
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
            SideArrow(side, MaterialTheme.typography.bodyMedium.fontSize, Modifier.padding(end = 8.dp))
            Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = BarColors.Sub)
        }
        state.hrBpm?.let {
            Text("♥ $it", style = MaterialTheme.typography.bodyMedium, color = BarColors.Red)
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
        // The counter's calls, not the tracker's pairing (dsp-core F1): as many
        // bars as the number on screen, a null one for a rep with no figure.
        val values = state.sensorRepFigures.map { it?.meanMps }
        val measured = values.filterNotNull()
        RepBars(
            values = values,
            plannedSlots = plannedReps,
            colorFor = { _, v -> velocityLossColor(v, measured, stopPct) },
        )
    }
}

private fun velocityColor(phase: Phase): Color = when (phase) {
    Phase.CONCENTRIC -> BarColors.Volt
    Phase.ECCENTRIC -> BarColors.Blue
    else -> BarColors.Sub
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
private fun EndSetControl(state: RecordState, viewModel: RecordViewModel, lastRepTapAtMs: Long? = null) {
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
            // Top to bottom in the policy's order, so the red failure control
            // a guided set had mid-set is still the top control at "Done" and
            // the grid opens below it rather than under the finger (UX review
            // 2026-11-06, record-live F3).
            SetEndControlPolicy.drawOrder(controls).forEach { control ->
                when (control) {
                    SetEndControl.EFFORT_GRID ->
                        EndSetRpeGrid(
                            state,
                            viewModel,
                            failedTile = SetEndControl.FAILED_TILE in controls,
                            lastRepTapAtMs = lastRepTapAtMs,
                        )
                    SetEndControl.END_FAILED ->
                        FailSetButton(state, viewModel, gridBelow = SetEndControl.EFFORT_GRID in controls)
                    SetEndControl.END_UNRATED ->
                        if (state.setEndKind == SetEndKind.SENSOR_COUNTED) {
                            EndSetUnratedButton(viewModel)
                        } else {
                            EndSetEarlyButton(viewModel)
                        }
                    // Drawn inside the grid, never on its own.
                    SetEndControl.FAILED_TILE -> Unit
                }
            }
            if (state.mayCancelSet) CancelSetButton(viewModel)
        }
    }
}

/**
 * Takes back a START tapped too early or on the wrong exercise (owner's ruling
 * 2026-11-06, UX review rest F2). Drawn only while `RecordState.mayCancelSet`
 * holds -- the lead-in, or a rep set with no rep counted yet -- and it writes
 * nothing: the screen the set was started from comes back with the same set
 * next. A text button under the end-set controls, because it is the way out
 * of a mistake rather than a way to end a set.
 */
@Composable
private fun CancelSetButton(viewModel: RecordViewModel) {
    Spacer(Modifier.height(6.dp))
    TextButton(onClick = confirmTap(viewModel::cancelSet), modifier = Modifier.fillMaxWidth()) {
        Text("CANCEL SET", style = MaterialTheme.typography.titleMedium, color = BarColors.Sub)
    }
    SectionCaption("Nothing is saved · the same set is next")
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
 * The standalone failure control of a guided or timed set that is UNDER WAY:
 * alone before the set is complete (#186), and above the effort grid once it
 * is ([gridBelow], below).
 *
 * Before completion the grid is not drawn, because how the set went is not a
 * fact until the set is over -- the owner's "earlier than that the only option
 * available should be fail". So until then this is the whole exit, and it
 * stores the lifter's own TAPPED failure: [SetRating] with no RPE and
 * `failed = true`, the value the grid's failure tile writes elsewhere.
 *
 * WHAT IT COSTS, and it is not hidden from the lifter: every early exit on a
 * set that is UNDER WAY now records a failure, including the ones that are not
 * -- a rack taken, a cramp. The caption (drawn while it stands alone) says
 * the set is rateable afterwards,
 * because that is the half that keeps #137 from coming back: the rest screen's
 * row for a Fail-ended set reads EFFORT -- FAILED and carries a Change action,
 * and re-rating overwrites the tapped verdict. (EFFORT -- NOT RATED with a
 * Rate action is the OTHER row, for a set carrying no verdict at all, such as
 * an auto-ended hold its armed unit's release did not put short (#311); both
 * open the same grid.)
 *
 * This control is not drawn during the set's lead-in. A set whose clock or
 * cadence had not begun has not failed at anything, so that window offers
 * [EndSetEarlyButton] instead (#186).
 *
 * Deliberately not the shape of [EndSetEarlyButton]. That one ends a set with
 * no verdict at all and is the SKIP beside a grid; this one states a verdict
 * on a set that is already running, so it is a filled button rather than an
 * outlined afterthought.
 *
 * [gridBelow] is the completed set (UX review 2026-11-06, record-live F3).
 * This button used to be REPLACED at "Done" by the grid, whose first row of
 * headroom tiles then sat where it had been, so a lifter grinding a last rep
 * that failed and reaching for the red button stored "Could have added much
 * more". It now stays put, the same size and in the same place, and the grid
 * opens under it without its own failure tile; the caption gives way to a
 * spacer, since the grid's own caption follows.
 */
@Composable
private fun FailSetButton(state: RecordState, viewModel: RecordViewModel, gridBelow: Boolean) {
    // The failure wording of the set's own ladder, so this button says the
    // same word as the failure tile other sets' grids draw. A hold breaks
    // early; a snatch is missed.
    //
    // Named, never counted to. `.last()` was right only because EffortScale
    // happens to append the failure tile last, and that order is pinned in a
    // different module: reordering the tiles there would put a headroom
    // caption on a red destructive button and nothing in `:app` would catch
    // it.
    val label =
        rpeOptions(
            state.currentIsTimed,
            currentKind(state) == ExerciseKind.EXPLOSIVE,
            state.weightUnit,
            // The slot in front of the lifter: during IN_SET `currentSlot` is
            // the set being recorded, not the one coming up. Null on an ad-hoc
            // set, which no plan declared anything for. It changes no failure
            // wording -- that is the set kind's -- and is passed because the
            // parameter is not defaulted.
            EffortScale.askFor(state.currentIsTimed, state.currentSlot?.progression),
        ).first { it.failed }.description
    Button(
        onClick = confirmTap { viewModel.endSet(SetRating(null, failed = true)) },
        modifier = Modifier.fillMaxWidth().height(64.dp),
        colors = ButtonDefaults.buttonColors(containerColor = BarColors.Red),
    ) {
        Text(label.uppercase(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
    Spacer(Modifier.height(6.dp))
    if (gridBelow) {
        // The grid's own caption follows directly; this one only has to keep
        // the gap between the red button and the first tile row.
        Spacer(Modifier.height(18.dp))
    } else {
        SectionCaption("How that set felt is asked once it is finished · you can still rate this one while you rest")
    }
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
 * it. A TAPPED failure is one no later REP CORRECTION can clear:
 * `SetRatingTracker.correct` re-derives the derived half and leaves the tapped
 * half standing. Re-rating
 * the set does overwrite it, so it is not permanent -- it is out of reach of
 * the one repair that fits the mistake. A lifter who did all five reps but only
 * tapped "+1 REP" three times reaches exactly this control, and a tapped
 * verdict here would defeat correcting the count, which is what they would
 * actually go and do.
 */
@Composable
internal fun EndSetEarlyButton(viewModel: RecordViewModel) {
    OutlinedButton(
        onClick = confirmTap { viewModel.endSet() },
        modifier = Modifier.fillMaxWidth().height(64.dp),
    ) {
        Text("END SET EARLY", style = MaterialTheme.typography.titleLarge, color = BarColors.Red)
    }
    Spacer(Modifier.height(6.dp))
    SectionCaption(END_SET_EARLY_CAPTION)
}

/** What END SET EARLY's caption says, a constant so `ShortOfTargetCopyTest` can read it. */
internal const val END_SET_EARLY_CAPTION =
    "Ends the set with no rating · a set that came up short is recorded as short of target"

/**
 * The unrated way out of a set the SENSOR is counting (UX review 2026-11-06,
 * record-live F6). Drawn under the grid, never above it: the +1 REP button
 * sits above the grid, and a slip off it must not end the set.
 *
 * Not END SET EARLY, because on this set nothing is early: no shortfall is
 * derived from a sensor count, and the lifter may simply be done and not want
 * to rate at the bar. The rest screen then asks "How hard was that set?"
 * (`RestEffortPromptPolicy`, keyed on `SetFeedback.sensorCounted`).
 */
@Composable
private fun EndSetUnratedButton(viewModel: RecordViewModel) {
    OutlinedButton(
        onClick = confirmTap { viewModel.endSet() },
        modifier = Modifier.fillMaxWidth().height(64.dp),
    ) {
        Text("END SET", style = MaterialTheme.typography.titleLarge)
    }
    Spacer(Modifier.height(6.dp))
    SectionCaption("Ends the set without a rating · you're asked how it felt while you rest")
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
private fun EndSetRpeGrid(state: RecordState, viewModel: RecordViewModel, failedTile: Boolean, lastRepTapAtMs: Long?) {
    val options =
        rpeOptions(
            state.currentIsTimed,
            currentKind(state) == ExerciseKind.EXPLOSIVE,
            state.weightUnit,
            // The set being rated is the one still in front of the lifter, so
            // its own exercise's declaration words the headroom rungs (#244).
            // Null on an ad-hoc set, which no plan declared anything for.
            EffortScale.askFor(state.currentIsTimed, state.currentSlot?.progression),
        ).filter { failedTile || !it.failed }
            // On a sensor-counted set "2 reps left" and "1 rep left" come
            // first, so the usual answer is the first row (record-live F4).
            .let { SetEndControlPolicy.gridOrder(state.setEndKind, it) { option -> option.rpe } }
    SectionCaption("Tap how that set felt to end it")
    Spacer(Modifier.height(6.dp))
    options.chunked(2).forEach { row ->
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        ) {
            row.forEach { option ->
                // Built here, run only once the slip guard accepts the tap: a
                // thumb that slips off +1 REP lands on this grid, and that tap
                // must neither end the set nor buzz as if it had (record-live F2).
                val endWithBuzz = confirmTap { viewModel.endSet(SetRating(option.rpe, failed = option.failed)) }
                RpeTile(option, selected = false, modifier = Modifier.weight(1f)) {
                    if (EffortGridSlipGuard.accepts(SystemClock.uptimeMillis(), lastRepTapAtMs)) endWithBuzz()
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
 * Everything about the set that had just finished -- what it was, and every
 * correction to it -- sits below the next-set block, in [LastSetDetail]. Since
 * #237 that is a BOX stating the record in one line, a Correct button, and a
 * rep-quality card carrying a 64dp chart; the six inline correction rows that
 * stood there are inside the popup the button opens. THREE things are still drawn
 * above it, and all three for the same reason. The first is
 * [RestEffortPromptSection] (#283), the effort question a set nothing asked at
 * its end is asked here instead -- which is a hold that RAN ITS CLOCK, because
 * such a hold ends itself and draws no grid. A hold the lifter ended with the
 * failure control is asked nothing at all: "if you can't do it, it's failed" is
 * the owner's rule on #283, and the reason page below is that set's page. It is
 * above the other two because both depend on its answer. The second is the reason page the app opens BY
 * ITSELF, drawn here under the header: the screen scrolls to 0 on entering
 * RESTING, so a question drawn below the fold is a question the lifter starts
 * the next set without seeing. See [SetLimiterPagePlacement]. The field report for v0.1.37 is
 * "try to get the rest dialog to fit on one page without scrolling"; that
 * report, not this arithmetic, is the evidence it did not fit, because `:app`
 * has no reachable test seam for a Compose layout and nothing here has been
 * measured on a device -- see the bench-harness evidence in the commit that
 * moved START.
 *
 * The third is [NextSetNudgeSection], the headroom grid, moved above
 * [NextSetBlock] by #236: it is one tap that acts on the rating just given,
 * and below the Up next card plus five adjustment controls it was below the
 * fold too. The owner's field report after v0.1.50 is that it got lost there.
 * WHETHER IT NOW FITS ABOVE THE FOLD IS NOT CLAIMED. The sentence that stood
 * here said the move was measured on the bench in the commit that made it, and
 * it is deleted rather than softened: no bench run happened. The emulator slot
 * was free throughout, but this machine's free RAM never rose above 1358 MB
 * against the roughly 3 GB the headless AVD needs, polled every 60 s across 32
 * minutes. So the fit is a [Field] question, and the only evidence for it is
 * the owner's report that the old order did not fit.
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
    // Keyed on setsCompleted: an open page must close when the next set ends
    // rather than carrying a stale set's answer into the following rest.
    //
    // `dismissed` is not in RecordState, and the distinction is the one #189
    // turns on: it is not a fact about the set, it is whether this screen has
    // already offered the page. A skip stores nothing -- absence is already
    // what the row carries -- so there is nothing for the record to remember.
    var dismissed by remember(state.setsCompleted) { mutableStateOf(false) }
    val timed = state.lastFeedback?.actualDurationS != null
    // `changing` is passed FALSE and no longer exists as state (#237). It was
    // the lifter's own tap on the reason ROW, and the row is gone: every
    // correction, the reason among them, is now behind the Correct button's
    // popup, which draws its own tiles rather than asking this screen to place
    // a page under a row. So SetLimiterPagePlacement.CORRECTION is unreachable
    // from here. The member and the policy that returns it are LEFT ALONE
    // rather than deleted -- SetLimiterPolicy.placement is pinned in
    // :core:model and moving a pinned policy is not this change.
    val placement =
        if (state.lastFeedback != null &&
            SetLimiterPolicy.offersCorrection(
                state.lastSetFailed,
                state.lastSetRpe,
                state.lastSetLimiter,
            )
        ) {
            SetLimiterPolicy.placement(
                failed = state.lastSetFailed,
                rpe = state.lastSetRpe,
                limiter = state.lastSetLimiter,
                dismissed = dismissed,
                changing = false,
            )
        } else {
            SetLimiterPagePlacement.NONE
        }
    RestHeader(state, viewModel)
    Spacer(Modifier.height(6.dp))
    // FIRST of the three things drawn above the fold, and above the limiter
    // page deliberately (#283). A hold that runs its clock out is never asked
    // how it went while it is ending -- the clock ends it -- so this is the
    // only place that question can be put, and it is the question the two rows
    // below it depend on: the limiter page asks why a set ended and the
    // headroom grid acts on the rung this stores. A hold ended by the failure
    // control is asked nothing here; it gets the reason page below and no
    // effort question. Decides nothing itself; see [RestEffortPromptSection].
    RestEffortPromptSection(state, viewModel)
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
        // onDone is a no-op since #237: it closed the reason page the lifter
        // had opened from the row, and the row is gone. This is the page the
        // app opened BY ITSELF, which closes on the stored answer.
        LimiterPage(state, timed, viewModel, onSkip = { dismissed = true }) {}
        Spacer(Modifier.height(10.dp))
    }
    // Sets two onwards start from here, not from READY, and this is the screen
    // where the lifter has a rest period to spend fixing it.
    PermissionBanner()
    // Same reason as the banner above it, for the sensor rather than the
    // permission: READY renders on the way to a session's first set, on the
    // way to a continued session's first set of this visit (#48), and
    // again after a body-weight refusal taken from the rest screen -- see
    // SkipSetSection -- so a card drawn only there can name a silent unit
    // more than once, though almost always just before set one. Every set
    // from the second onwards is armed from this screen, and the rest period
    // is the window in which switching a unit on or re-seating it still costs
    // the lifter nothing.
    ArmedSilenceCard(state)
    // ABOVE the card it changes, not below the whole block that carries it
    // (#236). It used to be drawn after NextSetBlock -- the Up next card plus
    // five adjustment controls -- so a lifter who had just rated a set in the
    // headroom range scrolled past the thing the grid changes in order to
    // reach the grid, and the owner's field report is that it got lost that
    // far down. Here the column reads in the order the decision is made: that
    // set had more in it, put it on the next set, and the next set is drawn
    // immediately below with the change struck into it.
    //
    // The move changes which side of this row its own inputs sit on, and that
    // is the part worth reading twice. Four of them are written by rest-screen
    // controls, and since #236 ALL FOUR are drawn BELOW it. THREE -- the
    // warm-up toggle, the effort re-rating and the rep and duration
    // corrections -- are inside LastSetDetail and, since #237, one tap further
    // away behind its Correct button rather than inline under it. The FOURTH,
    // AddSetSection, is inside NextSetBlock. Appending a set is the case that
    // turns on: on the last set of an exercise this row draws nothing, because
    // setsLeftInExercise is 0 and nextSlot may be null, and the append gives
    // it both -- so the row now appears ABOVE the button that was just tapped
    // and pushes that button down, where before it appeared below it and moved
    // nothing under the finger. Whether that reaches #137's stacked-target
    // hazard is UNMEASURED and is a [Field] question, not a property claimed
    // here; the bench never appended a set from a rest in which this row could
    // appear.
    //
    // A FIFTH writer now sits ABOVE this row and the count above is no longer
    // the whole picture: RestEffortPromptSection, drawn a few lines up, reaches
    // lastSetRpe and lastSetFailed through the same ratedState. On a hold that
    // ran its clock that is what makes this row appear at all; on one broken
    // early the question never draws. The column behaviour between the
    // two is a [Field] question; see [NextSetNudgeSection]'s KDoc, which is the
    // canonical copy of this paragraph rather than a second one to keep in step.
    // Decides nothing itself; see [NextSetNudgeSection].
    NextSetNudgeSection(state, viewModel)
    NextSetBlock(state, viewModel)
    SessionCloseControls(state, viewModel)
    Spacer(Modifier.height(16.dp))
    // `onReasonRetracted` is the second half of what [LimiterPage]'s CLEAR
    // foot does -- it runs `limitLastSet(null)` AND `onSkip()`. Without it a
    // confirm that clears the reason makes SetLimiterPolicy.prompts true
    // again and re-opens the page above.
    LastSetDetail(
        state = state,
        viewModel = viewModel,
        timed = timed,
        onReasonRetracted = { dismissed = true },
    )
}

/** What happens next: the prescription with the changes struck into it, and the way in. */
@Composable
private fun NextSetBlock(state: RecordState, viewModel: RecordViewModel) {
    val next = state.nextSlot
    if (!state.adHoc && next != null) {
        if (next.isExerciseChange) {
            MoveSensorCard(next.exercise.displayName, next.implement)
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
        NextSetControlsRow(state, viewModel, next, next = true)
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
        Spacer(Modifier.height(8.dp))
        AddSetSection(state, viewModel)
        AddSetCaption(state)
        RemoveSetSection(state, viewModel)
        // A finisher the plan does not list: the chooser's "Different
        // exercise..." puts one off-plan set next, which brings START back the
        // way an appended set does (record-setup F2).
        SwitchExerciseSection(state, viewModel, Modifier.fillMaxWidth())
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
        onClick = confirmTap(viewModel::startNextSet),
        // An ad-hoc rest draws AdHocForm, whose load box says why (record-setup F4).
        enabled = !state.adHocLoadRefused(),
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
            // Kept a TextButton (#201): the secondary close while START NEXT SET is primary; the
            // branch above fills it when finishing is what is left.
            TextButton(onClick = viewModel::askSessionRpe, modifier = Modifier.fillMaxWidth()) {
                Text("Finish session", color = BarColors.Sub)
            }
        }
    }
    if (RestControl.RATE_SESSION in controls) {
        SessionRpePanel(viewModel)
    }
    if (RestControl.RETRY_FINISH in controls) {
        UnclosedSessionNotice(state, viewModel)
    }
}

/**
 * How was the whole session? One tap answers it, one tap skips it, and either
 * way the session closes (#159).
 *
 * DELIBERATELY NOT THE EFFORT GRID'S VOCABULARY. That grid says "3 reps left"
 * about ONE set on 1-10, and below the counted end it says whatever that
 * exercise progresses in -- "Could have added 10-15 lb", "About 3-4 reps
 * left", "Could have gone about 15 s longer" or a bare "Easy" (#244). This is
 * the whole workout on 1-10 and there are no reps left in a session, nor a
 * progression for one to be asked in. The two scales now
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
        // Kept a TextButton (#201): #159's deliberately subdued skip, so declining to rate does not
        // compete with the ratings.
        TextButton(onClick = { viewModel.finishSession(null) }, modifier = Modifier.fillMaxWidth()) {
            Text("Finish without rating", color = BarColors.Sub)
        }
    }
}

/**
 * A close that came back failed, by cause (#333). Where the session write
 * did not land (FAILED), the row is still open: no end time, no heart-rate or
 * HRV summary, no rating and no skips. Where it landed and only the rest
 * recorded after the last set did not (REST_UNSAVED, #109), the session is
 * finished and the row has its end time; only that window is missing. Every
 * set is already stored either way, so what is at risk here is smaller than an
 * unsaved set and is not nothing -- whatever is still missing is held in memory
 * here and in no durable place at all. The title says which, from
 * [SessionNotClosedCopy.title].
 *
 * It replaces Finish session rather than sitting beside it, as
 * [UnsavedSetNotice] replaces the effort grid: two controls that both close the
 * session would launch the same work from different inputs, and one of them
 * would be the wrong one.
 */
@Composable
private fun UnclosedSessionNotice(state: RecordState, viewModel: RecordViewModel) {
    // The prompt's own title, worded by cause in :core:model (#333), so the
    // notice and the dialog cannot disagree about whether the session finished.
    Text(
        SessionNotClosedCopy.title(state.closeUnwritten ?: UNKNOWN_UNWRITTEN).uppercase(Locale.ROOT),
        style = MaterialTheme.typography.titleMedium,
        color = BarColors.Red,
    )
    Spacer(Modifier.height(6.dp))
    SectionCaption("Your sets are saved. Part of the session's own record is not")
    Spacer(Modifier.height(10.dp))
    Button(
        onClick = viewModel::retrySessionClose,
        modifier = Modifier.fillMaxWidth().height(64.dp),
    ) {
        Text(
            SessionNotClosedCopy.RETRY_LABEL,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
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
 * could have added WHEN [ask] is a load question, and that figure is authored
 * per unit rather than converted -- "+10-15 lb" and "+5 kg", never "+4.5 kg".
 * It is passed on every ask and ignored by three of the four: seconds, reps
 * and feelings are the same in both gyms.
 *
 * [ask] is which noun the HEADROOM rungs use (#244), and it is NOT defaulted:
 * the four call sites answer it from two different places -- the set-end grid
 * resolves the slot in front of the lifter through `EffortScale.askFor`, the
 * rest screen's two read the word FROZEN with the finished set -- and a
 * default would let a new call site ask a pull-up about load by saying
 * nothing.
 */
internal fun rpeOptions(timed: Boolean, explosive: Boolean, unit: WeightUnit, ask: EffortAsk): List<RpeOption> =
    EffortScale.tiles(timed = timed, explosive = explosive, unit = unit, ask = ask).map { tile ->
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

// RpeSelector and LimiterLine are DELETED here (#237). They were the effort
// correction grid and the reason row of the inline stack, and both wrote to
// the view-model on the tap. The popup behind the Correct button draws the
// same tiles from the same tables and writes on its own confirm instead;
// leaving these standing would have left a second, unbuffered write path for
// the two facts the popup drafts. SetLimiterPolicy.lineAction, which only
// LimiterLine called, keeps its pins in :core:model and is left alone.

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
    SectionCaption(SetLimiterPolicy.pageTitle(state.lastSetFailed))
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
            // Back to the stored words, which is what this box is seeded from.
            // Nothing here reaches Room without a SAVE, so what this drops is a
            // box that reopens holding words the lifter had backed out of.
            onBack = {
                words = state.lastSetLimiterNote ?: ""
                typing = false
            },
        )
        return
    }
    var group: SetLimiterGroup? = null
    for (tile in SetLimiterScale.tiles(timed, state.lastSetFailed)) {
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
        // just left would reopen at the top of the screen and ask again.
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
internal fun LimiterWords(words: String, onWords: (String) -> Unit, onSave: () -> Unit, onBack: () -> Unit) {
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
internal fun LimiterTile(tile: SetLimiterTile, onClick: () -> Unit) {
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
internal fun RpeTile(option: RpeOption, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
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
                // body-weight work -- so halving it would read "2 × 50 kg" for
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
                    SideArrow(
                        feedback.side,
                        MaterialTheme.typography.titleMedium.fontSize,
                        Modifier.padding(end = 6.dp),
                        color = BarColors.Text,
                    )
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
        // red against a record that says the target was met. The text and
        // the tone are HistoryTarget.heldChip's, the history card's own, so
        // the two screens cannot colour one hold two ways (#320).
        feedback.effectiveDurationS?.let { actual ->
            val chip = HistoryTarget.heldChip(actual, feedback.plannedDurationS)
            VerdictChip(chip.text, chip.tone.chipTone())
        }
        // The wording, the tick and the tone are TempoScoreLabel's in
        // :core:model, so this screen and the history screen cannot drift and
        // the decision is reachable by a test. #56.
        tempoScoreOf(analysis)?.let { VerdictChip(it.text, it.tone.chipTone()) }
        // WHICH FIGURE LEADS FOLLOWS THE REGIME (#250). On a controlled set the
        // prescribed tempo fixed the drive's speed, so velocity loss measures
        // how well the lifter held the count and not how much they had left --
        // and the pill that reads "-41% vel" beside a tempo score is the
        // reading the whole issue is about. Range consistency takes the slot
        // there. The figure is still PUBLISHED in the export on both regimes;
        // this decides which number the lifter reads between sets.
        //
        // A null regime draws exactly what this row drew before #250. The
        // decision itself is VelocityLossRegime in :core:model, where a test
        // runs on every push -- nothing here decides anything, which is the
        // arrangement the tempo chip above already uses (#56).
        // Reads the accessor rather than comparing against a member (#277):
        // VelocityLossRegime.readsVelocityLoss's own KDoc says the screens read
        // it precisely so a third regime cannot come apart from this
        // comparison silently, and comparing against CONTROLLED only agreed
        // with it because the enum happens to have exactly two members today.
        // Null stays the identity -- the same branch a null regime took before
        // #250 existed.
        if (feedback.velocityLossRegime?.readsVelocityLoss == false) {
            RangeConsistencyChip(analysis)
        } else {
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
                VelocityLoss.NotEnoughReps, VelocityLoss.NoReference, VelocityLoss.NoEligiblePair -> Unit
            }
        }
        hrBpm?.let { VerdictChip("♥ $it", ChipTone.NEUTRAL) }
        hrvMs?.let { VerdictChip("HRV ${it}ms", ChipTone.NEUTRAL) }
    }
}

/**
 * [verdicts] is handed in rather than read off [analysis], because whether a
 * stored verdict line is shown depends on the SET's regime and a chart holds no
 * regime. `CoachingVerdictPolicy.forRegime` decides it once at the call site
 * above, where the regime is (#261). The same holds for [EccTempoChart] and
 * [ConVelocityChart] below.
 */
@Composable
internal fun PeakVelocityChart(analysis: SetAnalysis, verdicts: List<String>) {
    Text("Peak velocity per rep (m/s)", style = MaterialTheme.typography.bodySmall, color = BarColors.Sub)
    Spacer(Modifier.height(8.dp))
    // EVERY REP, including one whose span carries a sample above the physical
    // bound. One bar is drawn per rep and velocityLossColor shades each against
    // the maximum of the values it is handed, so narrowing this list while still
    // drawing that rep's bar would shade the tallest bar on screen as a large
    // loss against a smaller best. AccelArtefact.peakEligible's KDoc carries the
    // reasoning. The per-rep bars a withheld rep contributes are still that
    // rep's own inflated peak; the residue behind them is #291.
    val peaks = analysis.reps.map { it.peakConVelMps }
    RepBars(
        values = peaks,
        plannedSlots = null,
        colorFor = { _, v -> velocityLossColor(v, peaks, null) },
        barHeight = 64,
    )
    Spacer(Modifier.height(6.dp))
    PowerLine(analysis)
    // The SET-LEVEL claim, over the reps AccelArtefact.isPeakEligible admits
    // (#290, #306). This line printed "Best 2.52 m/s" on a 55 lb press two
    // lines above an already-corrected "Drive power: peak 329 W".
    val best = AccelArtefact.setPeakConVelMps(analysis.reps)
    if (best != null && best > 0) {
        // Absent rather than zero where the set's own last rep is withheld, and
        // measured against the same reps as [best]. The decision is
        // AccelArtefact.terminalPeakLossPct's, where a test reaches it.
        val lastLossPct = AccelArtefact.terminalPeakLossPct(analysis.reps)
        Text(
            if (lastLossPct != null) {
                String.format(Locale.US, "Best %.2f m/s · last rep −%.0f%% off best.", best, lastLossPct)
            } else {
                String.format(Locale.US, "Best %.2f m/s · last rep's peak withheld.", best)
            },
            style = MaterialTheme.typography.bodySmall,
            color = BarColors.Sub,
        )
    }
    verdicts.take(2).forEach {
        Text("• $it", style = MaterialTheme.typography.bodySmall, color = BarColors.Sub)
    }
}

@Composable
internal fun EccTempoChart(analysis: SetAnalysis, targetEccS: Double, verdicts: List<String>) {
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
    verdicts.take(2).forEach {
        Text("• $it", style = MaterialTheme.typography.bodySmall, color = BarColors.Sub)
    }
}

@Composable
internal fun ConVelocityChart(analysis: SetAnalysis, verdicts: List<String>, readsVelocityLoss: Boolean = true) {
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
    // Gated on the same regime the chip above already reads (#276): on a
    // CONTROLLED set the prescribed tempo fixed the drive's speed, so a
    // slowing rep is a count held poorly and not fatigue, and this caption
    // stated the figure as fact with no regime attached at all. Default true
    // keeps a null regime -- not decidable -- drawing exactly what this row
    // drew before #250's chip gate existed.
    if (readsVelocityLoss) {
        VelocityLoss.of(analysis.reps).pctOrNull?.let {
            Text(
                "Velocity loss ${trim(it)}% across the set.",
                style = MaterialTheme.typography.bodySmall,
                color = BarColors.Sub,
            )
        }
    }
    verdicts.take(2).forEach {
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
    // Over the reps AccelArtefact.isPeakEligible admits (#290, #306). This line
    // printed "peak 3606 W" on a 55 lb press, which is the reading the owner
    // ignores these screens for.
    val peak = AccelArtefact.setPeakPowerW(analysis.reps) ?: return null
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
                SideArrow(side, MaterialTheme.typography.titleMedium.fontSize, Modifier.padding(end = 8.dp))
                Text(
                    struckLine("${slot.exercise.displayName} — ", values),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            // How to load the movement, directly under it, in the card's own
            // text -- not the tiny blue caption it used to be at the bottom of
            // the card (#253). It is the second thing to read and the second
            // line on the card, and it is READ AT A RACK, where a caption
            // under everything else is the wrong size in the wrong place.
            //
            // WHAT DECIDES IT IS THE PLAN, not the exercise id. Nothing is
            // drawn where the plan declared no implement, which is every plan
            // written before schema 1.12 -- the import gate warns about the
            // pair case and the version log carries the rest.
            //
            // The decision is `ImplementLine`'s, in :core:model, and the
            // handover is `PlannedSlot.cardInstruction`. Both are tested; this
            // -- the size, the colour, the position -- is not, because nothing
            // in this repository draws Compose.
            slot.cardInstruction(unit, plateLoadKgOverride)?.let {
                Text(it, style = MaterialTheme.typography.titleMedium)
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
        }
    }
}

@Composable
private fun FinishedStage(state: RecordState, navController: NavController) {
    Text(SessionFinishedCopy.headline(state.sessionId != null), style = MaterialTheme.typography.headlineSmall)
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
