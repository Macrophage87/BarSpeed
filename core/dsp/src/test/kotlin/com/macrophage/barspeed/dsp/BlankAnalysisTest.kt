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
 * `summary` object with every key absent. The export said so only by
 * omission, and a set whose integrator ran away WAS byte-identical to a
 * manual set recorded with no sensor at all -- until the commit "Say why a
 * set resolved nothing instead of publishing an empty summary" on this
 * branch published `noRepsReason`. That key is what tells them apart now,
 * and it is asserted below rather than described here.
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

    /**
     * The run limits unconverted. Every fixture here is declared at
     * `travelRatio` 1.0, so this names the frame these series are in rather
     * than changing any number.
     */
    private val sensorFrame = RunThresholds.sensorFrame(DspConfig())

    private fun series(fixture: String): VelocitySeries =
        VelocityEstimator.estimate(load(fixture), DspConfig(), MovementPlane.VERTICAL)

    /**
     * The series BEFORE issue #94's runaway correction. The run-structure
     * assertions below are the diagnosis of why these captures published
     * nothing, and that diagnosis is about the series the correction acts on;
     * taking it off the shipped series would measure the result instead of the
     * cause and would silently go vacuous.
     */
    private fun anchoredSeries(fixture: String): VelocitySeries =
        VelocityEstimator.estimateAnchored(load(fixture), DspConfig(), MovementPlane.VERTICAL)

    private fun spans(fixture: String, startsWith: StartPhase): Int =
        RepSegmenter.segment(series(fixture), LiftDirection(startsWith), DspConfig()).size

    /** Every capture on the classpath, as [GyroGateTest] and [AnchorSupplyByMountTest] enumerate them. */
    private val corpus: List<String> by lazy {
        File(javaClass.getResource("/field-still-0rep.csv")!!.toURI()).parentFile.list()!!
            .filter {
                it.startsWith("field-") && it.endsWith(".csv") &&
                    !it.endsWith("-cues.csv") && !it.endsWith("-prep.csv")
            }
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

    private fun rawRuns(fixture: String): RawRuns = rawRunsOf(anchoredSeries(fixture))

    private fun rawRunsOf(s: VelocitySeries): RawRuns {
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
        // declares the lift.
        //
        // ONE capture qualifies at this commit, and it is the sensor that
        // never moved. Two did before issue #94's runaway correction; the
        // Romanian deadlift that was the other now resolves ten spans on the
        // phase its plan declares. That is #138's exemplar leaving the corpus,
        // and it is the strongest single statement this branch can make about
        // the defect: no capture of a healthy stream with reps in it publishes
        // a blank analysis any more.
        val blankBothWays = corpus.filter {
            spans(it, StartPhase.ECCENTRIC) == 0 && spans(it, StartPhase.CONCENTRIC) == 0
        }
        assertEquals(
            listOf("field-still-0rep"),
            blankBothWays,
            "the corpus's blank-either-way captures",
        )
        // The seated overhead press USED to be blank read as eccentric-first,
        // resolving qualifying runs and pairing none of them. Since the
        // runaway correction its 5.5-11.5 s over-cap run resolves into
        // alternating strokes, so a DOWN exists for an UP to pair with and it
        // resolves one. Both readings are pinned: the plan declares concentric
        // and that is the number the lifter sees.
        assertEquals(1, spans("field-seated-ohp-2rep", StartPhase.ECCENTRIC), "seated OHP read as eccentric-first")
        assertEquals(3, spans("field-seated-ohp-2rep", StartPhase.CONCENTRIC), "seated OHP as its plan declares it")
        // The one capture that IS blank on its declared phase and should be:
        // a twenty-second rope dead hang, reps 0 in its own meta.json.
        assertEquals(
            0,
            spans("field-ropedeadhang-hold20-s37-set11", StartPhase.ECCENTRIC),
            "a hold resolves nothing, which is the right answer",
        )
    }

    @Test
    fun `what the capture that used to publish nothing publishes now`() {
        // This test was `what a blank analysis publishes today` and asserted
        // that this capture published reps: [] over a healthy 99.35 Hz stream
        // -- #138's exemplar, and byte-identical in the export to a manual set
        // recorded with no sensor. Issue #94's runaway correction resolves ten
        // spans against the ten reps the lifter performed, so that assertion
        // is not weakened here, it is INVERTED.
        //
        // The count landing on ten was never the reps being right, and issue
        // #72's slow-eccentric fallback has now taken it to ELEVEN against ten
        // performed. That is worth stating plainly rather than filing under
        // improvement: the total moved AWAY from the hand count. What moved
        // toward the truth is the composition. This capture carries a cue
        // track, and BatchCueCoverageTest scores the eleventh detection into a
        // window that was empty -- 9 matched of 10 marks with 1 stray before,
        // 10 matched with the same 1 stray after -- so the ten was a rep the
        // metronome called being missed while an unrelated detection outside
        // every window made the arithmetic come out right. The new rep carries
        // 0.228 m, inside this set's own 0.115-0.441 m spread.
        //
        // Their ROMs still run 0.115 m to 0.441 m on a Romanian deadlift, a
        // spread of nearly four to one within one set, and the velocity loss
        // the lifter reads is 74.2% where before this branch's ancestors they
        // read nothing at all. A wrong figure is not better than no figure by
        // default; what makes this the better outcome is that the set is no
        // longer indistinguishable from an unmeasured one, and the raw stream
        // was always recoverable either way.
        val rdl = SetAnalyzer.analyze(
            load("field-rdl-3010-10rep-s36-set05"),
            LiftDirection(StartPhase.ECCENTRIC),
            loadKg = 52.163122551154075,
        )
        assertEquals(11, rdl.reps.size, "reps resolved on a 10-rep set")
        assertEquals(
            listOf(0.441, 0.115, 0.325, 0.216, 0.272, 0.154, 0.228, 0.345, 0.411, 0.193, 0.139),
            rdl.reps.map { it.romM },
            "ROM per rep, metres -- the spread this count is built out of",
        )
        assertEquals<Double?>(74.2, rdl.velocityLossPct, "velocity loss now reported to the lifter")
        assertNull(rdl.tempoCompliance, "tempo compliance with no target declared")
        assertNull(rdl.detectionsAfterSetEndCue, "no cue track was passed, so nothing bounded the set")
        assertEquals(99.351, rdl.sampleRateHz, 1e-3, "measured sample rate, unmoved by the correction")
        // What a genuinely blank analysis publishes, kept because the case has
        // to stay pinned somewhere and this capture no longer provides it.
        val still = SetAnalyzer.analyze(load("field-still-0rep"), LiftDirection(StartPhase.ECCENTRIC))
        assertEquals(emptyList(), still.reps, "a sensor that did not move resolves nothing")
        assertNull(still.velocityLossPct, "velocity loss on a set with no reps")
        // The field that stops this being byte-identical to a manual set. It
        // is asserted, not narrated in the KDoc, so the claim cannot go stale
        // if the enumeration moves.
        assertEquals(
            NoRepsReason.NO_MOVEMENT,
            still.noRepsReason,
            "the reason a blank analysis publishes for a sensor that did not move",
        )
    }

    @Test
    fun `why the Romanian deadlift used to resolve nothing - three of four runs discarded`() {
        // MEASURED ON THE ANCHORED SERIES, before issue #94's runaway
        // correction: this is the diagnosis of the defect, so it has to be
        // taken on the input to the correction and not on its output. On the
        // shipped series the same capture now has 52 movement runs, none over
        // the cap, and resolves ten spans.
        //
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
            RepSegmenter.classifyRuns(anchoredSeries("field-rdl-3010-10rep-s36-set05"), sensorFrame)
                .filter { it.type != RunType.STILL }
        assertEquals(1, qualifying.size, "movement runs surviving demotion, before the correction")
        assertEquals(RunType.DOWN, qualifying.single().type, "the one surviving run's direction")
        // And after it, which is the differential this diagnosis predicts:
        // the three runaways become alternating strokes, so both directions
        // are present and pairing has something to do.
        val corrected =
            RepSegmenter.classifyRuns(series("field-rdl-3010-10rep-s36-set05"), sensorFrame)
                .filter { it.type != RunType.STILL }
        assertEquals(31, corrected.size, "movement runs surviving demotion, after the correction")
        assertEquals(
            setOf(RunType.DOWN, RunType.UP),
            corrected.map { it.type }.toSet(),
            "both directions present, which is what pairing needs",
        )
    }

    @Test
    fun `the seated overhead press reaches zero the other way - runs survive and none pair`() {
        // Anchored series again: this is the second way to reach zero and it
        // is a fact about the pre-correction runs.
        val runs = rawRuns("field-seated-ohp-2rep")
        assertEquals(7, runs.movement, "raw movement runs")
        assertEquals(1, runs.overDisplacementCap, "runs past the cap -- a minority here, three of four on the deadlift")
        val qualifying =
            RepSegmenter.classifyRuns(anchoredSeries("field-seated-ohp-2rep"), sensorFrame)
                .filter { it.type != RunType.STILL }
        assertEquals(3, qualifying.size, "movement runs surviving demotion")
        assertTrue(qualifying.all { it.type == RunType.UP }, "all three survivors are UP, so no DOWN opens a rep")
    }

    @Test
    fun `the neighbouring Romanian deadlift went from one rep of ten to eleven`() {
        // field-36 set 04, committed here. Same session, exercise, tempo, load
        // and hand count as set 05, four minutes earlier, and its integrator
        // runs away FURTHER -- a single run displacing 123.64 m against set
        // 05's 21.93 m -- yet one rep survives, so its analysis is not blank.
        //
        // This was the pin on what a blank-analysis diagnosis does NOT cover:
        // a set carrying five times the drift of the set beside it -- 123.64 m
        // against 21.93 m -- published a summary anyway, because a summary is
        // published whenever ONE rep resolves, and nothing in the export
        // distinguished that from a well-measured single.
        //
        // Issue #94's runaway correction took it from one rep to ten, against
        // ten performed, and issue #72's slow-eccentric fallback takes it to
        // ELEVEN. The run-structure figures below are on the ANCHORED series
        // and are the diagnosis, unchanged; the span count is on the shipped
        // one.
        //
        // THIS CAPTURE HAS NO CUE TRACK, so unlike its sister set 05 nothing
        // here can say whether the eleventh detection is the rep set 05's
        // marks show being recovered or a phantom. It carries 0.107 m, which
        // is 0.007 m over the minRomM floor, in a set whose other ten run
        // 0.103 m to 0.932 m. It is pinned as an unadjudicated cost of that
        // change and not as an improvement.
        val runs = rawRuns("field-rdl-3010-10rep-s36-set04")
        assertEquals(12, runs.movement, "raw movement runs")
        assertEquals(2, runs.overDisplacementCap, "runs displacing past the cap")
        assertEquals(123.64, runs.maxDisplacementM, 0.01, "the longest single run, metres")
        assertEquals(11, spans("field-rdl-3010-10rep-s36-set04", StartPhase.ECCENTRIC), "spans, 10 reps performed")
        val analysis = SetAnalyzer.analyze(
            load("field-rdl-3010-10rep-s36-set04"),
            LiftDirection(StartPhase.ECCENTRIC),
            loadKg = 52.163122551154075,
        )
        assertEquals(11, analysis.reps.size, "reps published to the lifter")
        // Ten reps and STILL no velocity loss, for a different reason: the
        // last rep resolved is the fastest of the set, so VelocityLoss
        // withholds the figure rather than publishing a negative drawdown.
        // Absence stays absence, and it is now absence for a stated reason
        // instead of for want of reps.
        assertNull(analysis.velocityLossPct, "the last rep is the fastest, so no velocity loss")
    }
}
