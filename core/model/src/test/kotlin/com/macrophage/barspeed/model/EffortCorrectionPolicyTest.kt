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
 */
class EffortCorrectionPolicyTest {
    @Test
    fun `a warm-up lights the warm-up tile and nothing else`() {
        val s = EffortCorrectionPolicy.selection(rpe = null, warmup = true, tappedFailed = false, derivedFailed = false)
        assertTrue(s.warmup)
        assertFalse(s.failed)
        assertNull(s.rpe)
    }

    @Test
    fun `a warm-up outranks a shortfall derived from the count`() {
        // The warm-up branch is first in the chain today and stays first: a set
        // declared not-work is not a set that fell short of a target.
        val s = EffortCorrectionPolicy.selection(rpe = null, warmup = true, tappedFailed = false, derivedFailed = true)
        assertTrue(s.warmup)
        assertFalse(s.failed)
    }

    @Test
    fun `the lifter's own failure tap lights the failed tile`() {
        val s = EffortCorrectionPolicy.selection(rpe = null, warmup = false, tappedFailed = true, derivedFailed = false)
        assertTrue(s.failed)
        assertFalse(s.warmup)
        assertNull(s.rpe)
    }

    @Test
    fun `a plain rated set lights its own RPE`() {
        val s = EffortCorrectionPolicy.selection(rpe = 8, warmup = false, tappedFailed = false, derivedFailed = false)
        assertEquals(8, s.rpe)
        assertFalse(s.failed)
        assertFalse(s.warmup)
    }

    @Test
    fun `an unrated set that met its target lights nothing`() {
        val s =
            EffortCorrectionPolicy.selection(rpe = null, warmup = false, tappedFailed = false, derivedFailed = false)
        assertFalse(s.warmup)
        assertFalse(s.failed)
        assertNull(s.rpe)
        assertFalse(s.derivedShortfall)
    }

    @Test
    fun `a shortfall the lifter never said is a derived shortfall`() {
        val s = EffortCorrectionPolicy.selection(rpe = null, warmup = false, tappedFailed = false, derivedFailed = true)
        assertTrue(s.derivedShortfall)
    }

    @Test
    fun `a failure the lifter tapped is not a derived shortfall`() {
        // The grid must not tell the lifter their own verdict was derived, so
        // the tap wins the attribution wherever both are true.
        listOf(false, true).forEach { derived ->
            val s =
                EffortCorrectionPolicy.selection(
                    rpe = null,
                    warmup = false,
                    tappedFailed = true,
                    derivedFailed = derived,
                )
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
    val warmup: Boolean,
    val tappedFailed: Boolean,
    val derivedFailed: Boolean,
) {
    fun select(): EffortSelection = EffortCorrectionPolicy.selection(rpe, warmup, tappedFailed, derivedFailed)
}

internal fun allInputs(): List<EffortInput> = buildList {
    listOf(null, 6, 7, 8, 9, 10).forEach { rpe ->
        listOf(false, true).forEach { warmup ->
            listOf(false, true).forEach { tapped ->
                listOf(false, true).forEach { derived ->
                    add(EffortInput(rpe, warmup, tapped, derived))
                }
            }
        }
    }
}
