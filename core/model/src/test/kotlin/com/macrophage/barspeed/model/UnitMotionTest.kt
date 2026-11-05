package com.macrophage.barspeed.model

import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [UnitMotion] on synthetic buffers. The corpus its still floor is argued from
 * is measured in `UnitMotionFieldTest` in `:core:dsp`.
 */
class UnitMotionTest {
    /** [n] frames at 10 ms, the vertical axis swinging by [amplitudeG] about 1 g, nothing else moving. */
    private fun buffer(n: Int, amplitudeG: Double, firstMs: Long = 0L): List<ImuSample> = List(n) { i ->
        ImuSample(
            timestampMs = firstMs + i * 10L,
            axG = 0.0,
            ayG = 0.0,
            azG = 1.0 + amplitudeG * sin(i / 20.0),
            wxDps = 0.0,
            wyDps = 0.0,
            wzDps = 0.0,
            rollDeg = 0.0,
            pitchDeg = 0.0,
            yawDeg = 0.0,
        )
    }

    @Test
    fun `a span is the last arrival minus the first`() {
        assertEquals(990L, UnitMotion.spanMs(buffer(100, 0.0)))
        assertEquals(990L, UnitMotion.spanMs(buffer(100, 0.0).reversed()), "arrival order must not matter")
        assertNull(UnitMotion.spanMs(buffer(1, 0.0)), "one frame has no span")
    }

    @Test
    fun `a unit that did not move is still, and one that did is not`() {
        assertEquals(true, UnitMotion.still(buffer(400, 0.0)))
        assertEquals(false, UnitMotion.still(buffer(400, 0.2)))
    }

    /**
     * The floor is [UnitMotion.STILL_ACCEL_SD_G] on the noisiest axis: a sine
     * of amplitude A has a population deviation of A over root two, so the
     * boundary sits at an amplitude of about 0.0141 g.
     */
    @Test
    fun `the floor is the noisiest axis's deviation`() {
        assertEquals(true, UnitMotion.still(buffer(2_000, 0.013)), "0.0092 g of deviation")
        assertEquals(false, UnitMotion.still(buffer(2_000, 0.015)), "0.0106 g of deviation")
    }

    /** Rotation alone is not motion here: a stack unit rides the load without turning. */
    @Test
    fun `a unit that only rotates is judged on its accelerometer`() {
        val turning = buffer(400, 0.0).mapIndexed { i, s -> s.copy(wxDps = 30.0, rollDeg = i * 0.3) }
        assertEquals(true, UnitMotion.still(turning))
    }

    @Test
    fun `a buffer the analysis would refuse is not judged`() {
        assertNull(UnitMotion.still(buffer(SensorCapturePolicy.MIN_ANALYSABLE_FRAMES - 1, 0.5)))
    }
}
