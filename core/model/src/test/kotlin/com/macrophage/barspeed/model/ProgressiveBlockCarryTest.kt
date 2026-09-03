package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What a load the lifter STATED does to the sets after it when the plan
 * declares a DIFFERENT load for each set of the same exercise -- a progressive
 * block, 45 / 55 / 65 rather than 3 x 55 (#143).
 *
 * ## Why this file exists apart from `SetLoadPolicyTest`
 *
 * That file pins the four boundaries #124 drew, one case each, on a block
 * whose declarations are all equal. The stepping block is the population where
 * those boundaries interact -- the block edge, the statement, and the plan's
 * own step all bear on the same set -- and reading a five-line block plan out
 * of a one-line assertion is what made #143's defect survive #124's landing
 * gate. The cases here are written as whole blocks: three declarations, one
 * statement, and the number the set after it is offered.
 *
 * ## Characterization, at this commit
 *
 * Every assertion below states what
 * [SetLoadPolicy.standingStatedAddedKg] does TODAY, not what it should do.
 * The ones marked `(pre-fix)` are the defect: they are asserted so the
 * differential that inverts them is a diff of one file and not a claim about
 * a state nobody wrote down.
 */
class ProgressiveBlockCarryTest {
    /**
     * A flat block, correction up. The plan says 55 for all three sets, the
     * lifter puts 60 on the bar for set 2, and set 3 is offered 60. This is
     * #124's landed behaviour and the case #143 does not touch; it is pinned
     * here so the fix can be shown not to move it.
     */
    @Test
    fun `a flat block carries a correction up`() {
        assertEquals(
            60.0,
            SetLoadPolicy.standingStatedAddedKg(
                statedAddedKg = 60.0,
                sameExerciseBlock = true,
                lastDeclaredAddedKg = 55.0,
                nextDeclaredAddedKg = 55.0,
            ),
        )
    }

    /**
     * A flat block, correction down. A lifter who fails at the prescribed 55
     * and drops to 50 is offered 50 again, not the weight they just failed.
     */
    @Test
    fun `a flat block carries a correction down`() {
        assertEquals(
            50.0,
            SetLoadPolicy.standingStatedAddedKg(
                statedAddedKg = 50.0,
                sameExerciseBlock = true,
                lastDeclaredAddedKg = 55.0,
                nextDeclaredAddedKg = 55.0,
            ),
        )
    }

    /**
     * A STEPPING block, correction up (pre-fix). Plan 45 / 55 / 65; the lifter
     * opens at 50 instead of 45 because the empty bar plus the plates they own
     * lands there. Today the two declarations compare unequal, the statement is
     * dropped, and set 2 is offered the plan's own 55 with nothing on screen
     * marking that the five kilos they added went away.
     */
    @Test
    fun `a stepping block drops a correction up (pre-fix)`() {
        assertNull(
            SetLoadPolicy.standingStatedAddedKg(
                statedAddedKg = 50.0,
                sameExerciseBlock = true,
                lastDeclaredAddedKg = 45.0,
                nextDeclaredAddedKg = 55.0,
            ),
        )
    }

    /**
     * A STEPPING block, correction down (pre-fix), which is #143's own worked
     * case. Plan 60 / 80 / 100; the lifter fails at 80, drops to 70, types it,
     * and does set 2 there. Today the statement is dropped at the transition
     * out of set 2 and set 3 is offered 100 -- thirty kilos above a weight
     * they have just failed at.
     */
    @Test
    fun `a stepping block drops a correction down (pre-fix)`() {
        assertNull(
            SetLoadPolicy.standingStatedAddedKg(
                statedAddedKg = 70.0,
                sameExerciseBlock = true,
                lastDeclaredAddedKg = 80.0,
                nextDeclaredAddedKg = 100.0,
            ),
        )
    }

    /**
     * The set after next, on the same stepping block. The carry is re-decided
     * at every rest transition, so the third set of 45 / 55 / 65 asks the same
     * question about the pair (55, 65) that the second asked about (45, 55) --
     * here with the lifter at 60 for set 2. Today it drops there too, so the
     * correction is gone for the whole remainder of the block rather than for
     * one set of it.
     */
    @Test
    fun `a stepping block drops the correction again at the next step (pre-fix)`() {
        assertNull(
            SetLoadPolicy.standingStatedAddedKg(
                statedAddedKg = 60.0,
                sameExerciseBlock = true,
                lastDeclaredAddedKg = 55.0,
                nextDeclaredAddedKg = 65.0,
            ),
        )
    }

    /**
     * The plan declares a load for the set just finished and NONE for the set
     * coming up (pre-fix). #143's first adjacent note: `60.0 == null` is false,
     * so the statement is dropped, and `seedAddedKg`'s `?: 0.0` then puts a
     * zero in the box. A plan that named no load for a set has not prescribed a
     * change to zero -- it has prescribed nothing -- and the difference is the
     * whole of the "absence rendered as a value" class.
     */
    @Test
    fun `a block whose next set declares no load drops the statement (pre-fix)`() {
        assertNull(
            SetLoadPolicy.standingStatedAddedKg(
                statedAddedKg = 65.0,
                sameExerciseBlock = true,
                lastDeclaredAddedKg = 60.0,
                nextDeclaredAddedKg = null,
            ),
        )
    }

    /**
     * The mirror of the case above: no declaration for the set just finished,
     * a declaration for the set coming up. The statement drops, and that stays
     * true after the fix -- there is no declaration for it to have been a
     * correction TO, so nothing measures how far off the plan the lifter is.
     * Pinned here beside its mirror because the two are one line apart in the
     * policy and the fix moves exactly one of them.
     */
    @Test
    fun `a block whose finished set declared no load drops the statement`() {
        assertNull(
            SetLoadPolicy.standingStatedAddedKg(
                statedAddedKg = 65.0,
                sameExerciseBlock = true,
                lastDeclaredAddedKg = null,
                nextDeclaredAddedKg = 60.0,
            ),
        )
    }

    /**
     * The block edge still ends the carry, whatever the declarations say. A
     * statement about the movement just finished says nothing about the next
     * one, and #124's leak was exactly this boundary being absent.
     */
    @Test
    fun `the block edge ends the carry on a stepping block too`() {
        assertNull(
            SetLoadPolicy.standingStatedAddedKg(
                statedAddedKg = 50.0,
                sameExerciseBlock = false,
                lastDeclaredAddedKg = 45.0,
                nextDeclaredAddedKg = 55.0,
            ),
        )
    }

    /**
     * Two slots declaring the SAME weight in different units (pre-fix). #143's
     * second adjacent note: `PlanSetDef.resolvedLoadKg` divides a `load_lb` by
     * `LB_PER_KG` and passes a `load_kg` through, so a plan writing 90 lb for
     * one set and 40.82 kg for the next declares the same bar twice and the two
     * Doubles differ. Today the statement drops there.
     */
    @Test
    fun `two declarations of one weight in different units drop the statement (pre-fix)`() {
        assertNull(
            SetLoadPolicy.standingStatedAddedKg(
                statedAddedKg = 45.0,
                sameExerciseBlock = true,
                lastDeclaredAddedKg = 40.82,
                nextDeclaredAddedKg = 90 / WeightUnit.LB_PER_KG,
            ),
        )
    }
}
