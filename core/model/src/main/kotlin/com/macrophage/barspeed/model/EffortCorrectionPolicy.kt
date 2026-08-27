package com.macrophage.barspeed.model

/**
 * What the rest screen's effort-correction grid pre-lights for the set that has
 * just been stored.
 *
 * At most one of [warmup], [failed] and a non-null [rpe] is ever set; the grid
 * draws one tile per fact and two lit tiles say two contradictory things about
 * one set.
 */
data class EffortSelection(
    /** The warm-up tile is pre-lit. */
    val warmup: Boolean,
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
 * Here rather than in `:app` because `:app` is not test-gated: the one file
 * that can express this decision is a 2,300-line Compose screen whose only
 * gate is that it compiles. Lifted out, the rule is pinned on every push.
 *
 * The two facts are the lifter's own tap and the objective shortfall derived
 * from the rep or second count. `SetRatingTracker` deliberately keeps them
 * apart and ORs them on the way to the database, so that correcting a
 * miscounted rep total re-derives one without erasing the other.
 */
object EffortCorrectionPolicy {
    /**
     * @param rpe the effort rating stored for the set, or null.
     * @param warmup the lifter marked the set a warm-up.
     * @param tappedFailed the lifter tapped a failure tile in their own words.
     * @param derivedFailed the set was recorded short of its target.
     */
    fun selection(rpe: Int?, warmup: Boolean, tappedFailed: Boolean, derivedFailed: Boolean): EffortSelection {
        val anyFailed = tappedFailed || derivedFailed
        return EffortSelection(
            warmup = warmup,
            failed = !warmup && anyFailed,
            rpe = if (!warmup && !anyFailed) rpe else null,
            derivedShortfall = derivedFailed && !tappedFailed,
        )
    }
}
