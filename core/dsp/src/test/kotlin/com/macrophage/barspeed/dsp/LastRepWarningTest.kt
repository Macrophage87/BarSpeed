package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.StartPhase
import com.macrophage.barspeed.model.Tempo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
 * The audio of the final rep on these plans is 0.1.43's again, which is
 * measured on two of session 33's thirteen -- `MergedCallCueTrackTest` compares
 * the scripted window against sets 5 and 13's own tracks -- and derived from
 * the rule on the other eleven, whose tracks nobody has replayed against this
 * schedule. What is not the same is the eleven calls before it, and that is the
 * whole of the argument. Whether the lifter hears it that way is a `[Field]`
 * question and is named as one in the commit that made this change; the cue
 * track records what was said, so the next capture answers it.
 *
 * ## The predicate is the SCHEDULE, never the tempo string
 *
 * The pair still decides everything about the call except where it lands, and
 * both facts are asserted below rather than asserted about:
 *
 * - the same four digits give the call a different WORD to replace -- `3010`
 *   replaces `Down` on an eccentric-first incline press and `Up` on a
 *   concentric-first overhead press, in the same session;
 * - whether a plan speaks at all is the pair's too: `1110` speaks on the
 *   geometries whose swap carries digit 2's pause to the end of the rep and is
 *   silent on the others.
 *
 * ## The late call is gone, and the paragraph that justified it with it
 *
 * This section used to argue that a call landing on the beat the rep ends on is
 * FORCED: `CadencePlan.of` tried the closing pause, then the opening stroke, and
 * only then the rep's own last stroke, so on those plans there was provably no
 * earlier slot. That argument is deleted rather than softened, because its
 * premise was that a call needs a stroke with a tempo count to give up. #293
 * replaces the opening stroke's WORD instead, which every plan has, so every
 * call opens the rep it names and no plan reaches a later beat. The owner asked
 * for exactly that, after a session on a 3010 overhead press: "It's hard to
 * follow. Have the rep number be at the start of the rep, and replace the
 * relevant up or down, etc."
 *
 * ## What still cannot be said, and it is not a preference
 *
 * A SCHEDULE of two one-second strokes with no closing pause has a word in
 * every second of its cycle and no beat with a count to give up. It announces
 * NOTHING on any rep, and the final rep is no exception. `1010` resolves to one
 * on every lift; `1110` does so only when `TempoSchedule.of` leaves the digits
 * in prescription order, because the swap it performs on a lift that does not
 * open with digit 1's stroke carries digit 2's pause to the end of the rep,
 * where case 1 takes it. An earlier version of this paragraph named `1110`
 * flatly, which is false on a concentric-first lift whose concentric is up and
 * on an eccentric-first lift whose concentric is down, and it is deleted rather
 * than softened. The two rows below are the whole of the "some schedule has no
 * beat that fits any word" case: `1010` on a leg press, which `TempoSchedule.of`
 * SWAPS -- harmlessly, since its two strokes are equal and both its pauses are
 * zero -- and `1110` on a bench press, which it leaves unswapped.
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
        // A prescription that ends in a pause. The pause used to carry the call,
        // a rep early; it is silent now and the call opens the rep it names.
        Row("2011", benchPress, 5, CadencePlan.LAST_REP),
        // A prescription whose opening stroke is long enough to have counted the
        // call's room out of its own tempo. It replaces the word instead.
        Row("3010", benchPress, 10, CadencePlan.LAST_REP),
        Row("2010", benchPress, 8, CadencePlan.LAST_REP),
        Row("3010", inclinePress, 10, CadencePlan.LAST_REP),
        // Plans whose call used to open the announced rep's LAST stroke,
        // because nothing earlier in the rep had a count to give up. Withheld
        // until #243, spoken since, and moved to the rep's first second by #293.
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
        // The two plans that still say nothing on any rep: both strokes one
        // second and no closing pause. The owner ruled these count at lockout
        // instead (#266, "Rep count is at lockout"), which is not built yet and
        // is not this file's subject.
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
    fun `no call is late now -- every one opens the rep it names`() {
        // The replacement for `the late call is late because nothing earlier in
        // the rep could carry it`, which asserted the opposite and was true when
        // it was written. Eleven of these rows put the call on the beat the rep
        // ENDS on, with one beat left; all fifteen now put it on the rep's first
        // beat, with the whole rep ahead.
        val speaking = corpus.filter { plan(it.tempo, it.direction).announceOnBeat != null }
        assertEquals(15, speaking.size, "plans that speak at all")
        speaking.forEach { row ->
            val p = plan(row.tempo, row.direction)
            assertEquals(0, p.announceOnBeat, "${row.describe()}: the call opens the rep")
            assertEquals(
                p.repCompleteAfterBeat + 1,
                p.beatsOfRepLeftWhenAnnounced,
                "${row.describe()}: every beat of the named rep is still to come",
            )
            val cycle = p.deliveredCycleS
            val warning = CadenceVoice.script(p, row.reps)
                .flatMap { call -> call.recorded.map { call.atSecond to it } }
                .single { it.second == CadencePlan.LAST_REP }
            assertEquals(
                (row.reps - 1) * cycle,
                warning.first,
                "${row.describe()}: and the warning lands on the final rep's first second",
            )
        }
    }

    @Test
    fun `the same four digits replace two different words, because the schedule decides`() {
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
        assertEquals(2, concFirst.beatsOfRepLeftWhenAnnounced, "and so does set 5 now, where it heard it with one")
        // What the pair still decides is the WORD the number replaces, and with
        // it which stroke is counted from the number: the three-second stroke on
        // set 1, the one-second drive on set 5.
        assertEquals("Down", eccFirst.beats[0].spokenLabel, "set 1's opening word")
        assertEquals("Up", concFirst.beats[0].spokenLabel, "set 5's, from the same four digits")
        assertEquals(
            listOf("2", "3"),
            (1..2).mapNotNull { CadenceVoice.countCall(eccFirst.beats[0], "Rep 2", it)?.utterance },
            "set 1's opening stroke counts from the number",
        )
        assertEquals(
            emptyList(),
            (1..1).mapNotNull { CadenceVoice.countCall(concFirst.beats[0], "Rep 2", it)?.utterance },
            "set 5's has no second to count in, so the number is all of it",
        )
        // Both name the last rep. What differs is which word the lifter stops
        // hearing, not whether the rep is named.
        assertEquals(CadencePlan.LAST_REP, eccFirst.announcementFor(10, 10), "set 1")
        assertEquals(CadencePlan.LAST_REP, concFirst.announcementFor(8, 8), "set 5")
    }

    @Test
    fun `the numbered call names the rep now due, and keeps its place, late or not`() {
        // Issue #147 gave these plans their only spoken count and #243 fixed
        // which rep it names. A set of twelve hears eleven things: ten numbers,
        // "Rep 2" through "Rep 11", then the word for the twelfth. Each is
        // spoken on the first second of the rep it names, on every schedule that
        // speaks at all -- which is #293 and is what CueTrackOriginTest holds
        // across the families. This comment used to say "during the rep it
        // names, because 1120 merges the call into a stroke", and named the
        // closing-pause plans as the exception; there is no exception now.
        val p = plan("1120", pushdown)
        assertEquals("Rep 2", p.announcementFor(2, 12))
        assertEquals("Rep 11", p.announcementFor(11, 12))
        assertEquals(CadencePlan.LAST_REP, p.announcementFor(12, 12), "the twelfth is named by word")
        assertEquals("Rep 12", p.announcementFor(12, plannedReps = null), "an unbounded set has no last rep at all")
        assertNull(plan("1010", legPress).announcementFor(6, 6), "and no home means no call, on any rep")
    }

    @Test
    fun `the final rep opens on the warning, and gets its tempo count back`() {
        // What the warning costs, re-measured. It used to cost a tempo count:
        // the stroke it rode gave up its first count to make room, so on a 1120
        // pushdown the second that had carried a `1` fell silent. Nothing is
        // given up now -- the warning takes the opening stroke's word instead --
        // so that `1` is spoken on the final rep as on every other, and what the
        // lifter stops hearing is the word `Down`.
        //
        // These are the three session-33 plans, at the second of the cadence
        // each row lands on, from the start of the final rep.
        listOf(
            Triple(
                plan("1120", pushdown),
                12,
                listOf(44 to CadencePlan.LAST_REP, 45 to "Hold", 46 to "Up", 47 to "1", 48 to "Done"),
            ),
            Triple(
                plan("3010", seatedOhp),
                8,
                listOf(28 to CadencePlan.LAST_REP, 29 to "Down", 30 to "1", 31 to "2", 32 to "Done"),
            ),
            Triple(
                plan("3010", inclinePress),
                10,
                listOf(36 to CadencePlan.LAST_REP, 37 to "2", 38 to "3", 39 to "Up", 40 to "Done"),
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
        // from #293 the difference is exactly one word: rep 1 says `Down` where
        // the final rep says the warning. Both count the same seconds.
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
            listOf(0 to CadencePlan.LAST_REP, 1 to "Hold", 2 to "Up", 3 to "1"),
            last,
            "rep $reps, which says the warning where rep 1 says Down and keeps the count",
        )
    }
}
