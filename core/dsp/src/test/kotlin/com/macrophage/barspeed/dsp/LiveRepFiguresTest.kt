package com.macrophage.barspeed.dsp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [LiveRepFigures]: one bar per rep the count stands at (dsp-core F1). The
 * field figures are in `DeadliftLiveCountFieldTest`; these are the rules.
 */
class LiveRepFiguresTest {
    private fun run(startS: Double, endS: Double, mean: Double) =
        StreamingSetTracker.DriveRun(startS, endS, mean, mean * 2)

    private fun call(count: Int, startS: Double, endS: Double) =
        RepCall.Speak(count, 0L, DriveWindow(startS, endS))

    @Test
    fun `a correction, a skipped total and a call with no drive are each a bar with no figure`() {
        val bars = LiveRepFigures()
        bars.onCall(RepCall.Speak(1, 0L), emptyList())
        bars.onCorrection()
        bars.onCall(RepCall.Speak(3, 0L), emptyList())
        assertEquals(listOf<RepFigure?>(null, null, null, null), bars.figures)
    }

    @Test
    fun `a rebuild drops the sensor's bars and keeps the lifter's`() {
        val bars = LiveRepFigures()
        bars.onCall(call(1, 1.0, 1.5), listOf(run(1.1, 1.8, 0.5)))
        bars.onCorrection()
        bars.onRebuild()
        assertEquals(listOf<RepFigure?>(null), bars.figures)
        bars.clear()
        assertEquals(emptyList(), bars.figures)
    }

    @Test
    fun `a call takes the run its drive overlaps most`() {
        val bars = LiveRepFigures()
        val runs = listOf(run(0.2, 1.05, 0.3), run(1.1, 1.8, 0.5), run(3.0, 3.6, 0.9))
        bars.onCall(call(1, 1.0, 1.5), runs)
        assertEquals(listOf<RepFigure?>(RepFigure(0.5, 1.0)), bars.figures)
    }

    @Test
    fun `a run gives at most one bar, and a run before the last one claimed gives none`() {
        val bars = LiveRepFigures()
        val runs = listOf(run(1.1, 1.8, 0.5))
        bars.onCall(call(1, 1.0, 1.5), runs)
        bars.onCall(call(2, 1.2, 1.6), runs)
        assertNull(bars.figures[1], "the second call over the same run")
        bars.onCall(call(3, 0.0, 1.0), listOf(run(0.1, 0.9, 0.4)) + runs)
        assertNull(bars.figures[2], "a run older than the one already claimed")
    }

    @Test
    fun `a drive no run overlaps is a bar with no figure`() {
        val bars = LiveRepFigures()
        bars.onCall(call(1, 5.0, 5.5), listOf(run(1.1, 1.8, 0.5)))
        assertEquals(listOf<RepFigure?>(null), bars.figures)
    }
}
