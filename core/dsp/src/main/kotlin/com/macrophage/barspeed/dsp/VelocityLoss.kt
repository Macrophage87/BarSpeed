package com.macrophage.barspeed.dsp

/**
 * Whether a set has a velocity-loss figure at all, and when it does not, which
 * of three different absences it is in.
 *
 * `velocityLoss_pct` is best rep to LAST rep. The reference is the maximum
 * `meanConVelMps` over the reps `AccelArtefact.isPeakEligible` admits (#306),
 * and the last rep is required to be one of them, so the list the maximum is
 * taken over CONTAINS the last rep, `best - last` can never be negative and the
 * quotient can reach exactly 0.0 only when the last rep TIES the maximum. That makes a published 0.0 a
 * statement about the ORDER of the rep list rather than a measurement of
 * fatigue, and it is the reason this type exists: a bare `Double?` cannot tell
 * "the lifter held velocity" from "the last thing resolved was the fastest
 * thing in the set" from "there were not two reps to compare".
 *
 * A set in [TerminalRepIsFastest] publishes no `velocityLoss_pct` at all.
 * That withholds the figure on a genuinely flat set too, and the trade is
 * deliberate: best-to-last with last == best is the degenerate case of the
 * definition and carries no information about fatigue in either direction,
 * while `velocityLossBasis` still tells a reader the last rep was the fastest
 * of the set.
 */
sealed interface VelocityLoss {
    /** Best rep to last rep, percent, rounded as the export publishes it. */
    data class Measured(val pct: Double) : VelocityLoss

    /** Fewer than two reps resolved: there is no pair to take a difference over. */
    data object NotEnoughReps : VelocityLoss

    /** No rep carries a positive drive velocity, so there is nothing to divide by. */
    data object NoReference : VelocityLoss

    /**
     * The last rep resolved is the fastest of the set, so best-to-last is
     * exactly zero by construction.
     *
     * This is also the signature of a spurious final detection -- the sensor
     * set down, or the lifter getting off the machine, moving faster than any
     * rep. Nothing in the rep list tells that apart from a set held flat to the
     * end, which is why both are withheld rather than one being corrected.
     */
    data object TerminalRepIsFastest : VelocityLoss

    /**
     * At least two reps resolved, but the pair best-to-last needs is not one
     * the analysis can stand behind: fewer than two reps are
     * `AccelArtefact.isPeakEligible`, or the set's LAST rep is not. Issue #306.
     *
     * A distinct case rather than [NotEnoughReps], because a set of thirteen
     * detections is not a set with too few reps, and saying which absence a set
     * is in is the reason this type exists.
     */
    data object NoEligiblePair : VelocityLoss

    /** The figure to publish, or null in every case where there is not one. */
    val pctOrNull: Double? get() = (this as? Measured)?.pct

    /**
     * The wire name of this case. These strings are the vocabulary
     * `SessionExport.VALID_VELOCITY_LOSS_BASES` publishes; this is the side
     * that owns them.
     */
    val basis: String
        get() = when (this) {
            is Measured -> MEASURED
            NotEnoughReps -> NOT_ENOUGH_REPS
            NoReference -> NO_REFERENCE
            TerminalRepIsFastest -> TERMINAL_REP_IS_FASTEST
            NoEligiblePair -> NO_ELIGIBLE_PAIR
        }

    companion object {
        const val MEASURED = "measured"
        const val NOT_ENOUGH_REPS = "notEnoughReps"
        const val NO_REFERENCE = "noReference"
        const val TERMINAL_REP_IS_FASTEST = "terminalRepIsFastest"
        const val NO_ELIGIBLE_PAIR = "noEligiblePair"

        /**
         * A pure function of the rep list, so it can be asked of a stored
         * analysis at export time and not only of a set as it is recorded.
         */
        fun of(reps: List<RepAnalysis>): VelocityLoss {
            if (reps.size < 2) return NotEnoughReps
            // The pair must be one the analysis can stand behind (#306): a
            // reference taken on a rep whose velocity integral nothing bounds,
            // or on one a floor contact or an impossible sample reached, is a
            // statement about the integrator. field-44 set 3 published 84.9 %
            // off a reference rep carrying ten samples above the bound.
            val eligible = AccelArtefact.peakEligible(reps)
            if (eligible.size < 2 || !AccelArtefact.isPeakEligible(reps.last())) return NoEligiblePair
            val best = eligible.maxOf { it.meanConVelMps }
            if (best <= 0) return NoReference
            val last = reps.last().meanConVelMps
            // `>=` against the MAXIMUM, not "is the maximum at the last index".
            // maxByOrNull returns the FIRST maximum, so an index comparison
            // answers "not the fastest" on a tie -- and a tie is precisely the
            // case this branch exists for.
            if (last >= best) return TerminalRepIsFastest
            // No coerceAtLeast, and none is reachable: `best` is a maximum over
            // `eligible`, which contains `last` because the guard above
            // required it, so best - last is never negative, and
            // the branch above has already taken the equality case. What
            // remains is strictly positive.
            return Measured(round1((best - last) / best * 100.0))
        }

        private fun round1(x: Double) = Math.round(x * 10.0) / 10.0
    }
}
