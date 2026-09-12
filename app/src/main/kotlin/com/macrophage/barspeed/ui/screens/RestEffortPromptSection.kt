package com.macrophage.barspeed.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.macrophage.barspeed.model.RestEffortPromptPolicy
import com.macrophage.barspeed.record.RecordState
import com.macrophage.barspeed.record.RecordViewModel
import com.macrophage.barspeed.ui.BarColors
import com.macrophage.barspeed.ui.components.SectionCaption

/**
 * "How hard was that set?", asked on the rest screen about a set nothing asked
 * when it ended (#283).
 *
 * ## Nothing here decides anything
 *
 * Whether to draw at all, whether the failure tile is among the tiles, and what
 * a tap carries as `failed`, are all `RestEffortPromptPolicy.prompt`'s answers,
 * pinned in `:core:model` on the CI path. This file projects [RecordState] onto
 * that function's four inputs and draws the result, for the reason every other
 * rule on this screen has been lifted out for: no test in this repository can
 * render a composable.
 *
 * ## The ladder is the FROZEN one
 *
 * `feedback.rpeAsk` is the noun resolved when the set was WRITTEN (#244), never
 * the plan re-read -- an exercise's `progression` can be edited, or its whole
 * plan deleted, while this question is still on screen. On a hold that resolves
 * to `EffortAsk.TIME`, so the rungs read "Could have gone about 15 s longer"
 * and the failure tile reads "Broke early — failed". A hold on an exercise whose
 * plan declares `"reps"` is asked in reps here, because that is what
 * `EffortScale.askFor` froze onto the row and what the plan gate already warns
 * the lifter about; it is that function's business and not this one's.
 *
 * `timed` is computed ONCE and handed to both the policy and the ladder, so the
 * decision to ask and the wording of the question cannot disagree about which
 * kind of set this was.
 *
 * ## What a tap writes
 *
 * `rateLastSet`, which is the same entry point the Correct popup's effort
 * section calls -- so the rating lands on the row exactly as a correction does,
 * `rpe` and `failedByLifter` through `SetRatingTracker.rate`. `rpeScale` needs
 * no write at all: the word was frozen onto the row at the insert and
 * `EffortScale.publishedScale` withholds it from the export only while `rpe` is
 * null, so the scale appears in the export the moment the rating does. Nothing
 * about the export, the plan or the database changes for this question to be
 * answerable, which is why #283 is a screen change.
 *
 * `failed = option.failed || ask.carriesFailed` is the load-bearing half.
 * `SetRatingTracker.rate` ASSIGNS its `failed` argument to the tapped fact
 * rather than OR-ing it, so on a hold the lifter broke early a headroom tap
 * passing false would withdraw the lifter's own verdict and republish
 * `failedByLifter` false. Read from source; no test in this repository can
 * execute that tracker, and no device run has confirmed it.
 *
 * ## Why SKIP and the tap both set `answered`
 *
 * `answered` is composable state keyed on `setsCompleted`, hoisted above every
 * early return -- `NextSetNudgeSection`'s `done` records what happens when it
 * is not. It is not a fact about the set and so is deliberately not in
 * [RecordState], which is #189's rule for the limiter page: a skip stores
 * nothing, because absence is already what the row carries, so there is nothing
 * for the record to remember.
 *
 * The tap needs it as much as the skip does, and that is the non-obvious part.
 * Tapping the FAILURE tile stores `rpe` null, so the policy would answer
 * non-null again on the next composition and the question would redraw itself
 * with its tile now withheld. A rung's tap does close the question through the
 * policy, since a non-null `rpe` withholds it; `answered` makes both taps
 * behave the same way rather than one of them by luck.
 *
 * ## Placement, and what is NOT claimed about it
 *
 * Drawn from `RestingStage` directly under `RestHeader`, above the limiter page
 * and above `NextSetNudgeSection`, which is #236's order and its argument: the
 * screen scrolls to 0 on entering RESTING, so a question drawn below the fold
 * is a question the lifter starts the next set without seeing. It is first
 * because it is the question the others depend on -- the headroom grid draws
 * off `HeadroomTier.ofRpe(lastSetRpe)` and has nothing to offer a hold until
 * this is answered.
 *
 * WHETHER IT FITS ABOVE THE FOLD IS NOT CLAIMED, and neither is anything about
 * what the lifter sees. `:app` has no reachable test seam for a Compose layout.
 * See the [Field] items in the commit body.
 *
 * ## NOTHING HERE HAS BEEN RENDERED
 *
 * No bench run happened, and the blocker was memory rather than the emulator
 * slot -- the lock file
 * `<scratch>/emulator.lock` was free throughout. Free physical
 * memory was polled 31 times on 2026-09-12, 16:43:50 to 17:12:26 local, against
 * the roughly 3 GB a headless `barspeed-api35` boot needs: peak 747 MB, floor
 * 215 MB, and the last 26 samples peaked at 639 MB. The AVD was never started,
 * so no device was started and none was killed. Every claim in this file about
 * what DRAWS is read from source; the tile count, the wording, the placement and
 * what the export carries afterwards are all carried as [Field] items.
 */
@Composable
internal fun RestEffortPromptSection(state: RecordState, viewModel: RecordViewModel) {
    // Hoisted above every early return in this function. A `remember` reached
    // only on some compositions leaves no slot on the ones that return before
    // it, so the answer would be forgotten whenever the row happened not to
    // draw -- and this row's inputs are written during rest, by the Correct
    // popup's own effort section among others.
    var answered by remember(state.setsCompleted) { mutableStateOf(false) }
    val feedback = state.lastFeedback ?: return
    val timed = feedback.actualDurationS != null
    val ask =
        RestEffortPromptPolicy.prompt(
            timed = timed,
            rpe = state.lastSetRpe,
            tappedFailed = state.lastSetTappedFailed,
            // The shortfall the app worked out for itself: the effective
            // verdict minus the lifter's own tap, which is the same pair
            // `DraftEffortSection` reads and the only way to recover the
            // derived half from a state that stores the OR.
            derivedFailed = state.lastSetFailed && !state.lastSetTappedFailed,
        ) ?: return
    if (answered) return
    val options = rpeOptions(timed, feedback.explosive, state.weightUnit, feedback.rpeAsk).filter {
        ask.failedTile || !it.failed
    }
    Spacer(Modifier.height(6.dp))
    // The owner's own words for the thing a hold never showed him.
    SectionCaption("How hard was that set?")
    if (!ask.failedTile) {
        // One sentence for both routes to a withheld tile. The row already
        // reads failed either way, and saying which route it came by would put
        // the derived/tapped distinction in front of a lifter it means nothing
        // to; what they need to know is that answering does not undo it.
        Text(
            "Already recorded as failed — saying how it felt does not change that.",
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
                RpeTile(option, selected = false, modifier = Modifier.weight(1f)) {
                    viewModel.rateLastSet(option.rpe, failed = option.failed || ask.carriesFailed)
                    answered = true
                }
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
    // Writes NOTHING. An unrated set is not a zero and is not a warm-up, and
    // the Last set box says "Not rated" in words, so declining to answer is
    // already representable. The Correct button's popup reopens the same grid
    // for the rest of the rest period.
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = { answered = true }, modifier = Modifier.weight(1f)) { Text("SKIP") }
    }
    Spacer(Modifier.height(10.dp))
}
