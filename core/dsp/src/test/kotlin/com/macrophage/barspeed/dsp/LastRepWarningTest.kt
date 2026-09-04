package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.StartPhase
import com.macrophage.barspeed.model.Tempo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * When the guide warns that the rep now due is the last one, and when it does
 * not (issue #173).
 *
 * ## The report, and the measurement that shaped it
 *
 * From the gym, 2026-08-28: *"It sometimes says 'last rep', done, with no rep
 * in between."* The "sometimes" is the whole content of the defect, and it is
 * not the tempo. `"Last rep"` is a WARNING about a rep not yet performed, and
 * on some schedules the only beat that can carry it is the beat the rep ENDS
 * on -- so the lifter is told to brace for a rep with only its closing stroke
 * left -- on every schedule measured, the eccentric, so the working stroke is
 * already finished -- and then hears `Done`.
 *
 * Measured on session 33's cue tracks, sixteen sets, app 0.1.43: THREE sets
 * carry the call with a whole rep still in front of it, and THIRTEEN do not.
 * Of the thirteen, eleven read 2.00 s from their last stroke word to `Done`;
 * one reads 3.002 s, set 5, whose closing stroke is three seconds rather than
 * two; and one cannot be measured at all because it says no `Done`, set 4
 * (issue #141, firing in the field). The three read 1.001 s.
 *
 * An earlier version of this paragraph said five and eleven. That is wrong and
 * it is corrected rather than reworded: the five came from the measurement
 * filed on the issue, which tabulates all five 3010 sets at 1.00 s and reads
 * the family as safe, and two of those five are concentric-first and are not
 * safe -- they are the same case as the eleven. `MergedCallCueTrackTest`
 * carries the re-derivation from the archive and asserts both readings.
 *
 * ## The predicate is the SCHEDULE, never the tempo string
 *
 * Two facts make a list of tempo strings the wrong shape for this, and both
 * are asserted below rather than asserted about:
 *
 * - the same four digits land in different cases on different lifts -- `3010`
 *   is the safe case on an eccentric-first incline press and the unsafe one on
 *   a concentric-first overhead press, in the same session;
 * - the unsafe case is REACHED because nothing earlier in the rep could carry
 *   the call. `CadencePlan.of` tries the closing pause, then the opening
 *   stroke, and only then the rep's own last stroke. So on those plans there
 *   is provably no earlier slot to move the warning to, and the choice is late
 *   or silent. The field says silent.
 *
 * The structural statement: the call opens the beat at
 * [CadencePlan.announceOnBeat], and the announced rep ends with the beat at
 * [CadencePlan.repCompleteAfterBeat]. When those are the same beat, one stroke
 * of the announced rep is all the lifter has left when the warning arrives.
 *
 * ## What is NOT suppressed, and why the distinction is the point
 *
 * The `Rep N` call counts FINISHED reps. It reports and instructs nothing, so
 * arriving late costs it nothing, and issue #147 added it to exactly these
 * plans because they had no spoken count at all. It stays. Only the warning
 * goes.
 *
 * Nor does this touch `RecordViewModel.announceRepMilestones`, the UNGUIDED
 * counter, which speaks its own `"Last rep"` at the instant a rep is counted
 * rather than on a metronome schedule -- there the whole final rep is still
 * ahead. It is a different code path with a different clock and is out of
 * scope; no test on the CI path can construct the view model that owns it, so
 * nothing could pin it either way.
 */
class LastRepWarningTest {
    private val benchPress = LiftDirection(startsWith = StartPhase.ECCENTRIC, concentricUp = true)

    /** meta.json set 1: dumbbell_incline_press, ecc-first, drive up, vertical. */
    private val inclinePress = LiftDirection(startsWith = StartPhase.ECCENTRIC, concentricUp = true)

    /** meta.json set 5: seated_overhead_press, CONC-first, drive up, vertical. */
    private val seatedOhp = LiftDirection(startsWith = StartPhase.CONCENTRIC, concentricUp = true)

    /** meta.json set 13: triceps_pushdown, conc-first, drive DOWN, vertical, on-stack. */
    private val pushdown = LiftDirection(
        startsWith = StartPhase.CONCENTRIC,
        concentricUp = false,
        sensorOnStack = true,
    )

    private val legCurl = LiftDirection(
        startsWith = StartPhase.CONCENTRIC,
        concentricUp = false,
        sensorInverted = true,
        sensorOnStack = true,
    )

    private val legPress = LiftDirection(startsWith = StartPhase.CONCENTRIC, concentricUp = true)

    private val facePull = LiftDirection(
        startsWith = StartPhase.CONCENTRIC,
        concentricUp = true,
        plane = MovementPlane.HORIZONTAL,
        sensorOnStack = true,
    )

    private fun plan(tempo: String, direction: LiftDirection) =
        CadencePlan.of(TempoSchedule.of(Tempo.parse(tempo), direction))

    /**
     * (tempo, lift, planned reps, what the guide should warn with), covering
     * all four homes for the rep call and both answers.
     *
     * The expected column is written out per row rather than computed, so that
     * a production rule and this table cannot agree by sharing an expression.
     * Every `null` here is a plan whose call opens the beat the rep completes
     * after; every `Last rep` is a plan where the announced rep is still whole.
     */
    private val corpus = listOf(
        // Case 1 -- the call rides the closing pause of the PREVIOUS rep, so
        // the announced rep has not started when it is spoken.
        Row("2011", benchPress, 5, CadencePlan.LAST_REP),
        // Case 2 -- the call opens the announced rep's FIRST stroke.
        Row("3010", benchPress, 10, CadencePlan.LAST_REP),
        Row("2010", benchPress, 8, CadencePlan.LAST_REP),
        Row("3010", inclinePress, 10, CadencePlan.LAST_REP),
        // Case 3 -- the call opens the announced rep's LAST stroke. Nothing of
        // the rep is left to warn about, and nothing earlier could carry it.
        Row("1030", legCurl, 12, null),
        Row("1020", legCurl, 12, null),
        Row("2010", legPress, 8, null),
        Row("3010", legPress, 8, null),
        Row("2011", legPress, 12, null),
        Row("2011", facePull, 12, null),
        Row("3010", seatedOhp, 8, null),
        Row("2010", seatedOhp, 12, null),
        Row("1120", pushdown, 12, null),
        Row("1020", pushdown, 12, null),
        // Eccentric-first, so the carrying stroke is the CONCENTRIC and the
        // working stroke is NOT finished. Pinned as it behaves, not as it
        // should.
        Row("1120", benchPress, 12, null),
        // Case 4 -- every second of the cycle already has a word in it, so
        // nothing is decided at all and nothing changes here.
        Row("1010", legPress, 6, null),
        Row("1110", benchPress, 6, null),
    )

    private data class Row(
        val tempo: String,
        val direction: LiftDirection,
        val reps: Int,
        val expected: String?,
    )

    private fun Row.describe() = "$tempo on ${direction.plane}/${direction.startsWith}, $reps reps"

    @Test
    fun `the warning is dropped where it would open the last beat of the rep it is about`() {
        val outcomes = corpus.map { row ->
            val p = plan(row.tempo, row.direction)
            assertEquals(
                row.expected,
                p.announcementFor(row.reps, row.reps),
                "${row.describe()}: beats=${p.beats.map { it.label to it.seconds }}, " +
                    "announceOnBeat=${p.announceOnBeat}, repCompleteAfterBeat=${p.repCompleteAfterBeat}",
            )
            row.expected
        }
        // A table of all-null or all-LAST_REP would pass a rule that ignores
        // the plan entirely, so the corpus is asserted to contain both.
        assertEquals(4, outcomes.count { it == CadencePlan.LAST_REP }, "plans that still warn")
        assertEquals(13, outcomes.count { it == null }, "plans that do not")

        // And the argument for suppressing rather than rescheduling, as an
        // assertion rather than a claim in a comment. `CadencePlan.of` reaches
        // the rep's own last stroke only after a closing pause and the opening
        // stroke have both been ruled out, so on every suppressed plan there is
        // provably no unoccupied slot earlier in the rep to move the warning
        // to: every earlier beat is shorter than a merged call needs.
        corpus.filter { it.expected == null && plan(it.tempo, it.direction).announceOnBeat != null }
            .forEach { row ->
                val p = plan(row.tempo, row.direction)
                assertEquals(
                    p.repCompleteAfterBeat,
                    p.announceOnBeat,
                    "${row.describe()}: suppressed, so the call must be the one that closes the rep",
                )
                p.beats.take(p.repCompleteAfterBeat).forEach { earlier ->
                    assertTrue(
                        earlier.seconds < CadencePlan.MERGE_MIN_STROKE_S,
                        "${row.describe()}: ${earlier.label} is ${earlier.seconds}s and could have carried it",
                    )
                }
            }
    }

    @Test
    fun `the same four digits get two answers, because the schedule decides and the string does not`() {
        // Session 33 ran 3010 on both, on the same afternoon. A rule keyed on
        // the tempo string cannot tell these apart and would be wrong on one
        // of them whichever way it went.
        val eccFirst = plan("3010", inclinePress)
        val concFirst = plan("3010", seatedOhp)
        assertEquals(
            listOf("DOWN" to 3, "UP" to 1),
            eccFirst.beats.map { it.label to it.seconds },
            "set 1: the three-second stroke opens the rep, so the call has the whole rep in front of it",
        )
        assertEquals(
            listOf("UP" to 1, "DOWN" to 3),
            concFirst.beats.map { it.label to it.seconds },
            "set 5: the same three-second stroke CLOSES the rep, so the call has only it left",
        )
        assertEquals(CadencePlan.LAST_REP, eccFirst.announcementFor(10, 10), "set 1 still warns")
        assertNull(concFirst.announcementFor(8, 8), "set 5 must not")
    }

    @Test
    fun `the finished-rep count is a report and keeps its place, late or not`() {
        // Issue #147 gave these plans their only spoken count. The warning
        // goes and the count stays, so a set of twelve still hears eleven
        // things -- ten counts and no warning, rather than ten counts and a
        // warning that has nothing left to warn about.
        val p = plan("1120", pushdown)
        assertEquals("Rep 1", p.announcementFor(2, 12))
        assertEquals("Rep 10", p.announcementFor(11, 12))
        assertNull(p.announcementFor(12, 12), "the twelfth is the rep whose warning would open its own last beat")
        assertEquals("Rep 11", p.announcementFor(12, plannedReps = null), "an unbounded set has no last rep at all")
    }

    @Test
    fun `the suppressed warning hands the final rep back the tempo count it was taking`() {
        // The change is not a deletion. A carrying stroke gives up its first
        // tempo count only when an announcement actually rode it, so dropping
        // the warning returns the count to the final rep -- the cadence the
        // lifter is following is uninterrupted where it used to fall silent.
        //
        // These are the three session-33 plans, at the second of the cadence
        // each row lands on, from the start of the final rep.
        listOf(
            Triple(plan("1120", pushdown), 12, listOf(44 to "Down", 45 to "Hold", 46 to "Up", 47 to "1", 48 to "Done")),
            Triple(plan("3010", seatedOhp), 8, listOf(28 to "Up", 29 to "Down", 30 to "1", 31 to "2", 32 to "Done")),
            Triple(
                plan("3010", inclinePress),
                10,
                listOf(36 to "Down", 36 to CadencePlan.LAST_REP, 38 to "2", 39 to "Up", 40 to "Done"),
            ),
        ).forEach { (p, reps, expected) ->
            val finalRepStarts = (reps - 1) * p.deliveredCycleS
            val rows = CadenceVoice.script(p, reps)
                .flatMap { call -> call.recorded.map { call.atSecond to it } }
                .filter { it.first >= finalRepStarts }
            assertEquals(expected, rows, "the final rep of ${p.beats.map { it.label to it.seconds }}")
        }
    }

    @Test
    fun `the returned count makes the final rep sound like the first`() {
        // The count handed back is also what leaves rep 1 and rep N with the
        // same words: they are the only two reps of a suppressed plan with no
        // merged call on them. Asserted here rather than named in a comment.
        val p = plan("1120", pushdown)
        val reps = 12
        val rows = CadenceVoice.script(p, reps)
            .flatMap { call -> call.recorded.map { call.atSecond to it } }
        val cycle = p.deliveredCycleS
        val first = rows.filter { it.first < cycle }.map { it.first % cycle to it.second }
        val last = rows.filter { it.first >= (reps - 1) * cycle && it.second != CadenceVoice.DONE }
            .map { it.first % cycle to it.second }
        assertEquals(first, last, "rep 1 and rep $reps carry the same words at the same offsets")
    }
}
