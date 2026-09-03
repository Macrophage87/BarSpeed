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
 * Twenty of the twenty-eight committed captures carry a cue track. Across them
 * the metronome called 170 reps; 74 produce a counted rep and 96 produce none.
 * Seven counted reps land outside every cued window. All four figures are
 * pinned below.
 *
 * They have moved twice. They read 118 / 62 / 56 / 2 over thirteen captures,
 * then 126 / 67 / 59 / 5 over fourteen once
 * field-legpress-single-2011-8rep-s36-set07 was committed for issue #93, and
 * they now read 170 / 74 / 96 / 7 over twenty with the six field-36 and
 * field-37 captures committed for issue #87. The rate got WORSE rather than
 * staying flat: 47% of cued reps produced nothing over fourteen captures and
 * 56% do over twenty, because five of the six new captures are bar-mounted and
 * four of those five produce almost nothing at all. That population was
 * under-represented in the corpus until these were committed.
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
 * The twenty tracks carry nine distinct cycle widths from 3.003 s to
 * 6.008 s, pinned in `window width is read from the track, not from a
 * constant`. The four session-26 barbell captures and the three 12-rep leg
 * curls were
 * paced before issue 106 removed the announcement beat, so their 4 s
 * prescription measures 5.006 s where the same prescription recorded after it
 * measures 4.005 s. A hard-coded width would mis-window most of the corpus.
 */
class CuedRepCoverageTest {
    private data class CountedRep(val displacementM: Double, val endMs: Long)

    /** The training classes the corpus covers, so a total can be read per class. */
    private enum class TrainingClass {
        BARBELL_UPPER,
        BARBELL_LOWER,
        MACHINE_LOWER,
        ACCESSORY,

        /**
         * Assisted pull-up: the load is the lifter, the sensor rides an assist
         * strap, and it is neither barbell nor machine work. Added with the
         * field-37 captures rather than folded into ACCESSORY, because the
         * strap is exactly the mount [AnchorSupplyByMountTest] contrasts the
         * barbell against and burying it would hide that contrast in a total.
         */
        BODYWEIGHT_UPPER,
    }

    private data class Cued(
        val fixture: String,
        val direction: LiftDirection,
        val performed: Int,
        val trainingClass: TrainingClass,
        /**
         * Reps the metronome CALLED, which is the number of `Down` cues on the
         * track and therefore the number of windows this file builds. It equals
         * [performed] on every capture but one: field-ohp-3010-6rep-s37-set02
         * is a FAILED set, so the metronome called the planned 8 while the
         * lifter got 6. Defaulting it to [performed] keeps the other nineteen
         * rows reading as before; naming it separately is what lets a failed
         * set into the corpus at all.
         */
        val cued: Int = performed,
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

    /** The twenty captures carrying a cue track, with the count the lifter performed. */
    private val cueTracked = listOf(
        Cued("field-ohp-rotating-8rep", eccFirst, 8, TrainingClass.BARBELL_UPPER),
        Cued("field-ohp-rotating-8rep-b", eccFirst, 8, TrainingClass.BARBELL_UPPER),
        Cued("field-bench-rotating-6rep-ok", eccFirst, 6, TrainingClass.BARBELL_UPPER),
        Cued("field-bench-rotating-6rep", eccFirst, 6, TrainingClass.BARBELL_UPPER),
        Cued("field-backsquat-99hz-6rep", eccFirst, 6, TrainingClass.BARBELL_LOWER),
        Cued("field-rdl-3010-10rep", eccFirst, 10, TrainingClass.BARBELL_LOWER),
        Cued("field-legpress-2010-8rep", eccFirst, 8, TrainingClass.MACHINE_LOWER),
        Cued("field-legpress-single-2010-8rep", conFirst, 8, TrainingClass.MACHINE_LOWER),
        Cued("field-legpress-single-2011-8rep-s36-set07", conFirst, 8, TrainingClass.MACHINE_LOWER),
        Cued("field-legcurl-1030-12rep", legCurl, 12, TrainingClass.ACCESSORY),
        Cued("field-legcurl-1030-12rep-b", legCurl, 12, TrainingClass.ACCESSORY),
        Cued("field-legcurl-1030-12rep-c", legCurl, 12, TrainingClass.ACCESSORY),
        Cued("field-legcurl-1030-10rep", legCurl, 10, TrainingClass.ACCESSORY),
        Cued("field-reardeltfly-s32-set06", rearDeltFly, 12, TrainingClass.ACCESSORY),
        Cued("field-ohp-3010-6rep-s37-set02", conFirst, 6, TrainingClass.BARBELL_UPPER, cued = 8),
        Cued("field-bench-3010-6rep-s37-set05", eccFirst, 6, TrainingClass.BARBELL_UPPER),
        Cued("field-bench-3010-6rep-s37-set06", eccFirst, 6, TrainingClass.BARBELL_UPPER),
        Cued("field-pullup-3010-8rep-s37-set09", conFirst, 8, TrainingClass.BODYWEIGHT_UPPER),
        Cued("field-rdl-3010-10rep-s36-set05", eccFirst, 10, TrainingClass.BARBELL_LOWER),
        Cued("field-backsquat-4011-6rep-s36-set01", eccFirst, 6, TrainingClass.BARBELL_LOWER),
    )

    /**
     * Captures committed AFTER the twenty-one that [LiveUnderCountAttributionTest]
     * and [LiveCapCalibrationTest] pin their corpus totals over. Those two files
     * enumerate their corpus by hand and carry no coverage guard, so their
     * figures are over twenty-one captures and this one is not among them. It is
     * named here rather than left implicit, because the reconciliation below
     * subtracts one population from the other and the subtraction is only true
     * if both cover the same captures.
     */
    private val outsideCorpusTotals = setOf(
        "field-legpress-single-2011-8rep-s36-set07",
        "field-ohp-3010-6rep-s37-set02",
        "field-bench-3010-6rep-s37-set05",
        "field-bench-3010-6rep-s37-set06",
        "field-pullup-3010-8rep-s37-set09",
        "field-rdl-3010-10rep-s36-set05",
        "field-backsquat-4011-6rep-s36-set01",
    )

    /**
     * The nine that do not, which is where the surviving artefacts live.
     *
     * `field-rdl-3010-10rep-s36-set04` is here by choice and the choice is
     * worth stating: the capture HAS a cue track in its session archive and it
     * is deliberately not committed. Enrolling it would move six aggregate
     * figures in this file -- the cued-rep total, the counted total, the
     * drive-up total, the batch-rejected total and both tolerance-sweep
     * triples -- and every one of those re-baselines a claim about rep
     * counting that issue #138 is not about. The set's hand count comes from
     * its own `meta.json` instead; see `BlankAnalysisTest`.
     */
    private val notCueTracked = listOf(
        "field-backsquat-10hz",
        "field-backsquat-10hz-set5",
        "field-cablerow-static-8rep",
        "field-facepull-static-12rep",
        "field-ohp-100hz-bursty",
        "field-pallof-static-12rep",
        "field-rdl-3010-10rep-s36-set04",
        // Committed WITH its cue track, and still here: the track is a
        // twenty-second hold's, so it carries no `Down` and calls no rep.
        // There is no window to open and nothing per-rep to score. Its
        // zero-rep guard is in `BatchCueCoverageTest`.
        "field-ropedeadhang-hold20-s37-set11",
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
        var peak = 0.0
        var n = 0
        var pending = false
        val out = mutableListOf<CountedRep>()
        samples.forEachIndexed { i, sample ->
            val v = tracker.feed(sample).velocityMps
            val k = if (v > c.pauseBandMps) 1 else if (v < -c.pauseBandMps) -1 else 0
            if (k == type) {
                if (k != 0) {
                    displacement += abs(v) * dt
                    peak = maxOf(peak, abs(v))
                    n++
                }
                return@forEachIndexed
            }
            if (type != 0) {
                val durationS = n * dt
                // The peak term is [StreamingSetTracker.updateRuns]'s FIRST
                // qualification clause and this model used to omit it. The two
                // agreed on all fourteen captures the file was written over and
                // diverged on the first one added afterwards:
                // field-bench-3010-6rep-s37-set05 rebuilds one rep the shipped
                // tracker does not count, off a run that clears minRomM and
                // minPhaseS while never peaking above startThresholdMps. The
                // omission is corrected here rather than pinned as a known gap.
                val qualified = peak >= c.startThresholdMps && displacement >= c.minRomM &&
                    durationS >= c.minPhaseS && displacement <= c.maxRunDisplacementM
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
            peak = if (k != 0) abs(v) else 0.0
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
        assertEquals(81, counted, "counted reps across the twenty cue-tracked captures")
        assertEquals(74, called, "of those, reps landing inside a cued rep window")
    }

    @Test
    fun `the counted reps nobody called are outside the cue track, not inside it`() {
        // Seven counted reps land in no cued window: four late, three early.
        // The four LATE ones are the same shape:
        // they complete after the last window closed, in the tail that keeps
        // recording past the metronome's last call -- the population
        // [SetEndWindowTest] bounds out of the BATCH analysis, which the live
        // tracker has no bound for. field-rdl-3010-10rep is 3.830 s past its
        // last window with a 0.278 m rep, field-legpress-single-2010-8rep
        // 2.047 s past with 0.189 m, and field-legpress-single-2011-8rep-s36-set07
        // twice, 0.609 s and 6.755 s past, with 1.056 m and 1.466 m -- the last
        // two far outside any rep this set produced, so they are the sensor
        // being handled rather than a rep at all.
        //
        // The three EARLY ones are a different shape and two of them are not
        // a counter defect at all. Windows
        // are anchored on `Down` cues, and on a concentric-first set the
        // tempo holds one second at the TOP, so a drive finishes about a
        // second BEFORE the `Down` that ends its own rep. The set's first
        // drive therefore completes 0.630 s before the first window opens and
        // has nowhere to land, while every later drive lands in the PREVIOUS
        // rep's window. That off-by-one is a property of this file's windowing
        // model on a con-first lift with a top hold: measured first on
        // field-legpress-single-2011-8rep-s36-set07 at -630 ms, and reproduced
        // by the field-37 assisted pull-up at -229 ms. The third early stray is
        // NOT that shape -- field-backsquat-4011-6rep-s36-set01 is
        // eccentric-first, and its -241 ms detection is on a capture that
        // over-resolves on the live path, where [AnchorSupplyByMountTest]
        // pins seven counted reps against six performed; its BATCH count
        // over-resolved at eight before issue #87 and is pinned at six after.
        // All three are recorded rather than fixed:
        // changing the windowing model moves figures on every capture, and
        // neither issue #93 nor issue #87 asked for that.
        val c = DspConfig()
        val tol = CueTrack.WINDOW_TOLERANCE_MS.toLong()
        val strays = cueTracked.flatMap { (fixture, d, _) ->
            uncalled(fixture, d, c, tol).map { fixture to it }
        }
        assertEquals(7, strays.size, "counted reps outside every cued window")
        assertEquals(
            listOf(
                "field-backsquat-4011-6rep-s36-set01",
                "field-legpress-single-2010-8rep",
                "field-legpress-single-2011-8rep-s36-set07",
                "field-legpress-single-2011-8rep-s36-set07",
                "field-legpress-single-2011-8rep-s36-set07",
                "field-pullup-3010-8rep-s37-set09",
                "field-rdl-3010-10rep",
            ),
            strays.map { it.first }.sorted(),
            "the captures they are on",
        )
        val late = strays.filter { (fixture, rep) -> rep.endMs >= windows(fixture).last().second }
        assertEquals(4, late.size, "strays completing after the last cued window closed")
        val early = strays.filter { (fixture, rep) -> rep.endMs < windows(fixture).first().first }
        assertEquals(3, early.size, "strays completing before the first cued window opened")
        assertEquals(
            listOf(
                "field-backsquat-4011-6rep-s36-set01",
                "field-legpress-single-2011-8rep-s36-set07",
                "field-pullup-3010-8rep-s37-set09",
            ),
            early.map { it.first }.sorted(),
            "the captures the early strays are on",
        )
        assertEquals(
            listOf(-630L, -241L, -229L),
            early.map { (fixture, rep) -> rep.endMs - windows(fixture).first().first }.sorted(),
            "how far before the first Down cue each completes, ms",
        )
        assertTrue(
            strays.size == late.size + early.size,
            "no stray lands between the first and last window: every one is outside the track",
        )
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
        assertTrue(onDisk.size >= 28, "captures found on the classpath: ${onDisk.size}")
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
        // The condition is no track that CALLS A REP, which is weaker than no
        // track at all and is the honest form of it: a hold's track is
        // committed (field-ropedeadhang-hold20-s37-set11) and calls nothing,
        // so it belongs here, while a track with `Down` rows on it could not
        // be parked here to avoid re-baselining a figure.
        notCueTracked.forEach { fixture ->
            assertTrue(
                javaClass.getResourceAsStream("/$fixture-cues.csv") == null ||
                    CueTrack.calledReps(fixture) == 0,
                "$fixture must NOT carry a cue track that calls a rep",
            )
        }
        assertEquals(20, cueTracked.size, "captures with a cue track")
        assertEquals(10, notCueTracked.size, "captures with no track that calls a rep")
        assertTrue("field-ohp-100hz-bursty" in notCueTracked, "the survivors' capture has no truth")
    }

    @Test
    fun `every rep the batch reference rejects is one the metronome called`() {
        // Why no metric is built on that reference, measured rather than
        // suspected: it rejects eighteen counted reps and the metronome called
        // sixteen. "Out of family" is LiveCapCalibrationTest's own rule --
        // more than twice, or less than a third of, the set's BATCH median rep
        // ROM -- applied to the captures where the metronome says what
        // happened. Five of the seven reps nobody called are IN family, so the
        // reference does not separate them either. The rejection count rose
        // from fifteen to eighteen with issue #87, which widened the batch
        // reference's ROM spread on seven captures without touching the live
        // counter those reps come from.
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
        // Issue #94's runaway correction moves the BATCH reference this file
        // scores against; the live tracker is untouched by it. The figures
        // below therefore move without any live behaviour changing, which is
        // the standing hazard of scoring one path against the other.
        assertEquals(17, outOfFamily, "counted reps the batch reference rejects")
        assertEquals(15, outOfFamilyCalled, "of those, reps the metronome actually called")
        assertEquals(64, inFamily, "counted reps the batch reference accepts")
        assertEquals(59, inFamilyCalled, "of those, reps the metronome actually called")
    }

    @Test
    fun `the out-of-family survivors reconcile with the corpus totals`() {
        // Ties this file to the corpus figures pinned in `the corpus totals,
        // corrected against the ones issue 94 was filed with (pre-fix)` and in
        // `the bound destroys no in-family rep`. Both of those count 101 reps
        // the shipped tracker reports over TWENTY-ONE captures, of which 82 are
        // in family with their own set -- 85 before issue #87 moved the batch
        // reference the classification is taken against. The live counter did
        // not move; the reference did. Sixty-four of the 101 and 47 of the 82
        // are here, so 37 counted reps and 35 in-family ones are on captures
        // with no truth, and two out-of-family reps are neither confirmed nor
        // disproved by any cue track.
        //
        // [outsideCorpusTotals] is subtracted first, because this file's own
        // lists cover TWENTY-EIGHT captures and those totals cover twenty-one.
        // Without it the subtraction below silently changes meaning: it would
        // read as "reps on captures with no cue track" while actually being
        // that plus the seven later captures' own counted reps.
        val c = DspConfig()
        var counted = 0
        var inFamily = 0
        cueTracked.filterNot { it.fixture in outsideCorpusTotals }.forEach { (fixture, d, _) ->
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
        assertEquals(34, corpusInFamily - inFamily, "of those, in family by the batch reference")
        assertEquals(
            3,
            (corpusCounted - corpusInFamily) - (counted - inFamily),
            "out-of-family reps that no cue track can confirm or disprove",
        )
    }

    @Test
    fun `half the reps the lifter performed produce no count at all`() {
        // Issue 94 as a per-rep fact rather than a per-set total, across every
        // training class the corpus covers. The rate is 56%, and it has got
        // worse at each widening of the corpus: 42% over the seven captures
        // this started with, 47% over fourteen, 56% over twenty.
        val c = DspConfig()
        val tol = CueTrack.WINDOW_TOLERANCE_MS.toLong()
        var cued = 0
        var empty = 0
        val cuedByClass = mutableMapOf<TrainingClass, Int>()
        val emptyByClass = mutableMapOf<TrainingClass, Int>()
        cueTracked.forEach { row ->
            val h = hits(row.fixture, row.direction, c, tol)
            assertEquals(row.cued, h.size, "${row.fixture}: windows built against the reps the metronome called")
            cued += h.size
            empty += h.count { it == 0 }
            val k = row.trainingClass
            cuedByClass[k] = (cuedByClass[k] ?: 0) + h.size
            emptyByClass[k] = (emptyByClass[k] ?: 0) + h.count { it == 0 }
        }
        assertEquals(170, cued, "cued reps the metronome called across the twenty")
        assertEquals(96, empty, "cued reps that produced no counted rep")
        // Per class, because the owner's calibration order is barbell first and
        // accessory last, and the loss is not distributed the way that order
        // would suggest.
        assertEquals(
            mapOf(
                TrainingClass.BARBELL_UPPER to 48,
                TrainingClass.BARBELL_LOWER to 32,
                TrainingClass.MACHINE_LOWER to 24,
                TrainingClass.ACCESSORY to 58,
                TrainingClass.BODYWEIGHT_UPPER to 8,
            ),
            cuedByClass,
            "cued reps per training class",
        )
        assertEquals(
            mapOf(
                TrainingClass.BARBELL_UPPER to 34,
                TrainingClass.BARBELL_LOWER to 16,
                TrainingClass.MACHINE_LOWER to 12,
                TrainingClass.ACCESSORY to 27,
                TrainingClass.BODYWEIGHT_UPPER to 7,
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
        assertEquals(124, driveUpCued, "cued reps on drive-up lifts")
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
        assertEquals(Triple(74, 96, 0), tally(0L), "at 0 ms")
        assertEquals(Triple(74, 96, 0), tally(CueTrack.WINDOW_TOLERANCE_MS.toLong()), "at 150 ms")
        // Three reps are re-attributed at 300 ms and one of the three goes
        // back at 600, which is why neither is a better tolerance than the
        // shipped one -- they are noisier, not stricter.
        assertEquals(Triple(76, 97, 3), tally(300L), "at 300 ms three reps are re-attributed")
        assertEquals(Triple(76, 96, 2), tally(600L), "at 600 ms one of the three goes back")
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
        val widths = cueTracked.map { row -> windows(row.fixture).first().let { it.second - it.first } }
        assertEquals(3_003L, widths.min(), "narrowest window in the corpus, ms -- a 2010 leg press")
        assertEquals(6_008L, widths.max(), "widest, ms -- a 4011 back squat")
        assertEquals(9, widths.toSortedSet().size, "distinct widths across the twenty tracks")
    }
}
