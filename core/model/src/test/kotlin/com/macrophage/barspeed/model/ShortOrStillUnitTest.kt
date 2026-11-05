package com.macrophage.barspeed.model

import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which buffer [armedCaptureOf] hands the analysis when one armed unit
 * delivered enough frames for the estimator but cannot stand for the set:
 * it stopped long before its partner did (#242), or it never moved while its
 * partner did (#287).
 *
 * Both are judged against the PARTNER, because neither is a property of one
 * stream alone: eight frames is a stream the estimator accepts, and a stack
 * unit's quiet gyro is what a unit doing its job looks like.
 * `UnitMotionFieldTest` in `:core:dsp` measures the committed corpus both
 * rules are argued from, and pins that neither fires on any committed pair.
 *
 * Every buffer here is at 10 ms. A MOVING buffer swings its vertical axis by
 * 0.2 g; a STILL one does not vary at all.
 */
class ShortOrStillUnitTest {
    private fun buffer(n: Int, moving: Boolean, stepMs: Long = 10L, firstMs: Long = 0L): List<ImuSample> =
        List(n) { i ->
            ImuSample(
                timestampMs = firstMs + i * stepMs,
                axG = 0.0,
                ayG = 0.0,
                azG = if (moving) 1.0 + 0.2 * sin(i / 20.0) else 1.0,
                wxDps = 0.0,
                wyDps = 0.0,
                wzDps = 0.0,
                rollDeg = 0.0,
                pitchDeg = 0.0,
                yawDeg = 0.0,
            )
        }

    private val dual =
        RecordedSensors(count = 2, expected = listOf(SensorRole.A, SensorRole.B), analysed = SensorRole.A)

    private fun capture(
        a: List<ImuSample>,
        b: List<ImuSample>,
        declaresStackMount: Boolean = false,
        stackSignalOf: (List<ImuSample>) -> StackMountSignal = { StackMountSignal.UNMEASURED },
    ) = armedCaptureOf(
        armed = dual,
        secondaryRole = SensorRole.B,
        analysedBuffer = a,
        secondaryBuffer = b,
        declaresStackMount = declaresStackMount,
        stackSignalOf = stackSignalOf,
    )

    /** A minute of partner capture. */
    private val partner = buffer(6_000, moving = true)

    /**
     * #242's boundary, RED before the fix: exactly [SensorCapturePolicy.MIN_ANALYSABLE_FRAMES]
     * frames, 70 ms, then nothing, beside a minute of partner capture. The
     * estimator accepts eight frames, so the armed unit kept the analysis and
     * the set's summary described 70 ms of it with no key saying so.
     */
    @Test
    fun `an armed unit that delivered eight frames and stopped hands the analysis to its partner`() {
        val c = capture(buffer(SensorCapturePolicy.MIN_ANALYSABLE_FRAMES, moving = true), partner)
        assertEquals(SensorRole.B, c.sensors?.analysed)
        assertEquals(true, c.sensors?.analysedFellBack, "the move is not published")
        assertEquals(AnalysedRoleBasis.FALLBACK, c.sensors?.analysedRoleBasis)
        assertTrue(c.samples === partner, "the analysis is not on the partner's buffer")
    }

    /** RED before the fix: half a set is not the set. */
    @Test
    fun `an armed unit that stopped half way hands the analysis to its partner`() {
        val c = capture(buffer(3_000, moving = true), partner)
        assertEquals(SensorRole.B, c.sensors?.analysed)
        assertEquals(true, c.sensors?.analysedFellBack)
    }

    /** Green before and after: stopping inside the margin is the corpus's ordinary end-of-set jitter and more. */
    @Test
    fun `an armed unit that stopped near its partner keeps the analysis`() {
        val c = capture(buffer(5_950, moving = true), partner)
        assertEquals(SensorRole.A, c.sensors?.analysed)
        assertEquals(false, c.sensors?.analysedFellBack)
    }

    /** Green before and after: field-42's shape, a 44 Hz unit over the same minute as a 100 Hz partner. */
    @Test
    fun `a slower unit over the same seconds keeps the analysis`() {
        val c = capture(buffer(2_640, moving = true, stepMs = 22L), partner)
        assertEquals(SensorRole.A, c.sensors?.analysed)
        assertEquals(false, c.sensors?.analysedFellBack)
    }

    /**
     * #287's rule, RED before the fix: the armed unit streamed a whole minute
     * and never moved, while its partner did. Its figures would describe a
     * unit lying still.
     */
    @Test
    fun `an armed unit that never moved beside a partner that did hands the analysis over`() {
        val c = capture(buffer(6_000, moving = false), partner)
        assertEquals(SensorRole.B, c.sensors?.analysed)
        assertEquals(true, c.sensors?.analysedFellBack, "the move is not published")
        assertTrue(c.samples === partner)
    }

    /** Green before and after: a still PARTNER takes nothing. */
    @Test
    fun `a still partner does not take the analysis`() {
        val c = capture(partner, buffer(6_000, moving = false))
        assertEquals(SensorRole.A, c.sensors?.analysed)
        assertEquals(false, c.sensors?.analysedFellBack)
    }

    /** Green before and after: with nothing moving there is nothing better to point at. */
    @Test
    fun `two still units keep the armed one`() {
        val c = capture(buffer(6_000, moving = false), buffer(6_000, moving = false))
        assertEquals(SensorRole.A, c.sensors?.analysed)
        assertEquals(false, c.sensors?.analysedFellBack)
    }

    /**
     * RED before the fix: a stack-declared set whose armed unit is on the rope
     * and moving, and whose partner never moved. A still unit's roll does not
     * move either, so its signature reads ON_STACK and #278's rule handed the
     * analysis to the unit lying still. It stays on the armed unit -- the one
     * stream that moved -- read as a one-unit set is, where #323's own-roll
     * check applies.
     */
    @Test
    fun `a still unit's stack signature does not take the analysis`() {
        val rope = buffer(6_000, moving = true)
        val lying = buffer(6_000, moving = false)
        val c = capture(rope, lying, declaresStackMount = true) {
            if (it === lying) StackMountSignal.ON_STACK else StackMountSignal.NOT_ON_STACK
        }
        assertEquals(SensorRole.A, c.sensors?.analysed)
        assertEquals(AnalysedRoleBasis.DECLARED, c.sensors?.analysedRoleBasis)
        assertTrue(c.samples === rope)
    }

    /**
     * RED before the fix, and the withholding half of #287. Before it, the
     * still armed unit's ON_STACK signature confirmed it and it kept the
     * analysis. Now it hands over by fallback, and
     * `SetAnalyzer` refuses a fallback under a mount-specific declaration
     * (#247), so the set publishes no figures rather than the partner's read
     * under a mount nobody declared for it.
     */
    @Test
    fun `a still armed unit on a stack declaration falls back, which the analyzer withholds`() {
        val lying = buffer(6_000, moving = false)
        val c = capture(lying, partner, declaresStackMount = true) {
            if (it === lying) StackMountSignal.ON_STACK else StackMountSignal.NOT_ON_STACK
        }
        assertEquals(SensorRole.B, c.sensors?.analysed)
        assertEquals(true, c.sensors?.analysedFellBack)
        assertEquals(AnalysedRoleBasis.FALLBACK, c.sensors?.analysedRoleBasis)
    }
}
