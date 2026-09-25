package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.VoiceCue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A hold's `Time` bounds its working window the way `Done` bounds a guided
 * set's (#295).
 *
 * Measured in field-42 (app 0.1.52, 2026-09-07): all six hold streams of sets
 * 14-16 publish `rollExcursionBasis: "fromWorkStart"` -- the word for "nothing
 * said when the set ended" -- although each track ends on `Time`, spoken on
 * the tick 30.014 to 30.020 s after work start, and the rest clock already
 * takes the set's end from the instant the app froze the write 1 ms later.
 *
 * `field-ropefarmershold-hold30-s42-set16` is set 16's role `a` stream and
 * cue track, committed for #259 and described in `HoldReleaseFieldTest`. Its
 * work started at 1788776811073 (the archive's `workStartedAt_ms`, the same
 * instant as the clock start) and its `Time` is at 1788776841087.
 *
 * WHAT THIS DOES NOT CLAIM. On this capture the figure cannot move: the stream
 * stops before `Time`, so the windowed sweep is the same either way, and only
 * the basis word changes. That is pinned rather than assumed. A capture that
 * kept recording past `Time` would move the figure too; none is held here.
 */
class HoldTerminalCueFieldTest {
    private val fixture = "field-ropefarmershold-hold30-s42-set16"
    private val workStartedAtMs = 1788776811073L
    private val timeAtMs = 1788776841087L

    private fun samples(): List<ImuSample> =
        ImuCsv.decode(javaClass.getResourceAsStream("/$fixture.csv")!!.readBytes().decodeToString())

    private fun cues(): List<VoiceCue> =
        CueTrack.read(fixture).map { VoiceCue(timestampMs = it.timestampMs, cue = it.label) }

    @Test
    fun `a hold's Time bounds its window and seeds its rest`() {
        assertEquals(TimedSetVoice.TIME_UP, cues().last().cue, "the premise: the track ends on Time")
        assertEquals(timeAtMs, cues().last().timestampMs, "the Time instant")
        // No cadence runs on a hold, so `Done` could not bound it; `Time` must.
        assertEquals(SetEnd.Cued(timeAtMs), SetEnd.of(cues(), cadenceGuided = false), "the analysis bound")
        assertEquals(SetEnd.Cued(timeAtMs), SetEnd.calledOver(cues()), "the record's set-over instant")
    }

    @Test
    fun `a hold stream with a Time cue publishes the working window`() {
        val measured = RollExcursion.of(samples(), workStartedAtMs, SetEnd.of(cues(), cadenceGuided = false))
        assertEquals(RollExcursion.Basis.WORKING_WINDOW, measured?.basis, "the basis word the archive publishes")
    }

    @Test
    fun `on this capture only the word moves, because the stream stops before Time`() {
        assertTrue(samples().last().timestampMs < timeAtMs, "the premise: no sample after Time")
        val bounded = RollExcursion.of(samples(), workStartedAtMs, SetEnd.Cued(timeAtMs))
        val unbounded = RollExcursion.of(samples(), workStartedAtMs, SetEnd.NotCued)
        assertEquals(unbounded?.degrees, bounded?.degrees, "the sweep is the same either way")
    }
}
