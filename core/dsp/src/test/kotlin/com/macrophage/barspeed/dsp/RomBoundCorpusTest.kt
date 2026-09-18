package com.macrophage.barspeed.dsp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * HOW MANY REPS OF EACH COMMITTED CAPTURE THE ANALYSIS CAN BOUND, measured
 * through [SetAnalyzer.analyze] itself rather than through a rebuilt pipeline.
 * Issue #291.
 *
 * `ArtefactCorpus` states each capture's provenance, geometry, load and
 * bounding instants once; `ArtefactCorpusBaselineTest`'s KDoc carries the
 * licence for using the eleven and the figures each one reproduces from its own
 * archive.
 *
 * ## The column
 *
 * At most ONE rep per set is bounded, and on nine of the eleven captures none
 * is. That is the finding rather than a side effect of it: an anchor is accepted
 * only in a genuinely quiet window, and on a working set the bar never goes
 * quiet, so the whole working window is one uncorrected integration interval
 * shared by every rep in it.
 *
 * The consequence for the lifter, which is what issue #291 is about: a
 * dispersion figure needs [RomBound.MIN_BOUNDED_REPS] bounded reps, so
 * `romSpread_pct` is withheld on every capture in this corpus instead of reading
 * 98.1 %, 96.9 % or 87.4 %. The commit that narrows it is separate from the one
 * this test arrives in.
 *
 * ## What is NOT claimed
 *
 * That the two bounded reps are correct. `RomBound`'s own KDoc states the
 * limit: the cap bounds the travel the drift correction was licensed to REMOVE,
 * never the residual an uncorrected non-linear bias leaves, and no machine in
 * this corpus but the leg-curl rail has an independently known travel to check a
 * figure against.
 */
class RomBoundCorpusTest {
    private data class Expected(val fixture: String, val reps: Int, val bounded: Int)

    private val column = listOf(
        Expected("field-ohp-3010-7rep-s42-set02", 9, 0),
        Expected("field-bench-3010-6rep-s42-set05", 5, 0),
        Expected("field-bench-3010-6rep-s42-set07", 6, 0),
        Expected("field-cablerow-3010-8rep-s42-set09", 4, 0),
        Expected("field-pullup-3010-8rep-s42-set11", 9, 0),
        Expected("field-pullup-4010-8rep-s42-set13", 9, 0),
        Expected("field-deadlift-straight-5rep-s43-set04", 9, 1),
        Expected("field-deadlift-straight-5rep-s43-set05", 7, 0),
        Expected("field-deadlift-straight-5rep-s43-set06", 7, 0),
        Expected("field-assistedpullup-3010-s37-set08", 7, 1),
        Expected("field-ohp-prepinflated-s37-set03", 11, 0),
    )

    @Test
    fun `at most one rep per capture is bounded, and on nine of eleven none is`() {
        column.forEach { e ->
            val a = ArtefactCorpus.analyse(ArtefactCorpus.cases.first { it.fixture == e.fixture })
            assertEquals(e.reps, a.reps.size, "${e.fixture} detections")
            assertEquals(
                e.bounded,
                a.reps.count { it.romBounded == true },
                "${e.fixture} reps whose displacement the analysis can bound",
            )
            // Never null on a set analysed by this code: the flag is computed
            // for every detection, so null can only mean a stored analysis.
            assertEquals(0, a.reps.count { it.romBounded == null }, "${e.fixture} reps with no answer")
        }
        assertEquals(9, column.count { it.bounded == 0 }, "captures with no bounded rep at all")
        assertEquals(2, column.count { it.bounded == 1 }, "captures with exactly one")
        assertTrue(column.none { it.bounded >= RomBound.MIN_BOUNDED_REPS }, "no capture can state a dispersion")
    }

    @Test
    fun `the rep counts and the rom_m figures are untouched by measuring the bound`() {
        // The whole point of marking rather than repairing. These are the same
        // figures RomDriftBaselineTest pins before RomBound existed.
        val seven = ArtefactCorpus.analyse(
            ArtefactCorpus.cases.first { it.fixture == "field-bench-3010-6rep-s42-set07" },
        )
        assertEquals(listOf(0.168, 0.758, 0.188, 0.124, 0.353, 1.592), seven.reps.map { it.romM }, "set 7 rom_m")
        val nine = ArtefactCorpus.analyse(
            ArtefactCorpus.cases.first { it.fixture == "field-cablerow-3010-8rep-s42-set09" },
        )
        assertEquals(listOf(1.675, 1.88, 0.142, 0.342), nine.reps.map { it.romM }, "set 9 rom_m")
    }

    @Test
    fun `the two bounded reps in the corpus are named, not left as a count`() {
        // So a reader can check the rule did what its derivation says: each of
        // these detections is the only one between two accepted anchors.
        val four = ArtefactCorpus.analyse(
            ArtefactCorpus.cases.first { it.fixture == "field-deadlift-straight-5rep-s43-set04" },
        )
        assertEquals(listOf(7), four.reps.filter { it.romBounded == true }.map { it.index }, "field-43 set 4")
        assertEquals(0.351, four.reps[7].romM, "and its rom_m, on a 61.2 kg deadlift")

        val eight = ArtefactCorpus.analyse(
            ArtefactCorpus.cases.first { it.fixture == "field-assistedpullup-3010-s37-set08" },
        )
        assertEquals(listOf(5), eight.reps.filter { it.romBounded == true }.map { it.index }, "field-37 set 8")
        assertEquals(0.2, eight.reps[5].romM, "and its rom_m, on an assisted pull-up")
    }

    @Test
    fun `field-42 set 11 has a bounded SPAN and no bounded REP, because its own Done cue excluded it`() {
        // The one place the two populations differ, stated rather than left to be
        // discovered. RomBound judges spans and is handed EVERY segmented span;
        // SetAnalyzer publishes the spans its cue and work-start bounds kept. On
        // this capture the only span alone in an inter-anchor interval is one of
        // the two that began after the set was called over, so the set publishes
        // no bounded rep.
        val case = ArtefactCorpus.cases.first { it.fixture == "field-pullup-3010-8rep-s42-set11" }
        val samples = ArtefactCorpus.load(case.fixture)
        val anchored = VelocityEstimator.estimate(samples, DspConfig(), case.direction.measuredPlane)
        val lifterFrame = anchored.mappedToLifter(case.direction.sensorToLifter)
        val spans = RepSegmenter.segment(lifterFrame, case.direction, DspConfig())
        assertEquals(12, spans.size, "segmented spans")
        assertEquals(
            listOf(11),
            RomBound.boundedFlags(spans, anchored.anchorIndices).withIndex().filter { it.value }.map { it.index },
            "bounded span indices",
        )
        val a = ArtefactCorpus.analyse(case, samples)
        assertEquals(9, a.reps.size, "published detections")
        assertEquals(2, a.detectionsAfterSetEndCue, "detections that began after the set was called over")
        assertEquals(0, a.reps.count { it.romBounded == true }, "published reps the analysis can bound")
    }
}
