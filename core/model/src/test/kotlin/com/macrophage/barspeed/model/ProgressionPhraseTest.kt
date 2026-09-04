package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What a full-session view says an exercise steps up by, before START (#235).
 *
 * CHARACTERIZATION ONLY AT THIS COMMIT. Every assertion below states the
 * answer [ProgressionKind.phrase] gives today, and every one of them states a
 * defect: the phrase is the empty string for all four kinds, which is what
 * every full-session view says about progression right now. They are marked
 * GAP and the differential commit after this one repoints exactly those.
 *
 * The fact this file exists to hold still: the plan's `progression` key is
 * read once, at flatten time, into each queued set (`PlanQueue.flattenPlan`
 * calls [ProgressionKind.ofPlan]), and is drawn once, by the post-set
 * headroom grid. So the dimension an exercise moves on is unreadable until
 * the lifter has already rated a set of it -- and for an exercise declared
 * [ProgressionKind.NONE], which shows no grid by design, it is never readable
 * at all.
 */
class ProgressionPhraseTest {
    /** GAP. Every kind says nothing, so nothing tells the four apart. */
    @Test
    fun `every kind says nothing today`() {
        assertEquals("", ProgressionKind.WEIGHT.phrase())
        assertEquals("", ProgressionKind.REPS.phrase())
        assertEquals("", ProgressionKind.TIME.phrase())
        assertEquals("", ProgressionKind.NONE.phrase())
    }

    /**
     * GAP. The omitted case is [ProgressionKind.ofPlan]'s answer put through
     * the same function -- there is no second path for it, and this states the
     * composition so a differential has something to point at.
     */
    @Test
    fun `an omitted declaration says nothing today either`() {
        assertEquals("", ProgressionKind.ofPlan(null).phrase())
    }
}
