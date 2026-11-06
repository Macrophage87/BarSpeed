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
    fun `a drive no run overlaps is a bar with no figure`() {
        val bars = LiveRepFigures()
        bars.onCall(call(1, 5.0, 5.5), listOf(run(1.1, 1.8, 0.5)))
        assertEquals(listOf<RepFigure?>(null), bars.figures)
    }
}
