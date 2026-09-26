package com.macrophage.barspeed.model

/**
 * How many of a set's reps a tempo phase was MEASURED on, out of how many it
 * could have been -- the one coverage rule every tempo surface reads (#89,
 * #230, #328, #329).
 *
 * A tempo statement about a set is computed over the reps that resolved the
 * phase, and the reps that did not are dropped before anything is compared.
 * So "all on tempo" over the measured reps says nothing about the others, and
 * a screen that prints it without the count has scoped a sentence about the
 * whole set to a subset that selected itself. This type is that count, and
 * [notMeasuredClause] is the words a screen appends for it, so the rest
 * screen's eccentric card and the tempo chip's note cannot count or phrase
 * the gap two ways.
 *
 * [of] is every rep of the analysis, at every caller: the eccentric
 * caption, the tempo verdict line and the tempo chip's note. A rep that
 * resolved no scored phase is still a rep the phase went unmeasured on,
 * and a count taken over the reps a ratio graded cannot see it (#329).
 *
 * Pure arithmetic over two counts. Nothing here says what the sensor or the
 * lifter did on an unmeasured rep -- only that nothing measured it.
 */
data class PhaseCoverage(
    /** Reps the phase was measured on. */
    val measured: Int,
    /** Reps it could have been measured on. */
    val of: Int,
) {
    /**
     * Reps nothing measured the phase on. Never negative: a stored analysis
     * written under an older counting rule can carry a phase count above the
     * set's, and a history screen must draw that set rather than throw.
     */
    val notMeasured: Int get() = (of - measured).coerceAtLeast(0)

    /** True when no rep in [of] went unmeasured. */
    val complete: Boolean get() = notMeasured == 0

    /**
     * The clause a screen appends to a tempo statement it made over the
     * measured reps only, or null when there is no gap to state.
     */
    val notMeasuredClause: String? get() = if (complete) null else "$notMeasured not measured"
}
