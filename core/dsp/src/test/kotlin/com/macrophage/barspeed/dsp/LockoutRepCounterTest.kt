package com.macrophage.barspeed.dsp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [LockoutRepCounter]'s rule, clause by clause, on synthetic frames.
 *
 * Nothing here is a field measurement. The frames are drawn at 100 Hz in the
 * drive frame with the default [LiftDirection] and run through the default
 * [DspConfig]. A REP is an eccentric and a concentric of the same shape:
 * -2 m/s^2 for 0.5 s, +2 for 1.0 s, -2 for 0.5 s, so the bar goes down and
 * comes back up at up to 1 m/s; an UNRACK is the concentric half alone.
 * Every figure below is a property of those shapes.
 */
class LockoutRepCounterTest {
    private class Stream {
        private val frames = mutableListOf<LiveSetState>()
        private val stepS = 0.01
        private var t = 0.0

        fun hold(untilS: Double, accel: Double, magnitude: Double = 1.0): Stream {
            while (t < untilS - 1e-9) {
                frames += LiveSetState(elapsedS = t, accelMps2 = accel, accMagnitudeG = magnitude, quiet = false)
                t += stepS
            }
            return this
        }

        /** A rep starting now: down, up, and still for [restS]. */
        fun rep(restS: Double = 1.0): Stream {
            val t0 = t
            return hold(t0 + 0.5, -2.0).hold(t0 + 1.5, 2.0).hold(t0 + 2.0, -2.0).hold(t0 + 2.0 + restS, 0.0)
        }

        /** Lifting the bar off the hooks: up and stop, no eccentric before it. */
        fun unrack(): Stream {
            val t0 = t
            return hold(t0 + 0.5, 2.0).hold(t0 + 1.0, -2.0).hold(t0 + 2.0, 0.0)
        }

        fun withBlanks(): Stream {
            val blanked = frames.flatMap { listOf(it, LiveSetState(elapsedS = it.elapsedS, accelMps2 = -3.0)) }
            frames.clear()
            frames += blanked
            return this
        }

        fun speaks(counter: LockoutRepCounter = LockoutRepCounter()): List<Pair<Double, RepCall.Speak>> =
            frames.mapNotNull { live ->
                (counter.feed(live, 0L) as? RepCall.Speak)?.let { Math.round(live.elapsedS * 100) / 100.0 to it }
            }

        fun calls(counter: LockoutRepCounter = LockoutRepCounter()): List<Pair<Double, Int>> =
            speaks(counter).map { (at, speak) -> at to speak.count }
    }

    /**
     * Each rep is called once, with the running total, at the end of its own
     * concentric -- inside the last 0.2 s of the deceleration that stops the
     * bar at the top, before the next rep has begun.
     */
    @Test
    fun `each rep is called at its own lockout`() {
        val calls = Stream().hold(1.0, 0.0).rep().rep().rep().calls()
        assertEquals(listOf(1, 2, 3), calls.map { it.second })
        listOf(3.0, 6.0, 9.0).zip(calls).forEach { (topS, call) ->
            assertTrue(call.first in topS - 0.2..topS, "a call at ${call.first} against a top at $topS")
        }
    }

    /**
     * The walk-out: a concentric with no eccentric before it never opens a
     * cycle, so the first rep after it is rep 1 -- the phantom the owner
     * heard on every v0.2.2 squat set.
     */
    @Test
    fun `an unrack is not a rep`() {
        assertEquals(emptyList(), Stream().hold(1.0, 0.0).unrack().calls())
        assertEquals(listOf(1, 2), Stream().hold(1.0, 0.0).unrack().rep().rep().calls().map { it.second })
    }

    /** A dip that never reaches the eccentric's threshold opens nothing. */
    @Test
    fun `a small dip and recovery opens nothing`() {
        val calls = Stream().hold(1.0, 0.0).hold(1.2, -0.5).hold(1.6, 0.5).hold(1.8, -0.5).hold(4.0, 0.0).calls()
        assertEquals(emptyList(), calls)
    }

    /**
     * A shallow settle that does open a cycle -- down past the eccentric's
     * threshold, then a short push back -- rises under
     * [DspConfig.lockoutMinRise] and is closed without a call.
     */
    @Test
    fun `a settle that rises too little is not called`() {
        val settle = Stream().hold(1.0, 0.0).hold(1.3, -1.5).hold(1.6, 1.5).hold(1.7, 0.6).hold(1.8, -0.6)
        assertEquals(emptyList(), settle.hold(4.0, 0.0).calls())
        assertEquals(listOf(1), settle.rep().calls().map { it.second })
    }

    /**
     * A floor strike's ringing cannot open or close a cycle: for
     * [DspConfig.cycleContactWindowS] after a sample over
     * [DspConfig.cycleContactG] the acceleration is clipped, as
     * [CycleRepCounter] clips it.
     */
    @Test
    fun `a contact's ringing is clipped`() {
        val ringing = Stream().hold(1.0, 0.0).hold(1.01, 0.0, magnitude = 5.0).hold(1.31, -30.0).hold(3.0, 0.0)
        assertEquals(emptyList(), ringing.calls())
    }

    /**
     * What the bias tracker costs, pinned rather than argued: a FAST descent
     * stopped hard with no concentric after it -- 0.5 s at -2 m/s^2 and
     * 0.5 s at +2 -- reads, once the running mean is taken off, as a rise
     * past [DspConfig.lockoutMinRise], and is called. Every re-rack of the
     * squat corpus is slower and rises 0.19 or less; a faster one would be
     * a phantom.
     */
    @Test
    fun `a fast descent stopped hard is called -- the bias tracker's cost`() {
        val stopped = Stream().hold(1.0, 0.0).hold(1.5, -2.0).hold(2.0, 2.0).hold(5.0, 0.0)
        assertEquals(listOf(1), stopped.calls().map { it.second })
    }

    /** The window a call carries is the concentric: from the ascent's start to the call. */
    @Test
    fun `a call carries its concentric as the drive`() {
        val (at, speak) = Stream().hold(1.0, 0.0).rep().speaks().single()
        val drive = checkNotNull(speak.drive)
        assertEquals(at, Math.round(drive.endS * 100) / 100.0)
        assertTrue(drive.startS in 1.5..2.0, "the concentric began at ${drive.startS}")
    }

    /** A state no sample produced is skipped, so its acceleration is never read. */
    @Test
    fun `a state carrying no sample is skipped`() {
        assertEquals(listOf(1, 2), Stream().hold(1.0, 0.0).rep().rep().withBlanks().calls().map { it.second })
    }
}
