package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Who wins when the plan and the lifter disagree about a warm-up (#194). */
class WarmupMarkPolicyTest {
    /**
     * With no mark, the plan's declaration stands unchanged.
     *
     * This is every set on a declared plan, so it is the case that must not
     * move: #187 made `warmup` the plan's word, and a composition that
     * quietly altered a declared set would undo it.
     */
    @Test
    fun `with no mark the plan's declaration is the answer`() {
        assertTrue(WarmupMarkPolicy.effective(declared = true, mark = null))
        assertFalse(WarmupMarkPolicy.effective(declared = false, mark = null))
    }

    /**
     * The lifter's mark wins where both exist, in both directions.
     *
     * The second half is the one that costs something and is the reason the
     * mark is stored apart: unmarking a plan-declared warm-up has to be
     * possible, or the plan gets the last word on a set it only predicted.
     */
    @Test
    fun `the lifter's mark wins over the plan's declaration in both directions`() {
        assertTrue(WarmupMarkPolicy.effective(declared = false, mark = true))
        assertFalse(WarmupMarkPolicy.effective(declared = true, mark = false))
    }

    @Test
    fun `a mark agreeing with the plan changes nothing about the answer`() {
        assertTrue(WarmupMarkPolicy.effective(declared = true, mark = true))
        assertFalse(WarmupMarkPolicy.effective(declared = false, mark = false))
    }

    /**
     * Null is silence and is not a quiet false.
     *
     * A set nobody marked and a set marked as not-a-warm-up are different
     * facts, and only the first is the ordinary state of a plan-run set.
     */
    @Test
    fun `only a stated mark counts as the lifter having spoken`() {
        assertFalse(WarmupMarkPolicy.markedByLifter(null))
        assertTrue(WarmupMarkPolicy.markedByLifter(true))
        assertTrue(WarmupMarkPolicy.markedByLifter(false))
    }

    /**
     * A tap flips whatever currently stands, and never returns to silence.
     *
     * The ad-hoc case -- nothing declared, nothing marked -- is the one #194
     * exists for: one tap makes the rack warm-up sayable.
     */
    @Test
    fun `a tap flips the standing answer and never returns to silence`() {
        assertTrue(WarmupMarkPolicy.toggled(declared = false, mark = null))
        assertFalse(WarmupMarkPolicy.toggled(declared = true, mark = null))
        assertFalse(WarmupMarkPolicy.toggled(declared = false, mark = true))
        assertTrue(WarmupMarkPolicy.toggled(declared = true, mark = false))
    }

    @Test
    fun `two taps return the set to where it started`() {
        for (declared in listOf(false, true)) {
            val once = WarmupMarkPolicy.toggled(declared, null)
            val twice = WarmupMarkPolicy.toggled(declared, once)
            assertEquals(declared, WarmupMarkPolicy.effective(declared, twice))
            assertTrue(WarmupMarkPolicy.markedByLifter(twice), "two taps still leave a stated mark")
        }
    }

    /**
     * A disagreement is a fact the row can state.
     *
     * Without it, a lifter who unmarks a plan warm-up leaves a row that reads
     * exactly like a set no plan ever called one -- which is a gap the record
     * could not express rather than a value it did not carry.
     */
    @Test
    fun `a disagreement is only reported where the lifter has contradicted the plan`() {
        assertTrue(WarmupMarkPolicy.disagrees(declared = true, mark = false))
        assertTrue(WarmupMarkPolicy.disagrees(declared = false, mark = true))
        assertFalse(WarmupMarkPolicy.disagrees(declared = true, mark = true))
        assertFalse(WarmupMarkPolicy.disagrees(declared = false, mark = false))
        assertFalse(WarmupMarkPolicy.disagrees(declared = true, mark = null))
        assertFalse(WarmupMarkPolicy.disagrees(declared = false, mark = null))
    }
}
