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
 * in this file is over all twenty-one, and figures quoted against the earlier
 * fifteen -- seven reps removed, 63 in family, 213 batch runs -- are that
 * corpus and not this one.
 *
 * A TWENTY-SECOND capture is committed and is deliberately NOT in this file's
 * list: field-legpress-single-2011-8rep-s36-set07, landed for issue #93. It is
 * excluded so this file's series stays comparable with the figures already
 * published against the twenty-one, and because it is the corpus's first
 * capture on which the live counter resolves every rep performed, which would
 * move every total here without being evidence about anything issue 94 or 86
 * measured. Folding it in is a task of its own; the corpus-wide phrasing in
 * this file means these twenty-one, not every file on the classpath.
 * `CuedRepCoverageTest.outsideCorpusTotals` names the same exclusion and is
 * what keeps its reconciliation arithmetic true.
 *
 * ## The constant has never been calibrated anywhere
 *
 * `maxRunDisplacementM` is 2.0 m and has exactly one consumer, `RepSegmentation`,
 * where across the 293 batch movement runs in this corpus it has NEVER demoted
 * anything -- the largest batch run is 1.982 m, and the eighty runs the six new
 * captures added did not raise it. Reusing it in the live path therefore meant
 * importing a number with no evidence behind it.
 *
 * Two measurements pinned below bracket it:
 *
 *  - the least extreme artefact it catches on the live path sits at 3.34x its
 *    own set's batch median rep ROM, and the largest carries 127.405 m;
 *  - the largest live rep displacement that is IN FAMILY with its own set is
 *    1.675 m, on field-ohp-rotating-8rep-b.
 *
 * So the admissible range is narrow: 2.0 m clears the largest legitimate rep by
 * only 1.19x, not by the 2x an earlier reading of this claimed. That earlier
 * figure, 1.058 m, was the largest in-family rep among the three sets used to
 * derive the scoring bound rather than across the whole corpus, and it is
 * corrected here.
 *
 * ## And it cannot be tightened, which is why the tail is left uncut
 *
 * Lowering the bound as far as field-ohp-100hz-bursty's surviving runs -- 1.553,
 * 1.604 and 1.892 m, all three UNPINNED, quoted from a one-off measurement with
 * no assertion behind them -- would demote batch runs, because the largest batch
 * run is 1.982 m (pinned below). That degrades the path WITH retroactive drift
 * correction in order to improve the one without it. Tightening therefore
 * requires splitting one constant into two, which is a new tunable rather than
 * a reused one.
 *
 * Taken together the two floors leave almost no room at all. A shared constant
 * must clear 1.675 m to keep every in-family live rep and 1.982 m to demote no
 * batch run, so 2.0 m is very nearly the SMALLEST admissible value rather than
 * a comfortable one. The surviving out-of-family reps are a consequence of that,
 * not of conservatism.
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
 * and it should not be. It is used here only to separate artefacts that sit
 * 3.3x to 27x from their set median, which no reference error of 1.65x can
 * explain away.
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
        // Every one, not most. The least extreme is 3.34x its own set median,
        // which no reference error of at most 1.65x can explain away.
        assertEquals(11, removedOutOfFamily, "of those, how many are out of family with their own set")
        assertEquals(3.338, smallestRemovedRatio, 5e-3, "the least extreme removal, as a multiple of its set median")
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
        assertEquals(85, inFamilyToday, "in-family counted reps today")
        assertEquals(85, inFamilyCapped, "in-family counted reps with the bound applied")
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
        assertEquals(1.675, largestInFamilyLive, 5e-3, "largest in-family live rep displacement, metres")
        // The second floor, and the binding one: anything below this starts
        // demoting BATCH runs, because the constant is shared and the batch path
        // is the trusted one. 2.0 m clears it by 18 mm.
        assertEquals(293, batchRuns, "batch movement runs across the corpus")
        assertEquals(1.982, largestBatchRun, 5e-3, "largest batch run displacement, metres")
        assertEquals(2.0, c.maxRunDisplacementM, "the value, barely above both floors")
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
