package com.macrophage.barspeed.model

/**
 * What a session close that came back failed has not written (#333).
 *
 * A close is two writes in order (`SessionCloser`): `endSession`, which stamps
 * the end time and stores the heart-rate summary, the HRV, the session rating
 * and the skipped-set list in one update, and then the rest window recorded
 * after the last set. Which of them failed decides what is still held only in
 * memory, and so what the unfinished-session prompt may say leaving costs.
 *
 * Each field is true where that thing exists AND did not reach storage. A
 * session the lifter did not rate has no rating to lose, and a close that
 * captured no heart rate after the last set has no window to lose; saying
 * either is at risk would name a loss that cannot happen.
 */
data class UnwrittenClose(
    /** `endSession` did not land: no end time, no summary, no HRV. */
    val sessionRow: Boolean,
    /** The lifter's rating of the whole session (#159), given and not written. */
    val sessionRating: Boolean,
    /** The prescribed sets the lifter skipped (#300), recorded and not written. */
    val skippedSets: Boolean,
    /** The heart rate recorded after the last set (#109), captured and not written. */
    val finalRest: Boolean,
) {
    /** The close state this leaves the rest screen in. */
    val state: SessionCloseState
        get() = if (sessionRow) SessionCloseState.FAILED else SessionCloseState.REST_UNSAVED

    companion object {
        /**
         * What is unwritten after a failed close.
         *
         * [sessionRowLanded] is whether `endSession` returned before the
         * failure; [rated], [skipped] and [restCaptured] are whether the frozen
         * close carried a rating, at least one skipped set and at least one
         * heart-rate sample after the last set. The rating and the skips ride
         * in `endSession`'s one update, so they are unwritten exactly when it
         * did not land. The rest window is written after it, so a failure at
         * either write leaves it unwritten.
         */
        fun of(sessionRowLanded: Boolean, rated: Boolean, skipped: Boolean, restCaptured: Boolean) = UnwrittenClose(
            sessionRow = !sessionRowLanded,
            sessionRating = !sessionRowLanded && rated,
            skippedSets = !sessionRowLanded && skipped,
            finalRest = restCaptured,
        )
    }
}
