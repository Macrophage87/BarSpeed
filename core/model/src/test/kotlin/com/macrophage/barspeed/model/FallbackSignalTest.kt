package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Which sets [armedCaptureOf] measures the ANALYSED unit's roll on. Issue #279.
 *
 * `SetAnalyzer` refuses a frame-count fallback under a mount-specific
 * declaration (#247). The mirrored case -- a MOUNT-FREE declaration, declared
 * from the lifter's end, over a partner that rode the stack -- needs the
 * fallen-back unit's own signature to be refused at all, and
 * [ArmedCapture.analysedSignal] used to be measured only where the set
 * declared a stack mount. What is pinned here is which shapes of set carry a
 * measured signal; the refusal itself is `SetAnalyzer`'s and is pinned in
 * `FallbackMountGeometryTest` in `:core:dsp`.
 *
 * The supplier answers by buffer identity, so a signal measured on the wrong
 * unit's buffer cannot pass any assertion below.
 */
class FallbackSignalTest {
    private fun samples(n: Int, firstMs: Long = 0L): List<ImuSample> = List(n) { i ->
        ImuSample(
            timestampMs = firstMs + i * 10L,
            axG = 0.0,
            ayG = 0.0,
            azG = 1.0,
            wxDps = 0.0,
            wyDps = 0.0,
            wzDps = 0.0,
            rollDeg = 0.0,
            pitchDeg = 0.0,
            yawDeg = 0.0,
        )
    }

    private fun armed() =
        RecordedSensors(count = 2, expected = listOf(SensorRole.A, SensorRole.B), analysed = SensorRole.A)

    /** The armed unit sent too few frames to analyse, its partner a full capture. */
    private val starved = samples(SensorCapturePolicy.MIN_ANALYSABLE_FRAMES - 1)
    private val partner = samples(SensorCapturePolicy.MIN_ANALYSABLE_FRAMES + 4, firstMs = 1L)

    private fun fellBack(declaresStackMount: Boolean): ArmedCapture = armedCaptureOf(
        armed(),
        SensorRole.B,
        starved,
        partner,
        declaresStackMount = declaresStackMount,
        stackSignalOf = { if (it === partner) StackMountSignal.ON_STACK else StackMountSignal.NOT_ON_STACK },
    )

    /** The frame-count fallback a stack declaration has always measured: the partner's roll, unchanged by #279. */
    @Test
    fun `a fallback under a stack declaration carries the unit it fell back to's signal`() {
        val capture = fellBack(declaresStackMount = true)

        assertSame(partner, capture.samples, "the analysis did not move onto the partner")
        assertTrue(capture.sensors!!.analysedFellBack, "the move was not recorded as a fallback")
        assertEquals(StackMountSignal.ON_STACK, capture.analysedSignal)
    }

    /**
     * A two-unit set that did NOT fall back, under a declaration naming no
     * stack, still asks nothing: a still bench unit reads stack as readily as
     * a unit on one, and nothing here consults the answer.
     */
    @Test
    fun `a two-unit set that did not fall back and declares no stack measures nothing`() {
        val a = samples(SensorCapturePolicy.MIN_ANALYSABLE_FRAMES + 4)
        val capture = armedCaptureOf(
            armed(),
            SensorRole.B,
            a,
            partner,
            declaresStackMount = false,
            stackSignalOf = { StackMountSignal.ON_STACK },
        )

        assertSame(a, capture.samples)
        assertEquals(StackMountSignal.UNMEASURED, capture.analysedSignal)
    }
}
