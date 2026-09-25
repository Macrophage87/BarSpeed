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
 * ONE RULE, READ BY ALL THREE WRITES THAT CAN PAIR A RATING WITH A FAILURE:
 * the set write (`SetRatingTracker.onSetRecorded`), the rest screen's
 * re-rating (`SetRatingTracker.rate`) and the Correct popup's SAVE
 * ([SetCorrectionPolicy.row]). Until #313 only the SAVE cleared a rating, and
 * only for a failure the lifter stated, so the same set could carry a rating
 * beside a failure the app derived and lose it beside one the lifter tapped.
 *
 * WHAT IT COSTS, stated rather than discovered: a rating cleared by a derived
 * failure is gone from the row. A lifter who then corrects the seconds or the
 * count back up past the target has an unrated set; while the rest screen is
 * up, the Correct popup's grid is where they can rate it again. A rung tapped in that same popup
 * beside a draft that still derives short is not stored either.
 *
 * `failedByLifter` and `shortfall` are the two facts `SetRatingTracker` keeps
 * apart, taken separately so a pin can tell which of them a later change
 * stopped reading. Neither is moved here: `failedByLifter` is the lifter's own
 * word and nothing in this rule writes it.
 */
object FailedSetRatingPolicy {
    /**
     * The rpe to store, given the rating [rpe] the write carries: none on a
     * set that failed by either fact, the rating otherwise.
     */
    fun storedRpe(rpe: Int?, failedByLifter: Boolean, shortfall: Boolean): Int? =
        if (failedByLifter || shortfall) null else rpe
}
