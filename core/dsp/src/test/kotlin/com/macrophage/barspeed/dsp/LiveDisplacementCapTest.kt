package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.StartPhase
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The live rep counter has no upper bound on how far a movement run may travel;
 * the batch analyzer has one. This pins the consequence. IT DOES NOT FIX IT,
 * and the reason is the interesting part.
 *
 * RepSegmentation demotes a run displacing beyond [DspConfig.maxRunDisplacementM]
 * -- 2.0 m -- on the ground that no real barbell phase travels that far.
 * StreamingSetTracker qualifies a run on three LOWER bounds and no upper one, so
 * a drift run becomes a rep the moment anything terminates it. See issue 86.
 *
 * ## What is measured
 *
 * Across the fifteen captures here the live path produces TWELVE qualified runs
 * beyond 2.0 m, of which NINE go on to increment the rep count. The worst
 * travels 32.6 m over 24.3 s. Over the same fifteen the batch path demotes
 * NOTHING: its 213 movement runs top out at 1.982 m, so the cap has never once
 * fired where it actually lives.
 *
 * ## Why the obvious fix is not applied
 *
 * Three findings, each of which alone would stop the one-line change.
 *
 * FIRST, the constant is inert where it lives. 2.0 m has never demoted a run on
 * this corpus, so reusing it in the live path would promote an uncalibrated
 * number to a place where it WOULD fire.
 *
 * SECOND, the same number does not mean the same thing in the two places. Batch
 * measures a completed run after retroactive drift correction; live measures an
 * uncorrected series carrying only a causal step offset. On the same captures
 * the live median run displacement runs 1.5 to 3.5 times the batch median, and
 * the tail is far worse than any constant factor -- 32.6 m live against 0.350 m
 * batch on field-bench-rotating-6rep. A 2.0 m live bound is roughly a 0.6 to
 * 1.3 m batch bound. The per-capture pair below makes that divergence visible
 * rather than a claim.
 *
 * THIRD, and this decides it: applying the bound today takes the number the
 * lifter watches FURTHER from the truth. All nine phantom reps are compensating
 * for an under-count -- not most, all nine -- so suppressing them moves live
 * absolute error from 23 to 32 across the fifteen captures, worse on six and
 * better on none. On the cable row the lifter would go from seeing 7 of 8 to
 * seeing 5 of 8. That under-count is issue 94, and it is the blocker.
 *
 * That is a sharper case than one this corpus has already ruled on.
 * FieldDataRegressionTest pins the cable row resolving 8 for 8 by cancelling
 * misses against drift as a DEFECT rather than a success -- a wrong mechanism
 * reaching a right number. Here the mechanism is equally wrong, but removing it
 * would actively move a number the lifter reads mid-set in the WRONG direction.
 * Same logic, harder case, and it argues for the under-count first.
 *
 * ## What right looks like
 *
 * A bound on DURATION, not displacement. Live displacement is the integral of a
 * series the live path cannot correct; live duration is the sample clock and is
 * untouched by drift, so it is the quantity worth bounding. Measured over the
 * 176 QUALIFIED LIVE runs -- a different population from the 213 batch runs
 * above, and the two are easy to confuse: plausible ones have a median of
 * 1.98 s and a maximum of 5.94 s, and the twelve over-cap runs have a median
 * of 6.96 s.
 *
 * The value should be the anchor-starvation interval, 6 s. A run outlasting the
 * point at which the drift correction itself declares residual drift has outrun
 * the rejection band is drift by the declaration of the pipeline, which is an
 * argument rather than a fit. On this corpus it catches 7 of the 12 and none of
 * the 164 plausible runs.
 *
 * It is NOT applied yet because 5.94 against 6.00 is a one per cent margin,
 * fitted to captures containing no eccentric slower than 3 s. Issue 47 asks for
 * 4010 and 6010 sets, and a 6 s prescribed eccentric will exceed a 6 s run
 * bound. The falsifier is already written and already booked: any qualified run
 * on that capture exceeding the bound while being a real phase.
 *
 * ## One hazard for whoever fixes it
 *
 * Suppress the REP, not the arming. An over-cap eccentric that is simply
 * discarded takes with it the arming for the following concentric, and the next
 * legitimate rep is lost with it. Measured: that fires once on this corpus.
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
        val tracker = StreamingSetTracker(d.startsWith, c, velocityScale = d.sensorToLifter)
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

    /** Replays the tracker pairing, returning reps and how many came from an over-cap run. */
    private fun replay(runs: List<LiveRun>, d: LiftDirection, c: DspConfig): Pair<Int, Int> {
        var pending = false
        var reps = 0
        var fromOverCap = 0
        runs.filter { qualifies(it, c) }.forEach { r ->
            val overCap = r.displacementM > c.maxRunDisplacementM
            val concentric = r.type == 1
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
            assertEquals(liveReps, replay(runs, d, c).first, "$file: rebuilt reps against the tracker count")
        }
    }

    @Test
    fun `the live counter builds reps from runs the batch path would discard (pre-fix)`() {
        val c = DspConfig()
        var overCapRuns = 0
        var repsFromOverCap = 0
        var worstM = 0.0
        var worstS = 0.0
        corpus.forEach { (file, d, _) ->
            val (runs, _) = liveRuns(load(file), d, c)
            val over = runs.filter { qualifies(it, c) && it.displacementM > c.maxRunDisplacementM }
            overCapRuns += over.size
            repsFromOverCap += replay(runs, d, c).second
            over.forEach {
                if (it.displacementM > worstM) {
                    worstM = it.displacementM
                    worstS = it.durationS
                }
            }
        }
        assertEquals(12, overCapRuns, "qualified live runs beyond maxRunDisplacementM")
        // NINE, not twelve: an over-cap ECCENTRIC only arms a rep, it does not
        // count one, so three of the twelve never reach the counter at all.
        // Earlier figures of twelve and of eight were reported against this
        // defect; both measured something real that was not the count.
        assertEquals(9, repsFromOverCap, "live reps whose completing run was over the cap")
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
}
