package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.VoiceCue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
}
