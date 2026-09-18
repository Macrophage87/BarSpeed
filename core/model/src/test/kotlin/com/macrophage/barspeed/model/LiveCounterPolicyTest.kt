package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Which live rep detector a set feeds (#301).
 *
 * These were CHARACTERIZATION pins and then the DIFFERENTIAL: two rows below
 * assert that a sensor-counted set runs [LiveCounter.DRIVE_IMPULSE], and at
 * 72938caa0aae4095ac7eef6e71197283b00d8b43, where the policy still answered
 * [LiveCounter.SEGMENTER], both FAILED -- CI run 35297021878 holds that red and
 * is the only part of it CI ever reached, having aborted at `:core:model:test`
 * before `:core:dsp`'s two differential files and their six red rows ran --
 * LiveCountDifferentialTest (5 of its 6) and LiveRepCountersTest (1 of its 3).
 * The policy answers
 * DRIVE_IMPULSE now and both rows are green. The retired answer is kept in the
 * words: SEGMENTER on a sensor-counted set is what #286 shipped and what
 * field-43 measured at three, one and two calls for five performed reps a set.
 */
class LiveCounterPolicyTest {
    /**
     * Four counters, so a fifth cannot be added without a row here.
     *
     * [LiveCounterPolicy.counterFor]'s `when` is exhaustive over [RepCounter],
     * so a new member is a compile error there and not a silent default -- this
     * assertion is the reminder that the new member also needs a row in the
     * table below, which a compiler cannot ask for.
     */
    @Test
    fun `every counter a set can have has a row here`() {
        assertEquals(4, RepCounter.entries.size, "counters a set can be counted by")
    }

    /**
     * The answer this issue requires, counter by counter.
     *
     * The sensor runs [LiveCounter.DRIVE_IMPULSE]. The other three run no live
     * detector at all, and null is that absence rather than a fourth quiet
     * counter -- unchanged by this issue, and asserted here so a change that
     * reached them could not pass.
     */
    @Test
    fun `a sensor-counted set runs the drive-impulse counter and no other set runs one`() {
        assertEquals(
            listOf(LiveCounter.DRIVE_IMPULSE, null, null, null),
            RepCounter.entries.map { LiveCounterPolicy.counterFor(it) },
            "the live detector for SENSOR, MANUAL, METRONOME, NOBODY in that order",
        )
    }

    /**
     * The shape field-43's three deadlift sets were recorded as, end to end.
     *
     * A rep-based dynamic set, no prescribed tempo, not timed, an IMU
     * connected: `meta.json` for all three carries no `tempoPrescribed` key,
     * `kind: "dynamic"` and two sensors armed. Pinning that shape through both
     * policies is what stops the two from being changed apart. It is NOT a claim
     * that nothing else reaches [RepCounter.SENSOR] without a tempo: the gate
     * reads the tempo, the clock and the kind and never the geometry, so a
     * machine set prescribed in reps with no tempo reaches it too, and
     * `LiveCounterPolicy`'s KDoc carries what that costs.
     */
    @Test
    fun `the shape field-43 recorded reaches the sensor, and the sensor drives on impulse`() {
        val counter = CountingPolicy.counterFor(
            hasTempo = false,
            isTimed = false,
            kind = ExerciseKind.DYNAMIC,
            imuConnected = true,
        )
        assertEquals(RepCounter.SENSOR, counter, "who counts a straight-reps set with a sensor on")
        assertEquals(
            LiveCounter.DRIVE_IMPULSE,
            LiveCounterPolicy.counterFor(counter),
            "and which detector that counter runs",
        )
    }

    /**
     * A tempo'd set of the same exercise keeps no live counter at all.
     *
     * The metronome counts it, so whatever the choice above becomes, a guided
     * set cannot be moved by it. That is the whole reason the choice is scoped
     * to the counter rather than to the lift.
     */
    @Test
    fun `a tempo-guided set of the same lift runs no live counter`() {
        val counter = CountingPolicy.counterFor(
            hasTempo = true,
            isTimed = false,
            kind = ExerciseKind.DYNAMIC,
            imuConnected = true,
        )
        assertEquals(RepCounter.METRONOME, counter, "who counts a tempo'd set")
        assertNull(LiveCounterPolicy.counterFor(counter), "the live detector a tempo'd set feeds")
    }
}
