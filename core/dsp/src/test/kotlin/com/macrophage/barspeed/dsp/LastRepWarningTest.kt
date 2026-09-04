package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.StartPhase
import com.macrophage.barspeed.model.Tempo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * When the guide says the rep now due is the set's last, and the one case where
 * it cannot say anything at all (issues #173 and #243).
 *
 * ## The report #173 was filed on, which this file keeps
 *
 * From the gym, 2026-08-28: *"It sometimes says 'last rep', done, with no rep
 * in between."* Measured on session 33's cue tracks, sixteen sets, app 0.1.43:
 * THREE sets carry the call with a whole rep still in front of it, and THIRTEEN
 * do not. Of the thirteen, eleven read 2.00 s from their last stroke word to
 * `Done`; one reads 3.002 s, set 5, whose closing stroke is three seconds
 * rather than two; and one cannot be measured at all because it says no `Done`,
 * set 4 (issue #141, firing in the field). The three read 1.001 s.
 *
 * An earlier version of that paragraph said five and eleven. That was wrong and
 * it was corrected rather than reworded: the five came from the measurement
 * filed on the issue, which tabulates all five 3010 sets at 1.00 s and reads
 * the family as safe, and two of those five are concentric-first and are not
 * safe -- they are the same case as the eleven. `MergedCallCueTrackTest`
 * carries the re-derivation from the archive and asserts both readings.
 *
 * ## Why the answer #173 gave is reversed here, rather than reworded
 *
 * #173 read that report as a WARNING arriving too late to warn, and withheld it
 * on the thirteen. The reasoning turned on a word the schedule no longer says.
 * Until #243 the guide's numbered call counted FINISHED reps -- `Rep 6` while
 * the seventh was under way -- so the beat that carries a call was a beat the
 * lifter had been taught, for the whole set, to read as being about a rep
 * ALREADY OVER. `Last rep` landing in that beat referred to a rep not yet
 * started, and there was nothing in the eleven calls before it to say the
 * frame had changed. That is the reading "with no rep in between" is the sound
 * of.
 *
 * #243 moves every numbered call onto the rep it is calling FOR, so the same
 * beat now means "the rep you are in" on every rep of the set, and `Last rep`
 * in it is a statement about the rep in hand rather than a warning about one to
 * come. The withholding is therefore deleted rather than narrowed: it was the
 * right answer to a question the schedule stopped asking.
 *
 * The audio of the final rep on these plans is what 0.1.43 played, to the
 * second. What is not the same is the eleven calls before it, and that is the
 * whole of the argument. Whether the lifter hears it that way is a `[Field]`
 * question and is named as one in the commit that made this change; the cue
 * track records what was said, so the next capture answers it.
 *
 * ## The predicate is the SCHEDULE, never the tempo string
 *
 * Two facts make a list of tempo strings the wrong shape for any rule here, and
 * both are asserted below rather than asserted about:
 *
 * - the same four digits land in different cases on different lifts -- `3010`
 *   puts the call at the start of the rep on an eccentric-first incline press
 *   and at its end on a concentric-first overhead press, in the same session;
 * - the late case is REACHED because nothing earlier in the rep could carry the
 *   call. `CadencePlan.of` tries the closing pause, then the opening stroke,
 *   and only then the rep's own last stroke. So on those plans there is
 *   provably no earlier slot, and lateness is forced rather than chosen.
 *
 * ## What still cannot be said, and it is not a preference
 *
 * A tempo whose both strokes are one second with no closing pause -- `1010`,
 * `1110` -- has a word in every second of its cycle and no beat with a count to
 * give up. It announces NOTHING on any rep, and the final rep is no exception.
 * Those two rows are the whole of the "some tempo has no beat that fits any
 * word" case, and they are in the corpus below for it.
 *
 * Nothing here touches `RecordViewModel`'s unguided counter, which speaks
 * through `VoiceMilestonePolicy.repMilestone` at the instant a rep is counted
 * rather than on a metronome schedule -- there `Rep N` is spoken as rep N
 * completes, and the whole final rep is still ahead when its `Last rep` lands.
 * It is a different code path with a different clock and is out of scope; the
 * two cannot speak on one set, because `RecordScreen.InSetStage` returns into
 * `GuidedSetStage` before the branch that draws the tap counter.
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
     * (tempo, lift, planned reps, what the guide should say for the final rep),
     * covering all four homes for the rep call and both answers.
     *
     * The expected column is written out per row rather than computed, so that
     * a production rule and this table cannot agree by sharing an expression.
     * Every `Last rep` here is a plan with a beat that can carry a call; the
     * two `null`s are the plans with no such beat on any rep at all.
     */
    private val corpus = listOf(
        // Case 1 -- the call rides the closing pause of the PREVIOUS rep, so
        // the announced rep has not started when it is spoken.
        Row("2011", benchPress, 5, CadencePlan.LAST_REP),
        // Case 2 -- the call opens the announced rep's FIRST stroke.
        Row("3010", benchPress, 10, CadencePlan.LAST_REP),
        Row("2010", benchPress, 8, CadencePlan.LAST_REP),
        Row("3010", inclinePress, 10, CadencePlan.LAST_REP),
        // Case 3 -- the call opens the announced rep's LAST stroke, because
        // nothing earlier in the rep could carry it. Withheld until #243; now
        // spoken, naming the rep the lifter is in.
        Row("1030", legCurl, 12, CadencePlan.LAST_REP),
        Row("1020", legCurl, 12, CadencePlan.LAST_REP),
        Row("2010", legPress, 8, CadencePlan.LAST_REP),
        Row("3010", legPress, 8, CadencePlan.LAST_REP),
        Row("2011", legPress, 12, CadencePlan.LAST_REP),
        Row("2011", facePull, 12, CadencePlan.LAST_REP),
        Row("3010", seatedOhp, 8, CadencePlan.LAST_REP),
        Row("2010", seatedOhp, 12, CadencePlan.LAST_REP),
        Row("1120", pushdown, 12, CadencePlan.LAST_REP),
        Row("1020", pushdown, 12, CadencePlan.LAST_REP),
        // Eccentric-first, so the carrying stroke is the CONCENTRIC and the
        // working stroke is NOT finished when the call lands.
        Row("1120", benchPress, 12, CadencePlan.LAST_REP),
        // Case 4 -- every second of the cycle already has a word in it, so
        // nothing is decided on any rep and the final rep is silent with the
        // rest.
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
    fun `the last rep is named wherever a beat can carry a call, and nowhere else`() {
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
        assertEquals(15, outcomes.count { it == CadencePlan.LAST_REP }, "plans that name the last rep")
        assertEquals(2, outcomes.count { it == null }, "plans with no beat for any call")

        // And the two answers are the plan's, not the tempo's: a null is a plan
        // with no home for a call at all, never a plan that has one and
        // declines to use it.
        corpus.forEach { row ->
            val p = plan(row.tempo, row.direction)
            assertEquals(
                row.expected == null,
                p.announceOnBeat == null,
                "${row.describe()}: silence must mean no home, and a home must mean a call",
            )
        }
    }

    @Test
    fun `the late call is late because nothing earlier in the rep could carry it`() {
        // The argument for speaking it where it lands rather than moving it, as
        // an assertion rather than a claim in a comment. `CadencePlan.of`
        // reaches the rep's own last stroke only after a closing pause and the
        // opening stroke have both been ruled out, so on every such plan every
        // earlier beat is shorter than a merged call needs.
        val late = corpus.filter { plan(it.tempo, it.direction).announceOnBeat != null }
            .filter { plan(it.tempo, it.direction).let { p -> p.announceOnBeat == p.repCompleteAfterBeat } }
        assertEquals(11, late.size, "plans whose call opens the beat their rep ends on")
        late.forEach { row ->
            val p = plan(row.tempo, row.direction)
            assertEquals(1, p.beatsOfRepLeftWhenAnnounced, "${row.describe()}: one beat of the rep is left")
            p.beats.take(p.repCompleteAfterBeat).forEach { earlier ->
                assertTrue(
                    earlier.seconds < CadencePlan.MERGE_MIN_STROKE_S,
                    "${row.describe()}: ${earlier.label} is ${earlier.seconds}s and could have carried it",
                )
            }
        }
    }

    @Test
    fun `the same four digits put the call in two places, because the schedule decides`() {
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
        assertEquals(2, eccFirst.beatsOfRepLeftWhenAnnounced, "set 1 hears it with a stroke and a stroke to go")
        assertEquals(1, concFirst.beatsOfRepLeftWhenAnnounced, "set 5 hears it inside the closing stroke")
        // Both name the last rep. What differs is where in that rep the lifter
        // hears it, which is the plan's business and no longer decides whether
        // it is said.
        assertEquals(CadencePlan.LAST_REP, eccFirst.announcementFor(10, 10), "set 1")
        assertEquals(CadencePlan.LAST_REP, concFirst.announcementFor(8, 8), "set 5")
    }

    @Test
    fun `the numbered call names the rep now due, and keeps its place, late or not`() {
        // Issue #147 gave these plans their only spoken count and #243 fixed
        // which rep it names. A set of twelve hears eleven things: ten numbers,
        // "Rep 2" through "Rep 11", each during the rep it names, and then the
        // word for the twelfth.
        val p = plan("1120", pushdown)
        assertEquals("Rep 2", p.announcementFor(2, 12))
        assertEquals("Rep 11", p.announcementFor(11, 12))
        assertEquals(CadencePlan.LAST_REP, p.announcementFor(12, 12), "the twelfth is named by word")
        assertEquals("Rep 12", p.announcementFor(12, plannedReps = null), "an unbounded set has no last rep at all")
        assertNull(plan("1010", legPress).announcementFor(6, 6), "and no home means no call, on any rep")
    }

    @Test
    fun `the restored warning takes back the tempo count the suppression handed over`() {
        // The change is not free and the cost is a row. A carrying stroke gives
        // up its first tempo count only when an announcement actually rides it,
        // so speaking the warning takes back the count #173's suppression had
        // returned to the final rep. On the two plans that suppressed it, the
        // second that carried a `1` carries the call instead.
        //
        // These are the three session-33 plans, at the second of the cadence
        // each row lands on, from the start of the final rep.
        listOf(
            Triple(
                plan("1120", pushdown),
                12,
                listOf(44 to "Down", 45 to "Hold", 46 to "Up", 46 to CadencePlan.LAST_REP, 48 to "Done"),
            ),
            Triple(
                plan("3010", seatedOhp),
                8,
                listOf(28 to "Up", 29 to "Down", 29 to CadencePlan.LAST_REP, 31 to "2", 32 to "Done"),
            ),
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
    fun `the final rep no longer sounds like the first, because only it carries a call`() {
        // Rep 1 carries no call on any plan, so before #243 -- with the final
        // rep's warning suppressed -- the two were word for word identical and
        // `Done` was the set's only ending marker. They are told apart now, and
        // the difference is exactly the call against the count it gives up.
        val p = plan("1120", pushdown)
        val reps = 12
        val rows = CadenceVoice.script(p, reps)
            .flatMap { call -> call.recorded.map { call.atSecond to it } }
        val cycle = p.deliveredCycleS
        val first = rows.filter { it.first < cycle }.map { it.first % cycle to it.second }
        val last = rows.filter { it.first >= (reps - 1) * cycle && it.second != CadenceVoice.DONE }
            .map { it.first % cycle to it.second }
        assertEquals(listOf(0 to "Down", 1 to "Hold", 2 to "Up", 3 to "1"), first, "rep 1")
        assertEquals(
            listOf(0 to "Down", 1 to "Hold", 2 to "Up", 2 to CadencePlan.LAST_REP),
            last,
            "rep $reps, which trades that count for the call",
        )
    }
}
