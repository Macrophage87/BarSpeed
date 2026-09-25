package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The measured rest and the one comparison drawn from it (#157).
 *
 * Green pins on a new symbol with no caller yet; each pin's strength is shown
 * by a mutation run. The owner, 2026-09-25: "I consider rests a minimum. If it
 * takes more time to setup I do." and "With a gym that a lot of people are
 * using, timing can't easily be predicted in advance".
 */
class RestMeasurePolicyTest {
    private val restStart = 1_700_000_000_000L

    @Test
    fun `a rest is the seconds from its start to the next START`() {
        assertEquals(150.0, RestMeasurePolicy.measuredS(restStart, restStart + 150_000))
        assertEquals(212.3, RestMeasurePolicy.measuredS(restStart, restStart + 212_300))
    }

    @Test
    fun `a rest is rounded to the nearest tenth, half up`() {
        assertEquals(120.0, RestMeasurePolicy.measuredS(restStart, restStart + 120_049))
        assertEquals(120.1, RestMeasurePolicy.measuredS(restStart, restStart + 120_050))
        assertEquals(120.1, RestMeasurePolicy.measuredS(restStart, restStart + 120_149))
    }

    @Test
    fun `a rest missing either instant is absent, not zero`() {
        assertNull(RestMeasurePolicy.measuredS(null, restStart), "no stored rest-start")
        assertNull(RestMeasurePolicy.measuredS(restStart, null), "the last set: no START followed")
        assertNull(RestMeasurePolicy.measuredS(null, null))
    }

    @Test
    fun `a next START before the rest-start is absent, never negative`() {
        assertNull(RestMeasurePolicy.measuredS(restStart, restStart - 1))
        assertNull(RestMeasurePolicy.measuredS(restStart, restStart - 90_000))
    }

    @Test
    fun `equal instants are a measured zero, not an absence`() {
        assertEquals(0.0, RestMeasurePolicy.measuredS(restStart, restStart))
    }

    @Test
    fun `a rest shorter than the prescription is short of it`() {
        assertTrue(RestMeasurePolicy.shortOfPrescribed(149.9, 150))
        assertTrue(RestMeasurePolicy.shortOfPrescribed(90.0, 150))
    }

    @Test
    fun `a rest exactly at the prescription met the minimum`() {
        assertFalse(RestMeasurePolicy.shortOfPrescribed(150.0, 150))
    }

    /** The owner: a rest is a minimum, and setup and gym traffic take what they take. */
    @Test
    fun `a rest longer than the prescription is never a deviation`() {
        assertFalse(RestMeasurePolicy.shortOfPrescribed(150.1, 150))
        assertFalse(RestMeasurePolicy.shortOfPrescribed(600.0, 150))
    }

    @Test
    fun `an unmeasured or unprescribed rest is not short`() {
        assertFalse(RestMeasurePolicy.shortOfPrescribed(null, 150), "no measurement")
        assertFalse(RestMeasurePolicy.shortOfPrescribed(10.0, null), "the plan named no rest")
        assertFalse(RestMeasurePolicy.shortOfPrescribed(null, null))
    }
}
