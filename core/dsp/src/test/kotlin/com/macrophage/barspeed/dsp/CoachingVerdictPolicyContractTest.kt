package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.CoachingVerdictPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The sentence `CoachingRules` writes and the prefix `CoachingVerdictPolicy`
 * drops, pinned equal (#261).
 *
 * The producer is in `:core:dsp` and the reader's decision is in `:core:model`,
 * which cannot import the producer -- the same arrangement, and the same
 * reason, as `VelocityLossRegimeTempoScheduleContractTest`. This module depends
 * on both, so this is the only place the two statements can be compared at all.
 * That it catches the drift is measured, not asserted: a mutation run recorded
 * in this branch's tip commit body reworded the produced sentence to "Large
 * velocity loss (" and observed this class red while every pin in
 * `:core:model` stayed green. The reader's own tests hold their own copy of
 * the text, so this module is the only place a producer-side reword is seen.
 */
class CoachingVerdictPolicyContractTest {
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

    private val fading = listOf(rep(0, 1.0), rep(1, 0.9), rep(2, 0.5), rep(3, 0.4))

    @Test
    fun `the fatigue sentence CoachingRules writes starts with the prefix the policy drops`() {
        val out = CoachingRules.verdicts(fading, 42.8, tempoCompliance = null, targets = SetTargets())
        assertTrue(
            out.single().startsWith(CoachingVerdictPolicy.FATIGUE_PREFIX),
            "produced: ${out.single()}",
        )
    }

    @Test
    fun `no other line CoachingRules can write starts with that prefix`() {
        // Every other verdict kind the rules can produce, gathered on one set:
        // the resolved-reps note, the shortfall, the bar-speed note, the
        // prescribed stop and a tempo phase line. Exactly none of them may be
        // taken for the fatigue sentence.
        val targets = SetTargets(
            plannedReps = 6,
            countedReps = 5,
            targetMeanConcentricVelocityMps = 1.5,
            velocityLossStopPct = 20.0,
        )
        val out = CoachingRules.verdicts(fading, 42.8, tempoCompliance = null, targets = targets)
        assertEquals(4, out.size, "four lines, none of them the fatigue sentence: $out")
        assertTrue(
            out.none { it.startsWith(CoachingVerdictPolicy.FATIGUE_PREFIX) },
            "no false positive: $out",
        )
    }
}
