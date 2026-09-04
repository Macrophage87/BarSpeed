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
 * ## The rule these differentials ask for: an OFFSET, not an absolute
 *
 * A lifter who states a load on a set the plan declared a different one for
 * has said how far off the prescription they are, and that distance is what
 * holds for the rest of the block. Plan 45 / 55 / 65, opened at 50: the
 * statement is +5, so set 2 is offered 60 and set 3 is offered 70. The block
 * still STEPS -- the plan's own differences are untouched -- and the
 * correction still survives, which is the pair of properties the absolute
 * carry cannot hold at once. Carrying the absolute 50 flattens 55 and 65 to
 * 50, which is the defect facing the other way and the reason #124 drew the
 * boundary #143 is about.
 *
 * The offset is the reading #214's grid already committed to. Its tiles are
 * increments -- `NextSetNudgePolicy.bumpedLoadKg` is `current + nudge.amount`
 * -- so a lifter who taps "+10 lb" after a headroom rating has said "ten
 * pounds more than this", once, about the exercise. That claim means the same
 * thing on a flat block and on a stepping one only if what carries is the ten
 * pounds. Under an absolute carry the tile's own addition is what disappears
 * at the next step, which is what `NextSetNudgeGrid`'s KDoc records the grid
 * inheriting from #143.
 *
 * ## Why not the other candidate rule
 *
 * The alternative #143 raises is to keep the absolute, yield to the plan's
 * next step, and make the yield VISIBLE -- tell the lifter the load changed
 * instead of silently re-seeding. Only the DRAWING of a yield notice is out
 * of reach. Its wording and the condition it appears under would be a pure
 * function in `:core:model`, as [PlanValueCaption.load] and
 * [SetLoadPolicy.correctionCaption] already are, both pinned on the CI path.
 * The ground for choosing the offset is that a notice answers a lifter who
 * has just corrected the load with words instead of with the load, leaving
 * the correction to be retyped on every remaining set.
 *
 * THE SENTENCE THAT STOOD HERE CLAIMED "VISIBILITY IS NOT LOST BY CHOOSING
 * THE OFFSET" AND IS DELETED RATHER THAN REWORDED, #143 round 2. It was true
 * of the Up next card and the export -- a carried load never touches
 * `plannedLoad_kg`, so every set it reaches still renders as a deviation
 * there -- but PlanValueCaption's own reach sentence is a THIRD visibility
 * surface the argument did not check, and unlike the other two it names a
 * SPECIFIC number: "the rest of this exercise runs $shown". On a stepping
 * block that number is only true of the very next set, so the sentence was
 * wrong exactly where this file's offset carry made it reachable, and
 * "visibility is not lost" was false of the one surface a lifter reads
 * mid-set. `PlanValueCaptionContractTest`'s stepping-block differentials are
 * where that is fixed.
 *
 * ## Red, and then green
 *
 * Eight of the fourteen tests this class held then failed at `Red the
 * correction that should ride the plan's own steps`, where they were pushed
 * with no production change, and were answered at `Carry a load correction
 * across the plan's own steps as a distance`. The other six -- the two
 * flat-block carries, the flat block's bit-for-bit identity, the block edge,
 * the mirror null, and the empty statement -- are the behaviour the fix does
 * not move, and are green both sides of it. The class holds seventeen now:
 * the three added at `Red the stepping caption and the warm-up carry, round
 * 1's two findings` are round 2's, and one of the fourteen, the flat-block
 * identity test, was rewritten at `Kill the mutation the flat-block pin was
 * not catching` after the fix and is green on both sides of it. Commits are
 * named by subject rather than by SHA: this branch has been rebased twice and
 * every SHA a body wrote down went stale.
 *
 * ## Round 2: the warm-up opener is not a step (#143)
 *
 * A warm-up opener declares a load DIFFERENT from its working sets BY
 * DESIGN -- lighter, so the lifter can move before the working weight is on
 * the bar -- not as a step in a progression the way 45 / 55 / 65 is. A
 * correction stated on the opener, rounding it to the plates the rack
 * actually has, is not a distance the working sets should be shifted by, and
 * until this round the function above could not tell the two apart: any pair
 * of differing declarations was read as a step, warm-up or not.
 * [SetLoadPolicy.standingStatedAddedKg]'s `finishedWarmup`/`nextWarmup`
 * parameters are the guard, checked only on the branch that computes a
 * shift -- the equal-declaration and no-next-declaration branches above
 * already carry the statement through unchanged, and a warm-up whose own
 * declaration matches the working weight has corrected nothing to exclude.
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
                bodyweight = false,
                finishedWarmup = false,
                nextWarmup = false,
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
                bodyweight = false,
                finishedWarmup = false,
                nextWarmup = false,
            ),
        )
    }

    /**
     * A STEPPING block, correction up. Plan 45 / 55 / 65; the lifter opens at
     * 50 instead of 45 because the empty bar plus the plates they own lands
     * there. That is a correction of +5, and set 2 is offered 60 -- the plan's
     * own 55 with the same five kilos on it. The step from 45 to 55 is not
     * touched; only where the block sits.
     */
    @Test
    fun `a stepping block carries a correction up onto the plan's next step`() {
        assertEquals(
            60.0,
            SetLoadPolicy.standingStatedAddedKg(
                statedAddedKg = 50.0,
                sameExerciseBlock = true,
                lastDeclaredAddedKg = 45.0,
                nextDeclaredAddedKg = 55.0,
                bodyweight = false,
                finishedWarmup = false,
                nextWarmup = false,
            ),
        )
    }

    /**
     * A STEPPING block, correction down: #143's own worked case. Plan
     * 60 / 80 / 100; the lifter fails at 80, drops to 70, types it, does set 2
     * there. The correction is -10, so set 3 is offered 90 rather than the
     * plan's 100 -- still the step up the plan asked for, and not thirty kilos
     * above a weight they have just failed at.
     */
    @Test
    fun `a stepping block carries a correction down onto the plan's next step`() {
        assertEquals(
            90.0,
            SetLoadPolicy.standingStatedAddedKg(
                statedAddedKg = 70.0,
                sameExerciseBlock = true,
                lastDeclaredAddedKg = 80.0,
                nextDeclaredAddedKg = 100.0,
                bodyweight = false,
                finishedWarmup = false,
                nextWarmup = false,
            ),
        )
    }

    /**
     * The set after next, on the same stepping block. The carry is re-decided
     * at every rest transition, so the third set of 45 / 55 / 65 asks the same
     * question about the pair (55, 65) that the second asked about (45, 55) --
     * here with the lifter at 60 for set 2. The same +5 comes out of the pair
     * (55, 65) that came out of (45, 55), so the correction holds for the whole
     * remainder of the block and not for one set of it. THE OFFSET IS
     * RE-DERIVED at every transition, never accumulated: two transitions do not
     * make it +10.
     */
    @Test
    fun `the same correction re-derives at the next step of the block`() {
        assertEquals(
            70.0,
            SetLoadPolicy.standingStatedAddedKg(
                statedAddedKg = 60.0,
                sameExerciseBlock = true,
                lastDeclaredAddedKg = 55.0,
                nextDeclaredAddedKg = 65.0,
                bodyweight = false,
                finishedWarmup = false,
                nextWarmup = false,
            ),
        )
    }

    /**
     * The plan declares a load for the set just finished and NONE for the set
     * coming up. #143's first adjacent note: `60.0 == null` compares false, so
     * the statement was dropped and `seedAddedKg`'s `?: 0.0` put a zero in the
     * box -- 65 kg of work offered as an empty bar.
     *
     * There is no step to shift by here, because the plan prescribed no next
     * load to shift. A plan that named nothing has not prescribed a change to
     * zero; it has prescribed nothing, and the difference between those two is
     * the whole of the "absence rendered as a value" class. So the statement
     * stands unchanged -- which is also the rule this function already applies
     * where NEITHER set declares a load.
     */
    @Test
    fun `a statement stands where the plan declares no load for the next set`() {
        assertEquals(
            65.0,
            SetLoadPolicy.standingStatedAddedKg(
                statedAddedKg = 65.0,
                sameExerciseBlock = true,
                lastDeclaredAddedKg = 60.0,
                nextDeclaredAddedKg = null,
                bodyweight = false,
                finishedWarmup = false,
                nextWarmup = false,
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
                bodyweight = false,
                finishedWarmup = false,
                nextWarmup = false,
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
                bodyweight = false,
                finishedWarmup = false,
                nextWarmup = false,
            ),
        )
    }

    /**
     * Two slots declaring the SAME weight in different units (pre-fix). #143's
     * second adjacent note: `PlanSetDef.resolvedLoadKg` divides a `load_lb` by
     * `LB_PER_KG` and passes a `load_kg` through, so a plan writing 90 lb for
     * one set and 40.82 kg for the next declares the same bar twice and the two
     * Doubles differ, so the statement was dropped.
     *
     * Under the offset rule the two declarations no longer have to be equal,
     * only comparable, and the difference between them carries through as
     * itself. What comes out is the statement plus the residue between the two
     * ways of writing one weight: 45.00331330090319 rather than 45, measured
     * here and asserted exactly rather than to a tolerance, so a change to
     * `LB_PER_KG` or to the arithmetic reds this. The residue is 0.0033 kg --
     * three grams, below the 0.1-of-display-unit grid the load box renders on,
     * so nothing the lifter reads moves.
     */
    @Test
    fun `a weight declared twice in different units carries with its conversion residue`() {
        assertEquals(
            45.00331330090319,
            SetLoadPolicy.standingStatedAddedKg(
                statedAddedKg = 45.0,
                sameExerciseBlock = true,
                lastDeclaredAddedKg = 40.82,
                nextDeclaredAddedKg = 90 / WeightUnit.LB_PER_KG,
                bodyweight = false,
                finishedWarmup = false,
                nextWarmup = false,
            ),
        )
    }

    /**
     * A DESCENDING block corrected down far enough to cross zero, on loaded
     * work. Plan 100 / 20 -- a drop set -- and the lifter does the first at 60.
     * The offset is -40 and the plan's next step is 20, so the arithmetic alone
     * would offer -20 kg: a load neither the plan nor the lifter ever named,
     * and one a barbell cannot hold. It clamps to an empty bar.
     *
     * This is the floor `correctedAddedKg` already applies to the other load
     * control on this screen, applied here for its reason. It exists only
     * because the offset rule COMPUTES a load; the absolute carry passed one
     * through and could not invent a sign.
     */
    @Test
    fun `a downward offset that crosses zero clamps to an empty bar on loaded work`() {
        assertEquals(
            0.0,
            SetLoadPolicy.standingStatedAddedKg(
                statedAddedKg = 60.0,
                sameExerciseBlock = true,
                lastDeclaredAddedKg = 100.0,
                nextDeclaredAddedKg = 20.0,
                bodyweight = false,
                finishedWarmup = false,
                nextWarmup = false,
            ),
        )
    }

    /**
     * The same block on BODY-WEIGHT work, where the negative is not an
     * artifact. Assisted pull-ups declare negative added load by contract --
     * `PlanFile.validate` passes `allowNegativeLoad` on exactly this population
     * -- so -20 means twenty kilos of band, and clamping it to zero would make
     * assistance unsayable. The flag taken in the previous commit is what
     * separates these two cases, and this pair is what reds if it is ignored.
     */
    @Test
    fun `a downward offset stays negative for assisted body-weight work`() {
        assertEquals(
            -20.0,
            SetLoadPolicy.standingStatedAddedKg(
                statedAddedKg = 60.0,
                sameExerciseBlock = true,
                lastDeclaredAddedKg = 100.0,
                nextDeclaredAddedKg = 20.0,
                bodyweight = true,
                finishedWarmup = false,
                nextWarmup = false,
            ),
        )
    }

    /**
     * THE FLAT BLOCK RETURNS THE STATEMENT ITSELF, bit for bit, rather than
     * being put through `d + (s - d)`.
     *
     * THE VALUES HERE ARE NOT A GYM'S, and that is the finding rather than a
     * flaw in the case. The version of this test that stood here used a plan
     * declaring 175 lb against a statement of 79.4 kg and claimed to pin the
     * identity; it did not, and the mutation that deletes the early return
     * survived it with 0 failures. The two expressions agree for every
     * realistic pair -- searched over three million random plate-step
     * statements against declarations from 45 to 500 lb, and 200,000 kilogram
     * pairs, with zero disagreements -- because a statement and a declaration
     * within a factor of two of each other have an exact difference. A
     * disagreement needs a correction of about 290 kg, which is what these
     * two doubles are: the only shape of input that can tell the guarantee
     * from its absence.
     *
     * So this pins a GUARANTEE, and says so. The flat block is where the
     * lifter's own number has to survive untouched -- #45 is what happens when
     * a load is quantised on a path that promised not to move it -- and a
     * guarantee that costs one comparison is worth keeping even where no gym
     * input can observe it. What this test defends is the comparison's
     * continued existence, not a number any lifter will ever see.
     */
    @Test
    fun `a flat block returns the stated load unchanged rather than recomputing it`() {
        // Not representative and not meant to be: the identity holds for every
        // pair a gym produces, so only a pair a gym cannot produce can red.
        val declared = 325.46723651992687
        val stated = 36.21814333377138
        assertEquals(
            stated,
            SetLoadPolicy.standingStatedAddedKg(
                statedAddedKg = stated,
                sameExerciseBlock = true,
                lastDeclaredAddedKg = declared,
                nextDeclaredAddedKg = declared,
                bodyweight = false,
                finishedWarmup = false,
                nextWarmup = false,
            ),
        )
    }

    /**
     * Nothing stated is still nothing carried, whatever the declarations step
     * to. An offset needs a statement to be an offset FROM, and inventing one
     * from the plan's own step would put the block's next number in the box as
     * though the lifter had typed it.
     */
    @Test
    fun `a stepping block with no statement carries nothing`() {
        assertNull(
            SetLoadPolicy.standingStatedAddedKg(
                statedAddedKg = null,
                sameExerciseBlock = true,
                lastDeclaredAddedKg = 45.0,
                nextDeclaredAddedKg = 55.0,
                bodyweight = false,
                finishedWarmup = false,
                nextWarmup = false,
            ),
        )
    }

    /**
     * A lifter who states exactly what the plan declared has corrected nothing.
     * The offset is zero and the next set is offered the plan's own step
     * untouched. Its own case because it is the one input on which the offset
     * rule and the absolute rule it replaces disagree about nothing while the
     * declarations still step.
     */
    @Test
    fun `a statement equal to the declaration leaves the plan's step alone`() {
        assertEquals(
            55.0,
            SetLoadPolicy.standingStatedAddedKg(
                statedAddedKg = 45.0,
                sameExerciseBlock = true,
                lastDeclaredAddedKg = 45.0,
                nextDeclaredAddedKg = 55.0,
                bodyweight = false,
                finishedWarmup = false,
                nextWarmup = false,
            ),
        )
    }

    /**
     * RED before the fix, #143 round 2. A warm-up opener declared 20 kg; the
     * rack only has plates for 22.5, so the lifter states that. The first
     * working set is declared 60 -- a designed jump, not a step -- and must
     * not be offered 62.5: the opener's own rounding is not a distance the
     * working weight should move by. Null is offered here rather than
     * asserting 60 directly so this pin is about the CARRY, not about
     * `seedAddedKg`'s separate answer for what fills the box when nothing
     * carries.
     */
    @Test
    fun `a warm-up opener's correction does not shift the working set it steps to`() {
        assertNull(
            SetLoadPolicy.standingStatedAddedKg(
                statedAddedKg = 22.5,
                sameExerciseBlock = true,
                lastDeclaredAddedKg = 20.0,
                nextDeclaredAddedKg = 60.0,
                bodyweight = false,
                finishedWarmup = true,
                nextWarmup = false,
            ),
        )
    }

    /**
     * Green before the fix and after it, characterizing the guard's scope. A
     * warm-up opener declared the SAME load as the working set that follows
     * it -- an activation set at working weight, marked warmup for RPE
     * purposes rather than for a lighter load -- has corrected nothing to
     * exclude: the equal-declaration branch above already carries the
     * statement through untouched, before the warmup check is ever reached.
     */
    @Test
    fun `a warm-up opener declared at the working weight still carries its correction`() {
        assertEquals(
            62.5,
            SetLoadPolicy.standingStatedAddedKg(
                statedAddedKg = 62.5,
                sameExerciseBlock = true,
                lastDeclaredAddedKg = 60.0,
                nextDeclaredAddedKg = 60.0,
                bodyweight = false,
                finishedWarmup = true,
                nextWarmup = false,
            ),
        )
    }

    /**
     * Green before the fix and after it, characterizing the guard's scope.
     * 72b991b3 left [SetLoadPolicy.standingStatedAddedKg]'s finishedWarmup
     * and nextWarmup parameters unread, so 32.0 was already returned before
     * the guard existed; the guard is scoped to the warm-up-TO-working
     * transition specifically, not to "a warm-up is involved" -- a ramp of
     * two warm-up sets before the working weight is a progression in exactly
     * the sense 45 / 55 / 65 is, and a correction stated on the first warm-up
     * still steps onto the second one.
     */
    @Test
    fun `a correction still steps from one warm-up to the next`() {
        assertEquals(
            32.0,
            SetLoadPolicy.standingStatedAddedKg(
                statedAddedKg = 22.0,
                sameExerciseBlock = true,
                lastDeclaredAddedKg = 20.0,
                nextDeclaredAddedKg = 30.0,
                bodyweight = false,
                finishedWarmup = true,
                nextWarmup = true,
            ),
        )
    }
}
