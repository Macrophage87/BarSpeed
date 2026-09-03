package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ImuSample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The smallest stream [VelocityEstimator.estimate] will look at, pinned as a
 * number rather than left as a literal nobody reads (#209).
 *
 * CHARACTERIZATION, not a new rule. The gate is `require(samples.size >= 8)`
 * and has been there since the estimator was written; what had nothing running
 * against it is the NUMBER, which #209's fix goes on to depend on. A unit that
 * delivers fewer frames than this cannot produce a summary at all -- the
 * estimator refuses, and `RecordViewModel` publishes "No sensor data
 * recorded." -- so this is the boundary at which "a stream arrived" stops
 * being the same statement as "a stream could be analysed".
 *
 * WHAT IT DOES NOT SAY. Eight frames is the point below which the arithmetic
 * refuses, and it is not a claim that eight frames make a usable capture: at
 * the 99.30-99.98 Hz field session 37 measured over its thirteen `imu-a`
 * streams, eight frames span about 70-80 ms of bar travel, which is less than
 * a rep by two orders of magnitude. Whether a stream in the tens or hundreds
 * of frames is worth analysing is a question no test here answers and no
 * capture in this repo has been asked.
 */
class VelocityEstimatorMinimumTest {
    private fun samples(n: Int): List<ImuSample> = (0 until n).map { i ->
        ImuSample(
            timestampMs = i * 10L,
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

    /**
     * Seven frames are refused, and the message names the count it was handed.
     *
     * Seven rather than zero on purpose: an empty stream is refused by any
     * plausible bound, and a pin on the empty case would stay green under a
     * gate of one, two or four. Seven is the largest stream this estimator
     * still refuses, so it is the only fixture that fails when the bound moves
     * down.
     */
    @Test
    fun `seven frames are too few for the estimator to produce anything`() {
        val thrown = assertFailsWith<IllegalArgumentException> { VelocityEstimator.estimate(samples(7)) }

        assertEquals("Not enough samples (7)", thrown.message, "what the estimator says about a stream it refuses")
    }

    /**
     * Eight frames are accepted, which is the other half of the boundary: a
     * pin on the refusal alone stays green under a gate of eight hundred.
     */
    @Test
    fun `eight frames are enough for the estimator to run`() {
        val series = VelocityEstimator.estimate(samples(8))

        assertTrue(series.velocityMps.isNotEmpty(), "the estimator accepted eight frames and produced no series")
    }
}
