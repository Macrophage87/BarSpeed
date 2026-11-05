package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.StartPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A stream that never goes quiet, and whether the batch analysis still
 * resolves its reps. Issue #138's MECHANISM, on SYNTHETIC samples.
 *
 * ## What #138 reported, and what is owed
 *
 * Three dumbbell sets of the 2026-08-21 capture (sets 3, 5 and 7) each streamed
 * a complete, gapless ~99.4 Hz stream and published `reps: []`. Reproduced
 * offline at the time: the drift-corrected velocity never re-anchored on
 * continuously-cycling hand-held work, so the whole set collapsed into ONE
 * movement run -- 259.62 m, 80.57 m and 243.51 m -- which
 * `DspConfig.maxRunDisplacementM` then demoted to stillness. The precondition
 * measured beside it: median gyro magnitude 21.9-107.4 deg/s on every
 * hand-held set, against `stationaryGyroBandDps` = 10.
 *
 * #138's first deliverable is three fixtures from those sets' persisted
 * streams. THEY ARE NOT COMMITTED and are not on this classpath: no committed
 * capture is a dumbbell set from that session, and they live only in the
 * owner's archive. Nothing here substitutes for them, and nothing here says the
 * dumbbell sets now resolve. Owed, `[Field]`.
 *
 * The BAR-MOUNTED candidates #138 later named ARE committed and pinned
 * elsewhere: field-37's `field-bench-3010-6rep-s37-set05` and `-set06` and
 * `field-ohp-3010-6rep-s37-set02` (its 2026-09-02 comment), and field-36's
 * `field-rdl-3010-10rep-s36-set05`. `AnchorSupplyByMountTest`'s "what the
 * two families resolve" pins them at 5, 6, 9 and 11 detections against 6,
 * 6, 6 and 10 performed, and `BlankAnalysisTest` carries the RDL set's runs.
 * So "no hand-held stream is committed" is not "no #138 capture is".
 *
 * ## What a synthetic stream can and cannot say
 *
 * `SyntheticSets` emits ten 0.40 m reps -- a 1.5 s lowering straight into a
 * 1.0 s drive, no pause at either end -- with every gyro axis carrying 30 deg/s
 * of noise, so the median gyro magnitude is 46 deg/s and sits inside #138's
 * measured hand-held range, and a constant 0.01 g accelerometer bias on the
 * vertical axis, which is what makes an integrator with no zero run away. The
 * quiet predicate admits 11 of its 2,800 samples (asserted). Review round 1
 * measured them isolated -- no two adjacent -- with four in the 1.5 s lead-in
 * and lead-out and seven mid-set; this file does not assert that placement.
 * That is #138's SHAPE: a healthy stream with almost nothing to anchor on,
 * and the over-cap run below is what that supply amounts to.
 *
 * On that shape:
 *
 * - The ANCHORED series alone -- `VelocityEstimator.estimateAnchored`, the
 *   shipped estimator with `RunawayDrift`'s (#94) correction removed --
 *   carries a largest run of about 39.4 m, the cap demotes it, and NOTHING
 *   pairs: zero spans. (Only the largest run is asserted, not how many
 *   runs the set splits into.)
 *   That is #138's failure, reproduced without a field fixture.
 * - The SHIPPED estimator, which de-trends every run past the cap
 *   (`RunawayDrift.corrected`), resolves TEN of ten.
 *
 * So the re-anchoring #138 asks for is what `RunawayDrift` does, and on this
 * shape it does it. That is the whole claim. It is evidence about a
 * fabricated stream with a CONSTANT bias; a hand-held set's bias moves with
 * the sensor's attitude as it rotates, which no synthetic here models.
 *
 * ## Recovering a rep is not measuring it
 *
 * The ten resolved reps were each generated at 0.40 m and come back between
 * 0.119 m and 1.061 m. The count is right and the ranges are not; pinned
 * exactly so nobody reads the count as a ROM result. The per-rep bound
 * (`RomBound`, `romBounded`) is what withholds set-level figures over reps like
 * these, and it is not re-asserted here.
 *
 * `maxRunDisplacementM` is not touched, on #138's own instruction: raising it
 * was measured there not to fix the dumbbell sets.
 */
class ContinuousCyclingTest {
    private val config = DspConfig()

    private val direction = LiftDirection(startsWith = StartPhase.ECCENTRIC)

    /** Ten 0.40 m reps with no pause, rotating throughout, on a 0.01 g bias. */
    private fun neverQuiet(): List<ImuSample> = SyntheticSets.generate(
        reps = List(10) {
            SyntheticSets.RepSpec(eccS = 1.5, bottomPauseS = 0.0, conS = 1.0, topPauseS = 0.0, romM = 0.40)
        },
        gyroNoiseDps = 30.0,
        seed = 7,
        eccentricFirst = true,
    ).map { it.copy(azG = it.azG + 0.01) }

    @Test
    fun `the synthetic stream has #138's shape - rotating throughout and almost never quiet`() {
        val samples = neverQuiet()
        assertEquals(2800, samples.size, "samples")
        assertEquals(11, samples.count { VelocityEstimator.isQuietSample(it, config) }, "quiet samples")
        val medianGyro = VelocityEstimator.medianGyroDps(samples)
        assertTrue(
            medianGyro > config.stationaryGyroBandDps && medianGyro in 21.9..107.4,
            "median gyro $medianGyro deg/s: above the stationary band and inside #138's hand-held range",
        )
    }

    @Test
    fun `without the runaway correction the set collapses into one over-cap run and resolves nothing`() {
        val anchored = VelocityEstimator.estimateAnchored(neverQuiet(), config, MovementPlane.VERTICAL)
        val runs = RepSegmenter.classifyRunsDetailed(anchored, RunThresholds.sensorFrame(config)).beforeDemotion
        val largest = runs.maxOf { RepSegmenter.displacement(anchored, it.startIdx, it.endIdx) }
        assertEquals(39.37, largest, 0.01, "the largest run on the anchored series, metres")
        assertTrue(largest > config.maxRunDisplacementM, "over the cap, so demoted")
        assertEquals(0, RepSegmenter.segmentDetailed(anchored, direction, config).spans.size, "spans paired")
    }

    @Test
    fun `the shipped estimator re-anchors it and resolves every rep, at ranges it cannot bound`() {
        val a = SetAnalyzer.analyze(neverQuiet(), direction, loadKg = 20.0, config = config)
        assertEquals(10, a.reps.size, "reps resolved; ten were generated")
        val expected = listOf(0.119, 0.327, 0.621, 0.236, 0.218, 0.458, 0.83, 0.125, 0.321, 1.061)
        assertEquals(expected.size, a.reps.size)
        expected.zip(a.reps).forEachIndexed { i, (want, rep) ->
            assertEquals(want, rep.romM, 1e-3, "rep ${i + 1} rom_m; every rep was generated at 0.40 m")
        }
    }
}
