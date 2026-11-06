package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The once-only announcement of a shared plan (UX review 2026-11-06, plans F1). */
class ArrivalClaimTest {
    @Test
    fun `nothing has arrived in a new process, so there is nothing to claim`() {
        assertFalse(ArrivalClaim().claim())
    }

    @Test
    fun `an arrival is claimed exactly once`() {
        val claim = ArrivalClaim()
        claim.arrive()
        assertTrue(claim.claim())
        assertFalse(claim.claim())
    }

    @Test
    fun `every later arrival is claimable again, however many came before`() {
        // The defect this replaces: a saved "handled" count that outlived the
        // process made every share after a process death look already handled.
        val claim = ArrivalClaim()
        repeat(3) {
            claim.arrive()
            assertTrue(claim.claim())
        }
    }

    @Test
    fun `arrivals before one claim are one announcement`() {
        val claim = ArrivalClaim()
        claim.arrive()
        claim.arrive()
        assertTrue(claim.claim())
        assertFalse(claim.claim())
    }
}
