package com.macrophage.barspeed.dsp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What `CoachingRules.verdicts` actually writes about velocity loss, pinned
 * before anything reads it back (#261).
 *
 * The strings are FROZEN. `SetAnalysis` is `@Serializable` and every recorded
 * set carries its verdict list spelled out in `analysisJson`, so a reader that
 * wants to suppress one of them has to recognise it by its text. These pins
 * say which text, and they say the two velocity-loss sentences are two
 * different sentences that cannot both be present.
 *
 * Characterization only: every assertion here holds on `origin/main` before
 * this branch changes anything.
 */
class CoachingVerdictLinesTest {
    private fun rep(index: Int, meanConVelMps: Double) = RepAnalysis(
        index = index,
        eccS = null,
        bottomPauseS = null,
        conS = 1.0,
        topPauseS = null,
        meanConVelMps = meanConVelMps,
        peakConVelMps = meanConVelMps,
        meanEccVelMps = null,
        peakEccVelMps = null,
        romM = 0.5,
        peakPowerW = null,
    )

    /** Best rep first, slowest last, so a stop threshold is crossed before the final rep. */
    private val fading = listOf(rep(0, 1.0), rep(1, 0.9), rep(2, 0.5), rep(3, 0.4))

    @Test
    fun `a high velocity loss with no prescribed stop writes the fatigue sentence`() {
        val out = CoachingRules.verdicts(fading, 42.8, tempoCompliance = null, targets = SetTargets())
        assertEquals(
            listOf("High velocity loss (42.8%) — significant fatigue this set."),
            out,
            "the sentence #261 is about, verbatim",
        )
    }

    @Test
    fun `the fatigue sentence always opens with the same prefix whatever the figure`() {
        // The figure is interpolated, so the only stable head of the line is
        // everything before it. A reader recognising the line has this and
        // nothing else to key on.
        for (loss in listOf(35.1, 42.8, 79.1, 100.0)) {
            val out = CoachingRules.verdicts(fading, loss, tempoCompliance = null, targets = SetTargets())
            assertEquals(1, out.size, "one line at $loss%")
            assertTrue(out.single().startsWith("High velocity loss ("), "prefix at $loss%: ${out.single()}")
        }
    }

    @Test
    fun `a prescribed velocity-loss stop writes its own sentence instead of the fatigue one`() {
        // The two are mutually exclusive by construction: the fatigue rule is
        // guarded on velocityLossStopPct == null. So the stop sentence -- which
        // reports that a threshold the PLAN asked for was crossed -- can never
        // be mistaken for the fatigue claim, and suppressing one cannot
        // suppress the other.
        val targets = SetTargets(velocityLossStopPct = 20.0)
        val out = CoachingRules.verdicts(fading, 42.8, tempoCompliance = null, targets = targets)
        assertEquals(
            listOf("Velocity-loss stop (20.0%) was reached at rep 3; later reps exceeded the plan."),
            out,
            "the plan's own threshold, reported",
        )
        assertTrue(out.none { it.startsWith("High velocity loss (") }, "and no fatigue sentence beside it")
    }

    @Test
    fun `the fatigue sentence shares the list with lines that are not about velocity`() {
        val out = CoachingRules.verdicts(
            fading,
            42.8,
            tempoCompliance = null,
            targets = SetTargets(plannedReps = 6, countedReps = 5),
        )
        assertEquals(
            listOf(
                "Sensor resolved 4 of 5 reps — per-rep velocity and tempo below cover only those.",
                "Completed 5 of 6 planned reps.",
                "High velocity loss (42.8%) — significant fatigue this set.",
            ),
            out,
            "three lines, one of them the velocity claim",
        )
    }
}
