package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * When the voice holds audio focus (#339).
 *
 * `VoiceCounter` turns [VoiceFocusPolicy.step] into a platform request or
 * abandon; nothing here can say what the platform or a music app did with it.
 */
class VoiceFocusPolicyTest {
    private val idle = VoiceFocusState()

    /**
     * Plays [events] from idle the way `VoiceCounter` does -- after each one,
     * [VoiceFocusPolicy.step] from what is held to what [VoiceFocusPolicy.holds]
     * wants, every request granted -- and returns every step that was not KEEP.
     */
    private fun walk(vararg events: (VoiceFocusState) -> VoiceFocusState): List<VoiceFocusStep> {
        var state = idle
        var held = false
        val steps = mutableListOf<VoiceFocusStep>()
        for (event in events) {
            state = event(state)
            val step = VoiceFocusPolicy.step(held, VoiceFocusPolicy.holds(state))
            if (step == VoiceFocusStep.REQUEST) held = true
            if (step == VoiceFocusStep.ABANDON) held = false
            if (step != VoiceFocusStep.KEEP) steps += step
        }
        return steps
    }

    private val start: (VoiceFocusState) -> VoiceFocusState = VoiceFocusPolicy::setStarted
    private val end: (VoiceFocusState) -> VoiceFocusState = VoiceFocusPolicy::setEnded

    /** A word handed to the engine that the engine then starts playing. */
    private fun say(id: String): (VoiceFocusState) -> VoiceFocusState =
        { VoiceFocusPolicy.started(VoiceFocusPolicy.spoke(it, id), id) }

    private fun done(id: String): (VoiceFocusState) -> VoiceFocusState = { VoiceFocusPolicy.finished(it, id) }

    @Test
    fun `an idle voice holds nothing`() {
        assertFalse(VoiceFocusPolicy.holds(idle))
    }

    /**
     * A set recorded with the voice off, or a set no voice counts, must not
     * duck the lifter's music for its whole length.
     */
    @Test
    fun `a set nothing has been said in holds nothing`() {
        assertFalse(VoiceFocusPolicy.holds(VoiceFocusPolicy.setStarted(idle)))
    }

    /** What a finished set leaves behind is a voice that holds nothing. */
    @Test
    fun `a set that ended with nothing playing holds nothing`() {
        val spoken = VoiceFocusPolicy.spoke(VoiceFocusPolicy.setStarted(idle), "a")
        val ended = VoiceFocusPolicy.setEnded(VoiceFocusPolicy.finished(spoken, "a"))
        assertFalse(VoiceFocusPolicy.holds(ended))
    }

    /** The rest countdown and any other word outside a set: focus for the word, no longer. */
    @Test
    fun `a word outside a set holds focus while it plays and lets go when it finishes`() {
        val playing = VoiceFocusPolicy.spoke(idle, "a")
        assertTrue(VoiceFocusPolicy.holds(playing))
        assertFalse(VoiceFocusPolicy.holds(VoiceFocusPolicy.finished(playing, "a")))
    }

    /**
     * The decision #339 had to make. A guided set speaks every second, and
     * letting go between words would raise the music in each gap and duck it
     * again at the next word; once a set has said anything, focus is kept
     * until the set is over.
     */
    @Test
    fun `a set that has spoken holds focus between its words`() {
        val handed = VoiceFocusPolicy.spoke(VoiceFocusPolicy.setStarted(idle), "a")
        val spoken = VoiceFocusPolicy.started(handed, "a")
        assertTrue(VoiceFocusPolicy.holds(VoiceFocusPolicy.finished(spoken, "a")))
    }

    /** A start reported for a word outside any set marks no set as spoken (#340). */
    @Test
    fun `a word that starts outside a set leaves nothing held once it finishes`() {
        val playing = VoiceFocusPolicy.started(VoiceFocusPolicy.spoke(idle, "3"), "3")
        assertFalse(VoiceFocusPolicy.holds(VoiceFocusPolicy.finished(playing, "3")))
    }

    /**
     * A start reported for a word that has since been replaced is about a
     * word no longer playing, and must not be what marks the set (#340).
     */
    @Test
    fun `a start reported for a replaced word is not the set's word`() {
        // Inside a set, so the answer rests on the id alone: outside one a
        // start marks nothing for any word, and the pin would hold whatever
        // `started` did with the id.
        val replaced = VoiceFocusPolicy.spoke(VoiceFocusPolicy.spoke(VoiceFocusPolicy.setStarted(idle), "a"), "b")
        assertEquals(replaced, VoiceFocusPolicy.started(replaced, "a"))
    }

    /**
     * A cadence speaks whatever the cue toggle says (`GuidedCadenceRunner`),
     * so switching the cues off under one must leave the set's focus where it
     * is: releasing it would raise the music under the next word (#340).
     */
    @Test
    fun `switching the cues off under a cadence keeps the set's focus`() {
        val spoken = say("Down")(VoiceFocusPolicy.setStarted(idle))
        val quiet = VoiceFocusPolicy.finished(spoken, "Down")
        assertTrue(VoiceFocusPolicy.holds(VoiceFocusPolicy.cuesSwitchedOff(quiet, cadenceSpeaks = true)))
    }

    /** A permanent loss means the platform dropped the request: nothing is held (#340). */
    @Test
    fun `a permanent loss leaves nothing held`() {
        assertFalse(VoiceFocusPolicy.heldAfter(held = true, change = VoiceFocusChange.LOSS))
        assertFalse(VoiceFocusPolicy.heldAfter(held = false, change = VoiceFocusChange.LOSS))
    }

    /**
     * A transient loss -- a phone call -- leaves the request on the
     * platform's stack, which hands focus back with a GAIN when the call
     * ends; so the request is still owed an abandon (#340).
     */
    @Test
    fun `a held request stays held through a transient loss and a gain`() {
        assertTrue(VoiceFocusPolicy.heldAfter(held = true, change = VoiceFocusChange.LOSS_TRANSIENT))
        assertTrue(VoiceFocusPolicy.heldAfter(held = true, change = VoiceFocusChange.LOSS_TRANSIENT_CAN_DUCK))
        assertTrue(VoiceFocusPolicy.heldAfter(held = true, change = VoiceFocusChange.GAIN))
    }

    /**
     * QUEUE_FLUSH stops the playing word when the next is handed over, and
     * the engine reports the stopped one. That report is about a word that is
     * no longer playing and must not release the word that replaced it.
     */
    @Test
    fun `a flushed word's report does not release the word that replaced it`() {
        val replaced = VoiceFocusPolicy.spoke(VoiceFocusPolicy.spoke(idle, "a"), "b")
        assertTrue(VoiceFocusPolicy.holds(VoiceFocusPolicy.finished(replaced, "a")))
        assertFalse(VoiceFocusPolicy.holds(VoiceFocusPolicy.finished(replaced, "b")))
    }

    @Test
    fun `the end of a set lets go once its last word has finished`() {
        val ending = VoiceFocusPolicy.setEnded(VoiceFocusPolicy.spoke(VoiceFocusPolicy.setStarted(idle), "end"))
        assertTrue(VoiceFocusPolicy.holds(ending))
        assertFalse(VoiceFocusPolicy.holds(VoiceFocusPolicy.finished(ending, "end")))
    }

    @Test
    fun `focus is requested when wanted and abandoned when not`() {
        assertEquals(VoiceFocusStep.REQUEST, VoiceFocusPolicy.step(held = false, wanted = true))
        assertEquals(VoiceFocusStep.ABANDON, VoiceFocusPolicy.step(held = true, wanted = false))
    }

    /**
     * A whole guided set, then the rest countdown: ONE request and ONE
     * abandon for the set however many words it says, then one pair per word
     * of the countdown.
     */
    @Test
    fun `a set ducks once and the rest countdown ducks per word`() {
        val set = walk(
            start, say("Down"), done("Down"), say("2"), say("Up"), done("2"), done("Up"),
            say("Rep 2"), done("Rep 2"), say("Done"), end, done("Done"),
        )
        assertEquals(listOf(VoiceFocusStep.REQUEST, VoiceFocusStep.ABANDON), set)
        val rest = walk(say("3"), done("3"), say("2"), done("2"))
        assertEquals(List(2) { listOf(VoiceFocusStep.REQUEST, VoiceFocusStep.ABANDON) }.flatten(), rest)
    }

    /**
     * The same two in ONE walk, as they happen: a set's end must leave the
     * voice outside any set, or the countdown that follows would count as
     * the set's words and keep the music down through the rest. Two walks
     * from idle cannot see that, which is how a mutation leaving `inSet` true
     * at the set's end survived the test above.
     */
    @Test
    fun `after a set the rest countdown still lets go after each word`() {
        val steps = walk(start, say("Down"), end, done("Down"), say("3"), done("3"), say("2"), done("2"))
        assertEquals(List(3) { listOf(VoiceFocusStep.REQUEST, VoiceFocusStep.ABANDON) }.flatten(), steps)
    }

    @Test
    fun `focus already where it is wanted is left alone`() {
        assertEquals(VoiceFocusStep.KEEP, VoiceFocusPolicy.step(held = false, wanted = false))
        assertEquals(VoiceFocusStep.KEEP, VoiceFocusPolicy.step(held = true, wanted = true))
    }

    /**
     * #340: a word the engine refused or failed never played, so it must not
     * be what holds the lifter's music down for the rest of the set. Handed
     * over, then reported finished with no start between.
     */
    @Test
    fun `a word that never started does not hold the set`() {
        val handed = VoiceFocusPolicy.spoke(VoiceFocusPolicy.setStarted(idle), "a")
        assertTrue(VoiceFocusPolicy.holds(handed), "focus is asked for while the word is due")
        assertFalse(VoiceFocusPolicy.holds(VoiceFocusPolicy.finished(handed, "a")))
    }

    /** The same through the walk: one request, one abandon, nothing held after. */
    @Test
    fun `a refused word asks once and lets go`() {
        val refused: (VoiceFocusState) -> VoiceFocusState = { VoiceFocusPolicy.spoke(it, "a") }
        assertEquals(listOf(VoiceFocusStep.REQUEST, VoiceFocusStep.ABANDON), walk(start, refused, done("a")))
    }

    /**
     * #340: with no cadence running nothing speaks once the cues are off, so
     * the set's focus goes as soon as nothing is playing. A word playing at
     * the toggle finishes first.
     */
    @Test
    fun `switching the cues off with no cadence lets go once nothing plays`() {
        val playing = say("Rep 3")(VoiceFocusPolicy.setStarted(idle))
        val switched = VoiceFocusPolicy.cuesSwitchedOff(playing, cadenceSpeaks = false)
        assertTrue(VoiceFocusPolicy.holds(switched), "the word playing at the toggle still has focus")
        assertFalse(VoiceFocusPolicy.holds(VoiceFocusPolicy.finished(switched, "Rep 3")))
    }

    /**
     * #340: every change the platform sends the request but a permanent
     * loss says the request is still on its stack, so it is owed an abandon
     * whatever was believed before the change -- or the music stays ducked
     * under a request nothing will release.
     */
    @Test
    fun `any change but a permanent loss means the request is still held`() {
        assertTrue(VoiceFocusPolicy.heldAfter(held = false, change = VoiceFocusChange.GAIN))
        assertTrue(VoiceFocusPolicy.heldAfter(held = false, change = VoiceFocusChange.LOSS_TRANSIENT))
        assertTrue(VoiceFocusPolicy.heldAfter(held = false, change = VoiceFocusChange.LOSS_TRANSIENT_CAN_DUCK))
    }

    /** A gain arriving when nothing wants focus is abandoned at once. */
    @Test
    fun `a gain nothing wants is abandoned`() {
        val held = VoiceFocusPolicy.heldAfter(held = false, change = VoiceFocusChange.GAIN)
        assertEquals(VoiceFocusStep.ABANDON, VoiceFocusPolicy.step(held, VoiceFocusPolicy.holds(idle)))
    }
}
