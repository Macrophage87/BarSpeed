// The rest screen's finished-set correction surface, lifted out of
// RecordScreen.kt by #208: the block below the next-set block that says how
// the set just finished went and lets the lifter correct it -- effort, why it
// ended, warm-up mark, load (#205), rep count, held seconds, and the per-rep
// chart. Split out so those additions had somewhere to go; of the three, only
// #205 landed here. The move itself changed no behaviour -- the
// widened visibility of the helpers it still calls in RecordScreen.kt
// (private -> internal) was the only edit it needed -- and the load
// correction is the first thing added here since that did.
package com.macrophage.barspeed.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
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
import com.macrophage.barspeed.model.BodyweightLoadDisplay
import com.macrophage.barspeed.model.EffortCorrectionPolicy
import com.macrophage.barspeed.model.SetLimiterPagePlacement
import com.macrophage.barspeed.model.SetLimiterPolicy
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
internal fun LastSetDetail(
    state: RecordState,
    viewModel: RecordViewModel,
    placement: SetLimiterPagePlacement,
    timed: Boolean,
    onChangeLimiter: () -> Unit,
    onSkipLimiter: () -> Unit,
    onLimiterDone: () -> Unit,
) {
    // The effort tile is tapped mid-workout to end the set, so give a mistap
    // somewhere to go rather than baking it into the record.
    var changingEffort by remember(state.setsCompleted) { mutableStateOf(false) }
    LoggedEffortLine(state) { changingEffort = !changingEffort }
    if (changingEffort) {
        RpeSelector(state, viewModel) { changingEffort = false }
    }
    LimiterCorrection(
        state = state,
        viewModel = viewModel,
        placement = placement,
        timed = timed,
        onChange = onChangeLimiter,
        onSkip = onSkipLimiter,
        onDone = onLimiterDone,
    )
    WarmupMarkRow(state, viewModel)
    LoadCorrectionRow(state, viewModel)
    state.lastFeedback?.let { RepCorrectionRow(it, viewModel) }
    state.lastFeedback?.let { HoldCorrectionRow(it, viewModel) }
    Spacer(Modifier.height(6.dp))
    state.lastFeedback?.let { RepQualityCard(it) }
}

/**
 * Rest-screen reminder of the effort recorded for the finished set.
 *
 * When an effort tile was tapped, its own label is shown, with "short of
 * target" appended if the set also fell short by rep count or duration -- both
 * are real, distinct facts and neither replaces the other.
 *
 * When none was tapped, [RecordState.lastSetRpe] is null: the lifter tapped
 * the grid's own "Failed the set" tile, tapped the standalone failure control
 * (#186), was offered the grid and declined it via [EndSetEarlyButton], left
 * during the set's lead-in, or had the set auto-ended by its clock (#168).
 * [RecordState.lastSetTappedFailed] tells a tapped failure from a derived one;
 * this line still prints the shared word "Failed" for both, by #139's
 * deliberate choice, not because the state is incapable.
 *
 * [RecordState.lastSetWarmup] is NOT one of those flags any more. There is no
 * warm-up tile since #187 -- `EffortScaleTest` pins its absence -- and the
 * flag is the plan's declaration about the set, frozen at the write, so it is
 * routinely true on a set carrying no rating at all. The lifter's own mark,
 * added by #194, is not one of them either and for the same reason: it says
 * what the set was FOR, this line says how it went, and [WarmupMarkRow] draws
 * the first one row below.
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
        rpeOptions(feedback.actualDurationS != null, feedback.explosive, state.weightUnit)
    // The warm-up branch this used to open with is gone with the tile (#187).
    // A warm-up set carries an ordinary rating and reads as one; that it was a
    // ramp is a separate fact, and since #194 it is shown and editable one row
    // down, in [WarmupMarkRow], rather than being absent from the record flow
    // altogether. The sentence that stood here said the lifter is never told
    // mid-session that the set was declared preparatory and called the in-app
    // toggle missing; both were true until #194 and are deleted rather than
    // reworded, because the row below now says it and can change it.
    val tapped = options.firstOrNull { !it.failed && it.rpe == state.lastSetRpe }?.description
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

/**
 * Why the set ended: the page after a fail, and the row that corrects it
 * afterwards (#189).
 *
 * WHEN IT OPENS BY ITSELF IS [SetLimiterPolicy]'S DECISION, not this file's,
 * and so is what the row reads. No test on the CI path reaches a composable,
 * so a rule written beside its caller here is a rule nothing enforces; one
 * module over, every case is a literal in a test.
 *
 * WHERE IT IS DRAWN IS ALSO [SetLimiterPolicy]'S DECISION, and it is not one
 * place. The page the app opens by ITSELF is drawn by [RestingStage] at the
 * top of the rest screen; this function draws only the page the lifter opened
 * from the row below, under that row. Both are the same composable and the
 * same tiles -- see [SetLimiterPagePlacement] for why the two cases are an
 * enum rather than a pair of booleans.
 *
 * THE ROW SURVIVES THE SKIP. Once the page has been offered it does not offer
 * itself again for the same set, but the row below stays, with SAY WHY on it,
 * for the whole rest period -- the same arrangement the effort line has for
 * exactly the same reason: a mark given in the moment is what gets revised ten
 * seconds later.
 */
@Composable
private fun LimiterCorrection(
    state: RecordState,
    viewModel: RecordViewModel,
    placement: SetLimiterPagePlacement,
    timed: Boolean,
    onChange: () -> Unit,
    onSkip: () -> Unit,
    onDone: () -> Unit,
) {
    if (state.lastFeedback == null) return
    if (!SetLimiterPolicy.offersCorrection(state.lastSetFailed, state.lastSetLimiter)) return
    LimiterLine(state, timed, onChange)
    // Under the row that opened it. A page the lifter asked for belongs
    // beside the finger that asked; only the automatic offer goes to the top.
    if (placement == SetLimiterPagePlacement.CORRECTION) {
        LimiterPage(state, timed, viewModel, onSkip = onSkip, onDone = onDone)
    }
}

/**
 * What the set was FOR, and the one tap that changes it (#194).
 *
 * WHY IT IS NOT A TILE. #187 removed the warm-up tile from the effort grid
 * because a set's purpose and its effort are orthogonal, and the tile recorded
 * `warmup = true` and `rpe = null` together -- discarding the effort by
 * construction. A seventh tile here would rebuild that coupling in a new
 * place. This is a row of its own and touches nothing about the rating.
 *
 * WHOSE ANSWER IS DRAWN IS [WarmupMarkPolicy]'S DECISION, not this file's. The
 * plan declares, the lifter may mark, the lifter wins where both exist -- and
 * the row says out loud when the two disagree, so a lifter who takes a set off
 * the plan's ramp list can see that the app knows the plan said otherwise
 * rather than finding the plan's word quietly gone.
 */
@Composable
private fun WarmupMarkRow(state: RecordState, viewModel: RecordViewModel) {
    if (state.lastFeedback == null) return
    val declared = state.lastSetWarmup
    val mark = state.lastSetWarmupMark
    val warmup = WarmupMarkPolicy.effective(declared, mark)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        SectionCaption(
            if (warmup) "Purpose \u00b7 Warm-up" else "Purpose \u00b7 Working set",
            color = if (warmup) BarColors.Sub else BarColors.Volt,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = viewModel::toggleLastSetWarmup) {
            Text(if (warmup) "Not a warm-up" else "Warm-up", color = BarColors.Sub)
        }
    }
    if (WarmupMarkPolicy.disagrees(declared, mark)) {
        SectionCaption(
            if (declared) {
                "The plan called this a warm-up \u00b7 your mark stands"
            } else {
                "The plan did not call this a warm-up \u00b7 your mark stands"
            },
        )
    }
    Spacer(Modifier.height(4.dp))
}

/**
 * Put a different weight on the bar than the app had? State it here (#205).
 *
 * THE SET JUST FINISHED. Every other row on this surface corrects the set that
 * has been written, and so does this one; the caption says so in words, from
 * [SetLoadPolicy.correctionCaption], because #188 is the neighbouring control
 * that named the upcoming exercise when it meant the finished one. Editing an
 * arbitrary past set from the history screen is a larger and different thing
 * and is deliberately not here.
 *
 * DRAWN ON EVERY SET, unlike [RepCorrectionRow] and [HoldCorrectionRow], which
 * are mutually exclusive on whether the set was timed. A weighted carry has a
 * load and so does a squat, and a body-weight set with nothing added is
 * exactly the case where the lifter clipped a plate on and the app never knew.
 *
 * THE FIGURE IS THE ADDED LOAD. On body-weight work that is #160's notation --
 * "BW + 10 kg", "BW - 50 kg" for assistance, bare "BW" for nothing added --
 * so the number cannot be read as the whole load, and the steppers move it
 * signed. On loaded work the added load and the total are the same number and
 * it renders as any other load does. The implement split the header draws
 * ("2 x 20 kg") is not repeated here: what is being edited is one figure.
 *
 * The corrected figure is amber and the label says corrected, the same way a
 * corrected rep count and a corrected hold are.
 */
@Composable
private fun LoadCorrectionRow(state: RecordState, viewModel: RecordViewModel) {
    val feedback = state.lastFeedback ?: return
    val added = feedback.effectiveAddedKg
    val corrected = feedback.loadOverrideAddedKg != null
    val step = SetLoadPolicy.correctionStepKg(state.weightUnit)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            SetLoadPolicy.correctionLabel(corrected),
            style = MaterialTheme.typography.bodySmall,
            color = BarColors.Sub,
        )
        TextButton(onClick = { viewModel.addLastSetLoad(-step) }) {
            Text("\u2212", style = MaterialTheme.typography.titleMedium)
        }
        Text(
            if (feedback.bodyweight) {
                BodyweightLoadDisplay.label(added, state.weightUnit)
            } else {
                state.weightUnit.format(added)
            },
            style = MaterialTheme.typography.titleMedium,
            color = if (corrected) BarColors.Amber else BarColors.Text,
        )
        TextButton(onClick = { viewModel.addLastSetLoad(step) }) {
            Text("+", style = MaterialTheme.typography.titleMedium)
        }
    }
    // Whether the tap also moves what the set coming up is offered is
    // SetLoadPolicy's decision, taken against what is standing right now, and
    // this caption is that answer said out loud. A control that quietly
    // changes a second thing is worse than one that changes nothing.
    SectionCaption(
        SetLoadPolicy.correctionCaption(
            SetLoadPolicy.carryFollowsCorrection(
                standingAddedKg(state),
                added,
                state.weightUnit,
                carryBlock(state),
            ),
        ),
    )
    Spacer(Modifier.height(4.dp))
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

/**
 * Held it longer than the app stopped you at? State it here (#168).
 *
 * A hold or a carry now ends when its clock reaches the seconds it was
 * working to, so the recorded figure is the announced one and the
 * phone-retrieval walk is no
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
