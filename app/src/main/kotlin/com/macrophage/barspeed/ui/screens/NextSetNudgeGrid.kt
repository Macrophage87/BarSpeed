package com.macrophage.barspeed.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.macrophage.barspeed.model.HeadroomTier
import com.macrophage.barspeed.model.NextSetNudge
import com.macrophage.barspeed.model.NextSetNudgePolicy
import com.macrophage.barspeed.model.ProgressionKind
import com.macrophage.barspeed.model.WarmupMarkPolicy
import com.macrophage.barspeed.record.RecordState
import com.macrophage.barspeed.record.RecordViewModel
import com.macrophage.barspeed.ui.components.SectionCaption

/**
 * The grid offered after a set the lifter says had more in it: put that onto
 * the NEXT set of the same exercise, in one tap (#214).
 *
 * ## Nothing here decides anything
 *
 * Whether to draw at all, and what the tiles are, is
 * [NextSetNudgePolicy.options]'s answer, pinned by `NextSetNudgeOptionsTest`
 * on the CI path. This file projects [RecordState] onto that function's six
 * inputs and draws the result. That split is the point: no test in this repo
 * can reach a composable, so a rule written inside one is a rule nothing can
 * measure.
 *
 * ## What a tap writes, and how far it carries
 *
 * A tile calls the SAME ViewModel entry point the change-next-set dialog's box
 * calls -- `updateLoadInput`, `updateRepsInput`, `updateDurationInput` -- so
 * the increment is a lifter STATEMENT indistinguishable from a typed one from
 * the moment it is made. It lands on the next set unconditionally, and from
 * there it carries exactly as a typed correction carries: through
 * `SetLoadPolicy.standingStatedAddedKg` for load and
 * `SetRepsPolicy.standingStatedReps` / `standingStatedDurationS` for the other
 * two, which hold it for the rest of the exercise block and drop it at the
 * boundary.
 *
 * **THAT INHERITS #143 AND DOES NOT FIX IT.** On a plan declaring a DIFFERENT
 * load for each set of one exercise -- a progressive 60 / 80 / 100 block --
 * `standingStatedAddedKg` compares the two slots' frozen declarations, finds
 * them unequal, and drops the statement, so the set after next re-seeds to the
 * plan's own next number with nothing on screen marking that the addition went
 * away. The tile's effect on the immediately following set is unaffected;
 * what is lost is the carry past it. That is #143's open defect and it is left
 * alone deliberately here rather than fixed as a side effect of a grid change:
 * the carry rule is read by four other controls, and changing it under this
 * one would be a change to all of them.
 *
 * ## Why a tap closes the grid
 *
 * One tap is one decision. Left open, a second tap would compound onto the
 * first -- +10 then +5 is +15 -- and nothing on screen would say which of the
 * two readings had happened. What the change WAS stays visible: the Up next
 * card above strikes the plan's figure through and shows the new one, because
 * `cardValues` reads the same `statedLoadKg` / `statedReps` / `statedDurationS`
 * the tap just wrote. Anything further goes through CHANGE NEXT SET, which is
 * also where CUSTOM leads.
 *
 * ## Placement
 *
 * Drawn from `RestingStage` directly after `NextSetBlock`, because it is about
 * the set coming up and the card it changes is the one immediately above it.
 *
 * FOUR OF ITS INPUTS ARE WRITTEN DURING REST, so this row can appear or vanish
 * part-way through one and shift what is drawn below it. `toggleLastSetWarmup`
 * writes `lastSetWarmupMark` through `applyWarmupMark`; `ratedState` writes
 * `lastSetRpe` and `lastSetFailed`; `applyRepCorrection` and
 * `durationCorrectedState` each write `lastSetFailed`;
 * `addSetOfCurrentExercise` writes, through `appendedState`, the queue that
 * `setsLeftInExercise` is counted off. Every one of those is a rest-screen
 * control drawn BELOW this row, so acting on one can move the next target
 * under the finger that acted.
 *
 * Whether that reaches the stacked-target hazard #137 removed elsewhere on
 * this screen is UNMEASURED and is a [Field] question, not a property claimed
 * here. The bench run saw the row drawn and tapped; it never toggled a control
 * beneath it and re-read the layout.
 */
@Composable
internal fun NextSetNudgeSection(state: RecordState, viewModel: RecordViewModel) {
    if (state.adHoc) return
    // The slot that just FINISHED. During RESTING `currentSlot` is that set,
    // not the one coming up -- `queueIndex` does not advance until START --
    // and it is the exercise whose declaration decides what is offered.
    val finished = state.currentSlot ?: return
    val next = state.nextSlot ?: return
    val options =
        NextSetNudgePolicy.options(
            tier = HeadroomTier.ofRpe(state.lastSetRpe),
            // The OR of the lifter's own tap and the derived shortfall, which
            // is what this field already carries. A set stopped short and then
            // rated on a headroom rung must not be offered more load.
            failed = state.lastSetFailed,
            // The plan's declaration AND the lifter's own mark, composed by
            // the one function that answers this. Never the declaration alone.
            warmup = WarmupMarkPolicy.effective(state.lastSetWarmup, state.lastSetWarmupMark),
            setsLeftInExercise =
            NextSetNudgePolicy.setsLeftInExercise(
                finished.exercise.id,
                state.queue.drop(state.queueIndex + 1).map { it.exercise.id },
            ),
            progression = finished.progression,
            unit = state.weightUnit,
        )
    if (options.isEmpty()) return

    // Keyed on setsCompleted, as every other per-set page on this screen is:
    // an answer given about one set must not survive into the next one's rest.
    var done by remember(state.setsCompleted) { mutableStateOf(false) }
    var changing by remember(state.setsCompleted) { mutableStateOf(false) }
    if (changing) {
        ChangeSetDialog(state, viewModel, next, next = true) { changing = false }
    }
    if (done) return

    Spacer(Modifier.height(12.dp))
    SectionCaption(nudgeCaption(options.first().kind))
    Spacer(Modifier.height(6.dp))
    options.chunked(3).forEach { row ->
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        ) {
            row.forEach { nudge ->
                OutlinedButton(
                    onClick = {
                        applyNudge(state, viewModel, nudge)
                        done = true
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(nudge.label, style = MaterialTheme.typography.labelLarge)
                }
            }
            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        // Skip writes NOTHING. The next set keeps exactly the load, reps and
        // hold it was seeded with, and no statement is recorded -- there is
        // nothing for the export to carry, because declining to change
        // something is already what an absent statement means.
        TextButton(onClick = { done = true }, modifier = Modifier.weight(1f)) { Text("SKIP") }
        // The existing surface, not a second load editor. Everything in that
        // dialog is reachable whatever this exercise declares, which is the
        // owner's "the user can still change it on the usual screen".
        TextButton(onClick = { changing = true }, modifier = Modifier.weight(1f)) { Text("CUSTOM") }
    }
}

/**
 * The line above the tiles, per dimension.
 *
 * Says what the tap DOES rather than what the last set was: the rating has
 * already been given by the time this draws, and the question on screen is
 * about the set coming up.
 */
private fun nudgeCaption(kind: ProgressionKind): String = when (kind) {
    ProgressionKind.WEIGHT -> "Had more in you — add to the next set?"
    ProgressionKind.REPS -> "Had more in you — add reps to the next set?"
    ProgressionKind.TIME -> "Had more in you — add time to the next set?"
    // Unreachable: NONE returns no options, so no caption is ever asked for.
    // Written out rather than left to an else, so adding a kind is a compile
    // error here instead of a silently wrong line on a screen.
    ProgressionKind.NONE -> ""
}

/**
 * Write the tap through the same entry point a typed correction uses.
 *
 * The seed the increment is added TO is the STATEMENT if there is one and the
 * box otherwise, never the slot's own declaration: the box is what the rest
 * transition already seeded from the plan, and reading the slot instead would
 * discard a correction the lifter made moments earlier in the same rest.
 *
 * Every arm is null-safe in the same direction. A box the app cannot parse is
 * a load or a count nobody stated, and the arithmetic returns null rather than
 * treating absence as a zero to add to -- so a tap in that state writes
 * nothing at all instead of putting a figure on the card the lifter never
 * gave.
 */
private fun applyNudge(state: RecordState, viewModel: RecordViewModel, nudge: NextSetNudge) {
    when (nudge.kind) {
        ProgressionKind.WEIGHT -> {
            val currentKg = state.statedLoadKg ?: state.weightUnit.parseToKg(state.loadInput)
            NextSetNudgePolicy.bumpedLoadKg(currentKg, nudge, state.weightUnit)?.let {
                viewModel.updateLoadInput(state.weightUnit.inputValue(it))
            }
        }
        ProgressionKind.REPS ->
            NextSetNudgePolicy.bumpedCount(state.repsInput.trim().toIntOrNull(), nudge)?.let {
                viewModel.updateRepsInput(it.toString())
            }
        ProgressionKind.TIME ->
            NextSetNudgePolicy.bumpedCount(state.durationInput.trim().toIntOrNull(), nudge)?.let {
                viewModel.updateDurationInput(it.toString())
            }
        ProgressionKind.NONE -> Unit
    }
}
