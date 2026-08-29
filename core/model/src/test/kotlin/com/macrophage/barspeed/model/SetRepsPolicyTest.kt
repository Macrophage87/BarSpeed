package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [SetRepsPolicy] as it stands on arrival: the bake it already performs, and
 * the carry it does not.
 *
 * Every pin here is green at the commit that adds it. The carry differentials
 * live in [SetRepsCarryTest] and are red until the fix.
 */
class SetRepsPolicyTest {
    @Test
    fun `an untouched box carries the plan's declared rep count unchanged`() {
        assertEquals(8, SetRepsPolicy.carriedIntoNextSet(declaredReps = 8, statedReps = null))
    }

    @Test
    fun `a stated rep count is what the next set carries`() {
        assertEquals(10, SetRepsPolicy.carriedIntoNextSet(declaredReps = 8, statedReps = 10))
    }

    /**
     * A slot the plan declared no rep count for -- a hold, or a set written to
     * failure -- carries none rather than a fabricated zero.
     */
    @Test
    fun `a countless slot carries no rep count`() {
        assertNull(SetRepsPolicy.carriedIntoNextSet(declaredReps = null, statedReps = null))
    }

    @Test
    fun `an untouched box carries the plan's declared hold unchanged`() {
        assertEquals(45, SetRepsPolicy.carriedDurationIntoNextSet(declaredDurationS = 45, statedDurationS = null))
    }

    @Test
    fun `a stated hold is what the next set carries`() {
        assertEquals(30, SetRepsPolicy.carriedDurationIntoNextSet(declaredDurationS = 45, statedDurationS = 30))
    }

    /**
     * Shipped behaviour, written down: a rep count typed for one set dies with
     * that set. #174. Replaced by [SetRepsCarryTest] once the carry lands.
     */
    @Test
    fun `today a stated rep count does not stand for the next set`() {
        assertNull(
            SetRepsPolicy.standingStatedReps(
                statedReps = 6,
                sameExerciseBlock = true,
                lastDeclaredReps = 8,
                nextDeclaredReps = 8,
            ),
        )
    }

    /** The same, one target over: a shortened hold is shortened for one set. */
    @Test
    fun `today a stated hold does not stand for the next set`() {
        assertNull(
            SetRepsPolicy.standingStatedDurationS(
                statedDurationS = 30,
                sameExerciseBlock = true,
                lastDeclaredDurationS = 45,
                nextDeclaredDurationS = 45,
            ),
        )
    }
}
