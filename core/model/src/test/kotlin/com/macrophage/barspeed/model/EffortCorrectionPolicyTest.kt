package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the rest screen's correction grid pre-lights.
 *
 * These are the cases the grid already got right before issue #140. The two it
 * got wrong get their own differentials, in their own commit, so the red is a
 * durable artifact rather than a claim.
 *
 * Two warm-up cases used to sit at the top of this class and are DELETED by
 * #187 rather than reworded: the grid has no warm-up tile to pre-light, the
 * policy takes no warm-up argument, and a test asserting what happens when one
 * is passed would be asserting about a state the app can no longer construct.
 * A warm-up set now carries an ordinary rating, so it lights whichever rung
 * the lifter tapped and is covered by `a plain rated set lights its own RPE`
 * like any other set.
 */
class EffortCorrectionPolicyTest {
    @Test
    fun `the lifter's own failure tap lights the failed tile`() {
        val s = EffortCorrectionPolicy.selection(rpe = null, tappedFailed = true, derivedFailed = false)
        assertTrue(s.failed)
        assertNull(s.rpe)
    }

    @Test
    fun `a plain rated set lights its own RPE`() {
        val s = EffortCorrectionPolicy.selection(rpe = 8, tappedFailed = false, derivedFailed = false)
        assertEquals(8, s.rpe)
        assertFalse(s.failed)
    }

    @Test
    fun `an unrated set that met its target lights nothing`() {
        val s = EffortCorrectionPolicy.selection(rpe = null, tappedFailed = false, derivedFailed = false)
        assertFalse(s.failed)
        assertNull(s.rpe)
        assertFalse(s.derivedShortfall)
    }

    @Test
    fun `a shortfall the lifter never said is a derived shortfall`() {
        val s = EffortCorrectionPolicy.selection(rpe = null, tappedFailed = false, derivedFailed = true)
        assertTrue(s.derivedShortfall)
    }

    @Test
    fun `a failure the lifter tapped is not a derived shortfall`() {
        // The grid must not tell the lifter their own verdict was derived, so
        // the tap wins the attribution wherever both are true.
        listOf(false, true).forEach { derived ->
            val s = EffortCorrectionPolicy.selection(rpe = null, tappedFailed = true, derivedFailed = derived)
            assertFalse(s.derivedShortfall, "tappedFailed=true derivedFailed=$derived")
        }
    }

    @Test
    fun `a set that met its target never reports a shortfall`() {
        allInputs().filter { !it.derivedFailed }.forEach { i ->
            assertFalse(i.select().derivedShortfall, i.toString())
        }
    }
}

/** Every input the grid can be asked about, so a rule cannot be pinned only where it is convenient. */
internal data class EffortInput(
    val rpe: Int?,
    val tappedFailed: Boolean,
    val derivedFailed: Boolean,
) {
    fun select(): EffortSelection = EffortCorrectionPolicy.selection(rpe, tappedFailed, derivedFailed)
}

internal fun allInputs(): List<EffortInput> = buildList {
    // Every anchor of the scale, not a range: 2, 3 and 5 are valid in the
    // column and carry no tile, so no rating the app writes can be one.
    listOf(null, 1, 4, 6, 7, 8, 9, 10).forEach { rpe ->
        listOf(false, true).forEach { tapped ->
            listOf(false, true).forEach { derived ->
                add(EffortInput(rpe, tapped, derived))
            }
        }
    }
}
