package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.LeadInPolicy
import com.macrophage.barspeed.model.SkipSetControl
import com.macrophage.barspeed.model.VoiceMilestonePolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The prep before a guided set, as [LeadInPlan] lays it out second by second.
 *
 * This arithmetic is here rather than in `GuidedCadenceRunner` because no test
 * on the CI path reaches that class. The last per-second lead-in arithmetic
 * that lived
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

    /**
     * The third collision class (#130): no lead-in word may be a word the app
     * already uses for a SET, on the cue record or on screen.
     *
     * This is the class `Brace` was chosen for. The owner took it over `Set`
     * because `Set` collides with a set of reps -- the screen says which set
     * of how many, and a cue track is entirely about one set. The two pins
     * above cannot see that: `Set` is neither a stroke word nor a digit, so
     * with only them a rename of [LeadInPlan.BRACE] back to `Set` reds
     * nothing.
     *
     * READ FROM THE APP'S OWN STATEMENTS, NOT COPIED, wherever one exists:
     *
     * - the cue record: [SetEnd.TERMINAL_CUES], which is the canonical set of
     *   words that end a set on the track (`Done`, `Set ended`, `Time`), and
     *   the two halves of a guided rep call, [CadencePlan.LAST_REP] and
     *   [CadencePlan.REP_CALL_PREFIX];
     * - the unguided counter's words, rendered by
     *   `VoiceMilestonePolicy.repMilestone` itself rather than restated;
     * - the screen: `SkipSetControl.confirmTitle`, the one rendering of a
     *   set's own name ("Skip Set 3 of ...") that lives in a module this test
     *   can reach.
     *
     * WHAT HAS NO CANONICAL SOURCE, said plainly rather than hand-copied
     * here: the in-set header that reads `SET 3 OF 5` is a literal inside a
     * `@Composable` in `:app` (`RecordScreen`), which no test on the CI path
     * can load. Nothing ties that literal to the copy read here, so a rename
     * of the screen's word alone is invisible to this pin. What the pin does
     * hold is every set word the app states in `:core:model` and `:core:dsp`.
     *
     * Compared word by word, case-folded: `Brace` against `Set ended` is a
     * comparison of `brace` against `set` and `ended`, because a lifter
     * hearing one word and reading another does not match whole strings.
     */
    @Test
    fun `no lead-in word is a word the app uses for a set`() {
        fun words(s: String): Set<String> =
            s.split(Regex("[^A-Za-z]+")).filter { it.isNotEmpty() }.map { it.lowercase() }.toSet()

        val placeholder = "Placeholderexercise"
        val setVocabulary = buildSet {
            SetEnd.TERMINAL_CUES.forEach { addAll(words(it)) }
            addAll(words(CadencePlan.LAST_REP))
            addAll(words(CadencePlan.REP_CALL_PREFIX))
            listOf(
                VoiceMilestonePolicy.repMilestone(repCount = 1, announcedRep = 0, plannedReps = null),
                VoiceMilestonePolicy.repMilestone(repCount = 2, announcedRep = 0, plannedReps = 3),
                VoiceMilestonePolicy.repMilestone(repCount = 3, announcedRep = 0, plannedReps = 3),
            ).forEach { addAll(words(requireNotNull(it))) }
            addAll(words(SkipSetControl.confirmTitle(placeholder, 3)) - words(placeholder))
        }
        // The pin is only as good as what it reads; if a source above stopped
        // saying "set" this would pass vacuously, so the word it exists for is
        // asserted present.
        assertTrue("set" in setVocabulary, "the set vocabulary read here must contain the word set: $setVocabulary")
        LeadInPlan.RECORDED.forEach { word ->
            assertEquals(
                emptySet(),
                words(word) intersect setVocabulary,
                "lead-in word $word shares a word with the vocabulary the app uses for a set",
            )
        }
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
