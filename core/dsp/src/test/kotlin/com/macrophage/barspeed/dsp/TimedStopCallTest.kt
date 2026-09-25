package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.VoiceCue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A timed set the lifter ends before its target says the word a guided set
 * says when it ends early, and writes it into the cue track (#288).
 *
 * ## Provenance
 *
 * `field-ropedeadhang-hold30-s41-set21-cues.csv` is field-41 set 21's cue
 * stream, copied byte for byte out of `set21_rope_dead_hang_cues.csv` in that
 * session's raw archive (`appVersion` 0.1.52, recorded 2026-09-11) and
 * compared against the zip entry itself. A 30 s rope dead hang the lifter came
 * off at 5 s: the archive's `meta.json` has `workStartedAt_ms` 1789124896358
 * and `endedAt_ms` 1789124901799, and `session.json` publishes `duration_s`
 * 5, `failed` and `failedByLifter` true, `limiter` grip. Its whole track is
 * three rows -- `Ready`, `Brace`, `Hold` -- and no terminal word.
 *
 * The owner, 2026-09-12: "It just asked for the reason and marked it failed."
 * The failure was recorded; what was missing was any word at the break.
 *
 * ## The word, and why it is not a new one
 *
 * `Set ended` ([SetEnd.STOPPED]) is what a guided set says when it ends
 * without `Done`, and its KDoc already argues why it is spoken rather than
 * written silently and why it differs from the completion word. A timed set's
 * completion word is `Time`; a timed set ended before it is the same case as
 * a guided set ended before `Done`, so it takes the same word.
 *
 * ## What this does not claim
 *
 * What the lifter hears -- nothing here executes a speech engine -- or that
 * the instant bounds anything on this capture: the stream's last sample is
 * 1789124901766, before the write, so the bound excludes nothing and what
 * changes is that the window HAS an upper bound and the record an end word.
 */
class TimedStopCallTest {
    private val fixture = "field-ropedeadhang-hold30-s41-set21"
    private val endedAtMs = 1789124901799L

    private fun track(): List<VoiceCue> =
        CueTrack.read(fixture).map { VoiceCue(timestampMs = it.timestampMs, cue = it.label) }

    private fun timedCall(clockEnded: Boolean, voiceSpeaks: Boolean, spoken: List<VoiceCue>) = SetEnd.terminalCall(
        guided = false,
        timed = true,
        clockEnded = clockEnded,
        voiceSpeaks = voiceSpeaks,
        spoken = spoken,
    )

    @Test
    fun `the committed track is the set's, and it ends on no terminal word`() {
        assertEquals(listOf("Ready", "Brace", "Hold"), track().map { it.cue })
        assertEquals(SetEnd.NotCued, SetEnd.calledOver(track()))
    }

    @Test
    fun `a hang broken at 5 s says Set ended`() {
        assertEquals(
            SpokenCall(SetEnd.STOPPED, listOf(SetEnd.STOPPED)),
            timedCall(clockEnded = false, voiceSpeaks = true, spoken = track()),
        )
    }

    @Test
    fun `the word it writes gives the set an end on the record and its window an upper bound`() {
        val written = timedCall(clockEnded = false, voiceSpeaks = true, spoken = track())?.recorded.orEmpty()
        val after = track() + written.map { VoiceCue(endedAtMs, it) }
        assertEquals(SetEnd.Cued(endedAtMs), SetEnd.calledOver(after), "the record's set-over instant")
        assertEquals(SetEnd.Cued(endedAtMs), SetEnd.of(after, cadenceGuided = false), "the analysis bound")
    }

    // ---- what must not move ------------------------------------------------

    @Test
    fun `a timed set the clock ended says nothing more than Time`() {
        val completed = track() + VoiceCue(1789124926358L, TimedSetVoice.TIME_UP)
        assertNull(timedCall(clockEnded = true, voiceSpeaks = true, spoken = completed))
        // The voice off: the clock ended it and no Time was spoken. A
        // `Set ended` here would say the lifter stopped a set that ran out.
        assertNull(timedCall(clockEnded = true, voiceSpeaks = false, spoken = track()))
    }

    @Test
    fun `a timed set whose countdown was silent stays silent at its end`() {
        assertNull(timedCall(clockEnded = false, voiceSpeaks = false, spoken = track()))
    }

    @Test
    fun `a timed set whose record already carries Time is not given a second word`() {
        val completed = track() + VoiceCue(1789124926358L, TimedSetVoice.TIME_UP)
        assertNull(timedCall(clockEnded = false, voiceSpeaks = true, spoken = completed))
    }
}
