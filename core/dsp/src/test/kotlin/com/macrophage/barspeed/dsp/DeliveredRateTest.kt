package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ImuSample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * [DeliveredRate] on streams written down here, issue #321.
 *
 * DIFFERENTIALS. At the commit that adds this file [DeliveredRate.of] answers
 * null for every stream, so every case expecting a figure fails. The fix is the
 * commit after it. The null cases pass at both commits and are here so the fix
 * cannot widen what it measures.
 *
 * THE SHAPES are the two the committed two-unit captures carry, read off them
 * by `DeliveredRateFieldTest`: a healthy link hands over a notification about
 * every 31 ms carrying three or four frames, about 99 frames a second, and
 * field-42's slow link hands over exactly four frames every 90 ms, about 44 a
 * second. The streams below are synthetic, built to those two shapes. No sensor
 * produced any of them.
 */
class DeliveredRateTest {
    /** [bursts] notifications [spacingMs] apart from [startMs], [perBurst] frames each. */
    private fun bursty(startMs: Long, bursts: Int, perBurst: Int, spacingMs: Long): List<ImuSample> =
        (0 until bursts).flatMap { burst ->
            List(perBurst) { ImuSample(startMs + burst * spacingMs, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0) }
        }

    private fun at(vararg stampsMs: Long): List<ImuSample> =
        stampsMs.map { ImuSample(it, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0) }

    /**
     * field-42's slow link: four frames every 90 ms is 44.4 frames a second.
     *
     * 334 notifications from 0 ms is a last stamp at 29,970 ms. The window is
     * work start 0 to a terminal cue at 30,000 ms, 30 s, holding all 1,336
     * frames: 1336 / 30 = 44.533.
     */
    @Test
    fun `four frames every 90 ms reads about 44 a second`() {
        val measured =
            assertNotNull(
                DeliveredRate.of(bursty(0L, 334, 4, 90L), workStartedAtMs = 0L, end = SetEnd.Cued(30_000L)),
                "a slow link's stream stated no rate",
            )
        assertEquals(1336 / 30.0, measured.hz, 1e-9)
        assertEquals(90L, measured.burstSpacingMs)
    }

    /**
     * A healthy link: three frames every 30 ms over the same window is 100.
     */
    @Test
    fun `three frames every 30 ms reads 100 a second`() {
        val measured =
            assertNotNull(
                DeliveredRate.of(bursty(0L, 1000, 3, 30L), workStartedAtMs = 0L, end = SetEnd.Cued(30_000L)),
                "a healthy link's stream stated no rate",
            )
        assertEquals(100.0, measured.hz, 1e-9)
        assertEquals(30L, measured.burstSpacingMs)
    }

    /**
     * Frames before the work start and after the terminal cue are not counted.
     *
     * A healthy prep and a healthy re-rack either side of a slow working
     * window: the window alone is what the analysis read, and a whole-capture
     * figure would dilute the slow part with the healthy ends.
     */
    @Test
    fun `only frames inside the working window count`() {
        val prep = bursty(0L, 100, 3, 30L)
        val work = bursty(3_000L, 334, 4, 90L)
        val tail = bursty(33_100L, 100, 3, 30L)
        val measured =
            assertNotNull(
                DeliveredRate.of(prep + work + tail, workStartedAtMs = 3_000L, end = SetEnd.Cued(33_000L)),
            )
        assertEquals(1336 / 30.0, measured.hz, 1e-9, "the healthy prep or tail was counted")
        assertEquals(90L, measured.burstSpacingMs)
    }

    /**
     * A link that stops half way through lowers the rate: the window's length
     * is the denominator, not the span of the frames that arrived.
     *
     * 15 s of a healthy stream in a 30 s window is 50 frames a second over the
     * window. A span-based figure would read 100 and hide the silent half. The
     * spacing is a median and stays at 30, which is why the two keys are
     * published side by side.
     */
    @Test
    fun `a link that goes quiet half way reads half the rate`() {
        val measured =
            assertNotNull(
                DeliveredRate.of(bursty(0L, 500, 3, 30L), workStartedAtMs = 0L, end = SetEnd.Cued(30_000L)),
            )
        assertEquals(1500 / 30.0, measured.hz, 1e-9, "the silent half of the window was not counted against it")
        assertEquals(30L, measured.burstSpacingMs, "one long gap moved the median")
    }

    /**
     * With neither bound known, the stream's own first and last stamps stand
     * in, so the figure is the rows over their own span.
     */
    @Test
    fun `with no bounds the stream's own span is the window`() {
        val measured = assertNotNull(DeliveredRate.of(at(1_000L, 1_010L, 1_020L, 1_030L, 1_040L), null, SetEnd.NotCued))
        assertEquals(5 / 0.04, measured.hz, 1e-9)
        assertEquals(10L, measured.burstSpacingMs)
    }

    /**
     * The spacing is the UPPER median of the gaps between distinct stamps, and
     * a shared stamp is not a gap.
     *
     * Gaps 10, 20, 30 and 40 ms: the upper median of four is the third, 30.
     */
    @Test
    fun `the spacing is the upper median of the gaps between distinct stamps`() {
        val stream = at(0L, 0L, 10L, 10L, 30L, 60L, 60L, 100L)
        val measured = assertNotNull(DeliveredRate.of(stream, null, SetEnd.NotCued))
        assertEquals(30L, measured.burstSpacingMs)
    }

    // ---- what states nothing ------------------------------------------------

    @Test
    fun `an empty stream states nothing`() {
        assertNull(DeliveredRate.of(emptyList(), 0L, SetEnd.Cued(30_000L)))
    }

    @Test
    fun `a window holding one row states nothing`() {
        assertNull(DeliveredRate.of(at(5_000L), 0L, SetEnd.Cued(30_000L)))
    }

    @Test
    fun `a window that excludes every row states nothing`() {
        assertNull(DeliveredRate.of(at(1_000L, 1_010L, 1_020L), 5_000L, SetEnd.Cued(30_000L)))
    }

    /**
     * One notification with no bound either side has no length to divide by.
     * A rate there would be a division by zero, not a slow link.
     */
    @Test
    fun `one notification with no bounds states nothing`() {
        assertNull(DeliveredRate.of(at(1_000L, 1_000L, 1_000L, 1_000L), null, SetEnd.NotCued))
    }

    /**
     * One notification inside a known window HAS a rate -- four frames over the
     * window's length -- and no spacing, because there is no second stamp to
     * measure a gap to.
     */
    @Test
    fun `one notification in a known window has a rate and no spacing`() {
        val measured = assertNotNull(DeliveredRate.of(at(1_000L, 1_000L, 1_000L, 1_000L), 0L, SetEnd.Cued(2_000L)))
        assertEquals(2.0, measured.hz, 1e-9)
        assertNull(measured.burstSpacingMs, "a spacing was published with no gap to measure")
    }
}
