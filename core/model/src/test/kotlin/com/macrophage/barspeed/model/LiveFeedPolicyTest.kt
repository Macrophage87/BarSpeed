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
 * THE LAST CASE IN THE FILE EXISTS BECAUSE A MUTATION SURVIVED. Deleting the
 * guard that leaves the candidate list unfiltered while the armed unit is
 * analysable -- writing `val candidates = ahead` -- passed all fifteen cases
 * here and all five in `:app`'s LiveFeedSourceTest, while silently moving the
 * readout off an armed unit that was delivering perfectly well and merely
 * running behind a faster partner. The margin is a rule about a unit that is
 * NOT analysable, and nothing said so.
 *
 * EVERY CASE HERE WAS REWRITTEN when [LiveFeedPolicy.liveFeed] took frame
 * counts instead of a list of analysable roles. Each call passes the counts
 * that produce the list the same case used to pass, so the inputs are the same
 * inputs; the assertions are untouched. The three added at the bottom pass
 * both before and after the margin rule lands, which is why they are here and
 * not in the red commit.
 *
 * THE THREE MARGIN CASES ADDED HERE ARE RED at this commit and are the
 * whole of it. `liveFeed` moves the readout the instant the armed unit is
 * under [SensorCapturePolicy.MIN_ANALYSABLE_FRAMES] and any partner is over
 * it, so armed 7 / partner 8 -- a lead of ONE frame, roughly 10 ms at the
 * 100 Hz this app configures -- currently returns role B, fellBack true and
 * switched true, and all three assertions fail on each of the three.
 *
 * The seam is why this file can exist. The decision runs inside `:app`'s sample
 * handlers, which no test on the CI path reaches.
 */
class LiveFeedPolicyTest {
    @Test
    fun `the armed role feeds the tracker while it is delivering`() {
        val feed = LiveFeedPolicy.liveFeed(SensorRole.A, SensorRole.A, BOTH, frames(8, 8))
        assertEquals(SensorRole.A, feed.role)
        assertFalse(feed.fellBack)
        assertFalse(feed.switched)
    }

    @Test
    fun `nothing has been analysable yet at the start of a set`() {
        val feed = LiveFeedPolicy.liveFeed(SensorRole.A, SensorRole.A, BOTH, frames(0, 0))
        assertEquals(SensorRole.A, feed.role)
        assertFalse(feed.fellBack)
        assertFalse(feed.switched)
    }

    @Test
    fun `the readout moves to the partner when the armed unit cannot be analysed`() {
        val feed = LiveFeedPolicy.liveFeed(SensorRole.A, SensorRole.A, BOTH, frames(0, 8))
        assertEquals(SensorRole.B, feed.role)
        assertTrue(feed.fellBack)
        assertTrue(feed.switched)
    }

    @Test
    fun `the move is the same in either direction`() {
        val feed = LiveFeedPolicy.liveFeed(SensorRole.B, SensorRole.B, BOTH, frames(8, 0))
        assertEquals(SensorRole.A, feed.role)
        assertTrue(feed.fellBack)
        assertTrue(feed.switched)
    }

    @Test
    fun `the switch is announced on the frame that makes it and no later one`() {
        val first = LiveFeedPolicy.liveFeed(SensorRole.A, SensorRole.A, BOTH, frames(0, 8))
        val next = LiveFeedPolicy.liveFeed(SensorRole.A, first.role, BOTH, frames(0, 9))
        assertEquals(SensorRole.B, next.role)
        assertTrue(next.fellBack)
        assertFalse(next.switched)
    }

    @Test
    fun `the readout does not return to the armed stream once it has left it`() {
        val feed = LiveFeedPolicy.liveFeed(SensorRole.A, SensorRole.B, BOTH, frames(8, 8))
        assertEquals(SensorRole.B, feed.role)
        assertTrue(feed.fellBack)
        assertFalse(feed.switched)
    }

    @Test
    fun `a set with no roles has no role to feed from`() {
        val feed = LiveFeedPolicy.liveFeed(null, null, emptyList(), emptyMap())
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

    @Test
    fun `the readout moves off an armed unit its partner has left far behind`() {
        val feed = LiveFeedPolicy.liveFeed(SensorRole.A, SensorRole.A, BOTH, frames(0, 400))
        assertEquals(SensorRole.B, feed.role)
        assertTrue(feed.fellBack)
        assertTrue(feed.switched)
    }

    @Test
    fun `neither unit has enough frames to be worth moving to`() {
        val feed = LiveFeedPolicy.liveFeed(SensorRole.A, SensorRole.A, BOTH, frames(0, 7))
        assertEquals(SensorRole.A, feed.role)
        assertFalse(feed.fellBack)
        assertFalse(feed.switched)
    }

    @Test
    fun `a partner exactly eight frames ahead takes the readout`() {
        val feed = LiveFeedPolicy.liveFeed(SensorRole.A, SensorRole.A, BOTH, frames(7, 15))
        assertEquals(SensorRole.B, feed.role)
        assertTrue(feed.fellBack)
        assertTrue(feed.switched)
    }

    @Test
    fun `an armed unit one frame behind its partner keeps the readout`() {
        val feed = LiveFeedPolicy.liveFeed(SensorRole.A, SensorRole.A, BOTH, frames(7, 8))
        assertEquals(SensorRole.A, feed.role)
        assertFalse(feed.fellBack)
        assertFalse(feed.switched)
    }

    @Test
    fun `a partner seven frames ahead is not far enough ahead to take the readout`() {
        val feed = LiveFeedPolicy.liveFeed(SensorRole.A, SensorRole.A, BOTH, frames(7, 14))
        assertEquals(SensorRole.A, feed.role)
        assertFalse(feed.fellBack)
        assertFalse(feed.switched)
    }

    @Test
    fun `the margin holds in either direction`() {
        val feed = LiveFeedPolicy.liveFeed(SensorRole.B, SensorRole.B, BOTH, frames(9, 2))
        assertEquals(SensorRole.B, feed.role)
        assertFalse(feed.fellBack)
        assertFalse(feed.switched)
    }

    @Test
    fun `an armed unit that is delivering keeps the readout however far ahead its partner is`() {
        val feed = LiveFeedPolicy.liveFeed(SensorRole.A, SensorRole.A, BOTH, frames(400, 900))
        assertEquals(SensorRole.A, feed.role)
        assertFalse(feed.fellBack)
        assertFalse(feed.switched)
    }

    private companion object {
        /** Both roles armed, in the order they were armed in. */
        val BOTH = listOf(SensorRole.A, SensorRole.B)

        /** Frames delivered so far by role A and role B. */
        fun frames(a: Int, b: Int) = mapOf(SensorRole.A to a, SensorRole.B to b)
    }
}
