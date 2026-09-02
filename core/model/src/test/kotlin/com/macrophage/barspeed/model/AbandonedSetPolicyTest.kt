package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins for what a set that never entered its work phase may publish (#216).
 *
 * Green when written: the policy arrives with them and nothing calls it yet.
 * They are here so the decision is fixed before either export writer is
 * pointed at it, and so the three-state rule below cannot be simplified into
 * a two-state one by a later reader who sees only booleans.
 */
class AbandonedSetPolicyTest {
    @Test
    fun `a set with no prep has begun its work at the tap`() {
        assertTrue(AbandonedSetPolicy.workBegan(PrepCase.NONE, workStartedAtMs = null))
        assertTrue(AbandonedSetPolicy.workBegan(PrepCase.NONE, workStartedAtMs = 42L))
    }

    @Test
    fun `a prepped set has begun its work exactly when it has an instant`() {
        for (case in listOf(PrepCase.TIMED, PrepCase.CUED)) {
            assertTrue(AbandonedSetPolicy.workBegan(case, workStartedAtMs = 1L), "$case with an instant")
            assertFalse(AbandonedSetPolicy.workBegan(case, workStartedAtMs = null), "$case with no instant")
        }
    }

    /**
     * The case the issue exists for: field-37 set 13's stored shape.
     *
     * duration 0, prep 12, work never begun. Both figures are withheld and the
     * document says why instead.
     */
    @Test
    fun `a set abandoned in its prep publishes neither figure and says so`() {
        val phase = AbandonedSetPolicy.published(workBegan = false, actualDurationS = 0, prepS = 12)
        assertNull(phase.durationS, "a duration nobody measured was published")
        assertNull(phase.prepS, "a prep that never finished was published")
        assertTrue(phase.abandonedInPrep, "the document does not say the set never started")
    }

    @Test
    fun `a set that did its work publishes both figures and claims no abandonment`() {
        val phase = AbandonedSetPolicy.published(workBegan = true, actualDurationS = 20, prepS = 12)
        assertEquals(20, phase.durationS)
        assertEquals(12, phase.prepS)
        assertFalse(phase.abandonedInPrep)
    }

    /**
     * The third state, and the one a two-way reading destroys.
     *
     * Null is a row written before the app recorded this. Every timed set in
     * the lifter's history is in that state, and treating it as "the work
     * never began" would strike duration_s off all of them at once.
     */
    @Test
    fun `a row that predates the column publishes exactly what it always did`() {
        val phase = AbandonedSetPolicy.published(workBegan = null, actualDurationS = 0, prepS = 12)
        assertEquals(0, phase.durationS, "a legacy row's stored duration was withheld")
        assertEquals(12, phase.prepS, "a legacy row's stored prep was withheld")
        assertFalse(phase.abandonedInPrep, "a legacy row was declared abandoned on no evidence")
    }

    /**
     * Withholding is driven by the phase and never by the figures.
     *
     * A completed set really can hold zero seconds -- a hold corrected to 0 on
     * the rest screen -- and a set that never began really can carry a
     * non-zero stored duration if a later build changes what it freezes. The
     * rule must not become "hide the zeros".
     */
    @Test
    fun `the stored figures never decide whether they are published`() {
        assertEquals(0, AbandonedSetPolicy.published(true, 0, 0).durationS, "a measured zero was hidden")
        assertEquals(0, AbandonedSetPolicy.published(true, 0, 0).prepS, "a measured zero prep was hidden")
        assertNull(AbandonedSetPolicy.published(false, 9, 9).durationS, "an abandoned set published a duration")
        assertNull(AbandonedSetPolicy.published(false, 9, 9).prepS, "an abandoned set published a prep")
    }
}
