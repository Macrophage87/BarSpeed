package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.VoiceCue

/**
 * When the app told the lifter the set was over, and therefore which detected
 * drives are reps of that set.
 *
 * A guided set goes on recording after the metronome stops prescribing. The
 * eleven sets of session 32 that carry both a `Done` cue and an IMU stream keep
 * recording for 4.3 to 13.7 s past it, measured as last sample minus cue. The
 * sensor is handled in that window: put down, unclipped, or carried while the
 * load is racked. Movement there segments like any other movement, so it
 * arrives in the rep list as a rep and sets whatever the rep list decides.
 * Issue #125.
 *
 * The other five sets of that session say nothing at all. They are exactly the
 * five the lifter failed, and until issue #141 a guided set ended early emitted
 * no terminal cue, so the rule had no instant and declined to bound the sets
 * whose figures were least trustworthy. [STOPPED] is now spoken and recorded at
 * that ending. What that buys is NOT a tighter rep list -- see [terminalCall]
 * for why it cannot be -- it is that the set stops being an absence.
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
 * with the voice off says neither terminal word, and neither does any set
 * recorded before the app wrote cue tracks. There is then no instant to bound
 * at and none may be invented -- the end of the stream is when the lifter got
 * round to tapping, not when the set ended, and the last sample would be a
 * boundary that excludes nothing while looking like a rule that ran.
 * [detectionsAfter] reports null there rather than 0, because "nothing said
 * when the set ended" and "the set ended and nothing came after" are different
 * facts and a reader of a stored analysis has to be able to tell them apart.
 *
 * A guided set the lifter ended early used to be in that group and no longer
 * is (#141): it now says [STOPPED] and reports 0.
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
 * [TERMINAL_CUES] is the whole of the vocabulary that means the set is over --
 * [DONE] when the prescription was called through, [STOPPED] when it was not
 * -- see [STOPPED] for the two populations that covers. Every other cue calls
 * a stroke or counts one. `Time`
 * ends a TIMED set, and a timed set publishes no rep list at all, so widening
 * the vocabulary to it would add a case with nothing in it.
 *
 * The EARLIEST terminal cue by instant, not the last and not the first in list
 * order. The boundary is the moment the lifter was told to stop, and a second
 * telling cannot un-tell them. Every capture held here carries at most one, and
 * cue tracks are written in the order they are spoken, so on every one of them
 * the earliest and the first are the same row; taking the earliest makes the
 * rule total without depending on either. The known cost is on a
 * sensor-counted set whose count runs ahead: the app calls `Done` early,
 * and reps performed after it are then excluded from the
 * figures as well as from the count. That set already published a wrong rep
 * count; this rule propagates that error into the velocities rather than
 * creating it, and bounding at the LAST `Done` instead would be worse on the
 * defect this exists for, because the tail is where the spurious detections are.
 *
 * Both instants are host arrival times in epoch milliseconds: `VoiceCue` is
 * stamped in the recorder and `ImuSample` when the notification lands. The
 * caller must pass a drive-start instant read off the SAMPLE, never a time
 * converted from the DSP's reconstructed clock. That conversion costs up to
 * 105.3 ms, the worst skew measured across the four barbell captures
 * `CueTrack.MAX_SKEW_MS` is derived from; reading the sample's own stamp
 * avoids it entirely.
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
        /**
         * The cue the metronome speaks when the prescription has been called
         * through.
         *
         * Read off [CadenceVoice], which is the thing that SPEAKS it, rather
         * than restated as a second literal here. The two were separate copies
         * of `"Done"` and nothing checked they agreed -- a rename on the
         * emitting side would have left this rule bounding on a word the app
         * had stopped saying, and every guided set would have gone quietly
         * unbounded with no test failing.
         */
        const val DONE = CadenceVoice.DONE

        /**
         * The cue the app speaks when a guided set ends without the guide
         * having called [DONE].
         *
         * TWO populations, not one, and the second is an ordinary completion.
         * [terminalCall] asks whether the set is already bounded, never how it
         * ended, so this word goes on any guided set whose track carries no
         * terminal cue. That is the lifter stopping early -- the failure tile,
         * or the early exit offered during the lead-in (#186) -- and ALSO a
         * guided set the plan or the ad-hoc form gave no rep count at all
         * (`PlannedSlot.reps` is nullable; the ad-hoc field may be left blank).
         * `GuidedCadenceRunner` speaks [DONE] only where `plannedReps != null`,
         * and `RecordViewModel.setTargetMet` offers such a set the effort grid
         * from the start, so it finishes normally and carries this word. Read
         * `failed` for whether a set was failed; this cue does not say.
         *
         * A different word from [DONE] on purpose, and the choice is argued
         * rather than incidental. `Done` means the prescription was delivered;
         * saying it to someone who just failed a set would be both a wrong
         * thing to hear and a wrong thing to record, and an archive in which
         * both endings carry one word cannot tell a completed set from an
         * abandoned one by its cue track at all.
         *
         * SPOKEN, not a silent marker written into the track. The alternative
         * considered was a row the app writes without uttering, which would
         * have kept the vocabulary at one terminal word -- and it was rejected
         * on issue #176's rule: the cue track is a record of what the app
         * SAID, and a row nobody heard makes that false again in a new place,
         * one release after it was made true. It would also need a way for a
         * reader to tell a spoken row from a silent one, which the row format
         * has no field for. Speaking it costs one short utterance and buys the
         * confirmation the lifter currently does not get on exactly the sets
         * that ended badly.
         *
         * Two words rather than one, and not a digit or a stroke name: the
         * vocabulary already carries bare digits as tempo counts and `Up`,
         * `Down`, `Hold`, `Drive`, `Return`, `Brace`, `Ready` and `Time` as
         * calls, and #147 rejected a form that could be confused with one of
         * those.
         */
        const val STOPPED = "Set ended"

        /**
         * Every cue that means the set is over, in no particular order --
         * [of] takes the earliest by INSTANT, not by this list's order.
         */
        val TERMINAL_CUES = setOf(DONE, STOPPED)

        fun of(cues: List<VoiceCue>): SetEnd {
            val terminal = cues.filter { it.cue in TERMINAL_CUES }.minByOrNull { it.timestampMs }
            return terminal?.let { Cued(it.timestampMs) } ?: NotCued
        }

        /**
         * What to say and write when a set ends, or null when nothing should
         * be said.
         *
         * [guided] is whether a CADENCE was running -- `RecordViewModel`'s
         * `guidedSet`, which is `prepCase == CUED`. [spoken] is the set's cue
         * track as it stands at the moment the set is ending.
         *
         * Scoped to guided sets deliberately, and #141 argues why the other
         * two cases are separate decisions. An unguided set ends by the same
         * tap, and bounding those would change the figures of every manual set
         * recorded from here on -- a far larger population, and one no capture
         * held here measures. A timed set ends on `Time` and publishes no rep
         * list, so there is nothing for a boundary to bound.
         *
         * The question asked is "is this set already bounded", through [of],
         * rather than "was `Done` spoken". Those are the same question today
         * and the first is the one that stays right: a second terminal word
         * added to [TERMINAL_CUES] later would otherwise get a duplicate
         * boundary written beside it, and the duplicate would be the earlier
         * instant's neighbour rather than a visible defect.
         *
         * WHAT THIS DOES NOT BUY, stated here because the issue that asked for
         * it assumed otherwise. `endSet` cancels the sample collectors before
         * it reads the clock, so the last sample of a tap-ended set is never
         * later than the tap and a boundary placed there cannot exclude a
         * detection -- on session 32's five failed sets, or on any other.
         * Measured on that session: the eleven completed sets record 4.3 to
         * 13.7 s past `Done`, the five failed ones 0.482 to 0.832 s past their
         * last cue. What the boundary changes is that [detectionsAfter] answers
         * 0 instead of null, that `RestClockPolicy`'s seed instant exists, that
         * an analysis no longer has to exclude the failed sets, and that the
         * lifter hears the set end.
         */
        fun terminalCall(guided: Boolean, spoken: List<VoiceCue>): SpokenCall? =
            if (guided && of(spoken) is NotCued) SpokenCall(STOPPED, listOf(STOPPED)) else null
    }
}
