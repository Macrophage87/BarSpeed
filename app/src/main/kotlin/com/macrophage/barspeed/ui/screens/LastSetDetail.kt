// The rest screen's finished-set surface, lifted out of RecordScreen.kt by
// #208 and turned inside out by #237.
//
// It was a stack of six inline correction rows -- effort, reason, warm-up
// mark, load (#205), rep count, held seconds -- each with its own caption and
// its own buttons, plus a display-only rep-quality card. Every correction was
// one tap away at all times, which made the set just finished read as a FORM;
// what was lifted, for how many and how it went was spread across the rows
// instead of stated once. It is now a BOX in the shape of the "Up next" card
// stating the record in one line, one Correct button, and a popup holding the
// six corrections. What a correction DOES is unchanged: the popup's confirm
// calls the same RecordViewModel methods the rows called, and
// LastSetCorrections.kt is untouched.
package com.macrophage.barspeed.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.macrophage.barspeed.model.BodyweightLoadDisplay
import com.macrophage.barspeed.model.EffortCorrectionPolicy
import com.macrophage.barspeed.model.LastSetRecordPolicy
import com.macrophage.barspeed.model.SetLimiter
import com.macrophage.barspeed.model.SetLimiterGroup
import com.macrophage.barspeed.model.SetLimiterPolicy
import com.macrophage.barspeed.model.SetLimiterScale
import com.macrophage.barspeed.model.SetLoadPolicy
import com.macrophage.barspeed.model.TimedSetEndPolicy
import com.macrophage.barspeed.model.WarmupMarkPolicy
import com.macrophage.barspeed.record.RecordState
import com.macrophage.barspeed.record.RecordViewModel
import com.macrophage.barspeed.record.SetFeedback
import com.macrophage.barspeed.record.carryBlock
import com.macrophage.barspeed.record.standingAddedKg
import com.macrophage.barspeed.ui.BarColors
import com.macrophage.barspeed.ui.components.SectionCaption
import com.macrophage.barspeed.ui.components.SideArrow

/**
 * The set that just finished, stated once, with one way to correct it (#237).
 *
 * THE BOX IS THE SHAPE OF THE "Up next" CARD ON PURPOSE. The two are the
 * before and the after of the same rest period, and reading as one another is
 * the whole point: the same words for a count, a hold and a load, the same
 * separator, the same strike drawn by the same `struckLine`. What each card
 * PAIRS differs and [LastSetRecordPolicy] says why -- the card strikes the
 * plan's prescription against the lifter's change, this strikes what the row
 * was written with against the correction standing over it.
 *
 * THE CORRECTIONS ARE BEHIND ONE BUTTON, NOT SIX ROWS. What each one does is
 * unchanged; where it is has moved. Two things follow that are worth reading
 * as consequences rather than as decoration:
 *
 *  - A popup does not reflow the column under the lifter's finger. The inline
 *    reason page did -- see [RestingStage]'s note on #137's stacked-target
 *    hazard -- and a dialog cannot, because it is drawn over the screen rather
 *    than inserted into it.
 *  - Every correction is now one tap further away. The rest period is the only
 *    window in which a set can be corrected at all, and nothing anywhere can
 *    edit a stored set once this screen is gone, so a control moved behind a
 *    tap is a control some lifters will not reach. The trade is the owner's
 *    ask; it is stated here rather than assumed away.
 *
 * [RepQualityCard] STAYS OUTSIDE THE POPUP, below the button. It is display
 * only, and the popup is a thing whose confirm APPLIES -- a read-only 64dp
 * chart inside it would read as something the confirm does. It is also the one
 * item on this surface the lifter reads rather than acts on, and the rest
 * period is when it is read; behind a tap it would be read never.
 *
 * `changingEffort` is gone with the effort row. The popup's own open flag is
 * keyed on `setsCompleted` in its place, so a popup left open when the next set
 * ends closes rather than carrying a stale set's draft into the following rest.
 *
 * WHETHER ANY OF IT DRAWS AS DESCRIBED IS NOT CLAIMED HERE. Nothing on this
 * surface has been rendered on a device. The emulator lock at the session
 * scratchpad read "free" throughout, so nothing contended for the slot; the
 * blocker was memory. Free physical RAM was sampled every 60 s from 15:58:09Z
 * to 16:31:11Z on 2026-09-04, 28 samples across 33 minutes, and never reached
 * the roughly 3 GB a headless `barspeed-api35` boot needs -- it peaked once at
 * 2925 MB and otherwise sat between 271 and 1135 MB. The AVD was never started,
 * so no device was started and none was killed. The box's one line, its strike
 * and the popup's scroll are all [Field] questions, and this surface stays
 * compile- and lint-gated.
 */
@Composable
internal fun LastSetDetail(state: RecordState, viewModel: RecordViewModel, timed: Boolean) {
    val feedback = state.lastFeedback ?: return
    var correcting by remember(state.setsCompleted) { mutableStateOf(false) }
    LastSetCard(state, feedback)
    OutlinedButton(onClick = { correcting = true }, modifier = Modifier.fillMaxWidth()) {
        Text("CORRECT", color = BarColors.Sub)
    }
    if (correcting) {
        CorrectionDialog(state, feedback, viewModel, timed) { correcting = false }
    }
    Spacer(Modifier.height(6.dp))
    RepQualityCard(feedback)
}

/**
 * The record as it stands: the figures on one line, the words under them.
 *
 * WHAT IT SAYS IS [LastSetRecordPolicy]'S DECISION and not this file's, for
 * the reason every other rule on this screen has been lifted out for: `:app`
 * has no reachable test seam for a composable, so a rule written here is a rule
 * nothing on the CI path can fail.
 *
 * The effort WORDING comes from [rpeOptions], which is `:app`'s own table, so
 * the tile description is resolved here and handed over; everything the policy
 * decides from it is the policy's.
 */
@Composable
private fun LastSetCard(state: RecordState, feedback: SetFeedback) {
    val options = rpeOptions(feedback.actualDurationS != null, feedback.explosive, state.weightUnit)
    val rated = options.firstOrNull { !it.failed && it.rpe == state.lastSetRpe }?.description
    val values =
        LastSetRecordPolicy.values(
            kind = feedback.kind,
            bodyweight = feedback.bodyweight,
            unit = state.weightUnit,
            side = feedback.side,
            recordedAddedKg = feedback.addedKg,
            correctedAddedKg = feedback.loadOverrideAddedKg,
            // The count the ROW WAS WRITTEN WITH, never the DSP's count.
            // `repsOverride` is seeded with the app's own tally at set end, so
            // on a guided or a sensorless set it stands over an analysis that
            // counted nothing, and this pair used to strike "0 reps" through
            // on a set nobody had corrected (#237 round 2).
            countedReps = feedback.recordedReps,
            correctedReps = feedback.repsOverride,
            recordedDurationS = feedback.actualDurationS,
            correctedDurationS = feedback.durationOverrideS,
        )
    val status =
        LastSetRecordPolicy.status(
            ratedDescription = rated,
            rpe = state.lastSetRpe,
            failed = state.lastSetFailed,
            limiter = state.lastSetLimiter,
            limiterNote = state.lastSetLimiterNote,
            timed = feedback.actualDurationS != null,
            warmupDeclared = state.lastSetWarmup,
            warmupMark = state.lastSetWarmupMark,
        )
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(14.dp)) {
            SectionCaption("Last set")
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 4.dp),
            ) {
                // The rest screen can hold two arrows at once -- this one and
                // "Up next" -- so colour separates last from next, exactly as
                // RestHeader's does.
                SideArrow(feedback.side, Modifier.padding(end = 8.dp), color = BarColors.Text)
                Text(
                    struckLine("${feedback.exerciseName} — ", values),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                // Red for a failure, amber where there is something for the
                // lifter to do and the rest period is the only window to do it
                // in, which is the colouring the two rows this replaces used.
                color =
                when {
                    state.lastSetFailed -> BarColors.Red
                    status.contains(EffortCorrectionPolicy.NOT_RATED) ||
                        status.contains(SetLimiterPolicy.NOT_GIVEN) -> BarColors.Amber
                    else -> BarColors.Sub
                },
            )
        }
    }
    Spacer(Modifier.height(6.dp))
}

/**
 * Every correction the rest screen can make to the set just finished, drafted
 * and then applied together (#237).
 *
 * THE DRAFT IS LOCAL AND DIES WITH THE POPUP. Nothing here is in
 * [RecordState]; cancel discards by returning without calling anything, which
 * is the owner's ask and the reason a draft exists at all. Dismissal by tapping
 * outside is turned OFF -- a set can only be corrected while this screen is up,
 * and a stray tap on the scrim throwing away corrections the lifter had just
 * dialled in would be the expensive direction to fail. Back still dismisses.
 *
 * CONFIRM CALLS THE SAME METHODS THE ROWS CALLED, in a named order: load, then
 * the warm-up mark, then the reason, then the count or the hold, then the
 * rating. The first three write columns nobody else here writes and cannot
 * collide with anything. THE LAST TWO BOTH WRITE THE RATING ROW, and that is a
 * real and unmeasured hazard when the lifter changes BOTH the count and the
 * rating in one confirm: `applyRepCorrection` reads `lastSetRpe` out of the
 * state at launch and `applyRating` publishes its new rating only after Room
 * returns, so the two `rateSet` writes carry different rpe values and Room's
 * default query executor is a pool that does not order them. The row may keep
 * the count correction's earlier rating while this screen shows the new one.
 * It is the same unordered window `applyLoadCorrection` and `applyWarmupMark`
 * already document for two fast taps -- what changes is that a confirm reaches
 * it in one frame rather than at human tapping speed. Not fixed here: fixing it
 * means making the corrections awaitable, which is a change to
 * LastSetCorrections.kt that #237 puts out of scope. Read from source; never
 * observed on a device.
 *
 * A correction is issued ONLY where the draft differs from what stands, so a
 * confirm that changed nothing spends no Room write and a confirm that changed
 * one thing issues one call.
 */
@Composable
private fun CorrectionDialog(
    state: RecordState,
    feedback: SetFeedback,
    viewModel: RecordViewModel,
    timed: Boolean,
    onClose: () -> Unit,
) {
    val standingWarmup = WarmupMarkPolicy.effective(state.lastSetWarmup, state.lastSetWarmupMark)
    var rpe by remember { mutableStateOf(state.lastSetRpe) }
    var tappedFailed by remember { mutableStateOf(state.lastSetTappedFailed) }
    var addedKg by remember { mutableStateOf(feedback.effectiveAddedKg) }
    var reps by remember { mutableStateOf(feedback.effectiveReps) }
    var seconds by remember { mutableStateOf(feedback.effectiveDurationS) }
    var warmup by remember { mutableStateOf(standingWarmup) }
    var limiter by remember { mutableStateOf(state.lastSetLimiter) }
    var note by remember { mutableStateOf(state.lastSetLimiterNote ?: "") }
    AlertDialog(
        onDismissRequest = onClose,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text("Correct that set", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                DraftLoadRow(state, feedback, addedKg) { addedKg = it }
                val held = seconds
                if (timed && held != null) {
                    DraftHoldRow(held) { seconds = it }
                } else if (!timed) {
                    DraftRepsRow(reps) { reps = it }
                }
                DraftWarmupRow(state, warmup) { warmup = it }
                DraftEffortSection(state, feedback, rpe, tappedFailed) { pickedRpe, pickedFailed ->
                    rpe = pickedRpe
                    tappedFailed = pickedFailed
                }
                DraftLimiterSection(state, timed, limiter, note, { limiter = it }) { note = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                applyDraft(
                    state = state,
                    feedback = feedback,
                    viewModel = viewModel,
                    timed = timed,
                    standingWarmup = standingWarmup,
                    addedKg = addedKg,
                    reps = reps,
                    seconds = seconds,
                    warmup = warmup,
                    limiter = limiter,
                    note = note,
                    rpe = rpe,
                    tappedFailed = tappedFailed,
                )
                onClose()
            }) { Text("SAVE CORRECTIONS") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("CANCEL", color = BarColors.Sub) } },
    )
}

/**
 * The draft applied, in the order [CorrectionDialog]'s KDoc names and for the
 * reasons it gives.
 *
 * Not a composable: it is called from a click and reads nothing that
 * recomposes. Every call here is the one the row it replaces made, unchanged.
 */
private fun applyDraft(
    state: RecordState,
    feedback: SetFeedback,
    viewModel: RecordViewModel,
    timed: Boolean,
    standingWarmup: Boolean,
    addedKg: Double,
    reps: Int,
    seconds: Int?,
    warmup: Boolean,
    limiter: SetLimiter?,
    note: String,
    rpe: Int?,
    tappedFailed: Boolean,
) {
    // A DELTA, because addLastSetLoad takes one and SetLoadPolicy.correctedAddedKg
    // is what produced every figure the draft stepped through. The delta from
    // what stands to a value that function already returned re-derives that
    // same value, so the two steppings cannot disagree.
    if (addedKg != feedback.effectiveAddedKg) viewModel.addLastSetLoad(addedKg - feedback.effectiveAddedKg)
    // One tap, not a value: toggleLastSetWarmup flips whatever stands, and
    // WarmupMarkPolicy.toggled never returns to null, so one call moves the
    // effective answer to the draft's and a second would move it back.
    if (warmup != standingWarmup) viewModel.toggleLastSetWarmup()
    val storedNote = if (limiter == SetLimiter.OTHER) SetLimiter.normalizeNote(note) else null
    if (limiter != state.lastSetLimiter || storedNote != state.lastSetLimiterNote) {
        viewModel.limitLastSet(limiter, note)
    }
    val heldNow = feedback.effectiveDurationS
    if (timed) {
        if (seconds != null && heldNow != null && seconds != heldNow) {
            viewModel.addLastSetSeconds(seconds - heldNow)
        }
    } else if (reps != feedback.effectiveReps) {
        viewModel.overrideLastSetReps(reps)
    }
    if (rpe != state.lastSetRpe || tappedFailed != state.lastSetTappedFailed) {
        viewModel.rateLastSet(rpe, failed = tappedFailed)
    }
}

/**
 * Put a different weight on the bar than the app had? State it here (#205),
 * now inside the popup.
 *
 * Everything the inline row decided it still decides. The figure is the ADDED
 * load in #160's notation on body-weight work, so it cannot be read as the
 * whole load, and the steppers move it signed. The caption is
 * [SetLoadPolicy.correctionCaption]'s answer to whether the correction also
 * moves what the next set is offered, and it is computed against the SAME
 * operands the confirm will use -- `feedback.effectiveAddedKg`, not the draft
 * -- because that is what `applyLoadCorrection` reads at the tap.
 */
@Composable
private fun DraftLoadRow(state: RecordState, feedback: SetFeedback, addedKg: Double, onDraft: (Double) -> Unit) {
    val step = SetLoadPolicy.correctionStepKg(state.weightUnit)
    val corrected = addedKg != feedback.addedKg
    Stepper(
        label = SetLoadPolicy.correctionLabel(corrected),
        figure = if (feedback.bodyweight) {
            BodyweightLoadDisplay.label(addedKg, state.weightUnit)
        } else {
            state.weightUnit.format(addedKg)
        },
        corrected = corrected,
        onDown = { onDraft(SetLoadPolicy.correctedAddedKg(addedKg, -step, feedback.bodyweight)) },
        onUp = { onDraft(SetLoadPolicy.correctedAddedKg(addedKg, step, feedback.bodyweight)) },
    )
    SectionCaption(
        SetLoadPolicy.correctionCaption(
            SetLoadPolicy.carryFollowsCorrection(
                standingAddedKg(state),
                feedback.effectiveAddedKg,
                state.weightUnit,
                carryBlock(state),
            ),
        ),
    )
    Spacer(Modifier.height(6.dp))
}

/** Sensor miscount (or manual set)? Adjust the drafted rep count with - / +. */
@Composable
private fun DraftRepsRow(reps: Int, onDraft: (Int) -> Unit) {
    Stepper(
        label = "Reps counted",
        figure = "$reps",
        corrected = false,
        // Floored at zero, the bound applyRepCorrection enforces on the way in:
        // a draft it would silently drop is a draft the lifter watched change.
        onDown = { onDraft((reps - 1).coerceAtLeast(0)) },
        onUp = { onDraft(reps + 1) },
    )
    Spacer(Modifier.height(6.dp))
}

/**
 * Held it longer than the app stopped you at? State it here (#168).
 *
 * The step is [TimedSetEndPolicy.CORRECTION_STEP_S] rather than one second,
 * because what is being added is a walk back to the phone, and the floor is
 * [TimedSetEndPolicy.adjustedSeconds]'s so the draft cannot show a figure the
 * write would clamp.
 */
@Composable
private fun DraftHoldRow(seconds: Int, onDraft: (Int) -> Unit) {
    Stepper(
        label = "Held",
        figure = "${seconds}s",
        corrected = false,
        onDown = { onDraft(TimedSetEndPolicy.adjustedSeconds(seconds, -TimedSetEndPolicy.CORRECTION_STEP_S)) },
        onUp = { onDraft(TimedSetEndPolicy.adjustedSeconds(seconds, TimedSetEndPolicy.CORRECTION_STEP_S)) },
    )
    Spacer(Modifier.height(6.dp))
}

/**
 * What the set was FOR, and the one tap that changes it (#194).
 *
 * Still not a tile on the effort grid, for #187's reason: a set's purpose and
 * its effort are orthogonal, and a seventh tile would rebuild the coupling that
 * discarded one to record the other. The disagreement between the plan's
 * declaration and the lifter's mark is still said out loud.
 */
@Composable
private fun DraftWarmupRow(state: RecordState, warmup: Boolean, onDraft: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        SectionCaption(
            if (warmup) "Purpose · Warm-up" else "Purpose · Working set",
            color = if (warmup) BarColors.Sub else BarColors.Volt,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { onDraft(!warmup) }) {
            Text(if (warmup) "Not a warm-up" else "Warm-up", color = BarColors.Sub)
        }
    }
    // Against the DRAFT rather than the stored mark, so the sentence answers
    // the state the lifter is looking at. A draft equal to the plan's word is
    // no disagreement even where a stored mark disagreed a moment ago.
    if (warmup != state.lastSetWarmup) {
        SectionCaption(
            if (state.lastSetWarmup) {
                "The plan called this a warm-up · your mark stands"
            } else {
                "The plan did not call this a warm-up · your mark stands"
            },
        )
    }
    Spacer(Modifier.height(6.dp))
}

/**
 * The effort grid, selecting into the draft rather than writing on the tap.
 *
 * Which tile is lit is [EffortCorrectionPolicy.selection]'s decision, read
 * against the DRAFT's rating and tapped-failure, so a tile the lifter has just
 * chosen lights before anything is stored. `derivedFailed` is the shortfall the
 * app worked out for itself and is read from the STORED state, because no tap
 * here changes it -- and correcting the rep count in the same popup may move it
 * on confirm, which is exactly why the sentence below the grid says a rating
 * cannot clear a shortfall.
 */
@Composable
private fun DraftEffortSection(
    state: RecordState,
    feedback: SetFeedback,
    rpe: Int?,
    tappedFailed: Boolean,
    onDraft: (Int?, Boolean) -> Unit,
) {
    val options = rpeOptions(feedback.actualDurationS != null, feedback.explosive, state.weightUnit)
    val selection =
        EffortCorrectionPolicy.selection(
            rpe = rpe,
            tappedFailed = tappedFailed,
            derivedFailed = state.lastSetFailed && !state.lastSetTappedFailed,
        )
    SectionCaption("Effort")
    if (selection.derivedShortfall) {
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
                val selected =
                    when {
                        option.failed -> selection.failed
                        else -> selection.rpe != null && option.rpe == selection.rpe
                    }
                RpeTile(option, selected, modifier = Modifier.weight(1f)) {
                    onDraft(option.rpe, option.failed)
                }
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

/**
 * Why the set ended, drafted (#189).
 *
 * WHETHER IT IS OFFERED AT ALL IS [SetLimiterPolicy.offersCorrection]'S
 * decision, unchanged: a failure, a completed set rated at the counted end, or
 * a set already carrying an answer. It is read against the STORED rating rather
 * than the draft's, so the section does not appear and disappear under the
 * finger while the lifter is still picking a rung.
 *
 * The free-text arm keeps the shape [LimiterWords] already had: it is reachable
 * only behind Other, so the ordinary answer never costs a keyboard, and the box
 * is seeded from what is STORED so a lifter correcting an answer sees the words
 * they are correcting. Its SAVE closes the typing arm into the draft rather
 * than into Room; the popup's own confirm is what writes.
 *
 * There is no SKIP foot: leaving by CANCEL is the skip, and the confirm's
 * "only where it differs" rule spends no write on a section nobody touched.
 *
 * THERE IS NO CLEAR ANY MORE, AND THAT IS A LOSS rather than a
 * simplification. No tile here can return the draft to null -- a
 * `SetLimiterTile` carries a non-null limiter and Other routes to the typing
 * arm -- so a reason once given can be CHANGED but never REMOVED, and the
 * confirm's null arm is unreachable for that reason and not by design.
 * [LimiterPage] still draws a CLEAR foot and it cannot be reached either:
 * since #237 that page is placed only under `SetLimiterPagePlacement.PROMPT`,
 * and [SetLimiterPolicy.prompts] requires `limiter == null` -- exactly the
 * case [SetLimiterPolicy.leavesPageAsSkip] answers SKIP to. That foot's
 * `limitLastSet(null)` is the only call anywhere in the app that writes a null
 * reason, so nothing retracts one now.
 */
@Composable
private fun DraftLimiterSection(
    state: RecordState,
    timed: Boolean,
    limiter: SetLimiter?,
    note: String,
    onLimiter: (SetLimiter?) -> Unit,
    onNote: (String) -> Unit,
) {
    if (!SetLimiterPolicy.offersCorrection(state.lastSetFailed, state.lastSetRpe, state.lastSetLimiter)) return
    var typing by remember { mutableStateOf(false) }
    SectionCaption(SetLimiterPolicy.pageTitle(state.lastSetFailed))
    Spacer(Modifier.height(6.dp))
    if (typing) {
        LimiterWords(
            words = note,
            onWords = onNote,
            onSave = {
                onLimiter(SetLimiter.OTHER)
                typing = false
            },
            onBack = { typing = false },
        )
        return
    }
    var group: SetLimiterGroup? = null
    for (tile in SetLimiterScale.tiles(timed, state.lastSetFailed)) {
        // A gap between groups, so pain is drawn apart from the performance
        // answers, which #189 asks for in as many words.
        if (group != null && tile.group != group) Spacer(Modifier.height(10.dp))
        group = tile.group
        LimiterTile(tile) {
            if (tile.limiter == SetLimiter.OTHER) typing = true else onLimiter(tile.limiter)
        }
    }
    // What the draft currently answers, because a page of tiles cannot show a
    // selection and the lifter has to be able to see what they have picked
    // before confirming it.
    SectionCaption(
        SetLimiterPolicy.lineLabel(state.lastSetFailed) + ": " +
            SetLimiterPolicy.lineText(limiter, note, timed, state.lastSetFailed),
    )
    Spacer(Modifier.height(6.dp))
}

/** One label, one figure, and a minus and a plus either side of it. */
@Composable
private fun Stepper(label: String, figure: String, corrected: Boolean, onDown: () -> Unit, onUp: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = BarColors.Sub)
        TextButton(onClick = onDown) { Text("−", style = MaterialTheme.typography.titleMedium) }
        Text(
            figure,
            style = MaterialTheme.typography.titleMedium,
            color = if (corrected) BarColors.Amber else BarColors.Text,
        )
        TextButton(onClick = onUp) { Text("+", style = MaterialTheme.typography.titleMedium) }
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
