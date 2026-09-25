package com.macrophage.barspeed.model

/**
 * What one SAVE of the rest screen's Correct popup says about the count and
 * the effort of the set just stored (#310).
 *
 * [reps] and [seconds] are null where the draft left that figure where it
 * stood; at most one is ever non-null, because a set is either counted or
 * timed. [rpe] and [tappedFailed] are the draft's rating as the popup drew it
 * at the SAVE. The popup seeds both from what stands, so where the lifter did
 * not touch the grid they ARE the standing rating, and [ratingChanged] says
 * whether they touched it.
 */
data class CountAndRatingDraft(
    val reps: Int?,
    val seconds: Int?,
    val rpe: Int?,
    val tappedFailed: Boolean,
    val ratingChanged: Boolean,
) {
    /** Whether the SAVE changed anything this draft carries. */
    val changesAnything: Boolean get() = reps != null || seconds != null || ratingChanged
}

/** The rating half of a set row, as one statement writes it. */
data class CorrectedRatingRow(
    val rpe: Int?,
    val failed: Boolean,
    val failedByLifter: Boolean,
)

/**
 * The single rating row one Correct-popup SAVE leaves on a set (#310).
 *
 * Here rather than in `:app` because the popup is a Compose screen no test on
 * the CI path reaches, and because the defect this exists for was a decision
 * split across two writes whose order nothing fixed: the count correction
 * wrote a rating it read before it suspended, the rating correction wrote the
 * lifter's new one, and whichever Room ran last is what the row kept.
 * Folded into one answer, there is one rating to write.
 *
 * The two failure facts stay two facts, as `SetRatingTracker` keeps them:
 * [shortfall] is the derived half and comes off the count; the tapped half
 * comes off the draft and nothing else.
 */
object SetCorrectionPolicy {
    /**
     * The derived shortfall after the draft's count is applied.
     *
     * Seconds are judged by [TimedSetEndPolicy.fellShort], the function the
     * set write asked; reps by the rule `SetRatingTracker` has always used,
     * short of a planned count. A draft that moves neither leaves [standing]
     * where it is, because nothing it says bears on the verdict.
     */
    fun shortfall(
        draft: CountAndRatingDraft,
        plannedReps: Int?,
        plannedDurationS: Int?,
        standing: Boolean,
    ): Boolean {
        val seconds = draft.seconds
        val reps = draft.reps
        return when {
            seconds != null -> TimedSetEndPolicy.fellShort(seconds, plannedDurationS)
            reps != null -> plannedReps != null && reps < plannedReps
            else -> standing
        }
    }

    /**
     * The rating row the SAVE writes, given the shortfall it re-derived.
     *
     * A set the lifter calls failed carries NO rpe. The owner's rule, in his
     * words: "Don't ask for an RPE on failed sets, if you can't do it, it's
     * failed." Every failure tile already drafts a null rpe; this makes the
     * one write unable to pair a failure the lifter stated with a rating it
     * replaced, whatever the draft carried.
     */
    fun row(draft: CountAndRatingDraft, shortfall: Boolean): CorrectedRatingRow = CorrectedRatingRow(
        rpe = if (draft.tappedFailed) null else draft.rpe,
        failed = draft.tappedFailed || shortfall,
        failedByLifter = draft.tappedFailed,
    )
}
