package com.macrophage.barspeed.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import com.macrophage.barspeed.ui.components.SectionCaption

/**
 * "How hard was that set?", asked on the rest screen about a hold that ran its
 * clock and was never asked (#283).
 *
 * ## Nothing here decides anything
 *
 * Whether to draw at all is `RestEffortPromptPolicy.prompt`'s answer, pinned in
 * `:core:model` on the CI path. This file projects [RecordState] onto that
 * function's four inputs and draws the result, for the reason every other rule
 * on this screen has been lifted out for: no test in this repository can render
 * a composable.
 *
 * ## Only a set that did what it was asked is asked
 *
 * The owner's rule: *"Don't ask for an RPE on failed sets, if you can't do it,
 * it's failed."* So this question is drawn for a timed set carrying no rating
 * and no failure of either kind, and for nothing else. A hold the lifter ended
 * with the failure control gets the limiter and reason page below and no effort
 * question at all; a hold the app judged short of its target gets the same.
 *
 * TWO CONSEQUENCES FOR THIS FILE, both of which used to be decisions the policy
 * made and are now invariants:
 *
 *  - the failure tile is filtered out of the ladder, because a set with nothing
 *    saying it fell short cannot be asked to say it failed on the rest screen
 *    after it already finished. Seven tiles, not eight.
 *  - every tap therefore carries `failed = false`, and it is written as
 *    `failed = option.failed` rather than as the literal so the filter and the
 *    write cannot come to disagree about which tiles are on screen.
 *
 * Neither is test-gated: they are `:app` rules and `:app` has no reachable seam
 * for them, so they are compile- and lint-gated only.
 *
 * ## The ladder is the FROZEN one
 *
 * `feedback.rpeAsk` is the noun resolved when the set was WRITTEN (#244), never
 * the plan re-read -- an exercise's `progression` can be edited, or its whole
 * plan deleted, while this question is still on screen. On a hold that resolves
 * to `EffortAsk.TIME`, so the rungs read "Could have gone about 15 s longer". A
 * hold on an exercise whose plan declares `"reps"` is asked in reps here,
 * because that is what `EffortScale.askFor` froze onto the row and what the plan
 * gate already warns the lifter about; it is that function's business and not
 * this one's.
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
 * ## Why SKIP and the tap both set `answered`
 *
 * `answered` is composable state keyed on `setsCompleted`, hoisted above every
 * early return -- `NextSetNudgeSection`'s `done` records what happens when it
 * is not. It is not a fact about the set and so is deliberately not in
 * [RecordState], which is #189's rule for the limiter page: a skip stores
 * nothing, because absence is already what the row carries, so there is nothing
 * for the record to remember.
 *
 * A rung's tap closes the question through the policy on its own, since a
 * non-null `rpe` withholds it, and it sets `answered` as well so that both ways
 * out of this row behave the same rather than one of them by luck. THAT HAS A
 * COST AND IT IS A CAPTURE-PATH ONE: `answered` is set on the tap, before the
 * write returns. `applyRating` publishes the new rating only if
 * `SetRatingTracker.rate` returns non-null, so on a set with no attached row
 * the tap stores nothing AND the question is gone for that rest; the Correct
 * popup is then the only route back. Read from source, never observed.
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
 * No bench run happened. Every claim in this file about what DRAWS is read from
 * source; the tile count, the wording, the placement and what the export
 * carries afterwards are all carried as [Field] items.
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
    RestEffortPromptPolicy.prompt(
        timed = timed,
        rpe = state.lastSetRpe,
        tappedFailed = state.lastSetTappedFailed,
        // The shortfall the app worked out for itself: the effective verdict
        // minus the lifter's own tap, which is the same pair
        // `DraftEffortSection` reads and the only way to recover the derived
        // half from a state that stores the OR. Handed over separately from
        // the tapped fact even though both now answer null, so that this call
        // site cannot quietly start reading only one of them.
        derivedFailed = state.lastSetFailed && !state.lastSetTappedFailed,
    ) ?: return
    if (answered) return
    // The failure tile is not among these. The policy has already established
    // that nothing on this row says the set fell short, and a tile offering to
    // say so after the set finished is the ask the owner's rule forbids.
    val options = rpeOptions(timed, feedback.explosive, state.weightUnit, feedback.rpeAsk).filter { !it.failed }
    Spacer(Modifier.height(6.dp))
    // The owner's own words for the thing a hold never showed him.
    SectionCaption("How hard was that set?")
    Spacer(Modifier.height(6.dp))
    options.chunked(2).forEach { row ->
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        ) {
            row.forEach { option ->
                RpeTile(option, selected = false, modifier = Modifier.weight(1f)) {
                    viewModel.rateLastSet(option.rpe, failed = option.failed)
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
