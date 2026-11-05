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
 * The policy answered
 * DRIVE_IMPULSE from then until #305 and both rows were green. The retired
 * answer is kept in the words: SEGMENTER on a sensor-counted set is what #286
 * shipped and what field-43 measured at three, one and two calls for five
 * performed reps a set.
 *
 * #305 RE-BASELINED both rows to [LiveCounter.CYCLE], deliberately, in a
 * commit pushed alone so its red is a durable CI artifact; the policy's SENSOR
 * row moves in the commit after it. The answer it retires is kept in the words
 * too: DRIVE_IMPULSE on a sensor-counted set is what v0.1.54 shipped, and on
 * field-44 it called 5, 5, 5, 0 and 0 for 5, 5, 5, 4 and 2 completed reps.
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
     * The sensor runs [LiveCounter.CYCLE]. The other three run no live
     * detector at all, and null is that absence rather than a fourth quiet
     * counter -- unchanged by this issue, and asserted here so a change that
     * reached them could not pass.
     */
    @Test
    fun `a sensor-counted set runs the cycle counter and no other set runs one`() {
        assertEquals(
            listOf(LiveCounter.CYCLE, null, null, null),
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
    fun `the shape field-43 recorded reaches the sensor, and the sensor counts full cycles`() {
        val counter = CountingPolicy.counterFor(
            hasTempo = false,
            isTimed = false,
            kind = ExerciseKind.DYNAMIC,
            imuConnected = true,
        )
        assertEquals(RepCounter.SENSOR, counter, "who counts a straight-reps set with a sensor on")
        assertEquals(
            LiveCounter.CYCLE,
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

    /**
     * No set both runs a prep and arms a live rep counter, so the live count
     * has no head to bound (#256).
     *
     * `WorkStart` bounds the ANALYSED set at the head: a detection whose drive
     * ended before `PrepWindow.workStartedAtMs` is not a rep (#245). #256 asks
     * for the same bound on the number the lifter hears mid-set. That number
     * is the live counter's -- `LiveRepCounters.forCounted`, built from this
     * policy -- and not `StreamingSetTracker.repCount`, which since #301 is
     * drawn nowhere. So the bound would only ever act on a set that has BOTH
     * a work-start instant and a live counter, and this asserts there is no
     * such set, over every shape [CountingPolicy.counterFor] can be asked.
     *
     * Why there is none, read off the three policies rather than restated: a
     * work-start instant is written only on a [PrepCase.TIMED] or a
     * [PrepCase.CUED] prep (`RecordViewModel.startSetTimer` and the cadence's
     * `onWorkStarted`), a CUED set is counted by the [RepCounter.METRONOME]
     * and a timed one by [RepCounter.NOBODY], and neither of those arms a live
     * detector. On every set the sensor counts, the prep case is
     * [PrepCase.NONE], the instant is null and `WorkStart.of(null)` is
     * `Unknown` -- the batch path bounds nothing there either, so the live and
     * analysed counts agree at the head by construction rather than by a
     * second statement of the rule.
     *
     * THIS IS THE PIN THAT MAKES THAT SENTENCE CHECKED. A change that gives a
     * sensor-counted set a prep -- a countdown before a straight-reps set, say
     * -- reds here, and that change then owes the live counter the head bound
     * `WorkStart.withinSet` states, applied to each detector's drive end on the
     * arrival clock.
     */
    @Test
    fun `no set both runs a prep and arms a live counter`() {
        val both = mutableListOf<String>()
        for (hasTempo in listOf(false, true)) {
            for (isTimed in listOf(false, true)) {
                for (kind in ExerciseKind.entries) {
                    for (imuConnected in listOf(false, true)) {
                        val prep = LeadInPolicy.prepCase(hasTempo, isTimed, kind)
                        val counter = CountingPolicy.counterFor(hasTempo, isTimed, kind, imuConnected)
                        if (prep != PrepCase.NONE && LiveCounterPolicy.counterFor(counter) != null) {
                            both += "tempo=$hasTempo timed=$isTimed $kind imu=$imuConnected: $prep, $counter"
                        }
                    }
                }
            }
        }
        assertEquals(emptyList(), both, "set shapes with a prep AND a live counter")
        // Not vacuous: both halves of the conjunction are reachable on their own.
        assertEquals(
            PrepCase.CUED,
            LeadInPolicy.prepCase(hasTempo = true, isTimed = false, kind = ExerciseKind.DYNAMIC),
            "a tempo'd dynamic set runs a prep",
        )
        assertEquals(
            LiveCounter.CYCLE,
            LiveCounterPolicy.counterFor(
                CountingPolicy.counterFor(
                    hasTempo = false,
                    isTimed = false,
                    kind = ExerciseKind.DYNAMIC,
                    imuConnected = true,
                ),
            ),
            "a straight-reps dynamic set with a sensor arms a live counter",
        )
    }
}
