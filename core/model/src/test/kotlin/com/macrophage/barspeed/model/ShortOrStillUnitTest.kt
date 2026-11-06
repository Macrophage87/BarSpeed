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

    /**
     * The one-second floor under the tenth, on a set short enough for the two
     * to differ. FOUND BY MUTATION TESTING: setting
     * [SensorCapturePolicy.SPAN_MARGIN_MS] to 0 left every other test here
     * green, because on a minute of capture the tenth is six seconds and the
     * floor never decides anything. On a five-second set the tenth is half a
     * second, and a unit that stopped 0.8 s early is still inside a second.
     */
    @Test
    fun `on a short set a unit that stopped inside a second keeps the analysis, and one beyond it does not`() {
        val shortPartner = buffer(500, moving = true)
        val inside = capture(buffer(420, moving = true), shortPartner)
        assertEquals(SensorRole.A, inside.sensors?.analysed, "0.8 s short of a 5 s partner lost the analysis")
        val beyond = capture(buffer(390, moving = true), shortPartner)
        assertEquals(SensorRole.B, beyond.sensors?.analysed, "1.1 s short of a 5 s partner kept the analysis")
    }

    /**
     * The tenth, on a minute of capture, where it rather than the floor
     * decides: six seconds. Added beside the floor's pin for the same reason
     * -- `stopped half way` is thirty seconds short and would pass under any
     * share up to a half.
     */
    @Test
    fun `on a minute a unit that stopped inside a tenth keeps the analysis, and one beyond it does not`() {
        val inside = capture(buffer(5_500, moving = true), partner)
        assertEquals(SensorRole.A, inside.sensors?.analysed, "5 s short of a minute lost the analysis")
        val beyond = capture(buffer(5_300, moving = true), partner)
        assertEquals(SensorRole.B, beyond.sensors?.analysed, "7 s short of a minute kept the analysis")
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
     * Review round 1 (lens A 3): the "never when every unit is still" guard in
     * [SensorCapturePolicy.analysable], pinned where it is computed. Through
     * [armedCaptureOf] it is invisible on the analysed role -- with nothing
     * analysable, `analysedStream` keeps the armed role anyway, so
     * `two still units keep the armed one` stays green without it -- but the
     * list is also what `silent` is computed from, and without the guard both
     * still units drop out of it and either could be named silent.
     */
    @Test
    fun `with every unit still, every unit stays analysable`() {
        val both = listOf(SensorRole.A, SensorRole.B)
        assertEquals(
            both,
            SensorCapturePolicy.analysable(
                expected = both,
                framesByRole = mapOf(SensorRole.A to 6_000, SensorRole.B to 6_000),
                spanMsByRole = mapOf(SensorRole.A to 59_990L, SensorRole.B to 59_990L),
                stillByRole = mapOf(SensorRole.A to true, SensorRole.B to true),
            ),
        )
    }

    /**
     * RED before the fix, and the fallback half of #287. Before it, the
     * still armed unit's ON_STACK signature confirmed it and it kept the
     * analysis. Now it hands over by fallback. That a fallback under a
     * mount-specific declaration then publishes no figures is #247's pin,
     * `FallbackMountGeometryTest.a fallback under a mount-specific declaration
     * publishes no figures` in `:core:dsp`, and is not exercised here; this
     * test was named for the withholding until review round 1 (lens A 12,
     * lens B N6) said the name claimed more than it asserts.
     */
    @Test
    fun `a still armed unit on a stack declaration falls back to its partner`() {
        val lying = buffer(6_000, moving = false)
        val c = capture(lying, partner, declaresStackMount = true) {
            if (it === lying) StackMountSignal.ON_STACK else StackMountSignal.NOT_ON_STACK
        }
        assertEquals(SensorRole.B, c.sensors?.analysed)
        assertEquals(true, c.sensors?.analysedFellBack)
        assertEquals(AnalysedRoleBasis.FALLBACK, c.sensors?.analysedRoleBasis)
    }
}
