package com.macrophage.barspeed.model

/**
 * The rpe a set row may store beside its two failure facts (#313).
 *
 * The owner's rule, in his words: "Don't ask for an RPE on failed sets, if you
 * can't do it, it's failed." `RestEffortPromptPolicy` stops ASKING a failed
 * set; this decides what a write STORES, which is a different question,
 * because a rating can already be standing when a failure arrives -- rate a
 * hold, then correct its seconds below the close-enough fraction of the
 * target, and the failure is derived at the write while the rating is still on
 * the row.
 *
 * THREE WRITES CAN PAIR A RATING WITH A FAILURE: the set write
 * (`SetRatingTracker.onSetRecorded`), the rest screen's re-rating
 * (`SetRatingTracker.rate`) and the Correct popup's SAVE
 * ([SetCorrectionPolicy.row]). SEAM ONLY at this commit: the SAVE reads this
 * rule and the other two store the rating they were handed, as before.
 *
 * `failedByLifter` and `shortfall` are the two facts `SetRatingTracker` keeps
 * apart, taken separately so a pin can tell which of them a later change
 * stopped reading. Neither is moved here: `failedByLifter` is the lifter's own
 * word and nothing in this rule writes it.
 */
object FailedSetRatingPolicy {
    /**
     * The rpe to store, given the rating [rpe] the write carries.
     *
     * SEAM ONLY at this commit: a failure the lifter stated clears the rating,
     * which is what `SetCorrectionPolicy.row` has done since #310, and a
     * derived [shortfall] does not. The parameter is ignored under a stated
     * suppression until #313's fix reads it.
     */
    @Suppress("UnusedParameter")
    fun storedRpe(rpe: Int?, failedByLifter: Boolean, shortfall: Boolean): Int? = if (failedByLifter) null else rpe
}
