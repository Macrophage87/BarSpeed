package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.StartPhase
import com.macrophage.barspeed.model.VoiceCue
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * WHAT THE ELEVEN CAPTURES OF ISSUES #290 AND #255 PUBLISH TODAY, before any
 * rule about out-of-range samples exists. This is the BEFORE half of the corpus
 * table; `ArtefactPeakWithholdingTest` is the AFTER half.
 *
 * Nine captures arrive with this file and two were already committed.
 *
 * ## Provenance, from each session's own `meta.json`
 *
 * field-42 (`epoch` 2026-09-07T09:39:39.465Z, `timeZoneId` America/New_York,
 * `appVersion` **0.1.52**, `sensorModel` WitMotion WT901BLECL, `session.json`
 * `schemaVersion` 1.19). Six sets, all two-sensor with `analysedRole: "a"`, all
 * `repsManual: true`, and every load below is that file's figure rather than a
 * filename reading:
 *
 * - set 2 -- `seated_overhead_press`, 55.0 lb / 24.94758035055195 kg, tempo
 *   `3010`, concentric-first, drive up, vertical, sensor on the bar, ratio 1.0,
 *   **7 reps performed** of a planned 8, `failed: true`, `limiter: "muscle"`.
 *   1,744 samples at 43.81488650360725 Hz.
 * - set 5 -- `bench_press`, 105.0 lb / 47.62719885105372 kg, `3010`,
 *   ECCENTRIC-first, drive up, vertical, bar, **6 of 6**, RPE 4. 1,872 samples
 *   at 44.48407037565383 Hz.
 * - set 7 -- `bench_press`, 125.0 lb / 56.69904625125443 kg, `3010`,
 *   eccentric-first, **6 of 6**, RPE 7, `limiter: "pace"`. 1,740 samples at
 *   44.35093088497832 Hz.
 * - set 9 -- `seated_cable_row`, 90.0 lb / 40.82331330090319 kg, `3010`,
 *   concentric-first, `plane: "horizontal"`, `sensorOnStack: true`, ratio 1.0,
 *   **8 of 8**, RPE 6. 1,936 samples at 44.48378123635026 Hz.
 * - set 11 -- `assisted_pull_up`, 49.8 lb / 22.579000000000008 kg of
 *   ASSISTANCE, `bodyweight: true`, `bodyWeight_kg` 115.569, `3010`,
 *   concentric-first, vertical, `sensorOnStack: true`, **8 of 8**, RPE 8,
 *   `limiter: "muscle"`. 2,648 samples at 43.50756081525312 Hz.
 * - set 13 -- `assisted_pull_up`, same load and geometry, tempo `4010`,
 *   **8 of 8**, `failed: true`, `limiter: "muscle"`. 2,812 samples at
 *   44.07268622316991 Hz.
 *
 * THE ANALYSED ROLE OF EVERY FIELD-42 SET RUNS AT 43-44 Hz, not 99, and its
 * PARTNER ran at 99.42 Hz on set 2. These are the corpus's first captures at
 * half the nominal rate, which is a property of that session's link and not of
 * this issue; nothing here is derived from the rate.
 *
 * field-43 (`epoch` 2026-09-17T10:58:43.365Z, `appVersion` **0.1.53**, same
 * sensor model). Three `deadlift` sets, concentric-first, drive up, vertical,
 * sensor on the bar, ratio 1.0, no prescribed tempo, **5 reps performed each**
 * (`repsManual: true`), no work-start instant recorded:
 *
 * - set 4 -- 135.0 lb / 61.234969951354785 kg, 3,548 samples at
 *   99.43930473787496 Hz.
 * - set 5 -- 185.0 lb / 83.91458845185656 kg, 2,572 samples at
 *   99.4045777915249 Hz.
 * - set 6 -- 225.0 lb / 102.05828325225797 kg, 2,484 samples at
 *   99.47518128280117 Hz.
 *
 * The two already committed are `field-assistedpullup-3010-s37-set08` and
 * `field-ohp-prepinflated-s37-set03`, whose provenance `ArtefactRepTest` states
 * and which #255 names as the sample-level residue.
 *
 * ## The licence for using the nine
 *
 * Each field-42 set is analysed here with its own cue track and its own
 * `workStartedAt_ms`, and every figure it publishes reproduces that session's
 * archived `summary` and `repMetrics` to the last published digit -- set 2's
 * 3606.3 W / 2.516 m/s from nine detections, set 5's 1135.7 W from five, set
 * 7's 1379.8 W from six, set 9's 651.7 W from four, set 11's 634.3 W from nine
 * with one refused, set 13's 244.3 W from nine. That is what licences them:
 * the fixtures carry the defect the issue reported, at the figures the issue
 * quoted.
 *
 * The three deadlifts are analysed UNCUED and with no instant, because their
 * cue tracks carry no metronome and their sets recorded no prep window. Their
 * archived summaries were computed the same way, and set 5's 4347.4 W and set
 * 6's 2386.5 W are the figures #290's second comment quotes.
 *
 * ## Why the work-start instants are literals
 *
 * Each field-42 set's `workStartedAt_ms` is carried below as a constant with
 * its provenance, rather than by committing a `-prep.csv` sidecar beside the
 * capture. `ArtefactRepTest` does the same with field-37 set 10's terminal cue
 * instant and states the terms: the one instant a test needs is cheaper to
 * carry as a literal than a whole sidecar is to enrol in every corpus that
 * walks them. Set 9 is the one that needs it -- it publishes
 * `detectionsBeforeWorkStart: 1`, so without the instant it resolves a fifth
 * detection its own archive does not.
 */
class ArtefactCorpusBaselineTest {
    private fun load(fixture: String): List<ImuSample> =
        ImuCsv.decode(javaClass.getResourceAsStream("/$fixture.csv")!!.readBytes().decodeToString())

    private fun cues(fixture: String): List<VoiceCue> =
        CueTrack.read(fixture).map { VoiceCue(it.timestampMs, it.label) }

    private fun round3(x: Double) = Math.round(x * 1000.0) / 1000.0

    /**
     * One field-42 set, analysed the way its session analysed it: its own cue
     * track, its own work-start instant, `cadenceGuided` true because a
     * metronome ran.
     */
    private fun cued(fixture: String, loadKg: Double, direction: LiftDirection, workStartedAtMs: Long) =
        SetAnalyzer.analyze(
            load(fixture),
            direction,
            loadKg,
            SetTargets(cadenceGuided = true),
            DspConfig(),
            cues(fixture),
            workStartedAtMs,
        )

    /** One field-43 set: no metronome track, no prep instant. */
    private fun bare(fixture: String, loadKg: Double) =
        SetAnalyzer.analyze(load(fixture), conFirst, loadKg, SetTargets(), DspConfig(), emptyList())

    private val conFirst = LiftDirection(startsWith = StartPhase.CONCENTRIC, concentricUp = true)
    private val eccFirst = LiftDirection(startsWith = StartPhase.ECCENTRIC, concentricUp = true)

    /**
     * Set 9's declared geometry: a seated cable row measured off the stack.
     * `sensorOnStack` forces `measuredPlane` to VERTICAL whatever plane the
     * lifter works in, and with ratio 1.0 and no inversion `sensorToLifter` is
     * 1.0, so every term the analyzer reads matches the archive's.
     */
    private val rowOnStack = LiftDirection(
        startsWith = StartPhase.CONCENTRIC,
        concentricUp = true,
        plane = MovementPlane.HORIZONTAL,
        sensorOnStack = true,
    )

    /** The two assisted pull-ups: stack-mounted, ratio 1.0, uninverted, vertical. */
    private val pullOnStack = LiftDirection(
        startsWith = StartPhase.CONCENTRIC,
        concentricUp = true,
        sensorOnStack = true,
    )

    private companion object {
        /** `workStartedAt_ms` for field-42 sets 2, 5, 7, 9, 11 and 13, from that session's `meta.json`. */
        const val WORK_START_SET02 = 1788774182888L
        const val WORK_START_SET05 = 1788774769073L
        const val WORK_START_SET07 = 1788775291600L
        const val WORK_START_SET09 = 1788775636216L
        const val WORK_START_SET11 = 1788775982160L
        const val WORK_START_SET13 = 1788776254848L
    }

    /** `summary.peakPower_w` as the export computes it today: the max over every published rep. */
    private fun publishedPeakPowerW(a: SetAnalysis) = a.reps.mapNotNull { it.peakPowerW }.maxOrNull()

    /** `summary.peakConVel_mps`, likewise. */
    private fun publishedPeakConVelMps(a: SetAnalysis) = a.reps.maxOfOrNull { it.peakConVelMps }

    /**
     * FIELD-42 SET 2, the headline of #290: 3606.3 W on a 24.9476 kg press.
     *
     * The arithmetic the issue does, redone here from the published triple
     * alone: 3606.3 / (2.516 * 24.9476) is 57.5 m/s2, which is 5.86 g of total
     * support acceleration and about 4.9 g of net drive, on a set prescribed
     * `3010` and rated 4-8 for effort.
     */
    @Test
    fun `field-42 set 2 publishes 3606_3 W and 2_516 m per s on a 24_9 kg press`() {
        val a = cued("field-ohp-3010-7rep-s42-set02", 24.94758035055195, conFirst, WORK_START_SET02)
        assertEquals(9, a.reps.size, "detections, against 7 reps performed")
        assertEquals(0, a.refusedDetections, "a bound was derived and refused nothing")
        assertEquals(0, a.detectionsBeforeWorkStart, "nothing finished before the work began")
        assertEquals(0, a.detectionsAfterSetEndCue, "and nothing began after the set was called over")
        assertEquals(3606.3, publishedPeakPowerW(a), "summary peakPower_w")
        assertEquals(2.516, publishedPeakConVelMps(a), "summary peakConVel_mps")
        // The two reps that hold them, and their neighbours' figures, so the
        // pair cannot be read as the whole set being fast.
        assertEquals(
            listOf(260.3, 268.1, 219.1, 3606.3, 297.3, 1961.8, 329.4, 241.5, 214.1),
            a.reps.map { it.peakPowerW },
            "peakPower_w per detection",
        )
        val impliedG = 3606.3 / (2.516 * 24.94758035055195) / 9.80665
        assertTrue(impliedG > 5.0, "the published triple implies $impliedG g of support acceleration")
    }

    @Test
    fun `field-42 sets 5 and 7 publish 1135_7 W and 1379_8 W on a 47_6 kg and a 56_7 kg bench`() {
        val five = cued("field-bench-3010-6rep-s42-set05", 47.62719885105372, eccFirst, WORK_START_SET05)
        assertEquals(5, five.reps.size, "detections, against 6 reps performed")
        assertEquals(1, five.detectionsAfterSetEndCue, "its own Done cue excluded one")
        assertEquals(1135.7, publishedPeakPowerW(five), "set 5 summary peakPower_w")
        assertEquals(0.989, publishedPeakConVelMps(five), "set 5 summary peakConVel_mps")

        val seven = cued("field-bench-3010-6rep-s42-set07", 56.69904625125443, eccFirst, WORK_START_SET07)
        assertEquals(6, seven.reps.size, "detections, matching the 6 reps performed")
        assertEquals(1, seven.detectionsAfterSetEndCue, "its own Done cue excluded one")
        assertEquals(1379.8, publishedPeakPowerW(seven), "set 7 summary peakPower_w")
        assertEquals(1.084, publishedPeakConVelMps(seven), "set 7 summary peakConVel_mps")
        // The rep that holds set 7's figure also ranges 1.592 m on a bench
        // press, which is issue #291's defect on the same detection.
        assertEquals(1.592, seven.reps.last().romM, "and its rom_m, which #291 is about")
    }

    @Test
    fun `field-42 set 9 publishes 651_7 W on a 40_8 kg cable row and needs its work-start instant`() {
        val a = cued("field-cablerow-3010-8rep-s42-set09", 40.82331330090319, rowOnStack, WORK_START_SET09)
        assertEquals(4, a.reps.size, "detections, against 8 reps performed")
        assertEquals(1, a.detectionsBeforeWorkStart, "one detection finished before the work began")
        assertEquals(651.7, publishedPeakPowerW(a), "summary peakPower_w")
        assertEquals(1.171, publishedPeakConVelMps(a), "summary peakConVel_mps")
        assertEquals(1.88, a.reps.map { it.romM }.max(), "and a 1.88 m range on a seated row -- #291")
    }

    @Test
    fun `field-42 sets 11 and 13 publish 634_3 W and 244_3 W on the assisted pull-up`() {
        val eleven = cued("field-pullup-3010-8rep-s42-set11", 22.579000000000008, pullOnStack, WORK_START_SET11)
        assertEquals(9, eleven.reps.size, "detections, against 8 reps performed")
        assertEquals(1, eleven.refusedDetections, "one unpaired range outlier refused")
        assertEquals("unpairedRangeOutlier", eleven.refusedDetectionReason, "and the word for it")
        assertEquals(2, eleven.detectionsAfterSetEndCue, "two began after the set was called over")
        assertEquals(634.3, publishedPeakPowerW(eleven), "set 11 summary peakPower_w")
        assertEquals(1.275, publishedPeakConVelMps(eleven), "set 11 summary peakConVel_mps")

        val thirteen = cued("field-pullup-4010-8rep-s42-set13", 22.579000000000008, pullOnStack, WORK_START_SET13)
        assertEquals(9, thirteen.reps.size, "detections, against 8 reps performed")
        assertEquals(3, thirteen.detectionsAfterSetEndCue, "three began after the set was called over")
        assertEquals(244.3, publishedPeakPowerW(thirteen), "set 13 summary peakPower_w")
        assertEquals(1.026, publishedPeakConVelMps(thirteen), "set 13 summary peakConVel_mps")
    }

    /**
     * FIELD-43's THREE DEADLIFTS, and the worst figure in the corpus.
     *
     * Set 5 publishes 4347.4 W at 83.9 kg. #290's second comment measures why
     * it is the worst case: the accelerometer RAILS -- two samples at or above
     * 15.999 g on each unit, at the instants the bar meets the floor -- so the
     * offending readings do not bound the real acceleration even from below.
     */
    @Test
    fun `field-43's deadlifts publish 1203_1 W, 4347_4 W and 2386_5 W at 61, 84 and 102 kg`() {
        val four = bare("field-deadlift-straight-5rep-s43-set04", 61.234969951354785)
        assertEquals(9, four.reps.size, "detections, against 5 reps performed by hand count")
        assertEquals(1203.1, publishedPeakPowerW(four), "set 4 summary peakPower_w")

        val five = bare("field-deadlift-straight-5rep-s43-set05", 83.91458845185656)
        assertEquals(7, five.reps.size, "detections, against 5 reps performed")
        assertEquals(4347.4, publishedPeakPowerW(five), "set 5 summary peakPower_w")
        assertEquals(1.246, publishedPeakConVelMps(five), "set 5 summary peakConVel_mps")
        assertEquals(3430.7, five.reps[1].peakPowerW, "and the second implausible rep #290 names")

        val six = bare("field-deadlift-straight-5rep-s43-set06", 102.05828325225797)
        assertEquals(7, six.reps.size, "detections, against 5 reps performed")
        assertEquals(2386.5, publishedPeakPowerW(six), "set 6 summary peakPower_w")
    }

    /**
     * The accelerometer railing, read off the committed bytes rather than
     * relayed from the issue -- and off BOTH units, which is what says the
     * saturation is the sensor's range and not one unit's mount.
     */
    @Test
    fun `field-43 set 5 saturates its accelerometer on both units`() {
        val roleA = load("field-deadlift-straight-5rep-s43-set05")
        val roleB = load("field-deadlift-straight-5rep-s43-set05-imu-b")
        assertEquals(2, roleA.count { maxAxisG(it) >= 15.999 }, "role a samples at or above 15.999 g on one axis")
        assertEquals(2, roleB.count { maxAxisG(it) >= 15.999 }, "role b samples at or above 15.999 g on one axis")
        assertEquals(17.84, round3(roleA.maxOf { magnitudeG(it) }), "role a's largest magnitude")
        assertEquals(20.589, round3(roleB.maxOf { magnitudeG(it) }), "role b's largest magnitude")
    }

    /**
     * The two field-37 captures #255 names, pinned here beside the nine so the
     * before side of the table is one measurement.
     *
     * `ArtefactRepTest` already pins both and this does not restate its
     * figures: what is added is the pair the withholding rule is judged on.
     */
    @Test
    fun `the two field-37 captures publish 407_4 W and 783_2 W`() {
        val eight = SetAnalyzer.analyze(
            load("field-assistedpullup-3010-s37-set08"),
            conFirst,
            30.25,
            SetTargets(),
            DspConfig(),
            emptyList(),
        )
        assertEquals(7, eight.reps.size, "detections")
        assertEquals(407.4, publishedPeakPowerW(eight), "set 8 summary peakPower_w")

        val three = SetAnalyzer.analyze(
            load("field-ohp-prepinflated-s37-set03"),
            conFirst,
            22.67961850050177,
            SetTargets(),
            DspConfig(),
            emptyList(),
        )
        assertEquals(11, three.reps.size, "detections")
        assertEquals(783.2, publishedPeakPowerW(three), "set 3 summary peakPower_w")
    }

    private fun magnitudeG(s: ImuSample) = sqrt(s.axG * s.axG + s.ayG * s.ayG + s.azG * s.azG)

    private fun maxAxisG(s: ImuSample) = maxOf(kotlin.math.abs(s.axG), kotlin.math.abs(s.ayG), kotlin.math.abs(s.azG))
}
