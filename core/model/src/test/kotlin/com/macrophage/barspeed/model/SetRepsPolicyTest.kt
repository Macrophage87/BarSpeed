package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [SetRepsPolicy]'s bake: which of the plan's numbers a statement displaces
 * when the lifter taps through to the next set.
 *
 * How long a statement HOLDS is [SetRepsCarryTest]'s, and the two pins that
 * used to sit here saying it holds for one set only have been deleted rather
 * than reworded: they characterized the pre-#174 behaviour, that file demands
 * the opposite, and a reworded false claim is how this repository has produced
 * fresh ones.
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
}
