package com.macrophage.barspeed.model

/**
 * The line under the guided set's ring: which rep the lifter is in, of how
 * many.
 *
 * ## Why it is here and not in the composable
 *
 * It was one expression inside `RecordScreen.GuidedSetStage` --
 * `"rep ${state.manualReps}"` plus an optional `" of $n"` -- and
 * `state.manualReps` is written from `GuidedCadenceRunner`'s `onRepCounted`,
 * which fires when a rep FINISHES. So the ring counts finished reps.
 *
 * #243 moved every numbered call the VOICE makes onto the rep in hand: a
 * lifter starting their seventh now hears `"Rep 7"`. The screen did not move
 * with it, so for the whole of every guided set the ring reads one less than
 * the voice says, which a lifter glancing at the phone reads as a lost count
 * (#252).
 *
 * Nothing about that is fixed at THIS commit. The expression is lifted here
 * exactly as it stood, defect included, so the differentials that follow have
 * something to red against; `CadencePlan`'s own KDoc already states the
 * mismatch and says it was left for this.
 */
object GuidedRepCaption {
    /**
     * What the ring says, or null when it says nothing about reps.
     *
     * @param finishedReps reps COMPLETED so far -- `RecordState.manualReps`,
     *   which the runner sets from `onRepCounted`. The rep in hand is one more
     *   than this, and naming the parameter for what it holds rather than for
     *   what is displayed is the whole of the defect this will close.
     * @param plannedReps the count asked of the set, or null when none was.
     * @param leadIn true while the prep countdown is running, when no rep is in
     *   hand yet.
     * @param finished true once the cadence has called the last rep through --
     *   `RecordState.guidedFinished`.
     */
    @Suppress("UnusedParameter")
    fun forRing(finishedReps: Int, plannedReps: Int?, leadIn: Boolean, finished: Boolean): String? {
        // The lift, verbatim. [leadIn] and [finished] are accepted and
        // deliberately not read: the screen drew one caption for all three
        // phases of a guided set, and that is what the next commit reds. The
        // suppression above goes with the body it describes.
        return "rep $finishedReps" + (plannedReps?.let { " of $it" } ?: "")
    }
}
