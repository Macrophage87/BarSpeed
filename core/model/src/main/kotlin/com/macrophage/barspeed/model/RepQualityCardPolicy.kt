package com.macrophage.barspeed.model

/** What the rest screen's rep-quality card draws for the set just stored. */
enum class RepQualityLayout {
    /** No card at all. */
    NONE,

    /** The verdict lines, as text, with no chart above them. */
    VERDICTS,

    /** An explosive set's peak-velocity chart, its verdicts under it. */
    PEAK_VELOCITY_CHART,

    /** The eccentric-tempo chart against the graded prescription, its verdicts under it. */
    ECC_TEMPO_CHART,

    /** The concentric-velocity chart, its verdicts under it. */
    CON_VELOCITY_CHART,
}

/**
 * Which of [RepQualityLayout]'s shapes `LastSetDetail`'s `RepQualityCard`
 * draws (#281).
 *
 * Here rather than as a chain of early returns in the composable because the
 * composable is the rest screen's only reader of `SetAnalysis.verdicts`, and
 * no test on the CI path can render it. A branch that drops a verdict there is
 * a branch nothing can fail; lifted out, the draw decision runs on every push.
 *
 * Every chart draws its verdicts under it, so a layout that is a chart is also
 * a layout that shows the verdicts. A chart needs reps to plot; the verdicts
 * do not, and a rep set with none still draws them (#281). A set #247 refuses
 * has an empty rep list by construction and states why in its verdicts
 * (`SetAnalyzer.MOUNT_NOT_DECLARED_VERDICT`); the rest screen is the one window
 * in which such a set can still be corrected, and the card used to return
 * before reaching the only renderers of that list, so the lifter got a blank
 * card with no reason. History draws the same lines outside its reps block.
 */
object RepQualityCardPolicy {
    /**
     * @param timed the set is a hold or a carry, measured in seconds.
     * @param hasReps the stored analysis resolved at least one rep.
     * @param hasVerdicts the verdict lines the card would draw are not empty --
     *   after `restVerdicts` has re-graded a hold or filtered a rep set for
     *   its velocity-loss regime, never the frozen list before that.
     * @param explosive the set is judged on peak velocity.
     * @param gradedEccentric the analysis graded the eccentric against a
     *   prescription, so the tempo chart has a target line to draw.
     */
    fun layout(
        timed: Boolean,
        hasReps: Boolean,
        hasVerdicts: Boolean,
        explosive: Boolean,
        gradedEccentric: Boolean,
    ): RepQualityLayout = when {
        // Timed sets have no reps; the hold verdicts are drawn instead of a chart.
        timed -> if (hasVerdicts) RepQualityLayout.VERDICTS else RepQualityLayout.NONE
        !hasReps -> if (hasVerdicts) RepQualityLayout.VERDICTS else RepQualityLayout.NONE
        explosive -> RepQualityLayout.PEAK_VELOCITY_CHART
        gradedEccentric -> RepQualityLayout.ECC_TEMPO_CHART
        else -> RepQualityLayout.CON_VELOCITY_CHART
    }
}
