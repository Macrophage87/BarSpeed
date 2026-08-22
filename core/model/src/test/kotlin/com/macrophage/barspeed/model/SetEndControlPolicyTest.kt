package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the lifter may tap to end the set in front of them.
 *
 * The two expectations about a set that stopped short are the differential for
 * issue #137: gating the effort grid on the target left the RPE record holding
 * only sets that hit it, so every failed set is missing from it by
 * construction. On the 2026-08-21 session that is five of seventeen sets -- 7,
 * 9, 11, 12 and 16 -- and they are the five hardest of the day. An external
 * tool reading that export applied the rule "no RPE means warm-up" and reported
 * the lifter's five hardest sets as warm-ups, two pages after its own ledger
 * called the same five failed.
 */
class SetEndControlPolicyTest {
    @Test
    fun `a set that met its target rates and ends in one tap`() {
        assertEquals(
            setOf(SetEndControl.EFFORT_GRID, SetEndControl.FAILED_TILE),
            SetEndControlPolicy.controls(targetMet = true),
        )
    }

    @Test
    fun `a set that stopped short is asked how it felt, and may still be skipped`() {
        assertEquals(
            setOf(SetEndControl.EFFORT_GRID, SetEndControl.END_UNRATED),
            SetEndControlPolicy.controls(targetMet = false),
        )
    }

    @Test
    fun `the effort grid is offered whether or not the set met its target`() {
        // The one sentence issue #137 is about. A set the lifter stopped is
        // where the fatigue information is; withholding the grid there and
        // keeping it everywhere else biases the effort record in the direction
        // that makes it useless -- the record reads easier the harder the
        // session got.
        listOf(true, false).forEach { targetMet ->
            assertTrue(
                SetEndControl.EFFORT_GRID in SetEndControlPolicy.controls(targetMet),
                "targetMet=$targetMet cannot rate the set",
            )
        }
    }

    @Test
    fun `a set that stopped short may still be ended with no rating`() {
        // Rating stays skippable. A lifter walking away mid-set must not be
        // nagged, and absence has to remain a state the record can hold.
        assertTrue(SetEndControl.END_UNRATED in SetEndControlPolicy.controls(targetMet = false))
    }

    @Test
    fun `stopping short is never a tapped failure`() {
        // A shortfall is derived at the write and can be re-derived: correcting
        // a miscounted rep total clears it. A TAPPED failure is one no later rep
        // correction can clear, so the tile that sets it is withheld from the
        // path a miscount lands on.
        assertFalse(SetEndControl.FAILED_TILE in SetEndControlPolicy.controls(targetMet = false))
    }

    @Test
    fun `every case leaves a way to end the set that stores no RPE`() {
        // Absence has to stay reachable in one tap. A lifter who is walking away
        // mid-set must not be made to rate the set before it will end.
        listOf(true, false).forEach { targetMet ->
            val controls = SetEndControlPolicy.controls(targetMet)
            assertTrue(
                SetEndControl.FAILED_TILE in controls || SetEndControl.END_UNRATED in controls,
                "targetMet=$targetMet offers no unrated way to end the set",
            )
        }
    }

    @Test
    fun `no case offers two unrated ways out at once`() {
        // Both end the set with no RPE; they differ only in whether the failure
        // is stored as the lifter's own word. Side by side they would be one
        // decision presented as two, and one of them would be the wrong one.
        listOf(true, false).forEach { targetMet ->
            val controls = SetEndControlPolicy.controls(targetMet)
            assertFalse(
                SetEndControl.FAILED_TILE in controls && SetEndControl.END_UNRATED in controls,
                "targetMet=$targetMet offers two unrated exits",
            )
        }
    }

    @Test
    fun `every case leaves the lifter a way to end the set`() {
        // Drawing nothing is what [RestControlPolicy] does while a close is in
        // flight, and it is not available here. A set with no end control is a
        // set the lifter can only leave by navigating away, which destroys the
        // whole recording -- nothing reaches the database between `beginSet`
        // and `recordSet`.
        //
        // Asserted as "one of the two controls that DRAW", not as isNotEmpty.
        // The screen draws the grid on EFFORT_GRID and the button on
        // END_UNRATED; FAILED_TILE only decides whether one tile INSIDE the
        // grid appears, so a case answering {FAILED_TILE} alone is non-empty
        // and still puts nothing on the screen. `setOf(FAILED_TILE)` on either
        // branch reds this; `isNotEmpty()` passed it.
        //
        // Deliberately not the shape [RestControlPolicy] uses to assert its
        // enum is total (`controls(x).all { it in Control.entries }`). That
        // holds for any typed return and no mutation of this file can make it
        // fail, so it reads as coverage while measuring nothing.
        listOf(true, false).forEach { targetMet ->
            val controls = SetEndControlPolicy.controls(targetMet)
            assertTrue(
                SetEndControl.EFFORT_GRID in controls || SetEndControl.END_UNRATED in controls,
                "targetMet=$targetMet draws no way to end the set",
            )
        }
    }
}
