package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which stream feeds the in-set tracker, and which collector is allowed to
 * hand it a sample (#210).
 *
 * NEW SYMBOLS, so red-before-green is not available for the cases in this file
 * and no pretence is made that it was: nothing here failed before
 * [LiveFeedPolicy] existed, because there was nothing to call. What these pin
 * is the part of the rule that does NOT change when the differential lands --
 * a set whose armed unit is delivering, a set with no roles at all, and which
 * collector matches which feed. The cases that DO change are pushed alone, red,
 * in the commit after this one.
 *
 * The seam is why this file can exist. The decision runs inside `:app`'s sample
 * handlers, which no test on the CI path reaches.
 */
class LiveFeedPolicyTest {
    @Test
    fun `the armed role feeds the tracker while it is delivering`() {
        val feed = LiveFeedPolicy.liveFeed(SensorRole.A, SensorRole.A, listOf(SensorRole.A, SensorRole.B))
        assertEquals(SensorRole.A, feed.role)
        assertFalse(feed.fellBack)
        assertFalse(feed.switched)
    }

    @Test
    fun `nothing has been analysable yet at the start of a set`() {
        val feed = LiveFeedPolicy.liveFeed(SensorRole.A, SensorRole.A, emptyList())
        assertEquals(SensorRole.A, feed.role)
        assertFalse(feed.fellBack)
        assertFalse(feed.switched)
    }

    @Test
    fun `the readout moves to the partner when the armed unit cannot be analysed`() {
        val feed = LiveFeedPolicy.liveFeed(SensorRole.A, SensorRole.A, listOf(SensorRole.B))
        assertEquals(SensorRole.B, feed.role)
        assertTrue(feed.fellBack)
        assertTrue(feed.switched)
    }

    @Test
    fun `the move is the same in either direction`() {
        val feed = LiveFeedPolicy.liveFeed(SensorRole.B, SensorRole.B, listOf(SensorRole.A))
        assertEquals(SensorRole.A, feed.role)
        assertTrue(feed.fellBack)
        assertTrue(feed.switched)
    }

    @Test
    fun `the switch is announced on the frame that makes it and no later one`() {
        val first = LiveFeedPolicy.liveFeed(SensorRole.A, SensorRole.A, listOf(SensorRole.B))
        val next = LiveFeedPolicy.liveFeed(SensorRole.A, first.role, listOf(SensorRole.B))
        assertEquals(SensorRole.B, next.role)
        assertTrue(next.fellBack)
        assertFalse(next.switched)
    }

    @Test
    fun `the readout does not return to the armed stream once it has left it`() {
        val feed = LiveFeedPolicy.liveFeed(SensorRole.A, SensorRole.B, listOf(SensorRole.A, SensorRole.B))
        assertEquals(SensorRole.B, feed.role)
        assertTrue(feed.fellBack)
        assertFalse(feed.switched)
    }

    @Test
    fun `a set with no roles has no role to feed from`() {
        val feed = LiveFeedPolicy.liveFeed(null, null, emptyList())
        assertNull(feed.role)
        assertFalse(feed.fellBack)
        assertFalse(feed.switched)
    }

    @Test
    fun `the unroled stream feeds the tracker on a set with no roles`() {
        val feed = LiveFeed(role = null, fellBack = false, switched = false)
        assertTrue(LiveFeedPolicy.feedsTracker(feed, null))
        assertFalse(LiveFeedPolicy.feedsTracker(feed, SensorRole.A))
        assertFalse(LiveFeedPolicy.feedsTracker(feed, SensorRole.B))
    }

    @Test
    fun `only the collector whose role is fed hands the tracker a sample`() {
        val feed = LiveFeed(role = SensorRole.B, fellBack = true, switched = false)
        assertTrue(LiveFeedPolicy.feedsTracker(feed, SensorRole.B))
        assertFalse(LiveFeedPolicy.feedsTracker(feed, SensorRole.A))
        assertFalse(LiveFeedPolicy.feedsTracker(feed, null))
    }
}
