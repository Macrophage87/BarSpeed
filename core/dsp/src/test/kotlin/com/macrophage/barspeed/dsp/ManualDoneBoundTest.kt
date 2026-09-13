package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.StartPhase
import com.macrophage.barspeed.model.Tempo
import com.macrophage.barspeed.model.VoiceCue
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What bounds the analysed rep list of a set the LIFTER counted. Issue #285.
 *
 * ## The mechanism these pins are about
 *
 * On a manually counted set with audio cues on, the lifter's `+1 REP` tap at
 * the planned count speaks `"Done"`: `VoiceMilestonePolicy.repMilestone`
 * returns that word at `repCount == plannedReps`, every spoken word is written
 * to the cue track, and `"Done"` is the word [SetEnd] reads as the set having
 * been called over. So the milestone the lifter hears as *"that is the number
 * you asked for"* is byte-identical, in the cue track, to the metronome's
 * terminal call, and [SetAnalyzer] drops every drive begun after it.
 *
 * ## Provenance of the cue track, which is SYNTHETIC
 *
 * The stream is `field-ohp-3010-8rep-s38-set05.csv`, committed and unmodified:
 * a seated overhead press recorded on field session 38, hand-counted 8 reps by
 * the lifter, analysed here concentric-first exactly as
 * [PrepDetectionFieldTest] analyses it.
 *
 * ITS OWN `-cues.csv` IS NOT USED BY [manualTrack], and cannot be: that set was
 * metronome-guided, so its track is a cadence's. **No manual-set capture is
 * committed to this repository at all** -- all 34 committed `-cues.csv` files
 * are guided or timed sets -- so the manual track below is BUILT, and its rows
 * sit at the drive-end instants of the first eight detections this stream
 * resolves. Those eight instants are literals rather than a re-run of the
 * segmenter, so a change to segmentation moves what these pins measure and not
 * what they measure it against. They were read at
 * `3ae8833dccbd23234ce4aa12f8b9b7a14568422a` from
 * `RepSegmenter.segmentDetailed`'s spans over this stream.
 *
 * The words are `VoiceMilestonePolicy.repMilestone`'s own, for `plannedReps`
 * 8: `Rep 1` through `Rep 6`, then `Last rep` at rep 7 and `Done` at rep 8.
 *
 * ## What these pins do NOT say
 *
 * The lifter performed 8 reps and this stream resolves 15 detections unbounded
 * -- #284 measured this concentric-first corpus over-counting six captures of
 * six, by +1 to +4. So the seven detections that begin after the synthetic
 * `Done` are NOT seven over-plan reps, and nothing here claims the 15-detection
 * figure is nearer the truth than the 8-detection one. What is pinned is which
 * DECISION the cue track makes, not whether the segmenter was right.
 */
class ManualDoneBoundTest {
    private fun load(name: String) =
        ImuCsv.decode(javaClass.getResourceAsStream("/$name")!!.readBytes().decodeToString())

    private val fixture = "field-ohp-3010-8rep-s38-set05"

    /** The stream's exported geometry: a seated press, drive up, dead start. */
    private val press = LiftDirection(startsWith = StartPhase.CONCENTRIC)

    private val samples by lazy { load("$fixture.csv") }

    /** The capture's own cue track -- a metronome's, and used only as one. */
    private fun guidedTrack() = CueTrack.read(fixture).map { VoiceCue(it.timestampMs, it.label) }

    /**
     * A manual set's cue track, synthesised. See this class's KDoc for the
     * instants' provenance and for why none could be lifted from the corpus.
     */
    private val manualTrack = listOf(
        VoiceCue(1_788_516_176_165L, "Rep 1"),
        VoiceCue(1_788_516_178_653L, "Rep 2"),
        VoiceCue(1_788_516_181_443L, "Rep 3"),
        VoiceCue(1_788_516_184_447L, "Rep 4"),
        VoiceCue(1_788_516_185_703L, "Rep 5"),
        VoiceCue(1_788_516_189_363L, "Rep 6"),
        VoiceCue(1_788_516_190_773L, "Last rep"),
        VoiceCue(1_788_516_193_564L, "Done"),
    )

    /** The instant the synthetic milestone lands, which is rep 8's drive end. */
    private val milestoneAtMs = 1_788_516_193_564L

    /** A manual set is one with no tempo: nothing paced it and nobody but the lifter counted. */
    private fun manualTargets() = SetTargets(plannedReps = 8)

    /** A guided set is one with a tempo: the cadence counted it and spoke its own `Done`. */
    private fun guidedTargets() = SetTargets(plannedReps = 8, tempo = Tempo.parse("3010"))

    private fun analyse(cues: List<VoiceCue>, targets: SetTargets) =
        SetAnalyzer.analyze(samples, press, targets = targets, cues = cues)

    // ------------------------------------------------------------------
    // Today's behaviour, pinned before it is changed.
    // ------------------------------------------------------------------

    /**
     * The word the lifter's own tap writes is the word the rule reads.
     *
     * Asserted against [SetEnd] directly, not through the analyzer, because
     * this is the whole of the mechanism: nothing downstream can tell the two
     * `Done`s apart because nothing upstream wrote anything that differs.
     */
    @Test
    fun `today a manual set's rep-count milestone is read as the set being called over`() {
        assertEquals(
            SetEnd.Cued(milestoneAtMs),
            SetEnd.of(manualTrack),
            "the milestone at the planned count reads as a terminal cue",
        )
        assertEquals(
            SetEnd.DONE,
            manualTrack.last().cue,
            "and it is the same string, so no reader downstream can separate them",
        )
    }

    /**
     * The cost, in the figures the lifter reads.
     *
     * The unbounded figures are the same stream with no cue track at all, which
     * is what a manual set recorded with audio cues OFF publishes today -- so
     * these two rows are the same set recorded with one display toggle moved.
     */
    @Test
    fun `today the manual milestone drops the detections that began after the tap`() {
        val bounded = analyse(manualTrack, manualTargets())
        assertEquals(8, bounded.reps.size, "detections kept once the tap bounds the set")
        assertEquals(7, bounded.detectionsAfterSetEndCue, "detections dropped by the tap")
        assertEquals(62.2, bounded.velocityLossPct!!, 0.05, "velocity loss over the bounded list")

        val unbounded = analyse(emptyList(), manualTargets())
        assertEquals(15, unbounded.reps.size, "detections this stream resolves with nothing bounding it")
        assertEquals(null, unbounded.detectionsAfterSetEndCue, "no cue track says when the set ended")
        assertEquals(79.2, unbounded.velocityLossPct!!, 0.05, "velocity loss over the whole stream")
    }

    /**
     * The case that must NOT move: a cadence's own terminal word.
     *
     * This is the capture's real track, and the set really was guided, so this
     * row is the behaviour issue #125 added and is not a characterization of a
     * defect.
     */
    @Test
    fun `today a guided set's Done bounds the analysed rep list`() {
        val analysis = analyse(guidedTrack(), guidedTargets())
        assertEquals(13, analysis.reps.size, "detections kept inside the cadence's own call")
        assertEquals(2, analysis.detectionsAfterSetEndCue, "detections dropped by the cadence's Done")
    }

    /**
     * The other case that must not move: `Set ended`.
     *
     * Nothing but the app's own set-end call speaks this word --
     * `SetEnd.terminalCall` is its only writer -- so it is never a milestone
     * and bounds whoever counted the set.
     */
    @Test
    fun `today Set ended bounds a set with no tempo`() {
        val stopped = manualTrack.dropLast(1) + VoiceCue(milestoneAtMs, SetEnd.STOPPED)
        val analysis = analyse(stopped, manualTargets())
        assertEquals(8, analysis.reps.size, "detections kept inside the app's own set-end call")
        assertEquals(7, analysis.detectionsAfterSetEndCue, "detections dropped by it")
    }
}
