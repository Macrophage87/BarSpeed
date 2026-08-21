package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.LeadInPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The prep before a guided set, as [LeadInPlan] lays it out second by second.
 *
 * This arithmetic is here rather than in `GuidedCadenceRunner` because `:app`
 * has no test source set. The last per-second lead-in arithmetic that lived
 * there was wrong on every set the app had ever paced -- issue 106 -- and
 * nothing could say so.
 *
 * Nothing in this file observes TTS, a speaker or a lifter. It asserts what the
 * plan says to say and when, never what was heard.
 *
 * ## Literal 2, not [LeadInPlan.PHRASE_S]
 *
 * Where a test asserts WHERE the launch phrase sits it writes the number 2
 * rather than the constant. A test written against the constant moves with it:
 * raise PHRASE_S to 3 and `beats[p - PHRASE_S]` follows the change and stays
 * green, which is the whole failure the constant's own KDoc warns about.
 */
class LeadInPlanTest {
    /**
     * The prep the import gate warns below is the length of the launch phrase,
     * checked from the module that can see both constants.
     *
     * `LeadInPolicy.MIN_USEFUL_S` lives in `:core:model` and is 2 because the
     * phrase occupies [LeadInPlan.PHRASE_S] seconds. They are separate constants
     * on purpose -- `:core:model` cannot see this module at all, and PHRASE_S's
     * own KDoc records what aliasing two equal constants costs. Separate
     * constants still have to agree about the REASON, and this is the only place
     * that can say so: raise PHRASE_S to 3 and the import gate goes on warning
     * at 2, so a plan declaring a prep of 2 would be told the phrase fits when
     * it no longer does. The same two-hop arrangement `VelocityLossTest` uses
     * for the velocity-loss vocabulary.
     *
     * Asserted through the BEATS rather than by comparing the two numbers, so
     * what is pinned is the claim the warning makes and not an equality that
     * could be kept true while the claim went false.
     */
    @Test
    fun `the prep the import gate warns below is the length of the launch phrase`() {
        assertEquals(
            listOf(LeadInPlan.READY, LeadInPlan.BRACE),
            spoken(LeadInPolicy.MIN_USEFUL_S),
            "at the import gate's threshold the whole launch phrase should still fit",
        )
        assertEquals(
            listOf(LeadInPlan.BRACE),
            spoken(LeadInPolicy.MIN_USEFUL_S - 1),
            "one second below it, Ready is what gets dropped",
        )
    }

    private fun spoken(prepS: Int) = LeadInPlan.of(prepS).beats.map { it.spoken }

    private fun recorded(prepS: Int) = LeadInPlan.of(prepS).beats.mapNotNull { it.cue }

    @Test
    fun `the launch phrase occupies the last two seconds of every prep long enough for it`() {
        // The phrase is assigned from the END of the prep backwards, so it
        // lands in the same place whether the prep is 2 seconds or 20. That is
        // what makes "Brace" mean the same thing every time the lifter hears
        // it, instead of meaning "somewhere near the start of a countdown".
        for (p in 2..20) {
            val b = LeadInPlan.of(p).beats
            assertEquals(LeadInPlan.READY, b[p - 2].spoken, "prep $p: two seconds out")
            assertEquals(LeadInPlan.BRACE, b[p - 1].spoken, "prep $p: the last second")
        }
    }

    @Test
    fun `a two-second prep is the shortest that delivers the whole phrase`() {
        // Degradation order matters and is not symmetric: the brace-now beat
        // immediately before movement is worth more than the get-ready beat two
        // seconds out, so a one-second prep keeps Brace and drops Ready.
        assertEquals(listOf(LeadInPlan.READY, LeadInPlan.BRACE), spoken(2), "two seconds, the whole phrase")
        assertEquals(listOf(LeadInPlan.BRACE), spoken(1), "one second keeps the beat nearest the movement")
        assertEquals(emptyList(), LeadInPlan.of(0).beats, "no prep, no beats")
    }

    @Test
    fun `Ready still means two seconds before the first movement, on every prep that says it`() {
        // indexOfFirst, not last: if the phrase ever widens, the earliest beat
        // claiming to be Ready is the one that has moved.
        for (p in 2..20) {
            val plan = LeadInPlan.of(p)
            val i = plan.beats.indexOfFirst { it.spoken == LeadInPlan.READY }
            assertTrue(i >= 0, "prep $p never says Ready")
            assertEquals(2, plan.secondsBeforeStart(i), "prep $p: Ready must land two seconds out")
        }
    }

    @Test
    fun `the spoken countdown digit is the seconds left before the first stroke`() {
        // The screen already counts the prep down on a ring. A voice counting
        // anything else at the same moment is worse than no voice at all, so
        // the digit spoken at T-k is literally k.
        assertEquals(listOf("5", "4", "3"), spoken(5).take(3), "the default prep counts five, four, three")
        for (p in 0..30) {
            val plan = LeadInPlan.of(p)
            plan.beats.forEachIndexed { index, beat ->
                val s = beat.spoken ?: return@forEachIndexed
                if (!s.all(Char::isDigit)) return@forEachIndexed
                assertEquals(plan.secondsBeforeStart(index), s.toInt(), "prep $p beat $index")
            }
        }
    }

    @Test
    fun `no prep speaks a digit the phrase has taken`() {
        // "two, one, ready, brace" would be four beats for two seconds of prep.
        // The phrase REPLACES what would have been 2 and 1; it does not follow
        // them.
        for (p in 0..30) {
            spoken(p).forEach {
                assertTrue(it != "2" && it != "1", "prep $p speaks $it, a digit the phrase owns")
            }
        }
        val digits = (0..30).flatMap { spoken(it) }.filterNotNull().filter { it.all(Char::isDigit) }
        assertEquals(setOf("3", "4", "5"), digits.toSet(), "every digit any prep can speak")
    }

    @Test
    fun `a long prep names its length, and only when there is a silent second to protect it`() {
        // TTS runs with QUEUE_FLUSH, so an utterance owns exactly one second
        // before the next one cancels it mid-word. "Twenty seconds" needs more
        // than that, and only gets it when a silent second follows -- which is
        // why the rule is >= COUNT_FROM_S + PHRASE_S and not > COUNT_FROM_S.
        assertEquals("7 seconds", LeadInPlan.of(7).beats[0].spoken, "seven is the shortest that says it")
        assertEquals("10 seconds", LeadInPlan.of(10).beats[0].spoken)
        assertEquals("20 seconds", LeadInPlan.of(20).beats[0].spoken)
        assertNull(LeadInPlan.of(6).beats[0].spoken, "at six the opener would run into the countdown")
        assertEquals("5", LeadInPlan.of(5).beats[0].spoken, "the default prep opens on its first digit")
    }

    @Test
    fun `the record carries the phrase and not the countdown`() {
        // The contract this whole change turns on, and the reason it is a test
        // rather than a comment: a later author adding the digits to the record
        // must red something. Bare digits are already an overloaded cue
        // vocabulary with three producers, and 5,4,3 immediately before the
        // first Down lands exactly where a consumer measures "first cue to
        // first movement".
        assertEquals(listOf(LeadInPlan.READY, LeadInPlan.BRACE), recorded(20), "a twenty-second prep")
        assertEquals(listOf(LeadInPlan.READY, LeadInPlan.BRACE), recorded(5), "the default prep")
        assertEquals(listOf(LeadInPlan.BRACE), recorded(1), "one second records what it says")
        assertEquals(emptyList(), recorded(0), "no prep, no rows")
        for (p in 0..30) {
            recorded(p).forEach {
                assertTrue(it in LeadInPlan.RECORDED, "prep $p records $it, which is not a lead-in word")
            }
        }
    }

    @Test
    fun `the lead-in vocabulary is disjoint from the vocabulary a plan emits`() {
        // HAND COPY of the set CadencePlanTest.`the cue vocabulary a plan can
        // emit is the one the committed tracks use` pins, with no mechanical
        // link to it. If the plan vocabulary widens, that test reds first and
        // this list must be corrected in the same commit; nothing detects the
        // drift. Both sets share one cue track, and CueTrack.calledReps counts
        // rows equal to "Down": a lead-in word colliding with a stroke word
        // would inflate a rep count in every capture made afterwards.
        val strokeWords = setOf("Down", "Drive", "Hold", "Return", "Up")
        assertEquals(emptySet(), LeadInPlan.RECORDED intersect strokeWords, "lead-in word that is a stroke call")
        val digits = (1..9).map { it.toString() }.toSet()
        assertEquals(emptySet(), LeadInPlan.RECORDED intersect digits, "lead-in word that is a tempo count")
    }

    @Test
    fun `every prep is exactly as many seconds as it prescribes`() {
        // Issue 106's guard, transplanted. The defect there was a beat the
        // prescription did not ask for, inserted by the player; the plan is
        // now the only thing that decides how many seconds a prep is, so the
        // count is asserted directly rather than inferred from a cue track.
        for (p in 0..30) {
            assertEquals(p, LeadInPlan.of(p).beats.size, "prep $p must be $p beats")
            assertEquals(p, LeadInPlan.of(p).prepS, "prep $p must report itself as $p")
        }
        // And the beats run from the far end of the prep down to the last
        // second before the first stroke, one per second with no gaps.
        val plan = LeadInPlan.of(5)
        assertEquals(
            listOf(5, 4, 3, 2, 1),
            plan.beats.indices.map { plan.secondsBeforeStart(it) },
            "seconds before the first stroke, beat by beat",
        )
    }

    @Test
    fun `a word is never recorded that was not spoken`() {
        // The cue track is a record of what the app SAID. A row for a second
        // that passed in silence would be a fabricated instruction, and the
        // consumers that measure "first cue to first movement" would time it
        // from a moment nothing happened.
        for (p in 0..30) {
            LeadInPlan.of(p).beats.forEachIndexed { index, beat ->
                if (beat.cue != null) {
                    assertTrue(
                        beat.spoken != null,
                        "prep $p beat $index records ${beat.cue} without speaking it",
                    )
                }
                // Absence is null, never the empty string: an empty cue would
                // read as a row to every consumer that only checks for null.
                assertTrue(beat.cue != "", "prep $p beat $index has an empty-string cue")
                assertTrue(beat.spoken != "", "prep $p beat $index has an empty-string utterance")
            }
        }
    }
}
