package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.StartPhase
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression fixtures from real gym sessions. Three failure modes are pinned:
 *
 * 1. 10 Hz factory-default rate (the app failed to raise it — see
 *    WitmotionCommands.unlock): heavy attenuation, live tracker used to lock
 *    into a phantom "Lowering" phase forever.
 * 2. 100 Hz with bursty BLE arrivals: many frames share one arrival timestamp
 *    (median dt = 0 ms), so integrating against arrival times collapsed most
 *    of the signal, and rotation-induced gravity-projection bias drifted the
 *    integrator past the ZUPT rejection band.
 * 3. Segmentation that does not agree with the lifter, in both directions —
 *    the `field-{ohp,bench,cablerow,facepull,pallof}-*` fixtures below.
 *
 * ## Provenance of the seven 2026-08-17 fixtures
 *
 * One session, 17 sets, WitMotion WT901BLECL, recorded on app 0.1.37 — the
 * version this commit builds. `repsManual` is true on all 17 sets: the lifter
 * counted, so this is the first capture this repo has had with a known answer.
 * The seven files here are seven of those sets, copied byte for byte out of the
 * session's raw export; nothing was re-encoded, resampled or trimmed.
 *
 * What the session says as a whole: the lifter performed 158 reps and the
 * analyzer segmented 143, and the error is not one-signed. Rotating
 * free-weight lifts lose reps (overhead press 17 of 32, bench 13 of 18) and
 * stack-mounted cable work invents them (face pull 38 for 36, pallof 50 for
 * 48); the seated cable row lands on 25 for 24 with misses and inventions in
 * the same sets. `sample_idx` is contiguous in all seven files, so nothing was
 * dropped between the sensor and the CSV — none of this is a BLE gap.
 *
 * The opening phase each fixture is analysed with is not a guess. The plan's
 * declared geometry is not in the export (it is from this session that issue 73
 * was found), so it was recovered by sweeping direction flags until the
 * analysis reproduced the export: with these settings all 17 sets reproduce
 * their exported `repMetrics` — rep count, ecc_s, con_s, rom_m, meanConVel_mps
 * and peakPower_w — to the last published digit. Geometry combinations that
 * resolve to the same measured plane and the same sensor-to-lifter factor are
 * numerically identical and cannot be told apart from the data; `plane` and
 * `sensorOnStack` are therefore left at their defaults rather than asserted.
 *
 * Two things that fell out of that reproduction and are NOT addressed here:
 * the overhead-press sets only reproduce eccentric-first, although
 * `ExerciseDef.inferStartPhase("seated_overhead_press")` returns CONCENTRIC
 * today because "overhead" is a hint — consistent with a stored
 * CustomExerciseEntity predating that hint, which nothing updates; and the
 * cable sets were measured with `sensorInverted = false`, which is why they
 * carry published power figures at all.
 *
 * Rep counts here are pinned EXACTLY, against the count the lifter performed
 * named beside them. They used to be ranges — `reps.size in 4..9` for a set
 * the same line called "(8 real)" — and a band that wide cannot fail: it
 * passed at 4 of 8, so any candidate improvement to segmentation would have
 * been graded against an assertion that ratified the defect. An exact pin
 * records what the analyzer does TODAY and nothing about what it should do;
 * a fix is expected to red it, and to replace it with its inversion.
 */
class FieldDataRegressionTest {
    private fun load(name: String) =
        ImuCsv.decode(javaClass.getResourceAsStream("/$name")!!.readBytes().decodeToString())

    @Test
    fun `batch analysis segments slow squats from the 10 Hz field set`() {
        val samples = load("field-backsquat-10hz.csv")
        val analysis = SetAnalyzer.analyze(samples, StartPhase.ECCENTRIC, loadKg = 47.6)
        // The lifter did exactly 5 slow-tempo reps. The analyzer finds 4: the
        // file ends mid-re-rack, which smears the final rep. Pinned exactly,
        // because the old `in 4..6` band could not tell 4 from 5 and so could
        // not tell a fix from a coincidence.
        assertEquals(4, analysis.reps.size, "segmented reps; the lifter performed 5")
        val sane = analysis.reps.count { (it.eccS ?: 0.0) in 2.5..8.0 && it.romM < 1.5 }
        assertEquals(4, sane, "reps showing the slow-tempo character, of ${analysis.reps.size} segmented")
    }

    @Test
    fun `streaming tracker follows the 10 Hz field set without locking up`() {
        val samples = load("field-backsquat-10hz.csv")
        val tracker = StreamingSetTracker(StartPhase.ECCENTRIC)
        var last = LiveSetState()
        samples.forEach { last = tracker.feed(it) }
        // Live counting is best-effort at 10 Hz (grinder concentrics measure
        // near the noise floor); the batch analyzer is authoritative for the
        // stored count. What must never happen is the field failure mode:
        // a phantom phase lock-in or runaway velocity.
        assertEquals(4, last.repCount, "live rep count; the lifter performed 5")
        // Deliberately still a bound: this one asks "did the integrator run
        // away", which is a one-sided catastrophe question, not a measurement.
        assertTrue(abs(last.velocityMps) < 0.25, "velocity drifted: ${last.velocityMps}")
    }

    @Test
    fun `two quiet minutes on the rack stay anchored with no phantom reps (set 5)`() {
        val samples = load("field-backsquat-10hz-set5.csv")
        val startMs = samples.first().timestampMs
        val tracker = StreamingSetTracker(StartPhase.ECCENTRIC)
        var quietMaxV = 0.0
        var repsDuringQuiet = 0
        samples.forEach { sample ->
            val state = tracker.feed(sample)
            if (sample.timestampMs - startMs < 120_000) {
                quietMaxV = maxOf(quietMaxV, abs(state.velocityMps))
                repsDuringQuiet = state.repCount
            }
        }
        assertTrue(quietMaxV < 0.1, "velocity drifted to $quietMaxV during a quiet stretch")
        assertEquals(0, repsDuringQuiet, "phantom reps while the bar sat still")
    }

    @Test
    fun `batch analysis recovers the true rate and reps from bursty 100 Hz arrivals`() {
        val samples = load("field-ohp-100hz-bursty.csv")
        val analysis = SetAnalyzer.analyze(samples, StartPhase.CONCENTRIC, loadKg = 29.5)
        // 8 real overhead-press reps; arrival timestamps come in ~90 ms bursts
        // with median dt = 0, so only a span-based rate estimate works.
        assertTrue(
            analysis.sampleRateHz in 90.0..110.0,
            "measured rate ${analysis.sampleRateHz} (sensor streamed 100 Hz)",
        )
        assertEquals(7, analysis.reps.size, "segmented reps; the lifter performed 8")
        // Continuous cycling leaves stretches with no ZUPT anchor, so some reps
        // ride on residual drift; only a core of these look kinematically like
        // presses. (Before this fix the analyzer produced single "reps"
        // spanning 50 s and 150 m of ROM.) Pinned exactly: `sane >= 3` was true
        // of any outcome from 3 upwards and so measured nothing.
        val sane = analysis.reps.count { it.conS in 0.2..2.5 && it.romM in 0.2..1.2 }
        assertEquals(4, sane, "reps that look like real presses, of ${analysis.reps.size} segmented")
        // Deliberately still bounds: one-sided runaway-drift guards, not
        // measurements of the lift.
        analysis.reps.forEach { rep ->
            assertTrue(rep.conS < 10.0, "concentric ${rep.conS}s is runaway drift")
            assertTrue(rep.romM < 4.0, "ROM ${rep.romM}m is runaway drift")
        }
    }

    @Test
    fun `slow-eccentric seated press counts on the drive alone`() {
        // 2 real reps of seated OHP at tempo 3010: the ~5 s lowering averages
        // ~0.08 m/s — under the run threshold — so batch pairing that REQUIRED
        // a down run used to find 0 reps here. Con-first counting keys on the
        // drive; the eccentric is optional metric data. The second press sits
        // inside end-of-set re-rack drift (a 9 m phantom run, discarded by the
        // displacement cap), so batch recovers the first press only.
        val samples = load("field-seated-ohp-2rep.csv")
        val analysis = SetAnalyzer.analyze(samples, StartPhase.CONCENTRIC, loadKg = 20.4)
        assertEquals(1, analysis.reps.size, "segmented reps; the lifter performed 2 presses")
        analysis.reps.forEach { rep ->
            assertTrue(rep.conS in 0.2..2.5, "concentric ${rep.conS}s is implausible")
            assertTrue(rep.romM in 0.1..1.2, "ROM ${rep.romM}m is implausible")
        }

        val tracker = StreamingSetTracker(StartPhase.CONCENTRIC)
        var last = LiveSetState()
        samples.forEach { last = tracker.feed(it) }
        assertEquals(2, last.repCount, "live count should match the 2 real presses")
    }

    @Test
    fun `streaming tracker counts presses live despite bursty arrivals`() {
        val samples = load("field-ohp-100hz-bursty.csv")
        // Presses from the rack position drive up first — concentric-first
        // counting is what makes the reps land (ecc-first pairing found 1 of 8).
        val tracker = StreamingSetTracker(StartPhase.CONCENTRIC)
        var last = LiveSetState()
        samples.forEach { last = tracker.feed(it) }
        assertEquals(5, last.repCount, "live rep count; the lifter performed 8")
        assertTrue(abs(last.velocityMps) < 0.25, "velocity drifted: ${last.velocityMps}")
    }

    // ------------------------------------------------------------------
    // Session of 2026-08-17. See the class KDoc for provenance.
    // ------------------------------------------------------------------

    /** One set of that session, as [SetAnalyzer] is invoked on it here. */
    private data class FieldSet(val file: String, val startsWith: StartPhase, val loadKg: Double)

    /**
     * The seven captures, in session order. `loadKg` is the set's exported
     * load; `startsWith` is the opening phase the app used that day, recovered
     * by reproducing the export rather than assumed — see the class KDoc.
     */
    private val session20260817 =
        listOf(
            FieldSet("field-ohp-rotating-8rep.csv", StartPhase.ECCENTRIC, 20.411656650451594),
            FieldSet("field-ohp-rotating-8rep-b.csv", StartPhase.ECCENTRIC, 24.94758035055195),
            FieldSet("field-bench-rotating-6rep-ok.csv", StartPhase.ECCENTRIC, 43.091275150953365),
            FieldSet("field-bench-rotating-6rep.csv", StartPhase.ECCENTRIC, 43.091275150953365),
            FieldSet("field-cablerow-static-8rep.csv", StartPhase.CONCENTRIC, 27.215542200602126),
            FieldSet("field-facepull-static-12rep.csv", StartPhase.CONCENTRIC, 9.97903214022078),
            FieldSet("field-pallof-static-12rep.csv", StartPhase.CONCENTRIC, 11.79340234968141),
        )

    private fun fixture(file: String) = session20260817.first { it.file == file }

    private fun analyze(file: String) =
        fixture(file).let { SetAnalyzer.analyze(load(it.file), it.startsWith, loadKg = it.loadKg) }

    /** The series [SetAnalyzer] runs on, for facts it does not publish. */
    private fun series(fs: FieldSet): VelocitySeries {
        val direction = LiftDirection(startsWith = fs.startsWith)
        return VelocityEstimator.estimate(load(fs.file), DspConfig(), direction.measuredPlane)
            .mappedToLifter(direction.sensorToLifter)
    }

    private fun spans(fs: FieldSet, s: VelocitySeries) =
        RepSegmenter.segment(s, LiftDirection(startsWith = fs.startsWith), DspConfig())

    /** Signed vertical displacement over the whole set, metres. */
    private fun netDisplacementM(s: VelocitySeries): Double {
        var d = 0.0
        for (i in 1 until s.size) {
            val dt = s.timeS[i] - s.timeS[i - 1]
            d += 0.5 * (s.velocityMps[i] + s.velocityMps[i - 1]) * dt
        }
        return d
    }

    /** Per rep, |eccentric path travelled − concentric path travelled|, metres. */
    private fun phaseAsymmetriesM(fs: FieldSet): List<Double> {
        val s = series(fs)
        return spans(fs, s).filter { it.hasEccentric }.map { span ->
            abs(
                RepSegmenter.displacement(s, span.eccStartIdx, span.eccEndIdx) -
                    RepSegmenter.displacement(s, span.conStartIdx, span.conEndIdx),
            )
        }
    }

    /**
     * Per rep, the fraction of its reported ROM accrued over samples the IMU
     * itself calls still ([VelocityEstimator.quietMask]: acceleration magnitude
     * within 0.05 g of 1 g and gyro magnitude under 10 deg/s, held for 0.3 s).
     * Distance accrued there did not happen; it is integrator drift.
     */
    private fun quietRomFractions(fs: FieldSet): List<Double> {
        val samples = load(fs.file)
        val s = series(fs)
        val quiet = VelocityEstimator.quietMask(samples, s.timeS, DspConfig())
        return spans(fs, s).map { span ->
            var total = 0.0
            var still = 0.0
            for (i in span.conStartIdx + 1..span.conEndIdx) {
                val step = abs(s.velocityMps[i]) * (s.timeS[i] - s.timeS[i - 1])
                total += step
                if (quiet[i]) still += step
            }
            still / total
        }
    }

    /**
     * Equality of measured doubles at three decimal places. Not a fitted band:
     * every quantity pinned with it moves by 0.05 or more when the number of
     * segmented reps changes by one, so the window is four to three orders of
     * magnitude narrower than anything it is meant to detect.
     */
    private fun assertMeasured(expected: List<Double>, actual: List<Double>, label: String) {
        assertEquals(expected.size, actual.size, "$label: number of values")
        expected.indices.forEach { i -> assertEquals(expected[i], actual[i], 1e-3, "$label: rep ${i + 1}") }
    }

    @Test
    fun `rotating overhead press resolves 3 of the 8 reps performed (pre-fix)`() {
        // Set 1: seated overhead press, 45 lb warm-up, tempo 3010. The lifter
        // counted 8. The sensor rolled through 40.4 deg over the set.
        val analysis = analyze("field-ohp-rotating-8rep.csv")
        assertEquals(3, analysis.reps.size, "segmented reps; the lifter performed 8")
    }

    @Test
    fun `a second rotating overhead press resolves 4 of the 8 reps performed (pre-fix)`() {
        // Set 4: same lift at 55 lb, 8 counted, sensor rolled 37.1 deg. Kept
        // alongside set 1 because the shape of the loss differs — set 1 drops
        // its first five reps, this one drops four spread through the set.
        val analysis = analyze("field-ohp-rotating-8rep-b.csv")
        assertEquals(4, analysis.reps.size, "segmented reps; the lifter performed 8")
    }

    @Test
    fun `rotating bench press resolves 2 of the 6 reps performed (pre-fix)`() {
        // Set 6: bench press, 95 lb, 6 counted, sensor rolled 46.1 deg.
        val analysis = analyze("field-bench-rotating-6rep.csv")
        assertEquals(2, analysis.reps.size, "segmented reps; the lifter performed 6")
    }

    @Test
    fun `the bench press capture that works resolves all 6 reps performed`() {
        // Set 5: the SAME lift, load, tempo and mount as the set above, one
        // rest period earlier, and the sensor rolled 42.8 deg — no less. This
        // one comes out right. It is the negative control: a fix that recovers
        // the reps set 6 loses must not disturb this, and if it does, what it
        // found was not the mechanism. No `(pre-fix)` marker: this assertion is
        // expected to survive.
        val analysis = analyze("field-bench-rotating-6rep-ok.csv")
        assertEquals(6, analysis.reps.size, "segmented reps; the lifter performed 6")
    }

    @Test
    fun `stack-mounted cable row resolves 8 for 8 by cancelling misses against drift (pre-fix)`() {
        // Set 8: seated cable row, 60 lb warm-up, 8 counted, sensor on the
        // weight stack and rolling 0.5 deg over the whole set. The count agrees
        // with the lifter and the reps do not: two of the eight carry a tenth
        // of the others' velocity. A count alone cannot see this, which is why
        // the per-rep pins below exist.
        val analysis = analyze("field-cablerow-static-8rep.csv")
        assertEquals(8, analysis.reps.size, "segmented reps; the lifter performed 8")
        // Velocity loss is best rep to LAST rep. The last rep here is one of
        // the two slow ones, so the set the lifter completed as prescribed is
        // reported to them as an 81% drawdown.
        assertEquals<Double?>(80.9, analysis.velocityLossPct, "velocity loss reported to the lifter")
    }

    @Test
    fun `stack-mounted face pull resolves 13 reps for 12 performed (pre-fix)`() {
        // Set 11: cable face pull, 22 lb, tempo 2011, 12 counted, sensor on the
        // stack rolling 0.7 deg. A 13th rep appears, and it is the only rep in
        // the set over 1.2 m of ROM — 1.662 m on a face pull.
        val analysis = analyze("field-facepull-static-12rep.csv")
        assertEquals(13, analysis.reps.size, "segmented reps; the lifter performed 12")
        assertEquals(1, analysis.reps.count { it.romM > 1.2 }, "reps claiming over 1.2 m of ROM")
        assertEquals<Double?>(1.662, analysis.reps.last().romM, "ROM of the extra rep, metres")
        // That extra rep is also the fastest in the set, and velocity loss is
        // measured best-to-last, so best and last are the same rep and the
        // export tells a lifter who took a 12-rep set to RPE 6 that they lost
        // no velocity at all.
        assertEquals<Double?>(0.0, analysis.velocityLossPct, "velocity loss reported to the lifter")
    }

    @Test
    fun `stack-mounted pallof press resolves 13 reps for 12 performed (pre-fix)`() {
        // Set 17: cable pallof press, 26 lb, tempo 2011, 12 counted, sensor on
        // the stack rolling 0.4 deg. Same shape as the face pull above and the
        // last set of the session, so nothing after it could have corrected it.
        val analysis = analyze("field-pallof-static-12rep.csv")
        assertEquals(13, analysis.reps.size, "segmented reps; the lifter performed 12")
        assertEquals(1, analysis.reps.count { it.romM > 1.2 }, "reps claiming over 1.2 m of ROM")
        assertEquals<Double?>(1.275, analysis.reps.last().romM, "ROM of the extra rep, metres")
        assertEquals<Double?>(0.0, analysis.velocityLossPct, "velocity loss reported to the lifter")
    }

    @Test
    fun `every set ends where it started and the integrator says otherwise (pre-fix)`() {
        // Physically zero for all seven: every set begins and ends with the
        // load at rest in the same place, racked or resting on the stack, so
        // the signed vertical displacement across the whole capture is nothing.
        // These are the metres the integrator produces instead. No ground truth
        // is needed to read them and no candidate fix can argue with them.
        //
        // The two captures that segment correctly are also the two closest to
        // zero, which is suggestive and is not a finding: two points.
        val todayM =
            mapOf(
                "field-ohp-rotating-8rep.csv" to 16.142,
                "field-ohp-rotating-8rep-b.csv" to 16.357,
                "field-bench-rotating-6rep-ok.csv" to -1.010,
                "field-bench-rotating-6rep.csv" to 9.615,
                "field-cablerow-static-8rep.csv" to -0.259,
                "field-facepull-static-12rep.csv" to 4.687,
                "field-pallof-static-12rep.csv" to 4.383,
            )
        session20260817.forEach { fs ->
            assertMeasured(listOf(todayM.getValue(fs.file)), listOf(netDisplacementM(series(fs))), fs.file)
        }
    }

    @Test
    fun `a closed rep travels the same distance in both phases and these do not (pre-fix)`() {
        // Physically zero per rep, for the same reason: a rep that returns to
        // where it started travels as far down as up, so the eccentric and
        // concentric path lengths are equal. Pinned as the worst asymmetry in
        // each capture, in metres, over the reps that resolved an eccentric at
        // all — the count of those is pinned too, because a fix that "improves"
        // this figure by resolving fewer eccentrics has not improved anything.
        val today =
            mapOf(
                "field-ohp-rotating-8rep.csv" to (3 to 1.244),
                "field-ohp-rotating-8rep-b.csv" to (4 to 1.011),
                "field-bench-rotating-6rep-ok.csv" to (6 to 0.721),
                "field-bench-rotating-6rep.csv" to (2 to 0.068),
                "field-cablerow-static-8rep.csv" to (4 to 1.263),
                "field-facepull-static-12rep.csv" to (2 to 0.258),
                "field-pallof-static-12rep.csv" to (7 to 0.240),
            )
        session20260817.forEach { fs ->
            val (reps, worst) = today.getValue(fs.file)
            val measured = phaseAsymmetriesM(fs)
            assertEquals(reps, measured.size, "${fs.file}: reps that resolved an eccentric")
            assertMeasured(listOf(worst), listOf(measured.max()), "${fs.file}: worst asymmetry")
        }
    }

    @Test
    fun `fabricated reps accrue their ROM while the IMU reads still (pre-fix)`() {
        // The sharpest per-rep signal this session produced for a rep that did
        // not happen. See [quietRomFractions]: distance credited to a rep while
        // the sensor reports itself stationary is drift, not movement.
        //
        // It does NOT separate cleanly and this pin states no threshold. The
        // two reps that are fabricated BY COUNT — the 13th of a 12-rep face
        // pull and the 13th of a 12-rep pallof press — read 0.984 and 0.940.
        // Two more, reps 6 and 8 of the cable row, are fabricated by inference
        // rather than by count (that set segments 8 for 8, and those are its
        // only two reps under 0.12 m/s mean velocity); they read 0.734 and
        // 0.954. Against that, the highest reading among reps with no reason to
        // be doubted is 0.650, face pull rep 9. A gap of 0.084 on four
        // examples is not a rule, and the reason is known: a slow cable rep is
        // IMU-quiet too, which is exactly why VelocityEstimator cannot use
        // acceleration alone to place its anchors.
        //
        // Pinned as measured values so that a fix which stops fabricating reps
        // moves them visibly. An earlier report of this signal put it at 94-98%
        // on fabricated reps and under 25% on every real one, with no overlap.
        // That does not hold on the full session and is retracted here.
        val today =
            mapOf(
                "field-ohp-rotating-8rep.csv" to listOf(0.000, 0.000, 0.000),
                "field-ohp-rotating-8rep-b.csv" to listOf(0.000, 0.000, 0.000, 0.000),
                "field-bench-rotating-6rep-ok.csv" to listOf(0.000, 0.000, 0.000, 0.000, 0.000, 0.000),
                "field-bench-rotating-6rep.csv" to listOf(0.000, 0.000),
                "field-cablerow-static-8rep.csv" to
                    listOf(0.000, 0.089, 0.000, 0.000, 0.000, 0.734, 0.000, 0.954),
                "field-facepull-static-12rep.csv" to
                    listOf(
                        0.503, 0.000, 0.383, 0.329, 0.120, 0.340, 0.003,
                        0.348, 0.650, 0.217, 0.002, 0.125, 0.984,
                    ),
                "field-pallof-static-12rep.csv" to
                    listOf(
                        0.000, 0.141, 0.004, 0.155, 0.016, 0.109, 0.013,
                        0.110, 0.006, 0.329, 0.009, 0.539, 0.940,
                    ),
            )
        session20260817.forEach { fs ->
            assertMeasured(today.getValue(fs.file), quietRomFractions(fs), fs.file)
        }
    }
}
