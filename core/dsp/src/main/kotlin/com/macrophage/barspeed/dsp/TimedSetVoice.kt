package com.macrophage.barspeed.dsp

/**
 * What a hold or a carry says while its clock is running down.
 *
 * A timed set runs no [CadencePlan]: there is one movement and it lasts the
 * whole set, so there are no strokes to call. What it has instead is a clock,
 * and this is what the clock says.
 *
 * ## Why it is here rather than in the tick loop it came from
 *
 * This rule was four lines of `when` inside the coroutine `RecordViewModel`
 * launches to drive `setElapsedS`. No test on the CI path can construct that
 * `AndroidViewModel`, so nothing asserted any of it; the same arrangement in the same file already added a
 * beat no prescription asked for, which is issue 106. Moved here unchanged,
 * every case is a literal in `TimedSetVoiceTest`.
 *
 * The caller passes what [cueFor] returns to the same recorder the tempo calls
 * go through, so these words reach the set's cue track. Which of them a lifter
 * can hear over a loaded carry has not been measured and is not claimed.
 *
 * ## The shape of it
 *
 * Sparse on purpose, and the opposite of [LeadInPlan], which fills every second
 * of a prep. A prep is at most two minutes and a hold has no ceiling: a plank
 * counted out loud from 60 would be a minute of talking. So the far half of a
 * timed set is marked rather than counted -- a milestone every
 * [MILESTONE_EVERY_S] seconds of what remains -- and only the last
 * [FINAL_COUNTDOWN_FROM_S] seconds get every digit.
 *
 * Nothing is said past zero, and since #168 there is nothing there to say
 * anything about: the terminal word and the end of the set are the same
 * instant, decided once by `TimedSetEndPolicy` and handed to both. This used
 * to read "a set held longer than it was asked for is measured and scored the
 * same way, and the screen calls it bonus time" -- that went false with
 * auto-end and is corrected rather than reworded. Seconds past the target are
 * not measured and not scored; a genuine overage is stated afterwards on the
 * rest screen.
 *
 * [cueFor] still answers for negative input, and that is not vestigial. A
 * missed tick can present a remainder already past zero, and the answer has to
 * be silence rather than a spoken negative.
 */
object TimedSetVoice {
    /**
     * How often the remaining time is named, in seconds, until the final
     * countdown takes over.
     *
     * Counted in REMAINING seconds, not elapsed, so a 45 s hold is marked at 30
     * and 15 and a 40 s hold at 30 and 15 as well -- the marks land the same
     * distance from the end whatever the target is, which is the half of the
     * set the lifter is deciding whether to hold on through.
     */
    const val MILESTONE_EVERY_S = 15

    /** Longest remaining time counted down digit by digit, from this number to 1. */
    const val FINAL_COUNTDOWN_FROM_S = 10

    /**
     * Spoken as the target is reached.
     *
     * `Time` rather than `Done`: `Done` is what the guided cadence says when it
     * has called the last rep of a set, and a cue track that used one word for
     * both would leave a reader unable to tell which producer wrote it.
     */
    const val TIME_UP = "Time"

    /**
     * What is said when [remainingS] seconds of the target are left, or null
     * for a second that passes in silence.
     *
     * Negative input -- a tick the loop was late for, past a target the set is
     * about to end at -- is silence, and so is every second between
     * milestones.
     */
    fun cueFor(remainingS: Int): String? = when {
        remainingS == 0 -> TIME_UP
        remainingS in 1..FINAL_COUNTDOWN_FROM_S -> remainingS.toString()
        remainingS > 0 && remainingS % MILESTONE_EVERY_S == 0 -> "$remainingS seconds"
        else -> null
    }
}
