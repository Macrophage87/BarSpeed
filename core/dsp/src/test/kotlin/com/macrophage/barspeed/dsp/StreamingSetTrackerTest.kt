package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.StartPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StreamingSetTrackerTest {
    @Test
    fun `live rep count matches batch analysis`() {
        val rep = SyntheticSets.RepSpec(eccS = 3.0, bottomPauseS = 1.0, conS = 1.0, topPauseS = 1.5, romM = 0.6)
        val samples = SyntheticSets.generate(List(4) { rep })
        val tracker = StreamingSetTracker(StartPhase.ECCENTRIC)
        var last = LiveSetState()
        samples.forEach { last = tracker.feed(it) }
        assertEquals(4, last.repCount)
    }

    @Test
    fun `velocity readout tracks the concentric peak`() {
        val rep = SyntheticSets.RepSpec(eccS = 2.0, bottomPauseS = 1.0, conS = 1.0, topPauseS = 2.0, romM = 0.6)
        val samples = SyntheticSets.generate(List(1) { rep })
        val tracker = StreamingSetTracker(StartPhase.ECCENTRIC)
        var maxV = 0.0
        samples.forEach { maxV = maxOf(maxV, tracker.feed(it).velocityMps) }
        // Peak should be near rom*pi/(2T) = 0.94 m/s.
        assertTrue(maxV in 0.7..1.15, "live peak velocity $maxV should be ~0.94")
    }

    @Test
    fun `still stream produces no reps and near-zero velocity`() {
        val samples = SyntheticSets.generate(emptyList(), leadInS = 6.0, leadOutS = 0.0)
        val tracker = StreamingSetTracker(StartPhase.ECCENTRIC)
        var last = LiveSetState()
        samples.forEach { last = tracker.feed(it) }
        assertEquals(0, last.repCount)
        assertTrue(kotlin.math.abs(last.velocityMps) < 0.05)
    }
}
