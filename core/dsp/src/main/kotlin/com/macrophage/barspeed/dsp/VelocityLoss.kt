package com.macrophage.barspeed.dsp

/**
 * Whether a set has a velocity-loss figure at all, and when it does not, which
 * of three different absences it is in.
 *
 * `velocityLoss_pct` is best rep to LAST rep. The reference is
 * `reps.maxOf { meanConVelMps }`, taken over a list that CONTAINS the last rep,
 * so `best - last` can never be negative and the quotient can reach exactly 0.0
 * only when the last rep TIES the maximum. That makes a published 0.0 a
 * statement about the ORDER of the rep list rather than a measurement of
 * fatigue, and it is the reason this type exists: a bare `Double?` cannot tell
 * "the lifter held velocity" from "the last thing resolved was the fastest
 * thing in the set" from "there were not two reps to compare".
 *
 * [of] currently reproduces [SetAnalyzer.velocityLossPct] exactly, including
 * the degenerate case. Naming the cases is the whole of this commit; deciding
 * differently about them is not.
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
     * exactly zero by construction. Declared here, and not yet returned by
     * [of].
     */
    data object TerminalRepIsFastest : VelocityLoss

    /** The figure to publish, or null in every case where there is not one. */
    val pctOrNull: Double? get() = (this as? Measured)?.pct

    /**
     * The wire name of this case. These four strings are the vocabulary
     * `SessionExport.VALID_VELOCITY_LOSS_BASES` publishes; this is the side
     * that owns them.
     */
    val basis: String
        get() = when (this) {
            is Measured -> MEASURED
            NotEnoughReps -> NOT_ENOUGH_REPS
            NoReference -> NO_REFERENCE
            TerminalRepIsFastest -> TERMINAL_REP_IS_FASTEST
        }

    companion object {
        const val MEASURED = "measured"
        const val NOT_ENOUGH_REPS = "notEnoughReps"
        const val NO_REFERENCE = "noReference"
        const val TERMINAL_REP_IS_FASTEST = "terminalRepIsFastest"

        /**
         * A pure function of the rep list, so it can be asked of a stored
         * analysis at export time and not only of a set as it is recorded.
         */
        fun of(reps: List<RepAnalysis>): VelocityLoss {
            if (reps.size < 2) return NotEnoughReps
            val best = reps.maxOf { it.meanConVelMps }
            if (best <= 0) return NoReference
            val last = reps.last().meanConVelMps
            return Measured(round1(((best - last) / best * 100.0).coerceAtLeast(0.0)))
        }

        private fun round1(x: Double) = Math.round(x * 10.0) / 10.0
    }
}
