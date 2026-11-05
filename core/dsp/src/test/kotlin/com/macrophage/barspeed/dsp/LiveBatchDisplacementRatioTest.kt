package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.StartPhase
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * How far a LIVE counted rep travels against the BATCH analysis of the same
 * capture, on the seated leg curl, pinned so the ratio cannot drift silently.
 * Issue #115. Characterization only: NOTHING IS FIXED HERE, and no constant is
 * retuned on it. #115 item 3 -- restate the two live displacement constants
 * against corrected displacement, or keep them calibrated against the biased
 * quantity and document that -- is an owner decision this file does not take.
 *
 * ## Why this is pinned at all
 *
 * `DspConfig.minRomM` and `DspConfig.maxRunDisplacementM` gate BOTH paths, and
 * the two paths measure displacement differently: live integrates a causal
 * series with no retroactive correction, batch a series the drift correction
 * has re-anchored after the fact. #86 sized the shared cap by putting a live
 * figure beside a batch one. That argument is only sound while the ratio
 * between the two scales is stable, and until this file nothing held it.
 * `LiveCapCalibrationTest` says the ratio "should not be" pinned AS A METRIC
 * -- nothing should be calibrated against it -- and this file does not do
 * that: it pins today's value so that a change to either path that moves it
 * reds here, where the cost to the shared constants can be argued.
 *
 * ## The figures, and the one capture that contradicts the issue's framing
 *
 * Per capture, the median displacement of the reps the live tracker counts
 * against the median `rom_m` of the reps the batch analysis resolves. #115
 * measured the first three at `5cc641a` as 0.58x, 0.53x and 0.59x; they are
 * unchanged at this tree to two decimals, although #87 and #94 moved the batch
 * path in between. The batch medians sit at or next to the leg curl's
 * 0.4-0.5 m rail (0.394-0.504 m across the four), which is the only external
 * reference the corpus has for either path.
 *
 * `field-legcurl-1030-10rep` is the fourth leg curl and #115's table leaves
 * it out. Its live median is 2.12x the batch one -- live OVER-reads -- so
 * "live displacement reads 53-59% of batch" is a statement about three
 * captures, not about the live path. It is pinned beside them so that
 * sentence cannot be quoted as general.
 *
 * ## Scale, truncation or lost samples -- as far as this corpus can say
 *
 * Displacement is mean velocity times duration, so the shortfall splits into a
 * duration ratio and a velocity ratio, both pinned below as medians:
 *
 * - `field-legcurl-1030-12rep`: duration 0.50x, velocity 1.06x. The live run
 *   moves as fast as the batch drive and lasts half as long -- BOUNDARY
 *   TRUNCATION, not a scale error, on this capture.
 * - `-12rep-b` and `-12rep-c`: duration 0.81x, velocity 0.77x and 0.76x.
 *   Both factors, in roughly equal measure. A scale error alone would leave
 *   duration at 1.0x; truncation alone would leave velocity at 1.0x.
 *
 * Because medians are taken per column, the two factors do not multiply back
 * to the displacement ratio exactly; they say which way the shortfall leans,
 * not how it decomposes rep by rep. The live and batch reps are NOT paired
 * one-to-one here -- live counts 8, 6 and 9 where batch resolves 12, 13 and
 * 11 -- so this is a comparison of two populations from one capture.
 *
 * LOST SAMPLES are excluded ON REPLAY and nowhere else. This file feeds the
 * tracker every committed sample in order; the timestamps are monotone, the
 * largest inter-sample gap is 94-123 ms across the four, and `sample_idx` is
 * contiguous from 0 on all four -- so no index is missing from what is
 * replayed, and no part of the replayed shortfall is a dropped sample.
 * Whether a phone drops samples before they reach the live tracker is a
 * different question this file cannot answer: `[Field]`.
 *
 * ## How the live figures are read, and why that is licensed
 *
 * The tracker keeps its run state private, so displacement and duration are
 * rebuilt from the velocity it publishes, exactly as `LiveCapCalibrationTest`
 * and `LiveUnderCountAttributionTest` do. Every gate the tracker applies to a
 * run -- peak, duration, the `minRomM` floor and the cap -- is reapplied, and
 * the rebuilt count is asserted EQUAL to the tracker's own `repCount` on every
 * capture, and the rebuilt mean velocity equal to the tracker's own
 * `repMeanVelocities`, so a reconstruction that drifted from the tracker would
 * red here before it misreported a ratio.
 *
 * Nothing here runs a sensor, a BLE link or a phone.
 */
class LiveBatchDisplacementRatioTest {
    private fun load(n: String) = ImuCsv.decode(
        javaClass.getResourceAsStream("/$n.csv")!!.readBytes().decodeToString(),
    )

    /** The leg-curl geometry `LiveCapCalibrationTest` scores these captures under. */
    private val legCurl = LiftDirection(
        startsWith = StartPhase.CONCENTRIC,
        concentricUp = false,
        sensorInverted = true,
        plane = MovementPlane.VERTICAL,
        sensorOnStack = true,
    )

    private data class LiveRep(val displacementM: Double, val durationS: Double, val meanMps: Double)

    private data class Live(val reps: List<LiveRep>, val trackerCount: Int, val trackerMeans: List<Double>)

    /**
     * The reps the tracker counts, rebuilt from its published velocity under
     * the gates `StreamingSetTracker.updateRuns` applies, concentric-first.
     */
    private fun live(samples: List<ImuSample>, c: DspConfig): Live {
        val tracker = StreamingSetTracker.forLift(legCurl, c)
        val dt = 1.0 / VelocityEstimator.measureSampleRate(
            samples.size,
            (samples.last().timestampMs - samples.first().timestampMs) / 1000.0,
        )
        val reps = mutableListOf<LiveRep>()
        var type = 0
        var displacement = 0.0
        var signedSum = 0.0
        var peak = 0.0
        var n = 0
        var state = LiveSetState()
        fun close() {
            val duration = n * dt
            val concentric = (type == 1) == legCurl.driveIsPositive
            val qualified = peak >= c.startThresholdMps &&
                duration >= c.minPhaseS &&
                displacement >= c.minRomM &&
                displacement <= c.maxRunDisplacementM
            if (type != 0 && concentric && qualified) reps += LiveRep(displacement, duration, abs(signedSum) / n)
        }
        samples.forEach { sample ->
            state = tracker.feed(sample)
            val v = state.velocityMps
            val k = when {
                v > c.pauseBandMps -> 1
                v < -c.pauseBandMps -> -1
                else -> 0
            }
            if (k == type) {
                if (k != 0) {
                    displacement += abs(v) * dt
                    signedSum += v
                    peak = maxOf(peak, abs(v))
                    n++
                }
            } else {
                close()
                type = k
                displacement = if (k != 0) abs(v) * dt else 0.0
                signedSum = if (k != 0) v else 0.0
                peak = abs(v)
                n = if (k != 0) 1 else 0
            }
        }
        return Live(reps, state.repCount, state.repMeanVelocities)
    }

    private fun median(x: List<Double>): Double = x.sorted()[x.size / 2]

    private fun r2(x: Double) = Math.round(x * 100.0) / 100.0

    private data class Ratios(val displacement: Double, val duration: Double, val velocity: Double)

    private fun ratios(fixture: String): Ratios {
        val c = DspConfig()
        val samples = load(fixture)
        val l = live(samples, c)
        assertEquals(l.trackerCount, l.reps.size, "$fixture: the rebuilt live count is the tracker's own")
        l.trackerMeans.zip(l.reps).forEachIndexed { i, (own, rebuilt) ->
            assertEquals(own, rebuilt.meanMps, 2e-3, "$fixture: live rep ${i + 1} mean velocity, rebuilt vs tracker")
        }
        val batch = SetAnalyzer.analyze(samples, legCurl, loadKg = 20.0, config = c).reps
        return Ratios(
            displacement = r2(median(l.reps.map { it.displacementM }) / median(batch.map { it.romM })),
            duration = r2(median(l.reps.map { it.durationS }) / median(batch.map { it.conS })),
            velocity = r2(median(l.reps.map { it.meanMps }) / median(batch.map { it.meanConVelMps })),
        )
    }

    @Test
    fun `live displacement against batch ROM on the leg curl, per capture`() {
        assertEquals(
            mapOf(
                "field-legcurl-1030-12rep" to 0.58,
                "field-legcurl-1030-12rep-b" to 0.53,
                "field-legcurl-1030-12rep-c" to 0.59,
                // The fourth leg curl, which #115's table omits: live OVER-reads.
                "field-legcurl-1030-10rep" to 2.12,
            ),
            listOf(
                "field-legcurl-1030-12rep",
                "field-legcurl-1030-12rep-b",
                "field-legcurl-1030-12rep-c",
                "field-legcurl-1030-10rep",
            ).associateWith { ratios(it).displacement },
            "median live counted-rep displacement / median batch rom_m",
        )
    }

    @Test
    fun `the shortfall splits into duration and velocity, and leans differently per capture`() {
        assertEquals(
            mapOf(
                // Truncation: as fast, half as long.
                "field-legcurl-1030-12rep" to (0.50 to 1.06),
                // Both, roughly equally.
                "field-legcurl-1030-12rep-b" to (0.81 to 0.77),
                "field-legcurl-1030-12rep-c" to (0.81 to 0.76),
            ),
            listOf("field-legcurl-1030-12rep", "field-legcurl-1030-12rep-b", "field-legcurl-1030-12rep-c")
                .associateWith { ratios(it).let { r -> r.duration to r.velocity } },
            "median live run duration / median batch con_s, then median live mean velocity / median batch",
        )
    }

    @Test
    fun `the batch reference sits on the leg curl's rail`() {
        // The only external check either path has: the seated leg curl's
        // 0.4-0.5 m arc. Batch medians, which the ratios above divide by.
        val c = DspConfig()
        assertEquals(
            mapOf(
                "field-legcurl-1030-12rep" to 0.504,
                "field-legcurl-1030-12rep-b" to 0.489,
                "field-legcurl-1030-12rep-c" to 0.404,
                "field-legcurl-1030-10rep" to 0.394,
            ),
            listOf(
                "field-legcurl-1030-12rep",
                "field-legcurl-1030-12rep-b",
                "field-legcurl-1030-12rep-c",
                "field-legcurl-1030-10rep",
            ).associateWith { f ->
                median(SetAnalyzer.analyze(load(f), legCurl, loadKg = 20.0, config = c).reps.map { it.romM })
            },
            "median batch rom_m per capture",
        )
    }
}
