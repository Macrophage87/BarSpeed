package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.StartPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [VelocityEstimator.measureSampleRate] — the number the whole time base is
 * rebuilt on, and until now the only function in this module with no test.
 *
 * It has one production caller, and its result becomes `dt` for the integrator,
 * the cutoff basis for the low-pass filter, and the `sampleRate_hz` a raw export
 * tells a reader to divide `sample_idx` by. What it computes is the mean
 * ARRIVAL rate of the samples handed to it. That is not the same statement as
 * "the rate the sensor streamed at", and nothing here claims it is: a dropped
 * packet is arithmetically indistinguishable from a slower sensor, because
 * nothing in [ImuSample] can express a gap.
 *
 * The fallback is the load-bearing part. Below two samples, or across a span of
 * zero, there is nothing to measure and the function returns 100.0 anyway --
 * correct for an integrator that must have some dt to proceed with, and wrong
 * for anything that publishes the figure as fact. Pinned here so the difference
 * cannot be refactored away silently.
 */
class SampleRateTest {
    @Test
    fun `the rate is intervals over span, not samples over span`() {
        // 101 samples spanning exactly 1 s is 100 intervals: 100 Hz, not 101.
        assertEquals(100.0, VelocityEstimator.measureSampleRate(101, 1.0))
        assertEquals(50.0, VelocityEstimator.measureSampleRate(51, 1.0))
        assertEquals(100.0, VelocityEstimator.measureSampleRate(201, 2.0))
    }

    /**
     * Span-based rather than per-sample, and that is deliberate: BLE delivers
     * several samples under one arrival timestamp, so consecutive deltas are
     * 0 ms and then jump. A median-of-dt estimator returns 0 on that input.
     */
    @Test
    fun `burst arrivals do not disturb the rate`() {
        val bursty = burstySamples(bursts = 20, perBurst = 5, burstIntervalMs = 50L)
        val span = (bursty.last().timestampMs - bursty.first().timestampMs) / 1000.0
        val rate = VelocityEstimator.measureSampleRate(bursty.size, span)
        // 100 samples, first and last arrival 950 ms apart: 99 / 0.95.
        assertEquals(100, bursty.size)
        assertEquals(0.95, span)
        assertTrue(rate in 104.0..105.0, "expected ~104 Hz from 99/0.95, got $rate")
        // The median inter-arrival delta is 0 -- what this estimator avoids.
        val deltas = bursty.zipWithNext { a, b -> b.timestampMs - a.timestampMs }.sorted()
        assertEquals(0L, deltas[deltas.size / 2])
    }

    /**
     * A single sample, or a whole stream sharing one arrival timestamp, cannot
     * state a rate. The estimator answers 100.0 regardless.
     *
     * This is a fabricated number, not a measurement, and it is the correct
     * behaviour HERE: [VelocityEstimator.estimate] needs a dt to integrate with
     * and refuses streams under eight samples anyway. It is the wrong behaviour
     * for any caller that publishes the result, which is why the nullable
     * sibling exists.
     */
    @Test
    fun `too little to measure returns the fabricated default rather than failing`() {
        assertEquals(100.0, VelocityEstimator.measureSampleRate(0, 1.0))
        assertEquals(100.0, VelocityEstimator.measureSampleRate(1, 0.0))
        assertEquals(100.0, VelocityEstimator.measureSampleRate(1, 5.0))
        assertEquals(100.0, VelocityEstimator.measureSampleRate(4_500, 0.0))
        assertEquals(100.0, VelocityEstimator.measureSampleRate(4_500, -1.0))
    }

    @Test
    fun `implausible rates are clamped rather than propagated`() {
        // 2 samples 10 minutes apart would read as 0.0017 Hz.
        assertEquals(4.0, VelocityEstimator.measureSampleRate(2, 600.0))
        // 10 000 samples in 1 ms would read as 10 MHz.
        assertEquals(250.0, VelocityEstimator.measureSampleRate(10_000, 0.001))
        assertEquals(4.0, VelocityEstimator.measureSampleRate(3, 1.0))
        assertEquals(250.0, VelocityEstimator.measureSampleRate(2_501, 10.0))
    }

    /**
     * The rate a set was analysed at survives the round trip through the
     * canonical CSV, exactly.
     *
     * This is the pin that makes deriving the figure at the export boundary a
     * no-op rather than a second opinion. `ImuCsv` writes `timestamp_ms` as an
     * exact integer and decodes every data row, so sample count and span come
     * back unchanged and the same formula returns the same double -- not "close
     * to", the same. Measured, not assumed: the two agree to the last bit at
     * 100.00000000000001.
     */
    @Test
    fun `the analysed rate is recoverable from the encoded CSV bit for bit`() {
        val samples =
            SyntheticSets.generate(
                reps = List(5) { SyntheticSets.RepSpec(2.0, 0.3, 1.0, 0.4, 0.5) },
                sampleRateHz = 100.0,
            )
        val analysed = SetAnalyzer.analyze(samples, StartPhase.ECCENTRIC, loadKg = 100.0).sampleRateHz
        val decoded = ImuCsv.decode(ImuCsv.encode(samples))
        assertEquals(samples.size, decoded.size)
        val span = (decoded.last().timestampMs - decoded.first().timestampMs) / 1000.0
        assertEquals(analysed, VelocityEstimator.measureSampleRate(decoded.size, span))
    }

    /** Bursts of [perBurst] samples sharing one arrival stamp, as the BLE link delivers them. */
    private fun burstySamples(bursts: Int, perBurst: Int, burstIntervalMs: Long): List<ImuSample> =
        (0 until bursts).flatMap { b ->
            List(perBurst) {
                ImuSample(
                    timestampMs = b * burstIntervalMs,
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
        }
}
