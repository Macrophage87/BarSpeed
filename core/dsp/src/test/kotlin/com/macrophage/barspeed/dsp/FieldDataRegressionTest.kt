package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.Phase
import com.macrophage.barspeed.model.StartPhase
import com.macrophage.barspeed.model.Tempo
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
        assertEquals(8, analysis.reps.size, "segmented reps; the lifter performed 8")
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
        assertEquals(2, analysis.reps.size, "segmented reps; the lifter performed 2 presses")
        assertEquals(2, analysis.reps.count { it.eccS == null }, "reps counted on the drive alone")
        assertMeasured(listOf(1.71, 4.28), analysis.reps.map { it.conS }, "concentric seconds")
        assertMeasured(listOf(0.857, 0.484), analysis.reps.map { it.romM }, "ROM, metres")
        // Only the first is a press by any kinematic reading of it.
        assertEquals(1, analysis.reps.count { it.conS in 0.2..2.5 }, "reps whose drive lasts a press")

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
        assertEquals<Double?>(80.8, analysis.velocityLossPct, "velocity loss reported to the lifter")
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
        // Four of the seven move and three do not. The two that do not — both
        // rotating overhead presses — are the two whose anchors all sit in the
        // first half of the capture, so there is no accepted anchor left for a
        // tighter rule to refuse. Face pull is the largest improvement,
        // 4.687 m to 0.709 m, and the cable row and the working bench press
        // both move AWAY from zero. Refusing anchors removes erasure and leaves
        // drift, and on these two the drift is the larger term.
        val todayM =
            mapOf(
                "field-ohp-rotating-8rep.csv" to 16.142,
                "field-ohp-rotating-8rep-b.csv" to 16.357,
                "field-bench-rotating-6rep-ok.csv" to -1.316,
                "field-bench-rotating-6rep.csv" to 9.615,
                "field-cablerow-static-8rep.csv" to -1.255,
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
        // each capture, in metres, over the reps that resolved an eccentric at
        // all — the count of those is pinned too, because a fix that "improves"
        // this figure by resolving fewer eccentrics has not improved anything.
        val today =
            mapOf(
                "field-ohp-rotating-8rep.csv" to (3 to 1.244),
                "field-ohp-rotating-8rep-b.csv" to (4 to 1.011),
                "field-bench-rotating-6rep-ok.csv" to (6 to 0.721),
                "field-bench-rotating-6rep.csv" to (2 to 0.068),
                "field-cablerow-static-8rep.csv" to (5 to 1.344),
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
        // Three of the stack-mounted captures now resolve MORE eccentrics —
        // cable row 4 to 5, face pull 2 to 5, pallof 7 to 6 — because a slow
        // cable eccentric is exactly the phase that was being subtracted away.
        // Pallof falls by one because it also segments its reps differently.
        val expected =
            mapOf(
                "field-ohp-rotating-8rep.csv" to (3 to 3.33),
                "field-ohp-rotating-8rep-b.csv" to (4 to 1.08),
                "field-bench-rotating-6rep-ok.csv" to (6 to 3.32),
                "field-bench-rotating-6rep.csv" to (2 to 2.11),
                "field-cablerow-static-8rep.csv" to (5 to 3.38),
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
        // Two move. The cable row now names rep 8 rather than rep 2, and gains
        // the fatigue suffix, because rep 8 resolved an eccentric for the first
        // time and it is the worst one; the face pull names rep 8 of 11 rather
        // than rep 12 of 13. Both are the caption following the segmentation
        // rather than the caption changing.
        val expected =
            mapOf(
                "field-ohp-rotating-8rep.csv" to "Rep 2 eccentric 2.3 s — 0.7 s too fast.",
                "field-ohp-rotating-8rep-b.csv" to "Rep 4 eccentric 0.9 s — 2.1 s too fast. Fatigue showing.",
                "field-bench-rotating-6rep-ok.csv" to "Rep 6 eccentric 4.6 s — 1.6 s too slow.",
                "field-bench-rotating-6rep.csv" to "Rep 2 eccentric 1.5 s — 1.5 s too fast. Fatigue showing.",
                "field-cablerow-static-8rep.csv" to
                    "Rep 8 eccentric 1.6 s — 1.4 s too fast. Fatigue showing.",
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
        // and it still reads 0.940. Reps 6 and 8 of the cable row are
        // fabricated by inference rather than by count (that set segments 8 for
        // 8, and those are its only two reps under 0.12 m/s mean velocity);
        // they read 0.734 and 0.954, UNCHANGED to three decimals by this
        // change, which is the direct evidence that whatever costs those two
        // reps their travel is not the anchor accept rule. Against that, the
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
                "field-ohp-rotating-8rep.csv" to listOf(0.000, 0.000, 0.000),
                "field-ohp-rotating-8rep-b.csv" to listOf(0.000, 0.000, 0.000, 0.000),
                "field-bench-rotating-6rep-ok.csv" to listOf(0.000, 0.000, 0.000, 0.000, 0.000, 0.000),
                "field-bench-rotating-6rep.csv" to listOf(0.000, 0.000),
                "field-cablerow-static-8rep.csv" to
                    listOf(0.000, 0.077, 0.000, 0.000, 0.000, 0.734, 0.000, 0.954),
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
        // The cable row goes the other way, 8 to 7 against a truth of 8.
        // Across the seven, absolute live error falls from 10 to 8.
        val liveToday =
            mapOf(
                "field-ohp-rotating-8rep.csv" to (4 to 8),
                "field-ohp-rotating-8rep-b.csv" to (5 to 8),
                "field-bench-rotating-6rep-ok.csv" to (5 to 6),
                "field-bench-rotating-6rep.csv" to (1 to 6),
                "field-cablerow-static-8rep.csv" to (7 to 8),
                "field-facepull-static-12rep.csv" to (11 to 12),
                "field-pallof-static-12rep.csv" to (12 to 12),
            )
        session20260817.forEach { fs ->
            val (live, performed) = liveToday.getValue(fs.file)
            val tracker = StreamingSetTracker(fs.startsWith)
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
}
