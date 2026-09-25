package com.macrophage.barspeed.dsp

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [CycleRepCounter]'s closing rule, state by state, on synthetic frames.
 *
 * The field tables in `ClosingRuleCandidateTest` are the evidence the rule
 * counts deadlifts; this file is the evidence each clause does what its KDoc
 * says, one clause per test, on a stream shaped to reach exactly that clause.
 * Nothing here is a field measurement: the frames are drawn, 100 Hz, in the
 * drive frame with the default [LiftDirection], and every figure below is
 * a property of the shapes, not of a lift.
 *
 * The shapes: a DRIVE is the acceleration held at a positive level, a BRAKE a
 * negative one, a CONTACT one sample whose magnitude is 5 g, a STILL a stretch
 * of quiet samples. Run through the default [DspConfig], so every clause is
 * reached through the production constants.
 */
class CycleRepCounterTest {
    private class Stream {
        private val frames = mutableListOf<LiveSetState>()
        private val stepS = 0.01
        private var t = 0.0

        fun hold(untilS: Double, accel: Double, quiet: Boolean = false): Stream {
            while (t < untilS - 1e-9) {
                frames += LiveSetState(elapsedS = t, accelMps2 = accel, accMagnitudeG = 1.0, quiet = quiet)
                t += stepS
            }
            return this
        }

        fun contact(): Stream {
            frames += LiveSetState(elapsedS = t, accelMps2 = 0.0, accMagnitudeG = 5.0, quiet = false)
            t += stepS
            return this
        }

        /**
         * After every frame, a state no sample produced -- no magnitude, no
         * quiet flag -- on the same clock, carrying a large negative
         * acceleration that would wreck the drive if it were read.
         */
        fun withBlanks(): Stream {
            val blanked = frames.flatMap { listOf(it, LiveSetState(elapsedS = it.elapsedS, accelMps2 = -3.0)) }
            frames.clear()
            frames += blanked
            return this
        }

        /** The instants [CycleRepCounter] speaks at, and the totals it speaks. */
        fun calls(counter: CycleRepCounter = CycleRepCounter()): List<Pair<Double, Int>> = frames.mapNotNull { live ->
            (counter.feed(live, 0L) as? RepCall.Speak)?.let { Math.round(live.elapsedS * 100) / 100.0 to it.count }
        }
    }

    /** A drive from 1.0 s to 1.5 s and a long brake to 2.8 s: descent met by the time it arms. */
    private fun pull(): Stream = Stream().hold(1.0, 0.0).hold(1.5, 1.0).hold(2.8, -1.0)

    @Test
    fun `a completed pull is called at the floor contact that ends it, not at its brake`() {
        val calls = pull().hold(3.2, 0.0).contact().hold(4.0, 0.0).calls()
        assertEquals(listOf(3.2 to 1), calls, "one call, spoken at the contact")
    }

    /**
     * The failed pull's shape: the bar is back on the floor 0.45 s after the
     * drive, while the brake run that would arm it is still going. The drive is
     * dropped before it can arm, and nothing is ever spoken for it -- not at
     * the contact, and not at the stillness that follows either.
     */
    @Test
    fun `a contact too soon after the drive drops it before it arms, and nothing is spoken`() {
        val calls = Stream().hold(1.0, 0.0).hold(1.5, 1.0).hold(2.0, -1.0).contact()
            .hold(2.8, -1.0).hold(4.5, 0.0, quiet = true).calls()
        assertEquals(emptyList(), calls, "the attempt is silent")
    }

    /**
     * A drive that HAS armed -- a short hard brake -- and then a contact
     * 0.9 s after the drive ended: the pending rep is rejected, so the stillness
     * that comes after the minimum cycle has nothing left to close.
     */
    @Test
    fun `a contact before the minimum cycle rejects a pending rep`() {
        val calls = Stream().hold(1.0, 0.0).hold(1.3, 1.0).hold(1.8, -2.0).hold(2.2, 0.0).contact()
            .hold(4.5, 0.0, quiet = true).calls()
        assertEquals(emptyList(), calls, "the pending rep is rejected at the contact")
    }

    /**
     * A pending rep whose descent is short of `cycleDescentMps` when a STILL
     * arrives after the minimum cycle is KEPT, not called and not rejected; the
     * lowering that follows completes the descent and the next contact calls it.
     */
    @Test
    fun `a stillness before the descent is complete keeps the rep pending`() {
        val calls = Stream().hold(1.0, 0.0).hold(1.3, 1.0).hold(1.6, -1.0).hold(2.6, 0.0)
            .hold(3.2, 0.0, quiet = true).hold(4.2, -1.0).hold(4.4, 0.0).contact().hold(5.0, 0.0).calls()
        assertEquals(listOf(4.4 to 1), calls, "called at the contact after the descent, not at the stillness")
    }

    /**
     * A second drive STARTING 0.7 s after the first one ended REPLACES the
     * pending rep: nothing is spoken for the first, and the second is called at
     * its own contact as rep 1.
     */
    @Test
    fun `a drive starting inside the minimum cycle replaces the pending rep`() {
        val calls = Stream().hold(1.0, 0.0).hold(1.3, 1.0).hold(1.8, -2.0).hold(2.0, 0.0)
            .hold(2.3, 1.0).hold(2.8, -2.0).hold(4.0, 0.0).contact().hold(5.0, 0.0).calls()
        assertEquals(listOf(4.0 to 1), calls, "one call, for the second drive")
    }

    /**
     * The same two drives with the second STARTING 1.5 s after the first
     * ended: the first is closed and called at the second's arming, touch and
     * go, and the second at its own contact.
     */
    @Test
    fun `a drive starting after the minimum cycle closes the pending rep`() {
        val calls = Stream().hold(1.0, 0.0).hold(1.3, 1.0).hold(1.8, -2.0).hold(2.8, 0.0)
            .hold(3.1, 1.0).hold(3.6, -2.0).hold(5.0, 0.0).contact().hold(6.0, 0.0).calls()
        assertEquals(2, calls.size, "two calls: $calls")
        assertEquals(listOf(1, 2), calls.map { it.second }, "the running total")
        assertEquals(5.0, calls[1].first, "the second at its contact")
    }

    /**
     * A state no sample produced carries no magnitude and no quiet flag, and
     * is skipped rather than read: interleaving one after every frame of a
     * completed pull leaves the call exactly where it was.
     */
    @Test
    fun `a state no sample produced is not a frame`() {
        assertEquals(null, LiveSetState().accMagnitudeG)
        assertEquals(null, LiveSetState().quiet)
        assertEquals(RepCall.Hold, CycleRepCounter().feed(LiveSetState(accelMps2 = 3.0), 0L))
        val calls = pull().hold(3.2, 0.0).contact().hold(4.0, 0.0).withBlanks().calls()
        assertEquals(listOf(3.2 to 1), calls, "the blanks change nothing")
    }
}
