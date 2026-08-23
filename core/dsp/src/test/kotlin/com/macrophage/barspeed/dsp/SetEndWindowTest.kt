package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.StartPhase
import com.macrophage.barspeed.model.VoiceCue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Session 32, set 6: what the analyzer makes of the stream that keeps
 * running after the metronome said the set was over. Issue #125.
 *
 * ## Provenance
 *
 * Chest-supported rear delt fly, 12 reps performed at 20 lb, recorded on app
 * 0.1.41 on 2026-08-21 and rated RPE 7 by the lifter. `field-reardeltfly-s32-set06.csv`
 * and its `-cues.csv` are that set's two raw streams copied byte for byte out of
 * the session's raw export; nothing was re-encoded, resampled or trimmed.
 *
 * The geometry below is not a guess and was not swept for. This session's export
 * carries a `geometry` block per set (schema 1.2), and set 6's reads
 * concentric-first, drive up, vertical, sensor on the implement, travel ratio
 * 1.0. With it, [SetAnalyzer.analyze] reproduces the set's published
 * `repMetrics`, `velocityLoss_pct` and `summary` to the last digit -- which is
 * what makes this fixture usable as evidence about the shipped app rather than
 * about this test.
 *
 * ## What the capture shows
 *
 * The published `velocityLoss_pct` is 82.6 with `velocityLossBasis: "measured"`,
 * the app's strongest assertion that a figure is trustworthy. The fifth and last
 * detection begins 3.48 s AFTER the set's own `Done` cue and carries a 5.07 s
 * drive against a 1 s prescription -- tempo 2011 with the drive up, so digit 3
 * is the drive. Best-to-last is measured against it.
 *
 * The tail is not an accident of this set. The eleven sets of the capture that
 * carry both a `Done` cue and an IMU stream keep recording for 4.3 to 13.7 s
 * past the cue, measured as last sample minus cue; five of the seventeen never
 * say `Done` at all and are not bounded by this rule. So there is usually
 * somewhere for a spurious detection to land, and whether one does is luck.
 * Set 6 is pinned here; set 14 shows the same shape in the same session's
 * export and has no fixture committed. Sets 8 and 10 carry the same published
 * signature and were not analysed.
 *
 * ## What these pins do NOT say
 *
 * They pin what the analyzer computes, not what the lifter did. The lifter
 * performed 12 reps; the analyzer resolves 5, and of those 5 the third and
 * fourth carry 1.835 m and 1.930 m of range on a movement whose real travel is a
 * fraction of that. Segmentation quality on this capture is issues #72, #87 and
 * #94 and is deliberately untouched here -- a rule that judged a detection by
 * its range or its duration would be the tuned threshold this window rule exists
 * to avoid.
 */
class SetEndWindowTest {
    private fun load(name: String) =
        ImuCsv.decode(javaClass.getResourceAsStream("/$name")!!.readBytes().decodeToString())

    /** The capture's own cue track, in the form the recorder hands to the analyzer. */
    private fun track(name: String) = CueTrack.read(name).map { VoiceCue(it.timestampMs, it.label) }

    private val fixture = "field-reardeltfly-s32-set06"

    /** Set 6's exported geometry block, read off the session's own export. */
    private val rearDeltFly = LiftDirection(
        startsWith = StartPhase.CONCENTRIC,
        concentricUp = true,
        sensorInverted = false,
        travelRatio = 1.0,
        plane = MovementPlane.VERTICAL,
        sensorOnStack = false,
    )

    /** The set's exported `load_kg`, which is 20.0 lb. */
    private val loadKg = 9.071847400200708

    /**
     * One row of the published `repMetrics` array, in the export's own units.
     * Only the fields this fixture is cited for are carried.
     */
    private data class PublishedRep(
        val eccS: Double?,
        val conS: Double,
        val meanConVelMps: Double,
        val peakConVelMps: Double,
        val romM: Double,
        val peakPowerW: Double,
    )

    /** `repMetrics` exactly as v0.1.41 published it for this set. */
    private val published = listOf(
        PublishedRep(0.54, 1.19, 0.768, 1.522, 0.918, 214.4),
        PublishedRep(0.47, 0.52, 0.381, 0.556, 0.203, 50.6),
        PublishedRep(null, 2.80, 0.654, 2.085, 1.835, 244.9),
        PublishedRep(null, 5.23, 0.368, 0.622, 1.930, 58.9),
        PublishedRep(null, 5.07, 0.134, 0.306, 0.679, 27.8),
    )

    // ------------------------------------------------------------------
    // The rule itself. See SetEnd for what each of these decisions costs.
    // ------------------------------------------------------------------

    /**
     * An unguided set: nothing said when it ended, so nothing is bounded, and
     * the analysis says that rather than saying zero.
     *
     * Null and 0 are the two states this has to keep apart. Reporting 0 here
     * would state that a boundary was applied and excluded nothing, which is
     * the "absence rendered as a value" failure -- and on a set with no cue
     * track the tail is exactly as long and exactly as full of handling as it
     * is on a guided one.
     */
    @Test
    fun `a set with nothing saying when it ended is not bounded and says so`() {
        assertEquals(SetEnd.NotCued, SetEnd.of(emptyList()))
        assertNull(SetEnd.NotCued.detectionsAfter(listOf(1L, 2L, 3L)), "detections after an absent boundary")
        assertTrue(SetEnd.NotCued.startedWithinSet(Long.MAX_VALUE), "no boundary excludes nothing")
    }

    /** A drive beginning on the cue's own millisecond was begun no later than the call. */
    @Test
    fun `the boundary includes the instant the cue was stamped`() {
        val end = SetEnd.Cued(1_000L)
        assertTrue(end.startedWithinSet(999L), "a drive begun before the cue")
        assertTrue(end.startedWithinSet(1_000L), "a drive begun on the cue")
        assertEquals(false, end.startedWithinSet(1_001L), "a drive begun after the cue")
        assertEquals(2, end.detectionsAfter(listOf(999L, 1_000L, 1_001L, 5_000L)), "detections after the cue")
    }

    /**
     * The FIRST `Done`, not the last.
     *
     * Two sources can speak it -- the guided runner when it has called the
     * prescription through, and the rep counter when it reaches the planned
     * count -- and on every capture held here exactly one does. Taking the
     * first makes the rule total without resting on that, and a second `Done`
     * cannot un-tell the lifter the set is over.
     */
    @Test
    fun `the first Done bounds the set, not a later one`() {
        val spoken = listOf(
            VoiceCue(1_000L, "Up"),
            VoiceCue(2_000L, "Done"),
            VoiceCue(3_000L, "Done"),
        )
        assertEquals(SetEnd.Cued(2_000L), SetEnd.of(spoken))
    }

    /** Every other cue calls a stroke or counts one; none of them ends the set. */
    @Test
    fun `a cue track that never says Done leaves the set unbounded`() {
        val spoken = listOf(
            VoiceCue(1_000L, "Ready"),
            VoiceCue(2_000L, "Up"),
            VoiceCue(3_000L, "Hold"),
            VoiceCue(4_000L, "Down"),
            VoiceCue(5_000L, "1"),
            VoiceCue(6_000L, "Time"),
        )
        assertEquals(SetEnd.NotCued, SetEnd.of(spoken))
    }

    /**
     * Set 6 through the analyzer, with and without its own cue track.
     *
     * The count is what the rule found, reported whether or not anything is
     * done with it.
     */
    @Test
    fun `set 6 counts the one detection that began after its Done cue`() {
        val samples = load("$fixture.csv")
        assertEquals(
            1,
            SetAnalyzer.analyze(samples, rearDeltFly, loadKg = loadKg, cues = track(fixture))
                .detectionsAfterSetEndCue,
            "detections beginning after Done",
        )
        assertNull(
            SetAnalyzer.analyze(samples, rearDeltFly, loadKg = loadKg).detectionsAfterSetEndCue,
            "same capture with no cue track",
        )
    }

    /**
     * Every committed capture that carries a cue track, and how many of its
     * detections begin after the set was called over.
     *
     * All thirteen, enumerated, not nine. The list stood at nine while four
     * cue-tracked captures were committed beside it, and the KDoc claim to
     * cover "every committed capture that carries a cue track" stayed green
     * throughout because nothing checked the enumeration against the corpus --
     * [CuedRepCoverageTest] `the coverage limit` now does that against the
     * resource directory.
     *
     * Eight of the thirteen carry none: the four barbell captures of session
     * 2026-08-17, one of the four leg curls, the back squat and both leg
     * presses are untouched by this rule, which is what makes the other five
     * evidence rather than a coincidence of one recording. The affected five
     * are not a random sample either -- three of them are the captures
     * [VelocityLossTest] documents as publishing no velocity loss because their
     * last detection was the fastest of the set, one of which
     * (`field-legcurl-1030-10rep`, session 31 set 11) is issue #126's own set.
     * That case's KDoc says nothing in the rep list can tell a spurious final
     * detection from a set held flat; the cue track is the thing outside the
     * rep list that can. The fifth is the Romanian deadlift, whose single
     * post-`Done` detection is the one issue #125 is named for in
     * [FieldDataRegressionTest].
     */
    @Test
    fun `five of the thirteen cued captures carry a detection that began after Done`() {
        val legCurl = LiftDirection(
            startsWith = StartPhase.CONCENTRIC,
            concentricUp = false,
            sensorInverted = true,
            plane = MovementPlane.VERTICAL,
            sensorOnStack = true,
        )
        val barbell = LiftDirection(startsWith = StartPhase.ECCENTRIC)
        val concentricFirst = LiftDirection(startsWith = StartPhase.CONCENTRIC)
        val corpus = listOf(
            Triple("field-legcurl-1030-12rep", legCurl, 0),
            Triple("field-legcurl-1030-12rep-b", legCurl, 1),
            Triple("field-legcurl-1030-12rep-c", legCurl, 2),
            Triple("field-legcurl-1030-10rep", legCurl, 1),
            Triple("field-ohp-rotating-8rep", barbell, 0),
            Triple("field-ohp-rotating-8rep-b", barbell, 0),
            Triple("field-bench-rotating-6rep", barbell, 0),
            Triple("field-bench-rotating-6rep-ok", barbell, 0),
            // The four cue-tracked captures the list did not cover, in the
            // geometry FieldDataRegressionTest analyses each of them with.
            Triple("field-backsquat-99hz-6rep", barbell, 0),
            Triple("field-rdl-3010-10rep", barbell, 1),
            Triple("field-legpress-2010-8rep", barbell, 0),
            Triple("field-legpress-single-2010-8rep", concentricFirst, 0),
            Triple(fixture, rearDeltFly, 1),
        )
        assertEquals(13, corpus.size, "cue-tracked captures covered here")
        // Compared as one map rather than one assertion per row: a per-row loop
        // stops at the first mismatch, so a change that moved several captures
        // would be reported as moving one, and the rows after it would never be
        // evaluated at all.
        assertEquals(
            corpus.associate { (name, _, expected) -> name to expected },
            corpus.associate { (name, direction, _) ->
                name to SetAnalyzer.analyze(load("$name.csv"), direction, cues = track(name)).detectionsAfterSetEndCue
            },
            "detections beginning after Done, per capture",
        )
        assertEquals(5, corpus.count { it.third > 0 }, "captures carrying at least one post-Done detection")
    }

    // ------------------------------------------------------------------
    // What the rule changes about the figures the lifter reads.
    // ------------------------------------------------------------------

    /**
     * The correction this fixture was committed for: 82.6% to 52.1%.
     *
     * The set keeps its first four detections and loses the fifth, and the
     * fourth is the straddling one -- drive begun 1.829 s before the cue and
     * ended 3.389 s after it. Keeping it is the decision [SetEnd] argues for,
     * and this is where it shows: bounding on the drive's END instead would
     * leave three reps and read 14.8%.
     *
     * 52.1% is what the rule yields, and it is NOT a claim that 52.1% is the
     * set's honest fatigue. The lifter rated the set RPE 7 -- about three reps
     * left -- which agrees with neither figure, and the reps that remain still
     * include two carrying more than 1.8 m of range on a rear delt fly. What is
     * fixed here is that the figure is computed over movements the lifter was
     * still being told to make; what is not fixed is whether those movements
     * were segmented correctly, which is #72, #87 and #94.
     */
    @Test
    fun `set 6's velocity loss drops thirty points once the tail is out of the set`() {
        val analysis =
            SetAnalyzer.analyze(load("$fixture.csv"), rearDeltFly, loadKg = loadKg, cues = track(fixture))
        assertEquals(4, analysis.reps.size, "detections kept; the lifter performed 12 reps")
        assertEquals(1, analysis.detectionsAfterSetEndCue, "detections dropped")
        assertEquals(
            published.dropLast(1).map { it.meanConVelMps },
            analysis.reps.map { it.meanConVelMps },
            "the kept drives, in the export's own figures",
        )
        assertEquals(52.1, analysis.velocityLossPct, "velocity loss over the reps of the set")
    }

    /**
     * Every committed capture with a cue track, before and after.
     *
     * Five are untouched, which is what says the rule is not simply deleting
     * the last rep of every set. On the four that move, two land exactly on the
     * count the lifter performed -- 12 for `-b`, 10 for `-10rep` -- and the peak
     * drive velocity of three of them stops being the sensor being handled:
     * 1.673 to 0.737 m/s, 1.707 to 0.768, 1.261 to 0.910. That is the same
     * defect reaching `summary.peakConVel_mps` instead of `velocityLoss_pct`,
     * and it is why the rule is applied to the rep list rather than to the
     * velocity-loss calculation.
     *
     * Three of the four stop reading [VelocityLoss.TerminalRepIsFastest] and
     * start reading [VelocityLoss.Measured]. That is not a reinterpretation of
     * the basis vocabulary, which is unchanged: those sets were withheld because
     * nothing in the REP LIST could tell a spurious final detection from a set
     * held flat, and the cue track answers exactly that question from outside
     * the rep list. Set 6 goes the other way and stays `Measured` throughout --
     * its wrong figure was never withheld from the lifter at all.
     */
    @Test
    fun `the cue track changes four of the nine cued captures and leaves five alone`() {
        val legCurl = LiftDirection(
            startsWith = StartPhase.CONCENTRIC,
            concentricUp = false,
            sensorInverted = true,
            plane = MovementPlane.VERTICAL,
            sensorOnStack = true,
        )
        val barbell = LiftDirection(startsWith = StartPhase.ECCENTRIC)
        data class Case(
            val name: String,
            val direction: LiftDirection,
            val reps: Int,
            val loss: VelocityLoss,
            val peakConVelMps: Double,
        )

        val corpus = listOf(
            Case("field-legcurl-1030-12rep", legCurl, 12, VelocityLoss.Measured(36.5), 0.577),
            Case("field-legcurl-1030-12rep-b", legCurl, 12, VelocityLoss.Measured(34.7), 0.737),
            Case("field-legcurl-1030-12rep-c", legCurl, 9, VelocityLoss.Measured(69.1), 0.768),
            Case("field-legcurl-1030-10rep", legCurl, 10, VelocityLoss.Measured(22.5), 0.910),
            Case("field-ohp-rotating-8rep", barbell, 3, VelocityLoss.Measured(30.7), 1.062),
            Case("field-ohp-rotating-8rep-b", barbell, 4, VelocityLoss.Measured(35.9), 1.119),
            Case("field-bench-rotating-6rep", barbell, 2, VelocityLoss.Measured(7.0), 0.600),
            Case("field-bench-rotating-6rep-ok", barbell, 6, VelocityLoss.Measured(58.3), 0.676),
            Case(fixture, rearDeltFly, 4, VelocityLoss.Measured(52.1), 2.085),
        )
        corpus.forEach { case ->
            val reps = SetAnalyzer.analyze(load("${case.name}.csv"), case.direction, cues = track(case.name)).reps
            assertEquals(case.reps, reps.size, "${case.name} detections kept")
            assertEquals(case.loss, VelocityLoss.of(reps), "${case.name} velocity loss")
            assertEquals(case.peakConVelMps, reps.maxOf { it.peakConVelMps }, "${case.name} peak drive velocity")
        }
    }

    /**
     * The fixture reproduces the shipped export, rep for rep.
     *
     * This is the pin that licenses every other claim made from this capture. It
     * is also the analysis of an UNCUED set -- no cue track is passed -- so it
     * states the other half of the window rule at the same time: with nothing on
     * the record saying when the set ended, no boundary is invented and every
     * detection is kept, including the one that is plainly post-set handling.
     */
    @Test
    fun `set 6 reproduces its published export rep for rep with no cue track`() {
        val analysis = SetAnalyzer.analyze(load("$fixture.csv"), rearDeltFly, loadKg = loadKg)
        assertEquals(published.size, analysis.reps.size, "detections resolved; the lifter performed 12 reps")
        analysis.reps.forEachIndexed { i, rep ->
            val want = published[i]
            assertEquals(want.eccS, rep.eccS, "rep ${i + 1} ecc_s")
            assertEquals(want.conS, rep.conS, "rep ${i + 1} con_s")
            assertEquals(want.meanConVelMps, rep.meanConVelMps, "rep ${i + 1} meanConVel_mps")
            assertEquals(want.peakConVelMps, rep.peakConVelMps, "rep ${i + 1} peakConVel_mps")
            assertEquals(want.romM, rep.romM, "rep ${i + 1} rom_m")
            assertEquals(want.peakPowerW, rep.peakPowerW, "rep ${i + 1} peakPower_w")
        }
        assertEquals(82.6, analysis.velocityLossPct, "published velocityLoss_pct")
        assertEquals(
            2.085,
            analysis.reps.maxOf { it.peakConVelMps },
            "published summary.peakConVel_mps",
        )
    }

    /**
     * The enabling condition, stated as a number: the stream runs on for ten and
     * a half seconds after the app stopped prescribing.
     *
     * A data pin over the two fixture files, and it asserts nothing about any
     * code path beyond [CueTrack.read] and [ImuCsv.decode]. It is here because
     * the rest of this class is meaningless without it -- a window rule on a
     * capture with no tail would be a rule with nothing to do.
     */
    @Test
    fun `set 6 keeps recording for ten and a half seconds after its own Done cue`() {
        val samples = load("$fixture.csv")
        val done = CueTrack.read(fixture).first { it.label == "Done" }
        assertEquals(1787340900506L, done.timestampMs, "the Done cue instant")
        assertEquals(1787340847464L, samples.first().timestampMs, "first sample")
        assertEquals(1787340911067L, samples.last().timestampMs, "last sample")
        assertEquals(10561L, samples.last().timestampMs - done.timestampMs, "tail after Done, ms")
    }

    /**
     * Where each detected drive BEGINS relative to the `Done` cue, in
     * milliseconds, negative before.
     *
     * Compared on the arrival clock at both ends: a `RepSpan` indexes the
     * velocity series, which [VelocityEstimator.estimate] builds index-parallel
     * to the sample list, so `samples[conStartIdx]` is the sample the drive
     * starts on and its own `timestampMs` is the same wall clock the cue was
     * stamped on. Nothing here converts through the reconstructed clock, so
     * [CueTrack.MAX_SKEW_MS] does not enter.
     *
     * Four of five start before the cue. The fifth starts 3.479 s after it, and
     * the fourth starts 1.829 s before it while ending 3.389 s after -- the
     * straddling case, which is the one the rule has to make a decision about.
     */
    @Test
    fun `four of set 6's five detected drives begin before the Done cue`() {
        val samples = load("$fixture.csv")
        val doneMs = CueTrack.read(fixture).first { it.label == "Done" }.timestampMs
        val series = VelocityEstimator.estimate(samples, DspConfig(), rearDeltFly.measuredPlane)
            .mappedToLifter(rearDeltFly.sensorToLifter)
        val spans = RepSegmenter.segment(series, rearDeltFly, DspConfig())
        assertEquals(
            listOf(-50247L, -33778L, -32731L, -1829L, 3479L),
            spans.map { samples[it.conStartIdx].timestampMs - doneMs },
            "drive start relative to Done, ms",
        )
        assertEquals(
            listOf(-49081L, -33237L, -29909L, 3389L, 8550L),
            spans.map { samples[it.conEndIdx].timestampMs - doneMs },
            "drive end relative to Done, ms",
        )
    }
}
