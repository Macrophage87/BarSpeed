package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What takes START's slot on the rest screen (UX review 2026-11-06, rest F6).
 *
 * After the last planned set START is withheld (#195), and FINISH SESSION sat
 * below the limiter page, the "Great work!" card and the add/remove-set
 * controls, so the lifter scrolled for the only thing left to do. With START
 * gone there is nothing to stack against, so the finish takes its slot.
 */
class RestStartSlotTest {
    private fun slot(
        close: SessionCloseState = SessionCloseState.NONE,
        askedToFinish: Boolean = false,
        hasNextSlot: Boolean = true,
        adHoc: Boolean = false,
    ) = RestControlPolicy.startSlot(RestControlPolicy.restScreen(close, askedToFinish, hasNextSlot, adHoc))

    @Test
    fun `start holds the slot whenever there is a set to start`() {
        assertEquals(RestControl.START_NEXT_SET, slot())
        assertEquals(RestControl.START_NEXT_SET, slot(askedToFinish = true))
        assertEquals(RestControl.START_NEXT_SET, slot(hasNextSlot = false, adHoc = true))
        assertEquals(RestControl.START_NEXT_SET, slot(close = SessionCloseState.FAILED))
    }

    @Test
    fun `after the last planned set the finish takes start's slot`() {
        assertEquals(RestControl.FINISH_SESSION, slot(hasNextSlot = false))
    }

    @Test
    fun `and the rating it asks for appears in the same place`() {
        assertEquals(RestControl.RATE_SESSION, slot(hasNextSlot = false, askedToFinish = true))
    }

    @Test
    fun `a failed close keeps its retry where it was`() {
        assertNull(slot(close = SessionCloseState.REST_UNSAVED, hasNextSlot = false))
    }

    @Test
    fun `an in-flight close leaves the slot empty`() {
        assertNull(slot(close = SessionCloseState.IN_FLIGHT))
        assertNull(slot(close = SessionCloseState.IN_FLIGHT, hasNextSlot = false))
    }
}
