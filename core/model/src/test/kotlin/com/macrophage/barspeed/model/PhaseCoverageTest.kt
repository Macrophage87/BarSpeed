package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The one coverage rule the rest screen's eccentric card and the tempo chip's
 * note both read (#89, #230). Green when written: the rule is new, and the
 * reds that show each surface ignoring it are in their own tests.
 */
class PhaseCoverageTest {
    @Test
    fun `a gap is counted and stated`() {
        val c = PhaseCoverage(measured = 5, of = 7)
        assertEquals(2, c.notMeasured)
        assertFalse(c.complete)
        assertEquals("2 not measured", c.notMeasuredClause)
    }

    @Test
    fun `every rep measured states no gap`() {
        val c = PhaseCoverage(measured = 7, of = 7)
        assertEquals(0, c.notMeasured)
        assertTrue(c.complete)
        assertNull(c.notMeasuredClause)
    }

    @Test
    fun `nothing to measure is not a gap`() {
        assertTrue(PhaseCoverage(measured = 0, of = 0).complete)
    }

    @Test
    fun `nothing measured out of some is the whole set missing`() {
        val c = PhaseCoverage(measured = 0, of = 4)
        assertEquals(4, c.notMeasured)
        assertEquals("4 not measured", c.notMeasuredClause)
    }

    /**
     * A stored analysis written under an older counting rule can carry more
     * reps on a phase than on the set. The history screen draws it; a
     * negative gap would print "-1 not measured".
     */
    @Test
    fun `a phase count above the set's is never a negative gap`() {
        val c = PhaseCoverage(measured = 8, of = 7)
        assertEquals(0, c.notMeasured)
        assertTrue(c.complete)
        assertNull(c.notMeasuredClause)
    }
}
