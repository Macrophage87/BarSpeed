package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.RepAnalysisPolicy
import com.macrophage.barspeed.model.SensorCapturePolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A carry counted in reps says why it has no figures (lane L5's follow-up to
 * #63, export 1.26).
 *
 * A carry prescribed in reps is kept off the segmenter by
 * [RepAnalysisPolicy.segmentsReps]: its stream is written with the row and no
 * velocity, range or power is published for it. Before this its stored
 * analysis carried no [NoRepsReason], so its export was `reps: []` beside an
 * empty `summary` -- the shape of a set nobody measured, which is exactly the
 * collapse #138 introduced `noRepsReason` to undo. The difference is that this
 * set WAS measured; the app chose not to segment it.
 *
 * THE BOUNDARY IS THE STREAM, not the kind. A carry whose stream is too short
 * to analyse at all -- fewer than [SensorCapturePolicy.MIN_ANALYSABLE_FRAMES],
 * the line `RecordViewModel` draws for every other set -- would have had
 * nothing segmented whatever its kind, and it publishes what a sensorless set
 * publishes: no reason. The word names which gate emptied the list, and for
 * that set the carry gate is not the one that did.
 *
 * DIFFERENTIAL. The first method is red before the fix: the analysis carries
 * no reason at all. The rest are green on both sides and pin what must not
 * move with it.
 */
class WithheldCarryReasonTest {
    @Test
    fun `a carry whose stream could have been segmented says the carry gate emptied it`() {
        listOf(SensorCapturePolicy.MIN_ANALYSABLE_FRAMES, 4_000).forEach { n ->
            assertEquals(
                "carryNotSegmented",
                SetAnalyzer.withheldCarry(n).noRepsReason?.wireName,
                "a carry with $n samples publishes no word for why it has no reps",
            )
        }
    }

    @Test
    fun `a carry with too short a stream to analyse says nothing, as a sensorless set does`() {
        listOf(0, 1, SensorCapturePolicy.MIN_ANALYSABLE_FRAMES - 1).forEach { n ->
            assertNull(
                SetAnalyzer.withheldCarry(n).noRepsReason,
                "a carry with $n samples names a gate that was not what emptied it",
            )
        }
    }

    @Test
    fun `the withheld analysis still publishes no figures and keeps its note`() {
        listOf(0, SensorCapturePolicy.MIN_ANALYSABLE_FRAMES).forEach { n ->
            val analysis = SetAnalyzer.withheldCarry(n)
            assertTrue(analysis.reps.isEmpty(), "a withheld carry gained reps")
            assertNull(analysis.velocityLossPct, "a withheld carry gained a velocity loss")
            assertNull(analysis.tempoCompliance, "a withheld carry gained a tempo score")
            assertEquals(listOf(RepAnalysisPolicy.WITHHELD_NOTE), analysis.verdicts, "the withheld note moved")
        }
    }
}
