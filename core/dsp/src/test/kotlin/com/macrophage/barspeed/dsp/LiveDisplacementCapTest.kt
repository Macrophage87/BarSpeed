package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.StartPhase
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The live rep counter had no upper bound on how far a movement run may travel;
 * the batch analyzer has one. This pinned the consequence, and now pins the
 * repair: the runs are still produced, and none of them becomes a rep.
 *
 * RepSegmentation demotes a run displacing beyond [DspConfig.maxRunDisplacementM]
 * -- 2.0 m -- on the ground that no real barbell phase travels that far.
 * StreamingSetTracker qualified a run on three LOWER bounds and no upper one, so
 * a drift run became a rep the moment anything terminated it. See issue 86.
 *
 * ## What is measured
 *
 * Across the fifteen captures here the live path still produces TWELVE
 * qualified runs beyond 2.0 m, and none of them now increments the rep count.
 * SEVEN did before the bound was applied.
 *
 * The twelve split ELEVEN concentric to ONE eccentric. Of the eleven, nine
 * completed a rep and two arrived with nothing armed. So the three that never
 * reached a counter are two CONCENTRICS plus the single eccentric, and this
 * bound's effect on the ARMING path is nil on this corpus. An earlier version
 * of this comment said the three were all eccentrics "which only arm a rep";
 * that was wrong, and it was contradicted at the time by a green assertion in
 * [LiveCapCalibrationTest] counting exactly one over-cap down run.
 *
 * The largest of the twelve travels 32.558 m over 24.31 s, on
 * field-bench-rotating-6rep -- and it is one of the two that arrived with
 * nothing armed, so it never produced a rep and is NOT an instance of what
 * this bound removes. The largest run that does cost a rep travels 20.376 m,
 * pinned in [LiveCapCalibrationTest].
 *
 * Over the same fifteen the batch path demotes NOTHING: its 213 movement runs
 * top out at 1.982 m, so the shared constant has never once fired where it
 * originally lived.
 *
 * ## What the bound costs
 *
 * It takes the number the lifter watches FURTHER from the truth: live absolute
 * error over the fifteen goes 23 to 32 (pinned in [LiveUnderCountAttributionTest]),
 * worse on six captures and better on none -- that per-capture split is
 * UNPINNED, quoted from a one-off measurement. On the cable row the lifter goes from seeing 7 of 8 to seeing 5 of 8.
 * That under-count is issue 94 and it is not fixed here. The trade is
 * deliberate: an increment the lifter hears should correspond to something
 * that happened, and a count that lands nearer the truth by including a 20 m
 * run lands there by accident.
 *
 * ## Which variant ships
 *
 * The over-cap run fails qualification outright, so it neither counts a rep nor
 * arms the next one -- mode 2 in [LiveCapCalibrationTest]'s comparison. Mode 1,
 * blocking only the count and leaving the arming intact, is the alternative.
 * The two are count-identical on all fifteen captures, because the only
 * over-cap eccentric falls on a concentric-first capture where arming is never
 * consulted, so the corpus cannot separate them and the choice is structural.
 *
 * ## What this does NOT cover
 *
 * The same runs also hold a movement phase label for as long as they last --
 * 24.3 s on field-bench-rotating-6rep -- which is issue 95. A bound applied at
 * run END, which is what issue 86 is about, cannot help there: the label is
 * already wrong while the run is still in progress.
 */
class LiveDisplacementCapTest {
    private fun load(n: String) = ImuCsv.decode(
        javaClass.getResourceAsStream("/$n")!!.readBytes().decodeToString(),
    )

    private val legCurl = LiftDirection(
        startsWith = StartPhase.CONCENTRIC,
        concentricUp = false,
        sensorInverted = true,
        plane = MovementPlane.VERTICAL,
        sensorOnStack = true,
    )

    private fun ecc() = LiftDirection(startsWith = StartPhase.ECCENTRIC)

    private fun con() = LiftDirection(startsWith = StartPhase.CONCENTRIC)

    /** Every capture in the corpus, with the count the lifter performed. */
    private val corpus = listOf(
        Triple("field-ohp-rotating-8rep.csv", ecc(), 8),
        Triple("field-ohp-rotating-8rep-b.csv", ecc(), 8),
        Triple("field-bench-rotating-6rep-ok.csv", ecc(), 6),
        Triple("field-bench-rotating-6rep.csv", ecc(), 6),
        Triple("field-cablerow-static-8rep.csv", con(), 8),
        Triple("field-facepull-static-12rep.csv", con(), 12),
        Triple("field-pallof-static-12rep.csv", con(), 12),
        Triple("field-backsquat-10hz.csv", ecc(), 5),
        Triple("field-backsquat-10hz-set5.csv", ecc(), 0),
        Triple("field-ohp-100hz-bursty.csv", con(), 8),
        Triple("field-seated-ohp-2rep.csv", con(), 2),
        Triple("field-still-0rep.csv", ecc(), 0),
        Triple("field-legcurl-1030-12rep.csv", legCurl, 12),
        Triple("field-legcurl-1030-12rep-b.csv", legCurl, 12),
        Triple("field-legcurl-1030-12rep-c.csv", legCurl, 12),
    )

    private data class LiveRun(val displacementM: Double, val durationS: Double, val type: Int)

    /**
     * The live movement runs, rebuilt from the velocity StreamingSetTracker
     * PUBLISHES on every sample. Run displacement is private state, so this is
     * the only way to see it from outside. It is not a second implementation
     * under test: the first test below earns the right to use it by showing the
     * rebuilt runs reproduce the rep count of the tracker on all fifteen
     * captures.
     *
     * A run still in progress at the last sample is deliberately dropped. The
     * tracker qualifies a run only when the run TYPE changes, so a final
     * unterminated run is never counted; including it over-counts three
     * captures, which is how that detail was found.
     */
    private fun liveRuns(samples: List<ImuSample>, d: LiftDirection, c: DspConfig): Pair<List<LiveRun>, Int> {
        val tracker = StreamingSetTracker.forLift(d, c)
        val dt = 1.0 / VelocityEstimator.measureSampleRate(
            samples.size,
            (samples.last().timestampMs - samples.first().timestampMs) / 1000.0,
        )
        val runs = mutableListOf<LiveRun>()
        var type = 0
        var displacement = 0.0
        var count = 0
        var last = LiveSetState()
        samples.forEach { sample ->
            last = tracker.feed(sample)
            val v = last.velocityMps
            val t = when {
                v > c.pauseBandMps -> 1
                v < -c.pauseBandMps -> -1
                else -> 0
            }
            if (t == type) {
                if (t != 0) {
                    displacement += abs(v) * dt
                    count++
                }
            } else {
                if (type != 0) runs += LiveRun(displacement, count * dt, type)
                type = t
                displacement = if (t != 0) abs(v) * dt else 0.0
                count = if (t != 0) 1 else 0
            }
        }
        return runs to last.repCount
    }

    private fun qualifies(r: LiveRun, c: DspConfig) = r.displacementM >= c.minRomM && r.durationS >= c.minPhaseS

    /**
     * Replays the tracker pairing, returning reps and how many came from an
     * over-cap run.
     *
     * [capped] selects which tracker is modelled: false is the unbounded one,
     * true is the one this change produces. The census below needs BOTH -- ask
     * a capped replay how many of its reps came from over-cap runs and the
     * answer is zero however broken the tracker is, which is a check that
     * cannot fail.
     */
    private fun replay(runs: List<LiveRun>, d: LiftDirection, c: DspConfig, capped: Boolean): Pair<Int, Int> {
        var pending = false
        var reps = 0
        var fromOverCap = 0
        runs.filter { qualifies(it, c) && (!capped || it.displacementM <= c.maxRunDisplacementM) }.forEach { r ->
            val overCap = r.displacementM > c.maxRunDisplacementM
            val concentric = (r.type == 1) == d.driveIsPositive
            if (d.startsWith == StartPhase.ECCENTRIC) {
                if (!concentric) {
                    pending = true
                } else if (pending) {
                    pending = false
                    reps++
                    if (overCap) fromOverCap++
                }
            } else if (concentric) {
                reps++
                if (overCap) fromOverCap++
            }
        }
        return reps to fromOverCap
    }

    /**
     * The over-cap runs split by type, and how many over-cap CONCENTRICS arrive
     * with nothing armed. Returns (concentric, eccentric, concentricUnarmed).
     */
    private fun overCapSplit(runs: List<LiveRun>, d: LiftDirection, c: DspConfig): Triple<Int, Int, Int> {
        var pending = false
        var con = 0
        var ecc = 0
        var unarmed = 0
        runs.filter { qualifies(it, c) }.forEach { r ->
            val over = r.displacementM > c.maxRunDisplacementM
            val concentric = (r.type == 1) == d.driveIsPositive
            if (over) if (concentric) con++ else ecc++
            if (d.startsWith == StartPhase.ECCENTRIC) {
                if (!concentric) {
                    pending = true
                } else if (pending) {
                    pending = false
                } else if (over) {
                    unarmed++
                }
            }
        }
        return Triple(con, ecc, unarmed)
    }

    private fun batchRunDisplacements(file: String, d: LiftDirection, c: DspConfig): List<Double> {
        val s = VelocityEstimator.estimate(load(file), c, d.measuredPlane).mappedToLifter(d.sensorToLifter)
        return RepSegmenter.classifyRuns(s, c)
            .filter { it.type != RunType.STILL }
            .map { RepSegmenter.displacement(s, it.startIdx, it.endIdx) }
            .sorted()
    }

    @Test
    fun `the rebuilt live runs reproduce the rep count of the tracker`() {
        // The licence for everything else in this file. If this fails, the
        // rebuilt runs are not the runs of the tracker and no other assertion
        // here means anything.
        val c = DspConfig()
        corpus.forEach { (file, d, _) ->
            val (runs, liveReps) = liveRuns(load(file), d, c)
            val rebuilt = replay(runs, d, c, capped = true).first
            assertEquals(liveReps, rebuilt, "$file: rebuilt reps against the tracker count")
        }
    }

    @Test
    fun `census of what the bound would remove - seven reps from twelve runs`() {
        val c = DspConfig()
        var overCapRuns = 0
        var repsFromOverCap = 0
        var worstM = 0.0
        var worstS = 0.0
        corpus.forEach { (file, d, _) ->
            val (runs, _) = liveRuns(load(file), d, c)
            val over = runs.filter { qualifies(it, c) && it.displacementM > c.maxRunDisplacementM }
            overCapRuns += over.size
            repsFromOverCap += replay(runs, d, c, capped = false).second
            over.forEach {
                if (it.displacementM > worstM) {
                    worstM = it.displacementM
                    worstS = it.durationS
                }
            }
        }
        // The runs are still there. The bound does not stop them forming, it
        // stops them being counted, which is the whole of the change.
        assertEquals(12, overCapRuns, "qualified live runs beyond maxRunDisplacementM")
        // SEVEN, not twelve. The twelve are nine drives and three returns;
        // of the eleven, two arrive with nothing armed, so three never reach a
        // counter whatever this bound does. Earlier figures of twelve and of
        // eight were reported against this defect; both measured something real
        // that was not the count.
        //
        // Measured on the UNBOUNDED replay, so this is what the bound WOULD
        // remove, not a reading of the shipped tracker -- it is green both
        // before and after the fix and discriminates nothing on its own. It is
        // written that way on purpose: asking a BOUNDED replay how many of its
        // reps came from over-cap runs returns zero however broken the tracker
        // is, which is a check that cannot fail.
        //
        // `the rebuilt live runs reproduce the rep count of the tracker` is the
        // test that discriminates, by tying the bounded replay to the shipped
        // counter. Between the two, these nine no longer become reps.
        assertEquals(7, repsFromOverCap, "live reps whose completing run was over the cap")
        // The decomposition itself, pinned rather than left in prose: it was
        // reported wrong once, as "three eccentrics", and prose is not checkable.
        var con = 0
        var ecc = 0
        var unarmed = 0
        corpus.forEach { (file, d, _) ->
            val (runs, _) = liveRuns(load(file), d, c)
            val (a, b, u) = overCapSplit(runs, d, c)
            con += a
            ecc += b
            unarmed += u
        }
        assertEquals(9, con, "over-cap runs that are the drive")
        assertEquals(3, ecc, "over-cap runs that are the return")
        assertEquals(2, unarmed, "over-cap drives arriving with nothing armed")
        assertEquals(con + ecc, overCapRuns, "the split must account for every over-cap run")
        assertEquals(32.558, worstM, 5e-3, "worst over-cap run, metres")
        assertEquals(24.31, worstS, 5e-3, "worst over-cap run, seconds")
    }

    @Test
    fun `the batch cap has never fired on any capture in this corpus`() {
        // The cap exists and, on this evidence, has never done anything. That is
        // the argument against reusing its value in the live path.
        val c = DspConfig()
        var runs = 0
        var demoted = 0
        var largest = 0.0
        corpus.forEach { (file, d, _) ->
            val disp = batchRunDisplacements(file, d, c)
            runs += disp.size
            demoted += disp.count { it > c.maxRunDisplacementM }
            largest = maxOf(largest, disp.lastOrNull() ?: 0.0)
        }
        assertEquals(213, runs, "batch movement runs across the corpus")
        assertEquals(0, demoted, "batch runs demoted by maxRunDisplacementM")
        assertEquals(1.982, largest, 5e-3, "largest batch run displacement, metres")
        assertEquals(2.0, c.maxRunDisplacementM, "the cap none of them reached")
    }

    @Test
    fun `live and batch disagree about how far the same set travelled (pre-fix)`() {
        // The pair, per capture, so a change moving one path and not the other
        // is visible instead of silent. Median qualified-run displacement in
        // metres, live then batch.
        val c = DspConfig()
        val expected = mapOf(
            "field-ohp-rotating-8rep.csv" to (0.684 to 0.666),
            "field-ohp-rotating-8rep-b.csv" to (0.864 to 0.747),
            "field-bench-rotating-6rep-ok.csv" to (0.268 to 0.333),
            "field-bench-rotating-6rep.csv" to (0.825 to 0.338),
            "field-cablerow-static-8rep.csv" to (0.738 to 0.232),
            "field-facepull-static-12rep.csv" to (0.206 to 0.246),
            "field-pallof-static-12rep.csv" to (0.284 to 0.290),
            "field-backsquat-10hz.csv" to (0.861 to 0.556),
            "field-backsquat-10hz-set5.csv" to (0.753 to 0.458),
            "field-ohp-100hz-bursty.csv" to (1.604 to 0.503),
            "field-seated-ohp-2rep.csv" to (1.073 to 0.484),
            "field-still-0rep.csv" to (0.000 to 0.000),
            "field-legcurl-1030-12rep.csv" to (0.371 to 0.191),
            "field-legcurl-1030-12rep-b.csv" to (0.860 to 0.296),
            "field-legcurl-1030-12rep-c.csv" to (0.644 to 0.353),
        )
        corpus.forEach { (file, d, _) ->
            val (runs, _) = liveRuns(load(file), d, c)
            val live = runs.filter { qualifies(it, c) }.map { it.displacementM }.sorted()
            val batch = batchRunDisplacements(file, d, c)
            val (eLive, eBatch) = expected.getValue(file)
            assertEquals(eLive, if (live.isEmpty()) 0.0 else live[live.size / 2], 5e-3, "$file: live median")
            assertEquals(eBatch, if (batch.isEmpty()) 0.0 else batch[batch.size / 2], 5e-3, "$file: batch median")
        }
    }

    // ------------------------------------------------------------------
    // Two synthetic pins. The corpus above cannot reach either behaviour:
    // it holds one over-cap eccentric and it never varies DspConfig.
    // ------------------------------------------------------------------

    /**
     * An over-cap ECCENTRIC followed by an in-family CONCENTRIC. SyntheticSets
     * gives a rep one ROM for both phases, and it is production code, so this
     * splices the head of a 3.0 m rep (lead-in, eccentric, bottom pause: 4.5 s
     * at 100 Hz) onto a concentric-first 0.6 m rep.
     */
    private fun overCapEccentricThenNormalConcentric(): List<ImuSample> {
        val head = SyntheticSets.generate(
            listOf(SyntheticSets.RepSpec(eccS = 2.0, bottomPauseS = 1.0, conS = 2.0, topPauseS = 1.5, romM = 3.0)),
            leadInS = 1.5,
            leadOutS = 0.0,
            eccentricFirst = true,
        ).take(450)
        val tail = SyntheticSets.generate(
            listOf(SyntheticSets.RepSpec(eccS = 2.0, bottomPauseS = 1.0, conS = 1.0, topPauseS = 1.5, romM = 0.6)),
            leadInS = 0.0,
            leadOutS = 1.5,
            eccentricFirst = false,
        ).map { it.copy(timestampMs = it.timestampMs + 4500L) }
        return head + tail
    }

    private fun bigConcentric() = SyntheticSets.generate(
        listOf(SyntheticSets.RepSpec(eccS = 2.0, bottomPauseS = 1.0, conS = 2.0, topPauseS = 1.5, romM = 3.0)),
        eccentricFirst = false,
    )

    @Test
    fun `an over-cap eccentric arms nothing, so the concentric after it is no rep`() {
        // The eccentric half of the rule. Bounding only the concentric reds
        // nothing on the corpus -- every over-cap run there but one is
        // concentric -- so without this the eccentric half could be reverted
        // and CI would stay green.
        //
        // Measured runs: eccentric 3.020 m, then concentric 0.590 m, which is
        // an ordinary rep by displacement. Before the bound the eccentric armed
        // and the concentric completed a rep. After it, the eccentric does not
        // qualify, so nothing is armed and the concentric finds no pending
        // phase. Losing that concentric is the cost of mode 2 and this is the
        // only place in the suite where that cost is visible at all.
        val tracker = StreamingSetTracker(StartPhase.ECCENTRIC)
        var last = LiveSetState()
        overCapEccentricThenNormalConcentric().forEach { last = tracker.feed(it) }
        assertEquals(0, last.repCount, "over-cap eccentric must not arm the concentric behind it")
    }

    @Test
    fun `the live bound reads the config, not a hard-coded 2 point 0`() {
        // Nothing else in :core:dsp constructs a non-default DspConfig, so
        // replacing config.maxRunDisplacementM with the literal 2.0 passes the
        // whole suite -- and the central claim of this change is that the live
        // path bounds runs by the SAME constant the batch path does.
        //
        // The run is 3.034 m: over the default bound, under a 4.0 m one.
        val samples = bigConcentric()
        var last = LiveSetState()
        val strict = StreamingSetTracker(StartPhase.CONCENTRIC)
        samples.forEach { last = strict.feed(it) }
        assertEquals(0, last.repCount, "3.0 m run must not count under the default 2.0 m bound")

        // Green both before and after the bound exists, by construction: it
        // pins WHERE the number comes from, not whether the bound is applied.
        val loose = StreamingSetTracker(StartPhase.CONCENTRIC, DspConfig(maxRunDisplacementM = 4.0))
        var lastLoose = LiveSetState()
        samples.forEach { lastLoose = loose.feed(it) }
        assertEquals(1, lastLoose.repCount, "the same run must count when the config allows 4.0 m")
    }

    /**
     * Samples built from a designed velocity profile: a = dv/dt, azG = 1 + a/g.
     * SyntheticSets cannot express this one -- every phase it emits returns to
     * rest through the dead band, and what is needed here is a run that ends
     * WITHOUT passing through it.
     */
    private fun fromVelocity(v: List<Double>, dt: Double = 0.01): List<ImuSample> {
        val g = 9.80665
        return v.indices.map { i ->
            val a = if (i + 1 < v.size) (v[i + 1] - v[i]) / dt else 0.0
            ImuSample(
                timestampMs = (i * dt * 1000).toLong(),
                axG = 0.0,
                ayG = 0.0,
                azG = 1.0 + a / g,
                wxDps = 0.0,
                wyDps = 0.0,
                wzDps = 0.0,
                rollDeg = 0.0,
                pitchDeg = 0.0,
                yawDeg = 0.0,
            )
        }
    }

    @Test
    fun `an over-cap run is bounded even when the next run starts without a pause`() {
        // The bound is read at a run END, where `runType` is the run being
        // judged and `type` is the run about to start. Keying it on `type`
        // instead -- exempting a run whose successor is another movement run --
        // passes every other test in this module, because no over-cap run in
        // the corpus is followed directly by a movement run. Measured: 14 such
        // direct sign flips across 307 run ends, none of them over-cap.
        //
        // So this constructs the missing combination: a 2.713 m drive that
        // reverses to -1 m/s in a single sample, giving no dead-band sample
        // between the two runs. The reversal survives the 8 Hz low-pass.
        val v = mutableListOf<Double>()
        repeat(200) { v += 0.0 }
        repeat(20) { v += 1.0 * (it + 1) / 20.0 }
        repeat(280) { v += 1.0 }
        v += -1.0
        repeat(60) { v += -1.0 }
        repeat(20) { v += -1.0 * (20 - it - 1) / 20.0 }
        repeat(300) { v += 0.0 }

        val tracker = StreamingSetTracker(StartPhase.CONCENTRIC)
        var last = LiveSetState()
        fromVelocity(v).forEach { last = tracker.feed(it) }
        assertEquals(0, last.repCount, "an over-cap drive must not count because its successor starts at once")
    }
}
