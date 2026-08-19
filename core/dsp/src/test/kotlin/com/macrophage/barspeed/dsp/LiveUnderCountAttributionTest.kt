package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.StartPhase
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * WHY the live rep counter misses reps, decomposed. Issue 94.
 *
 * The counter the lifter watches during a set reports 81 reps across this
 * corpus against 111 the lifter performed (both pinned below, in `the corpus
 * totals`). This pins what that number is made of, because it is made of at
 * least three different things and issue 94 was filed describing one.
 *
 * Those two figures moved when the displacement bound of issue 86 landed in
 * the commit after this one: live was 90 and absolute error 23 before it. The
 * prose here was NOT updated with the assertions the first time and shipped
 * four stale numbers; they are corrected below and each now cites the
 * assertion that holds it.
 *
 * NOTHING IS FIXED HERE. These are characterization pins.
 *
 * ## The figures, and a correction to the ones issue 94 was filed with
 *
 * Issue 94 states 88 live and a batch total of 111. Both are arithmetic errors
 * in the issue body: its own per-capture table sums to 90 and to 98. Measured
 * at the SHA that issue names and again at this one, live and batch were
 * IDENTICAL -- nothing regressed and nothing was recovered between them, the
 * totals were added up wrong. Absolute live error, 23, was right AT THAT TIME.
 * It is 32 here, pinned at `assertEquals(32, absoluteError)`, because the
 * bound removes reps; that is the intended cost of issue 86, not a regression.
 *
 * ## Three mechanisms, not one
 *
 * ARMING ASYMMETRY accounts for part of the eccentric-first loss and none of
 * the concentric-first loss. onQualifiedRun requires a qualified down run
 * before any up run counts, so a qualified drive arriving unarmed is discarded
 * in silence. FIVE times, all on eccentric-first captures, by construction,
 * against an eccentric-first net deficit of 14 -- so roughly a third of that
 * half, not the majority an earlier version of this comment claimed. Both are
 * pinned in `arming asymmetry` below. Before the bound the figures were seven
 * of thirteen.
 *
 * A SECOND, UNIDENTIFIED LOSS costs the concentric-first captures 16 reps with
 * no arming involved -- pinned below, and doubled from 8 by the bound, which
 * makes this the LARGER of the two strands rather than the smaller. On the
 * three captures with a cue track, every rep the lifter performed produced a
 * qualified movement run in its window, so the loss is downstream of run
 * formation AND downstream of the three lower bounds.
 *
 * A PHANTOM: field-backsquat-10hz-set5 reports one rep against a performed
 * count of zero, from a run well inside maxRunDisplacementM. Issue 86 is about
 * reps built from runs BEYOND that cap, so its bound would not remove this one.
 * Different mechanism, and the two should not be assumed to share a fix.
 *
 * ## What is NOT the mechanism, established rather than assumed
 *
 * Issue 96 predicts that on a drive-down exercise the opening stroke is
 * structurally dropped from pairing. Whatever that argument establishes about
 * the batch path, it does not describe the live loss here: the opening cued rep
 * is COUNTED on all three leg-curl captures, and the window that goes empty is
 * interior and differs between them.
 *
 * The first attempt at that check assigned reps to cue windows by the END of
 * the completing run and appeared to show alternating double- and
 * under-counting. That was an artefact of reps completing near a window
 * boundary. Assigning by the run START and by its MIDPOINT agree with each
 * other and disagree with it, so the pin below uses the stable rule. The
 * assignment was tested rather than trusted.
 *
 * Separately, and confirmed by reading the constructor: StreamingSetTracker
 * takes startsWith, config, a sample rate and velocityScale. It never sees
 * concentricUp or LiftDirection at all, so it cannot be told which way the
 * drive moves and treats an UP run as the drive unconditionally. On a leg curl
 * the drive goes down, so the counter completes its reps on the RETURN stroke.
 * The count survives by symmetry -- one return per rep -- but repMeanVelocities
 * and repPeakVelocities are taken from the completing run, so what reaches the
 * screen as the rep is the return. That is a wrong-data consequence distinct
 * from the count, and it is not what costs the missing rep.
 */
class LiveUnderCountAttributionTest {
    private fun resource(n: String) = javaClass.getResourceAsStream("/" + n)!!.readBytes().decodeToString()

    private fun load(n: String) = ImuCsv.decode(resource(n))

    private fun cues(n: String): List<Pair<Long, String>> = resource(n).lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("timestamp_ms") }
        .map {
            val f = it.split(',')
            f[0].toLong() to f.drop(1).joinToString(",")
        }
        .toList()

    private val legCurl = LiftDirection(
        startsWith = StartPhase.CONCENTRIC,
        concentricUp = false,
        sensorInverted = true,
        plane = MovementPlane.VERTICAL,
        sensorOnStack = true,
    )

    private fun ecc() = LiftDirection(startsWith = StartPhase.ECCENTRIC)

    private fun con() = LiftDirection(startsWith = StartPhase.CONCENTRIC)

    /** Capture, direction, and the count the lifter performed. */
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
    )

    private data class LiveRun(
        val displacementM: Double,
        val durationS: Double,
        val type: Int,
        val startS: Double,
        val endS: Double,
    )

    /**
     * Live movement runs rebuilt from the velocity the tracker publishes. Run
     * state is private, so this is the only way to see it. The first test below
     * is its licence, and it has been mutation-checked: keeping the trailing
     * in-progress run, widening the dead band and dropping the minRomM bound
     * each red it.
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
        var n = 0
        var startS = 0.0
        var t = 0.0
        var last = LiveSetState()
        samples.forEach { sample ->
            last = tracker.feed(sample)
            val v = last.velocityMps
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
                if (type != 0) runs += LiveRun(displacement, n * dt, type, startS, t)
                type = k
                displacement = if (k != 0) abs(v) * dt else 0.0
                n = if (k != 0) 1 else 0
                startS = t
            }
            t += dt
        }
        return runs to last.repCount
    }

    private fun qualifies(r: LiveRun, c: DspConfig) = r.displacementM >= c.minRomM && r.durationS >= c.minPhaseS

    /** Replays the pairing, returning reps counted and drives discarded for want of an arm. */
    private fun replay(runs: List<LiveRun>, d: LiftDirection, c: DspConfig): Pair<Int, Int> {
        var pending = false
        var reps = 0
        var armingDrops = 0
        runs.filter { qualifies(it, c) && it.displacementM <= c.maxRunDisplacementM }.forEach { r ->
            val concentric = r.type == 1
            if (d.startsWith == StartPhase.ECCENTRIC) {
                if (!concentric) {
                    pending = true
                } else if (pending) {
                    pending = false
                    reps++
                } else {
                    armingDrops++
                }
            } else if (concentric) {
                reps++
            }
        }
        return reps to armingDrops
    }

    @Test
    fun `the rebuilt live runs reproduce the rep count of the tracker`() {
        val c = DspConfig()
        corpus.forEach { (file, d, _) ->
            val (runs, live) = liveRuns(load(file), d, c)
            assertEquals(live, replay(runs, d, c).first, file + ": rebuilt reps against the tracker count")
        }
    }

    @Test
    fun `the corpus totals, corrected against the ones issue 94 was filed with (pre-fix)`() {
        val c = DspConfig()
        var performed = 0
        var live = 0
        var batch = 0
        var absoluteError = 0
        corpus.forEach { (file, d, p) ->
            val samples = load(file)
            val (_, l) = liveRuns(samples, d, c)
            performed += p
            live += l
            batch += SetAnalyzer.analyze(samples, d, loadKg = 20.0, config = c).reps.size
            absoluteError += abs(l - p)
        }
        assertEquals(111, performed, "reps the lifter performed")
        // 90, not the 88 issue 94 states. Its own table sums to 90; the total
        // row was added up wrong, and the counts are identical at the SHA that
        // issue names.
        assertEquals(81, live, "reps the live counter reports")
        // 98, not the 111 issue 94 states, and the same arithmetic slip. Batch
        // matching the performed total exactly was never true.
        assertEquals(98, batch, "reps the batch analyzer reports")
        assertEquals(32, absoluteError, "absolute live error")
        assertEquals(30, performed - live, "net live deficit")
    }

    @Test
    fun `arming asymmetry costs eccentric-first lifts part of their deficit and concentric-first none (pre-fix)`() {
        val c = DspConfig()
        var eccPerformed = 0
        var eccLive = 0
        var eccArmingDrops = 0
        var conPerformed = 0
        var conLive = 0
        var conArmingDrops = 0
        corpus.forEach { (file, d, p) ->
            val (runs, live) = liveRuns(load(file), d, c)
            val drops = replay(runs, d, c).second
            if (d.startsWith == StartPhase.ECCENTRIC) {
                eccPerformed += p
                eccLive += live
                eccArmingDrops += drops
            } else {
                conPerformed += p
                conLive += live
                conArmingDrops += drops
            }
        }
        assertEquals(14, eccPerformed - eccLive, "net deficit on eccentric-first captures")
        assertEquals(5, eccArmingDrops, "drives discarded for want of a qualified eccentric before them")
        assertEquals(16, conPerformed - conLive, "net deficit on concentric-first captures")
        // Zero by construction rather than by luck: the concentric-first branch
        // of onQualifiedRun has no arming state that can be missing.
        assertEquals(0, conArmingDrops, "arming drops on concentric-first captures")
    }

    @Test
    fun `every performed rep produced a qualified run, so the loss is downstream of both (pre-fix)`() {
        // The class a table built by iterating RUNS cannot see: a rep with no
        // run at all. Counted from the cue track instead, which marks the reps
        // the lifter actually performed. It is empty here.
        val c = DspConfig()
        var cued = 0
        var withQualifiedRun = 0
        var withOnlyRejectedRuns = 0
        var withNoRunAtAll = 0
        listOf("field-legcurl-1030-12rep", "field-legcurl-1030-12rep-b", "field-legcurl-1030-12rep-c")
            .forEach { name ->
                val samples = load(name + ".csv")
                val (runs, _) = liveRuns(samples, legCurl, c)
                val t0 = samples.first().timestampMs
                val downs = cues(name + "-cues.csv").filter { it.second == "Down" }
                    .map { (it.first - t0) / 1000.0 }
                downs.forEachIndexed { i, a ->
                    val b = if (i + 1 < downs.size) downs[i + 1] else a + 6.0
                    cued++
                    val inWindow = runs.filter { it.endS > a && it.startS < b }
                    when {
                        inWindow.any { qualifies(it, c) } -> withQualifiedRun++
                        inWindow.isNotEmpty() -> withOnlyRejectedRuns++
                        else -> withNoRunAtAll++
                    }
                }
            }
        assertEquals(36, cued, "cued reps across the three leg-curl captures")
        assertEquals(36, withQualifiedRun, "cued reps whose window contains a qualified run")
        assertEquals(0, withOnlyRejectedRuns, "cued reps whose runs were all rejected by a bound")
        assertEquals(0, withNoRunAtAll, "cued reps that produced no run at all")
    }

    @Test
    fun `the rep the live counter loses is not the opening one (pre-fix)`() {
        // Issue 96 predicts the opening stroke of a drive-down exercise is
        // structurally dropped. That does not describe this: the FIRST cued rep
        // is counted on all three captures, and the window that goes empty is
        // interior. See the class KDoc for why the assignment rule below is the
        // run START rather than its end.
        val c = DspConfig()
        listOf("field-legcurl-1030-12rep", "field-legcurl-1030-12rep-b", "field-legcurl-1030-12rep-c")
            .forEach { name ->
                val samples = load(name + ".csv")
                val (runs, _) = liveRuns(samples, legCurl, c)
                val t0 = samples.first().timestampMs
                val downs = cues(name + "-cues.csv").filter { it.second == "Down" }
                    .map { (it.first - t0) / 1000.0 }
                val counted = IntArray(downs.size)
                runs.filter { qualifies(it, c) && it.type == 1 }.forEach { r ->
                    downs.indices.firstOrNull { i ->
                        val a = downs[i]
                        val b = if (i + 1 < downs.size) downs[i + 1] else a + 6.0
                        r.startS >= a && r.startS < b
                    }?.let { counted[it]++ }
                }
                assertEquals(1, counted.first(), name + ": reps counted in the OPENING cued window")
                assertEquals(true, counted.drop(1).any { it == 0 }, name + ": an interior window goes empty")
            }
    }

    @Test
    fun `the one capture that over-counts does not do so through an over-cap run (pre-fix)`() {
        // field-backsquat-10hz-set5 reports a rep across two quiet minutes on
        // the rack. Issue 86 is about reps built from runs beyond
        // maxRunDisplacementM; this one is not, so that bound would not remove
        // it and the two should not be assumed to share a fix.
        val c = DspConfig()
        val (runs, live) = liveRuns(load("field-backsquat-10hz-set5.csv"), ecc(), c)
        assertEquals(1, live, "live reps on a capture the lifter performed none in")
        assertEquals(
            0,
            runs.count { qualifies(it, c) && it.displacementM > c.maxRunDisplacementM },
            "qualified runs beyond the batch displacement cap",
        )
    }
}
