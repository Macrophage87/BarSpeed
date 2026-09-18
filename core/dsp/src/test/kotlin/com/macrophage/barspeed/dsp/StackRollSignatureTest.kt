package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.SensorCapturePolicy
import com.macrophage.barspeed.model.StackMountSignal
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The two thresholds and the three answers of [StackRollSignature], on streams
 * built to sit either side of each bound. Issue #278.
 *
 * SYNTHETIC ON PURPOSE. A real capture cannot be placed one hundredth of a
 * degree under a threshold, and a bound is only pinned if something crosses it
 * in both directions. The field figures the two constants were drawn from are
 * in `StackMountFieldTest`, which scores seven committed pairs.
 *
 * Nothing here observes a mount. Each stream states a roll track and a gyro
 * track, and the assertions are about what this classpath computes from them.
 */
class StackRollSignatureTest {
    /** A stream at 100 Hz from [firstMs], one sample per (roll, rate) pair given. */
    private fun stream(firstMs: Long = 0L, track: List<Pair<Double, Double>>): List<ImuSample> =
        track.mapIndexed { index, (rollDeg, rateDps) ->
            ImuSample(
                timestampMs = firstMs + index * 10L,
                axG = 0.0,
                ayG = 0.0,
                azG = 1.0,
                wxDps = rateDps,
                wyDps = 0.0,
                wzDps = 0.0,
                rollDeg = rollDeg,
                pitchDeg = 0.0,
                yawDeg = 0.0,
            )
        }

    /** [n] samples whose roll sweeps linearly over [rangeDeg] at a quiet [rateDps]. */
    private fun sweep(rangeDeg: Double, n: Int = 100, rateDps: Double = 1.0, firstMs: Long = 0L) =
        stream(firstMs, (0 until n).map { i -> rangeDeg * i / (n - 1) to rateDps })

    private val unbounded = SetEnd.NotCued

    @Test
    fun `a stream whose roll barely moves reads as riding the stack`() {
        assertEquals(StackMountSignal.ON_STACK, StackRollSignature.of(sweep(0.2), null, unbounded))
    }

    /**
     * The roll bound is EXCLUSIVE, and the two cases are asserted either side
     * of it rather than only under it. A range exactly at the threshold is not
     * on the stack: the constant is where the evidence stops supporting the
     * quieter reading, so the boundary belongs to the louder one.
     */
    @Test
    fun `the roll bound is exclusive`() {
        assertEquals(
            StackMountSignal.ON_STACK,
            StackRollSignature.of(sweep(StackRollSignature.MAX_STACK_ROLL_DEG - 0.01), null, unbounded),
            "just inside the bound",
        )
        assertEquals(
            StackMountSignal.NOT_ON_STACK,
            StackRollSignature.of(sweep(StackRollSignature.MAX_STACK_ROLL_DEG), null, unbounded),
            "exactly at the bound",
        )
    }

    /**
     * The guard the class KDoc argues for: a stream whose reported roll is flat
     * while its gyro says the unit is being turned. One sample over the rate
     * bound is enough, because it is the maximum that is read and not a mean --
     * a mean would let a long quiet stretch pay for a spin.
     *
     * This is the only way a unit that was NOT on the stack can be moved onto,
     * so the direction matters: a single loud sample refuses the candidate.
     */
    @Test
    fun `a flat roll with one loud gyro sample does not read as riding the stack`() {
        val track = MutableList(100) { 0.0 to 1.0 }
        track[57] = 0.0 to StackRollSignature.MAX_STACK_ROLL_RATE_DPS + 0.1

        assertEquals(StackMountSignal.NOT_ON_STACK, StackRollSignature.of(stream(track = track), null, unbounded))
    }

    /** The rate bound is INCLUSIVE, asserted for the same reason the roll bound's exclusivity is. */
    @Test
    fun `the rate bound is inclusive`() {
        val track = MutableList(100) { 0.0 to 1.0 }
        track[57] = 0.0 to StackRollSignature.MAX_STACK_ROLL_RATE_DPS

        assertEquals(StackMountSignal.ON_STACK, StackRollSignature.of(stream(track = track), null, unbounded))
    }

    /** The sign of the rate is not the question: a unit turning the other way is still turning. */
    @Test
    fun `a negative rate is read by its magnitude`() {
        val track = MutableList(100) { 0.0 to 1.0 }
        track[57] = 0.0 to -(StackRollSignature.MAX_STACK_ROLL_RATE_DPS + 0.1)

        assertEquals(StackMountSignal.NOT_ON_STACK, StackRollSignature.of(stream(track = track), null, unbounded))
    }

    /**
     * ABSENCE IS A THIRD ANSWER, never a flat reading. A window too short to
     * characterise is [StackMountSignal.UNMEASURED], and the bound is
     * [SensorCapturePolicy.MIN_ANALYSABLE_FRAMES] -- restated from `:core:model`
     * rather than invented here, because a window the DSP would refuse to
     * analyse is not one to judge a mount from either.
     *
     * Eight frames is where the arithmetic stops being empty and is NOT a claim
     * that eight frames characterise a mount; at the archived rates it is about
     * 80 ms.
     */
    @Test
    fun `a window too short to characterise is unmeasured`() {
        val short = sweep(0.2, n = SensorCapturePolicy.MIN_ANALYSABLE_FRAMES - 1)
        val just = sweep(0.2, n = SensorCapturePolicy.MIN_ANALYSABLE_FRAMES)

        assertEquals(StackMountSignal.UNMEASURED, StackRollSignature.of(short, null, unbounded), "seven frames")
        assertEquals(StackMountSignal.ON_STACK, StackRollSignature.of(just, null, unbounded), "eight frames")
        assertEquals(StackMountSignal.UNMEASURED, StackRollSignature.of(emptyList(), null, unbounded), "nothing at all")
    }

    /**
     * THE WINDOW IS THE WORKING WINDOW, and both bounds are shown to exclude
     * rotation rather than only one. A unit clipped on before the work started
     * and unclipped after the terminal cue rotates hugely OUTSIDE the set; the
     * same stream read over the whole capture would be called moved, and
     * field-42's set 11 is the real instance -- 135.9 degrees over the file
     * against 5.0 over the window.
     *
     * The bound is [RollExcursion]'s own, read through
     * [RollExcursion.inWindow], so the verdict cannot come to cover different
     * seconds from the `rollExcursion_deg` the archive publishes beside it.
     */
    @Test
    fun `rotation outside the working window is not read`() {
        val before = sweep(90.0, n = 50, firstMs = 0L)
        val during = sweep(0.2, n = 100, firstMs = 500L)
        val after = sweep(90.0, n = 50, firstMs = 1_500L)
        val whole = before + during + after

        assertEquals(
            StackMountSignal.ON_STACK,
            StackRollSignature.of(whole, workStartedAtMs = 500L, end = SetEnd.Cued(1_499L)),
            "the prep and the re-rack were read as the set",
        )
        assertEquals(
            StackMountSignal.NOT_ON_STACK,
            StackRollSignature.of(whole, workStartedAtMs = null, end = unbounded),
            "a set with neither bound is judged over its whole capture, which is looser and never tighter",
        )
    }

    /**
     * The range is taken over the UNWRAPPED signal, so a unit that turns
     * through the +-180 discontinuity is not read as having stayed still.
     *
     * `roll_deg` is bounded to (-180, 180], so a mount that keeps turning wraps;
     * a max-minus-min over the wrapped column can report a small number for a
     * large rotation, which is [RollExcursion]'s own first fault and is
     * inherited here rather than re-solved.
     */
    @Test
    fun `a turn through the discontinuity is not read as stillness`() {
        val track = (0 until 100).map { i ->
            val wrapped = 179.0 + i * 0.1
            (if (wrapped > 180.0) wrapped - 360.0 else wrapped) to 1.0
        }

        assertEquals(StackMountSignal.NOT_ON_STACK, StackRollSignature.of(stream(track = track), null, unbounded))
    }
}
