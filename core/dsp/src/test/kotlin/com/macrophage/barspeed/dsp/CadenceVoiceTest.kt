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
 * The cases chosen are eccentric-first, because an eccentric-first press is
 * the geometry whose opening stroke is the long one -- so it is the geometry
 * where the number replacing that stroke's word renumbers a count, which is the
 * half of #293 a row list shows and a rule does not. WHICH rep a call names is
 * #243's subject and is asserted here as well; WHERE it lands is #293's and has
 * its own file, `RepCallPlacementTest`, over three sets on two later sessions.
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
     * (tempo, lift, planned reps) covering every prescription family the call has
     * ever been placed on: one with a closing pause, one whose opening stroke is
     * long, one whose closing stroke is long, one that counts at the end of the
     * drive (#266) and one that says nothing at all.
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
    fun `a closing pause says nothing at all now that no call rides it`() {
        // Case 1 until #293: the pause was the only home that ever wrote a rep
        // call down, and the "Rep 4" in the export schema's cue vocabulary came
        // from it. The call opens the next rep instead, so on a 2011 bench press
        // the pause is silent on every rep of the set.
        val p = plan("2011")
        val closing = p.beats[2]
        assertEquals(0, p.announceOnBeat, "the call is not on the pause any more")
        assertNull(closing.spokenLabel, "a closing pause has no word of its own")
        assertNull(CadenceVoice.beatCall(closing, announcement = null), "so it says nothing")
        assertEquals(
            emptyList(),
            CadenceVoice.script(p, plannedReps = 5).filter { it.atSecond % p.deliveredCycleS == 3 },
            "and nothing is spoken on the fourth second of any rep, which is the pause",
        )
        // The function still answers for a wordless beat handed a call, and no
        // plan hands it one -- #266 included, whose call lands on a beat that has
        // a word: the other stroke's, or a mid-rep `Hold`. A pause that carried
        // a call would speak it alone. Kept because [CadenceBeat] can express
        // such a beat; asserted so it cannot quietly change under a later reader.
        assertEquals(
            SpokenCall("Last rep", listOf("Last rep")),
            CadenceVoice.beatCall(closing, CadencePlan.LAST_REP),
        )
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
    fun `a stroke whose word the number replaced is counted from the number`() {
        // #293's second half, and the owner's own example: "a 3010 press goes
        // Rep 3, 2, 3, Up". The number stands where the stroke's word stood, so
        // it is that stroke's first count and the rest follow from it. No count
        // is dropped -- the merged call used to buy its room by silencing one.
        //
        // Rep 1 carries no call, so its opening stroke keeps its word AND counts
        // from one. A recorded track therefore reads "Down 1 2" on rep 1 and
        // "Rep 2 2 3" on rep 2, which is a difference the lifter hears and a
        // [Field] question this cannot answer.
        val down = plan("3010").beats[0]
        assertEquals("1", CadenceVoice.countCall(down, null, 1)!!.utterance, "rep 1 counts from one")
        assertEquals("2", CadenceVoice.countCall(down, null, 2)!!.utterance)
        assertEquals("2", CadenceVoice.countCall(down, "Rep 2", 1)!!.utterance, "a called rep counts from the number")
        assertEquals("3", CadenceVoice.countCall(down, "Rep 2", 2)!!.utterance)
        assertNull(CadenceVoice.countCall(down, "Rep 2", 3), "and the stroke's last second is the next beat's word")
    }

    @Test
    fun `the call names the rep now due, warns on the last, and only where there is a home`() {
        // #243. The number is the rep the lifter is being called into, not
        // the count of the ones behind them.
        val p = plan("3010")
        assertEquals("Rep 2", p.announcementFor(2, plannedReps = 3), "the second rep is the one now due")
        assertEquals(CadencePlan.LAST_REP, p.announcementFor(3, plannedReps = 3), "the last rep is the one due")
        assertEquals("Rep 8", p.announcementFor(8, plannedReps = null), "no target, so no last rep to warn of")
        // 1010 on this bench press is ECCENTRIC-first, which is the one shape
        // with no beat for a call at all: the instant its drive ends belongs to
        // the next rep (#266). The concentric-first reading of the same four
        // digits decides a call, and `LockoutRepCallTest` holds it.
        assertNull(plan("1010").announcementFor(2, plannedReps = 3), "no home, so nothing is decided")
        assertEquals(
            "Rep 2",
            plan("1010", legPress).announcementFor(2, plannedReps = 3),
            "and the same digits on a concentric-first lift do decide one",
        )
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
                4 to "Rep 2",
                5 to "2",
                6 to "3",
                7 to "Up",
                8 to "Last rep",
                9 to "2",
                10 to "3",
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
     * CHARACTERIZATION when it was written, against #250's second comment,
     * which read `TempoSchedule.prescribedCycleS`'s KDoc -- about the
     * PRESCRIPTION -- as a statement about the metronome. The one-second beat
     * is still what this pins: `CadencePlan.strokeSeconds` substitutes a second
     * for a null stroke, so `30X0` and `3010` land every call on the same
     * second.
     *
     * A sentence here said the two produce the SAME script, and #264 makes it
     * false, so it is deleted: the X drive is called `Drive` where `3010` says
     * `Up`, on every rep, because on this eccentric-first press the X is the
     * rep's second stroke and the rep number replaces only the first. The rows
     * this list held before #264 were `3 to "Up"` and `7 to "Up"`.
     *
     * The X stroke's word is spoken and no count follows it, which is not a
     * rule about X at all -- a one-second stroke of any digit is below
     * [GuidedCadence.COUNT_ALOUD_FROM_S] and the last second of a stroke is
     * the next beat's word.
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
                3 to "Drive",
                4 to "Last rep",
                5 to "2",
                6 to "3",
                7 to "Drive",
                8 to "Done",
            ),
            script.map { it.atSecond to it.utterance },
        )
        assertEquals(
            CadenceVoice.script(plan("3010"), plannedReps = 2).map { it.atSecond },
            script.map { it.atSecond },
            "the owner's rule: 30X0 is four seconds of beats like 3010, every call on the same second",
        )
        assertEquals(
            listOf(3 to "Up", 7 to "Up"),
            CadenceVoice.script(plan("3010"), plannedReps = 2)
                .map { it.atSecond to it.utterance }
                .filterNot { it in script.map { call -> call.atSecond to call.utterance } },
            "and the only rows 3010 says that 30X0 does not are the one-second drive's own word",
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
    fun `every word the guide speaks is a word the cue track carries, one to a second`() {
        // Issue 176, as the general rule, and #293 narrowing it. The cue track
        // is presented in session-export.schema.json as what the app said, so a
        // word spoken and not written makes it a record of something else -- and
        // a word written and not spoken does the same in the other direction,
        // which is why the stroke word the number replaces is not recorded.
        //
        // An utterance used to be one word or two, "Down, Rep 3" being the pair.
        // It is exactly one now, on every plan in the corpus, so no second is
        // asked to carry two things under QUEUE_FLUSH.
        corpus.forEach { (tempo, direction, reps) ->
            CadenceVoice.script(plan(tempo, direction), reps).forEach { call ->
                assertEquals(
                    listOf(call.utterance),
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
        // decision, against the script's rows, which are the delivery.
        //
        // WHICH REP IS FIRST NAMED is the plan's, not a constant. A plan whose
        // call opens the rep says nothing on rep 1 -- that word is the start cue
        // (#241) -- so rep 2 is the first named. A plan that counts at the end of
        // the drive replaces a LATER beat's word, which rep 1 has like any other
        // rep, so rep 1 is named (#266). The leg press 1010 row is the second
        // kind; the bench press 1110 row is the one shape that still records no
        // call at all, its digits left unswapped by TempoSchedule.of so that
        // digit 2's pause sits INSIDE the rep and the drive closes it.
        corpus.forEach { (tempo, direction, reps) ->
            val p = plan(tempo, direction)
            val firstNamed = if (p.announcesAtConcentricEnd) 1 else 2
            val decided = (firstNamed..reps).mapNotNull { p.announcementFor(it, reps) }
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
    fun `a call replaces the stroke word it used to ride, in the record as well as in the ear`() {
        // The two homes that used to merge, so that changing one and leaving the
        // other is not available. Both wrote TWO rows at one instant -- the
        // stroke word and the call -- and both now write ONE, the call.
        //
        // The stroke word is not renamed, and that distinction is the whole of
        // why this is a published contract change rather than a rename: the word
        // still means what it meant and is still written on rep 1, so a consumer
        // matching `Down` rows matches fewer of them rather than different ones.
        val opener = plan("3010", benchPress).beats[0]
        assertEquals(
            SpokenCall("Rep 3", listOf("Rep 3")),
            CadenceVoice.beatCall(opener, "Rep 3"),
            "the eccentric-first press, whose opener is the three-second stroke",
        )
        assertEquals(
            SpokenCall("Down", listOf("Down")),
            CadenceVoice.beatCall(opener, announcement = null),
            "and the same beat on rep 1, which carries no call",
        )
        val oneSecondOpener = plan("2010", legPress).beats[0]
        assertEquals(
            SpokenCall(CadencePlan.LAST_REP, listOf(CadencePlan.LAST_REP)),
            CadenceVoice.beatCall(oneSecondOpener, CadencePlan.LAST_REP),
            "the one-second opener, which used to send the call to the other stroke",
        )
        assertEquals(
            SpokenCall("Up", listOf("Up")),
            CadenceVoice.beatCall(oneSecondOpener, announcement = null),
            "whose word is Up on a leg press, drive-up and concentric-first",
        )
    }

    @Test
    fun `a one-rep set is called through with no rep announcement at all`() {
        // There is no rep after it to warn about, and its own number would have
        // to take the word that opens the set.
        assertEquals(
            listOf(0 to "Down", 1 to "1", 2 to "2", 3 to "Up", 4 to "Done"),
            CadenceVoice.script(plan("3010"), plannedReps = 1).map { it.atSecond to it.utterance },
        )
    }

    @Test
    fun `a one-rep set that counts at the drive's end says the warning, and that is deliberate`() {
        // #266's placement replaces a LATER beat's word, so a one-rep set has a
        // beat for its own number -- and `announcementFor` renders the rep in
        // hand as the warning whenever it is the planned last, which on a set of
        // one it is from the first rep. So the lifter hears `Up, Last rep, Done`
        // where a 3010 single says nothing.
        //
        // Pinned rather than special-cased: #243's substitution is one rule for
        // every plan, and a second rule for sets of one would be a rule nothing
        // in the corpus exercises. What the lifter loses is a number they were
        // never in doubt about; what they gain is the set being called over.
        assertEquals(
            listOf(0 to "Up", 1 to CadencePlan.LAST_REP, 2 to "Done"),
            CadenceVoice.script(plan("1010", legPress), plannedReps = 1).map { it.atSecond to it.utterance },
        )
    }
}
