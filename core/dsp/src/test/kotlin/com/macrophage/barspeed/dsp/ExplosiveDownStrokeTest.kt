package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.StartPhase
import com.macrophage.barspeed.model.Tempo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the guide and the scorer do with a tempo whose DOWN stroke is `X`
 * (#258).
 *
 * `Tempo.downS` is nullable from #258, so a `Tempo` carrying an explosive
 * digit 1 can be built here directly whatever `Tempo.parse` accepts. Every
 * test in this file holds at the commit that made the field nullable and
 * before any parse change, because each one pins a consumer that already
 * treated a null stroke as "no prescribed seconds": the scorer's null-seconds
 * phase is unscored, and the metronome's `strokeSeconds` plays a null as one
 * second. They are the "a pin rather than a change" half of the issue's
 * shape. What the guide CALLS such a stroke is a change, and is not pinned
 * here.
 */
class ExplosiveDownStrokeTest {
    /** A lat pulldown driven first: digit 1 is its drive. */
    private val pulldown = LiftDirection(startsWith = StartPhase.CONCENTRIC, concentricUp = false)

    /** A bench press lowered first: digit 1 is its lowering. */
    private val bench = LiftDirection(startsWith = StartPhase.ECCENTRIC, concentricUp = true)

    private val explosiveDown = Tempo(downS = null, bottomPauseS = 0.0, upS = 3.0, topPauseS = 0.0)

    private fun reps(count: Int) = (0 until count).map { i ->
        RepAnalysis(
            index = i,
            eccS = 3.0,
            bottomPauseS = 0.0,
            conS = 0.6,
            topPauseS = 0.0,
            meanConVelMps = 0.5,
            peakConVelMps = 0.8,
            meanEccVelMps = -0.2,
            peakEccVelMps = -0.3,
            romM = 0.4,
            peakPowerW = null,
        )
    }

    @Test
    fun `an X down stroke on a drive-down lift is the concentric, with no prescribed seconds`() {
        val schedule = TempoSchedule.of(explosiveDown, pulldown)
        assertNull(schedule.concentricS, "the drive has no prescribed seconds")
        assertEquals(3.0, schedule.eccentricS, "the return is digit 3")
        assertTrue(schedule.first.isConcentric, "the rep opens on the drive")
        assertEquals(3.0, schedule.prescribedCycleS, "an X contributes no seconds to the prescription")
    }

    @Test
    fun `the scorer leaves an X drive unscored and grades the return`() {
        val c = SetAnalyzer.complianceFor(explosiveDown, toleranceS = 0.5, reps = reps(5), direction = pulldown)
        val drive = c.phases.first { it.phase == "concentric" }
        val lowering = c.phases.first { it.phase == TempoComplianceResult.PHASE_ECCENTRIC }
        assertNull(drive.prescribedS)
        assertFalse(drive.scored, "an X phase is never scored")
        assertEquals(0, drive.repsEvaluated)
        assertTrue(lowering.scored, "the return is still graded")
        assertEquals(5, lowering.repsWithinTolerance)
        assertEquals(5, c.repsFullyCompliant, "every rep is judged on the one phase that was prescribed")
        assertNull(c.prescribedEccConRatio, "no ratio against a stroke with no seconds")
    }

    @Test
    fun `the metronome plays an X down stroke as a one-second beat, as it plays an X up stroke`() {
        val down = CadencePlan.of(TempoSchedule.of(explosiveDown, pulldown))
        val up = CadencePlan.of(TempoSchedule.of(Tempo.parse("30X0"), bench))
        assertEquals(1, down.beats.first().seconds, "the X drive is a one-second beat")
        assertEquals(listOf(1, 3), down.beats.filter { it.isStroke }.map { it.seconds })
        assertEquals(
            up.beats.filter { it.isStroke }.map { it.seconds }.sorted(),
            down.beats.filter { it.isStroke }.map { it.seconds }.sorted(),
            "the same two stroke lengths, whichever digit carries the X",
        )
        assertEquals(4, down.deliveredCycleS)
    }

    @Test
    fun `an X down stroke on a drive-up lift is the eccentric, and the scorer leaves it unscored too`() {
        val schedule = TempoSchedule.of(explosiveDown, bench)
        assertNull(schedule.eccentricS)
        assertEquals(3.0, schedule.concentricS)
        val c = SetAnalyzer.complianceFor(explosiveDown, toleranceS = 0.5, reps = reps(3), direction = bench)
        assertFalse(c.phases.first { it.phase == TempoComplianceResult.PHASE_ECCENTRIC }.scored)
    }
}
