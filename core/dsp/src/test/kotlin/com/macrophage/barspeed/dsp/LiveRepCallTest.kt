package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.StartPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [LiveRepCaller]'s own contract: what it says, when, and what it refuses to
 * say. Issue #145.
 *
 * These pins are the ones that hold whatever the counting rule is. What the
 * thirteen mark-carrying captures actually make it say, and whether a number
 * it has said survives the rest of the set, are two different questions,
 * measured against the marks by the differentials pushed after this commit --
 * `LiveRepCallCorpusTest`, which does not exist yet.
 */
class LiveRepCallTest {
    private val con = LiftDirection(startsWith = StartPhase.CONCENTRIC)

    private fun load(n: String): List<ImuSample> = ImuCsv.decode(
        javaClass.getResourceAsStream("/$n.csv")!!.readBytes().decodeToString(),
    )

    /** Every call the caller makes over a capture, in order. */
    private fun replay(fixture: String, direction: LiftDirection): List<RepCall.Speak> {
        val tracker = StreamingSetTracker.forLift(direction)
        val caller = LiveRepCaller(direction)
        return load(fixture).mapNotNull { caller.feed(tracker.feed(it), it.timestampMs) as? RepCall.Speak }
    }

    @Test
    fun `the first sample of a set is always a hold`() {
        val direction = LiftDirection()
        val tracker = StreamingSetTracker.forLift(direction)
        val caller = LiveRepCaller(direction)
        val sample = load("field-bench-3010-6rep-s37-set05").first()
        assertEquals(RepCall.Hold, caller.feed(tracker.feed(sample), sample.timestampMs))
        assertEquals(0, caller.spoken)
    }

    /**
     * A set where nothing moved says nothing. The obvious case, pinned because
     * the alternative -- a counter that opens by announcing something -- is
     * exactly the failure a lifter cannot un-hear, and because a rule change
     * that broke it would break it silently.
     */
    @Test
    fun `a still capture is never spoken over`() {
        val calls = replay("field-still-0rep", LiftDirection())
        assertEquals(emptyList(), calls)
    }

    /**
     * The number spoken is the RUNNING TOTAL and it never goes backwards, so
     * the sequence of calls is 1, 2, 3, ... with no repeats and no gaps of
     * zero. Gaps ARE allowed -- a jump from 2 to 4 is the detector resolving
     * two reps between one sample and the next, and [RepCall.Speak] says the
     * later number rather than saying both.
     *
     * Asserted over every capture rather than one, because "strictly
     * increasing" is the sort of property one capture can satisfy by having
     * only one call in it.
     */
    @Test
    fun `every capture's calls are strictly increasing`() {
        for ((fixture, direction) in LiveRepCallCorpus.ALL) {
            val counts = replay(fixture, direction).map { it.count }
            assertEquals(counts.sorted(), counts, fixture)
            assertEquals(counts.size, counts.distinct().size, fixture)
            assertTrue(counts.all { it >= 1 }, fixture)
        }
    }

    /**
     * The instant on a call is the arrival stamp of the sample that was fed,
     * not any instant derived from the series.
     *
     * It matters because the cue track, the rep marks and the IMU stream are
     * all on the arrival clock while the series this class segments is on the
     * tracker's RECONSTRUCTED clock, and the two drift by up to
     * [CueTrack.MAX_SKEW_MS] mid-set. A call stamped off the wrong clock would
     * be off by that much against everything it has to be lined up with.
     */
    @Test
    fun `a call carries the arrival stamp of the sample that produced it`() {
        val samples = load("field-legpress-single-2011-8rep-s36-set07")
        val stamps = samples.map { it.timestampMs }.toSet()
        val calls = replay("field-legpress-single-2011-8rep-s36-set07", con)
        assertTrue(calls.isNotEmpty())
        assertTrue(calls.all { it.atTimestampMs in stamps })
    }

    /**
     * [LiveRepCaller.spoken] is the last number said, checked after every one
     * of this capture's samples rather than at the end -- an end-state check
     * cannot tell a field that tracks the calls from one that is assigned the
     * final total once. A caller fed nothing says 0.
     */
    @Test
    fun `spoken is the last number said`() {
        val fixture = "field-legpress-single-2011-8rep-s36-set07"
        val tracker = StreamingSetTracker.forLift(con)
        val caller = LiveRepCaller(con)
        var last = 0
        for (s in load(fixture)) {
            val call = caller.feed(tracker.feed(s), s.timestampMs)
            if (call is RepCall.Speak) last = call.count
            assertEquals(last, caller.spoken)
        }
        assertEquals(0, LiveRepCaller(con).spoken)
    }

    /**
     * The tracker publishes the clock the caller builds its series from, and
     * it is the one the tracker integrated: it starts at zero, never goes
     * backwards, and lands on the set's real length.
     *
     * NOT a constant frame interval. `StreamingSetTracker.updateClock`
     * re-estimates the interval from the arrival span as samples arrive, so
     * the step changes during the set; what is pinned is that the accumulated
     * clock is monotone, which is what a prefix series needs.
     *
     * Pinned here rather than in `StreamingSetTrackerTest` because these two
     * fields exist for this caller and for nothing else, and a pin beside the
     * consumer is the one that fails when the consumer's need changes.
     */
    @Test
    fun `the tracker publishes a monotone reconstructed clock`() {
        val tracker = StreamingSetTracker.forLift(con)
        var prev = -1.0
        var first = true
        for (s in load("field-ohp-3010-8rep-s38-set04")) {
            val live = tracker.feed(s)
            if (first) {
                assertEquals(0.0, live.elapsedS)
                first = false
            } else {
                assertTrue(live.elapsedS > prev, "clock went backwards at $prev")
            }
            prev = live.elapsedS
        }
        // 4988 samples at the ~99.4 Hz this capture was recorded at.
        assertTrue(prev in 49.0..51.0, "set length $prev s")
    }
}
