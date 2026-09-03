package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.VoiceCue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [RollExcursion]'s two decisions, on signals small enough to check by hand
 * (#133).
 *
 * The field captures that motivated it are asserted separately, in
 * [RollExcursionFieldTest]; this file exists so a failure says WHICH rule
 * broke rather than that a 6,752-row capture moved by some amount.
 *
 * Nothing here runs a sensor. Every roll value is written down by this file.
 */
class RollExcursionTest {
    private fun samples(vararg rollAtMs: Pair<Long, Double>): List<ImuSample> =
        rollAtMs.map { (t, roll) -> ImuSample(t, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, roll, 0.0, 0.0) }

    private fun cuedAt(atMs: Long) = SetEnd.of(listOf(VoiceCue(atMs, SetEnd.DONE)))

    // ---- unwrap ------------------------------------------------------------

    @Test
    fun `a signal that never crosses the boundary is returned unchanged`() {
        val rolls = listOf(-30.0, 0.0, 45.0, 120.0, 10.0)
        assertEquals(rolls, RollExcursion.unwrap(rolls))
    }

    @Test
    fun `a crossing from just under 180 to just over -180 continues upward`() {
        assertEquals(
            listOf(179.0, 181.0),
            RollExcursion.unwrap(listOf(179.0, -179.0)),
            "the mount turned 2 degrees, not 358 the other way",
        )
    }

    @Test
    fun `a crossing the other way continues downward`() {
        assertEquals(listOf(-179.0, -181.0), RollExcursion.unwrap(listOf(-179.0, 179.0)))
    }

    /**
     * The saturation this type exists for, in miniature: three full turns in
     * one direction read as 360 wrapped and 1080 unwrapped.
     */
    @Test
    fun `three turns in one direction come out as three turns`() {
        val turning = (0..36).map { step -> ((step * 30.0 + 180.0) % 360.0) - 180.0 }
        val wrapped = turning.max() - turning.min()
        val unwrapped = RollExcursion.unwrap(turning)
        assertTrue(wrapped <= 360.0, "a bounded signal cannot exceed 360, and this one reported $wrapped")
        assertEquals(1080.0, unwrapped.max() - unwrapped.min(), 1e-9)
    }

    @Test
    fun `an empty signal unwraps to an empty signal`() {
        assertEquals(emptyList(), RollExcursion.unwrap(emptyList<Double>()))
    }

    @Test
    fun `a step of exactly half a turn is not read as a crossing`() {
        assertEquals(listOf(0.0, 180.0), RollExcursion.unwrap(listOf(0.0, 180.0)))
    }

    // ---- the window --------------------------------------------------------

    @Test
    fun `samples before the work started are outside the set`() {
        val measured =
            RollExcursion.of(
                samples(100L to 0.0, 200L to 90.0, 300L to 10.0, 400L to 20.0),
                workStartedAtMs = 300L,
                end = SetEnd.NotCued,
            )
        assertEquals(10.0, measured?.degrees, "the 90-degree prep swing is not the set")
    }

    @Test
    fun `samples after the terminal cue are outside the set`() {
        val measured =
            RollExcursion.of(
                samples(100L to 0.0, 200L to 10.0, 300L to 170.0),
                workStartedAtMs = null,
                end = cuedAt(200L),
            )
        assertEquals(10.0, measured?.degrees, "the re-rack is not the set")
    }

    @Test
    fun `both bounds are inclusive`() {
        val measured =
            RollExcursion.of(
                samples(100L to 0.0, 200L to 5.0, 300L to 25.0, 400L to 0.0),
                workStartedAtMs = 200L,
                end = cuedAt(300L),
            )
        assertEquals(20.0, measured?.degrees, "a sample stamped on a bound belongs to the window")
    }

    @Test
    fun `unwrapping happens inside the window, not over the file`() {
        val measured =
            RollExcursion.of(
                samples(100L to 0.0, 200L to 179.0, 300L to -179.0),
                workStartedAtMs = 200L,
                end = SetEnd.NotCued,
            )
        assertEquals(2.0, measured?.degrees, "179 to -179 inside the window is a 2-degree sweep")
    }

    // ---- absence is not a low number ---------------------------------------

    @Test
    fun `a window holding one sample states nothing`() {
        assertNull(
            RollExcursion.of(samples(100L to 0.0, 500L to 90.0), workStartedAtMs = 400L, end = cuedAt(450L)),
            "one sample has a range of zero, and zero here reads as did not rotate",
        )
    }

    @Test
    fun `a window holding no samples states nothing`() {
        assertNull(RollExcursion.of(samples(100L to 0.0, 200L to 90.0), 900L, SetEnd.NotCued))
    }

    @Test
    fun `an empty capture states nothing`() {
        assertNull(RollExcursion.of(emptyList(), null, SetEnd.NotCued))
    }

    // ---- the basis says what the figure covers -----------------------------

    @Test
    fun `both bounds known is the working window`() {
        val measured = RollExcursion.of(samples(100L to 0.0, 200L to 5.0), 100L, cuedAt(200L))
        assertEquals(RollExcursion.Basis.WORKING_WINDOW, measured?.basis)
        assertEquals("workingWindow", measured?.basis?.published)
    }

    @Test
    fun `no terminal cue says the figure runs from the work start`() {
        val measured = RollExcursion.of(samples(100L to 0.0, 200L to 5.0), 100L, SetEnd.NotCued)
        assertEquals(RollExcursion.Basis.FROM_WORK_START, measured?.basis)
        assertEquals("fromWorkStart", measured?.basis?.published)
    }

    @Test
    fun `no prep window says the figure runs to the terminal cue`() {
        val measured = RollExcursion.of(samples(100L to 0.0, 200L to 5.0), null, cuedAt(200L))
        assertEquals(RollExcursion.Basis.TO_TERMINAL_CUE, measured?.basis)
        assertEquals("toTerminalCue", measured?.basis?.published)
    }

    @Test
    fun `neither bound says the figure covers the whole capture`() {
        val measured = RollExcursion.of(samples(100L to 0.0, 200L to 5.0), null, SetEnd.NotCued)
        assertEquals(RollExcursion.Basis.WHOLE_CAPTURE, measured?.basis)
        assertEquals("wholeCapture", measured?.basis?.published)
    }

    /**
     * The published spellings are what a reader of the archive matches on, so
     * they are pinned as a set rather than one at a time: a value renamed
     * without the archive's documentation moving with it is a key an outside
     * analysis silently stops recognising.
     */
    @Test
    fun `the published basis vocabulary is exactly these four words`() {
        assertEquals(
            listOf("workingWindow", "fromWorkStart", "toTerminalCue", "wholeCapture"),
            RollExcursion.Basis.entries.map { it.published },
        )
    }
}
