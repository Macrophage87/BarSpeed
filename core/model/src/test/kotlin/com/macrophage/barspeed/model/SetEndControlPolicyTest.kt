package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the lifter may tap to end the set in front of them.
 *
 * Characterization first: these pin what the screen has always drawn, so that
 * the change to it lands as a visible change to these expectations rather than
 * as new behaviour nothing was watching.
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
    fun `a set that stopped short is ended, unrated, and that is all it may do`() {
        // Characterization of the defect in issue #137, pinned before it moves:
        // the effort grid is withheld from exactly the sets a lifter stopped, so
        // the RPE record contains only sets that hit their target.
        assertEquals(
            setOf(SetEndControl.END_UNRATED),
            SetEndControlPolicy.controls(targetMet = false),
        )
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
    fun `every case names its controls explicitly`() {
        // A set per case rather than a boolean per control, so a control added
        // later has to be placed in each case rather than defaulting into all of
        // them.
        listOf(true, false).forEach { targetMet ->
            assertTrue(SetEndControlPolicy.controls(targetMet).all { it in SetEndControl.entries })
        }
    }
}
