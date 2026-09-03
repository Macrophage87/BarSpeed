package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.StartPhase
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a set publishes when the segmenter resolves nothing, and which
 * captures in this corpus are in that state. Issue #138.
 *
 * #138's shape is a HEALTHY stream -- contiguous `sample_idx`, no interval
 * over 100 ms, the full set window covered -- that yields `reps: []` and a
 * `summary` object with every key absent. The export says so only by
 * omission, and a set whose integrator ran away is then byte-identical to a
 * manual set recorded with no sensor at all.
 *
 * These are CHARACTERIZATION pins. They record what the pipeline does at this
 * commit; none of them says the result is right. In particular the
 * `field-rdl-3010-10rep-s36-set04` figures below pin a set that resolves ONE
 * rep out of ten performed, which is a failure this file does not fix and
 * does not describe as fixed.
 *
 * ## What issue #87 moved and what it did not
 *
 * Issue #87 dropped the gyro clause from batch anchor candidacy on the sets
 * whose gyro distribution straddles the gate, and three of the four sets
 * #138 named came off zero as a result -- 4, 1 and 3 spans against 6 reps
 * performed, pinned in [AnchorSupplyByMountTest]. The Romanian deadlift did
 * not: its median gyro magnitude is under the gate, so it never straddled and
 * #87 never reached it. It is the capture this file is measured against.
 *
 * ## Provenance of the capture added here
 *
 * Every field is read from the capture's own `meta.json`, never from the file
 * name and never from memory.
 *
 * Session **field-36**, epoch 2026-09-01T08:35:19.892Z, America/New_York, app
 * **0.1.47**, WitMotion WT901BLECL, IMU header
 * `timestamp_ms,ax_g,ay_g,az_g,wx_dps,wy_dps,wz_dps,roll_deg,pitch_deg,yaw_deg,sample_idx`.
 *
 * - `field-rdl-3010-10rep-s36-set04` -- set 4, romanian_deadlift, tempo
 *   **3010**, 52.163122551154075 kg (115.0 lb), planned 10, performed **10**,
 *   `repsManual` true, RPE 4, eccentric-first, concentric up, vertical, not on
 *   a stack, not inverted, travel ratio 1.0, `kind` dynamic, prep 25 s,
 *   **7104** samples at **99.36489284315373 Hz**, roll excursion **320.8 deg**,
 *   sensor role **a**. Its `analysedRole` is declared `b` and no role-b stream
 *   exists for this set, which is issue #207 and is why the archive's own
 *   `summary` for it is empty for a reason that has nothing to do with #138;
 *   the stream committed here is role a, the one that was recorded.
 *
 * ITS CUE TRACK IS DELIBERATELY NOT COMMITTED, and the reason is scope rather
 * than the file being uninteresting: a capture carrying a `-cues.csv` is
 * enrolled in [CuedRepCoverageTest], which would move six aggregate rep-count
 * figures there, none of which issue #138 is about. The 10 reps performed
 * comes from `meta.json` above, which is where every other field here comes
 * from too.
 *
 * It is the immediate neighbour of `field-rdl-3010-10rep-s36-set05`, already
 * committed: same session, same exercise, same tempo, same load, same hand
 * count, recorded four minutes earlier. Set 05 resolves nothing and set 04
 * resolves one, so the pair is the difference between a blank analysis and a
 * nearly-blank one on two sets a lifter would call identical.
 */
class BlankAnalysisTest {
    private fun load(n: String): List<ImuSample> = ImuCsv.decode(
        javaClass.getResourceAsStream("/$n.csv")!!.readBytes().decodeToString(),
    )

    private fun series(fixture: String): VelocitySeries =
        VelocityEstimator.estimate(load(fixture), DspConfig(), MovementPlane.VERTICAL)

    private fun spans(fixture: String, startsWith: StartPhase): Int =
        RepSegmenter.segment(series(fixture), LiftDirection(startsWith), DspConfig()).size

    /** Every capture on the classpath, as [GyroGateTest] and [AnchorSupplyByMountTest] enumerate them. */
    private val corpus: List<String> by lazy {
        File(javaClass.getResource("/field-still-0rep.csv")!!.toURI()).parentFile.list()!!
            .filter { it.startsWith("field-") && it.endsWith(".csv") && !it.endsWith("-cues.csv") }
            .map { it.removeSuffix(".csv") }
            .sorted()
    }

    /**
     * The raw sign-runs of the drift-corrected series, before any demotion,
     * with each of [RepSegmenter.classifyRuns]'s three demotion terms counted
     * over them.
     *
     * The three counts are INDEPENDENT and do not sum to the number demoted:
     * `classifyRuns` demotes on a single three-way `||`, so one run can fail
     * two of the terms and is counted under both. Nothing here attributes a
     * demotion to one cause.
     */
    private data class RawRuns(
        val movement: Int,
        val overDisplacementCap: Int,
        val belowStartThreshold: Int,
        val shorterThanMinPhase: Int,
        val maxDisplacementM: Double,
    )

    private fun rawRuns(fixture: String): RawRuns {
        val s = series(fixture)
        val config = DspConfig()
        val v = s.velocityMps
        val n = s.size
        val sign = IntArray(n) {
            when {
                v[it] > config.pauseBandMps -> 1
                v[it] < -config.pauseBandMps -> -1
                else -> 0
            }
        }
        var movement = 0
        var over = 0
        var below = 0
        var brief = 0
        var maxDisp = 0.0
        var start = 0
        for (i in 1..n) {
            if (i < n && sign[i] == sign[start]) continue
            val end = i - 1
            if (sign[start] != 0) {
                movement++
                val disp = RepSegmenter.displacement(s, start, end)
                val peak = (start..end).maxOf { abs(v[it]) }
                val durationS = s.timeS[end] - s.timeS[start]
                if (disp > config.maxRunDisplacementM) over++
                if (peak < config.startThresholdMps) below++
                if (durationS < config.minPhaseS) brief++
                maxDisp = maxOf(maxDisp, disp)
            }
            start = i
        }
        return RawRuns(movement, over, below, brief, maxDisp)
    }

    @Test
    fun `the captures that resolve nothing whichever phase the lift is declared to open with`() {
        // Direction-independent, so it needs no table of declared geometry: a
        // capture listed here publishes a blank analysis however the plan
        // declares the lift. Two captures qualify at this commit.
        val blankBothWays = corpus.filter {
            spans(it, StartPhase.ECCENTRIC) == 0 && spans(it, StartPhase.CONCENTRIC) == 0
        }
        assertEquals(
            listOf("field-rdl-3010-10rep-s36-set05", "field-still-0rep"),
            blankBothWays,
            "the corpus's blank-either-way captures",
        )
        // And one more that is blank on one declaration only. Its own plan
        // says concentric -- a press driven off the rack -- so it is not blank
        // in the field; it is here because it is the corpus's only capture
        // that resolves qualifying movement runs and pairs none of them, which
        // is a different way to reach zero from the Romanian deadlift's.
        assertEquals(0, spans("field-seated-ohp-2rep", StartPhase.ECCENTRIC), "seated OHP read as eccentric-first")
        assertEquals(2, spans("field-seated-ohp-2rep", StartPhase.CONCENTRIC), "seated OHP as its plan declares it")
    }

    @Test
    fun `what a blank analysis publishes today`() {
        // The whole of what the lifter and the coach get: reps empty, no
        // velocity loss, no tempo compliance, and a measured sample rate that
        // says the stream was healthy -- which is #138's point. Nothing in
        // this object distinguishes it from a set recorded with no sensor.
        val rdl = SetAnalyzer.analyze(
            load("field-rdl-3010-10rep-s36-set05"),
            LiftDirection(StartPhase.ECCENTRIC),
            loadKg = 52.163122551154075,
        )
        assertEquals(emptyList(), rdl.reps, "reps resolved on a 10-rep set")
        assertNull(rdl.velocityLossPct, "velocity loss on a set with no reps")
        assertNull(rdl.tempoCompliance, "tempo compliance with no target declared")
        assertNull(rdl.detectionsAfterSetEndCue, "no cue track was passed, so nothing bounded the set")
        assertEquals(99.351, rdl.sampleRateHz, 1e-3, "measured sample rate of the stream that resolved nothing")
    }

    @Test
    fun `why the Romanian deadlift resolves nothing - three of its four movement runs are discarded`() {
        // 67.95 s of a 10-rep hip hinge collapses into FOUR sign-runs, of
        // which three displace further than DspConfig.maxRunDisplacementM and
        // are demoted, the longest by a factor of eleven. One run survives, it
        // is a DOWN, and an eccentric-first rep needs a DOWN followed by an UP,
        // so there is nothing to pair.
        //
        // Not a threshold finding: #138's body measured its own three dumbbell
        // sets uncapped and got 0, 1 and 1 spans. The cap is what discards the
        // runaway, not what causes it.
        val runs = rawRuns("field-rdl-3010-10rep-s36-set05")
        assertEquals(4, runs.movement, "raw movement runs across the whole set")
        assertEquals(3, runs.overDisplacementCap, "runs displacing past the 2.0 m cap")
        assertEquals(0, runs.belowStartThreshold, "runs too slow to count")
        assertEquals(0, runs.shorterThanMinPhase, "runs too brief to count")
        assertEquals(21.93, runs.maxDisplacementM, 0.01, "the longest single run, metres")
        val qualifying =
            RepSegmenter.classifyRuns(series("field-rdl-3010-10rep-s36-set05"), DspConfig())
                .filter { it.type != RunType.STILL }
        assertEquals(1, qualifying.size, "movement runs surviving demotion")
        assertEquals(RunType.DOWN, qualifying.single().type, "the one surviving run's direction")
    }

    @Test
    fun `the seated overhead press reaches zero the other way - runs survive and none pair`() {
        val runs = rawRuns("field-seated-ohp-2rep")
        assertEquals(7, runs.movement, "raw movement runs")
        assertEquals(1, runs.overDisplacementCap, "runs past the cap -- a minority here, three of four on the deadlift")
        val qualifying =
            RepSegmenter.classifyRuns(series("field-seated-ohp-2rep"), DspConfig())
                .filter { it.type != RunType.STILL }
        assertEquals(3, qualifying.size, "movement runs surviving demotion")
        assertTrue(qualifying.all { it.type == RunType.UP }, "all three survivors are UP, so no DOWN opens a rep")
    }

    @Test
    fun `the neighbouring Romanian deadlift resolves one rep of ten and is not blank`() {
        // field-36 set 04, committed here. Same session, exercise, tempo, load
        // and hand count as set 05, four minutes earlier, and its integrator
        // runs away FURTHER -- a single run displacing 123.64 m against set
        // 05's 21.93 m -- yet one rep survives, so its analysis is not blank.
        //
        // This is the pin on what a blank-analysis diagnosis does NOT cover. A
        // set can carry two orders of magnitude more drift than the set beside
        // it and still publish a summary, because a summary is published
        // whenever ONE rep resolves. Under-resolution reaching zero is #138;
        // under-resolution stopping at one is the same defect and nothing the
        // export carries distinguishes it from a well-measured single.
        val runs = rawRuns("field-rdl-3010-10rep-s36-set04")
        assertEquals(12, runs.movement, "raw movement runs")
        assertEquals(2, runs.overDisplacementCap, "runs displacing past the cap")
        assertEquals(123.64, runs.maxDisplacementM, 0.01, "the longest single run, metres")
        assertEquals(1, spans("field-rdl-3010-10rep-s36-set04", StartPhase.ECCENTRIC), "spans, 10 reps performed")
        val analysis = SetAnalyzer.analyze(
            load("field-rdl-3010-10rep-s36-set04"),
            LiftDirection(StartPhase.ECCENTRIC),
            loadKg = 52.163122551154075,
        )
        assertEquals(1, analysis.reps.size, "reps published to the lifter")
        assertNull(analysis.velocityLossPct, "one rep, so no velocity loss -- absence stays absence")
    }
}
