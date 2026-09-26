package com.macrophage.barspeed.dsp

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The rest screen's eccentric caption never says "All reps on tempo." over
 * reps whose eccentric was not measured (#89).
 *
 * The caption picks the worst eccentric from the reps that resolved one, so
 * when that worst is in tolerance the sentence covers only those reps. #33
 * fixed the case where none resolved; this is the partial case, where the
 * caption must say how many were measured and how many of those were on
 * tempo, with the gap counted by [com.macrophage.barspeed.model.PhaseCoverage].
 *
 * No committed field capture reaches this branch with a partial set -- on
 * every mixed capture the worst measured eccentric is out of tolerance -- so
 * the reps are built by hand. Target 3.0 s, tolerance 0.5 s, as the rest
 * screen passes them.
 *
 * RED WHEN WRITTEN, except the two guards named in their KDoc.
 */
class EccentricCoverageInsightTest {
    private fun rep(index: Int, eccS: Double?) = RepAnalysis(
        index = index,
        eccS = eccS,
        bottomPauseS = null,
        conS = 1.0,
        topPauseS = null,
        meanConVelMps = 0.4,
        peakConVelMps = 0.6,
        meanEccVelMps = eccS?.let { -0.2 },
        peakEccVelMps = eccS?.let { -0.3 },
        romM = 0.5,
        peakPowerW = null,
    )

    private fun caption(reps: List<RepAnalysis>) = CoachingRules.eccentricTempoInsight(reps, 3.0, 0.5)

    /** Seven reps, five measured and all five in tolerance, two drive-only. */
    @Test
    fun `a partly measured set on tempo says how many were measured`() {
        val reps = (0..6).map { rep(it, if (it < 5) 3.2 else null) }
        assertEquals("All 5 measured reps on tempo · 2 not measured.", caption(reps))
    }

    /** The unmeasured reps are not only the late ones: the count is the count wherever they fall. */
    @Test
    fun `the gap is counted wherever the unmeasured reps fall`() {
        val reps = (0..5).map { rep(it, if (it % 2 == 0) 2.8 else null) }
        assertEquals("All 3 measured reps on tempo · 3 not measured.", caption(reps))
    }

    /** One measured rep is one rep, not "all 1". */
    @Test
    fun `a single measured rep is named as one`() {
        val reps = (0..4).map { rep(it, if (it == 2) 3.0 else null) }
        assertEquals("1 measured rep on tempo · 4 not measured.", caption(reps))
    }

    /** GUARD, green before and after: every rep measured and in tolerance is the whole set. */
    @Test
    fun `a fully measured set on tempo still says all reps`() {
        assertEquals("All reps on tempo.", caption((0..5).map { rep(it, 3.3) }))
    }

    /**
     * GUARD, green before and after: a partly measured set whose worst
     * measured rep is out of tolerance names that rep, a statement about one
     * rep that claims nothing about the others.
     */
    @Test
    fun `an out-of-tolerance rep is still named on a partly measured set`() {
        val reps = listOf(rep(0, 3.0), rep(1, null), rep(2, 1.5), rep(3, null))
        assertEquals("Rep 3 eccentric 1.5 s — 1.5 s too fast.", caption(reps))
    }
}
