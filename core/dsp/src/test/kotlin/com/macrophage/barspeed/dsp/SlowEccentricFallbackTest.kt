package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.StartPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * The conditions [RepSegmenter]'s eccentric-first drive-alone fallback
 * requires, one constructed case each, plus the two shapes that make it fire.
 * Issue #72.
 *
 * ## The two firing shapes, and which one the corpus holds
 *
 * A drive is orphaned either because its lowering was DEMOTED -- too slow to
 * clear `startThresholdMps` -- or because the lowering qualified and was
 * CONSUMED by a pair the `minRomM` floor then discarded. Both reach the
 * fallback. Only the second is in the committed corpus, on all four captures
 * the fallback moves; the first is constructed here and nowhere else, and
 * `RepSegmenter` records that a draft of its own note had the two the wrong
 * way round.
 *
 * ## Why these are constructed and not captured
 *
 * The committed field corpus exercises the fallback FIRING -- three metronome
 * windows it fills are pinned in `BatchCueCoverageTest` -- and does not
 * exercise any of its guards. Every one of the guards was mutation-tested
 * against the whole corpus at the commit that added it and three survived: the
 * drive's own `minRomM` check, the size of the travel the lowering must show,
 * and whether the resulting rep is marked as having an eccentric at all.
 * A guard nothing can fail reads as coverage, so the cases below are built to
 * fail it.
 *
 * These are VELOCITY SERIES, not captures. Nothing here says what a sensor
 * does; each is a piecewise-constant velocity trace chosen so exactly one
 * threshold decides the outcome, and the numbers are stated against
 * `DspConfig`'s defaults so a change to those defaults reds these rather than
 * silently re-aiming them.
 *
 * Against `DspConfig()` and a 1:1 lifter mapping the five that matter are
 * `pauseBandMps` 0.03, `startThresholdMps` 0.10, `minPhaseS` 0.20, `minRomM`
 * 0.10 and `maxRunDisplacementM` 2.0.
 */
class SlowEccentricFallbackTest {
    private val hz = 100.0

    private val direction = LiftDirection(startsWith = StartPhase.ECCENTRIC, concentricUp = true)

    /** A series of constant-velocity segments, each `(velocityMps, seconds)`. */
    private fun series(vararg segments: Pair<Double, Double>): VelocitySeries {
        val v = mutableListOf<Double>()
        segments.forEach { (velocity, seconds) -> repeat((seconds * hz).toInt()) { v += velocity } }
        val n = v.size
        return VelocitySeries(DoubleArray(n) { it / hz }, DoubleArray(n), v.toDoubleArray(), hz)
    }

    private fun spans(s: VelocitySeries) = RepSegmenter.segment(s, direction, DspConfig())

    /**
     * A lowering slow enough to be demoted: 0.05 m/s is over `pauseBandMps` so
     * it forms a run, and under `startThresholdMps` so `classifyRuns` demotes
     * it to stillness. Over 4 s it travels 0.1995 m, which clears `minRomM`.
     */
    private val slowLowering = -0.05 to 4.0

    /** A drive that qualifies on every gate: 0.40 m/s for 1 s, 0.396 m. */
    private val goodDrive = 0.40 to 1.0

    @Test
    fun `a drive whose lowering was measured but too slow to be a phase is a rep`() {
        // The case the fallback exists for, and the shape RepSegmenter's own
        // KNOWN LIMITATION note describes: the eccentric ramps through the
        // dead band slowly and never reaches the phase threshold, so before
        // this fallback there was nothing for the drive to pair with and the
        // whole rep was dropped.
        val reps = spans(series(0.0 to 1.0, slowLowering, 0.0 to 0.5, goodDrive, 0.0 to 1.0))
        assertEquals(1, reps.size, "reps resolved from one slow lowering and one drive")
        // The eccentric is UNKNOWN, not zero. The span is a placeholder and
        // `hasEccentric` is what tells SetAnalyzer to publish null rather than
        // a duration the lifter never took -- the same contract
        // pairConcentricFirst has always used for a drive-only rep. Without
        // this the rep would report a phase that was never measured as though
        // it had been.
        assertFalse(reps.single().hasEccentric, "a drive-only rep claims an eccentric")
        assertNull(reps.single().turnaroundPauseS, "a turnaround between one phase and nothing")
        assertEquals(reps.single().conEndIdx, reps.single().eccStartIdx, "the placeholder eccentric span")
        assertEquals(reps.single().conEndIdx, reps.single().eccEndIdx, "the placeholder eccentric span")
    }

    @Test
    fun `a drive whose lowering was spent on an under-floor fragment is a rep`() {
        // THE SHAPE THE CORPUS ACTUALLY HOLDS, and the reason the case above
        // is not the whole story. On all four captures the fallback moves, the
        // lowering QUALIFIED as a phase; what orphaned the drive was the pair
        // that consumed it, whose drive side was a fragment under `minRomM`
        // (0.096, 0.046, 0.025 and 0.064 m). The lifter's real drive is the
        // next run, and by then the lowering is spent.
        //
        // -0.30 m/s for 1 s travels 0.297 m and clears every phase gate, so
        // unlike `slowLowering` it is in the CLASSIFIED list. 0.15 m/s for
        // 0.4 s clears `startThresholdMps` and `minPhaseS` and travels
        // 0.0585 m, so it pairs with that lowering and the pair is discarded
        // by the floor. Then the real drive.
        val reps = spans(
            series(0.0 to 1.0, -0.30 to 1.0, 0.0 to 0.3, 0.15 to 0.4, 0.0 to 0.3, goodDrive, 0.0 to 1.0),
        )
        assertEquals(1, reps.size, "reps from a qualifying lowering, an under-floor fragment and a drive")
        assertFalse(reps.single().hasEccentric, "the eccentric was spent on the discarded pair, not on this rep")
        assertNull(reps.single().turnaroundPauseS, "a turnaround between one phase and nothing")
    }

    @Test
    fun `a drive with no lowering at all before it is not a rep`() {
        // The walkout and the re-rack, which is what requiring a pair bought
        // and what the fallback must not give back. One movement, no return
        // before it, nothing counted.
        assertEquals(0, spans(series(0.0 to 1.0, goodDrive, 0.0 to 1.0)).size, "reps from a lone drive")
    }

    @Test
    fun `a lowering that travelled under minRomM does not license a drive`() {
        // 0.05 m/s for 1.2 s is 0.0595 m -- a real movement of the right sign
        // and less than half a rep of travel. The bar dipped; the lifter did
        // not lower it. Sits deliberately between minRomM and half of it, so
        // relaxing the floor by a factor of two admits it.
        val reps = spans(series(0.0 to 1.0, -0.05 to 1.2, 0.0 to 0.5, goodDrive, 0.0 to 1.0))
        assertEquals(0, reps.size, "reps from a dip and a drive")
    }

    @Test
    fun `a drive that travelled under minRomM is not a rep however it was lowered`() {
        // 0.12 m/s for 0.6 s clears startThresholdMps and minPhaseS and
        // travels 0.0708 m. The floor applies to a drive-only rep exactly as
        // it applies to a paired one; nothing about having no eccentric
        // exempts it.
        val reps = spans(series(0.0 to 1.0, slowLowering, 0.0 to 0.5, 0.12 to 0.6, 0.0 to 1.0))
        assertEquals(0, reps.size, "reps from a full lowering and a stunted drive")
    }

    @Test
    fun `one lowering licenses one drive, not every drive after it`() {
        // The bound that makes the gate mean "the lifter lowered THIS rep".
        // Two drives, one lowering, and the second drive has nothing of its
        // own behind it -- if the search ran from the start of the series
        // instead of from the end of the last counted rep, the first
        // lowering would license both and a re-rack after a set would count.
        val reps = spans(
            series(0.0 to 1.0, slowLowering, 0.0 to 0.5, goodDrive, 0.0 to 1.0, goodDrive, 0.0 to 1.0),
        )
        assertEquals(1, reps.size, "reps from one lowering and two drives")
    }
}
