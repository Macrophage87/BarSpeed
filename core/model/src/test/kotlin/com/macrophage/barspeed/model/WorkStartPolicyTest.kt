package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The work-start instant the raw archive's manifest publishes (#337).
 *
 * Inputs as a row stores them: `prepS` is null on a set that ran no prep
 * (`beginSet` stores it only where the prep case is not NONE) and on every row
 * before v10; `workBegan` is null on every row before v15.
 */
class WorkStartPolicyTest {
    private val window = PrepWindow(startedAtMs = 1_000L, workStartedAtMs = 6_000L)

    /** A prepped set publishes what its stored window says, as since #185. */
    @Test
    fun `a stored window's work start is published`() {
        assertEquals(6_000L, WorkStartPolicy.publishedAtMs(window, 1_000L, prepS = 5, workBegan = true))
    }

    /**
     * A set that set out to run a prep and stored no window -- one abandoned
     * in it, or one whose window the policy refused -- has no work start this
     * row can name. The tap is not it: the prep ran after the tap.
     */
    @Test
    fun `a prepped set with no stored window publishes none`() {
        assertNull(WorkStartPolicy.publishedAtMs(null, 1_000L, prepS = 5, workBegan = true))
        assertNull(WorkStartPolicy.publishedAtMs(null, 1_000L, prepS = 5, workBegan = false))
    }

    /**
     * A row from before v15 cannot say its work began, so nothing is
     * published for it even where it ran no prep: an invented instant would
     * be indistinguishable from a recorded one.
     */
    @Test
    fun `a row that never recorded whether its work began publishes none`() {
        assertNull(WorkStartPolicy.publishedAtMs(null, 1_000L, prepS = null, workBegan = null))
    }

    /**
     * #337: a set that ran no prep began its work at the START SET tap, the
     * row's own `startedAtMs`. `AbandonedSetPolicy.workBegan` already says the
     * work began there; the archive now says when.
     */
    @Test
    fun `a set that ran no prep began its work at the tap`() {
        assertEquals(1_000L, WorkStartPolicy.publishedAtMs(null, 1_000L, prepS = null, workBegan = true))
    }

    /** A set whose work never began has no work start, prep or not. */
    @Test
    fun `a set whose work never began publishes none`() {
        assertNull(WorkStartPolicy.publishedAtMs(null, 1_000L, prepS = null, workBegan = false))
    }
}
