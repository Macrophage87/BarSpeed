package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.StartPhase
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every rep the live counter reports, checked against the rep the metronome
 * called for it. Per-rep ground truth on eccentric-first barbell work, on
 * lower-body barbell and machine work, and on accessory work.
 *
 * ## What the corpus says today
 *
 * Thirteen of the twenty-one committed captures carry a cue track. Across them
 * the metronome called 118 reps; 62 produce a counted rep and 56 produce none.
 * Two counted reps land outside every cued window. All four figures are pinned
 * below.
 *
 * ## What this does NOT say
 *
 * It does not say the counter no longer invents reps. Eight captures carry no
 * cue track, and previously identified survivors are in that group --
 * field-ohp-100hz-bursty keeps runs of 1.553, 1.604 and 1.892 m against a
 * 0.766 m median, all UNPINNED figures quoted in [LiveCapCalibrationTest].
 * Nothing here disproves them. The boundary between the two groups is pinned
 * in `the coverage limit`, against the resource directory rather than against
 * a hand-kept number, so the figures above cannot be read as corpus-wide and
 * cannot silently become stale when a fixture is added.
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
 * The thirteen tracks carry eight distinct cycle widths from 3.003 s to
 * 6.008 s, pinned in `window width is read from the track, not from a
 * constant`. The four barbell captures and the three 12-rep leg curls were
 * paced before issue 106 removed the announcement beat, so their 4 s
 * prescription measures 5.006 s where the same prescription recorded after it
 * measures 4.005 s. A hard-coded width would mis-window most of the corpus.
 */
class CuedRepCoverageTest {
    private data class CountedRep(val displacementM: Double, val endMs: Long)

    /** The four training classes the corpus covers, so a total can be read per class. */
    private enum class TrainingClass { BARBELL_UPPER, BARBELL_LOWER, MACHINE_LOWER, ACCESSORY }

    private data class Cued(
        val fixture: String,
        val direction: LiftDirection,
        val performed: Int,
        val trainingClass: TrainingClass,
    )

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

    /** Session 32 set 6's exported geometry block, as [SetEndWindowTest] reads it. */
    private val rearDeltFly = LiftDirection(
        startsWith = StartPhase.CONCENTRIC,
        concentricUp = true,
        sensorInverted = false,
        travelRatio = 1.0,
        plane = MovementPlane.VERTICAL,
        sensorOnStack = false,
    )

    private val eccFirst = LiftDirection(startsWith = StartPhase.ECCENTRIC)

    private val conFirst = LiftDirection(startsWith = StartPhase.CONCENTRIC)

    /** The thirteen captures carrying a cue track, with the count the lifter performed. */
    private val cueTracked = listOf(
        Cued("field-ohp-rotating-8rep", eccFirst, 8, TrainingClass.BARBELL_UPPER),
        Cued("field-ohp-rotating-8rep-b", eccFirst, 8, TrainingClass.BARBELL_UPPER),
        Cued("field-bench-rotating-6rep-ok", eccFirst, 6, TrainingClass.BARBELL_UPPER),
        Cued("field-bench-rotating-6rep", eccFirst, 6, TrainingClass.BARBELL_UPPER),
        Cued("field-backsquat-99hz-6rep", eccFirst, 6, TrainingClass.BARBELL_LOWER),
        Cued("field-rdl-3010-10rep", eccFirst, 10, TrainingClass.BARBELL_LOWER),
        Cued("field-legpress-2010-8rep", eccFirst, 8, TrainingClass.MACHINE_LOWER),
        Cued("field-legpress-single-2010-8rep", conFirst, 8, TrainingClass.MACHINE_LOWER),
        Cued("field-legcurl-1030-12rep", legCurl, 12, TrainingClass.ACCESSORY),
        Cued("field-legcurl-1030-12rep-b", legCurl, 12, TrainingClass.ACCESSORY),
        Cued("field-legcurl-1030-12rep-c", legCurl, 12, TrainingClass.ACCESSORY),
        Cued("field-legcurl-1030-10rep", legCurl, 10, TrainingClass.ACCESSORY),
        Cued("field-reardeltfly-s32-set06", rearDeltFly, 12, TrainingClass.ACCESSORY),
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
        assertEquals(64, counted, "counted reps across the thirteen cue-tracked captures")
        assertEquals(62, called, "of those, reps landing inside a cued rep window")
    }

    @Test
    fun `the two counted reps nobody called both land after the last cued window`() {
        // There was no artefact population left where truth existed when the
        // corpus was seven captures; on thirteen there are two, and both are
        // the same shape. field-rdl-3010-10rep completes a 0.278 m rep 3.83 s
        // after its last window closes and field-legpress-single-2010-8rep a
        // 0.189 m rep 2.047 s after its own. Neither is an interior miss: they
        // are in the tail that keeps recording past the metronome's last call,
        // which is the population [SetEndWindowTest] bounds out of the BATCH
        // analysis. The live tracker has no such bound and this is what that
        // costs, measured.
        val c = DspConfig()
        val tol = CueTrack.WINDOW_TOLERANCE_MS.toLong()
        val strays = cueTracked.flatMap { (fixture, d, _) ->
            uncalled(fixture, d, c, tol).map { fixture to it }
        }
        assertEquals(2, strays.size, "counted reps outside every cued window")
        assertEquals(
            listOf("field-legpress-single-2010-8rep", "field-rdl-3010-10rep"),
            strays.map { it.first }.sorted(),
            "the captures they are on",
        )
        strays.forEach { (fixture, rep) ->
            val lastWindowEnd = windows(fixture).last().second
            assertTrue(
                rep.endMs >= lastWindowEnd,
                "$fixture: the stray completes after the last cued window closed",
            )
        }
    }

    @Test
    fun `the coverage limit - every committed capture is in exactly one of the two lists`() {
        // Without this the figures above read as corpus-wide. They are not.
        // Asserted against the resource directory rather than against two
        // hand-kept list lengths, because the hand-kept version could not
        // detect the thing that actually happened: six captures were added and
        // named in neither list, and every assertion stayed green while the
        // file's headline claim went false.
        val dir = File(javaClass.getResource("/field-still-0rep.csv")!!.toURI()).parentFile
        val onDisk = dir.list()!!
            .filter { it.startsWith("field-") && it.endsWith(".csv") && !it.endsWith("-cues.csv") }
            .map { it.removeSuffix(".csv") }
            .sorted()
        assertTrue(onDisk.size >= 21, "captures found on the classpath: ${onDisk.size}")
        assertEquals(
            onDisk,
            (cueTracked.map { it.fixture } + notCueTracked).sorted(),
            "every capture in the resource directory must be named in exactly one list",
        )
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
        }
        assertEquals(13, cueTracked.size, "captures with a cue track")
        assertEquals(8, notCueTracked.size, "captures without one")
        assertTrue("field-ohp-100hz-bursty" in notCueTracked, "the survivors' capture has no truth")
    }

    @Test
    fun `every rep the batch reference rejects is one the metronome called`() {
        // Why no metric is built on that reference, measured rather than
        // suspected: it rejects eleven counted reps and the metronome called
        // all eleven. "Out of family" is LiveCapCalibrationTest's own rule --
        // more than twice, or less than a third of, the set's BATCH median rep
        // ROM -- applied to the captures where the metronome says what
        // happened. The two reps nobody called are both IN family, so the
        // reference does not separate them either.
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
        assertEquals(11, outOfFamily, "counted reps the batch reference rejects")
        assertEquals(11, outOfFamilyCalled, "of those, reps the metronome actually called")
        assertEquals(53, inFamily, "counted reps the batch reference accepts")
        assertEquals(51, inFamilyCalled, "of those, reps the metronome actually called")
    }

    @Test
    fun `the out-of-family survivors reconcile with the corpus totals`() {
        // Ties this file to the corpus figures pinned in `the corpus totals,
        // corrected against the ones issue 94 was filed with (pre-fix)` and in
        // `the bound destroys no in-family rep`. Both of those count the same
        // population this file does: 101 reps the shipped tracker reports over
        // all twenty-one captures, of which 85 are in family with their own
        // set. Sixty-four of the 101 and 53 of the 85 are here, so 37 counted
        // reps and 32 in-family ones are on captures with no truth, and five
        // out-of-family reps are neither confirmed nor disproved by any cue
        // track.
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
        val corpusCounted = 101
        val corpusInFamily = 85
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
        // Issue 94 as a per-rep fact rather than a per-set total, across every
        // training class the corpus covers. The rate is 47%, and it is worse on
        // the wider corpus than on the seven captures this started with (42%),
        // not better.
        val c = DspConfig()
        val tol = CueTrack.WINDOW_TOLERANCE_MS.toLong()
        var cued = 0
        var empty = 0
        val cuedByClass = mutableMapOf<TrainingClass, Int>()
        val emptyByClass = mutableMapOf<TrainingClass, Int>()
        cueTracked.forEach { row ->
            val h = hits(row.fixture, row.direction, c, tol)
            assertEquals(row.performed, h.size, "${row.fixture}: cued reps against the count performed")
            cued += h.size
            empty += h.count { it == 0 }
            val k = row.trainingClass
            cuedByClass[k] = (cuedByClass[k] ?: 0) + h.size
            emptyByClass[k] = (emptyByClass[k] ?: 0) + h.count { it == 0 }
        }
        assertEquals(118, cued, "cued reps across the thirteen")
        assertEquals(56, empty, "cued reps that produced no counted rep")
        // Per class, because the owner's calibration order is barbell first and
        // accessory last, and the loss is not distributed the way that order
        // would suggest.
        assertEquals(
            mapOf(
                TrainingClass.BARBELL_UPPER to 28,
                TrainingClass.BARBELL_LOWER to 16,
                TrainingClass.MACHINE_LOWER to 16,
                TrainingClass.ACCESSORY to 58,
            ),
            cuedByClass,
            "cued reps per training class",
        )
        assertEquals(
            mapOf(
                TrainingClass.BARBELL_UPPER to 14,
                TrainingClass.BARBELL_LOWER to 6,
                TrainingClass.MACHINE_LOWER to 9,
                TrainingClass.ACCESSORY to 27,
            ),
            emptyByClass,
            "of those, the ones that produced nothing",
        )
    }

    @Test
    fun `no cued rep is counted twice, on either kind of lift`() {
        // This WAS issue 102's differential: seven windows on the leg curls held
        // two counted reps each against none on the four barbell captures,
        // because the tracker could not be told which way the drive moves. It
        // can now, and the seven are gone. Kept as a regression guard, and as
        // the record that the differential was real when it was measured.
        //
        // Split on LiftDirection.driveIsPositive rather than on which
        // LiftDirection instance a row happens to hold: that is the property
        // issue 102 was about, and an identity test silently reclassified every
        // capture added since.
        val c = DspConfig()
        val tol = CueTrack.WINDOW_TOLERANCE_MS.toLong()
        var driveDownCued = 0
        var driveDownDoubled = 0
        var driveUpCued = 0
        var driveUpDoubled = 0
        cueTracked.forEach { (fixture, d, _) ->
            val h = hits(fixture, d, c, tol)
            if (d.driveIsPositive) {
                driveUpCued += h.size
                driveUpDoubled += h.count { it > 1 }
            } else {
                driveDownCued += h.size
                driveDownDoubled += h.count { it > 1 }
            }
        }
        assertEquals(46, driveDownCued, "cued reps on drive-down lifts")
        assertEquals(72, driveUpCued, "cued reps on drive-up lifts")
        assertEquals(0, driveDownDoubled, "cued reps counted twice on drive-down lifts, since issue 102")
        assertEquals(0, driveUpDoubled, "and none at all on drive-up lifts")
    }

    @Test
    fun `the verdict does not depend on the window tolerance`() {
        // Invariant from zero to 150 ms, so the cross-clock skew is not what
        // produces the answer. Beyond that it is not invariant, and the reason
        // is arithmetic rather than a finding: windows are contiguous by
        // construction -- one cycle wide, starting one cycle apart -- so any
        // positive tolerance lets neighbours overlap, and a rep in the overlap
        // is taken by the earlier window while the later one starves. The count
        // of MATCHED reps never moves; only their attribution does.
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
        assertEquals(Triple(62, 56, 0), tally(0L), "at 0 ms")
        assertEquals(Triple(62, 56, 0), tally(CueTrack.WINDOW_TOLERANCE_MS.toLong()), "at 150 ms")
        // Two reps are re-attributed at 300 ms and one of those two goes back
        // at 600, which is why neither is a better tolerance than the shipped
        // one -- they are noisier, not stricter.
        assertEquals(Triple(62, 58, 2), tally(300L), "at 300 ms two reps are re-attributed")
        assertEquals(Triple(62, 57, 1), tally(600L), "at 600 ms one of the two goes back")
    }

    @Test
    fun `window width is read from the track, not from a constant`() {
        // The session-26 fixtures were paced before issue 106 removed the
        // announcement beat, so their cycle is a second longer than the same
        // prescription recorded after it. A constant would mis-window most of
        // the corpus, and this pins by how much.
        val barbell = windows("field-bench-rotating-6rep").let { it[1].first - it[0].first }
        val curl = windows("field-legcurl-1030-12rep").let { it[1].first - it[0].first }
        assertEquals(5_006L, barbell, "bench 3010 cycle as recorded, ms")
        assertEquals(5_006L, curl, "leg curl 1030 cycle as recorded, ms")
        val widths = cueTracked.map { (fixture, _, _) -> windows(fixture).first().let { it.second - it.first } }
        assertEquals(3_003L, widths.min(), "narrowest window in the corpus, ms -- a 2010 leg press")
        assertEquals(6_008L, widths.max(), "widest, ms -- a 4011 back squat")
        assertEquals(8, widths.toSortedSet().size, "distinct widths across the thirteen tracks")
    }
}
