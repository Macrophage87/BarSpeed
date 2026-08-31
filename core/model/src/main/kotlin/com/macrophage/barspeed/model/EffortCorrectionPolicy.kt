package com.macrophage.barspeed.model

/**
 * What the rest screen's effort-correction grid pre-lights for the set that has
 * just been stored.
 *
 * At most one of [failed] and a non-null [rpe] is ever set; the grid draws one
 * tile per fact and two lit tiles say two contradictory things about one set.
 *
 * There is no warm-up member since #187, and #194 did not put one back. Warm-up
 * left the effort scale to become a declaration about the set's purpose --
 * the plan's, or since #194 the lifter's own mark on the rest screen -- so no
 * tile of this grid can set it and nothing here can pre-light it. A warm-up
 * set is rated on the same rungs as every other set, which is what the
 * parameter used to prevent. The mark is drawn as its own row beside this
 * grid, never as a rung in it.
 */
data class EffortSelection(
    /** The "failed the set" tile is pre-lit. */
    val failed: Boolean,
    /** The tile for this RPE is pre-lit; null pre-lights no effort tile. */
    val rpe: Int?,
    /**
     * The set is recorded short of its target and the lifter never said so.
     *
     * Not a tile. It is the reason the grid may pre-light nothing at all, and
     * the grid has to say so out loud or "nothing lit" reads as the app having
     * lost the rating.
     */
    val derivedShortfall: Boolean,
)

/**
 * Which effort tile the correction grid pre-lights, given the two independent
 * failure facts the app keeps apart everywhere else.
 *
 * Here rather than in `:app` because the one file that can express this
 * decision is a Compose screen no test on the CI path can render, whose only
 * gate is that it compiles. Lifted out, the rule is pinned on every push.
 *
 * The two facts are the lifter's own tap and the objective shortfall derived
 * from the rep or second count. `SetRatingTracker` deliberately keeps them
 * apart and ORs them on the way to the database, so that correcting a
 * miscounted rep total re-derives one without erasing the other.
 *
 * A lit tile is a quotation. It says "this is what you told the app", which is
 * why only a fact the lifter supplied may light one, and why a shortfall the
 * app worked out for itself gets a sentence instead. Correcting the effort
 * cannot un-fail a set that ended short -- `SetRatingTracker.rate` ORs the
 * derived flag back in on every correction -- so the grid has to say that in
 * words rather than by pre-lighting a verdict as though it were an answer
 * already given.
 */
object EffortCorrectionPolicy {
    /**
     * @param rpe the effort rating stored for the set, or null.
     * @param tappedFailed the lifter tapped a failure tile in their own words.
     * @param derivedFailed the set was recorded short of its target.
     */
    fun selection(rpe: Int?, tappedFailed: Boolean, derivedFailed: Boolean): EffortSelection = EffortSelection(
        // Only the lifter's own word lights this tile, and only where no
        // rating stands beside it: tapping the failed tile stores rpe null,
        // so a set carrying both has been re-rated since and the rating is
        // the later statement. derivedFailed is absent from this line on
        // purpose -- that is the whole of issue #140.
        failed = tappedFailed && rpe == null,
        rpe = if (!(tappedFailed && rpe == null)) rpe else null,
        derivedShortfall = derivedFailed && !tappedFailed,
    )

    /**
     * What the rest screen's effort line reads for the set just stored.
     *
     * [ratedDescription] is the gym-facing wording of whichever tile the
     * lifter's own rating lit, or null when the set carries no rating at all.
     * [failed] is the effective verdict, the lifter's tap OR-ed with the
     * derived shortfall, which is what the line is coloured by.
     *
     * SEAM ONLY at this commit: the unrated case returns the empty string,
     * which stands for what the screen does today -- draw no line and, with
     * it, no way into the correction grid. #168 makes that case reachable on
     * every timed set, because an auto-ended hold is ended by the clock and
     * not by a tap on the effort grid, so it arrives at rest with no rating
     * and nothing on screen to give it one. The differential is in the commit
     * after this.
     */
    fun lineText(ratedDescription: String?, failed: Boolean): String = when {
        ratedDescription != null && failed -> "$ratedDescription · short of target"
        ratedDescription != null -> ratedDescription
        failed -> FAILED
        else -> NOT_RATED
    }

    /** The line's wording for a set carrying only the derived shortfall. */
    const val FAILED = "Failed"

    /**
     * The line's wording for a set that carries no effort statement of any
     * kind.
     *
     * A named absence rather than a blank: an unrated set is not an RPE of
     * zero and is not a warm-up, and the line has to say which it is or the
     * lifter reads the gap as the app having lost the rating.
     */
    const val NOT_RATED = "Not rated"
}
