package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.StartPhase
import com.macrophage.barspeed.model.Tempo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Tempo compliance on sets where a phase was never measured.
 *
 * Two inputs recur. FIXTURE A is the committed 100 Hz overhead-press capture:
 * seven segmented reps of which four have no measurable eccentric, so it
 * exercises the mixed case. CASE B is a synthetic concentric-first set whose
 * five-second lowering never resolves at all, so every rep is drive-only.
 *
 * The per-phase half of [SetAnalyzer.complianceFor] already drops unmeasured
 * reps correctly. These pins hold it still while the set-level denominator
 * beside it is corrected.
 */
class TempoComplianceTest {
    private fun load(name: String) =
        ImuCsv.decode(javaClass.getResourceAsStream("/$name")!!.readBytes().decodeToString())

    private val conFirst = LiftDirection(startsWith = StartPhase.CONCENTRIC)

    /** Fixture A: 8 real presses, bursty arrivals, 4 of 7 segmented reps have no eccentric. */
    private fun fixtureA(tempo: String = "3010") = SetAnalyzer.analyze(
        load("field-ohp-100hz-bursty.csv"),
        conFirst,
        loadKg = 29.5,
        targets = SetTargets(tempo = Tempo.parse(tempo), toleranceS = 0.5),
    )

    /**
     * Case B: four drive-only reps. The 5 s lowering over 0.4 m is not rejected
     * at the peak-velocity test -- its peak is 0.4 * PI / 10 = 0.126 m/s against
     * a 0.10 m/s run threshold -- it fails to resolve as a run at all and is
     * absorbed into the bottom pause, which the drive-only pin below asserts so
     * that the figure is checked rather than merely claimed.
     */
    private fun caseB(tempo: String = "3010") = SetAnalyzer.analyze(
        SyntheticSets.generate(
            List(4) {
                SyntheticSets.RepSpec(eccS = 5.0, bottomPauseS = 0.5, conS = 0.8, topPauseS = 1.0, romM = 0.4)
            },
            sampleRateHz = 50.0,
            seed = 1234,
            eccentricFirst = false,
        ),
        conFirst,
        targets = SetTargets(tempo = Tempo.parse(tempo), toleranceS = 0.5),
    )

    private fun phase(c: TempoComplianceResult, name: String) = c.phases.first { it.phase == name }

    private fun scoredPhases(c: TempoComplianceResult) = c.phases.filter { it.scored }.map { it.phase }

    @Test
    fun `per-phase compliance already excludes unmeasured reps`() {
        val c = assertNotNull(fixtureA().tempoCompliance)
        // Three of seven reps resolved an eccentric; one of those three was in
        // tolerance. Both figures come from the measured reps only, and neither
        // may move when the set-level denominator is corrected.
        assertEquals(3, phase(c, "eccentric").repsEvaluated, "eccentric denominator")
        assertEquals(1, phase(c, "eccentric").repsWithinTolerance, "eccentric numerator")
        assertEquals(7, phase(c, "concentric").repsEvaluated, "concentric denominator")
        assertEquals(5, phase(c, "concentric").repsWithinTolerance, "concentric numerator")
        assertEquals(1.99, c.actualEccConRatio, "measured ecc:con contrast")
    }

    @Test
    fun `coaching verdicts are unaffected by the set-level denominator`() {
        // The verdict loop reads per-phase counts only, so this whole list must
        // survive byte-identical. The velocity-loss line shares the list and is
        // pinned with them; a size assertion alone would miss a dropped entry.
        assertEquals(
            listOf(
                "High velocity loss (79.4%) — significant fatigue this set.",
                "Tempo (eccentric): 1/3 reps on tempo; worst was 1.98 s too fast (target 3.00 s).",
                "Tempo (concentric): 5/7 reps on tempo; worst was 1.53 s too slow (target 1.00 s).",
            ),
            fixtureA().verdicts,
        )
    }

    @Test
    fun `a drive-only set still grades its concentric and says nothing`() {
        val analysis = caseB()
        val c = assertNotNull(analysis.tempoCompliance)
        assertEquals(4, analysis.reps.size, "segmented reps")
        assertEquals(4, analysis.reps.count { it.eccS == null }, "reps with no measurable eccentric")
        assertEquals(4, phase(c, "concentric").repsEvaluated, "every drive was measured")
        assertEquals(4, phase(c, "concentric").repsWithinTolerance, "every drive was on tempo")
        // Where the unresolved lowering ended up: absorbed into the bottom pause
        // rather than discarded, which is why the eccentric reads as absent and
        // not as short. Pinned so the figure in the KDoc above is checked.
        assertEquals(6.9, phase(c, "bottomPause").actualMeanS, "the lowering was absorbed into the pause")
        // Nothing is said about the eccentric, because the verdict loop is
        // guarded on repsEvaluated > 0. The set-level ratio is the only place
        // this set is described, which is why getting it wrong is silent.
        assertEquals(emptyList(), analysis.verdicts, "no verdict explains the set-level ratio")
    }

    @Test
    fun `scoredPhases is the reachable proxy for what the exporter publishes`() {
        // Exporters.kt evaluates exactly this expression to fill the export's
        // scoredPhases; :core:data has no test source set, so this is where it
        // can be pinned. Fixture A measured three eccentrics and must keep both
        // phases. Case B measured none, and today still advertises both.
        assertEquals(listOf("eccentric", "concentric"), scoredPhases(assertNotNull(fixtureA().tempoCompliance)))
        assertEquals(listOf("eccentric", "concentric"), scoredPhases(assertNotNull(caseB().tempoCompliance)))
    }

    @Test
    fun `a set that segmented no reps also reports no denominator`() {
        // One of the two routes to a zero denominator, and the one that needs no
        // unusual prescription: analyze() builds a compliance object whenever a
        // tempo was prescribed, reps or none, so a set the segmenter found
        // nothing in still produces 0 of 0. Green before and after the
        // denominator fix -- this characterises the input the screens must
        // refuse to draw, not a change in the analyzer.
        val c = SetAnalyzer.complianceFor(Tempo.parse("3010"), 0.5, emptyList())
        assertEquals(0, c.repsEvaluated, "no reps to grade")
        assertEquals(0, c.repsFullyCompliant, "nothing graded")
    }
}
