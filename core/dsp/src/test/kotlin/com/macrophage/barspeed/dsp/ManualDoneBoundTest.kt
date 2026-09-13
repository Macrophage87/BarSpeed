package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.StartPhase
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
 * returns that word at `repCount == plannedReps`, and every spoken word is
 * written to the cue track. So the milestone the lifter hears as *"that is the
 * number you asked for"* is byte-identical, in the cue track, to the
 * metronome's terminal call -- and the analyser used to read it as one, which
 * dropped every drive begun after the tap from the figures.
 *
 * The rule these pins hold is that the WORD's AUTHOR decides, and that the
 * prescription is what says who it was: only a cadence speaks `Done` as a call
 * to stop lifting, and `Set ended` is the app's own and is never a milestone.
 * The owner's rule behind it -- a manual count is the count, and a lifter who
 * taps past the prescription did those reps.
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
 * DECISION the cue track makes, not whether the segmenter was right. The
 * segmenter's over-count on concentric-first captures is #284's subject and is
 * untouched here.
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

    /** A manual set: no cadence ran, so nobody but the lifter counted it. */
    private fun manualTargets() = SetTargets(plannedReps = 8, cadenceGuided = false)

    /** A guided set: a cadence ran, counted the set and spoke its own `Done`. */
    private fun guidedTargets() = SetTargets(plannedReps = 8, cadenceGuided = true)

    private fun analyse(cues: List<VoiceCue>, targets: SetTargets) =
        SetAnalyzer.analyze(samples, press, targets = targets, cues = cues)

    // ------------------------------------------------------------------
    // The two questions, separated. See SetEnd for why they are two.
    // ------------------------------------------------------------------

    /**
     * `calledOver` answers the RECORD's question and is unchanged by any of
     * this: a terminal word was spoken on this set and here is when.
     *
     * `RestClockPolicy`'s seed instant is this question, which is why it is
     * still the milestone's own instant on a manual set. The rest after a set
     * begins when the lifter stopped lifting, whoever said so.
     */
    @Test
    fun `the record's answer is any terminal word, whoever spoke it`() {
        assertEquals(
            SetEnd.Cued(milestoneAtMs),
            SetEnd.calledOver(manualTrack),
            "the milestone is on the record and the record says so",
        )
        assertEquals(
            SetEnd.Cued(milestoneAtMs),
            SetEnd.calledOver(manualTrack.dropLast(1) + VoiceCue(milestoneAtMs, SetEnd.STOPPED)),
            "and so is the app's own set-end call",
        )
        assertEquals(
            SetEnd.NotCued,
            SetEnd.calledOver(manualTrack.dropLast(1)),
            "a track carrying no terminal word at all says nothing",
        )
    }

    /**
     * `of` answers what may BOUND THE REP LIST, and takes whether a cadence ran.
     *
     * Both branches over both words, because the rule is a two-by-two and only
     * one of its four cells changes.
     */
    @Test
    fun `the bound takes whether a cadence ran, and only Done depends on it`() {
        val stopped = manualTrack.dropLast(1) + VoiceCue(milestoneAtMs, SetEnd.STOPPED)
        assertEquals(
            SetEnd.Cued(milestoneAtMs),
            SetEnd.of(manualTrack, cadenceGuided = true),
            "a cadence's Done bounds",
        )
        assertEquals(
            SetEnd.NotCued,
            SetEnd.of(manualTrack, cadenceGuided = false),
            "a milestone's Done does not",
        )
        assertEquals(
            SetEnd.Cued(milestoneAtMs),
            SetEnd.of(stopped, cadenceGuided = true),
            "Set ended bounds a guided set",
        )
        assertEquals(
            SetEnd.Cued(milestoneAtMs),
            SetEnd.of(stopped, cadenceGuided = false),
            "and bounds a set no cadence ran on, because only the app's own set end writes it",
        )
    }

    /**
     * The default keeps every detection, which is the direction that loses no
     * figure. A caller that says nothing gets no boundary rather than a
     * boundary it did not ask for.
     */
    @Test
    fun `a prescription that says nothing declares no cadence`() {
        assertEquals(false, SetTargets().cadenceGuided, "the default")
        assertEquals(
            SetEnd.NotCued,
            SetEnd.of(manualTrack, SetTargets().cadenceGuided),
            "so nothing is bounded by default",
        )
    }

    // ------------------------------------------------------------------
    // What the analyzer does with each of the two words.
    // ------------------------------------------------------------------

    /**
     * The word the lifter's own tap writes is the same word the cadence writes.
     *
     * Asserted against [SetEnd] directly, not through the analyzer, because
     * this is the whole of the mechanism: nothing downstream can tell the two
     * `Done`s apart from the STRING, so the prescription has to say.
     */
    @Test
    fun `a manual set's rep-count milestone is the same word a cadence's call is`() {
        assertEquals(
            SetEnd.Cued(milestoneAtMs),
            SetEnd.calledOver(manualTrack),
            "the milestone at the planned count reads as a terminal cue",
        )
        assertEquals(
            SetEnd.DONE,
            manualTrack.last().cue,
            "and it is the same string, so no reader downstream can separate them",
        )
    }

    /**
     * THE DIFFERENTIAL. A manual set's milestone must not move the rep list.
     *
     * The owner's rule: a manual count is the count. A lifter who taps more
     * reps than were planned did those reps, and the analysis includes them.
     *
     * Pinned against the same stream with NO cue track at all -- which is what
     * a manual set recorded with audio cues OFF publishes -- because the two
     * must now agree. Before this, they did not: the audio-cues toggle moved a
     * stored rep list by seven detections and 17 points of velocity loss, which
     * is the "one flag, several jobs" class with a display switch on one end
     * and the archive on the other.
     */
    @Test
    fun `a manual set's rep-count milestone does not bound the analysed rep list`() {
        val spoken = analyse(manualTrack, manualTargets())
        val silent = analyse(emptyList(), manualTargets())
        assertEquals(15, spoken.reps.size, "detections kept with the milestone on the record")
        assertEquals(null, spoken.detectionsAfterSetEndCue, "no boundary ran, which is not a boundary that dropped 0")
        assertEquals(79.2, spoken.velocityLossPct!!, 0.05, "velocity loss over the whole set")
        assertEquals(silent.reps.size, spoken.reps.size, "audio cues on or off, the same set")
        assertEquals(silent.velocityLossPct, spoken.velocityLossPct, "and the same figure")
    }

    /**
     * The case that must NOT move: a cadence's own terminal word.
     *
     * This is the capture's real track, and the set really was guided, so this
     * row is the behaviour issue #125 added and is not a characterization of a
     * defect.
     */
    @Test
    fun `a guided set's Done still bounds the analysed rep list`() {
        val analysis = analyse(guidedTrack(), guidedTargets())
        assertEquals(13, analysis.reps.size, "detections kept inside the cadence's own call")
        assertEquals(2, analysis.detectionsAfterSetEndCue, "detections dropped by the cadence's Done")
    }

    /**
     * The other case that must not move: `Set ended`.
     *
     * Nothing but the app's own set-end call speaks this word --
     * `SetEnd.terminalCall` is its only writer -- so it is never a milestone
     * and bounds whoever counted the set. This is the cell of the two-by-two
     * that makes the rule a rule about the WORD's author rather than about
     * manual sets.
     */
    @Test
    fun `Set ended bounds a set no cadence ran on`() {
        val stopped = manualTrack.dropLast(1) + VoiceCue(milestoneAtMs, SetEnd.STOPPED)
        val analysis = analyse(stopped, manualTargets())
        assertEquals(8, analysis.reps.size, "detections kept inside the app's own set-end call")
        assertEquals(7, analysis.detectionsAfterSetEndCue, "detections dropped by it")
        assertEquals(
            62.2,
            analysis.velocityLossPct!!,
            0.05,
            "velocity loss over the bounded list -- the figure the published 1.20 entry and " +
                "SessionExport's KDoc both state",
        )
    }
}
