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
 * Every case here names a [SetEndKind] and a completion state. #186 gates the
 * grid on completion for the two kinds that HAVE a completion signal, and the
 * expectations under "the completion gate" are that change's differentials:
 * they fail against the ungated policy this file was written for and pass once
 * it reads its own arguments. #137 is not reversed by them -- a guided set
 * ended via Fail is rated on the rest screen, where its row reads
 * EFFORT -- FAILED and carries a Change action -- and the sweep asserting the
 * grid survives on a hand-counted set is what keeps the reversal from
 * spreading to the kinds that have no completion signal at all.
 */
class SetEndControlPolicyTest {
    private fun everyCase(): List<Triple<SetEndKind, Boolean, Boolean?>> = SetEndKind.entries.flatMap { kind ->
        listOf(true, false).flatMap { targetMet ->
            listOf(true, false, null).map { complete -> Triple(kind, targetMet, complete) }
        }
    }

    private val rateAndFail = setOf(SetEndControl.EFFORT_GRID, SetEndControl.FAILED_TILE)
    private val rateAndSkip = setOf(SetEndControl.EFFORT_GRID, SetEndControl.END_UNRATED)
    private val failOnly = setOf(SetEndControl.END_FAILED)

    private fun controls(c: Triple<SetEndKind, Boolean, Boolean?>) =
        SetEndControlPolicy.controls(kind = c.first, targetMet = c.second, complete = c.third, started = true)

    @Test
    fun `a set that met its target rates and ends in one tap`() {
        assertEquals(
            setOf(SetEndControl.EFFORT_GRID, SetEndControl.FAILED_TILE),
            SetEndControlPolicy.controls(SetEndKind.STRAIGHT_REPS, true, complete = null, started = true),
        )
    }

    @Test
    fun `a set that stopped short is asked how it felt, and may still be skipped`() {
        assertEquals(
            setOf(SetEndControl.EFFORT_GRID, SetEndControl.END_UNRATED),
            SetEndControlPolicy.controls(SetEndKind.STRAIGHT_REPS, false, complete = null, started = true),
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
                        SetEndControlPolicy.controls(SetEndKind.STRAIGHT_REPS, targetMet, complete, started = true),
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
                SetEndControlPolicy.controls(SetEndKind.STRAIGHT_REPS, false, complete = null, started = true),
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
                SetEndControlPolicy.controls(SetEndKind.STRAIGHT_REPS, false, complete = null, started = true),
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

    // ---- the completion gate, issue #186 ------------------------------------

    @Test
    fun `a tempo-guided set draws only the failure control until the guide finishes`() {
        // "It should only be shown when all the reps are finished or the hold
        // is finished. Earlier than that the only option available should be
        // fail." The app is counting, so the question is not answerable yet --
        // and asking it mid-set is what the owner asked to stop.
        listOf(true, false).forEach { targetMet ->
            assertEquals(
                setOf(SetEndControl.END_FAILED),
                SetEndControlPolicy.controls(SetEndKind.TEMPO_GUIDED, targetMet, false, started = true),
                "an unfinished guided set offers something other than Fail (targetMet=$targetMet)",
            )
        }
    }

    @Test
    fun `a hold draws only the failure control until its clock reaches the target`() {
        listOf(true, false).forEach { targetMet ->
            assertEquals(
                setOf(SetEndControl.END_FAILED),
                SetEndControlPolicy.controls(SetEndKind.TIMED, targetMet, false, started = true),
                "an unfinished hold offers something other than Fail (targetMet=$targetMet)",
            )
        }
    }

    @Test
    fun `a set the app cannot judge is not gated, so it keeps the grid`() {
        // complete = null is an ad-hoc hold with no target, or a guided set the
        // plan gave no rep count. Neither ever finishes on its own, so gating
        // them would leave a tapped failure as the only way out of a set that
        // went fine. This is the case that makes the argument nullable.
        listOf(SetEndKind.TEMPO_GUIDED, SetEndKind.TIMED).forEach { kind ->
            listOf(true, false).forEach { targetMet ->
                assertTrue(
                    SetEndControl.EFFORT_GRID in SetEndControlPolicy.controls(kind, targetMet, null, started = true),
                    "$kind with no completion signal cannot rate the set (targetMet=$targetMet)",
                )
            }
        }
    }

    @Test
    fun `a completed guided or timed set gets the grid it always got`() {
        listOf(SetEndKind.TEMPO_GUIDED, SetEndKind.TIMED).forEach { kind ->
            assertEquals(
                setOf(SetEndControl.EFFORT_GRID, SetEndControl.FAILED_TILE),
                SetEndControlPolicy.controls(kind, true, complete = true, started = true),
                "$kind loses the grid after completing",
            )
            assertEquals(
                setOf(SetEndControl.EFFORT_GRID, SetEndControl.END_UNRATED),
                SetEndControlPolicy.controls(kind, false, complete = true, started = true),
                "$kind that completed short of target loses the grid",
            )
        }
    }

    @Test
    fun `hand-counted and explosive sets are never gated on completion`() {
        // The owner's third message narrowed the scope to tempo and timed
        // work. On a manual-count set the app has no completion signal and the
        // effort tile IS how the lifter says the set is over: gating it there
        // would leave no exit at all.
        listOf(SetEndKind.STRAIGHT_REPS, SetEndKind.EXPLOSIVE).forEach { kind ->
            listOf(true, false).forEach { targetMet ->
                listOf(true, false, null).forEach { complete ->
                    assertEquals(
                        SetEndControlPolicy.controls(kind, targetMet, null, started = true),
                        SetEndControlPolicy.controls(kind, targetMet, complete, started = true),
                        "$kind changed with complete=$complete, which it must not read",
                    )
                    assertTrue(
                        SetEndControl.EFFORT_GRID in
                            SetEndControlPolicy.controls(kind, targetMet, complete, started = true),
                        "$kind lost the grid at targetMet=$targetMet complete=$complete",
                    )
                }
            }
        }
    }

    @Test
    fun `the grid never shares the screen with the standalone failure control`() {
        // END_FAILED exists because FAILED_TILE draws nothing by itself. The
        // two together would put the lifter's own failure verdict on screen
        // twice, in two controls that store the same fact.
        everyCase().forEach { case ->
            val controls = controls(case)
            assertFalse(
                SetEndControl.EFFORT_GRID in controls && SetEndControl.END_FAILED in controls,
                "$case draws the grid and the standalone failure control at once",
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

    /**
     * THE WHOLE TABLE, written out rather than swept.
     *
     * The invariance and membership sweeps above are well designed and each
     * catches a class of mutation, but between them they leave 10 of the 24
     * combinations pinned only by `in` or by equality with a sibling case: a
     * mutation returning [SetEndControl.END_UNRATED] where EXPLOSIVE now
     * answers [SetEndControl.FAILED_TILE] passes every one of them. So the
     * expected answer is stated once, exhaustively, as data.
     *
     * `started = true` throughout. The lead-in window is a separate rule with
     * its own table below, because folding it in would double 24 rows to 48
     * of which half are the same answer, and a table nobody can read is not a
     * pin.
     */
    private val expectedWhileRunning: Map<Triple<SetEndKind, Boolean, Boolean?>, Set<SetEndControl>> =
        mapOf(
            // A hand-counted set never reads `complete`: tapping an effort
            // tile IS how the lifter says the set is over.
            Triple(SetEndKind.STRAIGHT_REPS, true, true as Boolean?) to rateAndFail,
            Triple(SetEndKind.STRAIGHT_REPS, true, false as Boolean?) to rateAndFail,
            Triple(SetEndKind.STRAIGHT_REPS, true, null) to rateAndFail,
            Triple(SetEndKind.STRAIGHT_REPS, false, true as Boolean?) to rateAndSkip,
            Triple(SetEndKind.STRAIGHT_REPS, false, false as Boolean?) to rateAndSkip,
            Triple(SetEndKind.STRAIGHT_REPS, false, null) to rateAndSkip,
            // Explosive work keeps today's behaviour on the owner's third
            // message, so it reads `complete` no more than a manual set does.
            Triple(SetEndKind.EXPLOSIVE, true, true as Boolean?) to rateAndFail,
            Triple(SetEndKind.EXPLOSIVE, true, false as Boolean?) to rateAndFail,
            Triple(SetEndKind.EXPLOSIVE, true, null) to rateAndFail,
            Triple(SetEndKind.EXPLOSIVE, false, true as Boolean?) to rateAndSkip,
            Triple(SetEndKind.EXPLOSIVE, false, false as Boolean?) to rateAndSkip,
            Triple(SetEndKind.EXPLOSIVE, false, null) to rateAndSkip,
            // The gate: `complete == false` and nothing else withholds.
            Triple(SetEndKind.TEMPO_GUIDED, true, false as Boolean?) to failOnly,
            Triple(SetEndKind.TEMPO_GUIDED, false, false as Boolean?) to failOnly,
            Triple(SetEndKind.TIMED, true, false as Boolean?) to failOnly,
            Triple(SetEndKind.TIMED, false, false as Boolean?) to failOnly,
            // Complete, so the grid it always got.
            Triple(SetEndKind.TEMPO_GUIDED, true, true as Boolean?) to rateAndFail,
            Triple(SetEndKind.TEMPO_GUIDED, false, true as Boolean?) to rateAndSkip,
            Triple(SetEndKind.TIMED, true, true as Boolean?) to rateAndFail,
            Triple(SetEndKind.TIMED, false, true as Boolean?) to rateAndSkip,
            // Unjudgeable, so ungated. These four are the reason the argument
            // is nullable at all, and none of them was pinned by equality
            // before this table.
            Triple(SetEndKind.TEMPO_GUIDED, true, null) to rateAndFail,
            Triple(SetEndKind.TEMPO_GUIDED, false, null) to rateAndSkip,
            Triple(SetEndKind.TIMED, true, null) to rateAndFail,
            Triple(SetEndKind.TIMED, false, null) to rateAndSkip,
        )

    @Test
    fun `a set whose lead-in is still running offers only the way out`() {
        // The window the gate was never asked about: the 5 s prep before a
        // hold and the identical lead-in before a guided cadence. The clock
        // has not started, the guide has called no stroke, and `complete` is
        // therefore false -- so the gate offered BROKE EARLY - FAILED and
        // nothing else, and abandoning a set during its lead-in wrote a
        // TAPPED failure of a set that never began. #189 is about to build
        // failure-reason analysis on exactly that record.
        //
        // END_UNRATED and not the grid: how a set went is not a fact before
        // it starts either, so asking is no more answerable here than
        // mid-set. What the lifter needs is a way to leave, storing nothing.
        //
        // Every kind, because the rule is about the set and not about which
        // completion signal it has. Only TEMPO_GUIDED and TIMED can reach
        // this state in the app today, and a rule that reads the kind here
        // would be a second thing to keep in step with `SetEndKind`.
        everyCase().forEach { case ->
            assertEquals(
                setOf(SetEndControl.END_UNRATED),
                SetEndControlPolicy.controls(case.first, case.second, case.third, started = false),
                "$case during its lead-in",
            )
        }
    }

    @Test
    fun `every kind, target and completion combination has one stated answer`() {
        assertEquals(24, expectedWhileRunning.size, "the table does not carry one row per combination")
        assertEquals(
            everyCase().toSet(),
            expectedWhileRunning.keys,
            "the table and the combinations it claims to cover have drifted apart",
        )
        everyCase().forEach { case ->
            assertEquals(expectedWhileRunning.getValue(case), controls(case), "$case")
        }
    }
}
