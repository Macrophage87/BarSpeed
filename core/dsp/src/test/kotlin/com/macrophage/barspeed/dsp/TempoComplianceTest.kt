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
        // RED until the fix. Three of the seven reps resolved an eccentric,
        // and their drives averaged 1.92 s against 1.41 s across all seven, so
        // dividing the three eccentrics by the seven concentrics reports 1.99
        // for a contrast that measures 1.46 on the reps it was taken from --
        // a 36% overstatement, on this capture. The sign is not general: the
        // three cable captures in FieldDataRegressionTest understate.
        assertEquals(1.46, c.actualEccConRatio, "measured ecc:con contrast")
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
        // phases. Case B measured none, so it may advertise only the drive.
        assertEquals(listOf("eccentric", "concentric"), scoredPhases(assertNotNull(fixtureA().tempoCompliance)))
        assertEquals(listOf("concentric"), scoredPhases(assertNotNull(caseB().tempoCompliance)))
    }

    @Test
    fun `a drive-only set is graded on the drives it did measure`() {
        val c = assertNotNull(caseB().tempoCompliance)
        // The filed defect in its pure form. Every rep was driven on tempo and
        // the lifter is told 0 of 4, because a phase the sensor never resolved
        // is scored identically to one the lifter got wrong.
        assertEquals(4, c.repsEvaluated, "reps with something to grade")
        assertEquals(4, c.repsFullyCompliant, "reps on tempo on every phase that resolved")
    }

    @Test
    fun `a phase with no measurements is not advertised as scored`() {
        val c = assertNotNull(caseB().tempoCompliance)
        // scoredPhases is the export's only statement of what the ratio covers,
        // and the shipped coaching prompt tells the model to read it. Listing a
        // phase that was never measured makes 4 of 4 look like a graded
        // eccentric.
        assertEquals(false, phase(c, "eccentric").scored, "eccentric measured nothing")
        assertEquals(true, phase(c, "concentric").scored, "concentric measured four reps")
    }

    @Test
    fun `a mixed set counts every rep that resolved a scored phase`() {
        val c = assertNotNull(fixtureA().tempoCompliance)
        // Reps 0, 1, 5 and 6 have no eccentric but were driven within tolerance,
        // so they are graded on the concentric alone. Of the three that resolved
        // both, rep 2 misses the concentric only, rep 3 the eccentric only, and
        // rep 4 misses both -- which is what reconciles 1 of 3 eccentrics with
        // 5 of 7 concentrics and 4 of 7 reps overall. Dropping the four
        // unmeasured reps instead would report 0 of 3 and discard real
        // evidence.
        assertEquals(7, c.repsEvaluated, "every rep resolved at least one scored phase")
        assertEquals(4, c.repsFullyCompliant, "four reps were in tolerance on all they resolved")
    }

    @Test
    fun `a set with no gradeable phase at all reports no denominator`() {
        // An explosive up stroke leaves the eccentric as the only scored phase.
        // Case B never resolves one, so nothing is gradeable and the honest
        // denominator is zero -- not four. Rendering that zero is the app's
        // problem, and RecordScreen currently draws 0/0 as a green tick.
        val c = assertNotNull(caseB("30X0").tempoCompliance)
        assertEquals(0, c.repsEvaluated, "nothing was gradeable")
        assertEquals(0, c.repsFullyCompliant, "nothing was graded")
        assertEquals(emptyList(), scoredPhases(c), "no phase was both prescribed and measured")
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

    @Test
    fun `the eccentric caption names the rep's place in the set`() {
        // RED until the fix. Fixture A in the words the lifter reads on the
        // rest screen. The rep described is the FIFTH of seven; three reps
        // resolved an eccentric and it is the third of those, so today the
        // filtered index names it "Rep 3" -- a rep whose eccentric was 2.87 s,
        // near enough the 3 s target, and which the card is now blaming.
        //
        // The suffix goes with it for the same reason. Third of three measured
        // is last of three measured, so today a set with two whole reps after
        // it is called fatigued.
        assertEquals(
            "Rep 5 eccentric 1.0 s — 2.0 s too fast.",
            CoachingRules.eccentricTempoInsight(fixtureA().reps, 3.0, 0.5),
        )
    }

    @Test
    fun `a drive-only set's caption says the eccentric was not measured`() {
        // Case B: nothing to name, and the branch must not say "All reps on
        // tempo" over an empty chart. Green before and after the ordinal fix;
        // it guards the absence path against being collapsed into the others.
        assertEquals(
            "Eccentric not measured this set.",
            CoachingRules.eccentricTempoInsight(caseB().reps, 3.0, 0.5),
        )
    }
}
