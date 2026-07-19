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

    @Test
    fun `live per-rep mean velocities approximate batch analysis`() {
        val rep = SyntheticSets.RepSpec(eccS = 2.0, bottomPauseS = 1.0, conS = 1.0, topPauseS = 1.5, romM = 0.6)
        val samples = SyntheticSets.generate(List(3) { rep })
        val tracker = StreamingSetTracker(com.macrophage.barspeed.model.StartPhase.ECCENTRIC)
        var last = LiveSetState()
        samples.forEach { last = tracker.feed(it) }
        assertEquals(3, last.repMeanVelocities.size)
        // Half-sine mean = 2/pi * peak = 0.60 m/s for rom 0.6 over 1 s.
        last.repMeanVelocities.forEach { mean ->
            assertTrue(mean in 0.45..0.75, "live rep mean $mean should be ~0.6")
        }
        // Peaks (half-sine peak ≈ 0.94 m/s) must exceed the means.
        assertEquals(3, last.repPeakVelocities.size)
        last.repPeakVelocities.forEachIndexed { i, peak ->
            assertTrue(peak in 0.7..1.2, "live rep peak $peak should be ~0.94")
            assertTrue(peak > last.repMeanVelocities[i], "peak should exceed mean")
        }
    }
}
