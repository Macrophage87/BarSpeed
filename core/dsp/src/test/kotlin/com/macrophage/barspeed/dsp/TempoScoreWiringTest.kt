package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.StartPhase
import com.macrophage.barspeed.model.Tempo
import com.macrophage.barspeed.model.TempoScoreLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The two ends of the tempo label, held together.
 *
 * [TempoScoreLabel] lives in `:core:model` and names the phases it can report a
 * coverage gap for as bare strings; [SetAnalyzer] writes those names. Nothing
 * else pins the two spellings to each other, and the failure if they drift is
 * silent rather than loud: no phase would match, so no gap would ever be found
 * and every set would tick.
 *
 * The set here is the drive-only case -- four reps whose eight-second lowering
 * never clears the run threshold, the same construction `TempoComplianceTest`
 * uses for the absence path -- so the analysis carries a prescribed eccentric
 * that nothing graded.
 */
class TempoScoreWiringTest {
    private fun driveOnlySet() = SetAnalyzer.analyze(
        SyntheticSets.generate(
            List(4) {
                SyntheticSets.RepSpec(eccS = 8.0, bottomPauseS = 0.5, conS = 0.8, topPauseS = 1.0, romM = 0.4)
            },
            sampleRateHz = 50.0,
            seed = 1234,
            eccentricFirst = false,
        ),
        LiftDirection(startsWith = StartPhase.CONCENTRIC),
        targets = SetTargets(tempo = Tempo.parse("3010"), toleranceS = 0.5),
    )

    private fun facts(c: TempoComplianceResult) =
        c.phases.map { TempoScoreLabel.PhaseFacts(it.phase, prescribed = it.prescribedS != null, scored = it.scored) }

    @Test
    fun `the analyzer's gradeable phase names are the ones the label looks for`() {
        val c = assertNotNull(driveOnlySet().tempoCompliance)
        val movement = c.phases.map { it.phase }.filter { it in TempoScoreLabel.MOVEMENT_PHASES }
        assertEquals(TempoScoreLabel.MOVEMENT_PHASES, movement, "SetAnalyzer's phase names")
        assertEquals(TempoComplianceResult.PHASE_ECCENTRIC, TempoScoreLabel.MOVEMENT_PHASES.first())
    }

    @Test
    fun `this set really is graded on the drive alone`() {
        val c = assertNotNull(driveOnlySet().tempoCompliance)
        assertEquals(listOf("concentric"), c.phases.filter { it.scored }.map { it.phase })
        assertNotNull(c.eccentricPrescribedS, "the eccentric was prescribed")
        assertEquals(4, c.repsEvaluated)
        assertEquals(4, c.repsFullyCompliant)
        // End to end: a real analysis of a real drive-only set reaches the
        // label as one prescribed phase nothing graded. What the label then
        // says about it is TempoScoreLabelTest's subject.
        val score = assertNotNull(TempoScoreLabel.of(c.repsFullyCompliant, c.repsEvaluated, facts(c)))
        assertEquals(listOf(TempoComplianceResult.PHASE_ECCENTRIC), score.ungradedPhases)
    }

    @Test
    fun `a real drive-only set does not tick, and says why`() {
        val c = assertNotNull(driveOnlySet().tempoCompliance)
        val score = assertNotNull(TempoScoreLabel.of(c.repsFullyCompliant, c.repsEvaluated, facts(c)))
        assertEquals("Tempo 4/4", score.text)
        assertEquals(
            "Eccentric not measured this set -- the ratio covers the concentric only.",
            score.ungradedNote,
        )
    }
}
