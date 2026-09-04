package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.StartPhase
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What an upper displacement bound on the LIVE path would remove, what it would
 * keep, and why [DspConfig.maxRunDisplacementM] is the only value it can take.
 * Issue 86.
 *
 * NOTHING IS FIXED HERE. Characterization pins for the change that follows.
 *
 * ## The corpus is twenty-one captures, and was fifteen
 *
 * Six captures committed since issue 86 landed are included here. Every figure
 * in this file is over those twenty-one, and figures quoted against the earlier
 * fifteen -- seven reps removed, 63 in family, 213 batch runs -- are that
 * corpus and not this one.
 *
 * WHY THIS KDOC NO LONGER CARRIES THE NUMBERS. Issue #87 moved the BATCH
 * reference this file classifies against, and issue #94's runaway correction
 * moved it again and much further -- it re-forms the runs the reference is
 * taken over. Three rounds of review found narrated copies of those figures
 * stale while the assertions in this same file were right, so every figure an
 * assertion here pins is now NAMED BY ITS ASSERTION rather than repeated in
 * prose. Figures nothing pins are marked UNPINNED where they survive.
 *
 * The live counter is untouched by both issues, structurally and not by
 * measurement: #87 keeps `isQuietSample` bit-identical, which `GyroGateTest`
 * asserts, and `RunawayDrift` runs inside `VelocityEstimator.estimate` on the
 * batch path only. No live rep total is quoted here.
 *
 * Committed captures that are NOT in this file's list are deliberately
 * outside it, so this file's series stays comparable with the figures
 * already published against the twenty-one; the corpus-wide phrasing in
 * this file means these twenty-one, not every file on the classpath.
 * `CuedRepCoverageTest.outsideCorpusTotals` names seven of them and is what
 * keeps its reconciliation arithmetic true;
 * field-rdl-3010-10rep-s36-set04 and field-ropedeadhang-hold20-s37-set11 are
 * two more. THE COUNT THAT USED TO STAND HERE IS DELETED RATHER THAN
 * REWORDED: this said NINE, which was already false by eight at the tree
 * that wrote it -- 38 captures were committed against a list of 21 -- and
 * issue #245's two captures would only have made it falser. Read the list.
 *
 * One of those outside is field-legpress-single-2011-8rep-s36-set07, landed for
 * issue #93. Its live TOTAL matches the count performed, eight against eight,
 * but by cancellation rather than by resolution: three of its eight cued reps
 * produce no counted rep, and three of its eight counted reps land in no cued
 * window, two of those displacing 1.056 m and 1.466 m -- all pinned in
 * [CuedRepCoverageTest]. Folding its totals in would move every figure here
 * without being a clean result for issue 94 or 86 either way, and is a task of
 * its own.
 *
 * ## The constant has never been calibrated anywhere
 *
 * `maxRunDisplacementM` is 2.0 m and has THREE consumers: `StreamingSetTracker`
 * on the live path, `RepSegmentation` on the batch path, and -- since issue
 * #94 -- `RunawayDrift.runaways`, which de-trends every run beyond it before
 * the segmenter classifies. In `RepSegmentation` it has never demoted anything
 * across the batch movement runs in this corpus; `the bracket the constant has
 * to sit inside (pre-fix)` asserts both that run population and the largest
 * run in it. Reusing the constant in the live path therefore meant importing a
 * number with no evidence behind it.
 *
 * Two measurements pinned below bracket it, each named by the assertion that
 * holds it rather than repeated as a figure:
 *
 *  - the least extreme artefact it catches on the live path, as a multiple of
 *    its own set's batch median rep ROM, and the largest removal in metres --
 *    both in `every counted rep the bound removes is far outside its own set
 *    (pre-fix)`;
 *  - the largest live rep displacement that is IN FAMILY with its own set, in
 *    `the bracket the constant has to sit inside (pre-fix)`. That figure is
 *    1.220 m at this commit, quoted here only because the ratio below is
 *    computed from it; it moved because #94 shifted the batch reference this
 *    file scores against, not because any live behaviour changed.
 *
 * So the admissible range is narrow: 2.0 m clears the largest legitimate rep
 * by 1.64x.
 *
 * ## And it cannot be tightened, which is why the tail is left uncut
 *
 * Lowering the bound as far as field-ohp-100hz-bursty's surviving runs -- 1.553,
 * 1.604 and 1.892 m, all three UNPINNED, quoted from a one-off measurement with
 * no assertion behind them -- would degrade the path WITH retroactive drift
 * correction in order to improve the one without it. Tightening therefore
 * requires splitting one constant into two, which is a new tunable rather than
 * a reused one.
 *
 * SINCE #94 THE BATCH FLOOR IS NOT INDEPENDENT EVIDENCE, and this section used
 * to argue as though it were. `RunawayDrift.corrected` iterates until no
 * same-sign run exceeds the cap, forming runs and measuring displacement
 * exactly as `RepSegmentation` does, so the largest batch run is bounded BY
 * this constant and falls with it. Lowering the cap de-trends more; it does
 * not demote a batch run. What remains binding is the LIVE floor alone: a
 * shared constant must clear the largest in-family live rep, and the margin
 * over it is the 1.64x above. The surviving out-of-family reps are a
 * consequence of that, not of conservatism.
 *
 * ## Scoring caveat, stated because the numbers here depend on it
 *
 * "In family" below means displacement within 2x above or one third below the
 * set's own BATCH median rep ROM. That reference is independent of the live
 * path and validates at set level -- its median lands inside the seated leg
 * curl's known 0.4-0.5 m rail on two of three captures and misses by 4 mm on
 * the third. But live displacement comes from an uncorrected series and batch
 * from a retroactively corrected one, and the live-to-batch scale ratio spans
 * 0.30x to 1.65x across the sets we most trust, so the verdict is unreliable
 * for MARGINAL reps in both directions. It is not pinned as a metric anywhere
 * and it should not be. It is used here only to separate artefacts that sit at
 * least 4.99x from their set median -- the multiple asserted in `every counted
 * rep the bound removes is far outside its own set (pre-fix)` -- which no
 * reference error of 1.65x can explain away. The upper end of that spread was
 * narrated here as 27x, which nothing pinned; it is deleted rather than
 * re-derived.
 */
class LiveCapCalibrationTest {
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

    /** Session 32 set 6's exported geometry block, as [SetEndWindowTest] reads it. */
    private val rearDeltFly = LiftDirection(
        startsWith = StartPhase.CONCENTRIC,
        concentricUp = true,
        sensorInverted = false,
        travelRatio = 1.0,
        plane = MovementPlane.VERTICAL,
        sensorOnStack = false,
    )

    private fun ecc() = LiftDirection(startsWith = StartPhase.ECCENTRIC)

    private fun con() = LiftDirection(startsWith = StartPhase.CONCENTRIC)

    private val corpus = listOf(
        Triple("field-ohp-rotating-8rep.csv", ecc(), 8),
        Triple("field-ohp-rotating-8rep-b.csv", ecc(), 8),
        Triple("field-bench-rotating-6rep-ok.csv", ecc(), 6),
        Triple("field-bench-rotating-6rep.csv", ecc(), 6),
        Triple("field-backsquat-10hz.csv", ecc(), 5),
        Triple("field-backsquat-10hz-set5.csv", ecc(), 0),
        Triple("field-still-0rep.csv", ecc(), 0),
        Triple("field-cablerow-static-8rep.csv", con(), 8),
        Triple("field-facepull-static-12rep.csv", con(), 12),
        Triple("field-pallof-static-12rep.csv", con(), 12),
        Triple("field-ohp-100hz-bursty.csv", con(), 8),
        Triple("field-seated-ohp-2rep.csv", con(), 2),
        Triple("field-legcurl-1030-12rep.csv", legCurl, 12),
        Triple("field-legcurl-1030-12rep-b.csv", legCurl, 12),
        Triple("field-legcurl-1030-12rep-c.csv", legCurl, 12),
        // The six committed since issue 94 was scoped, in the geometry the
        // batch pins in FieldDataRegressionTest and SetEndWindowTest were taken
        // with.
        Triple("field-backsquat-99hz-6rep.csv", ecc(), 6),
        Triple("field-rdl-3010-10rep.csv", ecc(), 10),
        Triple("field-legpress-2010-8rep.csv", ecc(), 8),
        Triple("field-legpress-single-2010-8rep.csv", con(), 8),
        Triple("field-legcurl-1030-10rep.csv", legCurl, 10),
        Triple("field-reardeltfly-s32-set06.csv", rearDeltFly, 12),
    )

    private data class LiveRun(val displacementM: Double, val durationS: Double, val type: Int)

    /**
     * Live movement runs rebuilt from the velocity the tracker publishes; run
     * state is private. Licensed by [LiveUnderCountAttributionTest], which
     * asserts the same reconstruction reproduces the tracker's own rep count on
     * every capture and has been mutation-checked.
     */
    private fun liveRuns(samples: List<ImuSample>, d: LiftDirection, c: DspConfig): List<LiveRun> {
        val tracker = StreamingSetTracker.forLift(d, c)
        val dt = 1.0 / VelocityEstimator.measureSampleRate(
            samples.size,
            (samples.last().timestampMs - samples.first().timestampMs) / 1000.0,
        )
        val runs = mutableListOf<LiveRun>()
        var type = 0
        var displacement = 0.0
        var n = 0
        samples.forEach { sample ->
            val v = tracker.feed(sample).velocityMps
            val k = when {
                v > c.pauseBandMps -> 1
                v < -c.pauseBandMps -> -1
                else -> 0
            }
            if (k == type) {
                if (k != 0) {
                    displacement += abs(v) * dt
                    n++
                }
            } else {
                if (type != 0) runs += LiveRun(displacement, n * dt, type)
                type = k
                displacement = if (k != 0) abs(v) * dt else 0.0
                n = if (k != 0) 1 else 0
            }
        }
        return runs
    }

    private fun qualifies(r: LiveRun, c: DspConfig) = r.displacementM >= c.minRomM && r.durationS >= c.minPhaseS

    /** [mode] 0 = today, 1 = suppress the REP, 2 = suppress the ARMING as well. */
    private fun countedReps(runs: List<LiveRun>, d: LiftDirection, c: DspConfig, mode: Int): List<LiveRun> {
        var pending = false
        val out = mutableListOf<LiveRun>()
        runs.filter { qualifies(it, c) }.forEach { r ->
            val overCap = r.displacementM > c.maxRunDisplacementM
            if (mode == 2 && overCap) return@forEach
            val concentric = (r.type == 1) == d.driveIsPositive
            if (d.startsWith == StartPhase.ECCENTRIC) {
                if (!concentric) {
                    pending = true
                } else if (pending) {
                    pending = false
                    if (!(mode >= 1 && overCap)) out += r
                }
            } else if (concentric && !(mode >= 1 && overCap)) {
                out += r
            }
        }
        return out
    }

    private fun batchMedianRom(file: String, d: LiftDirection, c: DspConfig): Double {
        val roms = SetAnalyzer.analyze(load(file), d, loadKg = 20.0, config = c).reps.map { it.romM }.sorted()
        return if (roms.isEmpty()) 0.0 else roms[roms.size / 2]
    }

    private fun outOfFamily(r: LiveRun, ref: Double): Boolean {
        if (ref <= 0.0) return false
        return r.displacementM > 2.0 * ref || r.displacementM < ref / 3.0
    }

    @Test
    fun `every counted rep the bound removes is far outside its own set (pre-fix)`() {
        val c = DspConfig()
        var removed = 0
        var removedOutOfFamily = 0
        var smallestRemovedRatio = Double.MAX_VALUE
        var largestRemovedM = 0.0
        corpus.forEach { (file, d, _) ->
            val runs = liveRuns(load(file), d, c)
            val ref = batchMedianRom(file, d, c)
            val today = countedReps(runs, d, c, 0)
            val capped = countedReps(runs, d, c, 1)
            val gone = today.filterNot { capped.contains(it) }
            removed += gone.size
            gone.forEach {
                if (outOfFamily(it, ref)) removedOutOfFamily++
                if (ref > 0.0) smallestRemovedRatio = minOf(smallestRemovedRatio, it.displacementM / ref)
                largestRemovedM = maxOf(largestRemovedM, it.displacementM)
            }
        }
        assertEquals(11, removed, "counted reps the bound removes")
        // Every one, not most. The least extreme is the multiple asserted
        // three lines below, and no reference error of at most 1.65x can
        // explain it away.
        assertEquals(11, removedOutOfFamily, "of those, how many are out of family with their own set")
        // Issue #94's runaway correction moves the BATCH reference this file
        // scores against; the live tracker is untouched by it. The figures
        // below therefore move without any live behaviour changing, which is
        // the standing hazard of scoring one path against the other.
        assertEquals(4.994, smallestRemovedRatio, 5e-3, "the least extreme removal, as a multiple of its set median")
        // On field-reardeltfly-s32-set06. The largest across the earlier
        // fifteen captures was 20.376 m.
        assertEquals(127.405, largestRemovedM, 5e-3, "the largest removal, metres")
    }

    @Test
    fun `the bound destroys no in-family rep (pre-fix)`() {
        // The check that killed the arming proposal on issue 94, applied here:
        // look at what a change KEEPS, not only at what it removes.
        val c = DspConfig()
        var inFamilyToday = 0
        var inFamilyCapped = 0
        corpus.forEach { (file, d, _) ->
            val runs = liveRuns(load(file), d, c)
            val ref = batchMedianRom(file, d, c)
            inFamilyToday += countedReps(runs, d, c, 0).count { !outOfFamily(it, ref) }
            inFamilyCapped += countedReps(runs, d, c, 1).count { !outOfFamily(it, ref) }
        }
        // Issue #94's runaway correction moves the BATCH reference this file
        // scores against; the live tracker is untouched by it. The figures
        // below therefore move without any live behaviour changing, which is
        // the standing hazard of scoring one path against the other.
        assertEquals(83, inFamilyToday, "in-family counted reps today")
        assertEquals(83, inFamilyCapped, "in-family counted reps with the bound applied")
    }

    @Test
    fun `the bracket the constant has to sit inside (pre-fix)`() {
        val c = DspConfig()
        var largestInFamilyLive = 0.0
        var largestBatchRun = 0.0
        var batchRuns = 0
        corpus.forEach { (file, d, _) ->
            val ref = batchMedianRom(file, d, c)
            countedReps(liveRuns(load(file), d, c), d, c, 0)
                .filterNot { outOfFamily(it, ref) }
                .forEach { largestInFamilyLive = maxOf(largestInFamilyLive, it.displacementM) }
            val series = VelocityEstimator.estimate(load(file), c, d.measuredPlane)
                .mappedToLifter(d.sensorToLifter)
            RepSegmenter.classifyRuns(series, c).filter { it.type != RunType.STILL }.forEach {
                batchRuns++
                largestBatchRun = maxOf(largestBatchRun, RepSegmenter.displacement(series, it.startIdx, it.endIdx))
            }
        }
        // The floor: anything below this destroys a rep that looks like a rep.
        // Issue #94's runaway correction moves the BATCH reference this file
        // scores against; the live tracker is untouched by it. The figures
        // below therefore move without any live behaviour changing, which is
        // the standing hazard of scoring one path against the other.
        assertEquals(1.220, largestInFamilyLive, 5e-3, "largest in-family live rep displacement, metres")
        // The batch run population and its largest member. This USED to be
        // read here as a second, binding floor -- "anything below this starts
        // demoting BATCH runs" -- and since issue #94 that reading is wrong.
        // RunawayDrift.corrected iterates until no same-sign run exceeds the
        // cap, forming runs and measuring displacement exactly as
        // RepSegmentation does, so the largest batch run is bounded BY the
        // constant and falls with it. Lowering the cap de-trends more; it
        // demotes nothing. These two numbers are characterization, not a
        // floor: 301 runs before the correction and 403 after it, because
        // every over-cap run is de-trended into the strokes inside it before
        // the segmenter counts runs at all.
        assertEquals(403, batchRuns, "batch movement runs across the corpus")
        assertEquals(1.982, largestBatchRun, 5e-3, "largest batch run displacement, metres")
        assertEquals(2.0, c.maxRunDisplacementM, "the value, barely above the live floor")
    }

    @Test
    fun `suppressing the rep and suppressing the arming cannot be told apart on this corpus`() {
        // MODE 2 is what ships: the over-cap run fails qualification outright,
        // so it neither counts a rep nor arms the next one. Mode 1 -- block the
        // count, leave the arming intact -- is the alternative, and its appeal
        // is that an over-cap eccentric cannot then take the following
        // concentric down with it.
        //
        // THIS CORPUS STILL CANNOT SEPARATE THEM, and it is no longer for the
        // reason it was. On the earlier fifteen captures the only over-cap DOWN
        // run anywhere was on field-legcurl-1030-12rep-c, which is
        // concentric-first, where a down run arms nothing -- so the two modes
        // agreed trivially. On twenty-one there are six, and FOUR of them are on
        // eccentric-first captures, where a down run does arm. The claim that
        // they are "all on concentric-first captures" is false and is withdrawn.
        // The two modes agree anyway, which is a stronger result than the one it
        // replaces: it now holds where the mechanism could have shown itself.
        //
        // The argument for the shipped choice is still structural rather than
        // measured: a run that travelled far enough to be rejected is not a
        // trustworthy phase boundary either, so letting it arm the next rep
        // would propagate a boundary this bound has just thrown out.
        //
        // An earlier report of this said the arming variant deletes a legitimate
        // rep and that it "fires once". That is WRONG and is corrected here: the
        // suppression event fires on captures where it costs nothing.
        val c = DspConfig()
        var overCapDownRuns = 0
        var overCapDownOnEccentricFirst = 0
        corpus.forEach { (file, d, _) ->
            val runs = liveRuns(load(file), d, c)
            assertEquals(
                countedReps(runs, d, c, 1).size,
                countedReps(runs, d, c, 2).size,
                "$file: suppressing the rep against suppressing the arming",
            )
            val down = runs.count {
                qualifies(it, c) && it.displacementM > c.maxRunDisplacementM && it.type == -1
            }
            overCapDownRuns += down
            if (d.startsWith == StartPhase.ECCENTRIC) overCapDownOnEccentricFirst += down
        }
        assertEquals(6, overCapDownRuns, "over-cap DOWN runs in the corpus")
        assertEquals(
            4,
            overCapDownOnEccentricFirst,
            "of those, ones on eccentric-first captures, where a down run arms the next rep",
        )
    }
}
