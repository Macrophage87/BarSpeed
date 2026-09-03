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
 * Those five are issue #141's subject and every one of them is a set the lifter
 * failed. A set recorded on this app today says `Set ended` there and IS
 * bounded; these captures are 0.1.41 and predate that, so what this file
 * asserts about them is unchanged -- see [FailedSetBoundaryTest] for what the
 * boundary does and, more usefully, what it does not do.
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
     * The EARLIEST `Done`, not a later one.
     *
     * Two sources can speak it -- the guided runner when it has called the
     * prescription through, and the rep counter when it reaches the planned
     * count -- and on every capture held here exactly one does. Taking the
     * EARLIEST terminal instant makes the rule total without resting on that,
     * and a second `Done` cannot un-tell the lifter the set is over. See
     * [SetEnd] for why the earliest and the first coincide on every capture
     * held here.
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

    /**
     * Every cue outside [SetEnd.TERMINAL_CUES] calls a stroke or counts one;
     * none of those ends the set.
     */
    @Test
    fun `a cue track with no terminal cue leaves the set unbounded`() {
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
     * The two answers the REST clock will be seeded from, at the cue-track
     * shapes `:app` actually freezes into a pending write (#172).
     *
     * Characterization, not new behaviour: [SetEnd.of] is unchanged by #172.
     * What is new is a second consumer of it. The rest period is due to start
     * when the set was called over rather than when the rest screen draws, and
     * the instant it starts from is this cue's stamp -- so the two facts that
     * consumer rests on are pinned here before anything depends on them.
     *
     * The first is that a guided set's full track, rep calls and all, yields
     * the `Done` stamp UNCHANGED -- no rounding, no offset, the same host
     * arrival millisecond `speakCues` wrote. A rest clock seeded from a
     * shifted instant would be wrong by that shift on every set.
     *
     * The second is the one that decides the fallback exists at all: a hold's
     * track ends on `Time`, which this deliberately does not bound on, so a
     * timed set is [SetEnd.NotCued] and its rest has no cue instant to start
     * from. That is an absence and the caller has to treat it as one. It is
     * also harmless there -- a hold ends on its own clock, so the instant the
     * set write freezes IS the instant it ended.
     */
    @Test
    fun `the rest clock's seed instant is the Done stamp, and a hold has none`() {
        val guided = listOf(
            VoiceCue(1_000L, "Ready"),
            VoiceCue(2_000L, "Brace"),
            VoiceCue(3_000L, "Down"),
            VoiceCue(4_000L, "Up"),
            VoiceCue(4_000L, "Rep 1"),
            VoiceCue(9_000L, "Last rep"),
            VoiceCue(13_517L, "Done"),
        )
        assertEquals(SetEnd.Cued(13_517L), SetEnd.of(guided), "the Done stamp, unshifted")

        val hold = listOf(
            VoiceCue(1_000L, "Hold"),
            VoiceCue(31_000L, "15 seconds"),
            VoiceCue(45_000L, "1"),
            VoiceCue(46_000L, "Time"),
        )
        assertEquals(SetEnd.NotCued, SetEnd.of(hold), "a hold names no set-over cue")
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
     * Eighteen of the twenty-five committed captures that carry a cue track
     * over their own base IMU stream -- that population is the only one this
     * analyzer call can run over at all -- and how many of each one's
     * detections begin after the set was called over. The seven it does not
     * cover are named below; this heading said "every committed capture" and
     * the rebase onto v0.1.50 made that false, so it is narrowed to the
     * number counted at this tree rather than left as a claim.
     *
     * Eighteen, enumerated, not thirteen and not nine. The list stood at
     * nine while four cue-tracked captures were committed beside it, and the
     * KDoc claim to cover "every committed capture that carries a cue track"
     * stayed green throughout because nothing checked the enumeration
     * against the corpus -- [CuedRepCoverageTest] `the coverage limit` now
     * does that against the resource directory. It went stale the same way
     * a second time: round 1 of this issue's own review corrected nine to
     * thirteen and missed that issue #133 had, by then, committed a fifth
     * cue-tracked capture outside this list
     * (`field-legpress-single-2011-8rep-s36-set07`, issue #93) alongside the
     * four rotation fixtures (`field-backsquat-wrapping-s36-set01`,
     * `field-rdl-wrapping-s36-set05`, `field-ohp-prepinflated-s37-set03`,
     * `field-ohp-prepinflated-s37-set04`) it added for its own purpose.
     * AND IT WENT STALE A THIRD TIME, on the rebase that put this branch on
     * `origin/main` at `a7dfa323` (v0.1.50). The sentence here read "six
     * further captures carry a cue track and are still not here ... none of
     * them is a gap in this list: none has a base `.csv` committed beside its
     * `-cues.csv`". Counted at this tree rather than carried: 34 base
     * captures are committed, 25 of them carry a cue track, and this corpus
     * covers 18. SEVEN are outside it, and the "no base stream" reason is
     * true of exactly ONE of them. The claim is deleted rather than reworded.
     *
     * `field-ropedeadhang-hold20-s37-set11` is the one it stays true of in
     * spirit: it has a base stream, but its track calls no rep -- a
     * twenty-second hold, `reps: 0` in its own `meta.json` -- so there is no
     * post-`Done` drive for this question to have an answer about. (The five
     * tracks with no base `.csv` at all --
     * `field-inclinepress-3010-10rep-s33-set01`,
     * `field-lateralraise-2011-s32-set09`, `field-ohp-3010-8rep-s33-set05`,
     * `field-pushdown-1120-12rep-s33-set13` and
     * `field-ropedeadhang-hold30-s37-set12` -- are not base captures at all
     * and were never in the population this KDoc's first sentence names.)
     *
     * The other SIX are a real gap and are named as one rather than papered
     * over: `field-backsquat-4011-6rep-s36-set01`,
     * `field-bench-3010-6rep-s37-set05`, `field-bench-3010-6rep-s37-set06`,
     * `field-ohp-3010-6rep-s37-set02`, `field-pullup-3010-8rep-s37-set09`
     * and `field-rdl-3010-10rep-s36-set05` each carry a base stream and a
     * cue track and are not scored here. They were committed on `main` for
     * issue #87 while this branch was in flight, so nothing on either side
     * asked this question of them. Extending the corpus to them needs each
     * one's `LiftDirection` read off its own session's archive -- the
     * provenance rule the five rows added below already follow -- which is
     * its own task and is raised rather than folded into this one.
     *
     * Nine of the eighteen carry none: three of the four barbell captures of
     * session 2026-08-17, one of the four leg curls, the back squat, one of
     * the two leg presses, and three of the five added here (the Romanian
     * deadlift and both prep-inflated overhead presses) are untouched by this
     * rule. THIS PARAGRAPH READ "eleven of the eighteen ... the other seven"
     * and both halves were measured against a `main` this branch has since
     * been rebased onto: `field-ohp-rotating-8rep-b` joined the affected
     * group with issue #87 and `field-legpress-single-2010-8rep` joined it
     * with issue #94's runaway correction, so the split is nine and nine. The
     * figures are re-measured at the rebased SHA rather than carried.
     *
     * The nine that carry one are not a random sample -- two of them are
     * captures [VelocityLossTest] documents as publishing no velocity loss
     * because their last detection was the fastest of the set:
     * `field-legcurl-1030-10rep` (session 31 set 11) is issue #126's own set,
     * and `field-backsquat-wrapping-s36-set01`, added for #133, was never
     * checked against this rule before now and turns out to share the same
     * shape. That case's KDoc says nothing in the rep list can tell a
     * spurious final detection from a set held flat; the cue track is the
     * thing outside the rep list that can. `field-rdl-3010-10rep`'s single
     * post-`Done` detection is the one issue #125 is named for in
     * [FieldDataRegressionTest]. The remaining six -- two leg curls, the
     * chest-supported rear delt fly this file is named for, the two captures
     * #87 and #94 moved, and `field-legpress-single-2011-8rep-s36-set07`,
     * whose two are the most any capture in this corpus carries -- were
     * pinned or added without this question being asked of them until now.
     */
    @Test
    fun `nine of the eighteen cued captures carry a detection that began after Done`() {
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
            Triple("field-ohp-rotating-8rep-b", barbell, 1),
            Triple("field-bench-rotating-6rep", barbell, 0),
            Triple("field-bench-rotating-6rep-ok", barbell, 0),
            // The four cue-tracked captures the list did not cover, in the
            // geometry FieldDataRegressionTest analyses each of them with.
            Triple("field-backsquat-99hz-6rep", barbell, 0),
            Triple("field-rdl-3010-10rep", barbell, 1),
            Triple("field-legpress-2010-8rep", barbell, 0),
            // 0 before issue #94's runaway correction: the post-Done stretch on
            // this capture was one over-cap run the segmenter discarded, and
            // de-trending it makes a detection out of it for the cue bound to
            // reject. The bound rejecting more is the bound working.
            Triple("field-legpress-single-2010-8rep", concentricFirst, 1),
            Triple(fixture, rearDeltFly, 1),
            // The five cue-tracked captures round 2 of issue #133's review
            // found missing from this list -- one committed for issue #93
            // before this branch, four committed on this branch for #133's
            // own rotation measure, which reads roll only and assigns no
            // LiftDirection to any of them. Every direction below is read off
            // the session's own field-capture archive's meta.json (issue
            // #133's own provenance directory) rather than guessed by analogy
            // with another exercise of the same name: the single-stack leg
            // press and both overhead-press sets are ALL recorded
            // concentric-first (`startsWith: "concentric"`, `concentric:
            // "up"`), matching this file's `concentricFirst`; the back squat
            // and the Romanian deadlift are recorded eccentric-first,
            // matching `barbell`. Guessing "barbell" for the overhead-press
            // sets by analogy with `field-ohp-rotating-8rep` -- which is also
            // barbell -- would have been wrong here: the two exercises
            // sharing a name is not evidence they share a direction, and this
            // session's own meta.json settles it either way.
            Triple("field-legpress-single-2011-8rep-s36-set07", concentricFirst, 2),
            Triple("field-backsquat-wrapping-s36-set01", barbell, 1),
            Triple("field-rdl-wrapping-s36-set05", barbell, 0),
            Triple("field-ohp-prepinflated-s37-set03", concentricFirst, 0),
            Triple("field-ohp-prepinflated-s37-set04", concentricFirst, 0),
        )
        assertEquals(18, corpus.size, "cue-tracked captures covered here")
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
        assertEquals(9, corpus.count { it.third > 0 }, "captures carrying at least one post-Done detection")
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
        assertEquals(16, analysis.reps.size, "detections kept; the lifter performed 12 reps")
        assertEquals(1, analysis.detectionsAfterSetEndCue, "detections dropped")
        // This asserted the kept drives IN THE EXPORT'S OWN FIGURES, which
        // was possible while the analyzer reproduced the export. It no longer
        // does -- see `set 6 no longer reproduces its published export` -- so
        // the four published means are asserted to still be PRESENT among the
        // sixteen rather than to be the whole of them. That is a weaker check
        // and it is labelled as one: what it still catches is the kept list
        // losing one of the drives the app actually showed the lifter.
        assertEquals(
            listOf(0.768, 0.381, 0.654, 0.368),
            published.dropLast(1).map { it.meanConVelMps },
            "the four drives the export published inside the set",
        )
        assertTrue(
            analysis.reps.map { it.meanConVelMps }.containsAll(published.dropLast(1).map { it.meanConVelMps }),
            "every drive the export published inside the set is still kept",
        )
        assertEquals(
            listOf(
                0.768, 0.561, 0.88, 1.02, 1.114, 0.381, 0.654, 0.908,
                0.898, 0.826, 0.61, 0.104, 0.487, 0.62, 0.509, 0.368,
            ),
            analysis.reps.map { it.meanConVelMps },
            "the kept drives",
        )
        // 82.6 uncued against 52.1 cued was the thirty points this test is
        // named for. With issue #94's runaway correction the pair is 88.0
        // against 67.0 -- twenty-one points, over a rep list four times as
        // long. The drop the cue bound produces survives the correction; its
        // size does not.
        assertEquals(67.0, analysis.velocityLossPct, "velocity loss over the reps of the set")
    }

    /**
     * Every committed capture with a cue track, before and after.
     *
     * Five were untouched by the cue-window rule, which is what says it is not
     * simply deleting the last rep of every set. On the four that move, the
     * peak drive velocity of three stops being the sensor being handled:
     * 1.673 to 0.737 m/s, 1.707 to 0.768, 1.261 to 0.910. That is the same
     * defect reaching `summary.peakConVel_mps` instead of `velocityLoss_pct`,
     * and it is why the rule is applied to the rep list rather than to the
     * velocity-loss calculation.
     *
     * SIX of the nine rep counts move with issue #94's runaway correction, and
     * the two that used to land exactly on the count performed no longer do:
     * `-10rep` goes 10 to 11 and the rear delt fly 4 to 16 against 12
     * performed. `BatchCueCoverageTest` is where those are scored per rep --
     * the rear delt fly goes from 2 of 12 marks matched to 12 of 12, with 2
     * doubled and 3 stray, so the count overshooting and the coverage being
     * complete are the same fact seen two ways. The three legcurl `-12rep`
     * cases and the bench `-ok` are unmoved: none of them contains an over-cap
     * run for the correction to reach.
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
            Case("field-legcurl-1030-10rep", legCurl, 11, VelocityLoss.Measured(22.5), 0.910),
            Case("field-ohp-rotating-8rep", barbell, 8, VelocityLoss.Measured(50.9), 1.213),
            Case("field-ohp-rotating-8rep-b", barbell, 7, VelocityLoss.Measured(37.8), 1.250),
            Case("field-bench-rotating-6rep", barbell, 5, VelocityLoss.Measured(31.5), 1.080),
            Case("field-bench-rotating-6rep-ok", barbell, 6, VelocityLoss.Measured(58.3), 0.676),
            Case(fixture, rearDeltFly, 16, VelocityLoss.Measured(67.0), 2.085),
        )
        corpus.forEach { case ->
            val reps = SetAnalyzer.analyze(load("${case.name}.csv"), case.direction, cues = track(case.name)).reps
            assertEquals(case.reps, reps.size, "${case.name} detections kept")
            assertEquals(case.loss, VelocityLoss.of(reps), "${case.name} velocity loss")
            assertEquals(case.peakConVelMps, reps.maxOf { it.peakConVelMps }, "${case.name} peak drive velocity")
        }
    }

    /**
     * THE FIXTURE NO LONGER REPRODUCES THE SHIPPED EXPORT, and that is issue
     * #94's cost recorded where it falls.
     *
     * This test asserted, rep for rep, that analysing the committed stream
     * reproduced the five rows session 32 set 6 actually published -- the pin
     * that licensed every other claim made from this capture. Issue #94's
     * runaway correction resolves SEVENTEEN detections on the same stream, so
     * the analyzer that wrote that export and the analyzer in this tree are
     * different analyzers and no assertion can make them agree.
     *
     * What survives, and is asserted below, is the other half: the [published]
     * rows stay in this file as the archive's own record of what the lifter was
     * shown, and the fixture is still that set's stream. What is GONE is the
     * check that the pipeline reproduces it. Nothing else in this repository
     * replaces that, and issue #94's report says so rather than leaving it to
     * be discovered.
     *
     * It is also still the analysis of an UNCUED set -- no cue track is passed
     * -- so it states the other half of the window rule at the same time: with
     * nothing on the record saying when the set ended, no boundary is invented
     * and every detection is kept, including the ones that are plainly post-set
     * handling.
     */
    @Test
    fun `set 6 no longer reproduces its published export, and this is what it resolves`() {
        val analysis = SetAnalyzer.analyze(load("$fixture.csv"), rearDeltFly, loadKg = loadKg)
        assertEquals(5, published.size, "the rows the app published in the field, unchanged as a record")
        assertEquals(17, analysis.reps.size, "detections resolved; the lifter performed 12 reps")
        // The whole rep list, so a later change to it cannot pass unnoticed
        // now that the export comparison is gone.
        assertEquals(
            listOf(
                0.918, 0.559, 0.833, 0.729, 0.829, 0.203, 1.835, 0.758, 0.768,
                0.690, 1.000, 0.110, 0.446, 0.655, 0.368, 1.930, 0.679,
            ),
            analysis.reps.map { it.romM },
            "ROM per detection, metres",
        )
        // 82.6% was the published figure and this file's own reproduction of
        // it. It is now 88.0%, over a rep list three times as long.
        assertEquals(88.0, analysis.velocityLossPct, "velocityLoss_pct, against 82.6 published")
        assertEquals(
            2.085,
            analysis.reps.maxOf { it.peakConVelMps },
            "peak drive velocity, unmoved: the handling spike is still the maximum",
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
     * Sixteen of seventeen start before the cue. The last starts 3.479 s after
     * it, and the sixteenth starts 1.829 s before it while ending 3.389 s
     * after -- the straddling case, which is the one the rule has to make a
     * decision about. Both of those instants are unmoved by issue #94's
     * runaway correction; what it adds is twelve detections between them.
     */
    @Test
    fun `sixteen of set 6's seventeen detected drives begin before the Done cue`() {
        val samples = load("$fixture.csv")
        val doneMs = CueTrack.read(fixture).first { it.label == "Done" }.timestampMs
        val series = VelocityEstimator.estimate(samples, DspConfig(), rearDeltFly.measuredPlane)
            .mappedToLifter(rearDeltFly.sensorToLifter)
        val spans = RepSegmenter.segment(series, rearDeltFly, DspConfig())
        assertEquals(
            listOf(
                -50247L, -47069L, -42838L, -38971L, -35098L, -33778L, -32731L, -27297L,
                -23579L, -19467L, -15808L, -13016L, -11221L, -7351L, -3449L, -1829L, 3479L,
            ),
            spans.map { samples[it.conStartIdx].timestampMs - doneMs },
            "drive start relative to Done, ms",
        )
        assertEquals(
            listOf(
                -49081L, -46111L, -41909L, -38279L, -34351L, -33237L, -29909L, -26427L,
                -22738L, -18661L, -14190L, -11939L, -10315L, -6299L, -2729L, 3389L, 8550L,
            ),
            spans.map { samples[it.conEndIdx].timestampMs - doneMs },
            "drive end relative to Done, ms",
        )
    }
}
