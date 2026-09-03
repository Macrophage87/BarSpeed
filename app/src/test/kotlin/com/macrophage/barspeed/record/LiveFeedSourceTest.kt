package com.macrophage.barspeed.record

import com.macrophage.barspeed.model.RecordedSensors
import com.macrophage.barspeed.model.SensorRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Which stream [liveFeedOf] says the live readout is following, and which
 * frame counts it reads to say so (#210).
 *
 * THE DECISION IS NOT HERE. `LiveFeedPolicy` in `:core:model` decides, and
 * `LiveFeedPolicyTest` pins it. What lives in `:app`, and what this file pins,
 * is the step before: turning two buffers into frame counts KEYED BY ROLE. That
 * is the pairing a policy test cannot see, and its failure mode is the quiet
 * one -- counts swapped between roles would move the readout onto the silent
 * unit and leave it there, on exactly the sets where nothing else is wrong.
 *
 * It is [ArmedCaptureTest]'s argument for existing, applied to the live half of
 * the same question, and it is reachable for the same reason: `:app`'s test JVM
 * is pinned to 21, so [RecordedSensors] loads.
 */
class LiveFeedSourceTest {
    @Test
    fun `the armed role feeds the readout while both units deliver`() {
        val armed =
            RecordedSensors(
                count = 2,
                expected = listOf(SensorRole.A, SensorRole.B),
                analysed = SensorRole.A,
            )
        val feed = liveFeedOf(armed, SensorRole.B, SensorRole.A, analysedFrames = 400, secondaryFrames = 400)
        assertEquals(SensorRole.A, feed.role)
        assertFalse(feed.fellBack)
        assertFalse(feed.switched)
    }

    @Test
    fun `a one-sensor set has no role feeding the readout`() {
        val feed = liveFeedOf(null, null, null, analysedFrames = 400, secondaryFrames = 0)
        assertNull(feed.role)
        assertFalse(feed.fellBack)
        assertFalse(feed.switched)
    }
}
