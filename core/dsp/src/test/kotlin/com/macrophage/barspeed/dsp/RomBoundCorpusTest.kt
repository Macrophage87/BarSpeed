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
 * NOT ONE REP OF THE ELEVEN IS BOUNDED. That is the finding rather than a side
 * effect of it, and it has two halves. An anchor is accepted only in a genuinely
 * quiet window, and on a working set the bar never goes quiet, so the whole
 * working window is one uncorrected integration interval shared by every rep in
 * it. And where a rep does sit alone in an interval, the anchor that closed the
 * interval was taken by ANCHOR STARVATION, which caps nothing -- `AnchorRouteTest`
 * measures the three spans a route-blind rule admitted and the 1.0180 m, 5.0198 m
 * and 2.3153 m the correction erased across their intervals, against the 0.10 m
 * cap the rule cites.
 *
 * The consequence for the lifter, which is what issue #291 is about: a
 * dispersion figure needs [RomBound.MIN_BOUNDED_REPS] bounded reps and a mean
 * needs one, so `romSpread_pct` is withheld on every capture in this corpus
 * instead of reading 98.1 %, 96.9 % or 87.4 %, and `meanRom_m` is withheld on
 * every one of them too.
 *
 * ## What is NOT claimed
 *
 * That an unbounded rep's `rom_m` is wrong. `RomBound`'s own KDoc states the
 * limit in both directions: the cap bounds the travel the drift correction was
 * licensed to REMOVE, never the residual an uncorrected non-linear bias leaves,
 * and no machine in this corpus but the leg-curl rail has an independently known
 * travel to check a figure against.
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
        Expected("field-deadlift-straight-5rep-s43-set04", 9, 0),
        Expected("field-deadlift-straight-5rep-s43-set05", 7, 0),
        Expected("field-deadlift-straight-5rep-s43-set06", 7, 0),
        Expected("field-assistedpullup-3010-s37-set08", 7, 0),
        Expected("field-ohp-prepinflated-s37-set03", 11, 0),
    )

    @Test
    fun `no capture in the corpus publishes a bounded rep`() {
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
        assertEquals(11, column.count { it.bounded == 0 }, "captures with no bounded rep at all")
        assertTrue(column.none { it.bounded >= 1 }, "no capture can state a mean")
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
    fun `the two reps a route-blind rule called bounded are named, and neither is`() {
        // RED AT THE COMMIT THAT ADDS THIS. These are the only two reps the rule
        // ever admitted, and each sits in an interval a starvation anchor closed
        // after erasing 1.0180 m and 5.0198 m -- ten and fifty times the 0.10 m
        // the rule's own derivation cites, on reps publishing 0.351 m and
        // 0.200 m. AnchorRouteTest is the measurement.
        //
        // Each rep's own rom_m is asserted unchanged beside its flag, because
        // that is the whole terms of the trade: the SET-LEVEL claim narrows and
        // no per-rep figure moves.
        val four = ArtefactCorpus.analyse(
            ArtefactCorpus.cases.first { it.fixture == "field-deadlift-straight-5rep-s43-set04" },
        )
        assertEquals(emptyList(), four.reps.filter { it.romBounded == true }.map { it.index }, "field-43 set 4")
        assertEquals(false, four.reps[7].romBounded, "field-43 set 4 rep 7")
        assertEquals(0.351, four.reps[7].romM, "and its rom_m, on a 61.2 kg deadlift, unchanged")

        val eight = ArtefactCorpus.analyse(
            ArtefactCorpus.cases.first { it.fixture == "field-assistedpullup-3010-s37-set08" },
        )
        assertEquals(emptyList(), eight.reps.filter { it.romBounded == true }.map { it.index }, "field-37 set 8")
        assertEquals(false, eight.reps[5].romBounded, "field-37 set 8 rep 5")
        assertEquals(0.2, eight.reps[5].romM, "and its rom_m, on an assisted pull-up, unchanged")
    }

    @Test
    fun `field-42 set 11 is where the span population and the published reps differ`() {
        // The one place the two populations differ, stated rather than left to be
        // discovered. RomBound judges spans and is handed EVERY segmented span;
        // SetAnalyzer publishes the spans its cue and work-start bounds kept. On
        // this capture the only span a route-blind rule left alone in an
        // inter-anchor interval is one of the two that began after the set was
        // called over, so even that rule published no bounded rep here.
        val case = ArtefactCorpus.cases.first { it.fixture == "field-pullup-3010-8rep-s42-set11" }
        val samples = ArtefactCorpus.load(case.fixture)
        val anchored = VelocityEstimator.estimate(samples, DspConfig(), case.direction.measuredPlane)
        val lifterFrame = anchored.mappedToLifter(case.direction.sensorToLifter)
        val spans = RepSegmenter.segment(lifterFrame, case.direction, DspConfig())
        assertEquals(12, spans.size, "segmented spans")
        assertEquals(
            listOf(11),
            RomBound.boundedFlags(spans, anchored.anchorIndices, BooleanArray(anchored.anchorIndices.size) { true })
                .withIndex().filter { it.value }.map { it.index },
            "span indices a ROUTE-BLIND rule admits",
        )
        // And none once the route is read: the interval that span sits in was
        // closed by a starvation anchor, which capped nothing. AnchorRouteTest
        // measures the 2.3153 m it erased.
        assertEquals(
            emptyList(),
            RomBound.boundedFlags(spans, anchored.anchorIndices, anchored.anchorCapped)
                .withIndex().filter { it.value }.map { it.index },
            "span indices the rule admits once the route is read",
        )
        val a = ArtefactCorpus.analyse(case, samples)
        assertEquals(9, a.reps.size, "published detections")
        assertEquals(2, a.detectionsAfterSetEndCue, "detections that began after the set was called over")
        assertEquals(0, a.reps.count { it.romBounded == true }, "published reps the analysis can bound")
    }
}
