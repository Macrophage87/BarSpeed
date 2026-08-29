package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The differentials for #174: a rep count and a hold the lifter states hold for
 * the rest of the exercise block, bounded by the same four rules the load carry
 * is bounded by.
 *
 * RED at the commit that adds this file. [SetRepsPolicy.standingStatedReps] and
 * its hold twin return null unconditionally there, which is what ships today --
 * "while adjustments to the weight stay between sets, changing reps does not".
 * Six of the ten tests below assert a value and fail; the four that assert null
 * pass at both ends by construction, and are here because a boundary nobody
 * states is a boundary the fix can drop. Which is which is named in each
 * KDoc.
 *
 * The block boundary is [SetLoadPolicy.sameExerciseBlock]'s answer, passed in.
 * It is not re-tested here -- SetLoadPolicyTest owns it, over six cases -- and
 * that is the point of it being a parameter.
 */
class SetRepsCarryTest {
    /**
     * RED before the fix. The reported case: a lifter who drops a set from 8 to
     * 6 because the bar was heavier than the plan thought should not have to
     * say so again on every remaining set, when the same lifter changing the
     * load says it once.
     */
    @Test
    fun `a stated rep count stands for the rest of the exercise block`() {
        assertEquals(
            6,
            SetRepsPolicy.standingStatedReps(
                statedReps = 6,
                sameExerciseBlock = true,
                lastDeclaredReps = 8,
                nextDeclaredReps = 8,
            ),
        )
    }

    /**
     * RED before the fix, and the trap #174 names: a descending scheme must not
     * flatten. The plan writes 10 / 8 / 6; the lifter changes set one to 12;
     * set two is still 8, because the plan declares a DIFFERENT count next and
     * a plan that prescribes a change wins over a standing statement.
     *
     * Green before the fix too as an assertion -- nothing carries today, so
     * nothing flattens today -- and kept because it is the rule the carry is
     * most likely to be written without. Marked as what it is rather than
     * claimed as a differential.
     */
    @Test
    fun `a descending scheme keeps its own numbers after set one is changed`() {
        assertNull(
            SetRepsPolicy.standingStatedReps(
                statedReps = 12,
                sameExerciseBlock = true,
                lastDeclaredReps = 10,
                nextDeclaredReps = 8,
            ),
        )
    }

    /**
     * RED before the fix. The other half of the descending scheme: 10 / 8 / 6
     * with set TWO changed from 8 to 9 carries into set three only if the plan
     * declares the same count for both -- it does not, so this pair drops, and
     * the pair that does carry is a repeated one. Written as the 8 / 8 tail of
     * a 10 / 8 / 8 block, which is the shape a carry has to survive.
     */
    @Test
    fun `a repeated count later in a mixed scheme still carries`() {
        assertEquals(
            9,
            SetRepsPolicy.standingStatedReps(
                statedReps = 9,
                sameExerciseBlock = true,
                lastDeclaredReps = 8,
                nextDeclaredReps = 8,
            ),
        )
    }

    /**
     * RED before the fix. Direction-agnostic, exactly as the load carry is: a
     * lifter adding reps has said the same kind of thing as one dropping them.
     */
    @Test
    fun `a stated rep count that goes up carries the same as one that goes down`() {
        assertEquals(
            15,
            SetRepsPolicy.standingStatedReps(
                statedReps = 15,
                sameExerciseBlock = true,
                lastDeclaredReps = 12,
                nextDeclaredReps = 12,
            ),
        )
    }

    /**
     * Green before the fix and after it. The statement ends with the block; the
     * next exercise is offered its own prescription.
     */
    @Test
    fun `standingStatedReps drops a statement at the exercise boundary`() {
        assertNull(
            SetRepsPolicy.standingStatedReps(
                statedReps = 6,
                sameExerciseBlock = false,
                lastDeclaredReps = 8,
                nextDeclaredReps = 8,
            ),
        )
    }

    /**
     * Green before the fix and after it. #124's fourth boundary, inherited: a
     * count declared on one side of the pair and absent on the other is not
     * "the same prescription".
     */
    @Test
    fun `standingStatedReps drops a statement where only one of the two sets declares a count`() {
        assertNull(
            SetRepsPolicy.standingStatedReps(
                statedReps = 6,
                sameExerciseBlock = true,
                lastDeclaredReps = 8,
                nextDeclaredReps = null,
            ),
        )
        assertNull(
            SetRepsPolicy.standingStatedReps(
                statedReps = 6,
                sameExerciseBlock = true,
                lastDeclaredReps = null,
                nextDeclaredReps = 8,
            ),
        )
    }

    /**
     * RED before the fix. Two undeclared counts are the same prescription, so a
     * block written without rep targets -- sets to failure, or an AMRAP tail --
     * keeps the number the lifter supplied. Nothing else offers them one.
     */
    @Test
    fun `standingStatedReps carries across a block that declares no count on either set`() {
        assertEquals(
            5,
            SetRepsPolicy.standingStatedReps(
                statedReps = 5,
                sameExerciseBlock = true,
                lastDeclaredReps = null,
                nextDeclaredReps = null,
            ),
        )
    }

    /** Green before the fix and after it: nothing stated is nothing to carry. */
    @Test
    fun `standingStatedReps has nothing to carry when the lifter said nothing`() {
        assertNull(
            SetRepsPolicy.standingStatedReps(
                statedReps = null,
                sameExerciseBlock = true,
                lastDeclaredReps = 8,
                nextDeclaredReps = 8,
            ),
        )
    }

    /**
     * RED before the fix. The hold has the identical shape and the identical
     * complaint: a lifter who cuts a 45 s plank to 30 has said something about
     * the exercise, not about one set of it.
     */
    @Test
    fun `a stated hold stands for the rest of the exercise block`() {
        assertEquals(
            30,
            SetRepsPolicy.standingStatedDurationS(
                statedDurationS = 30,
                sameExerciseBlock = true,
                lastDeclaredDurationS = 45,
                nextDeclaredDurationS = 45,
            ),
        )
    }

    /**
     * RED before the fix. A hold block that ramps -- 30 / 45 / 60 -- keeps its
     * own seconds when the opener is changed, the descending-scheme rule one
     * target over.
     */
    @Test
    fun `a ramping hold keeps its own seconds after the first one is changed`() {
        assertNull(
            SetRepsPolicy.standingStatedDurationS(
                statedDurationS = 20,
                sameExerciseBlock = true,
                lastDeclaredDurationS = 30,
                nextDeclaredDurationS = 45,
            ),
        )
        assertEquals(
            20,
            SetRepsPolicy.standingStatedDurationS(
                statedDurationS = 20,
                sameExerciseBlock = true,
                lastDeclaredDurationS = 30,
                nextDeclaredDurationS = 30,
            ),
        )
    }
}
