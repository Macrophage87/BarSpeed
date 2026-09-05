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
 * which fires when a rep FINISHES. So the ring counted finished reps.
 *
 * #243 moved every numbered call the VOICE makes onto the rep in hand: a
 * lifter starting their seventh now hears `"Rep 7"`. The screen did not move
 * with it, so for the whole of every guided set the ring read one less than
 * the voice said, which a lifter glancing at the phone reads as a lost count
 * (#252).
 *
 * The rule is pure and lives here so `CadencePlan.announcementFor` and this can
 * be pinned to name the same rep. That pin is in `:core:dsp`
 * (`RingVoiceAgreementTest`), the only side that can see both.
 *
 * `[Field]` -- the two channels are pinned equal as STRINGS, and nothing here
 * has been seen and heard together. No device has drawn this caption: the lane
 * that wrote it polled free memory every 60 s for 30 minutes and never saw
 * above 1.52 GB against the bench recipe's ~3 GB floor, so the emulator was
 * never booted. Whether the ring and the voice now read as one count is the
 * lifter's to say, mid-set, on any tempo with a beat able to carry a call.
 */
object GuidedRepCaption {
    /**
     * What the ring says, or null when it says nothing about reps.
     *
     * @param finishedReps reps COMPLETED so far -- `RecordState.manualReps`,
     *   which the runner sets from `onRepCounted`. The rep in hand is one more
     *   than this, and naming the parameter for what it holds rather than for
     *   what is displayed is the whole of the defect this closes.
     * @param plannedReps the count asked of the set, or null when none was.
     *   A non-positive count is treated as no count: an ad-hoc set's rep field
     *   is a text box, and `"0"` parses.
     * @param leadIn true while the prep countdown is running, when no rep is in
     *   hand yet. The ring names how many are coming rather than claiming the
     *   lifter is in the first one.
     * @param finished true once the cadence has called the last rep through --
     *   `RecordState.guidedFinished`. Checked FIRST: a finished set has no rep
     *   in hand, and every other branch would invent one.
     */
    fun forRing(finishedReps: Int, plannedReps: Int?, leadIn: Boolean, finished: Boolean): String? {
        val planned = plannedReps?.takeIf { it > 0 }
        val repInHand = finishedReps + 1
        return when {
            finished -> planned?.let { "$finishedReps of $it done" } ?: "$finishedReps done"
            leadIn -> planned?.let { "$it reps to come" }
            planned == null -> "rep $repInHand"
            // `CadencePlan.LAST_REP`'s words, lower case for this line. Stated
            // rather than imported: `:core:model` cannot see `:core:dsp`, so
            // the agreement is pinned there instead of shared from here.
            repInHand >= planned -> "last rep of $planned"
            else -> "rep $repInHand of $planned"
        }
    }
}
