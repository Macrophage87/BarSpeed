package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.StartPhase
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every rep the live counter reports, checked against the rep the metronome
 * called for it. The first per-rep ground truth this repository has had on
 * eccentric-first barbell work.
 *
 * ## Why this exists instead of an in-family metric
 *
 * The plan was a metric separating real counted reps from artefact counted reps
 * by displacement, judged against a reference. It is not built, because there
 * is nothing left to separate: of 37 counted reps across the seven cue-tracked
 * captures, ALL 37 fall inside a rep the metronome called. Issue 86 removed
 * most of the artefact population it was designed for -- seven reps built from
 * runs of 3.3x to 27x their set median -- and a bound fitted to the one
 * survivor would be fitted to noise.
 *
 * ## What this does NOT say
 *
 * It does not say the counter no longer invents reps. Seven of fifteen captures
 * carry a cue track and eight do not, and the previously identified survivors
 * are in the second group -- field-ohp-100hz-bursty keeps three runs at 1.553,
 * 1.604 and 1.892 m against a 0.766 m median, all UNPINNED figures quoted in
 * [LiveCapCalibrationTest]. Nothing here disproves them. The claim is bounded to
 * where truth exists and the boundary is pinned below, so 43-of-44 cannot be
 * read as a corpus-wide property.
 *
 * ## A cue is an instruction, not a measurement
 *
 * See [CueTrack]. It records what was called and when, never what the bar did.
 * That is load-bearing here rather than decoration: an earlier pass ended each
 * set's last window at its `Done` cue and reported four counted reps as
 * uncalled. Three were real reps finishing 502, 935 and 716 ms AFTER the
 * metronome's final call, because the lifter completes the last rep after the
 * last cue rather than with it. Windows are one median cue cycle wide,
 * uniformly, including the last.
 *
 * ## The width comes from each track, never from a constant
 *
 * These fixtures were paced with the announcement beat issue 106 removed, so
 * their cycle is 5.006 s where a capture taken today is 4.005 s. A hard-coded
 * width would mis-window the first new capture; the median gap between
 * consecutive `Down` cues is taken from the track being read.
 */
class CuedRepCoverageTest {
    private data class CountedRep(val displacementM: Double, val endMs: Long)

    private fun load(n: String): List<ImuSample> = ImuCsv.decode(
        javaClass.getResourceAsStream("/$n.csv")!!.readBytes().decodeToString(),
    )

    private val legCurl = LiftDirection(
        startsWith = StartPhase.CONCENTRIC,
        concentricUp = false,
        sensorInverted = true,
        plane = MovementPlane.VERTICAL,
        sensorOnStack = true,
    )

    private val eccFirst = LiftDirection(startsWith = StartPhase.ECCENTRIC)

    /** The seven captures carrying a cue track, with the count the lifter performed. */
    private val cueTracked = listOf(
        Triple("field-ohp-rotating-8rep", eccFirst, 8),
        Triple("field-ohp-rotating-8rep-b", eccFirst, 8),
        Triple("field-bench-rotating-6rep-ok", eccFirst, 6),
        Triple("field-bench-rotating-6rep", eccFirst, 6),
        Triple("field-legcurl-1030-12rep", legCurl, 12),
        Triple("field-legcurl-1030-12rep-b", legCurl, 12),
        Triple("field-legcurl-1030-12rep-c", legCurl, 12),
    )

    /** The eight that do not, which is where the surviving artefacts live. */
    private val notCueTracked = listOf(
        "field-backsquat-10hz",
        "field-backsquat-10hz-set5",
        "field-cablerow-static-8rep",
        "field-facepull-static-12rep",
        "field-ohp-100hz-bursty",
        "field-pallof-static-12rep",
        "field-seated-ohp-2rep",
        "field-still-0rep",
    )

    /**
     * Rep windows: one median cue cycle each, starting at every `Down`. The
     * width is the median gap between consecutive `Down` cues on THIS track, so
     * it follows the tempo the set was paced at.
     */
    private fun windows(fixture: String): List<Pair<Long, Long>> {
        val downs = CueTrack.movement(fixture, "Down")
        val gaps = downs.zipWithNext { a, b -> b - a }.sorted()
        val cycleMs = gaps[gaps.size / 2]
        return downs.map { it to it + cycleMs }
    }

    /**
     * The reps the live tracker counts, each tagged with the ARRIVAL time of the
     * sample that completed it.
     *
     * Arrival time, not the DSP's reconstructed clock: VelocityEstimator places
     * sample i at `i * dt`, which drifts from arrival by up to
     * [CueTrack.MAX_SKEW_MS] mid-set. Windowing on arrival keeps that skew out
     * of the assignment.
     */
    private fun countedReps(fixture: String, d: LiftDirection, c: DspConfig): List<CountedRep> {
        val samples = load(fixture)
        val tracker = StreamingSetTracker.forLift(d, c)
        val dt = 1.0 / VelocityEstimator.measureSampleRate(
            samples.size,
            (samples.last().timestampMs - samples.first().timestampMs) / 1000.0,
        )
        var type = 0
        var displacement = 0.0
        var n = 0
        var pending = false
        val out = mutableListOf<CountedRep>()
        samples.forEachIndexed { i, sample ->
            val v = tracker.feed(sample).velocityMps
            val k = if (v > c.pauseBandMps) 1 else if (v < -c.pauseBandMps) -1 else 0
            if (k == type) {
                if (k != 0) {
                    displacement += abs(v) * dt
                    n++
                }
                return@forEachIndexed
            }
            if (type != 0) {
                val durationS = n * dt
                val qualified = displacement >= c.minRomM && durationS >= c.minPhaseS &&
                    displacement <= c.maxRunDisplacementM
                if (qualified) {
                    val concentric = (type == 1) == d.driveIsPositive
                    if (d.startsWith == StartPhase.ECCENTRIC) {
                        if (!concentric) {
                            pending = true
                        } else if (pending) {
                            pending = false
                            out += CountedRep(displacement, samples[i - 1].timestampMs)
                        }
                    } else if (concentric) {
                        out += CountedRep(displacement, samples[i - 1].timestampMs)
                    }
                }
            }
            type = k
            displacement = if (k != 0) abs(v) * dt else 0.0
            n = if (k != 0) 1 else 0
        }
        return out
    }

    private fun hits(fixture: String, d: LiftDirection, c: DspConfig, tolMs: Long): IntArray {
        val w = windows(fixture)
        val counts = IntArray(w.size)
        countedReps(fixture, d, c).forEach { r ->
            val k = w.indexOfFirst { (a, b) -> r.endMs >= a - tolMs && r.endMs < b + tolMs }
            if (k >= 0) counts[k]++
        }
        return counts
    }

    private fun uncalled(fixture: String, d: LiftDirection, c: DspConfig, tolMs: Long): List<CountedRep> {
        val w = windows(fixture)
        return countedReps(fixture, d, c)
            .filter { r -> w.none { (a, b) -> r.endMs >= a - tolMs && r.endMs < b + tolMs } }
    }

    @Test
    fun `the rebuilt reps reproduce the rep count of the shipped tracker`() {
        // The licence for every figure in this file. countedReps rebuilds the
        // tracker's pairing from the velocity it publishes, because its run
        // state is private -- so it is a model, and a model that drifts from
        // the tracker measures nothing. Without this, removing the displacement
        // bound of issue 86 from StreamingSetTracker leaves every assertion
        // here green, which is exactly what a check that cannot fail looks
        // like.
        val c = DspConfig()
        cueTracked.forEach { (fixture, d, _) ->
            val tracker = StreamingSetTracker.forLift(d, c)
            var last = LiveSetState()
            load(fixture).forEach { last = tracker.feed(it) }
            assertEquals(last.repCount, countedReps(fixture, d, c).size, "$fixture: rebuilt against shipped")
        }
    }

    @Test
    fun `almost every counted rep is one the metronome called`() {
        val c = DspConfig()
        val tol = CueTrack.WINDOW_TOLERANCE_MS.toLong()
        var counted = 0
        var called = 0
        cueTracked.forEach { (fixture, d, _) ->
            val reps = countedReps(fixture, d, c)
            counted += reps.size
            called += reps.size - uncalled(fixture, d, c, tol).size
        }
        assertEquals(37, counted, "counted reps across the seven cue-tracked captures")
        assertEquals(37, called, "of those, reps landing inside a cued rep window")
    }

    @Test
    fun `no counted rep is one nobody called`() {
        // There is no artefact population left where truth exists. Every rep
        // the counter reports lands inside a rep the metronome called.
        //
        // There used to be exactly one -- 0.125 m over 1.08 s on
        // field-legcurl-1030-12rep-c, arriving after the last window closed --
        // and issue 102 removed it along with the other reps that were being
        // taken off the return stroke. It is recorded here because a count of
        // zero says nothing about what it replaced.
        val c = DspConfig()
        val tol = CueTrack.WINDOW_TOLERANCE_MS.toLong()
        val strays = cueTracked.flatMap { (fixture, d, _) ->
            uncalled(fixture, d, c, tol).map { fixture to it }
        }
        assertEquals(0, strays.size, "counted reps outside every cued window")
    }

    @Test
    fun `the coverage limit - seven captures have truth and eight do not`() {
        // Without this the figures above read as corpus-wide. They are not:
        // every artefact identified before this fixture existed lives in the
        // second list, field-ohp-100hz-bursty most of all.
        assertEquals(7, cueTracked.size, "captures with a cue track")
        assertEquals(8, notCueTracked.size, "captures without one")
        cueTracked.forEach { (fixture, _, _) ->
            assertTrue(
                javaClass.getResourceAsStream("/$fixture-cues.csv") != null,
                "$fixture must carry a cue track",
            )
        }
        notCueTracked.forEach { fixture ->
            assertTrue(
                javaClass.getResourceAsStream("/$fixture-cues.csv") == null,
                "$fixture must NOT carry a cue track",
            )
            assertTrue(
                javaClass.getResourceAsStream("/$fixture.csv") != null,
                "$fixture must still be a capture in the corpus",
            )
        }
        assertTrue("field-ohp-100hz-bursty" in notCueTracked, "the survivors' capture has no truth")
    }

    @Test
    fun `every rep the batch reference rejects is one the metronome called`() {
        // Why no metric is built on that reference, measured rather than
        // suspected: it rejects six counted reps and the metronome called all
        // six of them. "Out of family" is LiveCapCalibrationTest's own rule --
        // more than twice, or less than a third of, the set's BATCH median rep
        // ROM -- applied to the captures where the metronome says what happened.
        val c = DspConfig()
        val tol = CueTrack.WINDOW_TOLERANCE_MS.toLong()
        var outOfFamily = 0
        var outOfFamilyCalled = 0
        var inFamily = 0
        var inFamilyCalled = 0
        cueTracked.forEach { (fixture, d, _) ->
            val roms = SetAnalyzer.analyze(load(fixture), d, loadKg = 20.0, config = c).reps
                .map { it.romM }
                .sorted()
            val ref = if (roms.isEmpty()) 0.0 else roms[roms.size / 2]
            val w = windows(fixture)
            countedReps(fixture, d, c).forEach { r ->
                val called = w.any { (a, b) -> r.endMs >= a - tol && r.endMs < b + tol }
                val out = ref > 0.0 && (r.displacementM > 2.0 * ref || r.displacementM < ref / 3.0)
                if (out) {
                    outOfFamily++
                    if (called) outOfFamilyCalled++
                } else {
                    inFamily++
                    if (called) inFamilyCalled++
                }
            }
        }
        assertEquals(6, outOfFamily, "counted reps the batch reference rejects")
        assertEquals(6, outOfFamilyCalled, "of those, reps the metronome actually called")
        assertEquals(31, inFamily, "counted reps the batch reference accepts")
        assertEquals(31, inFamilyCalled, "of those, reps the metronome actually called")
    }

    @Test
    fun `the out-of-family survivors reconcile with the corpus totals`() {
        // Ties this file to the corpus figures pinned in `the corpus totals,
        // corrected against the ones issue 94 was filed with (pre-fix)` and in
        // `the bound destroys no rep that is in family with its own set`: 81
        // counted, 61 in family, so 20 rejected. Fifteen of those twenty are
        // here and fourteen of the fifteen were called; the remaining five are
        // on captures with no truth and are neither confirmed nor disproved.
        val c = DspConfig()
        var counted = 0
        var inFamily = 0
        cueTracked.forEach { (fixture, d, _) ->
            val roms = SetAnalyzer.analyze(load(fixture), d, loadKg = 20.0, config = c).reps
                .map { it.romM }
                .sorted()
            val ref = if (roms.isEmpty()) 0.0 else roms[roms.size / 2]
            countedReps(fixture, d, c).forEach { r ->
                counted++
                val out = ref > 0.0 && (r.displacementM > 2.0 * ref || r.displacementM < ref / 3.0)
                if (!out) inFamily++
            }
        }
        val corpusCounted = 74
        val corpusInFamily = 63
        assertEquals(37, corpusCounted - counted, "counted reps on captures with no cue track")
        assertEquals(32, corpusInFamily - inFamily, "of those, in family by the batch reference")
        assertEquals(
            5,
            (corpusCounted - corpusInFamily) - (counted - inFamily),
            "out-of-family reps that no cue track can confirm or disprove",
        )
    }

    @Test
    fun `half the reps the lifter performed produce no count at all`() {
        // Issue 94 as a per-rep fact rather than a per-set total, and on
        // eccentric-first barbell work for the first time.
        val c = DspConfig()
        val tol = CueTrack.WINDOW_TOLERANCE_MS.toLong()
        var cued = 0
        var empty = 0
        var barbellCued = 0
        var barbellEmpty = 0
        cueTracked.forEach { (fixture, d, performed) ->
            val h = hits(fixture, d, c, tol)
            assertEquals(performed, h.size, "$fixture: cued reps against the count performed")
            cued += h.size
            empty += h.count { it == 0 }
            if (d !== legCurl) {
                barbellCued += h.size
                barbellEmpty += h.count { it == 0 }
            }
        }
        assertEquals(64, cued, "cued reps across the seven")
        assertEquals(27, empty, "cued reps that produced no counted rep")
        assertEquals(28, barbellCued, "cued reps on the four barbell captures")
        assertEquals(14, barbellEmpty, "of those, exactly half produced nothing")
    }

    @Test
    fun `no cued rep is counted twice, on either kind of lift`() {
        // This WAS issue 102's differential: seven windows on the leg curls held
        // two counted reps each against none on the four barbell captures,
        // because the tracker could not be told which way the drive moves. It
        // can now, and the seven are gone. Kept as a regression guard, and as
        // the record that the differential was real when it was measured.
        val c = DspConfig()
        val tol = CueTrack.WINDOW_TOLERANCE_MS.toLong()
        var legCurlDoubled = 0
        var barbellDoubled = 0
        cueTracked.forEach { (fixture, d, _) ->
            val doubled = hits(fixture, d, c, tol).count { it > 1 }
            if (d === legCurl) legCurlDoubled += doubled else barbellDoubled += doubled
        }
        assertEquals(0, legCurlDoubled, "cued reps counted twice, since issue 102")
        assertEquals(0, barbellDoubled, "and none at all on drive-up lifts")
    }

    @Test
    fun `the verdict does not depend on the window tolerance`() {
        // Invariant from zero to 300 ms, so the cross-clock skew is not what
        // produces the answer. 600 ms is excluded by arithmetic rather than by
        // result: windows are one cycle apart, so widening both edges by 600
        // makes neighbours overlap by 1200 ms and a rep near a boundary can be
        // claimed by either. That is a reason not to go there, not a finding.
        val c = DspConfig()
        fun tally(tolMs: Long): Triple<Int, Int, Int> {
            var called = 0
            var empty = 0
            var doubled = 0
            cueTracked.forEach { (fixture, d, _) ->
                val h = hits(fixture, d, c, tolMs)
                called += h.sum()
                empty += h.count { it == 0 }
                doubled += h.count { it > 1 }
            }
            return Triple(called, empty, doubled)
        }
        // The shipped tolerance and zero agree exactly.
        assertEquals(Triple(37, 27, 0), tally(0L), "at 0 ms")
        assertEquals(Triple(37, 27, 0), tally(CueTrack.WINDOW_TOLERANCE_MS.toLong()), "at 150 ms")
        // 300 ms does NOT agree, and the earlier claim that it did was measured
        // on a tracker configuration the app stopped producing at issue 102.
        // One rep now sits close enough to a boundary that the earlier window
        // claims it, so one window doubles and its neighbour starves. The same
        // count of matched reps, attributed differently.
        assertEquals(Triple(37, 28, 1), tally(300L), "at 300 ms one rep is re-attributed")
        // Why 600 ms is excluded, measured rather than argued. Windows are
        // contiguous by construction -- one cycle wide, starting one cycle
        // apart -- so any positive tolerance lets neighbours overlap, and a rep
        // in the overlap is taken by the earlier window while the later one
        // starves. No rep moves at the shipped 150 ms; one moves at 300 ms.
        var called600 = 0
        var empty600 = 0
        var doubled600 = 0
        cueTracked.forEach { (fixture, d, _) ->
            val h = hits(fixture, d, c, 600L)
            called600 += h.sum()
            empty600 += h.count { it == 0 }
            doubled600 += h.count { it > 1 }
        }
        assertEquals(37, called600, "the same reps are still matched at 600 ms")
        assertEquals(27, empty600, "and the 300 ms re-attribution happens to undo itself")
        assertEquals(0, doubled600, "so 600 ms agrees with 150 by coincidence, not by being better")
    }

    @Test
    fun `window width is read from the track, not from a constant`() {
        // These fixtures were paced before issue 106 removed the announcement
        // beat, so their cycle is a second longer than a capture taken now. A
        // constant would mis-window the first new capture.
        val barbell = windows("field-bench-rotating-6rep").let { it[1].first - it[0].first }
        val curl = windows("field-legcurl-1030-12rep").let { it[1].first - it[0].first }
        assertEquals(5_006L, barbell, "bench 3010 cycle as recorded, ms")
        assertEquals(5_006L, curl, "leg curl 1030 cycle as recorded, ms")
        // Both prescribe a 4.000 s cycle. Reading these tracks with a width
        // taken from a capture paced after issue 106 would be a second short.
        assertTrue(barbell > 4_500 && curl > 4_500, "both carry the announcement beat")
    }
}
