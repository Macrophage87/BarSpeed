package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.StartPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [LockoutRepCounter] over every eccentric-first capture the candidate corpus
 * holds, fed by the app's own tracker.
 *
 * Two kinds of row, and only one is evidence of the rule:
 *
 * - UNTEMPO'D SQUATS, the sets the sensor counts in the app. Field-46's five
 *   straight back squats (#335), on both units: the owner's bound is at least
 *   4 and most likely 5, and the rule calls 5, 5, 4, 4 and 5 on role a and
 *   the same on role b. Its constants were fitted on these and on the
 *   owner's 2026-11-06 session, which the repository does not hold.
 * - TEMPO'D CAPTURES. A set with a prescribed tempo is counted by the
 *   metronome (`CountingPolicy.counterFor`), so no app path feeds one of these
 *   to a live counter. They are pinned so a change to the rule shows what it
 *   would do off the squat, not as a claim it counts them.
 *
 * Every count is pinned exactly; the comparison with [CycleRepCounter] is
 * the table below the pins, measured by the same feed.
 */
class LockoutCorpusTest {
    private fun count(fixture: String, direction: LiftDirection): Int {
        val tracker = StreamingSetTracker.forLift(direction)
        val counter = LockoutRepCounter(direction)
        return LiveCountCandidates.load(fixture).count {
            counter.feed(tracker.feed(it), it.timestampMs) is RepCall.Speak
        }
    }

    private val eccentric = CandidateCorpus.ALL.filter { it.direction.startsWith == StartPhase.ECCENTRIC }

    /** Field-46's five straight squats, role a then role b, each inside the owner's bound. */
    @Test
    fun `field-46's squats are each called 4 or 5 on both units`() {
        val squats = eccentric.filter { it.fixture.startsWith("field-backsquat-straight-s46-") }
        assertEquals(5, squats.size)
        val roleA = squats.map { count(it.fixture, it.direction) }
        val roleB = squats.map { count("${it.fixture}-imu-b", it.direction) }
        assertEquals(listOf(5, 5, 4, 4, 5), roleA, "role a")
        assertEquals(listOf(5, 5, 4, 4, 5), roleB, "role b")
        (roleA + roleB).forEach { assertTrue(it in 4..5, "$it against the owner's at least 4, likely 5") }
    }

    /** Every eccentric-first capture, pinned. */
    @Test
    fun `every eccentric-first capture is pinned`() {
        val expected = mapOf(
            "field-backsquat-10hz" to 4,
            "field-backsquat-10hz-set5" to 0,
            "field-backsquat-4011-6rep-s36-set01" to 5,
            "field-backsquat-99hz-6rep" to 6,
            "field-backsquat-straight-s46-set01" to 5,
            "field-backsquat-straight-s46-set02" to 5,
            "field-backsquat-straight-s46-set03" to 4,
            "field-backsquat-straight-s46-set04" to 4,
            "field-backsquat-straight-s46-set05" to 5,
            "field-backsquat-wrapping-s36-set01" to 5,
            "field-bench-3010-6rep-s37-set05" to 5,
            "field-bench-3010-6rep-s37-set06" to 3,
            "field-bench-3010-6rep-s42-set05" to 5,
            "field-bench-3010-6rep-s42-set07" to 6,
            "field-bench-rotating-6rep" to 6,
            "field-bench-rotating-6rep-ok" to 6,
            "field-inclinepress-3010-12rep-s38-set02" to 12,
            "field-legpress-2010-8rep" to 7,
            "field-ohp-100hz-bursty" to 5,
            "field-ohp-rotating-8rep" to 8,
            "field-ohp-rotating-8rep-b" to 8,
            "field-rdl-3010-10rep" to 9,
            "field-rdl-3010-10rep-s36-set04" to 10,
            "field-rdl-3010-10rep-s36-set05" to 7,
            "field-rdl-wrapping-s36-set05" to 7,
            "field-ropedeadhang-hold20-s37-set11" to 0,
            "field-ropedeadhang-hold45-s38-set17" to 0,
            "field-ropedeadhang-hold45-s38-set18" to 0,
            "field-ropefarmershold-hold30-s42-set16" to 0,
            "field-still-0rep" to 0,
        )
        assertEquals(expected.keys, eccentric.map { it.fixture }.toSet(), "the eccentric-first captures")
        eccentric.forEach { assertEquals(expected.getValue(it.fixture), count(it.fixture, it.direction), it.fixture) }
    }

    /**
     * Against [CycleRepCounter] on the eccentric-first captures with a truth:
     * the summed miss, |calls - truth|, is 15 for this rule and 18 for the
     * full-cycle rule. The four holds, where nothing is lifted, go from a
     * phantom each to none; the slow 3010 RDLs lose the most.
     */
    @Test
    fun `against the full-cycle rule on the scored eccentric-first captures`() {
        val scored = CandidateCorpus.scored().filter { it.first.direction.startsWith == StartPhase.ECCENTRIC }
        fun cycle(fixture: String, direction: LiftDirection): Int {
            val tracker = StreamingSetTracker.forLift(direction)
            val counter = CycleRepCounter(direction)
            return LiveCountCandidates.load(fixture).count {
                counter.feed(tracker.feed(it), it.timestampMs) is RepCall.Speak
            }
        }
        val lockoutMiss = scored.sumOf { (c, t) -> kotlin.math.abs(count(c.fixture, c.direction) - t.reps!!) }
        val cycleMiss = scored.sumOf { (c, t) -> kotlin.math.abs(cycle(c.fixture, c.direction) - t.reps!!) }
        assertEquals(15 to 18, lockoutMiss to cycleMiss)
    }
}
