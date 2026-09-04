package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.SessionExport
import com.macrophage.barspeed.model.StartPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The two fields [SetAnalysis] carries for issue #125, and the vocabulary
 * mirror between this module and `:core:model`.
 *
 * These are GREEN pins on symbols the wiring commit introduced, not
 * differentials: the fields do not exist before it, so a test naming them
 * cannot be written earlier and cannot be shown failing. What was shown
 * failing is the rep list itself, in `ArtefactRefusalWiringTest` and
 * `RepRefusalCorpusTest`.
 *
 * Read off the ANALYSIS rather than recomputed from its rep list: the list
 * the analyzer returns is the list AFTER the refusal, so asking the rule
 * about it again answers "nothing left to refuse" on every capture, including
 * the one that refused something. The count the analysis carries is the only
 * record of what was removed, which is the whole reason it is published.
 */
class RefusedDetectionAnalysisTest {
    private fun load(f: String): List<ImuSample> =
        ImuCsv.decode(javaClass.getResourceAsStream("/$f.csv")!!.readBytes().decodeToString())

    private val con = LiftDirection(startsWith = StartPhase.CONCENTRIC)
    private val ecc = LiftDirection(startsWith = StartPhase.ECCENTRIC)

    private fun analyse(f: String, d: LiftDirection, kg: Double) =
        SetAnalyzer.analyze(load(f), d, kg, SetTargets(), DspConfig(), emptyList())

    /**
     * The vocabulary is owned by [RepRefusal] and mirrored in `:core:model`,
     * which cannot see this module. This is the only place both sides are
     * visible at once, so it is the only place the mirror can be checked --
     * the same arrangement `VelocityLossTest` and `BlankAnalysisReasonTest`
     * use.
     */
    @Test
    fun `the refusal words are the ones the export publishes`() {
        assertEquals(SessionExport.VALID_REFUSED_DETECTION_REASONS, RepRefusal.REASONS)
    }

    @Test
    fun `the one capture that refuses a detection reports the count and the word`() {
        val a = analyse("field-assistedpullup-3010-s37-set10", con, 23.443564147942737)
        assertEquals(4, a.reps.size, "detections published")
        assertEquals(1, a.refusedDetections)
        assertEquals(RepRefusal.UNPAIRED_RANGE_OUTLIER, a.refusedDetectionReason)
    }

    @Test
    fun `a capture the bound ran on and refused nothing answers zero and no word`() {
        val a = analyse("field-assistedpullup-3010-s37-set08", con, 23.443564147942737)
        assertEquals(0, a.refusedDetections, "a bound ran and refused nothing")
        assertNull(a.refusedDetectionReason)
    }

    /**
     * Absence is a third state and stays one. These four captures resolve
     * fewer than [RepRefusal.MIN_DETECTIONS] detections, so no median of
     * others exists to derive a bound from, and the count is NULL rather than
     * 0. Named individually so the two absences cannot quietly merge.
     */
    @Test
    fun `the captures with no bound to derive answer null rather than zero`() {
        assertNull(analyse("field-still-0rep", ecc, 20.4).refusedDetections, "zero detections")
        assertNull(analyse("field-ropedeadhang-hold20-s37-set11", ecc, 43.86).refusedDetections, "a twenty-second hold")
        assertNull(analyse("field-backsquat-10hz-set5", ecc, 60.0).refusedDetections, "one detection")
        assertNull(analyse("field-seated-ohp-2rep", con, 20.4).refusedDetections, "three detections")
    }
}
