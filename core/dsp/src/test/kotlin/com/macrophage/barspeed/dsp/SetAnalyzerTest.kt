package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.StartPhase
import com.macrophage.barspeed.model.Tempo
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SetAnalyzerTest {
    private val tempoRep =
        SyntheticSets.RepSpec(eccS = 4.0, bottomPauseS = 1.0, conS = 1.0, topPauseS = 1.0, romM = 0.6)

    @Test
    fun `segments five tempo reps with correct phase durations`() {
        val samples = SyntheticSets.generate(List(5) { tempoRep })
        val analysis = SetAnalyzer.analyze(samples, StartPhase.ECCENTRIC, loadKg = 100.0)

        assertEquals(5, analysis.reps.size, "expected 5 reps, verdicts=${analysis.verdicts}")
        for (rep in analysis.reps) {
            assertTrue(abs(rep.eccS - 4.0) < 0.5, "eccentric ${rep.eccS}s should be ~4s")
            assertTrue(abs(rep.conS - 1.0) < 0.35, "concentric ${rep.conS}s should be ~1s")
            assertTrue(rep.bottomPauseS in 0.5..1.6, "bottom pause ${rep.bottomPauseS}s should be ~1s")
            assertTrue(rep.romM in 0.45..0.75, "ROM ${rep.romM}m should be ~0.6m")
            assertNotNull(rep.peakPowerW)
        }
    }

    @Test
    fun `measures concentric velocity accurately`() {
        val samples = SyntheticSets.generate(List(3) { tempoRep })
        val analysis = SetAnalyzer.analyze(samples, StartPhase.ECCENTRIC)
        // Half-sine with ROM 0.6 over 1 s: peak = rom*pi/(2T) = 0.94 m/s, mean = 2/pi * peak = 0.60 m/s.
        val expectedPeak = 0.6 * PI / 2.0
        for (rep in analysis.reps) {
            assertTrue(abs(rep.peakConVelMps - expectedPeak) < 0.12, "peak ${rep.peakConVelMps} vs $expectedPeak")
            assertTrue(abs(rep.meanConVelMps - 0.6) < 0.12, "mean ${rep.meanConVelMps} vs 0.60")
        }
    }

    @Test
    fun `detects a rushed eccentric as tempo violation`() {
        val rushed = tempoRep.copy(eccS = 2.0)
        val samples = SyntheticSets.generate(listOf(tempoRep, tempoRep, rushed))
        val analysis =
            SetAnalyzer.analyze(
                samples,
                StartPhase.ECCENTRIC,
                targets = SetTargets(tempo = Tempo.parse("4010"), toleranceS = 0.5),
            )
        assertEquals(3, analysis.reps.size)
        val compliance = assertNotNull(analysis.tempoCompliance)
        val ecc = compliance.phases.first { it.phase == "eccentric" }
        assertEquals(3, ecc.repsEvaluated)
        assertEquals(2, ecc.repsWithinTolerance, "the 2s eccentric should fail a 4s prescription")
        assertTrue(analysis.verdicts.any { it.contains("eccentric") && it.contains("fast") })
    }

    @Test
    fun `computes velocity loss across a fatiguing set`() {
        // Concentric slows from 0.8s to 2.0s across the set at fixed ROM -> mean velocity drops.
        val reps =
            listOf(0.8, 0.9, 1.1, 1.5, 2.0).map {
                SyntheticSets.RepSpec(eccS = 2.0, bottomPauseS = 1.0, conS = it, topPauseS = 1.2, romM = 0.6)
            }
        val samples = SyntheticSets.generate(reps)
        val analysis = SetAnalyzer.analyze(samples, StartPhase.ECCENTRIC)
        assertEquals(5, analysis.reps.size)
        val loss = assertNotNull(analysis.velocityLossPct)
        // Expected: (0.75 - 0.3)/0.75 = 60% velocity loss.
        assertTrue(loss in 40.0..75.0, "velocity loss $loss should reflect the slowdown")
        val meanFirst = analysis.reps.first().meanConVelMps
        val meanLast = analysis.reps.last().meanConVelMps
        assertTrue(meanFirst > meanLast, "first rep ($meanFirst) should be faster than last ($meanLast)")
    }

    @Test
    fun `velocity loss stop verdict fires when plan limit exceeded mid-set`() {
        val reps =
            listOf(0.8, 0.85, 1.6, 1.7, 1.8).map {
                SyntheticSets.RepSpec(eccS = 2.0, bottomPauseS = 1.0, conS = it, topPauseS = 1.2, romM = 0.6)
            }
        val samples = SyntheticSets.generate(reps)
        val analysis =
            SetAnalyzer.analyze(
                samples,
                StartPhase.ECCENTRIC,
                targets = SetTargets(velocityLossStopPct = 20.0),
            )
        assertTrue(analysis.verdicts.any { it.contains("Velocity-loss stop") })
    }

    @Test
    fun `concentric-first lifts segment correctly`() {
        val deadliftRep = SyntheticSets.RepSpec(eccS = 2.0, bottomPauseS = 1.5, conS = 1.2, topPauseS = 1.0, romM = 0.5)
        val samples = SyntheticSets.generate(List(3) { deadliftRep }, eccentricFirst = false)
        val analysis = SetAnalyzer.analyze(samples, StartPhase.CONCENTRIC)
        assertEquals(3, analysis.reps.size)
        for (rep in analysis.reps) {
            assertTrue(abs(rep.conS - 1.2) < 0.35, "concentric ${rep.conS}s should be ~1.2s")
            assertTrue(abs(rep.eccS - 2.0) < 0.45, "eccentric ${rep.eccS}s should be ~2s")
        }
    }

    @Test
    fun `no reps detected on a still stream`() {
        val samples = SyntheticSets.generate(emptyList(), leadInS = 5.0, leadOutS = 5.0)
        val analysis = SetAnalyzer.analyze(samples, StartPhase.ECCENTRIC)
        assertEquals(0, analysis.reps.size)
        assertTrue(analysis.verdicts.any { it.contains("No reps detected") })
    }

    @Test
    fun `csv round-trip preserves analysis`() {
        val samples = SyntheticSets.generate(List(2) { tempoRep })
        val decoded = ImuCsv.decode(ImuCsv.encode(samples))
        assertEquals(samples.size, decoded.size)
        val a = SetAnalyzer.analyze(samples, StartPhase.ECCENTRIC)
        val b = SetAnalyzer.analyze(decoded, StartPhase.ECCENTRIC)
        assertEquals(a.reps.size, b.reps.size)
        for (i in a.reps.indices) {
            assertTrue(abs(a.reps[i].meanConVelMps - b.reps[i].meanConVelMps) < 0.01)
        }
    }

    @Test
    fun `analysis is deterministic`() {
        val samples = SyntheticSets.generate(List(3) { tempoRep })
        val a = SetAnalyzer.analyze(samples, StartPhase.ECCENTRIC, loadKg = 80.0)
        val b = SetAnalyzer.analyze(samples, StartPhase.ECCENTRIC, loadKg = 80.0)
        assertEquals(a, b)
    }
}
