package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.StartPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the published turnaround pause is on a reversal with NO dwell, the
 * shape issue #92 names for a machine that keeps the load on through the
 * turnaround.
 *
 * The issue's premise was that pause-based phase boundaries assume the load
 * comes off, so a dwell-free reversal has "nothing to detect". These pins say
 * what the pipeline computes instead: the segmenter pairs a movement run with
 * the next opposite one across whatever still runs lie between -- on a
 * dwell-free reversal, only the dead-band crossing -- so a dwell-free set
 * resolves every rep and publishes a turnaround near zero: a value, not an
 * absence and not a merged rep. The same lift with a
 * prescribed 1 s hold publishes a figure near that hold, so the two shapes
 * are told apart by the figure itself. The converse is NOT pinned and does
 * not hold: the zero-dwell bound is 0.15 s, so a figure near zero does not
 * show a reversal was dwell-free, and a short real dwell (#92's owner
 * estimate is 0.10-0.14 s on session 29) may publish a figure under that
 * bound too.
 *
 * Characterization of the shipped pipeline on synthetic streams; nothing here
 * was measured on a machine, and nothing here can say whether a load came off
 * a limb, which no stream in this repository records.
 */
class TurnaroundPauseTest {
    private val direction = LiftDirection(startsWith = StartPhase.ECCENTRIC, concentricUp = true)

    private fun bottomPauses(holdS: Double): List<Double?> {
        val rep = SyntheticSets.RepSpec(eccS = 2.0, bottomPauseS = holdS, conS = 1.0, topPauseS = 1.0, romM = 0.5)
        val samples = SyntheticSets.generate(List(5) { rep })
        val reps = SetAnalyzer.analyze(samples, direction).reps
        assertEquals(5, reps.size, "hold $holdS s: the five reps did not all resolve")
        return reps.map { it.bottomPauseS }
    }

    @Test
    fun `a reversal with no dwell resolves every rep and publishes a turnaround near zero`() {
        bottomPauses(0.0).forEach {
            assertTrue(it != null && it <= 0.15, "a dwell-free turnaround published $it rather than a figure near zero")
        }
    }

    @Test
    fun `a prescribed hold at the turnaround publishes a figure near the hold`() {
        bottomPauses(1.0).forEach {
            assertTrue(it != null && it in 0.8..1.4, "a 1 s hold published $it")
        }
    }
}
