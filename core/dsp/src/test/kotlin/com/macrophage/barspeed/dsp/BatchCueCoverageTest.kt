package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.StartPhase
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the BATCH analyzer resolves, scored rep by rep against the metronome's
 * own marks. Issue #94.
 *
 * ## Why this file exists beside [CuedRepCoverageTest]
 *
 * [CuedRepCoverageTest] scores the LIVE tracker -- the counter the lifter
 * watches during the set -- by rebuilding its private run state from the
 * velocity it publishes. Nothing scored the batch path the same way. The batch
 * path is the one that writes the export, the history and the coach-facing
 * figures, and it is the path the owner's standing preference points at:
 * capture faithfully, denoise offline.
 *
 * The two files share the WINDOW RULE and nothing else. A window is one median
 * cue cycle wide, opening at every `Down` on the track, with the cycle taken
 * from that track's own `Down`-to-`Down` gaps so it follows the tempo the set
 * was paced at. That rule is duplicated here rather than shared because the
 * two files are scoring different producers and a shared helper would make a
 * change to one silently rescore the other.
 *
 * ## Four numbers, not one
 *
 * A rep total cannot evaluate a change in this area: over-counting and
 * under-counting partially cancel, so a candidate that improves segmentation
 * can degrade the total and a candidate that improves the total can do it by
 * adding junk. Issue #94 records that finding at length. So every row carries:
 *
 * - **matched** -- windows with a detection in them.
 * - **empty** -- windows with none.
 * - **doubled** -- detections beyond the first inside one window. One rep
 *   reported twice.
 * - **stray** -- detections inside no window at all.
 *
 * A mark is a rep the metronome CALLED, which is not always a rep the lifter
 * performed. `field-ohp-3010-6rep-s37-set02` is a failed set: the metronome
 * called the planned 8 against 6 performed -- `CuedRepCoverageTest` pins
 * `cued = 8` beside `performed = 6` -- and this file scores 8 marks for it.
 * 188 of this corpus's 190 marks have a performed rep behind them; those two
 * do not. So an empty window is one the analyzer published nothing in, which
 * on every mark but those two is also a rep it missed.
 *
 * `matched + empty` is always the mark count. `spans` is what the lifter's
 * screen and the export show, and it is deliberately NOT the headline: a set
 * can publish the right total while matching half its windows.
 *
 * ## A cue is an instruction, not a measurement
 *
 * See [CueTrack]. The track records what the app told the lifter to do, not
 * what the bar did. The lifter was following the metronome so the two are
 * closely coupled, and "closely coupled" is exactly the claim that hardens
 * into "identical" over a few rounds. Nothing here may be read as ground truth
 * about bar position; it is ground truth about when a rep was CALLED.
 *
 * ## The assignment rule, and the artefact it avoids
 *
 * Detections are assigned to windows by the arrival timestamp of the sample
 * their DRIVE STARTS on. Issue #94 records an earlier pass that assigned by the
 * END of the completing run and produced a striking alternating pattern of
 * double- and under-counting -- an artefact of reps completing near a window
 * boundary. `the headline does not rest on which end of a detection is
 * assigned` scores the corpus under the start, midpoint and end rules. The
 * three disagree per-capture on nineteen of the twenty-two captures here, and
 * the matched total moves thirteen windows of 190 across them. This read
 * "seventeen of the twenty" and "twelve windows of 170"; that is the corpus
 * before session 38's two captures, and it is still what `origin/main`
 * measures at 2f7efa5f7cbcafec9c1ffcb34afda43ef01d09f9 over its own twenty --
 * 147, 141 and 135 matched.
 *
 * Arrival timestamps, not the DSP's reconstructed clock: `VelocityEstimator`
 * places sample i at `i * dt`, which drifts from arrival by up to
 * [CueTrack.MAX_SKEW_MS] mid-set. [CueTrack.WINDOW_TOLERANCE_MS] is the
 * tolerance and it is derived from that skew.
 */
class BatchCueCoverageTest {
    /** The training families a gap is reported per; see `the gap per family`. */
    private enum class Family {
        BARBELL_UPPER,
        BARBELL_LOWER,
        MACHINE_LOWER,
        ACCESSORY,
        BODYWEIGHT_UPPER,

        /**
         * Stack-mounted upper-body pulling. Added with session 38's lat
         * pulldown rather than folded into ACCESSORY: issue #72 is a
         * statement about the MOUNT, and the corpus's only other
         * stack-mounted captures with cue tracks are the four leg curls, so
         * one more accessory row would have hidden a second stack exercise
         * inside a family whose other members are all the same machine.
         */
        MACHINE_UPPER,
    }

    private data class Scored(val fixture: String, val direction: LiftDirection, val family: Family)

    private fun load(n: String): List<ImuSample> = ImuCsv.decode(
        javaClass.getResourceAsStream("/$n.csv")!!.readBytes().decodeToString(),
    )

    /**
     * The seated leg curl the four `field-legcurl-1030-*` captures were
     * recorded on: concentric-first, drive DOWN, stack-mounted, inverted.
     *
     * NOT session 26, which is what this line said until now, and the error
     * was mine. The three 12-rep captures are 2026-08-18 on app 0.1.39
     * ([LegCurlCueTrackTest] states both) and `field-legcurl-1030-10rep` is
     * session 31 set 11 ([VelocityLossTest] states it, citing issue #126).
     * Session 26 is 2026-08-17 and is where this corpus's four barbell
     * captures come from ([BarbellCueTrackTest]).
     */
    private val legCurl = LiftDirection(
        startsWith = StartPhase.CONCENTRIC,
        concentricUp = false,
        sensorInverted = true,
        plane = MovementPlane.VERTICAL,
        sensorOnStack = true,
    )

    /** Session 32 set 6's exported geometry block, as [CuedRepCoverageTest] reads it. */
    private val rearDeltFly = LiftDirection(startsWith = StartPhase.CONCENTRIC)

    /** Session 38 set 14's declared geometry: lat pulldown, drive DOWN, on the stack. */
    private val latPulldown = LiftDirection(
        startsWith = StartPhase.CONCENTRIC,
        concentricUp = false,
        sensorInverted = true,
        plane = MovementPlane.VERTICAL,
        sensorOnStack = true,
    )

    private val eccFirst = LiftDirection(startsWith = StartPhase.ECCENTRIC)

    private val conFirst = LiftDirection(startsWith = StartPhase.CONCENTRIC)

    /**
     * The twenty-two captures carrying a cue track with rep calls on it, with
     * the geometry each set declared. The directions are
     * [CuedRepCoverageTest]'s, read from the same session archives.
     *
     * The last two are session 38 (2026-09-04, app 0.1.50, WitMotion
     * WT901BLECL), added for issue #72 because that issue is a claim about the
     * MOUNT and no committed session carried a metronome track on a capture of
     * each mount. This paragraph used to say "no committed session held both
     * mounts", which is false and is deleted: session 26 holds both already --
     * four barbell captures and three cable ones -- but its cable captures
     * have no `-cues.csv`, so nothing could score the split per mark. See
     * `the gap by sensor mount, which is what issue 72 named`.
     *
     * WHAT WAS LEFT BEHIND, so the two committed here cannot read as the
     * session. Session 38 recorded EIGHTEEN sets, every one carrying a cue
     * track, sixteen of them dynamic and two rope dead hangs -- counted off
     * `field-38/extracted` in the owner's capture directory, which is not in
     * this repository. Two are committed here (four files: two `imu-a`
     * streams and their tracks); a further two, set 5 and set 2, are on the
     * classpath already from issue #245. The other fourteen are on no
     * classpath and are not scored by anything.
     *
     * WHY THESE TWO. Issue #72's claim is a mount split on the seated
     * overhead press, so one capture of each mount from ONE session was the
     * smallest pair that holds day, lifter, sensor and firmware fixed: set 4
     * is the seated overhead press the issue is named after with
     * `sensorOnStack` false, and set 14 is the session's FIRST lat pulldown,
     * declaring `sensorOnStack` true. The session's other stack-mounted work
     * is two triceps pushdowns and the two later lat pulldowns, sets 15 and
     * 16, which repeat set 14; set 5 repeats the press and is committed on
     * `origin/main` for #245, sitting in `notRepCorpus` below rather than
     * being scored here. Picking the first of a repeated exercise rather than
     * its best set is deliberate and is not a claim that the others agree.
     *
     * WHAT NEITHER OF THEM COVERS. Both captures added for #72 are
     * CONCENTRIC-FIRST, so neither exercises this branch's own eccentric-first
     * `loweredSince` fallback in `RepSegmenter.pairEccentricFirst` -- the
     * largest production change on the branch. The field coverage for that
     * fallback comes from a capture this branch did not add:
     * `field-inclinepress-3010-12rep-s38-set02`, eccentric-first 3010,
     * committed on `origin/main` for #245 and scored in
     * `the notRepCorpus captures the fallback moves, and what each is`.
     *
     * BOTH SETS ARE TWO-SENSOR CAPTURES and only role a is committed. Each
     * declares `sensorsArmed` 2 and `sensorRolesExpected` [a, b], each wrote a
     * role-b IMU file, and each set's `analysedRole` is a. So every figure
     * below and every figure this file scores is one of two streams recorded
     * simultaneously, and the role-b stream is not on the classpath for
     * anything here to cross-check against. The b files are NOT committed and
     * nothing in this round measured them beyond the summary each reports.
     *
     * Every field below is read from that session's own `meta.json`:
     *
     * - `field-ohp-3010-8rep-s38-set04` -- set 4, seated_overhead_press, tempo
     *   3010, 13.607771100301063 kg (30.0 lb), 8 performed of 8 planned,
     *   RPE 7, concentric-first, drive up, `sensorOnStack` false,
     *   `travelRatio` 1.0, 4988 samples at 99.36242279338514 Hz, roll
     *   excursion 360.0 deg, `analysedRole` a. This is the exercise issue #72
     *   is named after. Its role-b sensor reports `rollExcursion_deg` 360.0
     *   as well, over 4988 samples at 99.35846350015939 Hz. On the 360 itself
     *   see [GyroGateTest]: it is a wraparound artifact, not a rotation.
     * - `field-latpulldown-1120-12rep-s38-set14` -- set 14, lat_pulldown, tempo
     *   1120, 34.019427750752655 kg (75.0 lb), 12 performed of 12 planned,
     *   RPE 6, concentric-first, drive DOWN, `sensorOnStack` and
     *   `sensorInverted` both true, `travelRatio` 1.0. Its role-b sensor
     *   reports `rollExcursion_deg` 17.7 against role a's 1.3, over 6400
     *   samples at 99.34946979459392 Hz -- so "nothing rotates on this set"
     *   is a statement about the committed stream and not about the set.
     *   Role a is 6404 samples at
     *   99.36374922408443 Hz, roll excursion 1.3 deg, `analysedRole` a.
     */
    private val scored = listOf(
        Scored("field-ohp-rotating-8rep", eccFirst, Family.BARBELL_UPPER),
        Scored("field-ohp-rotating-8rep-b", eccFirst, Family.BARBELL_UPPER),
        Scored("field-bench-rotating-6rep-ok", eccFirst, Family.BARBELL_UPPER),
        Scored("field-bench-rotating-6rep", eccFirst, Family.BARBELL_UPPER),
        Scored("field-ohp-3010-6rep-s37-set02", conFirst, Family.BARBELL_UPPER),
        Scored("field-bench-3010-6rep-s37-set05", eccFirst, Family.BARBELL_UPPER),
        Scored("field-bench-3010-6rep-s37-set06", eccFirst, Family.BARBELL_UPPER),
        Scored("field-backsquat-99hz-6rep", eccFirst, Family.BARBELL_LOWER),
        Scored("field-rdl-3010-10rep", eccFirst, Family.BARBELL_LOWER),
        Scored("field-rdl-3010-10rep-s36-set05", eccFirst, Family.BARBELL_LOWER),
        Scored("field-backsquat-4011-6rep-s36-set01", eccFirst, Family.BARBELL_LOWER),
        Scored("field-legpress-2010-8rep", eccFirst, Family.MACHINE_LOWER),
        Scored("field-legpress-single-2010-8rep", conFirst, Family.MACHINE_LOWER),
        Scored("field-legpress-single-2011-8rep-s36-set07", conFirst, Family.MACHINE_LOWER),
        Scored("field-legcurl-1030-12rep", legCurl, Family.ACCESSORY),
        Scored("field-legcurl-1030-12rep-b", legCurl, Family.ACCESSORY),
        Scored("field-legcurl-1030-12rep-c", legCurl, Family.ACCESSORY),
        Scored("field-legcurl-1030-10rep", legCurl, Family.ACCESSORY),
        Scored("field-reardeltfly-s32-set06", rearDeltFly, Family.ACCESSORY),
        Scored("field-pullup-3010-8rep-s37-set09", conFirst, Family.BODYWEIGHT_UPPER),
        Scored("field-ohp-3010-8rep-s38-set04", conFirst, Family.BARBELL_UPPER),
        Scored("field-latpulldown-1120-12rep-s38-set14", latPulldown, Family.MACHINE_UPPER),
    )

    /**
     * Captures with no per-rep truth for this file to score against, each for a
     * stated reason. Nothing is here because it was inconvenient.
     *
     * - no `-cues.csv` is committed beside the capture at all: the first nine.
     * - `field-ropedeadhang-hold20-s37-set11` HAS a committed track, and the
     *   track calls no reps -- it is a twenty-second hold, `kind: "hold"`,
     *   `reps: 0` in its own `meta.json`. There is no `Down`, so there is no
     *   window to open. It is scored by `a hold and two no-rep controls, and
     *   what each resolves on the phase it declares` below instead, which is
     *   the assertion that matters for it.
     */
    private val notScored = listOf(
        "field-assistedpullup-3010-s37-set08",
        "field-assistedpullup-3010-s37-set09",
        "field-assistedpullup-3010-s37-set10",
        "field-backsquat-10hz",
        "field-backsquat-10hz-set5",
        "field-cablerow-static-8rep",
        "field-facepull-static-12rep",
        "field-ohp-100hz-bursty",
        "field-pallof-static-12rep",
        "field-rdl-3010-10rep-s36-set04",
        "field-seated-ohp-2rep",
        "field-still-0rep",
        "field-ropedeadhang-hold20-s37-set11",
        // Committed for issue #125. Its cue track is deliberately not
        // committed, on the terms `CuedRepCoverageTest` states for it and
        // for field-rdl-3010-10rep-s36-set04.
        "field-ohp-3010-8rep-s37-set01",
    )

    /**
     * Committed for a purpose other than cue coverage, so out of every figure
     * above rather than scored badly by it.
     *
     * Four arrived with issue #133 for a rotation measure that reads roll
     * only, and two with issue #245 for a head-of-stream bound. All six
     * carry cue tracks, so `notScored` would be a false statement about them
     * -- that list's own reason is "no track, or a track that calls no rep"
     * -- and scoring them would silently move the corpus totals #87 and #94
     * measured. [CuedRepCoverageTest] holds the same six in its own
     * `notRepCorpus` for the same reason; the two lists exist so
     * the classpath partition stays TOTAL, which is what makes the coverage
     * guard below able to catch the next capture nobody classified.
     *
     * Being out of every figure above does not make them unaffected: the
     * slow-eccentric fallback moves TWO of the six, and
     * `the notRepCorpus captures the fallback moves, and what each is` is what
     * keeps that from being an unpinned claim in a commit body. It said ONE of
     * the FOUR, measured before this branch was rebased onto `origin/main`,
     * which had committed two more of these six meanwhile; the second mover is
     * one of that pair.
     */
    private val notRepCorpus = listOf(
        "field-backsquat-wrapping-s36-set01",
        "field-inclinepress-3010-12rep-s38-set02",
        "field-ohp-3010-8rep-s38-set05",
        "field-ohp-prepinflated-s37-set03",
        "field-ohp-prepinflated-s37-set04",
        "field-rdl-wrapping-s36-set05",
    )

    /** One median cue cycle per window, opening at each `Down`. */
    private fun windows(fixture: String): List<Pair<Long, Long>> {
        val downs = CueTrack.movement(fixture, "Down")
        val gaps = downs.zipWithNext { a, b -> b - a }.sorted()
        val cycleMs = gaps[gaps.size / 2]
        return downs.map { it to it + cycleMs }
    }

    /** matched, empty, doubled, stray. */
    private data class Coverage(val matched: Int, val empty: Int, val doubled: Int, val stray: Int) {
        operator fun plus(other: Coverage) = Coverage(
            matched + other.matched,
            empty + other.empty,
            doubled + other.doubled,
            stray + other.stray,
        )
    }

    /** The spans the shipped batch path resolves, unbounded by any set-end cue. */
    private fun spans(s: Scored): List<RepSpan> {
        val config = DspConfig()
        val samples = load(s.fixture)
        val series = VelocityEstimator.estimate(samples, config, s.direction.measuredPlane)
            .mappedToLifter(s.direction.sensorToLifter)
        return RepSegmenter.segment(series, s.direction, config)
    }

    private fun cover(s: Scored, at: (RepSpan) -> Int): Coverage {
        val samples = load(s.fixture)
        val w = windows(s.fixture)
        val tolerance = CueTrack.WINDOW_TOLERANCE_MS.toLong()
        val hits = IntArray(w.size)
        var stray = 0
        spans(s).forEach { span ->
            val t = samples[at(span)].timestampMs
            val k = w.indexOfFirst { (a, b) -> t >= a - tolerance && t < b + tolerance }
            if (k >= 0) hits[k]++ else stray++
        }
        return Coverage(
            hits.count { it >= 1 },
            hits.count { it == 0 },
            hits.sumOf { maxOf(0, it - 1) },
            stray,
        )
    }

    private fun coverage(s: Scored): Coverage = cover(s) { it.conStartIdx }

    @Test
    fun `spans against the metronome's marks, capture by capture`() {
        // Rows are (marks, spans, matched, empty, doubled, stray). CHARACTERIZATION:
        // this records what the batch path resolves at this commit and says
        // nothing about whether it is right. Read the empty column first --
        // that is reps the lifter performed, the metronome called, and the
        // analyzer published nothing for.
        val expected = mapOf(
            "field-ohp-rotating-8rep" to listOf(8, 8, 8, 0, 0, 0),
            "field-ohp-rotating-8rep-b" to listOf(8, 8, 7, 1, 1, 0),
            "field-bench-rotating-6rep-ok" to listOf(6, 6, 6, 0, 0, 0),
            "field-bench-rotating-6rep" to listOf(6, 5, 5, 1, 0, 0),
            "field-ohp-3010-6rep-s37-set02" to listOf(8, 9, 7, 1, 0, 2),
            "field-bench-3010-6rep-s37-set05" to listOf(6, 5, 5, 1, 0, 0),
            "field-bench-3010-6rep-s37-set06" to listOf(6, 6, 5, 1, 0, 1),
            "field-backsquat-99hz-6rep" to listOf(6, 7, 6, 0, 0, 1),
            "field-rdl-3010-10rep" to listOf(10, 11, 10, 0, 0, 1),
            "field-rdl-3010-10rep-s36-set05" to listOf(10, 11, 10, 0, 0, 1),
            "field-backsquat-4011-6rep-s36-set01" to listOf(6, 9, 5, 1, 2, 2),
            "field-legpress-2010-8rep" to listOf(8, 7, 7, 1, 0, 0),
            "field-legpress-single-2010-8rep" to listOf(8, 8, 7, 1, 1, 0),
            "field-legpress-single-2011-8rep-s36-set07" to listOf(8, 10, 8, 0, 0, 2),
            "field-legcurl-1030-12rep" to listOf(12, 12, 9, 3, 2, 1),
            "field-legcurl-1030-12rep-b" to listOf(12, 13, 11, 1, 2, 0),
            "field-legcurl-1030-12rep-c" to listOf(12, 11, 10, 2, 0, 1),
            "field-legcurl-1030-10rep" to listOf(10, 12, 8, 2, 2, 2),
            "field-reardeltfly-s32-set06" to listOf(12, 17, 12, 0, 2, 3),
            "field-pullup-3010-8rep-s37-set09" to listOf(8, 6, 4, 4, 0, 2),
            "field-ohp-3010-8rep-s38-set04" to listOf(8, 9, 8, 0, 0, 1),
            "field-latpulldown-1120-12rep-s38-set14" to listOf(12, 14, 10, 2, 2, 2),
        )
        val actual = scored.associate { s ->
            val c = coverage(s)
            s.fixture to listOf(windows(s.fixture).size, spans(s).size, c.matched, c.empty, c.doubled, c.stray)
        }
        assertEquals(expected, actual, "marks, spans, matched, empty, doubled, stray")
        // matched + empty is the mark count by construction -- both are counts
        // over one hits array sized from the same windows() call -- so this
        // assertion CANNOT FAIL. It is a readability aid for the table above,
        // not a guard, and it was described as one until issue #94's round 3.
        actual.forEach { (fixture, row) ->
            assertEquals(row[0], row[2] + row[3], "$fixture: matched + empty must be the mark count")
        }
    }

    @Test
    fun `the gap per family`() {
        // The corpus does not weigh the families equally -- 58 marks of
        // accessory work against 8 of bodyweight -- so a corpus total hides
        // which kind of training is served. Rows are
        // (marks, spans, matched, empty, doubled, stray).
        val expected = mapOf(
            Family.BARBELL_UPPER to listOf(56, 56, 51, 5, 1, 4),
            Family.BARBELL_LOWER to listOf(32, 38, 31, 1, 2, 5),
            Family.MACHINE_LOWER to listOf(24, 25, 22, 2, 1, 2),
            Family.ACCESSORY to listOf(58, 65, 50, 8, 8, 7),
            Family.BODYWEIGHT_UPPER to listOf(8, 6, 4, 4, 0, 2),
            Family.MACHINE_UPPER to listOf(12, 14, 10, 2, 2, 2),
        )
        val actual = scored.groupBy { it.family }.mapValues { (_, group) ->
            val marks = group.sumOf { windows(it.fixture).size }
            val total = group.map { coverage(it) }.reduce(Coverage::plus)
            listOf(marks, group.sumOf { spans(it).size }, total.matched, total.empty, total.doubled, total.stray)
        }
        assertEquals(expected, actual, "per family: marks, spans, matched, empty, doubled, stray")
        // And the corpus, which is the one figure issue #94's field-36 and
        // field-37 comments quote.
        assertEquals(190, actual.values.sumOf { it[0] }, "metronome marks across the twenty-two scored captures")
        assertEquals(204, actual.values.sumOf { it[1] }, "spans the batch analyzer publishes over them")
        assertEquals(168, actual.values.sumOf { it[2] }, "marks with at least one detection")
        assertEquals(22, actual.values.sumOf { it[3] }, "marks with none -- called and not published")
    }

    @Test
    fun `a hold and two no-rep controls, and what each resolves on the phase it declares`() {
        // The guard against buying coverage with phantoms. All three are
        // captures where the right answer is zero, and two of the three carry a
        // sensor mount this corpus otherwise has one example of.
        //
        // None of these three contains a runaway -- `RunawayDriftTest` names
        // all three in its untouched list -- so this test is bit-identical
        // through the correction and cannot detect a phantom this change
        // creates. The only zero-truth case the correction touches is the
        // constructed run in RunawayDriftTest; no committed capture provides
        // one.
        //
        // field-ropedeadhang-hold20-s37-set11 is session 37 set 11: rope dead
        // hang, kind "hold", reps 0, twenty seconds, sensor on a strap. It is
        // the STRAP control -- the mount AnchorSupplyByMountTest contrasts the
        // barbell against -- and the only committed capture of a lift where
        // nothing is meant to be counted and the implement is nonetheless
        // loaded and moving slightly.
        //
        // The phase each is scored on is the phase its own archive declares.
        // field-backsquat-10hz-set5 resolves ONE detection where the lifter
        // performed none; the other two resolve nothing.
        val declared = listOf(
            Triple("field-ropedeadhang-hold20-s37-set11", eccFirst, 0),
            Triple("field-still-0rep", eccFirst, 0),
            Triple("field-backsquat-10hz-set5", eccFirst, 1),
        )
        declared.forEach { (fixture, direction, reps) ->
            assertEquals(
                reps,
                SetAnalyzer.analyze(load(fixture), direction).reps.size,
                "$fixture on its declared phase",
            )
        }
        // The OTHER phase, pinned because it is not zero everywhere and reading
        // the line above as "these captures cannot produce a rep" would be
        // wrong. Scored concentric-first, the dead hang publishes two and the
        // rack capture two. Neither is the phase the set declared, so neither
        // reaches a lifter -- but a change that moved these and not the ones
        // above would be a change nothing else in this file could see.
        listOf(
            "field-ropedeadhang-hold20-s37-set11" to 2,
            "field-still-0rep" to 0,
            "field-backsquat-10hz-set5" to 2,
        ).forEach { (fixture, reps) ->
            assertEquals(
                reps,
                SetAnalyzer.analyze(load(fixture), conFirst).reps.size,
                "$fixture scored on the phase it does not declare",
            )
        }
    }

    @Test
    fun `the notRepCorpus captures the fallback moves, and what each is`() {
        // Issue #72 rounds 1 and 2. TWO of these six are moved by the
        // slow-eccentric fallback. The note here said ONE of four; that was
        // measured while this branch was 66 commits behind main, and the
        // rebase onto 2f7efa5f7cbcafec9c1ffcb34afda43ef01d09f9 brought in two
        // more captures, one of which moves.
        //
        // `field-rdl-wrapping-s36-set05` moves because it is the SAME BYTES as
        // `field-rdl-3010-10rep-s36-set05`, which the cost table above already
        // accounts for. Byte equality rather than a matching rep count: two
        // files that happen to agree today would let the duplicate silently
        // diverge.
        //
        // `field-inclinepress-3010-12rep-s38-set02` is the one genuinely new
        // thing the rebase brought. It is a dumbbell incline press at tempo
        // 3010, ECCENTRIC-FIRST, committed on `origin/main` for issue #245 --
        // and it is the only committed capture that gives this branch's
        // eccentric-first `loweredSince` fallback any field coverage at all,
        // because both captures #72 added are concentric-first. Its figures
        // are pinned in [PrepDetectionFieldTest], which reads it with its cue
        // track and its work-start instant; what is pinned here is only the
        // unbounded span count, so the two files cannot drift into agreeing by
        // construction.
        //
        // The remaining three do not move: both `field-ohp-prepinflated-*`
        // sets are recorded concentric-first in their own session archive
        // (SetEndWindowTest reads the same directions off it), so
        // `pairEccentricFirst` is never reached on them, and
        // `field-backsquat-wrapping-s36-set01` is eccentric-first and resolves
        // no orphan drive at all. `field-ohp-3010-8rep-s38-set05` is
        // concentric-first.
        val duplicate = load("field-rdl-wrapping-s36-set05")
        val scoredTwin = load("field-rdl-3010-10rep-s36-set05")
        assertEquals(scoredTwin, duplicate, "the two files are the same capture under two names")
        // And what each resolves, eccentric-first, which is the phase both
        // declare. 11 against a hand count of 10 -- the eleventh is the
        // detection the cost table names on the twin.
        val spans = RepSegmenter.segment(
            VelocityEstimator.estimate(duplicate, DspConfig(), eccFirst.measuredPlane)
                .mappedToLifter(eccFirst.sensorToLifter),
            eccFirst,
            DspConfig(),
        )
        assertEquals(11, spans.size, "spans on the duplicate")
        assertEquals(spans.size, spans(Scored("field-rdl-3010-10rep-s36-set05", eccFirst, Family.BARBELL_LOWER)).size)
        // The incline press, unbounded by cue or instant: 13 spans where
        // `origin/main` resolves 11, against a hand count of 12.
        assertEquals(
            13,
            spans(Scored("field-inclinepress-3010-12rep-s38-set02", eccFirst, Family.BARBELL_UPPER)).size,
            "spans on the eccentric-first incline press the fallback moves",
        )
        // And the three that do not move, so a change that started moving one
        // of them cannot pass as unchanged.
        assertEquals(
            mapOf(
                "field-backsquat-wrapping-s36-set01" to 7,
                "field-ohp-prepinflated-s37-set03" to 11,
                "field-ohp-prepinflated-s37-set04" to 7,
                "field-ohp-3010-8rep-s38-set05" to 15,
            ),
            mapOf(
                "field-backsquat-wrapping-s36-set01" to
                    spans(Scored("field-backsquat-wrapping-s36-set01", eccFirst, Family.BARBELL_LOWER)).size,
                "field-ohp-prepinflated-s37-set03" to
                    spans(Scored("field-ohp-prepinflated-s37-set03", conFirst, Family.BARBELL_UPPER)).size,
                "field-ohp-prepinflated-s37-set04" to
                    spans(Scored("field-ohp-prepinflated-s37-set04", conFirst, Family.BARBELL_UPPER)).size,
                "field-ohp-3010-8rep-s38-set05" to
                    spans(Scored("field-ohp-3010-8rep-s38-set05", conFirst, Family.BARBELL_UPPER)).size,
            ),
            "spans on the four notRepCorpus captures the fallback leaves alone",
        )
    }

    @Test
    fun `the headline does not rest on which end of a detection is assigned`() {
        // Issue #94 records a per-rep measurement that had to be withdrawn
        // because it assigned reps to windows by the END of the completing run,
        // which put reps finishing near a boundary in their neighbour's window
        // and produced a tidy-looking alternation that was an artefact. The
        // defence is to score under every rule and publish the spread.
        //
        // A CORRECTION THIS FILE OWES THAT MEASUREMENT. Its method note said
        // the start rule and the midpoint rule "agree with each other and
        // disagree with" the end rule. That claim is withdrawn in its own
        // file too: start and midpoint differ on
        // field-legcurl-1030-12rep-c. It is more wrong here, where a span is
        // a run seconds long rather than an instant: the three rules disagree
        // per-capture on NINETEEN of the twenty-two captures here --
        // "disagree" meaning the (matched, empty, doubled, stray) tuple is not
        // identical under all three -- measured at this commit by running the
        // three rules over `scored` and comparing the tuples. The nine this
        // note used to claim was measured on a corpus that has since moved and
        // is withdrawn; so is the SEVENTEEN that replaced it, which was true
        // over the twenty captures scored before session 38's two arrived.
        //
        // field-bench-3010-6rep-s37-set05 is the clearest single case. It
        // resolves FOUR detections, not the one this note used to claim, and
        // the longest of them runs 4.29 s. Its drive starts inside the last
        // window; its midpoint falls 226 ms past that window's close, which
        // WINDOW_TOLERANCE_MS of 150 ms does not cover, and there is no
        // window after it -- so the start rule matches it and the midpoint
        // rule scores it a stray. Measured at this commit from the span
        // timestamps and the window bounds this file computes.
        //
        // What survives is the corpus figure, which is what the issue quotes:
        // the three rules put the matched total within THIRTEEN windows of
        // each other -- 168, 163 and 155 in the map below, and the assertTrue
        // bound beneath it reads <= 14 -- which is 6.8% of 190. The sentence
        // "it was exactly 14 of 170 before session 38's two captures arrived"
        // stood here and is DELETED rather than reworded: at
        // 2f7efa5f7cbcafec9c1ffcb34afda43ef01d09f9 the same three rules over
        // main's own twenty captures give 147, 141 and 135, a spread of TWELVE
        // of 170, so the 14 described no tree either side of the rebase. The
        // bound is left at 14 rather than tightened onto the new measurement.
        // That is a real spread and not the 2.4% this note used to claim, so
        // the choice of
        // rule is stated rather than waved away: the start rule is used
        // because a rep begins when the drive begins, and every figure in this
        // file is under it.
        val byRule = mapOf(
            "start" to scored.map { s -> cover(s) { it.conStartIdx } },
            "midpoint" to scored.map { s -> cover(s) { (it.conStartIdx + it.conEndIdx) / 2 } },
            "end" to scored.map { s -> cover(s) { it.conEndIdx } },
        ).mapValues { (_, rows) -> rows.reduce(Coverage::plus) }
        assertEquals(
            mapOf(
                "start" to Coverage(168, 22, 14, 22),
                "midpoint" to Coverage(163, 27, 17, 24),
                "end" to Coverage(155, 35, 24, 25),
            ),
            byRule,
            "corpus coverage under each assignment rule",
        )
        val matched = byRule.values.map { it.matched }
        assertTrue(
            matched.max() - matched.min() <= 14,
            "the assignment rule moves the matched total by ${matched.max() - matched.min()} windows of 190",
        )
    }

    // ------------------------------------------------------------------
    // Why each empty window is empty, and where the mount split went.
    // Issue #72.
    // ------------------------------------------------------------------

    /**
     * The movement runs the dead band alone cuts, BEFORE the peak, duration
     * and displacement gates demote any of them.
     *
     * Rebuilt here rather than read off [RepSegmenter], because the shipped
     * classifier returns only what survived and the question below is about
     * what did not. It is the same three-way comparison against
     * [RunThresholds.pauseBandMps] and nothing else, so the only way it can
     * drift from the shipped one is if that comparison changes -- in which
     * case `every empty window is attributed to a mechanism` stops
     * reproducing the empty column and says so.
     */
    private fun rawMovementRuns(series: VelocitySeries, t: RunThresholds): List<Run> {
        val v = series.velocityMps
        val sign = IntArray(series.size) {
            when {
                v[it] > t.pauseBandMps -> 1
                v[it] < -t.pauseBandMps -> -1
                else -> 0
            }
        }
        val runs = mutableListOf<Run>()
        var start = 0
        for (i in 1..series.size) {
            if (i == series.size || sign[i] != sign[start]) {
                if (sign[start] != 0) {
                    runs += Run(if (sign[start] == 1) RunType.UP else RunType.DOWN, start, i - 1)
                }
                start = i
            }
        }
        return runs
    }

    /**
     * Why one window of the metronome's track carries no detection.
     *
     * Five outcomes, in the order the pipeline reaches them, so the name says
     * how far the rep got before it was lost:
     *
     * - [Loss.NO_DRIVE_RUN] -- nothing of the drive's sign is in the window at
     *   all. The velocity series never showed the lifter driving. Upstream of
     *   every gate; a segmenter change cannot reach it.
     * - [Loss.DEMOTED] -- a drive-sign run is there and `classifyRuns` demoted
     *   it to stillness for failing [RunThresholds.startThresholdMps] or
     *   [RunThresholds.minPhaseS].
     * - [Loss.BELOW_MIN_ROM] -- the drive run cleared every gate and then
     *   displaced less than [RunThresholds.minRomM]. `DspConfig.minRomM`'s own
     *   KDoc says what these are: reps whose displacement RECONSTRUCTION
     *   failed, not reps that were small. Lowering the floor to admit them
     *   publishes velocities taken from the same broken reconstruction, so
     *   this bucket is not addressable in the segmenter either.
     * - [Loss.PAIRING] -- a fully qualifying drive run of a rep's size sits in
     *   the window and the segmenter published nothing for it. The only bucket
     *   where the signal is good and the loss is a rule.
     * - [Loss.UNATTRIBUTED] -- none of the above matched. Zero is the pinned
     *   value; any other is this classifier having gone stale against the
     *   pipeline.
     */
    private enum class Loss { NO_DRIVE_RUN, DEMOTED, BELOW_MIN_ROM, PAIRING, UNATTRIBUTED }

    private fun qualifies(s: VelocitySeries, t: RunThresholds, r: Run): Boolean {
        val peak = (r.startIdx..r.endIdx).maxOf { abs(s.velocityMps[it]) }
        val duration = s.timeS[r.endIdx] - s.timeS[r.startIdx]
        val disp = RepSegmenter.displacement(s, r.startIdx, r.endIdx)
        return peak >= t.startThresholdMps && duration >= t.minPhaseS && disp <= t.maxRunDisplacementM
    }

    /** [Loss] per empty window of one capture, in window order. */
    private fun losses(s: Scored): List<Loss> {
        val config = DspConfig()
        val samples = load(s.fixture)
        val series = VelocityEstimator.estimate(samples, config, s.direction.measuredPlane)
            .mappedToLifter(s.direction.sensorToLifter)
        val t = RunThresholds.forSeriesMappedToLifter(config, s.direction)
        val raws = rawMovementRuns(series, t)
        val w = windows(s.fixture)
        val tolerance = CueTrack.WINDOW_TOLERANCE_MS.toLong()
        val hits = IntArray(w.size)
        spans(s).forEach { span ->
            val at = samples[span.conStartIdx].timestampMs
            val k = w.indexOfFirst { (a, b) -> at >= a - tolerance && at < b + tolerance }
            if (k >= 0) hits[k]++
        }
        return w.indices.filter { hits[it] == 0 }.map { k ->
            val (a, b) = w[k]
            // A run belongs to the window its FIRST sample arrived in -- the
            // same start rule `coverage` assigns detections by, so a window
            // and its runs are read on one convention.
            val drives = raws.filter {
                it.type == s.direction.concentricRun &&
                    samples[it.startIdx].timestampMs >= a - tolerance &&
                    samples[it.startIdx].timestampMs < b + tolerance
            }
            val survivors = drives.filter { qualifies(series, t, it) }
            when {
                drives.isEmpty() -> Loss.NO_DRIVE_RUN
                survivors.isEmpty() -> Loss.DEMOTED
                survivors.all { RepSegmenter.displacement(series, it.startIdx, it.endIdx) < t.minRomM } ->
                    Loss.BELOW_MIN_ROM
                else -> Loss.PAIRING
            }
        }
    }

    @Test
    fun `every empty window is attributed to a mechanism`() {
        // The census that makes the empty column actionable. A total says a
        // candidate moved the empty column by N; this says WHICH of the five
        // it moved, and three of the five are not reachable from the
        // segmenter at all.
        //
        // CHARACTERIZATION: these are the mechanisms at this commit and no
        // claim that any of them is the right answer.
        val expected = mapOf(
            Loss.PAIRING to 6,
            Loss.BELOW_MIN_ROM to 8,
            Loss.NO_DRIVE_RUN to 5,
            Loss.DEMOTED to 3,
        )
        val actual = scored.flatMap { losses(it) }.groupingBy { it }.eachCount()
        assertEquals(expected, actual, "empty windows by mechanism")
        // The attribution has to add up to the empty column of the table
        // above, or it is describing a different run of the pipeline. This is
        // NOT the tautology `matched + empty == marks` that this file already
        // labels as unable to fail: `losses` rebuilds the run classification
        // from the velocity series and `coverage` does not, so the two agree
        // only while the rebuild still matches the shipped classifier.
        assertEquals(
            scored.sumOf { coverage(it).empty },
            actual.values.sum(),
            "attributed windows against the empty column",
        )
        assertEquals(0, actual[Loss.UNATTRIBUTED] ?: 0, "empty windows no mechanism claims")
    }

    @Test
    fun `where each mechanism costs its reps, capture by capture`() {
        // The per-capture detail, so a change that moves one capture from
        // PAIRING to BELOW_MIN_ROM cannot hide inside an unchanged total.
        // Captures with no empty window are omitted rather than mapped to an
        // empty list: absent means "nothing was lost here", which is a
        // different statement from "something was lost and it is nothing".
        val expected = mapOf(
            "field-ohp-rotating-8rep-b" to listOf(Loss.PAIRING),
            "field-bench-rotating-6rep" to listOf(Loss.BELOW_MIN_ROM),
            "field-ohp-3010-6rep-s37-set02" to listOf(Loss.NO_DRIVE_RUN),
            "field-bench-3010-6rep-s37-set05" to listOf(Loss.PAIRING),
            "field-bench-3010-6rep-s37-set06" to listOf(Loss.BELOW_MIN_ROM),
            "field-backsquat-4011-6rep-s36-set01" to listOf(Loss.PAIRING),
            "field-legpress-2010-8rep" to listOf(Loss.PAIRING),
            "field-legpress-single-2010-8rep" to listOf(Loss.BELOW_MIN_ROM),
            "field-legcurl-1030-12rep" to listOf(Loss.BELOW_MIN_ROM, Loss.DEMOTED, Loss.BELOW_MIN_ROM),
            "field-legcurl-1030-12rep-b" to listOf(Loss.PAIRING),
            "field-legcurl-1030-12rep-c" to listOf(Loss.BELOW_MIN_ROM, Loss.NO_DRIVE_RUN),
            "field-legcurl-1030-10rep" to listOf(Loss.BELOW_MIN_ROM, Loss.DEMOTED),
            "field-pullup-3010-8rep-s37-set09" to
                listOf(Loss.BELOW_MIN_ROM, Loss.DEMOTED, Loss.NO_DRIVE_RUN, Loss.NO_DRIVE_RUN),
            "field-latpulldown-1120-12rep-s38-set14" to listOf(Loss.PAIRING, Loss.NO_DRIVE_RUN),
        )
        val actual = scored.associate { it.fixture to losses(it) }.filterValues { it.isNotEmpty() }
        assertEquals(expected, actual, "empty windows per capture, in window order")
    }

    @Test
    fun `the gap by sensor mount, which is what issue 72 named`() {
        // Issue #72's title is a mount split: 53% of the reps found on seated
        // overhead press with the sensor off the stack against 104% on a
        // stack. This measures the same split on the corpus that exists now,
        // scored per window rather than as a ratio of totals -- 104% was a
        // total that cancelled misses against inventions, and the issue's own
        // thread records that being found out.
        //
        // The split is read from `sensorOnStack`, which is the geometry each
        // set DECLARED, not a judgement made here about where the sensor was.
        // The two groups are NOT-ON-STACK and ON-STACK. They were called "the
        // bar half" and "the stack half" here until now; that was wrong of me
        // and the words are deleted rather than softened.
        // `marks in the not-on-stack half, by family` measures its 132 marks
        // as 56 barbell-upper and 32 barbell-lower against 24 leg-press, 12
        // dumbbell rear-delt-fly and 8 pull-up, so 88 of the 132 stand on a
        // barbell and 44 do not. The words "fewer than half of them stand on a
        // barbell at all" were here and are deleted: 56 + 32 is 88, which is
        // two thirds. On the other side, 46 of the 58 on-stack marks are the
        // same leg-curl machine.
        //
        // THE WITHIN-SESSION PAIR IS THE FIGURE TO READ FIRST, and it is the
        // only one here that holds day, lifter, sensor and firmware fixed:
        // session 38 set 4, off the stack, matches 8 of 8; session 38 set 14,
        // on the stack, matches 10 of 12.
        //
        // Corpus-wide the rows are 120 of 132 not-on-stack (90.9%) against 48
        // of 58 on-stack (82.8%) -- the opposite direction to the one #72 was
        // filed on. Those two groups are different exercises recorded on
        // different days across at least three app versions (0.1.37 in
        // [BarbellCueTrackTest], 0.1.39 in [LegCurlCueTrackTest], 0.1.50 in
        // the entry above), so they support "the direction #72 named is not
        // the direction this corpus shows" and are not a measurement of the
        // mount.
        //
        // WHAT SESSION 38 IS FIRST AT is narrower than this comment used to
        // claim. It said session 38 was "the first committed session holding
        // both mounts"; that is false and is deleted. Session 26 (2026-08-17)
        // is issue #72's own session and already holds both BY ISSUE #72'S OWN
        // ACCOUNT OF IT -- the issue's table calls its three cable sets
        // stack-mounted -- though NO COMMITTED BYTE DECLARES THEM SO: the four
        // barbell captures scored here plus `field-cablerow-static-8rep`,
        // `field-facepull-static-12rep` and `field-pallof-static-12rep`, the
        // three cable sets the issue's table records at 0.2-0.7 deg of roll,
        // are all seven listed together in [FieldDataRegressionTest], and all
        // three cable sets take `LiftDirection(startsWith = CONCENTRIC)` in
        // [RepRefusalCorpusTest], whose `sensorOnStack` defaults to FALSE.
        // None of those three carries a `-cues.csv`, which is why all three
        // sit in `notScored` and why nothing could score a mount split PER
        // METRONOME MARK until session 38. That is the true statement: session
        // 38 is the first committed session carrying a cue track on a capture
        // of each DECLARED mount.
        //
        // THIS IS A BATCH-PATH CLAIM AND THE LIVE PATH DOES NOT SUPPORT IT.
        // [CuedRepCoverageTest] scores the counter the lifter watches during
        // the set, and on its figures session 38 set 4 matches 0 of its 8 cued
        // reps and set 14 matches 5 of 12: its BARBELL_UPPER row goes 48 -> 56
        // cued against 34 -> 42 empty, so that family's matched total stays at
        // 14 across the addition. Issue #72's own stated consequence is the
        // in-set feedback that shapes the next set, so the path that carries
        // the consequence is the one showing no improvement.
        //
        // This says nothing about over-detection, which these two rows cannot
        // see: `spans against the metronome's marks` is where the not-on-stack
        // group's 142 detections against 132 marks are readable, and the empty
        // column is the only thing measured here.
        val s38press = scored.single { it.fixture == "field-ohp-3010-8rep-s38-set04" }
        assertEquals(8, windows(s38press.fixture).size, "marks the metronome called on session 38 set 4")
        assertEquals(8, coverage(s38press).matched, "marks matched on session 38 set 4")
        val s38pull = scored.single { it.fixture == "field-latpulldown-1120-12rep-s38-set14" }
        assertEquals(12, windows(s38pull.fixture).size, "marks the metronome called on session 38 set 14")
        assertEquals(10, coverage(s38pull).matched, "marks matched on session 38 set 14")
        val expected = mapOf(
            false to listOf(132, 120),
            true to listOf(58, 48),
        )
        val actual = scored.groupBy { it.direction.sensorOnStack }.mapValues { (_, group) ->
            listOf(group.sumOf { windows(it.fixture).size }, group.sumOf { coverage(it).matched })
        }
        assertEquals(expected, actual, "by sensorOnStack: marks, matched")
        // What the not-on-stack group is actually made of. The previous commit
        // asserted {BARBELL_UPPER=132} -- the claim the deleted words made --
        // and it failed with exactly this map.
        val notOnStackByFamily = scored.filter { !it.direction.sensorOnStack }
            .groupBy { it.family }
            .mapValues { (_, g) -> g.sumOf { windows(it.fixture).size } }
        assertEquals(
            mapOf(
                Family.BARBELL_UPPER to 56,
                Family.BARBELL_LOWER to 32,
                Family.MACHINE_LOWER to 24,
                Family.ACCESSORY to 12,
                Family.BODYWEIGHT_UPPER to 8,
            ),
            notOnStackByFamily,
            "marks in the not-on-stack half, by family",
        )
        // And the on-stack side: one leg-curl machine and one other capture.
        val onStack = scored.filter { it.direction.sensorOnStack }
            .associate { it.fixture to windows(it.fixture).size }
        assertEquals(46, onStack.filterKeys { it.startsWith("field-legcurl-") }.values.sum(), "leg-curl marks")
        assertEquals(58, onStack.values.sum(), "on-stack marks in total")
        // And the exercise the issue's headline is about, named rather than
        // matched by prefix. It reported 17 of 32 reps found across four
        // seated-overhead-press sets, 53%. This corpus now holds four
        // seated-overhead-press captures of its own -- two from session 26,
        // one from field-37 and one from session 38 -- and they carry 32
        // marks, the same total by coincidence of arithmetic and not the same
        // sets. 30 of those 32 marks carry a detection.
        //
        // 30 of 32 IS NOT DIRECTLY COMPARABLE TO 17 of 32. A mark is a rep the
        // metronome called and `field-ohp-3010-6rep-s37-set02` is a failed set
        // whose metronome called 8 against 6 performed, so two of these 32
        // marks have no rep behind them at all. The comparable statement is
        // narrower and is the one that matters: on the seated overhead press
        // this corpus can see, the analyzer is no longer publishing nothing
        // for half the reps.
        val seatedPress = listOf(
            "field-ohp-rotating-8rep",
            "field-ohp-rotating-8rep-b",
            "field-ohp-3010-6rep-s37-set02",
            "field-ohp-3010-8rep-s38-set04",
        )
        val headline = scored.filter { it.fixture in seatedPress }
        assertEquals(4, headline.size, "the seated overhead press captures")
        assertEquals(32, headline.sumOf { windows(it.fixture).size }, "marks across them")
        assertEquals(30, headline.sumOf { coverage(it).matched }, "marks matched across them")
        // Session 38's set, asserted at the top of this test, is the only one
        // of the four with no empty window, and it is the newest capture of
        // the exercise the issue is named after.
        //
        // THIS FIGURE DOES NOT MOVE with the slow-eccentric fallback, and a
        // draft of that change's red asserted that it would. The rotating
        // set's empty window is a PAIRING loss the fallback does not reach:
        // `where each mechanism costs its reps` keeps that capture in its map
        // after the change. The three windows the fallback recovers are on the
        // bench, the Romanian deadlift and the back squat, none of them here.
    }

    @Test
    fun `every committed capture is scored here or named as unscorable`() {
        // The coverage guard. Without it the next capture dropped into the
        // resource directory is silently outside every figure above, which is
        // exactly how a corpus total goes stale while staying green.
        val onDisk = File(javaClass.getResource("/field-still-0rep.csv")!!.toURI()).parentFile.list()!!
            .filter {
                it.startsWith("field-") && it.endsWith(".csv") &&
                    !it.endsWith("-cues.csv") && !it.endsWith("-prep.csv")
            }
            .map { it.removeSuffix(".csv") }
            .sorted()
        assertEquals(
            onDisk,
            (scored.map { it.fixture } + notScored + notRepCorpus).sorted(),
            "every capture on the classpath is either scored against its marks or named as having none",
        )
        assertTrue(
            scored.none { it.fixture in notScored } && scored.none { it.fixture in notRepCorpus },
            "a capture cannot be both scored and named unscorable",
        )
        notRepCorpus.forEach { fixture ->
            assertTrue(
                javaClass.getResource("/$fixture-cues.csv") != null,
                "$fixture is named as out of scope but carries no track, so it belongs in notScored instead",
            )
        }
        // And the reason the unscorable ones are unscorable, checked rather
        // than asserted in a comment: nine have no committed track, and the
        // tenth has a track that calls no rep.
        val withTrack = notScored.filter {
            javaClass.getResource("/$it-cues.csv") != null
        }
        assertEquals(listOf("field-ropedeadhang-hold20-s37-set11"), withTrack, "unscorable captures with a track")
        assertEquals(0, CueTrack.calledReps(withTrack.single()), "a hold's track calls no rep")
    }
}
