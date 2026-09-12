package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * RED AT THIS COMMIT, all four (#283).
 *
 * `RestEffortPromptPolicy.prompt` answers null for every timed set today, which
 * is the app's own behaviour lifted into a pure function: a hold ends on its
 * clock or on the standalone failure control, neither draws a grid, and the rest
 * screen has never asked. Each case below therefore fails on
 * `assertNotNull` until the fix lands in the commit after this one. The cases
 * that are ALREADY true -- a rep set owes nothing, a rated set owes nothing --
 * are in `RestEffortPromptBaselineTest` and stay green throughout, which is what
 * makes this file the differential and that one the baseline.
 *
 * The three end-states are the three ways a hold reaches the rest screen, and
 * they are distinguished by which failure fact stands rather than by how the
 * set ended, because that is all the row carries:
 *
 *  - ran to its clock: no failure of either kind.
 *  - the lifter tapped the failure control: `tappedFailed`, and `derivedFailed`
 *    too where the hold also fell below `TimedSetEndPolicy.CLOSE_ENOUGH_FRACTION`
 *    of its prescription. Field session 38's dead hangs are that pair.
 *  - the app judged it short and the lifter never said so: `derivedFailed`
 *    alone, which is what END SET EARLY during a hold's lead-in leaves.
 */
class RestEffortPromptDifferentialTest {
    @Test
    fun `a hold that ran to its clock is asked how hard it was`() {
        val prompt =
            RestEffortPromptPolicy.prompt(
                timed = true,
                rpe = null,
                tappedFailed = false,
                derivedFailed = false,
            )
        assertNotNull(prompt, "a hold that ended on its own clock was never asked how it went")
        // The tile is offered here and only here. Nothing on the row says this
        // set fell short -- TimedSetEndPolicy.recordedSeconds stores the target
        // itself on an auto-ended hold -- so the lifter's own word is the only
        // thing that can say it broke, which is SetEndControlPolicy's rule for
        // a set that met its target applied to the same question asked later.
        assertEquals(true, prompt.failedTile, "a hold that met its target cannot say it broke")
        assertEquals(false, prompt.carriesFailed, "a rung tapped on a clean hold would store a failure")
    }

    @Test
    fun `a hold the lifter broke early keeps that verdict whatever rung is tapped`() {
        val prompt =
            RestEffortPromptPolicy.prompt(
                timed = true,
                rpe = null,
                tappedFailed = true,
                derivedFailed = true,
            )
        assertNotNull(prompt, "a hold ended on the failure control was never asked how it went")
        // Withheld because the verdict is already on the row: the tile would
        // re-store what stands.
        assertEquals(false, prompt.failedTile, "the failure tile was offered to a set that already failed")
        // THE LOAD-BEARING ONE. SetRatingTracker.rate assigns tappedFailed from
        // its argument rather than OR-ing it, so a headroom tap passing false
        // would withdraw the lifter's own verdict and republish failedByLifter
        // false on a plank they actually dropped.
        assertEquals(true, prompt.carriesFailed, "a headroom tap would clear the lifter's own failure verdict")
    }

    @Test
    fun `a hold the app judged short is asked without being made to tap a failure`() {
        val prompt =
            RestEffortPromptPolicy.prompt(
                timed = true,
                rpe = null,
                tappedFailed = false,
                derivedFailed = true,
            )
        assertNotNull(prompt, "a hold recorded short of target was never asked how it went")
        // Withheld for the OTHER reason, and it is not the same reason: the
        // shortfall here is DERIVED, so correcting the held seconds re-derives
        // it. A tapped failure is the one a correction cannot clear, and this
        // is the path a mis-measured hold arrives on.
        assertEquals(false, prompt.failedTile, "a derived shortfall was offered a tile that stores a tapped one")
        assertEquals(false, prompt.carriesFailed, "a shortfall the lifter never claimed was stored as their word")
    }

    @Test
    fun `every unrated timed set is asked, and none is asked to state a verdict twice`() {
        for (tapped in listOf(false, true)) {
            for (derived in listOf(false, true)) {
                val prompt =
                    RestEffortPromptPolicy.prompt(
                        timed = true,
                        rpe = null,
                        tappedFailed = tapped,
                        derivedFailed = derived,
                    )
                assertNotNull(prompt, "an unrated timed set owed no question: tapped=$tapped derived=$derived")
                assertFalse(
                    prompt.failedTile && prompt.carriesFailed,
                    "the grid both offered a failure tile and carried one: tapped=$tapped derived=$derived",
                )
            }
        }
    }
}
