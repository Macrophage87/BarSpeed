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

    private fun say(id: String): (VoiceFocusState) -> VoiceFocusState = { VoiceFocusPolicy.spoke(it, id) }

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
        val spoken = VoiceFocusPolicy.spoke(VoiceFocusPolicy.setStarted(idle), "a")
        assertTrue(VoiceFocusPolicy.holds(VoiceFocusPolicy.finished(spoken, "a")))
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

    /** `Set ended` is spoken at the tap: the set's end waits for that word. */
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

    @Test
    fun `focus already where it is wanted is left alone`() {
        assertEquals(VoiceFocusStep.KEEP, VoiceFocusPolicy.step(held = false, wanted = false))
        assertEquals(VoiceFocusStep.KEEP, VoiceFocusPolicy.step(held = true, wanted = true))
    }
}
