package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.VoiceCue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * Session 32, set 9: a guided set the lifter abandoned, and the boundary its
 * cue track does not carry. Issue #141.
 *
 * ## Provenance
 *
 * Seated lateral raise, 12 reps prescribed at 2011, 20 lb, recorded on app
 * 0.1.41 on 2026-08-21. `field-lateralraise-2011-s32-set09-cues.csv` is that
 * set's cue stream copied byte for byte out of
 * `field-32/set09_seated_lateral_raise_cues.csv` in the durable field-capture
 * archive; nothing was re-encoded, resampled or trimmed. The app version is the
 * archive's own `meta.json` `appVersion`, read there rather than remembered.
 * Its nine rows are identical, instant for instant, to the `voiceCues` array
 * `field-32/session.json` publishes for the set.
 *
 * The set's raw IMU stream is NOT committed with it. Every claim this file
 * makes is about the cue track and the rule that reads it; the figures quoted
 * below from the session's own export are context for why the set matters, and
 * nothing here recomputes them.
 *
 * ## What the capture shows
 *
 * The lifter counted ONE rep against a prescription of twelve and tapped the
 * failure. The export publishes three entries in `repMetrics`, a
 * `velocityLoss_pct` of 47.5 and `velocityLossBasis: "measured"` -- the app's
 * strongest assertion that a figure is trustworthy -- over three detections of
 * which at most one is a rep.
 *
 * Five of the session's seventeen sets carry no `Done`, and they are exactly
 * the five carrying `failed: true` (sets 7, 9, 11, 12 and 16), re-counted from
 * `session.json` for this file rather than taken from the issue.
 *
 * ## A correction to what issue #141 assumed, measured here
 *
 * The issue argues the missing boundary matters because "the sets most likely
 * to have a messy post-set tail are exactly the ones the cue cannot bound".
 * Measured on this session, that is the wrong way round. The eleven completed
 * sets keep recording 4.3 to 13.7 s past `Done`, because the set runs on until
 * the lifter walks back and taps an effort tile. The five FAILED sets stop
 * 0.482 to 0.832 s after their last spoken cue -- the tap that ends the set is
 * the same tap that stops the recording, so there is barely a tail for a
 * spurious detection to land in. Set 9's is the shortest of the five: last cue
 * at 1787341226423, last raw sample at 1787341226905.
 *
 * That is structural rather than a property of this session. `endSet` cancels
 * the sample collectors before it reads the clock, so the last sample of a set
 * ended by a tap is never later than the tap. **A boundary placed at the tap
 * cannot exclude a detection, on this capture or on any other.** What it can
 * do is the thing this issue is actually about: turn "nothing on the record
 * says when this set ended" into "the set was called over here and nothing came
 * after it", which is the null-versus-zero distinction [SetEnd] already makes
 * in the other direction, and hand the rest clock and every downstream
 * consumer an instant where they had an absence.
 */
class FailedSetBoundaryTest {
    private val fixture = "field-lateralraise-2011-s32-set09"

    /** The capture's own cue track, in the form the recorder hands to the analyzer. */
    private fun track(name: String) = CueTrack.read(name).map { VoiceCue(it.timestampMs, it.label) }

    /**
     * The fixture is the set's whole cue track and it names no terminal cue.
     *
     * Pinned as rows rather than as a count so that a fixture swapped for a
     * different set cannot pass this quietly. The lead-in's `Ready` and
     * `Brace`, two `Up`/`Hold`/`Down` cycles with one tempo count between them,
     * and then it stops on a stroke call. The last row is a stroke the guide
     * was calling, not a word that ends anything.
     */
    @Test
    fun `session 32 set 9 stops mid-cadence and never says the set is over`() {
        assertEquals(
            listOf(
                1_787_341_218_416L to "Ready",
                1_787_341_219_417L to "Brace",
                1_787_341_220_418L to "Up",
                1_787_341_221_418L to "Hold",
                1_787_341_222_419L to "Down",
                1_787_341_223_420L to "1",
                1_787_341_224_421L to "Up",
                1_787_341_225_422L to "Hold",
                1_787_341_226_423L to "Down",
            ),
            track(fixture).map { it.timestampMs to it.cue },
            "session 32 set 9's cue track, row for row",
        )
        assertFalse(
            track(fixture).any { it.cue == SetEnd.DONE },
            "a set the lifter ended before the prescription was called through says no Done",
        )
    }

    /**
     * The defect, stated as the rule's own answer: nothing bounds this set.
     *
     * Characterization of today's behaviour, and the reason the failed sets are
     * the ones an analysis has to leave out. `detectionsAfter` returning null
     * is correct for what the record currently holds -- no instant was written,
     * so none may be invented -- and it is the fix's job to put an instant on
     * the record, not to weaken this rule.
     */
    @Test
    fun `an abandoned guided set is not bounded and reports no count`() {
        val cues = track(fixture)
        assertEquals(SetEnd.NotCued, SetEnd.of(cues), "nothing on this set's record ends it")
        assertNull(
            SetEnd.of(cues).detectionsAfter(listOf(1_787_341_220_000L, 1_787_341_226_000L)),
            "no boundary, so no count -- and null rather than zero",
        )
    }

    // ------------------------------------------------------------------
    // The words that end a set, and which of them the app asks for.
    // ------------------------------------------------------------------

    /**
     * One statement of `"Done"`, not two.
     *
     * [CadenceVoice] speaks it and [SetEnd] bounds on it, and each used to
     * declare its own literal. Nothing checked they agreed: renaming the
     * spoken word would have left the rule looking for a word the app no
     * longer said, every guided set would have gone unbounded, and the whole
     * suite would have stayed green because both sides were internally
     * consistent.
     */
    @Test
    fun `the word the guide speaks and the word the rule bounds on are one constant`() {
        assertEquals(CadenceVoice.DONE, SetEnd.DONE, "the emitter and the reader must name one word")
        assertEquals("Done", SetEnd.DONE, "and it is the word the committed cue tracks carry")
    }

    /**
     * Two terminal words, and they say different things.
     *
     * `Done` is the prescription delivered; `Set ended` is the lifter stopping
     * before it was. A reader of an archive can tell those apart because the
     * words differ, which is the whole reason a second one exists rather than
     * the first being reused.
     */
    @Test
    fun `the terminal vocabulary names both endings and keeps them apart`() {
        assertEquals(setOf("Done", "Set ended"), SetEnd.TERMINAL_CUES, "the words that end a set")
        assertNotEquals(SetEnd.DONE, SetEnd.STOPPED, "a completed set and an abandoned one must not read alike")
    }

    /**
     * The new word cannot be mistaken for anything the guide already says.
     *
     * The vocabulary a cue row can carry is stroke names, bare digits, the rep
     * calls and the two lead-in words. #147 rejected a bare-digit rep call for
     * exactly this reason -- a digit already means a tempo count -- and the
     * same test applies to a terminal word.
     */
    @Test
    fun `the abandoned-set word collides with nothing already in the vocabulary`() {
        val existing = setOf(
            "Up", "Down", "Hold", "Drive", "Return", "Brace", "Ready", "Time",
            "Carry", "Last rep", "Done",
        )
        assertFalse(SetEnd.STOPPED in existing, "the terminal word reuses a word that already means something")
        assertFalse(SetEnd.STOPPED.toIntOrNull() != null, "a bare digit is a tempo count, never a set ending")
        assertFalse(
            track(fixture).any { it.cue == SetEnd.STOPPED },
            "the word already appears in a capture recorded before it existed",
        )
    }

    /**
     * Who is asked for the word: a guided set whose record does not already
     * end, and nobody else.
     *
     * The unguided case is #141's own second design question and is
     * deliberately not answered here -- a manual set ends by the same tap, and
     * bounding those changes the figures of every manual set recorded from
     * here on.
     */
    @Test
    fun `only a guided set with no boundary on its record asks for one`() {
        assertNull(SetEnd.terminalCall(guided = false, spoken = emptyList()), "an unguided set says nothing")
        assertNull(
            SetEnd.terminalCall(guided = false, spoken = track(fixture)),
            "and it says nothing however its track ends",
        )
        assertEquals(
            SpokenCall("Set ended", listOf("Set ended")),
            SetEnd.terminalCall(guided = true, spoken = track(fixture)),
            "session 32 set 9 is exactly the set that should have said this",
        )
        assertNull(
            SetEnd.terminalCall(guided = true, spoken = track(fixture) + VoiceCue(1_787_341_230_000L, SetEnd.DONE)),
            "a set the guide already called over does not say it twice",
        )
    }

    /**
     * Spoken and recorded as one word, not merged onto anything.
     *
     * A merged call exists because TTS cancels an in-flight utterance and two
     * words had to share one. Nothing is in flight at the tap -- `endSet`
     * cancels the runner before this is asked -- so this is the plain case,
     * and the row written is the word uttered.
     */
    @Test
    fun `the boundary is one utterance and one row carrying the same word`() {
        val call = SetEnd.terminalCall(guided = true, spoken = emptyList())!!
        assertEquals(SetEnd.STOPPED, call.utterance, "what the lifter hears")
        assertEquals(listOf(SetEnd.STOPPED), call.recorded, "what the archive keeps")
    }

    // ------------------------------------------------------------------
    // The differential: what changes for session 32 set 9, and what does not.
    // ------------------------------------------------------------------

    /**
     * The instant the fix would have written, and where it comes from.
     *
     * NOT a measurement of the tap. v0.1.41 wrote no terminal row, so this
     * archive does not hold the instant the lifter tapped the failure, and no
     * amount of reading it will produce one. What it holds is the last raw IMU
     * sample of the set, 1787341226905 -- and `endSet` cancels the sample
     * collectors before anything else, so that sample is a LOWER bound on the
     * tap and the tightest one the capture carries. Used here as the boundary a
     * fixed app would have stamped, and labelled as a bound rather than as the
     * tap, because the difference is the BLE delivery gap and nothing here
     * measures it.
     */
    private val tapLowerBoundMs = 1_787_341_226_905L

    /** The set's cue track as the fixed app would have written it. */
    private fun boundedTrack() = track(fixture) + VoiceCue(tapLowerBoundMs, SetEnd.STOPPED)

    /**
     * The fix, stated as the rule's answer: the set is bounded.
     *
     * This is the whole of #141 at the level the rule works on. `NotCued` says
     * nothing on the record ends this set; `Cued` says the set was called over
     * at a named instant, and every consumer of [SetEnd] -- the rep-list
     * boundary, `detectionsAfter`, and `RestClockPolicy`'s seed instant
     * through `setOverCueAtMs` -- gets an answer where it had an absence.
     */
    @Test
    fun `the boundary row bounds the set session 32 could not bound`() {
        assertEquals(
            SetEnd.Cued(tapLowerBoundMs),
            SetEnd.of(boundedTrack()),
            "a guided set the lifter ended is bounded at the word that ended it",
        )
    }

    /**
     * Null becomes zero, and zero is the true answer.
     *
     * The distinction this repository keeps re-learning, running the other
     * way for once. Today set 9 reports null -- "nothing said when this set
     * ended" -- and an analysis has to exclude it, which is what #141's field
     * comment on field-33 records happening. Bounded, it reports 0: the set
     * was called over and no detection began after that. That zero is
     * measured, not defaulted.
     *
     * ZERO IS WHAT IT WILL ALWAYS REPORT, and this pin says so rather than
     * leaving a reader to hope otherwise. The tap that ends the set is the tap
     * that stops the recording, so no sample and therefore no drive start can
     * lie beyond the boundary. The rule still runs, and a detection stamped
     * after the boundary is still excluded -- asserted here on invented
     * instants so the rule is pinned rather than assumed dead.
     */
    @Test
    fun `set 9 reports a measured zero where it used to report an absence`() {
        val everyDriveStartWithinTheSet = listOf(
            1_787_341_216_000L,
            1_787_341_220_000L,
            1_787_341_224_000L,
            tapLowerBoundMs,
        )
        assertEquals(
            0,
            SetEnd.of(boundedTrack()).detectionsAfter(everyDriveStartWithinTheSet),
            "every drive of a tap-ended set began before the tap",
        )
        assertNull(
            SetEnd.of(track(fixture)).detectionsAfter(everyDriveStartWithinTheSet),
            "and the unbounded track still refuses to answer, which is the state being replaced",
        )
        assertEquals(
            1,
            SetEnd.of(boundedTrack()).detectionsAfter(everyDriveStartWithinTheSet + (tapLowerBoundMs + 1)),
            "the rule is bounding, not merely reporting zero",
        )
    }

    /**
     * The earliest terminal instant wins, whichever word carries it.
     *
     * A set cannot be told to stop twice, and the second telling cannot un-tell
     * the first -- the reasoning [SetEnd] already records for taking the first
     * `Done`. The pair can only arise from a defect (the guide calling the
     * prescription through while the tap is in flight), and the answer has to
     * be total rather than depending on which word happens to sort first.
     */
    @Test
    fun `the earliest terminal cue bounds the set whichever word it is`() {
        assertEquals(
            SetEnd.Cued(2_000L),
            SetEnd.of(listOf(VoiceCue(1_000L, "Up"), VoiceCue(2_000L, SetEnd.STOPPED), VoiceCue(3_000L, SetEnd.DONE))),
            "the abandoned-set word came first",
        )
        assertEquals(
            SetEnd.Cued(2_000L),
            SetEnd.of(listOf(VoiceCue(1_000L, "Up"), VoiceCue(2_000L, SetEnd.DONE), VoiceCue(3_000L, SetEnd.STOPPED))),
            "and Done came first here",
        )
    }

    /**
     * A set already carrying the boundary is not given a second one.
     *
     * The near neighbour of the fix rather than a hypothetical: `endSet` is
     * re-entrant-guarded but the write path is not the only caller of the
     * voice, and a duplicate row would sit one instant later than the real
     * boundary, where [SetEnd] would ignore it and a reader counting terminal
     * rows would not.
     */
    @Test
    fun `a set already carrying the boundary does not ask for another`() {
        assertNull(
            SetEnd.terminalCall(guided = true, spoken = boundedTrack()),
            "the record already says when this set ended",
        )
    }
}
