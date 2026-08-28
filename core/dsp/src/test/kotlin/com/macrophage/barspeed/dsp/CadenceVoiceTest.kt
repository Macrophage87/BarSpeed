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
 * beside the sleeps, where the module's one test file could not reach them.
 * Everything asserted here was previously unassertable -- which is why the app
 * could speak a rep call on eleven of twelve reps and write none of them down
 * for as long as it did (issue 176).
 *
 * The cases chosen are eccentric-first, because [CadencePlan]'s case 2 is the
 * home whose behaviour neither issue 176 nor issue 173 changes. Case 3 -- the
 * call merged into the rep's OWN last stroke -- is issue 173's subject and is
 * asserted with it.
 */
class CadenceVoiceTest {
    private val benchPress = LiftDirection(startsWith = StartPhase.ECCENTRIC, concentricUp = true)

    private fun plan(tempo: String) = CadencePlan.of(TempoSchedule.of(Tempo.parse(tempo), benchPress))

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
    fun `the call counts finished reps, and warns once, and only where there is a home for it`() {
        val p = plan("3010")
        assertEquals("Rep 1", p.announcementAfter(1, plannedReps = 3))
        assertEquals(CadencePlan.LAST_REP, p.announcementAfter(2, plannedReps = 3), "one rep short of the target")
        assertEquals("Rep 7", p.announcementAfter(7, plannedReps = null), "no target, so no last rep to warn of")
        assertNull(plan("1010").announcementAfter(1, plannedReps = 3), "no home, so nothing is decided")
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
                4 to "Down, Rep 1",
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

    @Test
    fun `a one-rep set is called through with no rep announcement at all`() {
        // There is no finished rep to count and no rep after it to warn about.
        assertEquals(
            listOf(0 to "Down", 1 to "1", 2 to "2", 3 to "Up", 4 to "Done"),
            CadenceVoice.script(plan("3010"), plannedReps = 1).map { it.atSecond to it.utterance },
        )
    }
}
