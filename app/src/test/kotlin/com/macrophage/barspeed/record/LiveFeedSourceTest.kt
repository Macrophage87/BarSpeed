package com.macrophage.barspeed.record

import com.macrophage.barspeed.model.RecordedSensors
import com.macrophage.barspeed.model.SensorRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
 * It is `ArmedCaptureTest`'s argument for existing -- that file is in
 * `:core:model` since #212, with the function it drives -- applied to the live
 * half of the same question. `liveFeedOf` is still in `:app`, so this file is
 * reachable only because `:app`'s test JVM is pinned to 21 and
 * [RecordedSensors] therefore loads.
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
    fun `the readout follows the partner when the armed unit has fed almost nothing`() {
        val armed =
            RecordedSensors(
                count = 2,
                expected = listOf(SensorRole.A, SensorRole.B),
                analysed = SensorRole.A,
            )
        val feed = liveFeedOf(armed, SensorRole.B, SensorRole.A, analysedFrames = 3, secondaryFrames = 400)
        assertEquals(SensorRole.B, feed.role)
        assertTrue(feed.fellBack)
        assertTrue(feed.switched)
    }

    @Test
    fun `the counts are read against the roles they belong to`() {
        val armed =
            RecordedSensors(
                count = 2,
                expected = listOf(SensorRole.A, SensorRole.B),
                analysed = SensorRole.A,
            )
        val feed = liveFeedOf(armed, SensorRole.B, SensorRole.A, analysedFrames = 400, secondaryFrames = 3)
        assertEquals(SensorRole.A, feed.role)
        assertFalse(feed.fellBack)
        assertFalse(feed.switched)
    }

    @Test
    fun `a partner that has fed too little to analyse does not take the readout`() {
        val armed =
            RecordedSensors(
                count = 2,
                expected = listOf(SensorRole.A, SensorRole.B),
                analysed = SensorRole.A,
            )
        val feed = liveFeedOf(armed, SensorRole.B, SensorRole.A, analysedFrames = 0, secondaryFrames = 7)
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
