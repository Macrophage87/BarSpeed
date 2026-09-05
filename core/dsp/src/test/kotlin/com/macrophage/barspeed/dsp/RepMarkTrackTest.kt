package com.macrophage.barspeed.dsp

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a rep mark IS on the thirteen captures that now carry one. Issue #145.
 *
 * ## Why these files are here
 *
 * Issue #145 asks for a spoken rep count on a straight-rep set, and its own
 * comment names the enabler: "exporting the journal's `repMarks` (the tap
 * instants)". Those instants ship in the export as of `SessionExport` 1.13.
 * Nothing in this repository had ever read one, so before a rep call could be
 * scored against a mark, someone had to say what a mark on the committed
 * corpus actually records.
 *
 * ## Provenance
 *
 * Thirteen `-reps.csv` files over thirteen distinct sets of sessions 36, 37
 * and 38 -- the only sessions in the owner's capture directory that exported
 * `repMarks` at all, sessions 30 to 35 having written none. Each is copied
 * byte for byte out of `<session>/extracted/<setNN>_<exercise>_reps.csv`
 * there, and that directory is not in this repository. Every field below is
 * read from that session's own `meta.json`:
 *
 * | fixture | session | set | exercise | tempo | load | reps / planned |
 * |---|---|---|---|---|---|---|
 * | `field-backsquat-4011-6rep-s36-set01` | 36 | 1 | back squat | 4011 | 52.16 kg (115 lb) | 6 / 6 |
 * | `field-rdl-3010-10rep-s36-set05` | 36 | 5 | Romanian deadlift | 3010 | 52.16 kg (115 lb) | 10 / 10 |
 * | `field-legpress-single-2011-8rep-s36-set07` | 36 | 7 | single leg press | 2011 | 65.77 kg (145 lb) | 8 / 8 |
 * | `field-ohp-3010-6rep-s37-set02` | 37 | 2 | seated overhead press | 3010 | 24.95 kg (55 lb) | 6 / 8 |
 * | `field-ohp-prepinflated-s37-set03` | 37 | 3 | seated overhead press | 3010 | 22.68 kg (50 lb) | 7 / 8 |
 * | `field-ohp-prepinflated-s37-set04` | 37 | 4 | seated overhead press | 3010 | 22.68 kg (50 lb) | 5 / 8 |
 * | `field-bench-3010-6rep-s37-set05` | 37 | 5 | bench press | 3010 | 47.63 kg (105 lb) | 6 / 6 |
 * | `field-bench-3010-6rep-s37-set06` | 37 | 6 | bench press | 3010 | 49.90 kg (110 lb) | 6 / 6 |
 * | `field-pullup-3010-8rep-s37-set09` | 37 | 9 | assisted pull-up | 3010 | 23.44 kg (52 lb) | 8 / 8 |
 * | `field-inclinepress-3010-12rep-s38-set02` | 38 | 2 | dumbbell incline press | 3010 | 27.22 kg (60 lb) | 12 / 10 |
 * | `field-ohp-3010-8rep-s38-set04` | 38 | 4 | seated overhead press | 3010 | 13.61 kg (30 lb) | 8 / 8 |
 * | `field-ohp-3010-8rep-s38-set05` | 38 | 5 | seated overhead press | 3010 | 13.61 kg (30 lb) | 8 / 8 |
 * | `field-latpulldown-1120-12rep-s38-set14` | 38 | 14 | lat pulldown | 1120 | 34.02 kg (75 lb) | 12 / 12 |
 *
 * Session 36 is 2026-09-01 on app 0.1.47, session 37 is 2026-09-02 on 0.1.48
 * and session 38 is 2026-09-04 on 0.1.50; all three name the sensor as a
 * WitMotion WT901BLECL. Every one of the thirteen sets carries
 * `repsManual: true`.
 *
 * The rule is the exporting SET, not the cue track. Sixteen captures on this
 * classpath carry a `-cues.csv` and no rep file, three of them from these
 * same three sessions. `field-backsquat-wrapping-s36-set01` is session 36 set
 * 1's role-`a` stream and `field-backsquat-4011-6rep-s36-set01` its role-`b`
 * one -- one set, two fixtures, and the marks are committed once, beside the
 * role-`b` stream `meta.json` names as the analysed one.
 * `field-rdl-wrapping-s36-set05` is byte for byte the same file as
 * `field-rdl-3010-10rep-s36-set05`, committed twice under two names, and
 * again the marks sit beside one of the two.
 * `field-ropedeadhang-hold20-s37-set11` is a timed hold and its session wrote
 * no rep file for it. `thirteen captures carry marks and sixteen carry cues
 * without them` asserts that split against the resource directory, so this
 * paragraph cannot drift from the directory again.
 *
 * ## The finding these pins exist for
 *
 * `repsManual: true` reads as "the lifter counted", and on a guided set it
 * does not mean the lifter MARKED. Every one of these thirteen sets is
 * metronome-paced, and `the marks sit on the cue track` measures what that
 * costs: all 103 marks land within 1 ms of a row of the same capture's own
 * `-cues.csv`, 94 of them on its exact millisecond, and the rows they sit on
 * are the guide's own words. So the marks on this corpus are the GUIDE's rep
 * calls, and scoring a rep call against them is scoring it against the
 * metronome a second time.
 *
 * That is not a reason to leave them out. It is the reason the F1 capture
 * issue #145 asks for is still owed, it is a fact a later reader would
 * otherwise have to rediscover, and the reader and the window rule written
 * here transfer unchanged to a straight-rep capture where the marks ARE taps.
 */
class RepMarkTrackTest {
    /** The thirteen captures carrying a rep-mark track, and their mark counts. */
    private val corpus = listOf(
        "field-backsquat-4011-6rep-s36-set01" to 6,
        "field-rdl-3010-10rep-s36-set05" to 10,
        "field-legpress-single-2011-8rep-s36-set07" to 8,
        "field-ohp-3010-6rep-s37-set02" to 7,
        "field-ohp-prepinflated-s37-set03" to 7,
        "field-ohp-prepinflated-s37-set04" to 5,
        "field-bench-3010-6rep-s37-set05" to 6,
        "field-bench-3010-6rep-s37-set06" to 6,
        "field-pullup-3010-8rep-s37-set09" to 8,
        "field-inclinepress-3010-12rep-s38-set02" to 12,
        "field-ohp-3010-8rep-s38-set04" to 8,
        "field-ohp-3010-8rep-s38-set05" to 8,
        "field-latpulldown-1120-12rep-s38-set14" to 12,
    )

    private fun hasSidecar(f: String, suffix: String): Boolean = javaClass.getResource("/$f$suffix") != null

    /**
     * The Provenance paragraph, read off the resource directory instead of
     * asserted in prose.
     *
     * The commit before this one pushed this test stating the paragraph's OLD
     * rule -- that every cue-bearing capture carries a rep file -- and it
     * failed on sixteen captures. This is the same listing against the rule
     * that holds.
     *
     * Three assertions, not one total: a total would let the marked set and
     * the cue-only set move in opposite directions and still add up.
     */
    @Test
    fun `thirteen captures carry marks and sixteen carry cues without them`() {
        val captures = FieldCorpus.onClasspath()
        assertEquals(corpus.map { it.first }.sorted(), captures.filter { hasSidecar(it, "-reps.csv") })
        val cuedOnly = captures.filter { hasSidecar(it, "-cues.csv") && !hasSidecar(it, "-reps.csv") }
        assertEquals(16, cuedOnly.size)
        assertEquals(
            listOf(
                "field-backsquat-wrapping-s36-set01",
                "field-rdl-wrapping-s36-set05",
                "field-ropedeadhang-hold20-s37-set11",
            ),
            cuedOnly.filter { Regex("-s3[678]-set").containsMatchIn(it) },
        )
    }

    @Test
    fun `every capture reports the mark count its session recorded`() {
        assertEquals(corpus.toMap(), corpus.associate { (f, _) -> f to RepMarks.read(f).size })
        assertEquals(103, corpus.sumOf { (f, _) -> RepMarks.read(f).size })
    }

    @Test
    fun `marks arrive in order and never share an instant`() {
        for ((fixture, _) in corpus) {
            val marks = RepMarks.read(fixture)
            assertEquals(marks.sorted(), marks, fixture)
            assertEquals(marks.size, marks.distinct().size, fixture)
        }
    }

    /**
     * The whole point of the file. A mark on this corpus is a cue row.
     *
     * Two figures, not one: the exact count is what makes "the guide marked
     * it" the only reading left, and the 1 ms bound is what says the nine that
     * miss by a millisecond are a clock artefact rather than nine independent
     * taps.
     */
    @Test
    fun `the marks sit on the cue track`() {
        var exact = 0
        var total = 0
        for ((fixture, _) in corpus) {
            val cues = CueTrack.read(fixture).map { it.timestampMs }
            for (mark in RepMarks.read(fixture)) {
                total++
                val nearest = cues.minOf { abs(it - mark) }
                assertTrue(nearest <= 1L, "$fixture mark $mark is ${nearest}ms from any cue")
                if (nearest == 0L) exact++
            }
        }
        assertEquals(103, total)
        assertEquals(94, exact)
    }

    /**
     * The cycle the announcement windows are cut from, per capture.
     *
     * Every one is the prescribed tempo's own total, 3 to 8 ms long: a 4011
     * back squat sums to 6 s and measures 6008 ms, and every 4-second
     * prescription here -- 3010, 2011 and 1120 alike -- measures 4003 to 4005
     * ms. The overshoot is `GuidedCadenceRunner` speaking on wall-clock
     * `delay(1_000)`, which cannot be exact, so the gaps say what the
     * metronome did rather than what it was asked for.
     *
     * This is the same statement `the marks sit on the cue track` makes, read
     * off the gaps rather than off the rows: a track paced by a lifter would
     * not land every cycle inside 8 ms of a prescription.
     */
    @Test
    fun `each set's mark cycle is its prescribed tempo`() {
        assertEquals(
            mapOf(
                "field-backsquat-4011-6rep-s36-set01" to 6008.0,
                "field-rdl-3010-10rep-s36-set05" to 4005.0,
                "field-legpress-single-2011-8rep-s36-set07" to 4004.0,
                "field-ohp-3010-6rep-s37-set02" to 4004.0,
                "field-ohp-prepinflated-s37-set03" to 4004.0,
                "field-ohp-prepinflated-s37-set04" to 4005.0,
                "field-bench-3010-6rep-s37-set05" to 4004.0,
                "field-bench-3010-6rep-s37-set06" to 4004.0,
                "field-pullup-3010-8rep-s37-set09" to 4003.0,
                "field-inclinepress-3010-12rep-s38-set02" to 4004.0,
                "field-ohp-3010-8rep-s38-set04" to 4004.0,
                "field-ohp-3010-8rep-s38-set05" to 4004.0,
                "field-latpulldown-1120-12rep-s38-set14" to 4005.0,
            ),
            corpus.associate { (f, _) -> f to RepMarks.cycleMs(f) },
        )
    }

    /**
     * A mark track is not a rep count. ONE of the thirteen carries more marks
     * than the session recorded reps for the set: session 37 set 2 recorded 6
     * against 7 marks, the guide having called a rep the lifter did not
     * complete.
     *
     * Stated as the pair per capture rather than as a rule, because the two
     * agree on twelve of the thirteen and a rule fitted to one row would be a
     * rule fitted to this corpus. It matters to everything downstream: on that
     * set a spoken count that reached 7 would be scored right against the
     * marks and wrong against what the lifter did.
     */
    @Test
    fun `marks and recorded reps disagree on one capture`() {
        val recorded = mapOf(
            "field-backsquat-4011-6rep-s36-set01" to 6,
            "field-rdl-3010-10rep-s36-set05" to 10,
            "field-legpress-single-2011-8rep-s36-set07" to 8,
            "field-ohp-3010-6rep-s37-set02" to 6,
            "field-ohp-prepinflated-s37-set03" to 7,
            "field-ohp-prepinflated-s37-set04" to 5,
            "field-bench-3010-6rep-s37-set05" to 6,
            "field-bench-3010-6rep-s37-set06" to 6,
            "field-pullup-3010-8rep-s37-set09" to 8,
            "field-inclinepress-3010-12rep-s38-set02" to 12,
            "field-ohp-3010-8rep-s38-set04" to 8,
            "field-ohp-3010-8rep-s38-set05" to 8,
            "field-latpulldown-1120-12rep-s38-set14" to 12,
        )
        val differ = corpus.filter { (f, marks) -> recorded.getValue(f) != marks }.map { it.first }
        assertEquals(listOf("field-ohp-3010-6rep-s37-set02"), differ)
    }
}
