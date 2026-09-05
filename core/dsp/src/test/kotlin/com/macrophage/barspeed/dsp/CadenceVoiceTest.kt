package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.StartPhase
import com.macrophage.barspeed.model.Tempo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What a guided set says, and which of it reaches the cue track.
 *
 * These decisions used to live inside `GuidedCadenceRunner.play` in `:app`,
 * beside the sleeps, where no test on the CI path could reach them.
 * Everything asserted here was previously unassertable -- which is why the app
 * could speak all eleven rep calls of a twelve-rep set and write none of them
 * down for as long as it did (issue 176).
 *
 * The cases chosen are eccentric-first, because [CadencePlan]'s case 2 is the
 * home whose PLACEMENT neither issue 176 nor issue 173 changed. Case 3 -- the
 * call merged into the rep's OWN last stroke -- is issue 173's subject and is
 * asserted with it, in `LastRepWarningTest`, along with #243 reversing the
 * answer it gave. WHICH rep a call names is #243's subject and moved on every
 * case at once, so it is asserted here as well.
 */
class CadenceVoiceTest {
    private val benchPress = LiftDirection(startsWith = StartPhase.ECCENTRIC, concentricUp = true)

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

    /**
     * (tempo, lift, planned reps) covering all four homes for the rep call:
     * a closing pause, the next rep's first stroke, the rep's own last stroke,
     * and no home at all.
     */
    private val corpus = listOf(
        Triple("2011", benchPress, 5),
        Triple("3010", benchPress, 10),
        Triple("2010", benchPress, 8),
        Triple("1030", legCurl, 12),
        Triple("1020", legCurl, 12),
        Triple("2010", legPress, 8),
        Triple("3010", legPress, 8),
        Triple("2011", legPress, 12),
        Triple("2011", facePull, 12),
        Triple("1010", legPress, 6),
        Triple("1110", benchPress, 6),
    )

    private fun plan(tempo: String) = CadencePlan.of(TempoSchedule.of(Tempo.parse(tempo), benchPress))

    private fun plan(tempo: String, direction: LiftDirection) =
        CadencePlan.of(TempoSchedule.of(Tempo.parse(tempo), direction))

    @Test
    fun `a stroke says its own word and writes that word down`() {
        val down = plan("3010").beats[0]
        val call = CadenceVoice.beatCall(down, announcement = null)!!
        assertEquals("Down", call.utterance)
        assertEquals(listOf("Down"), call.recorded)
    }

    @Test
    fun `a closing pause has no word of its own, so a call spoken there is the row`() {
        // Case 1. The announcement is the whole utterance and the whole row --
        // this is the only home that has ever written a rep call down, and the
        // "Rep 4" in the export schema's cue vocabulary comes from here.
        val p = plan("2011")
        val closing = p.beats[p.announceOnBeat!!]
        assertNull(closing.spokenLabel, "a closing pause is silent unless a call rides it")
        assertNull(CadenceVoice.beatCall(closing, announcement = null), "and says nothing when none does")
        val call = CadenceVoice.beatCall(closing, CadencePlan.LAST_REP)!!
        assertEquals("Last rep", call.utterance)
        assertEquals(listOf("Last rep"), call.recorded)
    }

    @Test
    fun `tempo counts land inside a stroke and never on its last second`() {
        // The last second of a stroke is the next beat's word. A count there
        // would be flushed by it mid-digit.
        val down = plan("3010").beats[0]
        assertEquals("1", CadenceVoice.countCall(down, null, 1)!!.utterance)
        assertEquals("2", CadenceVoice.countCall(down, null, 2)!!.utterance)
        assertNull(CadenceVoice.countCall(down, null, 3), "the third second is the Up call")
        val up = plan("3010").beats[1]
        assertNull(CadenceVoice.countCall(up, null, 1), "a one-second stroke is not counted at all")
    }

    @Test
    fun `a stroke gives up its first count only when a call actually rode it`() {
        // Rep 1 has no announcement pending, so it keeps the count -- which is
        // what makes a missing count in a recorded track the fingerprint of a
        // merged call rather than a property of the tempo.
        val down = plan("3010").beats[0]
        assertEquals(true, down.suppressFirstCount, "3010 opens on the stroke that carries the call")
        assertEquals("1", CadenceVoice.countCall(down, null, 1)!!.utterance, "rep 1 keeps it")
        assertNull(CadenceVoice.countCall(down, "Rep 1", 1), "a rep that carries a call gives it up")
        assertEquals("2", CadenceVoice.countCall(down, "Rep 1", 2)!!.utterance, "and gives up only the first")
    }

    @Test
    fun `the call names the rep now due, warns on the last, and only where there is a home`() {
        // #243. The number is the rep the lifter is being called into, not
        // the count of the ones behind them.
        val p = plan("3010")
        assertEquals("Rep 2", p.announcementFor(2, plannedReps = 3), "the second rep is the one now due")
        assertEquals(CadencePlan.LAST_REP, p.announcementFor(3, plannedReps = 3), "the last rep is the one due")
        assertEquals("Rep 8", p.announcementFor(8, plannedReps = null), "no target, so no last rep to warn of")
        assertNull(plan("1010").announcementFor(2, plannedReps = 3), "no home, so nothing is decided")
    }

    @Test
    fun `the script places every call on the second the runner would speak it`() {
        // Three reps of a 3010 bench press: a four-second cycle whose call
        // opens the NEXT rep. Twelve seconds of cadence, eleven utterances.
        assertEquals(
            listOf(
                0 to "Down",
                1 to "1",
                2 to "2",
                3 to "Up",
                4 to "Down, Rep 2",
                6 to "2",
                7 to "Up",
                8 to "Down, Last rep",
                10 to "2",
                11 to "Up",
                12 to "Done",
            ),
            CadenceVoice.script(plan("3010"), plannedReps = 3).map { it.atSecond to it.utterance },
        )
        assertEquals(
            3 * plan("3010").deliveredCycleS,
            CadenceVoice.script(plan("3010"), plannedReps = 3).last().atSecond,
            "the set ends when the prescription says, and Done costs no second of its own",
        )
    }

    /**
     * An X stroke is called as a one-second beat, word then no count (#250).
     *
     * CHARACTERIZATION, not a change. #250's second comment asks for this
     * behaviour and states that the guide does not have it -- reading
     * `TempoSchedule.prescribedCycleS`'s KDoc, which is about the
     * PRESCRIPTION, as a statement about the metronome. The claim is wrong and
     * this pin is what says so: `30X0` and `3010` produce the SAME script,
     * second for second, because `CadencePlan.strokeSeconds` has always
     * substituted a second for a null stroke. No red preceded these
     * assertions; nothing moved for them to red against.
     *
     * The X stroke's word is spoken and no count follows it, which is not a
     * rule about X at all -- a one-second stroke of any digit is below
     * [GuidedCadence.COUNT_ALOUD_FROM_S] and the last second of a stroke is
     * the next beat's word. `3010`'s own `Up` behaves identically, which is
     * exactly why the two scripts coincide.
     *
     * What this does NOT pin, because it is untouched: `Tempo.upS` stays null,
     * so `isExplosiveUpStroke` and the compliance scorer still see an X phase
     * as unprescribed and leave it unscored.
     */
    @Test
    fun `an X stroke is called as a one-second beat, word then no count`() {
        val script = CadenceVoice.script(plan("30X0"), plannedReps = 2)
        assertEquals(
            listOf(
                0 to "Down",
                1 to "1",
                2 to "2",
                3 to "Up",
                4 to "Down, Last rep",
                6 to "2",
                7 to "Up",
                8 to "Done",
            ),
            script.map { it.atSecond to it.utterance },
        )
        assertEquals(
            CadenceVoice.script(plan("3010"), plannedReps = 2).map { it.atSecond to it.utterance },
            script.map { it.atSecond to it.utterance },
            "the owner's rule: 30X0 is four seconds of beats like 3010",
        )
        assertEquals(4, plan("30X0").deliveredCycleS, "one rep of 30X0 is four seconds of cadence")
        assertNull(
            CadenceVoice.countCall(plan("30X0").beats[1], null, 1),
            "the X stroke's own second is its word, never a count",
        )
        assertEquals(
            null,
            Tempo.parse("30X0").upS,
            "and the prescription still records X, so the scorer leaves that phase alone",
        )
    }

    @Test
    fun `every word the guide speaks is a word the cue track carries`() {
        // Issue 176, as the general rule. An utterance is one or two words --
        // the stroke call, and the rep announcement merged into it -- and both
        // were said out loud. The cue track is presented in
        // session-export.schema.json as what the app said, so a word spoken and
        // not written makes it a record of something else.
        corpus.forEach { (tempo, direction, reps) ->
            CadenceVoice.script(plan(tempo, direction), reps).forEach { call ->
                assertEquals(
                    call.utterance.split(", "),
                    call.recorded,
                    "$tempo on ${direction.plane}/${direction.startsWith}: \"${call.utterance}\" at ${call.atSecond}s",
                )
            }
        }
    }

    @Test
    fun `a set records exactly the rep calls its schedule decided on`() {
        // The pin issue 176 asks for by name: the recorded count matches the
        // number of calls the schedule made. Stated as the calls themselves and
        // in order, because a count alone passes when the right number of wrong
        // words is written.
        //
        // It reads announcementFor for the expected side, which is the
        // decision, against the script's rows, which are the delivery. A plan
        // that decides to say nothing is covered too, in the two shapes the
        // corpus carries. 1010 has no pause to give at either end, so it is a
        // schedule of two one-second strokes on every lift -- this leg press
        // included, even though TempoSchedule.of swaps it, because swapping
        // two equal strokes and two zero pauses changes nothing. 1110 keeps
        // its call-less shape only where the digits are left unswapped, as on
        // this bench press: digit 2's one-second pause then sits INSIDE the
        // rep and the rep closes on nothing, where the swap would carry that
        // pause to the END of the rep and hand it the call. Neither of these
        // two rows has a home, so both must record none.
        corpus.forEach { (tempo, direction, reps) ->
            val p = plan(tempo, direction)
            val decided = (2..reps).mapNotNull { p.announcementFor(it, reps) }
            val recorded = CadenceVoice.script(p, reps)
                .flatMap { it.recorded }
                .filter { it == CadencePlan.LAST_REP || it.startsWith(CadencePlan.REP_CALL_PREFIX) }
            assertEquals(
                decided,
                recorded,
                "$tempo on ${direction.plane}/${direction.startsWith}: calls decided, against calls written down",
            )
        }
    }

    @Test
    fun `a merged call writes down the call as well as the stroke it rode`() {
        // Both homes that merge, so that fixing one and leaving the other is
        // not available. The stroke word keeps its own row unchanged and
        // unrenamed -- CueTrack.calledReps counts those rows, and every
        // committed fixture matches them exactly.
        val nextRepsOpener = plan("3010", benchPress).beats[0]
        assertEquals(
            SpokenCall("Down, Rep 3", listOf("Down", "Rep 3")),
            CadenceVoice.beatCall(nextRepsOpener, "Rep 3"),
            "case 2, merged into the next rep's opening stroke",
        )
        val ownLastStroke = plan("2010", legPress).let { it.beats[it.announceOnBeat!!] }
        assertEquals(
            SpokenCall("Down, Last rep", listOf("Down", CadencePlan.LAST_REP)),
            CadenceVoice.beatCall(ownLastStroke, CadencePlan.LAST_REP),
            "case 3, merged into the rep's own last stroke",
        )
    }

    @Test
    fun `a one-rep set is called through with no rep announcement at all`() {
        // There is no finished rep to count and no rep after it to warn about.
        assertEquals(
            listOf(0 to "Down", 1 to "1", 2 to "2", 3 to "Up", 4 to "Done"),
            CadenceVoice.script(plan("3010"), plannedReps = 1).map { it.atSecond to it.utterance },
        )
    }
}
