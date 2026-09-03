package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.Phase
import com.macrophage.barspeed.model.StartPhase
import com.macrophage.barspeed.model.Tempo
import com.macrophage.barspeed.model.VoiceCue
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression fixtures from real gym sessions. Three failure modes are pinned,
 * plus one control:
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
 * 4. A control: `field-still-0rep.csv`, 45 s of a sensor that did not move.
 *    `field-backsquat-10hz-set5.csv` already asks whether reps are invented,
 *    live and across a quiet stretch of a set that does contain reps; this one
 *    extends that question to the batch path, to both opening phases, and to a
 *    whole capture with no reps in it at all.
 *
 * ## Provenance of the seven fixtures from the 17-set 0.1.37 session
 *
 * One session, 17 sets, WitMotion WT901BLECL, recorded on app 0.1.37.
 * `repsManual` is true on all 17 sets: the lifter counted, so this is the first
 * capture this repo has had with a known answer. The seven files here are seven
 * of those sets, copied byte for byte out of the session's raw export; nothing
 * was re-encoded, resampled or trimmed.
 *
 * What the session says as a whole: the lifter performed 158 reps and the
 * analyzer segmented 143, and the error is not one-signed. Rotating
 * free-weight lifts lose reps (overhead press 17 of 32, bench 13 of 18) and
 * stack-mounted cable work invents them (face pull 38 for 36, pallof 50 for
 * 48); the seated cable row lands on 25 for 24 with misses and inventions in
 * the same sets.
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
        // The lifter did exactly 5 slow-tempo reps and the analyzer now finds
        // SIX. Issue #94's runaway correction took this from 4 to 6, so it
        // crossed the count rather than landing on it: the last 6.7 s of this
        // capture is the re-rack, it was one over-cap run the segmenter
        // discarded whole, and de-trending it yields two detections where one
        // real rep was left to find. Under-count became over-count here.
        // Pinned exactly, because the old `in 4..6` band could not tell 4 from
        // 5 and so could not tell a fix from a coincidence.
        assertEquals(6, analysis.reps.size, "segmented reps; the lifter performed 5")
        val sane = analysis.reps.count { (it.eccS ?: 0.0) in 2.5..8.0 && it.romM < 1.5 }
        assertEquals(5, sane, "reps showing the slow-tempo character, of ${analysis.reps.size} segmented")
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
        assertEquals(10, analysis.reps.size, "segmented reps; the lifter performed 8")
        // Continuous cycling leaves stretches with no ZUPT anchor, so some reps
        // ride on residual drift; only a core of these look kinematically like
        // presses. (Before this fix the analyzer produced single "reps"
        // spanning 50 s and 150 m of ROM.) Pinned exactly: `sane >= 3` was true
        // of any outcome from 3 upwards and so measured nothing.
        val sane = analysis.reps.count { it.conS in 0.2..2.5 && it.romM in 0.2..1.2 }
        assertEquals(6, sane, "reps that look like real presses, of ${analysis.reps.size} segmented")
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
        // drive; the eccentric is optional metric data.
        //
        // The count reaching 2 here is NOT the count being right, and the
        // assertions below are written so nobody can read it that way. Traced:
        // the second rep is a 4.28 s "concentric" spanning 14.60-18.87 s,
        // admitted through a STARVATION anchor at 13.78 s that carries a
        // 0.988 m/s step — the escape hatch in applyZupt, which is deliberately
        // not gated by the accept rule. A press is about a second. So the
        // second rep is an artefact that happens to land on the right total,
        // and the concentric bound is pinned at what it actually is rather than
        // at what a press looks like.
        val samples = load("field-seated-ohp-2rep.csv")
        val analysis = SetAnalyzer.analyze(samples, StartPhase.CONCENTRIC, loadKg = 20.4)
        // THREE detections for two presses since issue #94's runaway
        // correction. The 5.5-11.5 s stretch was one over-cap run and is now
        // two detections of 1.42 s and 1.73 s; the 4.28 s artefact described
        // above survives unchanged. So this capture went from the right total
        // by cancellation to one too many, and the reps whose drive lasts a
        // press went from 1 of 2 to 2 of 3.
        assertEquals(3, analysis.reps.size, "segmented reps; the lifter performed 2 presses")
        assertEquals(3, analysis.reps.count { it.eccS == null }, "reps counted on the drive alone")
        assertMeasured(listOf(1.42, 1.73, 4.28), analysis.reps.map { it.conS }, "concentric seconds")
        assertMeasured(listOf(0.364, 0.864, 0.484), analysis.reps.map { it.romM }, "ROM, metres")
        assertEquals(2, analysis.reps.count { it.conS in 0.2..2.5 }, "reps whose drive lasts a press")

        val tracker = StreamingSetTracker(StartPhase.CONCENTRIC)
        var last = LiveSetState()
        samples.forEach { last = tracker.feed(it) }
        // The live tracker counts ONE where the batch analyzer above segments
        // two. It is not that the second press is unreal -- batch measures it
        // at 0.484 m -- it is that the live RUN carrying it travels past
        // `maxRunDisplacementM`, so live declines to count a rep it cannot
        // bound. Losing it is the intended cost, not a side effect.
        assertEquals(1, last.repCount, "live reps; the lifter performed 2")
    }

    @Test
    fun `streaming tracker counts presses live despite bursty arrivals`() {
        val samples = load("field-ohp-100hz-bursty.csv")
        // Presses from the rack position drive up first — concentric-first
        // counting is what makes the reps land (ecc-first pairing found 1 of 8).
        val tracker = StreamingSetTracker(StartPhase.CONCENTRIC)
        var last = LiveSetState()
        samples.forEach { last = tracker.feed(it) }
        // Was 5. Two of those five completed on runs past `maxRunDisplacementM`.
        assertEquals(3, last.repCount, "live rep count; the lifter performed 8")
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

    /**
     * As [analyze], with a prescription attached. `tempoCompliance` is null
     * without one, so the set-level figures cannot be read at all otherwise.
     */
    private fun analyze(file: String, tempo: String) = fixture(file).let {
        SetAnalyzer.analyze(
            load(it.file),
            it.startsWith,
            loadKg = it.loadKg,
            targets = SetTargets(tempo = Tempo.parse(tempo), toleranceS = 0.5),
        )
    }

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
     * itself calls still ([VelocityEstimator.quietMask]: acceleration within
     * 0.05 g of 1 g held for 0.3 s, ANDed with gyro under 10 deg/s only on sets
     * whose distribution does not straddle the gate -- the two rotating
     * overhead presses pinned below are sets where it is not).
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
    fun `rotating overhead press resolves all 8 reps performed`() {
        // Set 1: seated overhead press, 45 lb warm-up, tempo 3010. The lifter
        // counted 8. The sensor rolled through 40.4 deg over the set, and its
        // median gyro magnitude is 12.06 deg/s -- over the fixed gate, so
        // [VelocityEstimator.gyroGateApplies] is false here and candidacy rests
        // on the acceleration term. Issue #87 took this from 3 to 6 and issue
        // #94's runaway correction from 6 to 8, which is the count the lifter
        // performed. The count agreeing is not the reps being right: two of
        // the eight measure 1.05 m and 1.06 m of ROM on an overhead press.
        val analysis = analyze("field-ohp-rotating-8rep.csv")
        assertEquals(8, analysis.reps.size, "segmented reps; the lifter performed 8")
    }

    @Test
    fun `a second rotating overhead press resolves all 8 reps performed`() {
        // Set 4: same lift at 55 lb, 8 counted, sensor rolled 37.1 deg, median
        // gyro 12.98 deg/s. Kept alongside set 1 because the shape of the loss
        // differs. Issue #87 took this from 4 to 6 and issue #94 from 6 to 8.
        // Per-window scoring in BatchCueCoverageTest says 7 of the 8 metronome
        // marks are matched and one detection is doubled, so the total lands
        // on 8 with one mark still empty.
        val analysis = analyze("field-ohp-rotating-8rep-b.csv")
        assertEquals(8, analysis.reps.size, "segmented reps; the lifter performed 8")
    }

    @Test
    fun `rotating bench press resolves 5 of the 6 reps performed`() {
        // Set 6: bench press, 95 lb, 6 counted, sensor rolled 46.1 deg. Issue
        // #94's runaway correction took this from 2 to 5; one detection reads
        // 1.051 m, which is not a bench rep at any ROM this corpus has
        // measured (0.333-0.345 m on the sister capture), so the count moving
        // toward the truth has not made every rep true.
        val analysis = analyze("field-bench-rotating-6rep.csv")
        assertEquals(5, analysis.reps.size, "segmented reps; the lifter performed 6")
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
    fun `stack-mounted cable row resolves 11 detections for the 8 reps performed`() {
        // Set 8: seated cable row, 60 lb warm-up, 8 counted, sensor on the
        // weight stack and rolling 0.5 deg over the whole set. The count agrees
        // and the reps did not: two of the eight carried a tenth of the
        // others' velocity. A count alone cannot see that, which is why the
        // per-rep pins exist -- and issue #94's runaway correction is the
        // proof of the point, taking the count from an apparently perfect 8
        // to 11. Two of the three added detections fall in the 15.7-22.3 s
        // stretch that was one over-cap run, on this set's own five-second
        // cadence, so the 8 was real reps missing and artefacts cancelling.
        // This capture carries no cue track, so nothing here can adjudicate
        // the remaining detections.
        val analysis = analyze("field-cablerow-static-8rep.csv")
        assertEquals(11, analysis.reps.size, "segmented reps; the lifter performed 8")
        // Velocity loss is best rep to LAST rep. The last rep here is one of
        // the two slow ones, so the set the lifter completed as prescribed is
        // reported to them as an 85% drawdown.
        assertEquals<Double?>(85.1, analysis.velocityLossPct, "velocity loss reported to the lifter")
    }

    @Test
    fun `stack-mounted face pull resolves 11 reps for 12 performed (pre-fix)`() {
        // Set 11: cable face pull, 22 lb, tempo 2011, 12 counted, sensor on the
        // stack rolling 0.7 deg. The 13th rep this used to invent is gone, and
        // so is the 1.662 m of ROM it claimed on a face pull: no rep in the set
        // now exceeds 1.2 m. The count overshoots by one no longer and
        // undershoots by one instead, which is a different wrong answer and not
        // a better one — the absolute error is unchanged at 1.
        val analysis = analyze("field-facepull-static-12rep.csv")
        assertEquals(11, analysis.reps.size, "segmented reps; the lifter performed 12")
        assertEquals(0, analysis.reps.count { it.romM > 1.2 }, "reps claiming over 1.2 m of ROM")
        assertEquals<Double?>(0.447, analysis.reps.last().romM, "ROM of the last rep, metres")
        // What DOES change for the lifter: velocity loss was 0.0% because the
        // fabricated 13th rep was also the fastest, and best-to-last made best
        // and last the same rep. With that rep gone the figure is 51.5% on a
        // 12-rep set taken to RPE 6, which is a number they can act on.
        assertEquals<Double?>(51.5, analysis.velocityLossPct, "velocity loss reported to the lifter")
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
        // The 13th detection -- 1.275 m of travel on a pallof press, so not a
        // rep -- is also the FASTEST thing in the set: 0.428 m/s against 0.400
        // for the best of the other twelve. Velocity loss is best rep to LAST
        // rep, so best and last were the same detection and the figure came out
        // at exactly 0.0: a green "-0% vel" chip on the set whose fastest
        // measured movement was an artefact. The reassuring value and the
        // artefact are one event, which is why the figure is withheld rather
        // than corrected. This fixture carries no cue track at all, so nothing
        // keyed on the voice guide could ever reach it.
        assertEquals(VelocityLoss.TerminalRepIsFastest, VelocityLoss.of(analysis.reps))
        assertNull(analysis.velocityLossPct, "velocity loss reported to the lifter")
    }

    @Test
    fun `every set ends where it started and the integrator says otherwise (pre-fix)`() {
        // Physically zero for all seven: every set begins and ends with the
        // load at rest in the same place, racked or resting on the stack, so
        // the signed vertical displacement across the whole capture is nothing.
        // These are the metres the integrator produces instead. No ground truth
        // is needed to read them and no candidate fix can argue with them.
        //
        // The two rotating overhead presses used to be the two that did NOT
        // move, at 16.142 m and 16.357 m, because every anchor they had sat in
        // the first half of the capture and there was none left for a tighter
        // rule to refuse. Issue #87 gave them anchors across the whole set:
        // 16.142 m to -3.002 m and 16.357 m to -0.710 m against a physical
        // truth of zero.
        //
        // Issue #94's runaway correction moves six of the seven and NOT in one
        // direction, which is the honest reading of it. Toward zero: the
        // rotating bench press 9.615 m to -0.409 m, the largest single
        // improvement this figure has ever recorded, and the first overhead
        // press -3.002 m to -1.944 m. Away from zero: the second overhead
        // press -0.710 m to 4.312 m and the cable row -1.255 m to -1.839 m.
        // Removing a runaway's mean sets that stretch's net travel to zero by
        // construction, so what is left is the travel OUTSIDE the runaways,
        // and on a capture whose runaway was cancelling an error elsewhere the
        // total gets worse. Nothing here claims the integrator is fixed.
        val todayM =
            mapOf(
                "field-ohp-rotating-8rep.csv" to -1.944,
                "field-ohp-rotating-8rep-b.csv" to 4.312,
                "field-bench-rotating-6rep-ok.csv" to -1.316,
                "field-bench-rotating-6rep.csv" to -0.409,
                "field-cablerow-static-8rep.csv" to -1.839,
                "field-facepull-static-12rep.csv" to 0.709,
                "field-pallof-static-12rep.csv" to 4.684,
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
        // Issue #94's runaway correction resolves more eccentrics on four of
        // the seven and the worst asymmetry goes UP on three of those -- the
        // reps it recovers are inside stretches the integrator had lost, so
        // they are the least well reconstructed reps in each set. Recovering
        // a rep is not the same as measuring it. Pinned as the worst asymmetry
        // in
        // all — the count of those is pinned too, because a fix that "improves"
        // this figure by resolving fewer eccentrics has not improved anything.
        val today =
            mapOf(
                "field-ohp-rotating-8rep.csv" to (8 to 1.164),
                "field-ohp-rotating-8rep-b.csv" to (8 to 1.350),
                "field-bench-rotating-6rep-ok.csv" to (6 to 0.721),
                "field-bench-rotating-6rep.csv" to (5 to 0.978),
                "field-cablerow-static-8rep.csv" to (8 to 1.344),
                "field-facepull-static-12rep.csv" to (5 to 0.353),
                "field-pallof-static-12rep.csv" to (6 to 0.239),
            )
        session20260817.forEach { fs ->
            val (reps, worst) = today.getValue(fs.file)
            val measured = phaseAsymmetriesM(fs)
            assertEquals(reps, measured.size, "${fs.file}: reps that resolved an eccentric")
            assertMeasured(listOf(worst), listOf(measured.max()), "${fs.file}: worst asymmetry")
        }
    }

    @Test
    fun `the ecc con ratio each capture publishes, and over how many reps`() {
        // RED until the fix. What `actualEccConRatio` must report for each
        // capture under a 3010 prescription -- both means taken over the SAME
        // reps, the ones that resolved an eccentric -- beside how many reps
        // that was. Four captures resolved one for every rep and are controls:
        // paired and unpaired coincide there, so they may not move at all.
        //
        // The three that did not are the differential, and the error they
        // carry today is NOT one-signed. Cable row publishes 3.22 where the
        // paired figure is 3.42 and pallof press publishes 1.07 where it is
        // 1.60, both understatements; face pull publishes 0.68 against 0.79.
        // Which way it goes depends on whether the reps that resolved an
        // eccentric also had slower-than-average drives, and nothing controls
        // that.
        //
        // The rep counts are pinned with the ratios deliberately: a change
        // that moves a ratio by resolving a different number of eccentrics is
        // a segmentation change, not an arithmetic one, and without the count
        // beside it the two are indistinguishable from the failure message.
        //
        // Three of the stack-mounted captures resolve MORE eccentrics than
        // before the anchor accept rule — cable row 4 to 5, face pull 2 to
        // 5, pallof 7 to 6 — because a slow cable eccentric is exactly the
        // phase that was being subtracted away. Pallof falls by one because it
        // also segments its reps differently.
        //
        // Issue #94's runaway correction moves four more: both overhead
        // presses 6 to 8, the rotating bench press 2 to 5 and the cable row 5
        // to 8. Every published ratio moves with them, and on the two presses
        // it moves AWAY from the 3.0 the 3010 prescription asks for — 2.02 to
        // 1.82 and 1.32 to 1.22. The recovered reps resolve short eccentrics,
        // which is what a rep rebuilt out of a runaway looks like.
        val expected =
            mapOf(
                "field-ohp-rotating-8rep.csv" to (8 to 1.82),
                "field-ohp-rotating-8rep-b.csv" to (8 to 1.22),
                "field-bench-rotating-6rep-ok.csv" to (6 to 3.32),
                "field-bench-rotating-6rep.csv" to (5 to 1.16),
                "field-cablerow-static-8rep.csv" to (8 to 3.02),
                "field-facepull-static-12rep.csv" to (5 to 0.71),
                "field-pallof-static-12rep.csv" to (6 to 1.48),
            )
        session20260817.forEach { fs ->
            val (withEcc, ratio) = expected.getValue(fs.file)
            val analysis = analyze(fs.file, "3010")
            val compliance = assertNotNull(analysis.tempoCompliance, "${fs.file}: compliance")
            assertEquals(
                withEcc,
                analysis.reps.count { it.eccS != null },
                "${fs.file}: reps that resolved an eccentric, of ${analysis.reps.size} segmented",
            )
            assertEquals(ratio, compliance.actualEccConRatio, "${fs.file}: published ecc:con ratio")
        }
    }

    @Test
    fun `the rest-screen eccentric caption each capture produces`() {
        // RED until the fix, on two of the seven. The card under the eccentric
        // chart, at a 3 s target and the screen own 0.5 s tolerance, must name
        // the rep by its position in the SET. Today it names its position in
        // the filtered list of measured eccentrics, so on a capture where the
        // unmeasured reps come first the rep named is not the rep meant: face
        // pull says "Rep 2" about the twelfth rep of thirteen.
        //
        // The suffix is pinned in the same sentence rather than separately,
        // because the two are one claim to the lifter. It must mean the last
        // rep PERFORMED. Gated on the last rep MEASURED, as it is today, it
        // fires on face pull rep 12 of 13 and on the bursty overhead press
        // rep 5 of 7 -- calling a set fatigued from a rep partway through it.
        //
        // Five captures are controls and may not move, two of them with the
        // suffix present: it is meant to go on firing where the worst rep
        // really was the last one performed, and a change that silently
        // disabled it would show here.
        //
        // Two moved when the anchor accept rule changed: the cable row now
        // names rep 8 rather than rep 2 and gains the fatigue suffix, because
        // rep 8 resolved an eccentric for the first time and it is the worst
        // one; the face pull names rep 8 of 11 rather than rep 12 of 13.
        //
        // Two more moved with issue #87, and only on the two overhead presses,
        // because both then segmented six reps rather than three and four. The
        // caption follows the segmentation; nothing in the caption rule
        // changed. Note what that means for the lifter: the sentence under the
        // eccentric chart names a DIFFERENT REP after such a change, on a set
        // where neither the old nor the new rep list has been checked against
        // what the lifter actually did.
        //
        // Issue #94's runaway correction moves three: the second overhead
        // press from rep 6 to rep 8, the rotating bench press from rep 2 to
        // rep 1, and the cable row from rep 8 to rep 7 — the rotating bench
        // press and the cable row BOTH lose the fatigue suffix. That suffix is
        // the strongest sentence this app says about a set and it turns on
        // which reps resolved.
        val expected =
            mapOf(
                "field-ohp-rotating-8rep.csv" to "Rep 1 eccentric 1.8 s — 1.2 s too fast.",
                "field-ohp-rotating-8rep-b.csv" to "Rep 8 eccentric 0.5 s — 2.5 s too fast. Fatigue showing.",
                "field-bench-rotating-6rep-ok.csv" to "Rep 6 eccentric 4.6 s — 1.6 s too slow.",
                "field-bench-rotating-6rep.csv" to "Rep 1 eccentric 0.8 s — 2.2 s too fast.",
                "field-cablerow-static-8rep.csv" to "Rep 7 eccentric 0.4 s — 2.6 s too fast.",
                "field-facepull-static-12rep.csv" to "Rep 8 eccentric 0.5 s — 2.5 s too fast.",
                "field-pallof-static-12rep.csv" to "Rep 8 eccentric 0.7 s — 2.4 s too fast.",
            )
        session20260817.forEach { fs ->
            assertEquals(
                expected.getValue(fs.file),
                CoachingRules.eccentricTempoInsight(analyze(fs.file, "3010").reps, 3.0, 0.5),
                "${fs.file}: rest-screen eccentric caption",
            )
        }
    }

    @Test
    fun `fabricated reps accrue their ROM while the IMU reads still (pre-fix)`() {
        // The sharpest per-rep signal this session produced for a rep that did
        // not happen. See [quietRomFractions]: distance credited to a rep while
        // the sensor reports itself stationary is drift, not movement.
        //
        // It does NOT separate cleanly and this pin states no threshold. The
        // face pull no longer segments a 13th rep at all, so the 0.984 that
        // rep carried is gone from the list; the pallof press still invents one
        // and it still reads 0.940. Two cable-row reps are fabricated by
        // inference rather than by count -- they are that set's only reps
        // under 0.12 m/s mean velocity; they read 0.734 and 0.954, UNCHANGED
        // to three decimals both by the anchor accept rule and by issue #94's
        // runaway correction, which is direct evidence that whatever costs
        // those two reps their travel is neither of those. They are reps 9 and
        // 11 of 11 since #94 resolved three more before them; the values did
        // not move, their index did. Against that, the
        // highest reading among reps with no reason to be doubted is 0.724,
        // face pull rep 9. A gap on four
        // examples is not a rule. The reason the signal exists at all is that a
        // slow cable rep is IMU-quiet too.
        //
        // What this comment used to say next -- that this is exactly why
        // VelocityEstimator cannot use acceleration alone to place its anchors
        // -- is FALSE and is withdrawn here. Traced on this capture: inside the
        // reported concentrics of reps 6 and 8 the estimator accepts NO anchor
        // and refuses three apiece, so nothing there was erased by an anchor.
        // The nearest accepted anchors bracket gaps of 15.3 s and 6.3 s, which
        // makes those two reps an anchor DEFICIT, not an intrusion.
        //
        // The quantity is also agnostic between the two readings it gets put
        // to. It is the share of REPORTED travel accrued on IMU-quiet samples,
        // and a genuinely slow rep is IMU-quiet, so a fix that stopped erasing
        // slow phases would push these numbers UP rather than down. It cannot
        // separate unremoved drift from a real slow rep, and neither reading
        // should be asserted from it alone.
        //
        // Pinned as measured values so that a fix which stops fabricating reps
        // moves them visibly. An earlier report of this signal put it at 94-98%
        // on fabricated reps and under 25% on every real one, with no overlap.
        // That does not hold on the full session and is retracted here.
        val today =
            mapOf(
                "field-ohp-rotating-8rep.csv" to listOf(0.000, 0.000, 0.023, 0.000, 0.000, 0.097, 0.000, 0.067),
                "field-ohp-rotating-8rep-b.csv" to listOf(0.000, 0.000, 0.000, 0.105, 0.000, 0.067, 0.000, 0.000),
                "field-bench-rotating-6rep-ok.csv" to listOf(0.000, 0.000, 0.000, 0.000, 0.000, 0.000),
                "field-bench-rotating-6rep.csv" to listOf(0.000, 0.000, 0.000, 0.000, 0.000),
                "field-cablerow-static-8rep.csv" to
                    listOf(
                        0.000, 0.077, 0.000, 0.000, 0.000, 0.000,
                        0.000, 0.000, 0.734, 0.000, 0.954,
                    ),
                "field-facepull-static-12rep.csv" to
                    listOf(
                        0.503, 0.000, 0.212, 0.114, 0.137, 0.061,
                        0.072, 0.308, 0.724, 0.008, 0.393,
                    ),
                "field-pallof-static-12rep.csv" to
                    listOf(
                        0.000, 0.140, 0.131, 0.224, 0.016, 0.109, 0.013,
                        0.108, 0.008, 0.336, 0.009, 0.539, 0.940,
                    ),
            )
        session20260817.forEach { fs ->
            assertMeasured(today.getValue(fs.file), quietRomFractions(fs), fs.file)
        }
    }

    @Test
    fun `live rep counts on the seven captures (pre-fix)`() {
        // What StreamingSetTracker counts on these captures TODAY, against the
        // count the lifter performed. Four of the seven are wrong, in both
        // directions, and they must stay wrong until the change that fixes
        // them: this is characterization, not a target.
        //
        // It exists because the batch pins above cover only half the surface
        // the quiet-detection constants move, and the missing half is the half
        // the lifter watches during the set. Measured: at
        // `DspConfig.stationaryGyroBandDps` 12 the live count on
        // field-ohp-rotating-8rep-b falls from 5 to 4 against a truth of 8 --
        // further from the lifter, while the batch counts on that same
        // mutation improve -- and nothing in the suite went red, because no
        // test pinned a live count on any of these seven.
        //
        // The live tracker is not the batch analyzer with a different clock,
        // and these disagree in both directions on the same capture. The
        // pallof press was the sharpest at 9 live against 13 batch for 12
        // performed; live now reads 12, which is the lifter count, while batch
        // still reads 13 — the gap did not close, it moved into the export.
        // The cable row went the other way, 8 to 7 against a truth of 8.
        // Those two sentences describe an EARLIER change and are left as its
        // record; the map below no longer holds the values they were written
        // against, because bounding run displacement moves the overhead press
        // 4 -> 3 and the cable row 7 -> 5.
        //
        // Summed over these seven, |live - performed| RISES 15 -> 18 under
        // that bound. That is the expected direction and the whole argument
        // for the change is that it is not the figure to optimise: the three
        // reps it removes completed on runs travelling past
        // `maxRunDisplacementM`, which the batch path has never once produced
        // in 213 runs. A count that is nearer the truth by including
        // increments nothing can bound is nearer by accident.
        val liveToday =
            mapOf(
                "field-ohp-rotating-8rep.csv" to (3 to 8),
                "field-ohp-rotating-8rep-b.csv" to (5 to 8),
                "field-bench-rotating-6rep-ok.csv" to (5 to 6),
                "field-bench-rotating-6rep.csv" to (1 to 6),
                "field-cablerow-static-8rep.csv" to (5 to 8),
                "field-facepull-static-12rep.csv" to (11 to 12),
                "field-pallof-static-12rep.csv" to (12 to 12),
            )
        // The `performed` half of that map was a hand count with nothing behind
        // it. Four of these seven now have the metronome cue track beside them,
        // so for those the hand count is checked against what the app actually
        // called. BarbellCueTrackTest asserts the same equality from its side;
        // between them the two figures cannot drift apart unnoticed.
        val cueTracked = setOf(
            "field-ohp-rotating-8rep",
            "field-ohp-rotating-8rep-b",
            "field-bench-rotating-6rep-ok",
            "field-bench-rotating-6rep",
        )
        session20260817.forEach { fs ->
            val (live, performed) = liveToday.getValue(fs.file)
            val base = fs.file.removeSuffix(".csv")
            if (base in cueTracked) {
                assertEquals(performed, CueTrack.calledReps(base), "${fs.file}: hand count against the metronome")
            }
            // Built through forLift, the factory the app uses, not through the
            // raw constructor. No value in the map moves: all seven of these
            // captures are analysed above with a defaulted LiftDirection, whose
            // sensorToLifter is 1.0 and whose driveIsPositive is true, which is
            // exactly what the raw constructor's defaults were. The reason to
            // change it anyway is that the raw path is the one that goes wrong
            // silently -- the next fixture added here with real cable or
            // drive-down geometry would be tracked with the wrong sign and
            // nothing would say so.
            val tracker = StreamingSetTracker.forLift(LiftDirection(startsWith = fs.startsWith))
            var last = LiveSetState()
            load(fs.file).forEach { last = tracker.feed(it) }
            assertEquals(live, last.repCount, "${fs.file}: live reps; the lifter performed $performed")
        }
    }

    // ------------------------------------------------------------------
    // Still-sensor control. Session of 2026-08-17 evening, app 0.1.38.
    // ------------------------------------------------------------------

    /**
     * 45 s of a sensor lying on a flat surface with nothing lifted, recorded
     * against a `seated_overhead_press` plan slot because that is the slot it
     * was recorded into, not because a press happened. The lifter confirms the
     * sensor never moved, and the app agreed in the field: the exported set
     * carries no `repMetrics` and an empty `summary`.
     *
     * Three such sets were recorded back to back and one is landed. The seven
     * BEHAVIOURAL answers below are identical on all three: 0 reps from either
     * opening phase, 0 velocity samples that are not 0.0, null velocity loss,
     * 0 live reps, a live phase of IDLE, and 0.0 net displacement. A second and
     * third copy add no power to discriminate any of those. What is NOT
     * identical is five per-file constants, all of them pinned below at set 1's
     * value, with sets 2 and 3 in brackets: sample count 4440 (4496, 4504),
     * measured rate 98.776 Hz (99.348, 99.413), live velocity 9.4316e-5 m/s
     * (8.0856e-5, 1.3265e-4 — four orders outside the pin), and the pre-ZUPT
     * displacement and final speed, 8.303 m and 0.351 m/s (6.387 m, 0.270 m/s;
     * 5.719 m, 0.242 m/s).
     *
     * What this fixture cannot gate, so that nobody cites it as coverage it
     * does not have:
     *
     * - A WIDENING of [DspConfig.stationaryGyroBandDps]. The gyro reads exactly
     *   0.0000 on all 4440 samples of all three axes, so every band ABOVE ZERO
     *   accepts every sample. Not every band: the predicate is a strict `<`, so
     *   a band of exactly 0.0 excludes everything and reds all three tests
     *   here. Widening is what a segmentation fix would do, and it is the case
     *   this capture is blind to.
     * - A WIDENING of [DspConfig.stationaryAccBandG]. Narrowing it, 0.05 to
     *   0.0005, reds all three tests below; widening it to 0.5 reds ten
     *   elsewhere and leaves all three green. The asymmetry is structural, not
     *   incidental: every sample here is already quiet, so a stillness fixture
     *   can only catch a predicate that wrongly EXCLUDES still data, never one
     *   that wrongly INCLUDES data that moved.
     * - The segmentation thresholds, at all. With velocity identically zero no
     *   movement run can form at any of them. Measured: all three tests stay
     *   green with the quiet predicate forced always-true, and under gyro band
     *   12 and 20, accBand 0.5, pauseBandMps 0.003 and 0.30, startThresholdMps
     *   0.10 to 0.02, minRomM 0.10 to 0.001, and anchorStabilityBandMps 0.02
     *   to 1.0.
     *
     * What it does gate is the quiet/ZUPT zeroing path and the size of the
     * front-end bias the correction is fed.
     */
    private val stillFile = "field-still-0rep.csv"

    /** The load the set was recorded with. Nothing was lifted with it. */
    private val stillLoadKg = 20.411656650451594

    /**
     * Velocity before the ZUPT stage, reproduced from the series the estimator
     * publishes: [VelocitySeries.accelMps2] integrated trapezoidally at the
     * measured rate is exactly the `rawV` [VelocityEstimator.estimate] hands to
     * its drift correction, which is private. This is not a second
     * implementation under test — it exists to measure the input that
     * correction is fed, so that "the output is zero" can be shown to be earned
     * rather than trivial.
     */
    private fun preZuptVelocity(s: VelocitySeries): DoubleArray {
        val dt = 1.0 / s.sampleRateHz
        val raw = DoubleArray(s.size)
        for (i in 1 until s.size) raw[i] = raw[i - 1] + 0.5 * (s.accelMps2[i] + s.accelMps2[i - 1]) * dt
        return raw
    }

    /** Signed displacement of [preZuptVelocity] over the whole set, metres. */
    private fun preZuptDisplacementM(s: VelocitySeries): Double {
        val raw = preZuptVelocity(s)
        val dt = 1.0 / s.sampleRateHz
        var d = 0.0
        for (i in 1 until s.size) d += 0.5 * (raw[i] + raw[i - 1]) * dt
        return d
    }

    @Test
    fun `a motionless sensor produces no reps and not one non-zero velocity sample`() {
        val samples = load(stillFile)
        // Guards the fixture file itself. A truncated, resampled or re-encoded
        // CSV would change this before it changed anything measured below.
        assertEquals(4440, samples.size, "samples in the capture")

        val series = VelocityEstimator.estimate(samples, DspConfig(), MovementPlane.VERTICAL)
        // A count, not a tolerance. Every sample of this set comes out of the
        // estimator as the literal double 0.0, so the strongest statement
        // available is how many are not — and it is the statement that cannot
        // be satisfied by data that moved a little. "Peak |v| below 0.01 m/s"
        // would pass on a set with real motion in it.
        assertEquals(0, series.velocityMps.count { it != 0.0 }, "velocity samples that are not exactly 0.0")

        val analysis = SetAnalyzer.analyze(samples, StartPhase.ECCENTRIC, loadKg = stillLoadKg)
        assertEquals(0, analysis.reps.size, "segmented reps; the sensor never moved")
        // Neither opening phase may invent a rep. Pinned both ways because the
        // rep-undercount work this fixture is the control for changes how
        // phases are opened, and a control that only holds one way is half a
        // control.
        assertEquals(
            0,
            SetAnalyzer.analyze(samples, StartPhase.CONCENTRIC, loadKg = stillLoadKg).reps.size,
            "segmented reps analysed concentric-first",
        )
        // Absence, not zero: with no reps there is no loss to report. A 0.0
        // here would reach the export as "you lost no velocity" — a
        // measurement — from a set that measured nothing.
        assertEquals<Double?>(null, analysis.velocityLossPct, "velocity loss with no reps to compare")
        // Exact double equality, against the `sampleRate_hz` this set's own
        // meta.json published in the field: 4439 intervals over 44.940 s. The
        // fixture reproduces the shipped app's figure to the bit.
        assertEquals<Double>(98.77614597240766, analysis.sampleRateHz, "measured sample rate")
    }

    @Test
    fun `the live tracker counts nothing and ends idle on a motionless sensor`() {
        val samples = load(stillFile)
        val tracker = StreamingSetTracker(StartPhase.ECCENTRIC)
        var last = LiveSetState()
        samples.forEach { last = tracker.feed(it) }
        assertEquals(0, last.repCount, "live reps; the sensor never moved")
        assertEquals(Phase.IDLE, last.phase, "live phase after 45 s of stillness")
        // The streaming path does NOT clamp to zero the way the batch path
        // does — it carries no retroactive correction — so it ends 45 s of
        // stillness holding 0.094 mm/s. Pinned as the measured value rather
        // than bounded, at five significant figures: 1e-9 is five orders below
        // the figure itself and seven below `pauseBandMps`, so this is
        // equality, not a band. It is recorded because it is the live readout's
        // own noise floor on data with no motion in it at all.
        assertEquals(9.4316e-5, last.velocityMps, 1e-9, "live velocity after 45 s of stillness")
    }

    @Test
    fun `the same stillness drifts 8 metres before the ZUPT stage removes all of it`() {
        val samples = load(stillFile)
        val series = VelocityEstimator.estimate(samples, DspConfig(), MovementPlane.VERTICAL)

        // Exactly zero, no tolerance: the corrected velocity array is all 0.0,
        // so its integral is 0.0 and anything else is a change in behaviour.
        assertEquals<Double>(0.0, netDisplacementM(series), "displacement after drift correction, metres")

        // What that zero is worth. Left uncorrected, the same 45 s of stillness
        // walks 8.3 m and finishes at 0.351 m/s — over three times
        // `DspConfig.startThresholdMps` (0.10), so a movement run, from a
        // sensor on a table. This is the pin that stops the assertion above
        // being satisfied trivially: it reds if the front of the pipeline stops
        // producing the bias, which would make "the output is zero" a statement
        // about nothing.
        // Deliberately NOT [assertMeasured]. Its stated justification is that
        // every quantity pinned with it moves by 0.05 or more when the number
        // of segmented reps changes by one, and this fixture's rep count is
        // asserted to be zero, so that justification does not reach here.
        // These two carry a tolerance for a different reason: they descend from
        // the Math.sin/cos calls in [FrameTransform], which are specified only
        // to within 1 ulp and so are not guaranteed bit-identical across JVMs.
        // Exact equality would be a portability bet rather than a measurement.
        // 1e-6 is three orders tighter than this file's 1e-3 convention and the
        // measured values sit 3.8% and 0.8% into it; CI runs these same
        // assertions on a different machine and JVM, which is the portability
        // check itself rather than an argument about one.
        assertEquals(8.3030422, preZuptDisplacementM(series), 1e-6, "pre-ZUPT displacement, metres")
        assertEquals(0.3513516, abs(preZuptVelocity(series).last()), 1e-6, "pre-ZUPT final speed, m/s")
    }

    // ------------------------------------------------------------------
    // Lower-body corpus. Sessions 30 and 31, app 0.1.40, WitMotion
    // WT901BLECL, recorded back to back on 2026-08-20.
    // ------------------------------------------------------------------

    /**
     * The 24 fixtures above this section carry no leg press and no Romanian
     * deadlift at any sample rate, and back squat only at the factory 10 Hz
     * default (`field-backsquat-10hz*`) -- every ~99 Hz fixture is upper-body
     * free-weight or cable work. So every DSP change up to this point has
     * been gated against a corpus that structurally excludes the lower-body
     * sessions progression is read from: green there was evidence about cable
     * rows and overhead presses, not squats or presses with the legs. The
     * four fixtures below close that gap. They pin CURRENT behaviour only --
     * no source under `core/dsp/src/main` changed to add them.
     *
     * ## Provenance
     *
     * `field-30` and `field-31` are two raw session exports from the same
     * training day (epochs 09:19 and 09:48 UTC-4), copied byte for byte out
     * of a durable, read-only field-capture archive; nothing was re-encoded,
     * resampled or trimmed. `repsManual` is true on every set cited here, so
     * the "performed" figure named beside each pin is a hand count, not an
     * inference.
     *
     * Selection was measured, not guessed. For leg press and single-leg
     * press, the set chosen from each session is the one with the lowest
     * ratio of `repMetrics.size` (what the app's own analyzer resolved,
     * published in `session.json`) to `reps` (the hand count) of every set of
     * that exercise in its session: leg press 2/8 (25%) beats 4/6, 1/5, 5/6
     * and 7/8; single-leg press 2/8 (25%) beats 4/8, 3/8 and 3/8. For back
     * squat, the session has three near-identical 99 Hz sets (95 lb, tempo
     * 4011, RPE 6); the one committed here is one of the two that
     * under-resolve by one rep -- the third segments 6 for 6 and would add no
     * evidence about the gap this section exists to fill. For Romanian
     * deadlift, the session's three sets read 8/10, 11/10 and 10/10; the one
     * committed is the 11/10 set, chosen because it is the only one the app's
     * own export OVER-counted, which is the same defect class #125 fixed on
     * the rear-delt-fly fixture above, now shown on a second exercise and a
     * second session.
     *
     * Geometry -- `startsWith`, `concentric`, `plane`, `sensorOnStack`,
     * `sensorInverted`, `travelRatio` -- is copied verbatim from each set's
     * own entry in its session's `meta.json`, cross-checked against
     * `session.json`'s per-set `geometry` block (schema 1.8), which agrees.
     * That schema marks the source of every one of these fields `inferred` or
     * `default` rather than plan-declared, so unlike the rear-delt-fly
     * fixture above this is NOT evidence of a plan's own geometry
     * declaration -- it is the geometry value the app actually used to record
     * and export the set. All four collapse to `LiftDirection(startsWith =
     * X)` with every other field at its class default (`concentricUp` true,
     * `sensorInverted` false, `travelRatio` 1.0, `plane` VERTICAL,
     * `sensorOnStack` false), stated here so a reader does not have to
     * cross-reference `meta.json` to see that. `loadKg` is each set's own
     * `load_kg`, carried at the full float precision the app stores it at.
     *
     * The check that this geometry is right: every rep these fixtures
     * resolve reproduces app 0.1.40's own published `repMetrics` -- ROM and
     * mean concentric velocity -- to the last digit. That is asserted
     * directly in the tests below, not merely claimed here.
     *
     * BOTTOM PAUSE USED TO BE IN THAT LIST AND IS NOT ANY MORE. Issue #93
     * changed what the field is: a rep publishes the turnaround between its
     * own two phases and nothing at the end its boundary falls on, so on the
     * two concentric-first sets here the figure 0.1.40 published is one this
     * repository deliberately no longer computes. The lift-direction evidence
     * is unaffected -- ROM and mean concentric velocity still reproduce
     * exactly, and a wrong opening phase moves both.
     *
     * All four sets carry a `Done` cue -- none is a failed set, so issue
     * #141 (failed guided sets never speak `Done`) has no evidence for or
     * against it in this corpus, recorded here so nobody assumes otherwise.
     * `*_hrm.csv` and `*_rest_before_hrm.csv` exist beside these captures in
     * the source archive and were not copied: nothing in `:core:dsp` reads
     * heart rate, matching every other fixture already committed here.
     *
     * ## What each fixture is filed under, and why
     *
     * Back squat rolls 89.8 deg through the stroke -- past the 31-52 deg band
     * issue #72 measured bar-mounted sets losing reps at -- and resolves 5 of
     * 6. Filed under #72 directly: same mechanism, a larger rotation.
     *
     * Leg press and single-leg press roll only 0.6 and 0.7 deg -- inside the
     * 0.2-0.7 deg range #72's own table measured for its STACK-MOUNTED sets,
     * the ones that OVER-count. These are not stack-mounted (a leg-press sled
     * does not let the sensor rotate either way), and they UNDER-count at 2
     * of 8 -- breaking the correlation between roll and the direction of
     * error that #72's own table shows. They are filed under #72 because the
     * behaviour -- `repMetrics.size` far under the hand count, a batch
     * segmenter figure -- is exactly #72's title and scope; the roll-based
     * mechanism #72's own filing measured plainly does not explain them, and
     * that is stated rather than folded silently into #72's story.
     *
     * The bilateral leg press set's second (and last) resolved rep carries a
     * 14.27 s `bottomPauseS` against a 2010 prescription's 0 s pause -- inside
     * the range issue #93 measured for exactly this field on exactly this
     * exercise (mean 1.34 s, max 22.73 s). It is very likely one of #93's own
     * examples; that has not been independently re-derived here, so it is
     * stated as a match, not an identity. It SURVIVES #93's fix, and that is
     * the honest limit of that fix: this lift turns at the bottom, so the
     * figure is a genuine turnaround bounded by the detection's own two
     * phases -- and the detection merged reps, so the turnaround spans them.
     *
     * The single-leg press is the other direction and its comparison has
     * changed. It turns at the TOP, so the 0.04 s and 0.05 s this paragraph
     * used to quote as its bottom pauses were the interval to the next drive
     * and are no longer published at all; its turnarounds are 0.13 and
     * 0.03 s. The two sets still share an undercount and do NOT visibly
     * share a mechanism, which would be easy to misread from "same tempo,
     * same count" alone -- but the two numbers are now measured between
     * different pairs of instants and were never comparable in the first
     * place, which is the substance of #93.
     *
     * The bilateral leg press set's last resolved rep is also its FASTEST, so
     * today's `SetAnalyzer` withholds velocity loss (`TerminalRepIsFastest`)
     * rather than publish the degenerate 0% a naive best-to-last would
     * compute -- the same class already pinned for the pallof press above,
     * reached here from a leg press instead of a cable machine. App 0.1.40
     * published `velocityLoss_pct` 0.0 for this exact set in the field; that
     * is the reading this repo's `VelocityLoss` type exists to withhold, and
     * it is not reproduced in a test here because nothing in this repo
     * recomputes what an old app build did.
     *
     * The Romanian deadlift's ten KEPT reps (once its own `Done` cue bounds
     * the set) reproduce the app's own figures exactly and are NOT evidence
     * for #72 -- the batch segmenter is not the defect on this fixture. Only
     * the eleventh, post-`Done` detection is, and #125 already fixes it in
     * current code; the companion test below calls the analyzer with no cue
     * track to show what 0.1.40 actually published before that fix existed:
     * an 11-rep count and a 64.1% velocity loss, both reproduced exactly.
     */
    private fun track(name: String) = CueTrack.read(name).map { VoiceCue(it.timestampMs, it.label) }

    @Test
    fun `the corpus's first 99 Hz back squat resolves 7 detections for 6 reps (issue 72)`() {
        val samples = load("field-backsquat-99hz-6rep.csv")
        val direction = LiftDirection(startsWith = StartPhase.ECCENTRIC)
        val analysis = SetAnalyzer.analyze(
            samples,
            direction,
            loadKg = 43.091275150953365,
            cues = track("field-backsquat-99hz-6rep"),
        )
        assertEquals(
            6,
            CueTrack.calledReps("field-backsquat-99hz-6rep"),
            "metronome Down-cues, corroborating meta.json's hand count of 6",
        )
        assertEquals<Double>(99.3937495805463, analysis.sampleRateHz, "measured rate, against this set's own meta.json")
        assertEquals(7, analysis.reps.size, "segmented reps; the lifter performed 6")
        assertEquals(0, analysis.detectionsAfterSetEndCue, "detections after Done")
        assertEquals<Double?>(16.6, analysis.velocityLossPct, "velocity loss reported to the lifter")
        // THIS NO LONGER REPRODUCES THE SHIPPED EXPORT, and that is the
        // sharpest cost issue #87 carries. App 0.1.40 published
        // 0.606/0.599/0.562/0.561/0.458 m and 0.498/0.458/0.385/0.396/0.356
        // m/s for these five reps and this file reproduced them exactly, which
        // was the check that the geometry in the class KDoc is the geometry the
        // set was recorded with. #87 keeps the rep COUNT at 5 and moves ALL
        // FIVE ROMs -- 0.606/0.599/0.562/0.561/0.458 becomes
        // 0.604/0.867/0.919/0.636/0.601 -- and all five mean concentric
        // velocities with them; rep 5 alone moves 0.458 to 0.601 m.
        //
        // Whether 0.867 m and 0.919 m are travel this lifter's squat produces
        // cannot be settled from this corpus: it holds no independently
        // measured back-squat ROM, and the only other 99 Hz squat,
        // field-backsquat-4011-6rep-s36-set01, is itself re-baselined by this
        // change to 0.121-0.731 m. #74's declared plausibility window tops out
        // at 1.2 m, so both figures sit inside it and the window decides
        // nothing here. The tape-measure [Field] item answers it. The velocity
        // loss the lifter reads drops from 28.5% to 16.6% with them.
        //
        // This capture's median gyro magnitude is 11.24 deg/s, the smallest in
        // the corpus that clears the gate, and its tenth percentile is
        // 2.97 deg/s, so it straddles and the clause is dropped. Nothing about
        // the straddle rule protects a set that only just crosses the median
        // probe, and no attempt is made here to invent a second threshold that
        // would exclude this one capture. It is recorded as a cost, not
        // repaired.
        // Issue #94's runaway correction adds TWO detections ahead of the
        // five, at 0.705 m and 0.232 m, taking a set of six performed reps to
        // seven detections. The five #87 left are unmoved to three decimals
        // and are now reps 3 to 7; the velocity loss the lifter reads is
        // unchanged at 16.6%.
        assertMeasured(
            listOf(0.705, 0.232, 0.604, 0.867, 0.919, 0.636, 0.601),
            analysis.reps.map { it.romM },
            "ROM, metres",
        )
        assertMeasured(
            listOf(0.353, 0.292, 0.501, 0.284, 0.328, 0.419, 0.418),
            analysis.reps.map { it.meanConVelMps },
            "mean concentric velocity, m/s",
        )
    }

    @Test
    fun `bilateral leg press resolves 7 of the 8 reps performed (issue 72)`() {
        val samples = load("field-legpress-2010-8rep.csv")
        val direction = LiftDirection(startsWith = StartPhase.ECCENTRIC)
        val analysis = SetAnalyzer.analyze(
            samples,
            direction,
            loadKg = 24.94758035055195,
            cues = track("field-legpress-2010-8rep"),
        )
        assertEquals(
            8,
            CueTrack.calledReps("field-legpress-2010-8rep"),
            "metronome Down-cues, corroborating meta.json's hand count of 8",
        )
        assertEquals(7, analysis.reps.size, "segmented reps; the lifter performed 8")
        assertEquals(0, analysis.detectionsAfterSetEndCue, "detections after Done")
        // Issue #94's runaway correction took this capture from 2 of 8 to 7
        // of 8 -- the sharpest single recovery in the corpus, and the reason
        // this test is no longer called the sharpest undercount. The two reps
        // it always found are unmoved and still reproduce app 0.1.40's own
        // published repMetrics: 0.176/0.563 m are now reps 1 and 7.
        assertMeasured(
            listOf(0.176, 0.416, 0.867, 0.519, 0.319, 0.181, 0.563),
            analysis.reps.map { it.romM },
            "ROM, metres",
        )
        assertMeasured(
            listOf(0.243, 0.373, 0.403, 0.347, 0.353, 0.269, 0.419),
            analysis.reps.map { it.meanConVelMps },
            "mean concentric velocity, m/s",
        )
        // Rep 2's bottom pause was 14.27 s -- the interval across five reps
        // nothing resolved, published to the lifter as a pause they took.
        // With those reps resolved it is 0.50 s. Issue #93's artefact on this
        // capture is a consequence of the under-count and goes with it.
        val rep2BottomPause: Double? = analysis.reps[1].bottomPauseS
        assertEquals<Double?>(0.5, rep2BottomPause, "rep 2's bottom pause, seconds -- issue 93")
        // Ecc-first, so the top is the rep BOUNDARY and publishes nothing:
        // the stillness there ran on until the next drive and was rest, which
        // is the half of #93 that is removed rather than corrected.
        val tops: List<Double?> = analysis.reps.map { it.topPauseS }
        assertEquals(List<Double?>(7) { null }, tops, "top pause, seconds -- issue 93")
        // The last resolved rep is still the fastest of the set, so
        // SetAnalyzer still withholds velocity loss rather than publishing the
        // degenerate 0% app 0.1.40 reported for this set in the field.
        assertEquals(VelocityLoss.TerminalRepIsFastest, VelocityLoss.of(analysis.reps))
        assertNull(analysis.velocityLossPct, "velocity loss reported to the lifter")
    }

    @Test
    fun `single-leg press resolves 7 of the 8 reps performed, on the right leg (issue 72)`() {
        val samples = load("field-legpress-single-2010-8rep.csv")
        val direction = LiftDirection(startsWith = StartPhase.CONCENTRIC)
        val analysis = SetAnalyzer.analyze(
            samples,
            direction,
            loadKg = 52.163122551154075,
            cues = track("field-legpress-single-2010-8rep"),
        )
        assertEquals(
            8,
            CueTrack.calledReps("field-legpress-single-2010-8rep"),
            "metronome Down-cues, corroborating meta.json's hand count of 8",
        )
        assertEquals(7, analysis.reps.size, "segmented reps; the lifter performed 8")
        // ONE detection now begins after the Done cue where none did before.
        // Issue #94's correction de-trends the post-Done stretch as well as
        // the working one, so the set-end bound has more to reject; it
        // rejects it, which is the bound doing its job rather than a new
        // defect reaching the lifter.
        assertEquals(1, analysis.detectionsAfterSetEndCue, "detections after Done")
        assertMeasured(
            listOf(0.256, 0.103, 0.492, 0.844, 0.477, 0.260, 0.122),
            analysis.reps.map { it.romM },
            "ROM, metres",
        )
        // Con-first with the drive going up, so the BOTTOM is the rep
        // boundary: what used to be published here as a bottom pause was the
        // interval to the next drive, and it is not published at all now
        // (#93). The turnaround these reps do contain is at the top.
        val bottoms: List<Double?> = analysis.reps.map { it.bottomPauseS }
        assertEquals(List<Double?>(7) { null }, bottoms, "bottom pause, seconds -- issue 93")
        assertMeasured(
            listOf(0.13, 0.03, 0.11, 0.11, 0.04, 0.04),
            analysis.reps.mapNotNull { it.topPauseS },
            "top pause, seconds",
        )
        assertEquals<Double?>(46.8, analysis.velocityLossPct, "velocity loss reported to the lifter")
    }

    @Test
    fun `Romanian deadlift resolves all 10 reps performed once the tail after Done is bounded out (issue 125)`() {
        val samples = load("field-rdl-3010-10rep.csv")
        val direction = LiftDirection(startsWith = StartPhase.ECCENTRIC)
        val analysis = SetAnalyzer.analyze(
            samples,
            direction,
            loadKg = 43.091275150953365,
            cues = track("field-rdl-3010-10rep"),
        )
        assertEquals(
            10,
            CueTrack.calledReps("field-rdl-3010-10rep"),
            "metronome Down-cues, corroborating meta.json's hand count of 10",
        )
        assertEquals(10, analysis.reps.size, "segmented reps kept once the post-Done tail is bounded out")
        assertEquals(1, analysis.detectionsAfterSetEndCue, "detections dropped")
        assertEquals<Double?>(40.9, analysis.velocityLossPct, "velocity loss reported to the lifter, tail excluded")
        // The ten KEPT reps reproduce app 0.1.40's own published repMetrics
        // for its first ten entries -- confirming the Done bound is the ONLY
        // difference it makes here, not a reshuffling of the ten real reps.
        assertMeasured(
            listOf(1.29, 0.558, 0.401, 0.411, 0.4, 0.402, 0.423, 0.411, 0.344, 0.276),
            analysis.reps.map { it.romM },
            "ROM, metres",
        )
    }

    @Test
    fun `the same Romanian deadlift over-counts to 11 and reads 64 percent loss without the Done bound (issue 125)`() {
        // What app 0.1.40 actually published in the field for this set:
        // repMetrics.size 11 and velocityLoss_pct 64.1, both reproduced here
        // by calling the analyzer with no cues argument -- which defaults to
        // an empty list, the same as the class's own two-argument overload
        // documented as "never bounded". The eleventh, spurious detection is
        // the SLOWEST of the eleven, so it drags best-to-last down to the
        // largest reading in the set -- a 64.1% loss on a 6-RPE set the
        // lifter rated with plenty left, computed over a rep that begins
        // after the metronome had already said the set was over.
        val samples = load("field-rdl-3010-10rep.csv")
        val direction = LiftDirection(startsWith = StartPhase.ECCENTRIC)
        val analysis = SetAnalyzer.analyze(samples, direction, loadKg = 43.091275150953365)
        assertEquals(11, analysis.reps.size, "segmented reps with no cue track to bound the set")
        assertEquals(VelocityLoss.Measured(64.1), VelocityLoss.of(analysis.reps))
        assertEquals<Double?>(64.1, analysis.velocityLossPct, "velocity loss, as app 0.1.40 published it")
        assertEquals(0.194, analysis.reps.last().meanConVelMps, 1e-3, "the spurious 11th rep's own drive speed")
    }
}
