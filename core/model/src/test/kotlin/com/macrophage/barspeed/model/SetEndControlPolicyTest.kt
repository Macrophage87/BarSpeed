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
 *
 * Every case here names a [SetEndKind] and a completion state. Both arguments
 * are inert in this commit -- the policy reads only `targetMet` -- so what is
 * pinned is that the ungated rule is unchanged for every combination of them.
 * The differentials that make the two arguments matter are pushed separately,
 * red, before the gate exists.
 */
class SetEndControlPolicyTest {
    private fun everyCase(): List<Triple<SetEndKind, Boolean, Boolean?>> = SetEndKind.entries.flatMap { kind ->
        listOf(true, false).flatMap { targetMet ->
            listOf(true, false, null).map { complete -> Triple(kind, targetMet, complete) }
        }
    }

    private fun controls(c: Triple<SetEndKind, Boolean, Boolean?>) =
        SetEndControlPolicy.controls(kind = c.first, targetMet = c.second, complete = c.third)

    @Test
    fun `a set that met its target rates and ends in one tap`() {
        assertEquals(
            setOf(SetEndControl.EFFORT_GRID, SetEndControl.FAILED_TILE),
            SetEndControlPolicy.controls(SetEndKind.STRAIGHT_REPS, targetMet = true, complete = null),
        )
    }

    @Test
    fun `a set that stopped short is asked how it felt, and may still be skipped`() {
        assertEquals(
            setOf(SetEndControl.EFFORT_GRID, SetEndControl.END_UNRATED),
            SetEndControlPolicy.controls(SetEndKind.STRAIGHT_REPS, targetMet = false, complete = null),
        )
    }

    @Test
    fun `the effort grid is offered on a hand-counted set whether or not it met its target`() {
        // The one sentence issue #137 is about, and the one kind it stays
        // unqualified for: a hand-counted set has no completion signal, so
        // tapping an effort tile IS how the lifter says the set is over.
        // Withholding the grid there and keeping it everywhere else biases the
        // effort record in the direction that makes it useless -- the record
        // reads easier the harder the session got.
        listOf(true, false).forEach { targetMet ->
            listOf(true, false, null).forEach { complete ->
                assertTrue(
                    SetEndControl.EFFORT_GRID in
                        SetEndControlPolicy.controls(SetEndKind.STRAIGHT_REPS, targetMet, complete),
                    "targetMet=$targetMet complete=$complete cannot rate the set",
                )
            }
        }
    }

    @Test
    fun `a set that stopped short may still be ended with no rating`() {
        // Rating stays skippable. A lifter walking away mid-set must not be
        // nagged, and absence has to remain a state the record can hold.
        assertTrue(
            SetEndControl.END_UNRATED in
                SetEndControlPolicy.controls(SetEndKind.STRAIGHT_REPS, targetMet = false, complete = null),
        )
    }

    @Test
    fun `stopping short is never a tapped failure on a hand-counted set`() {
        // A shortfall is derived at the write and can be re-derived: correcting
        // a miscounted rep total clears it. A TAPPED failure is one no later rep
        // correction can clear, so the tile that sets it is withheld from the
        // path a miscount lands on.
        assertFalse(
            SetEndControl.FAILED_TILE in
                SetEndControlPolicy.controls(SetEndKind.STRAIGHT_REPS, targetMet = false, complete = null),
        )
    }

    @Test
    fun `every case leaves a way to end the set that stores no RPE`() {
        // Absence has to stay reachable in one tap. A lifter who is walking away
        // mid-set must not be made to rate the set before it will end.
        everyCase().forEach { case ->
            val controls = controls(case)
            assertTrue(
                SetEndControl.FAILED_TILE in controls ||
                    SetEndControl.END_UNRATED in controls ||
                    SetEndControl.END_FAILED in controls,
                "$case offers no unrated way to end the set",
            )
        }
    }

    @Test
    fun `no case offers two unrated ways out at once`() {
        // All three end the set with no RPE; they differ only in whether the
        // failure is stored as the lifter's own word. Side by side they would
        // be one decision presented as two, and one of them would be the wrong
        // one.
        everyCase().forEach { case ->
            val unrated =
                listOf(SetEndControl.FAILED_TILE, SetEndControl.END_UNRATED, SetEndControl.END_FAILED)
                    .count { it in controls(case) }
            assertTrue(unrated <= 1, "$case offers $unrated unrated exits")
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
        // Asserted as "one of the controls that DRAW", not as isNotEmpty. The
        // screen draws the grid on EFFORT_GRID, the skip on END_UNRATED and
        // the failure button on END_FAILED; FAILED_TILE only decides whether
        // one tile INSIDE the grid appears, so a case answering {FAILED_TILE}
        // alone is non-empty and still puts nothing on the screen.
        // `setOf(FAILED_TILE)` on any branch reds this; `isNotEmpty()` passed
        // it.
        //
        // Deliberately not the shape [RestControlPolicy] uses to assert its
        // enum is total (`controls(x).all { it in Control.entries }`). That
        // holds for any typed return and no mutation of this file can make it
        // fail, so it reads as coverage while measuring nothing.
        everyCase().forEach { case ->
            val controls = controls(case)
            assertTrue(
                SetEndControl.EFFORT_GRID in controls ||
                    SetEndControl.END_UNRATED in controls ||
                    SetEndControl.END_FAILED in controls,
                "$case draws no way to end the set",
            )
        }
    }

    // ---- which kind a set is, issue #186 ------------------------------------

    @Test
    fun `a hold is timed whatever else it is`() {
        // Measured on the clock is what makes it timed. An explosive movement
        // held for time is still judged by the clock, and the guide plays no
        // cadence into it.
        listOf(true, false).forEach { explosive ->
            listOf(true, false).forEach { guided ->
                assertEquals(
                    SetEndKind.TIMED,
                    SetEndKind.of(timed = true, explosive = explosive, guided = guided),
                    "a timed set with explosive=$explosive guided=$guided is not TIMED",
                )
            }
        }
    }

    @Test
    fun `an explosive rep set is explosive, guided or not`() {
        listOf(true, false).forEach { guided ->
            assertEquals(
                SetEndKind.EXPLOSIVE,
                SetEndKind.of(timed = false, explosive = true, guided = guided),
            )
        }
    }

    @Test
    fun `a cadenced rep set is tempo-guided and a hand-counted one is not`() {
        assertEquals(
            SetEndKind.TEMPO_GUIDED,
            SetEndKind.of(timed = false, explosive = false, guided = true),
        )
        assertEquals(
            SetEndKind.STRAIGHT_REPS,
            SetEndKind.of(timed = false, explosive = false, guided = false),
        )
    }

    @Test
    fun `exactly the kinds with a completion signal gate on it`() {
        // The property this whole change turns on, asserted per kind rather
        // than as a count: a kind that gates without a completion signal
        // leaves the lifter no exit but a failure they did not have.
        assertEquals(
            setOf(SetEndKind.TEMPO_GUIDED, SetEndKind.TIMED),
            SetEndKind.entries.filter { it.gatesOnCompletion }.toSet(),
        )
    }
}
