package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.StartPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What a finished set records about its LIVE tracker's latch (#302):
 * [StreamingSetTracker.publishedCountTrusted].
 *
 * THREE STATES, and the one this file exists for is the null. The latch starts
 * `true` and only a run past the displacement cap moves it, so a tracker that
 * was never fed a sample reads `true` -- which, published, would say a set
 * recorded with no sensor connected had a trusted velocity. The published
 * value is null there instead, and the latch itself is untouched.
 *
 * The two fed cases replay committed captures, under the declarations the
 * files that already pin their latch use: `TravelRatioSegmentationTest` pins
 * `countTrusted` true on the back squat at 1:1, and `DeadliftLiveCountFieldTest`
 * pins it false on every field-43 deadlift stream. This file pins only that the
 * published value carries the latch through unchanged once a sample arrived.
 */
class PublishedCountTrustedTest {
    private fun trackerFedWith(file: String, direction: LiftDirection): StreamingSetTracker {
        val samples = ImuCsv.decode(javaClass.getResourceAsStream("/$file")!!.readBytes().decodeToString())
        val tracker = StreamingSetTracker.forLift(direction)
        samples.forEach { tracker.feed(it) }
        return tracker
    }

    /** Back squat off a rack, as `TravelRatioSegmentationTest` declares it at 1:1. */
    private val backSquat = LiftDirection(startsWith = StartPhase.ECCENTRIC, travelRatio = 1.0)

    /** The geometry field-43's deadlifts declared, as `DeadliftLiveCountFieldTest` states it. */
    private val deadlift = LiftDirection(startsWith = StartPhase.CONCENTRIC)

    @Test
    fun `a tracker fed no sample publishes no trust at all, whatever its latch says`() {
        val tracker = StreamingSetTracker.forLift(backSquat)

        assertEquals(true, tracker.state.countTrusted, "the latch's starting value moved")
        assertNull(tracker.publishedCountTrusted, "a tracker that integrated nothing published a trusted velocity")
    }

    @Test
    fun `a tracker that held its zero publishes true`() {
        val tracker = trackerFedWith("field-backsquat-10hz.csv", backSquat)

        assertEquals(true, tracker.state.countTrusted, "the back squat's latch moved")
        assertEquals(true, tracker.publishedCountTrusted)
    }

    @Test
    fun `a tracker that lost its zero publishes false`() {
        val tracker = trackerFedWith("field-deadlift-straight-5rep-s43-set04.csv", deadlift)

        assertEquals(false, tracker.state.countTrusted, "field-43 set 4's latch moved")
        assertEquals(false, tracker.publishedCountTrusted)
    }
}
