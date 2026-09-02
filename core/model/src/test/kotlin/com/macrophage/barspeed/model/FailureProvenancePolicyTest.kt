package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins for naming the author of a failure (#216, #169).
 *
 * Green when written; nothing calls the policy yet.
 */
class FailureProvenancePolicyTest {
    @Test
    fun `a set the lifter called failed says so`() {
        assertEquals(true, FailureProvenancePolicy.published(failed = true, failedByLifter = true))
    }

    /**
     * The published `false`, which is the whole point of the key.
     *
     * A set the app derived a failure for -- short of its prescription, or
     * ended during its lead-in -- and that the lifter never called failed.
     * Until this key existed that set was indistinguishable from a tapped one.
     */
    @Test
    fun `a derived failure says the lifter did not call it`() {
        assertEquals(false, FailureProvenancePolicy.published(failed = true, failedByLifter = false))
    }

    @Test
    fun `a set that did not fail names no author`() {
        assertNull(FailureProvenancePolicy.published(failed = false, failedByLifter = false))
        assertNull(FailureProvenancePolicy.published(failed = false, failedByLifter = true))
        assertNull(FailureProvenancePolicy.published(failed = false, failedByLifter = null))
    }

    /**
     * A row that predates the column names no author either, and that absence
     * is permanent: the tap lived in the rest screen's memory and was
     * discarded, so there is nothing to backfill from.
     */
    @Test
    fun `a row that predates the column names no author`() {
        assertNull(FailureProvenancePolicy.published(failed = true, failedByLifter = null))
    }
}
