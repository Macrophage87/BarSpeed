package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * What a COMPLETED set's reason page reads, and the rung that qualifies one to
 * be asked at all (#191).
 *
 * The wording here is authored and unmeasured; what these pins guard is that
 * it cannot silently become the FAILURE wording again, which is the whole
 * defect: "Muscle failure" over a set the lifter finished is a claim about
 * something that did not happen.
 */
class SetLimiterCompletedWordingTest {
    @Test
    fun `the four failure-shaped answers are reworded for a completed set`() {
        val completed = SetLimiterScale.tiles(timed = false, failed = false).associate { it.limiter to it.label }
        val failedSet = SetLimiterScale.tiles(timed = false, failed = true).associate { it.limiter to it.label }
        for (limiter in listOf(SetLimiter.MUSCLE, SetLimiter.GRIP, SetLimiter.FORM, SetLimiter.PACE)) {
            assertNotEquals(
                failedSet.getValue(limiter),
                completed.getValue(limiter),
                "$limiter still reads as a failure on a set the lifter completed",
            )
        }
        assertEquals("The muscle was the limit", completed.getValue(SetLimiter.MUSCLE))
        assertEquals("Grip was the limit", completed.getValue(SetLimiter.GRIP))
        assertEquals("Form or position was going", completed.getValue(SetLimiter.FORM))
        assertEquals("Holding the tempo", completed.getValue(SetLimiter.PACE))
    }

    /**
     * The five that read correctly either way are NOT reworded.
     *
     * A second full table would drift from the first; the override map is
     * four entries precisely so an unchanged answer is unchanged by
     * construction rather than by two copies agreeing.
     */
    @Test
    fun `the answers that read correctly on a finished set are left alone`() {
        val completed = SetLimiterScale.tiles(timed = false, failed = false).associate { it.limiter to it.label }
        val failedSet = SetLimiterScale.tiles(timed = false, failed = true).associate { it.limiter to it.label }
        val unchanged =
            listOf(SetLimiter.SLIP, SetLimiter.SETUP, SetLimiter.PAIN, SetLimiter.OUTSIDE, SetLimiter.OTHER)
        for (limiter in unchanged) {
            assertEquals(failedSet.getValue(limiter), completed.getValue(limiter))
        }
    }

    /**
     * A completed set is offered the same answers, in the same order and the
     * same groups.
     *
     * The widening is a question asked of more sets, not a second vocabulary.
     * A member offered on one page and not the other would be an answer whose
     * availability depended on how the set went, which is exactly the coupling
     * #191 removes.
     */
    @Test
    fun `a completed set is offered the same answers in the same order`() {
        for (timed in listOf(false, true)) {
            assertEquals(
                SetLimiterScale.tiles(timed, failed = true).map { it.limiter to it.group },
                SetLimiterScale.tiles(timed, failed = false).map { it.limiter to it.group },
                "the completed page offers a different set of answers for timed=$timed",
            )
        }
    }

    /** No two tiles read the same, on either page, in either kind of set. */
    @Test
    fun `no two answers read the same on a completed set`() {
        for (timed in listOf(false, true)) {
            val labels = SetLimiterScale.tiles(timed, failed = false).map { it.label }
            assertEquals(labels.size, labels.toSet().size, "two completed tiles read the same for timed=$timed")
        }
    }

    /**
     * A completed HOLD reads the completed override where one exists and the
     * hold wording where it does not.
     *
     * That precedence is what lets the override map hold four entries instead
     * of eight, so it is pinned rather than left to the reading order of two
     * elvis operators.
     */
    @Test
    fun `a completed hold takes the completed wording over the hold wording`() {
        assertEquals(
            "The muscle was the limit",
            SetLimiterScale.label(SetLimiter.MUSCLE, timed = true, failed = false),
        )
        assertEquals(
            "Could not hold it any longer",
            SetLimiterScale.label(SetLimiter.MUSCLE, timed = true, failed = true),
        )
        assertEquals(
            "Bad setup or position",
            SetLimiterScale.label(SetLimiter.SETUP, timed = true, failed = false),
        )
    }

    /** The page asks a different question of a set that did not end badly. */
    @Test
    fun `the page caption asks what limited a completed set`() {
        assertEquals("Why did that set end? · optional", SetLimiterPolicy.pageTitle(failed = true))
        assertEquals("What limited that set? · optional", SetLimiterPolicy.pageTitle(failed = false))
    }

    /**
     * It says optional in BOTH cases.
     *
     * A completed set is asked once and may be left unanswered; a caption that
     * dropped the word on the widened case would turn an optional question
     * into an apparent gap in the record.
     */
    @Test
    fun `both page captions say the question is optional`() {
        for (failed in listOf(false, true)) {
            assertTrue(
                SetLimiterPolicy.pageTitle(failed).contains("optional"),
                "the page caption does not say it is optional for failed=$failed",
            )
        }
    }

    @Test
    fun `the reason line is labelled for what it is`() {
        assertEquals("Ended", SetLimiterPolicy.lineLabel(failed = true))
        assertEquals("Limited by", SetLimiterPolicy.lineLabel(failed = false))
    }

    /** The failure wording is unchanged; only the completed case is new. */
    @Test
    fun `the way into the page is worded for the set it is asked about`() {
        assertEquals("Say why", SetLimiterPolicy.lineAction(failed = true, limiter = null))
        assertEquals("Answer", SetLimiterPolicy.lineAction(failed = false, limiter = null))
        assertEquals("Change", SetLimiterPolicy.lineAction(failed = true, limiter = SetLimiter.GRIP))
        assertEquals("Change", SetLimiterPolicy.lineAction(failed = false, limiter = SetLimiter.PACE))
    }

    /**
     * Which ratings count as near failure, read off [EffortScale] rather than
     * restated.
     *
     * 7 through 10 are the counted rungs -- the reps-in-reserve end, where the
     * set was a test of something. 1, 4 and 6 are the headroom rungs, whose
     * whole content is that there was room left, so nothing limited the set
     * and there is nothing to ask about. 2, 3 and 5 are valid values with no
     * tile: the app never offered them, so it has no answer to act on and asks
     * nothing.
     */
    @Test
    fun `only the counted rungs read as near failure`() {
        for (rpe in EffortScale.PROXIMITY_FLOOR_RPE..10) {
            assertTrue(SetLimiterPolicy.ratedNearFailure(rpe), "rpe $rpe does not read as near failure")
        }
        for (tier in HeadroomTier.entries) {
            assertFalse(
                SetLimiterPolicy.ratedNearFailure(tier.rpe),
                "the headroom rung $tier reads as near failure",
            )
        }
        for (rpe in EffortScale.UNANCHORED_RPE) {
            assertFalse(SetLimiterPolicy.ratedNearFailure(rpe), "the unanchored rpe $rpe reads as near failure")
        }
        assertFalse(SetLimiterPolicy.ratedNearFailure(null), "an unrated set reads as near failure")
    }
}
