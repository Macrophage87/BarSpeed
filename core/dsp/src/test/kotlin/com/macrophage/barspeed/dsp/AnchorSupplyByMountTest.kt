package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.StartPhase
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Anchor supply, measured per capture and split by what the sensor was
 * mounted on. Issue #87.
 *
 * The ZUPT stage can only correct drift where it finds a zero-velocity anchor,
 * and a window is only offered as a candidate where
 * [VelocityEstimator.isQuietSample] holds for at least
 * [DspConfig.minStationaryS]. That predicate ANDs two terms: acceleration
 * magnitude within [DspConfig.stationaryAccBandG] of 1 g, and gyro magnitude
 * under [DspConfig.stationaryGyroBandDps] = 10 deg/s. The gyro term's premise,
 * written at that constant, is that a resting implement does not rotate at
 * 10 deg/s.
 *
 * That premise is a property of the MOUNT, and this file measures it as one.
 * A sensor clamped to a barbell rotates with the lifter's wrists through the
 * whole set; a sensor on a pull-up assist strap barely rotates at all. The six
 * captures below are the two families side by side, from two sessions recorded
 * a day apart.
 *
 * ## What is pinned here and what it is not
 *
 * These are CHARACTERIZATION pins: they record what the pipeline does today,
 * not what it should do. Anchor coverage is CANDIDATE SUPPLY -- the fraction
 * of the set offered to the anchor walk -- and not correctness. A window that
 * is offered may still be refused by [VelocityEstimator.anchorAcceptable], and
 * one that is accepted may still be the wrong place to pin velocity to zero.
 * Nothing here says the reps these captures resolve are the reps the lifter
 * performed; the hand count is named beside each so the gap stays visible.
 *
 * ## Provenance
 *
 * Every field below is read from the capture's own `meta.json`, never from the
 * file name and never from memory.
 *
 * Session **field-37**, epoch 2026-09-02T09:20:45.365Z, America/New_York, app
 * **0.1.48**, WitMotion WT901BLECL. Four of its thirteen sets:
 *
 * - `field-ohp-3010-6rep-s37-set02` -- set 2, seated_overhead_press, tempo
 *   3010, 24.948 kg (55.0 lb), planned 8, performed **6**, `failed` with
 *   limiter `muscle`, concentric-first, 3892 samples at 99.376819737447 Hz,
 *   roll excursion 74.9 deg, sensor role a (the analysed role). Its cue track
 *   calls **8** reps: the metronome kept counting after the set failed.
 * - `field-bench-3010-6rep-s37-set05` -- set 5, bench_press, 3010,
 *   47.627 kg (105.0 lb), 6 of 6, RPE 7, eccentric-first, 3880 samples at
 *   99.3011289455495 Hz, roll excursion 47.4 deg, role a.
 * - `field-bench-3010-6rep-s37-set06` -- set 6, bench_press, 3010,
 *   49.895 kg (110.0 lb), 6 of 6, RPE 7, eccentric-first, 4180 samples at
 *   99.36042226396253 Hz, roll excursion 72.1 deg, role a.
 * - `field-pullup-3010-8rep-s37-set09` -- set 9, assisted_pull_up, 3010,
 *   23.4436 kg (51.7 lb) of ASSIST, `bodyweight` true, 8 of 8, RPE 8,
 *   concentric-first, 6196 samples at 99.37439846005775 Hz, roll excursion
 *   **0.3 deg**, role a. The strap-family guard: same lifter, same session,
 *   same sensor, a mount that does not rotate.
 *
 * Session **field-36**, epoch 2026-09-01T08:35:19.892Z, America/New_York, app
 * **0.1.47**, same sensor model. Two of its fourteen sets:
 *
 * - `field-rdl-3010-10rep-s36-set05` -- set 5, romanian_deadlift, 3010,
 *   52.163 kg (115.0 lb), 10 of 10, RPE 4, eccentric-first, 6752 samples at
 *   99.3510029285809 Hz, roll excursion 360.0 deg, sensor role a.
 * - `field-backsquat-4011-6rep-s36-set01` -- set 1, back_squat, tempo **4011**,
 *   52.163 kg (115.0 lb), 6 of 6, RPE 1, eccentric-first, 5248 samples at
 *   99.375 Hz, roll excursion 220.3 deg, sensor role **b**. Set 1 is the only
 *   field-36 set that streamed a role-b sensor, and role b is the role its
 *   `meta.json` names as `analysedRole`, so this fixture is the stream the app
 *   itself analysed.
 *
 * ## A correction to the reason field-36 was reached for
 *
 * Thirteen of field-36's fourteen sets publish `summary: {}`. That is NOT
 * anchor starvation and this file does not treat it as such: `meta.json`
 * declares `analysedRole` = `b` on all fourteen sets while only set 1 ever
 * streamed a role-b sensor, so sets 2-14 were analysed against a sensor that
 * produced no samples, and their `sampleRate_hz` is `null`. Those sets include
 * machine work carrying 60-77% anchor coverage, among the highest in the
 * corpus. It is a separate defect, it is not #87, and nothing here fixes it.
 */
class AnchorSupplyByMountTest {
    private fun load(n: String): List<ImuSample> = ImuCsv.decode(
        javaClass.getResourceAsStream("/$n.csv")!!.readBytes().decodeToString(),
    )

    private data class Supply(
        val medianGyroDps: Double,
        /**
         * Samples passing the ABSOLUTE two-term predicate,
         * [VelocityEstimator.isQuietSample]. Named for what it measures rather
         * than for "candidates": since issue #87 the batch mask no longer takes
         * its candidates from this predicate on every set, so on a set whose
         * gyro distribution straddles the gate this figure is SMALLER than the
         * coverage beside it. That inversion is the change, visible.
         */
        val absoluteQuietPct: Double,
        val coveragePct: Double,
        val runs: Int,
    )

    private fun supply(fixture: String, config: DspConfig = DspConfig()): Supply {
        val samples = load(fixture)
        val n = samples.size
        val spanS = (samples.last().timestampMs - samples.first().timestampMs) / 1000.0
        val dt = 1.0 / VelocityEstimator.measureSampleRate(n, spanS)
        val timeS = DoubleArray(n) { it * dt }
        val gyro = DoubleArray(n) { FrameTransform.gyroMagnitudeDps(samples[it]) }
        gyro.sort()
        val mid = n / 2
        val median = if (n % 2 == 1) gyro[mid] else 0.5 * (gyro[mid - 1] + gyro[mid])
        val absolute = samples.count { VelocityEstimator.isQuietSample(it, config) }
        val mask = VelocityEstimator.quietMask(samples, timeS, config)
        var runs = 0
        for (i in 0 until n) if (mask[i] && (i == 0 || !mask[i - 1])) runs++
        return Supply(median, 100.0 * absolute / n, 100.0 * mask.count { it } / n, runs)
    }

    /**
     * Anchor coverage with the gyro clause forced on or off, bypassing
     * [VelocityEstimator.gyroGateApplies]. This is how the differential below
     * is measured now that the shipped mask no longer always applies the
     * clause.
     */
    private fun coverageWithGate(fixture: String, gyroGate: Boolean): Double {
        val samples = load(fixture)
        val n = samples.size
        val spanS = (samples.last().timestampMs - samples.first().timestampMs) / 1000.0
        val dt = 1.0 / VelocityEstimator.measureSampleRate(n, spanS)
        val config = DspConfig()
        val candidate = BooleanArray(n) { VelocityEstimator.isAnchorCandidate(samples[it], config, gyroGate) }
        var quiet = 0
        var runStart = -1
        for (i in 0..n) {
            val inRun = i < n && candidate[i]
            if (inRun && runStart < 0) runStart = i
            if (!inRun && runStart >= 0) {
                if ((i - 1 - runStart) * dt >= config.minStationaryS) quiet += i - runStart
                runStart = -1
            }
        }
        return 100.0 * quiet / n
    }

    /** Every capture on the classpath, as [GyroGateTest] enumerates them. */
    private val corpus: List<String> by lazy {
        File(javaClass.getResource("/field-still-0rep.csv")!!.toURI()).parentFile.list()!!
            .filter { it.startsWith("field-") && it.endsWith(".csv") && !it.endsWith("-cues.csv") }
            .map { it.removeSuffix(".csv") }
            .sorted()
    }

    private fun batchReps(fixture: String, startsWith: StartPhase, loadKg: Double): Int =
        SetAnalyzer.analyze(load(fixture), LiftDirection(startsWith), loadKg).reps.size

    private fun liveReps(fixture: String, startsWith: StartPhase): Int {
        val tracker = StreamingSetTracker(startsWith)
        var last = LiveSetState()
        load(fixture).forEach { last = tracker.feed(it) }
        return last.repCount
    }

    private fun assertSupply(
        fixture: String,
        medianGyroDps: Double,
        absoluteQuietPct: Double,
        coveragePct: Double,
        runs: Int,
    ) {
        val s = supply(fixture)
        assertEquals(medianGyroDps, s.medianGyroDps, 0.01, "$fixture: median gyro magnitude, deg/s")
        assertEquals(absoluteQuietPct, s.absoluteQuietPct, 0.01, "$fixture: samples passing isQuietSample, %")
        assertEquals(coveragePct, s.coveragePct, 0.01, "$fixture: anchor coverage, %")
        assertEquals(runs, s.runs, "$fixture: qualifying quiet runs")
    }

    @Test
    fun `the bar family's anchor supply`() {
        // Four of the five bar sets straddle the gate -- median above it, tenth
        // percentile below -- so since issue #87 the gyro clause is not applied
        // to them and coverage is the acceleration term's alone. The
        // isQuietSample column is the ABSOLUTE two-term figure and no longer
        // describes the mask: on all four, coverage is now LARGER than the
        // share of samples that predicate admits.
        //
        //                             median  isQuiet%  coverage%  runs
        //   ohp   s37 set02           16.74     26.52     10.38 ->  23.54
        //   bench s37 set05           23.92     20.03      6.55 ->  26.62
        //   bench s37 set06           32.32     14.95      8.16 ->  27.46
        //   squat s36 set01           15.50     26.37     18.39 ->  48.57
        assertSupply("field-ohp-3010-6rep-s37-set02", 16.742, 26.516, 23.535, 13)
        assertSupply("field-bench-3010-6rep-s37-set05", 23.923, 20.026, 26.624, 7)
        assertSupply("field-bench-3010-6rep-s37-set06", 32.318, 14.952, 27.464, 7)
        assertSupply("field-backsquat-4011-6rep-s36-set01", 15.497, 26.372, 48.571, 28)
        // The fifth is the counter-example inside the bar family and is named
        // as one: the RDL's median is 6.42 deg/s, BELOW the gate, so it does
        // not straddle, the clause still applies, and its figures are
        // bit-identical to before issue #87. It still resolves nothing.
        // Whatever costs that set its reps, the gyro gate is not it.
        assertSupply("field-rdl-3010-10rep-s36-set05", 6.423, 48.504, 35.545, 11)
    }

    @Test
    fun `the strap family's anchor supply is untouched by the change`() {
        // One capture, and it is the guard: the same sensor, same lifter, same
        // session, on a mount that does not rotate. Its whole gyro
        // distribution sits under the gate, so the straddle test cannot fire
        // and every figure here is bit-identical to what it was before issue
        // #87 -- the same numbers this file pinned at c0.
        assertSupply("field-pullup-3010-8rep-s37-set09", 0.936, 82.408, 69.981, 25)
    }

    @Test
    fun `what the two families resolve, against the counts performed`() {
        // field-ohp-...-set02, -bench-...-set05 and -set06 are the three
        // field-37 sets that publish `summary: {}` in the session archive,
        // which is issue #138. All three resolved NOTHING before issue #87 and
        // all three resolve something now -- 4, 1 and 3 against 6 performed.
        // None of them is right. Two of the three are still further from the
        // lifter's count than from zero, and this file claims only that the
        // sets stopped publishing nothing, not that they became correct.
        //
        // The RDL is unchanged at 0 of 10, which is the counter-example: it
        // does not straddle the gate, so #87 does not touch it.
        assertEquals(4, batchReps("field-ohp-3010-6rep-s37-set02", StartPhase.CONCENTRIC, 24.948), "ohp, 6 performed")
        assertEquals(
            1,
            batchReps("field-bench-3010-6rep-s37-set05", StartPhase.ECCENTRIC, 47.627),
            "bench, 6 performed",
        )
        assertEquals(
            3,
            batchReps("field-bench-3010-6rep-s37-set06", StartPhase.ECCENTRIC, 49.895),
            "bench, 6 performed",
        )
        assertEquals(0, batchReps("field-rdl-3010-10rep-s36-set05", StartPhase.ECCENTRIC, 52.163), "rdl, 10 performed")
        // The back squat used to over-resolve, 8 detections for 6 reps, and
        // now lands exactly on the count performed.
        assertEquals(
            6,
            batchReps("field-backsquat-4011-6rep-s36-set01", StartPhase.ECCENTRIC, 52.163),
            "back squat, 6 performed",
        )
        assertEquals(
            5,
            batchReps("field-pullup-3010-8rep-s37-set09", StartPhase.CONCENTRIC, 23.4436),
            "assisted pull-up, 8 performed",
        )
        // Live, for the same six. The live path reads its own predicate sample
        // by sample and has no set to take a distribution over.
        assertEquals(0, liveReps("field-ohp-3010-6rep-s37-set02", StartPhase.CONCENTRIC), "ohp live")
        assertEquals(0, liveReps("field-bench-3010-6rep-s37-set05", StartPhase.ECCENTRIC), "bench live")
        assertEquals(0, liveReps("field-bench-3010-6rep-s37-set06", StartPhase.ECCENTRIC), "bench live")
        assertEquals(0, liveReps("field-rdl-3010-10rep-s36-set05", StartPhase.ECCENTRIC), "rdl live")
        assertEquals(7, liveReps("field-backsquat-4011-6rep-s36-set01", StartPhase.ECCENTRIC), "back squat live")
        assertEquals(2, liveReps("field-pullup-3010-8rep-s37-set09", StartPhase.CONCENTRIC), "assisted pull-up live")
    }

    /** Anchor coverage this file requires bar-mounted work to clear, per cent. */
    private val barCoverageFloorPct = 20.0

    @Test
    fun `the contract - bar coverage clears the floor and no gate-holding capture moves`() {
        // The two halves of issue #87's bargain, asserted together because
        // either alone is satisfiable by doing nothing useful. Widening the
        // predicate everywhere would clear the floor and wreck the strap
        // family; leaving the predicate alone would preserve the strap family
        // and leave the floor uncleared.
        //
        // FLOOR: 20% is stated, not derived. It sits above the 18.4% the best
        // of the five bar captures managed before the change and below the
        // 23.5% the worst manages after it, so it is a line the change crosses
        // and nothing else in the corpus's history does. It is a coverage
        // floor, not a correctness floor -- see this file's KDoc.
        val bar = listOf(
            "field-ohp-3010-6rep-s37-set02",
            "field-bench-3010-6rep-s37-set05",
            "field-bench-3010-6rep-s37-set06",
            "field-backsquat-4011-6rep-s36-set01",
            "field-rdl-3010-10rep-s36-set05",
        )
        bar.forEach { fixture ->
            val coverage = supply(fixture).coveragePct
            assertTrue(
                coverage >= barCoverageFloorPct,
                "$fixture: anchor coverage $coverage%, floor $barCoverageFloorPct%",
            )
        }

        // NO GATE-HOLDING CAPTURE MOVES: on every capture where
        // gyroGateApplies is true the shipped mask must be the gate-on mask
        // sample for sample. That is stronger than pinning rep counts -- a
        // span cannot change if the mask it is derived from cannot -- and it
        // covers every strap, rope, stack and machine capture at once.
        val config = DspConfig()
        val holding = corpus.filter { VelocityEstimator.gyroGateApplies(load(it), config) }
        assertEquals(21, holding.size, "captures the gate still applies to")
        holding.forEach { fixture ->
            assertEquals(
                coverageWithGate(fixture, gyroGate = true),
                supply(fixture).coveragePct,
                0.0,
                "$fixture: the gate holds here, so the mask must be bit-identical",
            )
        }

        // And the spans themselves, on the strap family and one machine
        // capture from each session, named rather than left to the mask
        // argument alone.
        assertEquals(
            5,
            batchReps("field-pullup-3010-8rep-s37-set09", StartPhase.CONCENTRIC, 23.4436),
            "assisted pull-up spans, 8 performed -- unchanged by #87",
        )
        assertEquals(
            10,
            batchReps("field-legpress-single-2011-8rep-s36-set07", StartPhase.CONCENTRIC, 20.0),
            "single leg press spans, 8 performed -- unchanged by #87",
        )
        assertEquals(
            0,
            batchReps("field-still-0rep", StartPhase.ECCENTRIC, 20.0),
            "a sensor that did not move still resolves nothing",
        )
    }

    @Test
    fun `the gyro term is what separates the two families, not the acceleration term`() {
        // The differential that makes this a gyro finding rather than a general
        // "these sets are noisy" one. Measured with the clause forced on and
        // forced off, bypassing the straddle test, because the shipped mask no
        // longer applies the clause on these four and comparing it against
        // itself would compare two identical numbers -- a check that cannot
        // fail.
        listOf(
            "field-ohp-3010-6rep-s37-set02",
            "field-bench-3010-6rep-s37-set05",
            "field-bench-3010-6rep-s37-set06",
            "field-backsquat-4011-6rep-s36-set01",
        ).forEach { f ->
            val gated = coverageWithGate(f, gyroGate = true)
            val accOnly = coverageWithGate(f, gyroGate = false)
            assertTrue(accOnly > 2.0 * gated, "$f: acc-only coverage $accOnly against $gated with the gyro clause")
            // And the shipped mask now takes the second of the two.
            assertEquals(accOnly, supply(f).coveragePct, 0.01, "$f: the shipped mask drops the clause here")
        }
        val strap = "field-pullup-3010-8rep-s37-set09"
        assertEquals(
            coverageWithGate(strap, gyroGate = true),
            coverageWithGate(strap, gyroGate = false),
            0.0,
            "$strap: the gyro clause removes nothing on a mount that does not rotate",
        )
        assertEquals(
            coverageWithGate(strap, gyroGate = true),
            supply(strap).coveragePct,
            0.0,
            "$strap: and the shipped mask still applies it",
        )
    }
}
