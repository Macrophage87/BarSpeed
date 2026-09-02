package com.macrophage.barspeed.model

/**
 * What a set publishes about its own duration and its own prep, once it is
 * known whether the set ever entered its work phase (#216).
 *
 * Three answers rather than two flags at the call site, so a writer cannot
 * publish one of them and forget the other. Both writers -- the session
 * document and the raw archive's manifest -- take the same instance.
 */
data class AbandonedSetPhase(
    /**
     * The seconds to publish under `duration_s`, or null to publish nothing.
     *
     * Null on a set that ended before its work began. The stored column holds
     * 0 there, and a published 0 is a measurement claim: `num()` drops nulls
     * and prints zeros, so nothing downstream can tell it from a hold that was
     * attempted and lasted no time.
     */
    val durationS: Int?,
    /**
     * The seconds to publish under `prep_s`, or null to publish nothing.
     *
     * Null on a set that ended before its work began, because the stored
     * column holds the prep the app SET OUT to play rather than the prep that
     * elapsed, and on such a set the two are provably different.
     */
    val prepS: Int?,
    /** Whether to publish `abandonedInPrep`. True only where it is known true. */
    val abandonedInPrep: Boolean,
)

/**
 * Whether a set ever entered its work phase, and what an export may say about
 * one that did not (#216).
 *
 * Pure and here rather than in `:app` or `:core:data`, so the rule runs on
 * every push and both export writers reach the same answer. `:app` has almost
 * no test source set, and the wiring that supplies the instant is compile- and
 * lint-gated only -- what is pinned here is the decision, not that the
 * recorder hands it the right arguments.
 *
 * [PrepWindowPolicy] is the near neighbour and answers a different question:
 * whether the prep INTERVAL can be stated. This one answers whether the WORK
 * happened at all, and the two differ on a set with no prep, where there is no
 * interval to state and the work began at the tap.
 */
object AbandonedSetPolicy {
    /**
     * Did this set's work begin?
     *
     * [PrepCase.NONE] is true with no instant consulted: no prep runs, so the
     * work begins at the tap and there is nothing to wait for. On the other
     * two cases the instant IS the answer -- `:app` sets it when the set's own
     * clock starts on a timed set and when the cadence's first stroke call
     * comes due on a cued one, and leaves it null when the set ended first.
     *
     * Deliberately not derived from whether a prep window was stored. A window
     * is also refused when the two instants invert, which
     * [PrepWindowPolicy.of] documents and which `System.currentTimeMillis`
     * makes reachable; reading its absence as "the work never began" would
     * publish an abandonment because a clock was corrected mid-set.
     */
    fun workBegan(case: PrepCase, workStartedAtMs: Long?): Boolean = case == PrepCase.NONE || workStartedAtMs != null

    /**
     * The duration, the prep and the flag a set may publish.
     *
     * [workBegan] null is the THIRD state and is not "no": it is a row written
     * before the app recorded this at all, and every such row must go on
     * publishing exactly what it published before. Folding null in with false
     * would strike `duration_s` off every timed set in the lifter's history
     * and mark the whole archive abandoned.
     */
    fun published(workBegan: Boolean?, actualDurationS: Int?, prepS: Int?): AbandonedSetPhase =
        if (workBegan == false) {
            AbandonedSetPhase(durationS = null, prepS = null, abandonedInPrep = true)
        } else {
            AbandonedSetPhase(durationS = actualDurationS, prepS = prepS, abandonedInPrep = false)
        }
}
