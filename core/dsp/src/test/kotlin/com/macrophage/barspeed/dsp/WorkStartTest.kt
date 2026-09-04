package com.macrophage.barspeed.dsp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [WorkStart] on its own, before anything reads it.
 *
 * These are GREEN pins on a type that did not exist before the commit that adds
 * it, so they are not differentials and nothing here could have been shown
 * failing first. What IS shown failing is the figures the rule moves, in
 * `PrepDetectionFieldTest`.
 *
 * Constructed instants rather than a capture: this file is about the predicate
 * and its boundary, and a capture would decide those cases only by accident.
 * The captures are read where the rule's effect is measured.
 */
class WorkStartTest {
    private val at = 1_788_516_183_953L

    @Test
    fun `an instant makes the bound known and a null does not`() {
        assertEquals(WorkStart.Known(at), WorkStart.of(at))
        assertEquals(WorkStart.Unknown, WorkStart.of(null))
    }

    @Test
    fun `a drive that finished before the work began is outside the set`() {
        assertFalse(WorkStart.Known(at).withinSet(at - 1), "one millisecond early is early")
        assertFalse(WorkStart.Known(at).withinSet(at - 8_630), "set 5's earliest detection")
    }

    /**
     * The boundary case, inclusive, and the mirror of
     * [SetEnd.startedWithinSet]'s own inclusive boundary: a drive still under
     * way when the work began was not finished before it.
     */
    @Test
    fun `a drive ending on the instant itself is inside the set`() {
        assertTrue(WorkStart.Known(at).withinSet(at), "ending on the instant is not ending before it")
        assertTrue(WorkStart.Known(at).withinSet(at + 1))
    }

    /**
     * The straddling drive, kept, which is the whole of the argument for
     * bounding on the drive's END rather than its start.
     *
     * The instants are field-38 set 5's own fourth detection, whose drive began
     * 2.478 s before the cadence's first stroke call was due and ended 0.494 s
     * after it.
     */
    @Test
    fun `a drive begun before the work and still running when it began is kept`() {
        assertTrue(WorkStart.Known(at).withinSet(at + 494), "drive ended 494 ms after work began")
    }

    @Test
    fun `an unknown instant bounds nothing and counts nothing`() {
        assertTrue(WorkStart.Unknown.withinSet(0L), "no instant, no exclusion")
        assertNull(WorkStart.Unknown.detectionsBefore(listOf(0L, 1L, 2L)), "no instant, no count")
    }

    /**
     * Zero and null are different answers, which is the reason this returns
     * `Int?` at all.
     */
    @Test
    fun `a known instant with nothing before it answers zero, not null`() {
        assertEquals(0, WorkStart.Known(at).detectionsBefore(listOf(at, at + 1)))
        assertEquals(2, WorkStart.Known(at).detectionsBefore(listOf(at - 2, at - 1, at, at + 1)))
        assertEquals(0, WorkStart.Known(at).detectionsBefore(emptyList()))
    }
}
