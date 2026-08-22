package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.VoiceCue

/**
 * When the app told the lifter the set was over, and therefore which detected
 * drives are reps of that set.
 *
 * A guided set goes on recording after the metronome stops prescribing. The
 * eleven sets of session 32 that carry both a `Done` cue and an IMU stream keep
 * recording for 4.3 to 13.7 s past it, measured as last sample minus cue; five
 * of the seventeen never say `Done` at all and are not bounded. The sensor is
 * handled in that window: put down, unclipped, or carried while the load is
 * racked. Movement there segments like any other movement, so it arrives in the
 * rep list as a rep and sets whatever the rep list decides. Issue #125.
 *
 * THE RULE. A detection belongs to the set when its DRIVE BEGAN at or before
 * the instant the set was called over. Nothing else is asked of it.
 *
 * This is deliberately not a velocity, duration or range threshold. Any of those
 * would need a number per movement -- a rear delt fly and a Romanian deadlift
 * disagree about every one of them -- and a number tuned on the captures held
 * here would reclassify real reps on captures nobody has taken yet. A cue
 * timestamp is not tuned: it is an instant the app already wrote down, on the
 * same clock the samples carry.
 *
 * ## The three decisions this type makes, and why
 *
 * **A set with no end cue is [NotCued], and nothing is bounded.** An ad-hoc set
 * with the voice off never says `Done`, and neither does a guided set the
 * lifter ended before the prescription was called through -- five of the
 * seventeen sets of session 32. There is then no instant to bound at and none
 * may be invented -- the end of the stream is when the lifter got round to
 * tapping, not when the set ended, and the last sample would be a boundary that
 * excludes nothing while looking like a rule that ran. [detectionsAfter]
 * reports null there rather than 0, because "nothing said when the set ended"
 * and "the set ended and nothing came after" are different facts and a reader
 * of a stored analysis has to be able to tell them apart.
 *
 * **The boundary is the cue's own timestamp, not the end of its utterance.**
 * `VoiceCue` carries one instant, taken when the app decided to speak; TTS
 * completion is never observed, so an utterance-end boundary would have to be
 * the spoken length of a word plus a synthesiser's latency, neither of which is
 * measured anywhere -- the invented constant this rule exists to avoid. It also
 * keeps the rule reproducible: the cue track is persisted with every guided set,
 * so any figure derived under this rule can be re-derived from the export alone.
 * The cost is that the window is the tighter of the two candidates by the length
 * of one spoken word, so a drive begun in the moment between "Done" being
 * decided and "Done" being heard is dropped. Nothing here measures that
 * interval; it is a field question, not a claim.
 *
 * **A detection that straddles the boundary is KEPT.** What makes a movement a
 * rep of the set is that the lifter began it while the set was running; a drive
 * already under way when the cue fires was begun under the prescription. Bounding
 * on the drive's END instead would delete reps for a reason that is a known
 * measurement artefact rather than a fact about the lifter -- [RepSegmenter]
 * records that a phase boundary lands where |v| crosses the dead-band and that
 * the clipping grows with the phase's duration -- and it would fall hardest on
 * the last real rep of a fatigued set, the one best-to-last velocity loss is
 * entirely about. On session 32's set 6 this is the difference between keeping a
 * drive that began 1.83 s before the cue and ended 3.39 s after it, and dropping
 * it.
 *
 * ## Which cue, and on which clock
 *
 * [DONE] is the only cue in the vocabulary that means the set is over; every
 * other one calls a stroke or counts one. `Time` ends a TIMED set, and a timed
 * set publishes no rep list at all, so widening the vocabulary to it would add a
 * case with nothing in it.
 *
 * The FIRST `Done`, not the last. The boundary is the moment the lifter was told
 * to stop, and a second `Done` cannot un-tell them. Every capture held here
 * carries exactly one; taking the first makes the rule total without depending
 * on that. The known cost is on a sensor-counted set whose count runs ahead: the
 * app calls `Done` early, and reps performed after it are then excluded from the
 * figures as well as from the count. That set already published a wrong rep
 * count; this rule propagates that error into the velocities rather than
 * creating it, and bounding at the LAST `Done` instead would be worse on the
 * defect this exists for, because the tail is where the spurious detections are.
 *
 * Both instants are host arrival times in epoch milliseconds: `VoiceCue` is
 * stamped in the recorder and `ImuSample` when the notification lands. The
 * caller must pass a drive-start instant read off the SAMPLE, never a time
 * converted from the DSP's reconstructed clock. That conversion costs up to
 * 105.3 ms, the worst skew measured across the committed cue tracks; reading
 * the sample's own stamp avoids it entirely.
 */
sealed interface SetEnd {
    /** The set announced its own end at [atMs], host arrival clock. */
    data class Cued(val atMs: Long) : SetEnd

    /** Nothing on the record says when this set ended, so nothing is bounded. */
    data object NotCued : SetEnd

    /**
     * Was a drive beginning at [driveStartMs] begun while the set was running?
     *
     * Inclusive at the boundary: a drive beginning on the same millisecond the
     * cue was stamped was begun no later than the call.
     */
    fun startedWithinSet(driveStartMs: Long): Boolean = when (this) {
        is Cued -> driveStartMs <= atMs
        NotCued -> true
    }

    /**
     * How many of [driveStartMs] began after the set was called over, or null
     * when nothing said it was.
     *
     * Defined through [startedWithinSet] so the count and the rule cannot
     * disagree about a boundary case.
     */
    fun detectionsAfter(driveStartMs: List<Long>): Int? = when (this) {
        is Cued -> driveStartMs.count { !startedWithinSet(it) }
        NotCued -> null
    }

    companion object {
        /** The cue the metronome speaks when the prescription has been called through. */
        const val DONE = "Done"

        fun of(cues: List<VoiceCue>): SetEnd =
            cues.firstOrNull { it.cue == DONE }?.let { Cued(it.timestampMs) } ?: NotCued
    }
}
