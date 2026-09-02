// The finished-set correction seam, lifted out of RecordViewModel.kt by #208.
// Everything here is what the rest screen can still change about the set that
// has just been written: its effort rating, why it ended, whether it was a
// warm-up, its rep count, a hold's seconds and -- since #205 -- the load it
// was recorded at. Split out because the class these would otherwise have
// been added to had one line of growth left under detekt's `LargeClass`
// default of 600. Of the three, only #205 landed here: #204 landed in
// :core:model, RecordScreen.kt and RecordViewModel.kt without touching this
// file, and #206 has not arrived. The move itself
// changed no behaviour, and the load correction is the first thing added here
// that did.
package com.macrophage.barspeed.record

import com.macrophage.barspeed.model.SetLimiter
import com.macrophage.barspeed.model.SetLoadPolicy
import com.macrophage.barspeed.model.TimedSetEndPolicy
import com.macrophage.barspeed.model.WarmupMarkPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * The state a rest-screen effort correction leaves behind.
 *
 * Free function for the reason `restingState` and `advancedState` in
 * RecordViewModel.kt are (plain names, not links: those two are file-private
 * there and a link from here would not resolve): it is a pure `copy` over a
 * state and some arguments and needs nothing from the class.
 *
 * [tappedFailed] is what the lifter just said and [effectiveFailed] is the OR
 * `SetRatingTracker` returned. Both are stored, because the correction grid has
 * to attribute the verdict and the OR cannot say whose it was. #140.
 */
internal fun ratedState(s: RecordState, rpe: Int?, tappedFailed: Boolean, effectiveFailed: Boolean): RecordState =
    s.copy(
        lastSetRpe = rpe,
        lastSetFailed = effectiveFailed,
        // SetRatingTracker overwrites its own tapped flag on every correction,
        // so this mirrors it exactly: a correction away from the failed tile
        // withdraws the tap and leaves the derived shortfall standing.
        lastSetTappedFailed = tappedFailed,
        // lastSetWarmup is deliberately NOT written. It is the plan's
        // declaration about the set, frozen when the set was recorded, and
        // re-rating the effort says nothing about whether it was a ramp (#187).
    )

/**
 * The state a rest-screen duration correction leaves behind (#168). Free
 * function for [ratedState]'s reason, and it mirrors that one exactly:
 * `lastSetTappedFailed` is absent on purpose, because correcting how long a
 * hold lasted re-derives the shortfall and says nothing about whether the
 * lifter felt they failed.
 *
 * Why the correction is here at all, and post-set rather than mid-set: a hold
 * or a carry now ends when its clock reaches the seconds it was working to,
 * so the recorded
 * figure is the announced one and the walk back to the phone is no longer
 * inside it. The rare deliberate overage is stated on the rest screen, where
 * every other post-set correction already lives, because the owner does not
 * look at the phone while holding -- "There are rare instances I even look at
 * the phone mid set" -- so a mid-set affordance would be exercised never. The
 * delta moves the figure that currently stands, so repeated taps accumulate,
 * and `TimedSetEndPolicy.adjustedSeconds` floors the result at zero.
 */
internal fun durationCorrectedState(s: RecordState, seconds: Int, effectiveFailed: Boolean): RecordState = s.copy(
    lastFeedback = s.lastFeedback?.copy(durationOverrideS = seconds),
    lastSetFailed = effectiveFailed,
)

/**
 * One tap of the rest screen's warm-up mark (#194). Free function for the
 * reason `applyPrepAdjustment` in RecordViewModel.kt gives.
 *
 * The next mark is [WarmupMarkPolicy]'s decision, not this function's: it
 * flips whatever currently stands and never returns to null, because a lifter
 * who has tapped has said something and returning them to silence would hand
 * the plan back a set they had just taken off it.
 *
 * Writes the MARK only. `warmup` -- the plan's declaration -- is untouched, so
 * a set unmarked here still remembers that the plan called it a ramp, and
 * nothing about the rating moves: `warmup: true` beside `rpe: 6` is the point
 * of the whole change.
 *
 * THE NEW MARK IS PUBLISHED BEFORE THE WRITE, not after it. `markWarmup`
 * suspends into Room, and Dispatchers.Main.immediate runs a coroutine body
 * only as far as its first suspension -- so a second tap arriving while the
 * first is still writing used to read a state the first had not replaced yet,
 * compute the same next mark, and be lost. Publishing first makes the second
 * tap toggle from the first tap's answer, and flips the label on the tap
 * rather than on the round trip, which is what the lifter is responding to
 * when they tap again.
 *
 * The rollback is not a race of its own. `markWarmup` returns null only when
 * there is no recorded set, and it decides that BEFORE it suspends, so the
 * restore runs in the same frame as the tap and no other tap can interleave
 * with it.
 *
 * What is NOT ordered is the two writes themselves. `markWarmup` suspends into
 * Room and Room dispatches suspend queries to a pool, so two taps inside one
 * write window can reach the database in either order and the row may keep the
 * first tap's mark while this state keeps the second's. Read from source;
 * never observed, and the bench's two taps did not prove the window was
 * entered.
 */
internal fun applyWarmupMark(
    stateFlow: MutableStateFlow<RecordState>,
    ratings: SetRatingTracker,
    appScope: CoroutineScope,
) {
    val s = stateFlow.value
    val before = s.lastSetWarmupMark
    val mark = WarmupMarkPolicy.toggled(s.lastSetWarmup, before)
    stateFlow.value = s.copy(lastSetWarmupMark = mark)
    appScope.launch(Dispatchers.Main.immediate) {
        if (ratings.markWarmup(mark) == null) {
            stateFlow.value = stateFlow.value.copy(lastSetWarmupMark = before)
        }
    }
}

/**
 * Record, change or clear why the just-finished set ended (#189). Free
 * function for the reason `applyPrepAdjustment` in RecordViewModel.kt gives.
 *
 * A null [limiter] clears the answer, which is what a lifter changing their
 * mind back to no answer leaves behind. The note is normalized here, and is
 * DROPPED for any answer other than [SetLimiter.OTHER]:
 * words kept beside "grip gave out" would be read as describing an answer they
 * were never typed for, and the note's own published description promises they
 * are not there.
 *
 * appScope, as `rateLastSet` uses: the rest screen is the only place this can
 * be given, and the pop that leaves it must not cancel the write.
 *
 * THE ANSWER IS PUBLISHED AFTER THE WRITE HERE, unlike [applyWarmupMark]. The
 * page the app opens by itself closes on the published answer rather than on
 * the tap, so until `limit` returns the tiles are still drawn under a finger
 * that has already answered; a second tap inside that window writes a second
 * valid answer rather than losing one, and the two writes are not ordered
 * against each other. Whether the asymmetry should stay is raised here, not
 * settled.
 */
internal fun applyLimiter(
    stateFlow: MutableStateFlow<RecordState>,
    limiter: SetLimiter?,
    note: String?,
    ratings: SetRatingTracker,
    appScope: CoroutineScope,
) {
    val normalized = if (limiter == SetLimiter.OTHER) SetLimiter.normalizeNote(note) else null
    appScope.launch(Dispatchers.Main.immediate) {
        ratings.limit(limiter?.stored, normalized) ?: return@launch
        stateFlow.value =
            stateFlow.value.copy(
                lastSetLimiter = limiter,
                lastSetLimiterNote = normalized,
            )
    }
}

/**
 * The state a rest-screen rep correction leaves behind, and the write that
 * produces it (#140). Lifted whole out of `RecordViewModel.overrideLastSetReps`
 * by #208, which left the member as a one-line call.
 *
 * appScope, for the reason `launchSetWrite` is: a correction tapped on the
 * rest screen and then abandoned by Back is a correction the pop cancels, and
 * nothing anywhere can edit a stored set once this screen is gone.
 * Main.immediate keeps it ordered against the other writers and keeps
 * `SetRatingTracker`'s fields on the thread that already reads them.
 */
internal fun applyRepCorrection(
    stateFlow: MutableStateFlow<RecordState>,
    reps: Int,
    ratings: SetRatingTracker,
    appScope: CoroutineScope,
) {
    if (reps < 0) return
    appScope.launch(Dispatchers.Main.immediate) {
        val s = stateFlow.value
        val failed = ratings.correctReps(reps, rpe = s.lastSetRpe, warmup = s.lastSetWarmup) ?: return@launch
        stateFlow.value =
            stateFlow.value.copy(
                lastFeedback = stateFlow.value.lastFeedback?.copy(repsOverride = reps),
                lastSetFailed = failed,
                // lastSetTappedFailed is deliberately NOT written here.
                // correctReps re-derives only the shortfall; the lifter's
                // own tap is untouched by a rep correction, so carrying it
                // unchanged is what keeps the two facts two facts.
            )
    }
}

/**
 * The write behind one tap of the hold or carry seconds correction (#168),
 * lifted whole out of `RecordViewModel.addLastSetSeconds` by #208 for
 * [applyRepCorrection]'s reason.
 *
 * Returns without writing anything when the finished set has no duration to
 * correct, which is every set that is not a hold or a carry. The delta moves
 * the figure that currently stands, so repeated taps accumulate;
 * [durationCorrectedState] says why the correction is post-set rather than
 * mid-set.
 *
 * Two fast taps here are still LOST rather than reordered, for the reason
 * [applyLoadCorrection]'s KDoc gives: this one still reads outside the
 * coroutine and publishes after the write. Not fixed with #205, because the
 * two paths were changed one at a time.
 */
internal fun applyDurationCorrection(
    stateFlow: MutableStateFlow<RecordState>,
    deltaS: Int,
    ratings: SetRatingTracker,
    appScope: CoroutineScope,
) {
    val current = stateFlow.value.lastFeedback?.effectiveDurationS ?: return
    val seconds = TimedSetEndPolicy.adjustedSeconds(current, deltaS)
    appScope.launch(Dispatchers.Main.immediate) {
        val s = stateFlow.value
        val failed = ratings.correctDuration(seconds, rpe = s.lastSetRpe, warmup = s.lastSetWarmup) ?: return@launch
        stateFlow.value = durationCorrectedState(stateFlow.value, seconds, failed)
    }
}

/**
 * The added load standing for the SET COMING UP, in kilograms, or null when
 * nothing is standing for it.
 *
 * Two sources and not one, because the carry runs through two different fields
 * depending on the session. On a plan set `statedLoadKg` is the lifter's
 * standing statement and is what `SetLoadPolicy.resolve` reads; on an ad-hoc
 * set that field is not in the path at all and the load box itself is the
 * declaration, so the box is what would be recorded. Reading only the first
 * would say "nothing is standing" for every ad-hoc session, which is exactly
 * the session where the load repeats set after set.
 *
 * Null only where the box holds something that is not a number.
 */
internal fun standingAddedKg(s: RecordState): Double? = s.statedLoadKg ?: s.weightUnit.parseToKg(s.loadInput)

/**
 * Whether the set coming up is a continuation of the block just finished, for
 * the load correction's carry (#205).
 *
 * The slot coming up where there is one, the exercise the lifter has selected
 * where there is not; [SetLoadPolicy.correctionCarryBlock] holds the rule.
 *
 * EVERY OPERAND IS READ LIVE except [RecordState.lastSetExerciseId], which is
 * a fact about the set just finished and cannot move. `queueIndex` does not
 * advance until the rest ends, so `nextSlot` is the set the lifter is about to
 * do -- including after a switch or an append replaced it during the rest.
 * The switch half of that is OBSERVED, on the emulator and not on a phone: a
 * correction taken after Switch exercise left the coming set's declared load
 * where it was and the corrected load on the finished set's row.
 */
internal fun carryBlock(s: RecordState): Boolean = SetLoadPolicy.correctionCarryBlock(
    lastExerciseId = s.lastSetExerciseId,
    nextExerciseId = s.nextSlot?.exercise?.id,
    nextSetIndexInExercise = s.nextSlot?.setIndexInExercise,
    comingExerciseId = s.selectedExerciseId,
)

/**
 * The state a rest-screen load correction leaves behind (#205).
 *
 * [addedKg] is the corrected ADDED load; the total the row stores is computed
 * beside the write, not here.
 *
 * [carryFollows] is `SetLoadPolicy.carryFollowsCorrection`'s answer, decided
 * BEFORE the write against what was standing at the tap. When it is false this
 * touches the finished set and nothing else. When it is true the load box and
 * the standing statement both move to the corrected number, so the set coming
 * up is not offered the mistake a second time.
 *
 * BOTH FIELDS MOVE TOGETHER OR NEITHER DOES. Writing `loadInput` alone would
 * leave a plan set showing 65 in the box while `resolve` still read the slot's
 * 60, which is the box disagreeing with what the set would record -- the same
 * class of defect as #45. Writing `statedLoadKg` alone would record 65 under a
 * box still reading 60. The box takes the rounded render and the statement
 * takes the exact value, which is the arrangement `restingState` already uses.
 */
internal fun loadCorrectedState(s: RecordState, addedKg: Double, carryFollows: Boolean): RecordState {
    val corrected = s.copy(lastFeedback = s.lastFeedback?.copy(loadOverrideAddedKg = addedKg))
    if (!carryFollows) return corrected
    return corrected.copy(
        loadInput = s.weightUnit.inputValue(addedKg),
        statedLoadKg = addedKg,
    )
}

/**
 * The write behind one tap of the load correction (#205).
 *
 * A delta applied to the figure that currently stands, so repeated taps
 * accumulate. Everything the tap decides -- the delta, the total the row will
 * store, whether the carry follows -- is resolved OUTSIDE the coroutine from
 * ONE snapshot of the state the finger was looking at, so the three cannot
 * come from different sets.
 *
 * Returns without writing when the corrected load is the load already
 * standing -- a loaded set at an empty bar tapped down again -- so a tap that
 * changes nothing does not spend a Room write or republish the state.
 *
 * THE ANALYSIS IS NOT RECOMPUTED. `SetAnalyzer` derived the per-rep bar power
 * from the load at set end and that JSON is already stored; see
 * `SessionRepository.overrideLoad` for why rescaling it here would be worse
 * than naming it.
 *
 * THE CORRECTION IS PUBLISHED BEFORE THE WRITE, as [applyWarmupMark] does and
 * unlike [applyDurationCorrection]. `correctLoad` suspends into Room and
 * Dispatchers.Main.immediate runs a coroutine body only as far as its first
 * suspension, so with the write first a second tap arriving before the first
 * returned would read a state the first had not replaced yet, compute the same
 * corrected load, and be LOST -- not reordered, lost: two fast taps of + would
 * move a 60 kg set to 62.5 and not to 65. Publishing first makes the second tap
 * step from the first tap's answer.
 *
 * The rollback is not a race of its own. `correctLoad` returns null only when
 * there is no recorded set and it decides that BEFORE it suspends, so the
 * restore runs in the same frame as the tap, and it restores the fields this
 * wrote rather than the whole state.
 *
 * What is NOT ordered is the two writes themselves. Room dispatches suspend
 * queries to a pool, so two taps inside one window can still reach the
 * database in either order and the row may keep the first tap's total while
 * this state keeps the second's -- the trade [applyWarmupMark] already takes,
 * a disagreement the next screen shows against a tap silently dropped. Both
 * paragraphs are read from source; neither was observed on a device.
 */
internal fun applyLoadCorrection(
    stateFlow: MutableStateFlow<RecordState>,
    deltaKg: Double,
    ratings: SetRatingTracker,
    appScope: CoroutineScope,
) {
    val s0 = stateFlow.value
    val feedback = s0.lastFeedback ?: return
    val before = feedback.effectiveAddedKg
    val after = SetLoadPolicy.correctedAddedKg(before, deltaKg, feedback.bodyweight)
    if (after == before) return
    val total = SetLoadPolicy.correctedTotalKg(feedback.loadKg, feedback.addedKg, after)
    val carryFollows =
        SetLoadPolicy.carryFollowsCorrection(standingAddedKg(s0), before, s0.weightUnit, carryBlock(s0))
    stateFlow.value = loadCorrectedState(s0, after, carryFollows)
    appScope.launch(Dispatchers.Main.immediate) {
        if (ratings.correctLoad(total) == null) {
            stateFlow.value = loadCorrectionRolledBack(stateFlow.value, s0, carryFollows)
        }
    }
}

/**
 * The state a load correction leaves behind when there was no set to write it
 * to -- the optimistic publish in [applyLoadCorrection] undone.
 *
 * Field-scoped rather than a whole-state restore, exactly as
 * [applyWarmupMark]'s rollback is: [s0] is the snapshot the tap was taken
 * against, and anything else that moved between the tap and the null return
 * has to survive.
 */
internal fun loadCorrectionRolledBack(s: RecordState, s0: RecordState, carryFollows: Boolean): RecordState {
    val restored =
        s.copy(lastFeedback = s.lastFeedback?.copy(loadOverrideAddedKg = s0.lastFeedback?.loadOverrideAddedKg))
    if (!carryFollows) return restored
    return restored.copy(loadInput = s0.loadInput, statedLoadKg = s0.statedLoadKg)
}

/**
 * The write behind a corrected effort rating, lifted whole out of
 * `RecordViewModel.rateLastSet` by #208 for [applyRepCorrection]'s reason.
 *
 * appScope, as [applyRepCorrection]: the rest screen is the only place a set's
 * effort can be corrected, and the pop that leaves it cancelled the correction
 * on the way out.
 */
internal fun applyRating(
    stateFlow: MutableStateFlow<RecordState>,
    rpe: Int?,
    failed: Boolean,
    ratings: SetRatingTracker,
    appScope: CoroutineScope,
) {
    appScope.launch(Dispatchers.Main.immediate) {
        // The stored warm-up flag is handed back unchanged rather than
        // taken from the correction: `updateRpe` writes the column on every
        // correction, so passing anything else here would let re-rating a
        // ramp set silently turn it into work (#187).
        val warmup = stateFlow.value.lastSetWarmup
        val effectiveFailed = ratings.rate(rpe, failed, warmup) ?: return@launch
        stateFlow.value = ratedState(stateFlow.value, rpe, failed, effectiveFailed)
    }
}
