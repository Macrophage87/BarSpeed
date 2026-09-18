package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.LiveCounter
import com.macrophage.barspeed.model.RepCounter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The seam that chooses a live rep counter, and the licence for having built it:
 * the counter a sensor-counted set gets through the factory speaks the same
 * numbers at the same instants as the one `:app` constructed directly (#301).
 *
 * Nothing here is a differential. Every assertion is what the shipped app does
 * at this commit; the counts are the ones `DeadliftLiveCountFieldTest` and
 * `LiveRepCallCorpusTest` already pin, read through the new construction path.
 * If the factory had changed one call on one capture, the extraction would be a
 * behaviour change wearing a refactor's commit message.
 */
class LiveRepCountersTest {
    private fun load(fixture: String): List<ImuSample> = LiveCountCandidates.load(fixture)

    /** Every call one counter makes over a whole stream, as (count, arrival ms). */
    private fun calls(counter: LiveRepCounter, fixture: String, direction: LiftDirection): List<Pair<Int, Long>> {
        val tracker = StreamingSetTracker.forLift(direction)
        val spoken = mutableListOf<Pair<Int, Long>>()
        for (sample in load(fixture)) {
            val call = counter.feed(tracker.feed(sample), sample.timestampMs)
            if (call is RepCall.Speak) spoken += call.count to call.atTimestampMs
        }
        return spoken
    }

    /**
     * ALL 45 committed captures, call for call and instant for instant.
     *
     * The whole corpus rather than a sample, because the thing being licensed is
     * that a construction path changed nothing -- and "nothing" is a claim about
     * every capture there is, not about the three this issue is named for. The
     * comparison is against a directly constructed `LiveRepCaller`, which is
     * literally the expression `:app` held before this commit.
     */
    @Test
    fun `the segmenter built by the factory is call-for-call the one app built directly`() {
        var callsSeen = 0
        for (capture in CandidateCorpus.ALL) {
            val throughFactory = calls(
                LiveRepCounters.of(LiveCounter.SEGMENTER, capture.direction),
                capture.fixture,
                capture.direction,
            )
            val direct = calls(LiveRepCaller(capture.direction), capture.fixture, capture.direction)
            assertEquals(direct, throughFactory, "${capture.fixture}: calls through the factory")
            callsSeen += direct.size
        }
        assertEquals(45, CandidateCorpus.ALL.size, "captures compared")
        // A floor under the comparison: an equality that compared two empty
        // lists 45 times would pass and say nothing. 166 is over ALL 45
        // captures and is NOT `DriveImpulseCandidateTest`'s 108, which is the
        // total over the 33 that carry a truth.
        assertEquals(166, callsSeen, "calls the segmenter makes across all 45 captures")
    }

    /**
     * The drive-impulse counter built by the factory is the one the design
     * round's tables are built on.
     *
     * `DriveImpulseCandidateTest` scores `DriveImpulseCandidate`, a harness
     * wrapper; this asserts the factory hands back a counter that speaks the
     * same number of times on the three captures the choice was made on. Without
     * it, "the factory can build the drive counter" would be a claim about a
     * constructor and not about a count.
     */
    @Test
    fun `the drive-impulse counter built by the factory counts what the design round measured`() {
        val fixtures = CandidateCorpus.DEADLIFT_WINDOWS.keys.sorted()
        assertEquals(
            listOf(5, 5, 3),
            fixtures.map { fixture ->
                val direction = CandidateCorpus.capture(fixture).direction
                calls(LiveRepCounters.of(LiveCounter.DRIVE_IMPULSE, direction), fixture, direction).size
            },
            "drive-impulse calls on field-43 set 4, set 5, set 6",
        )
    }

    /**
     * What `:app` asks for: a counter per [RepCounter], or none.
     *
     * The three non-sensor counters get null, which is the disarm
     * `SensorRepCounter.begin` reads -- the `if (sensorCounted)` that used to
     * stand at the call site, now one decision in `:core:model`.
     *
     * The SENSOR row is a differential and is red at the commit that writes it:
     * it asserted `is LiveRepCaller` one commit ago, which is what #286 armed.
     */
    @Test
    fun `forCounted arms only a sensor-counted set, and arms it with the drive counter`() {
        val direction = LiftDirection()
        assertTrue(
            LiveRepCounters.forCounted(RepCounter.SENSOR, direction) is DriveImpulseCounter,
            "the counter a sensor-counted set is armed with",
        )
        listOf(RepCounter.MANUAL, RepCounter.METRONOME, RepCounter.NOBODY).forEach { counter ->
            assertNull(LiveRepCounters.forCounted(counter, direction), "$counter arms no live counter")
        }
    }
}
