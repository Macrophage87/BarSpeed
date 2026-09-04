package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.StartPhase
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The three field-37 captures issue #125 was REOPENED on, and what the
 * analyzer publishes for each.
 *
 * ## Provenance, and why only one capture is added here
 *
 * Two of the three sets are ALREADY committed, under names given by the
 * issues that first needed them, and this file uses those rather than
 * committing second copies:
 *
 * - set 3 is `field-ohp-prepinflated-s37-set03.csv`
 * - set 10 is `field-assistedpullup-3010-s37-set10.csv`
 * - set 1 is `field-ohp-3010-8rep-s37-set01.csv`, added by this commit
 *   because no test held it
 *
 * Session 37's own `meta.json` says: `epoch` 2026-09-02T09:20:45.365Z,
 * `timeZoneId` America/New_York, `appVersion` **0.1.48**, `sensorModel`
 * WitMotion WT901BLECL, one armed role analysed (`analysedRole: "a"`) of two
 * expected. Every geometry figure below is that file's, not a guess and not a
 * filename reading:
 *
 * - set 1 -- `seated_overhead_press`, 45.0 lb / 20.411656650451594 kg, tempo
 *   `3010`, `startsWith: "concentric"`, `concentric: "up"`,
 *   `plane: "vertical"`, sensor on the bar, travel ratio 1.0, **8 reps
 *   performed** (`repsManual: true`), RPE 6, not failed. 4,692 samples at
 *   99.4066539521085 Hz.
 * - set 3 -- same exercise and geometry, 50.0 lb / 22.67961850050177 kg,
 *   **7 reps performed**, `failed: true`, `limiter: "muscle"`. 3,852 samples
 *   at 99.43453226264556 Hz.
 * - set 10 -- `assisted_pull_up`, 51.7 lb / 23.443564147942737 kg of
 *   ASSISTANCE, `bodyweight: true`, concentric-first, drive up, vertical,
 *   sensor on the bar, **6 reps performed**, `failed: true`,
 *   `limiter: "pace"`. 4,060 samples at 99.40732758620689 Hz.
 *
 * ## The licence for using them
 *
 * Run against the SHIPPED analyzer at tag `v0.1.48`
 * (`fca343da4f62b17bed05b4f3b3aa9a612da2d1dd`), all three reproduce their
 * session's published `repMetrics` and `summary` to the last published digit
 * -- set 1's 1.356 m / 1.091 m/s / 228.0 W, set 3's 1.219 m / 1.263 m/s /
 * 309.4 W, set 10's 1.746 m / 1.044 m/s / 552.4 W. The fixtures carry the
 * defect the issue reported.
 *
 * **THEY DO NOT ALL STILL CARRY IT, AND THAT IS THE FIRST FINDING HERE.**
 * Between v0.1.48 and this commit the DSP family for issues #87 and #138
 * landed, and it moved two of the three outright. Set 1 now resolves eleven
 * detections where it resolved three, its phantom now begins 2.241 s AFTER
 * the `Done` cue and is excluded by the set-end bound that already exists,
 * and the set publishes `velocityLoss_pct` where it published none. Set 3 now
 * resolves eleven where it resolved three, and its published `peakPower_w`
 * went from 309.4 W to **783.2 W** -- the same defect class, larger. Only set
 * 10 is unchanged, to every digit. The figures below are measured at
 * `41c0c96bbc3be29cc7d705bf3d74c7196a0d12de`, and none of them is quoted from
 * the session archive.
 */
class ArtefactRepTest {
    private fun load(fixture: String): List<ImuSample> =
        ImuCsv.decode(javaClass.getResourceAsStream("/$fixture.csv")!!.readBytes().decodeToString())

    /** Concentric-first, drive up, sensor on the bar -- all three sets' declared geometry. */
    private val conFirst = LiftDirection(startsWith = StartPhase.CONCENTRIC, concentricUp = true)

    private fun analyse(fixture: String, loadKg: Double) =
        SetAnalyzer.analyze(load(fixture), conFirst, loadKg, SetTargets(), DspConfig(), emptyList())

    private fun round3(x: Double) = Math.round(x * 1000.0) / 1000.0

    private fun peakPower(a: SetAnalysis) = a.reps.mapNotNull { it.peakPowerW }.maxOrNull()

    private fun meanRom(a: SetAnalysis) = round3(a.reps.map { it.romM }.average())

    private val set01 = "field-ohp-3010-8rep-s37-set01"
    private val set03 = "field-ohp-prepinflated-s37-set03"
    private val set10 = "field-assistedpullup-3010-s37-set10"

    private companion object {
        /** field-37 set 10's `Set ended` instant, from the session archive's cue track. */
        const val TERMINAL_CUE_MS = 1788342608185L
    }

    /**
     * The phantom is a fact about SEGMENTATION and stays one: the segmenter
     * goes on finding it after issue #125's fix, which refuses it downstream
     * rather than making it disappear. Asserted against the segmenter's own
     * output so it survives that fix and keeps saying what is there --
     * including its range ratio, which is the figure `RepRefusal`'s KDoc
     * quotes as 5.23 and which no walk over a PUBLISHED list can reach once
     * the rule is wired in.
     */
    @Test
    fun `set 10's segmenter still finds a fifth detection ranging 1_746 m`() {
        val samples = load(set10)
        val raw = VelocityEstimator.estimate(samples, DspConfig(), conFirst.measuredPlane)
        val series = raw.mappedToLifter(conFirst.sensorToLifter)
        val spans = RepSegmenter.segmentDetailed(series, conFirst, DspConfig()).spans
        assertEquals(5, spans.size, "movements the segmenter paired into detections")
        val phantom = spans.last()
        assertEquals(false, phantom.hasEccentric, "the last one resolved no eccentric")
        val displacements = spans.map { round3(RepSegmenter.displacement(series, it.conStartIdx, it.conEndIdx)) }
        assertEquals(listOf(0.471, 0.33, 0.481, 0.334, 1.746), displacements, "displacement per detection")
        // Its range against the lower median of the other four, 0.334 -- the
        // ratio the refusal judges on, computed here from the segmenter's own
        // displacements.
        assertEquals(5.23, Math.round(1.746 / 0.334 * 100.0) / 100.0, "the phantom's range ratio")
    }

    /**
     * TERMINAL_CUE_MS is field-37 set 10's own `Set ended` instant, read from
     * `set10_assisted_pull_up_cues.csv` in the session archive. That track is
     * not committed beside the capture -- see `CuedRepCoverageTest`'s unscored
     * list for the terms -- so the one instant this issue needs is carried
     * here as a literal with its provenance stated, rather than the whole
     * track being committed to reach it.
     *
     * A failed set says `Set ended`, never `Done`: `GuidedCadenceRunner`
     * speaks `Done` only where the prescription was called through, and this
     * set was ended by the lifter at 6 of a planned 8 for pace.
     */
    @Test
    fun `set 10's phantom sits inside the cued window, where the set-end bound cannot reach it`() {
        val samples = load(set10)
        assertEquals(0, samples.count { it.timestampMs > TERMINAL_CUE_MS }, "samples after the terminal cue")
        val raw = VelocityEstimator.estimate(samples, DspConfig(), conFirst.measuredPlane)
        val series = raw.mappedToLifter(conFirst.sensorToLifter)
        val phantom = RepSegmenter.segmentDetailed(series, conFirst, DspConfig()).spans.last()
        assertEquals(
            -3660L,
            samples[phantom.conStartIdx].timestampMs - TERMINAL_CUE_MS,
            "the phantom's drive BEGINS this many ms relative to the cue",
        )
        assertEquals(
            -302L,
            samples[phantom.conEndIdx].timestampMs - TERMINAL_CUE_MS,
            "and ENDS this many ms relative to it -- both inside the cued window",
        )
        assertEquals(
            0,
            SetEnd.Cued(TERMINAL_CUE_MS).detectionsAfter(listOf(samples[phantom.conStartIdx].timestampMs)),
            "so the set-end bound excludes nothing",
        )
    }

    @Test
    fun `set 10's phantom drive is preceded by two samples the sensor cannot have measured`() {
        val samples = load(set10)
        val outOfRange = samples.indices.filter { mag(samples[it]) > 2.0 }
        assertEquals(listOf(3692, 3693), outOfRange, "the whole set's out-of-range samples, by index")
        assertEquals(4.048, round3(mag(samples[3692])), "|accel| g")
        assertEquals(16.191, round3(mag(samples[3693])), "|accel| g")
        // Their neighbours on both sides, and the rotation rate throughout.
        assertTrue(mag(samples[3691]) < 1.0, "the sample before reads under 1 g")
        assertTrue(mag(samples[3694]) < 1.3, "the sample after reads under 1.3 g")
        val gyro = samples.maxOf { sqrt(it.wxDps * it.wxDps + it.wyDps * it.wyDps + it.wzDps * it.wzDps) }
        assertTrue(gyro < 6.0, "nothing in this set rotates faster than 6 dps, measured $gyro")
    }

    /**
     * THE MEASUREMENT THE REFUSAL RULE IS BUILT ON, and the one that says why
     * the rule has two clauses rather than one.
     *
     * Replace every sample above 4 g with the last in-range reading and
     * re-run the unmodified analyzer. Set 10 resolves FOUR detections instead
     * of five -- the fifth ceases to exist, and the four that remain carry
     * exactly the ranges the refusal keeps. Set 8 resolves SEVEN either way;
     * its detection 6 survives and only its figures collapse. One capture's
     * outlier is manufactured by the corruption; the other's is a real rep
     * the corruption inflated, and the eccentric partner is what tells them
     * apart.
     *
     * The substitution is a diagnostic and is deliberately NOT what ships:
     * `RepRefusal`'s KDoc argues why a rule over the samples is a change to
     * the measurement and this one is not.
     */
    @Test
    fun `substituting the out-of-range samples deletes set 10's outlier and keeps set 8's`() {
        val ten = analyseWithInRangeAccel(set10, 23.443564147942737)
        assertEquals(4, ten.reps.size, "set 10 resolves four detections without the two corrupt samples")
        assertEquals(
            listOf(0.471, 0.33, 0.481, 0.334),
            ten.reps.map { round3(it.romM) },
            "and they are the four the refusal keeps",
        )

        val eight = analyseWithInRangeAccel("field-assistedpullup-3010-s37-set08", 23.443564147942737)
        assertEquals(7, eight.reps.size, "set 8 resolves seven either way")
        assertEquals(0.23, round3(eight.reps[6].romM), "its detection 6 survives, ranging this far")
        assertEquals(53.8, eight.reps[6].peakPowerW, "with this peak power, against the 315.7 W it publishes")
        assertNotNull(eight.reps[6].eccS, "and it resolved both phases either way")
    }

    /**
     * The uncorrupted neighbour of the pair above, pinned so the substitution
     * result is read against what the capture publishes untouched.
     */
    @Test
    fun `set 8 publishes seven detections and 315_7 W from the one that carries the corrupt sample`() {
        val a = analyse("field-assistedpullup-3010-s37-set08", 23.443564147942737)
        assertEquals(7, a.reps.size, "detections")
        assertEquals(0.878, round3(a.reps[6].romM), "detection 6 rom_m")
        assertEquals(1.68, round3(a.reps[6].eccS!!), "and it resolved an eccentric partner, so clause 1 keeps it")
        assertEquals(315.7, a.reps[6].peakPowerW, "detection 6 peakPower_w, against 37.7-53.5 for the other six")
        assertEquals(
            listOf(4079),
            load("field-assistedpullup-3010-s37-set08").indices.filter {
                mag(load("field-assistedpullup-3010-s37-set08")[it]) > 4.0
            },
            "the whole set's samples above 4 g, by index",
        )
    }

    /**
     * Analysed WITHOUT a cue track, because none is committed for it. Its
     * archived track puts a `Done` at 1788340885412 and the phantom's drive
     * begins 2.241 s after it, so the set-end bound already excludes this
     * detection on the device; that is a fact about the ARCHIVE and it is
     * stated here rather than asserted, because the fixture cannot reach it.
     * What is asserted is what this capture publishes uncued, which is one
     * detection more.
     */
    @Test
    fun `set 1 analysed uncued keeps the detection its Done cue would exclude`() {
        val a = analyse(set01, 20.411656650451594)
        assertEquals(11, a.reps.size, "detections, one more than the cued set publishes")
        assertNull(a.detectionsAfterSetEndCue, "no cue track passed, so nothing is bounded and the count is null")
    }

    /**
     * Set 3 is analysed uncued here even though a cue track IS committed
     * beside it for `field-ohp-prepinflated-s37-set03`: this file passes no
     * track, so nothing is bounded, and the figures below are the unbounded
     * ones.
     */
    @Test
    fun `set 3 keeps every detection and publishes 783_2 W, worse than the 309_4 W it shipped`() {
        val a = analyse(set03, 22.67961850050177)
        assertEquals(11, a.reps.size, "detections")
        assertNull(a.detectionsAfterSetEndCue, "no cue track passed, so nothing is bounded")
        assertEquals(1.396, a.reps.maxOf { it.peakConVelMps }, "summary peakConVel_mps")
        assertEquals(783.2, peakPower(a), "summary peakPower_w")
        assertEquals(0.698, meanRom(a), "summary meanRom_m")
        assertEquals(63.5, SetAnalyzer.romSpreadPct(a.reps), "summary romSpread_pct")
        assertEquals(67.0, a.velocityLossPct, "velocityLoss_pct")
    }

    /**
     * The residue no rule over a rep LIST can reach, pinned so it cannot be
     * mistaken for fixed. Set 3's rep 6 has an ordinary range and an ordinary
     * mean power for its set, and a peak power five times its own mean --
     * from one 11.601 g sample inside its drive.
     */
    @Test
    fun `set 3's rep 6 is an ordinary rep carrying a corrupt sample inside its drive`() {
        val rep = analyse(set03, 22.67961850050177).reps[6]
        assertEquals(0.533, rep.romM, "an ordinary range for this set")
        assertEquals(135.1, rep.meanConPowerW, "an ordinary mean drive power")
        assertEquals(783.2, rep.peakPowerW, "and a peak power 5.8x its own mean")
        assertNotNull(rep.eccS, "it resolved both phases, so it is a rep by every test here")
    }

    private fun mag(s: ImuSample) = sqrt(s.axG * s.axG + s.ayG * s.ayG + s.azG * s.azG)

    /**
     * The capture with every sample above 4 g replaced by the last in-range
     * accelerometer reading, timestamps and gyro untouched.
     */
    private fun analyseWithInRangeAccel(fixture: String, loadKg: Double): SetAnalysis {
        val raw = load(fixture)
        var lastGood = raw.first()
        val fixed = raw.map { s ->
            if (mag(s) > 4.0) {
                s.copy(axG = lastGood.axG, ayG = lastGood.ayG, azG = lastGood.azG)
            } else {
                lastGood = s
                s
            }
        }
        return SetAnalyzer.analyze(fixed, conFirst, loadKg, SetTargets(), DspConfig(), emptyList())
    }
}
