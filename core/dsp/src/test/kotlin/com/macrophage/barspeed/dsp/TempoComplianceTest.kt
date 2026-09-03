package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.StartPhase
import com.macrophage.barspeed.model.Tempo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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
     * Case B: four drive-only reps, the fixture the absence path is guarded on.
     *
     * The lowering was 5 s over 0.4 m and is now 8 s over the same 0.4 m. That
     * is not a fixture edited to keep a test green, and the difference is the
     * point: a 5 s lowering has a half-sine peak of 0.4 * PI / 10 = 0.126 m/s,
     * over the 0.10 m/s floor the anchor rule now protects, so it RESOLVES and
     * this set stopped being drive-only at all. AnchorAcceptanceTest pins that
     * recovery on the old timing, as the evidence it is real. At 8 s the peak is
     * 0.079 m/s, under the floor and under the run threshold, so the lowering is
     * unmeasurable for a reason the anchor rule does not touch -- which is what
     * a guard for the absence path needs.
     *
     * Every figure this file asserts about case B is identical before and after
     * the anchor change at 8 s. The absence path is still guarded by a fixture
     * that reaches it, and is no longer guarded by one that reached it only
     * because a real lowering was being erased.
     */
    private fun caseB(tempo: String = "3010") = SetAnalyzer.analyze(
        SyntheticSets.generate(
            List(4) {
                SyntheticSets.RepSpec(eccS = 8.0, bottomPauseS = 0.5, conS = 0.8, topPauseS = 1.0, romM = 0.4)
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
        // Six of ten reps resolve an eccentric and none of the six is in
        // tolerance. Both figures come from the measured reps only.
        //
        // The denominators grew twice for the same reason and neither time was
        // the lift changing: 3 of 7 became 4 of 8 when the anchor accept rule
        // moved, and 4 of 8 became 6 of 10 with issue #94's runaway
        // correction. Each time the set is graded on more of itself. The
        // numerators do not move at all -- 0 eccentrics in tolerance either
        // way, 5 concentrics either way -- so what a reader sees change is the
        // fraction, 0/4 to 0/6 and 5/8 to 5/10, on a set where the same five
        // drives were on tempo throughout. Nobody timed those phases, so no
        // reading here is validated -- see issue #47.
        assertEquals(6, phase(c, "eccentric").repsEvaluated, "eccentric denominator")
        assertEquals(0, phase(c, "eccentric").repsWithinTolerance, "eccentric numerator")
        assertEquals(10, phase(c, "concentric").repsEvaluated, "concentric denominator")
        assertEquals(5, phase(c, "concentric").repsWithinTolerance, "concentric numerator")
        // Both means are taken over the reps that resolved an eccentric, and
        // that is now six of ten. The pairing is unchanged; what moved is
        // which reps there are to pair.
        assertEquals(1.08, c.actualEccConRatio, "measured ecc:con contrast")
    }

    @Test
    fun `coaching verdicts are unaffected by the set-level denominator`() {
        // The verdict loop reads per-phase counts only, so this whole list must
        // survive byte-identical. The velocity-loss line shares the list and is
        // pinned with them; a size assertion alone would miss a dropped entry.
        assertEquals(
            listOf(
                "High velocity loss (79.1%) — significant fatigue this set.",
                "Tempo (eccentric): 0/6 reps on tempo; worst was 2.01 s too slow (target 3.00 s).",
                "Tempo (concentric): 5/10 reps on tempo; worst was 2.96 s too slow (target 1.00 s).",
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
        // Where the unresolved lowering used to end up: this asserted a mean
        // bottomPause of 9.9 s and called it "absorbed into the pause". It was
        // not a pause. These reps resolve ONE phase each, so they contain no
        // turnaround at all, and 9.9 s was the mean interval from the end of
        // each drive to the start of the next one -- the lowering plus whatever
        // rest followed it, measured outside the rep. Issue #93. Both pause
        // phases now resolve nothing on this set and say so.
        assertNull(phase(c, "bottomPause").actualMeanS, "a drive-only rep contains no turnaround")
        assertEquals(0, phase(c, "bottomPause").repsEvaluated, "and none was evaluated")
        assertNull(phase(c, "topPause").actualMeanS, "nor at the other end")
        // Nothing is said about the eccentric, because the verdict loop is
        // guarded on repsEvaluated > 0. The set-level ratio is the only place
        // this set is described, which is why getting it wrong is silent.
        assertEquals(emptyList(), analysis.verdicts, "no verdict explains the set-level ratio")
    }

    @Test
    fun `scoredPhases is the reachable proxy for what the exporter publishes`() {
        // Exporters.kt evaluates exactly this expression to fill the export's
        // scoredPhases. :core:data has its own tests and SessionExporterTest
        // pins the exporter's copy; this pins the same rule one module
        // upstream, against the DSP result the exporter is handed. Fixture A
        // measured three eccentrics and must keep both
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
        // Four of the ten reps have no eccentric but were driven within
        // tolerance, so they are graded on the concentric alone; the other six
        // resolved both and none was in tolerance on the eccentric. That
        // reconciles 0 of 6 eccentrics with 5 of 10 concentrics and 4 of 10
        // reps overall. Dropping the four unmeasured reps instead would report
        // 0 of 6 and discard real evidence.
        assertEquals(10, c.repsEvaluated, "every rep resolved at least one scored phase")
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
        // Fixture A in the words the lifter reads on the rest screen. The rep
        // named is the FOURTH of eight, by its position in the set and not by
        // its position in the filtered list of measured eccentrics -- which is
        // what this pin exists for, and is unchanged.
        //
        // What changed is which rep is worst, twice, and neither time was it
        // the eccentric moving. The 5.01 s eccentric that beats the 1.0 s
        // which used to win was rep 4 of 8 and is rep 6 of 10 since issue
        // #94's runaway correction added two detections ahead of it. The
        // sentence the lifter reads names a different rep for the same
        // measurement. No suffix, because rep 6 of 10 is not the last rep
        // performed. Whether a 5.01 s eccentric is what the lifter did is
        // established by nothing here.
        assertEquals(
            "Rep 6 eccentric 5.0 s — 2.0 s too slow.",
            CoachingRules.eccentricTempoInsight(fixtureA().reps, 3.0, 0.5),
        )
    }

    @Test
    fun `a set that measured no eccentric publishes no ratio at all`() {
        // Found by mutation testing, not by reading. Deleting the empty-
        // population guard from the paired ratio left all 505 tests green
        // while turning this null into 0.0: `average()` over no reps is NaN,
        // `NaN <= 0.0` is false so the divide guard passes it through, and
        // `Math.round(NaN)` is 0. The absence would reach the export as a
        // contrast of zero, which reads as a lifter who dropped the load as
        // fast as they lifted it -- the one reading this field must never
        // produce, and the reason the guard is there rather than tidy.
        //
        // Two routes to it, both pinned: a set whose reps all resolved on the
        // drive alone, and a set the segmenter found nothing in.
        assertNull(
            assertNotNull(caseB().tempoCompliance).actualEccConRatio,
            "drive-only set: no eccentric was measured, so there is no contrast",
        )
        assertNull(
            SetAnalyzer.complianceFor(Tempo.parse("3010"), 0.5, emptyList()).actualEccConRatio,
            "no reps at all",
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
