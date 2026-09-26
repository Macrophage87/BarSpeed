package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The history card's count chip names whose count the set's figure is (#325).
 *
 * The inputs are built through [RepsSourcePolicy.published] from the row's own
 * columns, so each case is the row a real set leaves behind, not a word
 * picked to suit the assertion.
 */
class RepsCountChipTest {
    private fun chipFor(liveReps: Int?, repsManual: Boolean, timed: Boolean = false, guided: Boolean = false) =
        RepsCountChip.label(RepsSourcePolicy.published(liveReps, repsManual, timed, guided))

    /** GUARD, green before and after: the lifter's own tally is a manual count. */
    @Test
    fun `a tapped set reads manual`() {
        assertEquals("MANUAL COUNT", chipFor(liveReps = null, repsManual = true))
    }

    /** GUARD, green before and after: nothing counted reps on a hold, so no count chip. */
    @Test
    fun `a timed set draws no count chip`() {
        assertNull(chipFor(liveReps = null, repsManual = true, timed = true))
        assertNull(chipFor(liveReps = null, repsManual = false, timed = true))
    }
}
