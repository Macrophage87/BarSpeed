package com.macrophage.barspeed.dsp

/**
 * One beat of a guided rep: a label held for a whole number of seconds.
 *
 * [spokenLabel] is what the voice says on entering the beat, or null when the
 * beat opens in silence. [isStroke] separates a movement stroke, which counts
 * itself out loud when long enough, from a pause, which does not.
 * [suppressFirstCount] drops a stroke's opening count to make room for a merged
 * rep announcement -- see [CadencePlan].
 */
data class CadenceBeat(
    val label: String,
    val seconds: Int,
    val spokenLabel: String?,
    val isStroke: Boolean,
    val suppressFirstCount: Boolean = false,
)

/**
 * The beats a guided rep is played as, derived from a [TempoSchedule].
 *
 * This arithmetic used to live inside GuidedCadenceRunner in `:app`, which has
 * no test source set, so nothing could assert that the cycle the metronome
 * plays is the cycle the plan prescribes. It was not, on every set the app has
 * ever paced -- issue 106.
 *
 * ## Whole seconds
 *
 * The runner can only sleep in one-second units, so every beat is a whole
 * number of seconds and a fractional prescription cannot be delivered exactly.
 * [deliveredCycleS] reports what will actually be played, so any shortfall is
 * visible rather than hidden. `Tempo.parse` accepts `"3-0-1.5-0"`; no captured
 * session has ever used such a tempo.
 *
 * ## Where the rep announcement goes, and why it is not free
 *
 * `VoiceCounter` speaks with `TextToSpeech.QUEUE_FLUSH`: every utterance
 * cancels the one before it. An announcement therefore needs silence after it
 * or it is cut off mid-word -- and the runner says something every second, the
 * stroke label and then a count on each following second. **The gap between
 * utterances is one second regardless of how long the stroke is**, so a
 * three-second eccentric buys no more room than a one-second drive. That is
 * measurable in the committed cue tracks: `Down` 594039, `1` 595041, `2`
 * 596043, `Up` 597043.
 *
 * Three places it can go, tried in this order, and one it cannot:
 *
 * 1. **A closing pause the prescription already provides**, when it is at least
 *    a second. Free, and preferred.
 * 2. **Merged into the next rep's FIRST stroke call** -- "Down, rep three" --
 *    with that stroke's FIRST count suppressed so the window is two seconds
 *    instead of one. Costs one tempo count and no time. Possible only when the
 *    stroke has a count to give up, which is exactly [MERGE_MIN_STROKE_S].
 * 3. **Merged into the next rep's SECOND stroke call**, on identical terms,
 *    when the first stroke is too short to carry it. A pauseless tempo that
 *    opens on a one second stroke keeps all its room in the other one: a leg
 *    curl's `1030` pulls for a second and lowers for three, a leg press's
 *    `2010` drives for a second and lowers for two. Those sets ran with no
 *    spoken count at all, which is issue 147 and a complaint from the gym.
 * 4. **Not spoken.** The rep number stays on screen, driven by `onRepCounted`,
 *    and that on-screen number is the metronome's own count rather than the
 *    sensor's.
 *
 * The behaviour before issue 106 was a fifth option: insert a one-second beat
 * the prescription did not ask for.
 *
 * ## No home moves a beat
 *
 * All four cases only decide what is SAID on seconds the prescription already
 * asked for. None lengthens a stroke, a pause or a cycle. That is the
 * obligation two shipped releases broke -- a flat allowance for everything
 * after the first stroke, then the one-second floor of issue 106, +1.00 s per
 * rep on 31 of 31 captured sets -- and `CadencePlanTest` pins it against the
 * prescription for 1,380 (tempo, lift) pairs rather than against the cycle
 * total, which a second moved from one beat into another leaves unchanged.
 *
 * ## What case 3 costs
 *
 * A place in the rep, and one tempo count. The call lands one stroke into the
 * rep instead of at its start: a second late on a `1030` leg curl, two on a
 * `2011` with an isometric pause between the strokes. It still rides a movement
 * word the lifter is listening for, and the number it carries counts FINISHED
 * reps and instructs no movement, so arriving late cannot be mistaken for a cue
 * to move.
 *
 * This file used to end that paragraph by deferring the rest to a session --
 * *"whether a count that lands mid-rep is followable at gym speed is a question
 * for a session and not for this file"* -- and then answered half of it in
 * advance: *"`\"Last rep\"` travels the same channel and arrives after the final
 * rep has begun. On these pairs it was not spoken at all before, so this is an
 * improvement bounded by that lateness, not a regression."*
 *
 * **The session happened and refuted that, and it is deleted rather than
 * softened.** From the gym, 2026-08-28: *"It sometimes says 'last rep', done,
 * with no rep in between."* Re-derived from that session's sixteen cue tracks:
 * THREE sets carry the call with a whole rep still in front of it -- sets 1, 2
 * and 3, eccentric-first `3010`, reading 1.001 s from their last stroke word to
 * `Done` -- and THIRTEEN do not. Of the thirteen, eleven read 2.00 s, set 5
 * reads 3.002 s because its closing stroke is three seconds rather than two,
 * and set 4 says no `Done` at all so nothing can be measured from it (#141,
 * firing in the field). 3 + 11 + 1 + 1 = 16.
 *
 * The trade the deleted sentence assumed -- late beats silent -- is the wrong
 * way round for a WARNING: an announcement implying a rep the lifter cannot
 * perceive reads as the app losing count, and costs more trust than saying
 * nothing. Issue #173.
 *
 * What survives the refutation, because it was never the same claim: the
 * FINISHED-rep count. `"Rep 3"` reports and instructs nothing, the lifter has
 * nothing to do differently on hearing it late, and issue #147 added it to
 * exactly these plans because they had no spoken count at all. It stays, and it
 * is what [announcementAfter] returns here on every rep but the last. Whether a
 * count that lands mid-rep is followable at gym speed is still a question for a
 * session; what the session answered is the warning, and only the warning.
 *
 * So on this case the warning is not spoken. It is not RESCHEDULED, and the
 * reason is structural rather than a preference: this case is reached only
 * because the closing pause and the opening stroke were both ruled out, so
 * every beat of the rep before its last is shorter than [MERGE_MIN_STROKE_S]
 * and there is no unoccupied slot to move the warning into. The choice was late
 * or silent, never early. See [warningWouldOpenTheLastBeatOfItsOwnRep].
 *
 * One consequence is worth naming because it is a gain rather than a loss: the
 * carrying stroke gives up its first tempo count only when an announcement
 * actually rode it, so a suppressed warning hands the final rep its count back.
 * The second the archive used to leave silent now carries a `1`.
 *
 * The alternative considered and rejected was to speak the ordinary
 * finished-rep count in the warning's place -- `"Rep 11"` on the twelfth of
 * twelve, which is true and carries the same information to a lifter who knows
 * their target. It was rejected because it invents an utterance neither issue
 * asked for on the final rep of every set of this shape, and because it keeps
 * the tempo count suppressed, which is the silence the whole investigation
 * started from. That the lifter therefore hears no count higher than
 * `plannedReps - 2` on these plans is a real cost and a `[Field]` question, not
 * a settled one.
 *
 * A second cost sits beside it, and the returned tempo count is what creates
 * it: rep 1 and the final rep are then the only two reps carrying no merged
 * call, so they carry the same words at the same offsets and `Done` is the
 * set's only ending marker. `LastRepWarningTest` asserts that rather than
 * claiming it. Whether the lifter hears the set ending is a `[Field]` question
 * too.
 *
 * Nothing here touches `RecordViewModel.announceRepMilestones`, the UNGUIDED
 * counter. That one speaks its own `"Last rep"` at the instant a rep is
 * counted, not on a metronome schedule, so the whole final rep is still ahead
 * of the lifter when it arrives.
 *
 * ## What stays uncarryable, and what was rejected
 *
 * A tempo whose BOTH strokes are one second with no closing pause -- `1010`,
 * `1110` -- has a word in every second of its cycle and keeps case 4. Four
 * ways of forcing a call into it were considered and rejected:
 *
 * - **Speak a bare digit** rather than `"Rep 3"`, on the theory that a shorter
 *   utterance survives a shorter window. Bare digits are already the most
 *   overloaded string in the cue vocabulary: the guided metronome's tempo
 *   counts, the unguided metronome's and the timed-set countdown all emit
 *   them, and `session-export.schema.json` gives `'3'` as an example cue
 *   meaning a tempo count. An earlier version of this bullet said that
 *   objection bound case 1 only, because a merged call wrote no row at all and
 *   a bare digit there would be spoken and never recorded. Issue 176 removed
 *   that escape: a merged call writes its own row now, so a bare digit on ANY
 *   of the three homes would land in the archive indistinguishable from a
 *   tempo count. The objection binds everywhere. The audio objection stands
 *   beside it: `"Up, three"` sits one second from the same stroke's own tempo
 *   count `"2"`, so the lifter hears digits meaning two different things
 *   inside one stroke. Whether the shorter utterance would in fact survive the
 *   window is unmeasured either way.
 * - **Let it clip**, accepting a call cut off mid-word. A count you cannot
 *   trust is worse than no count, which is why case 4 exists at all.
 * - **Replace a mid-stroke tempo count with the call** instead of merging it
 *   into the stroke's opening word. [CadenceBeat] cannot express "say this at
 *   second k", so it would put a timing decision back inside
 *   `GuidedCadenceRunner` in `:app`, whose test source set is one file deep --
 *   the shape of issue 106. It would also write a `Rep N` row into the middle
 *   of a stroke, where cue-track consumers measure phase boundaries.
 * - **Suppress the NEXT beat's label** rather than a tempo count, widening the
 *   window at the rep boundary so the call can land on time. This is the dual
 *   of case 3 and the one a later author will reach for, because it fixes the
 *   lateness case 3 accepts. It is the dangerous one. The utterance it deletes
 *   is a MOVEMENT INSTRUCTION, and on a leg press it is specifically the
 *   `Down` row, which is the row the committed cue-track fixtures and
 *   `CueTrack.calledReps` in the test source set count a rep as. Every
 *   capture made afterwards would report one called rep for a set of ten, in
 *   the persisted record, with nothing to reprocess: the rows were never
 *   written. Which label goes is lift-dependent -- on a leg curl it is `Up`
 *   and the count survives -- so the damage is silent AND intermittent, which
 *   is worse than a count that is merely late.
 *
 * A mid-rep isometric pause of [MERGE_MIN_STROKE_S] seconds or more could carry
 * a call the way case 3 does. No prescription in the corpus has one, and a home
 * nothing exercises is a home nothing checks, so it is named here and not
 * built.
 *
 * ## What cases 2 and 3 write to the cue track
 *
 * A merged call rides ONE utterance and writes TWO rows at that instant: the
 * stroke word, unchanged and unrenamed, and the call beside it. The stroke row
 * must stay exactly what it was -- the committed cue-track fixtures match
 * `Down` rows literally and `CueTrack.calledReps` in the test source set counts
 * them -- so the call is a second
 * row rather than a suffix on the first.
 *
 * This is issue 176 and it is a correction. Until it was fixed, cases 2 and 3
 * recorded `Down` and nothing else, so every merged call was spoken and written
 * nowhere: on session 33 that was all eleven rep calls of a twelve-rep 1120
 * pushdown, and the string `"Last rep"` did not appear once in a sixteen-set
 * archive where the lifter heard it on fifteen of the sixteen -- set 4 ended
 * before the beat that would have carried it. The one visible trace was a
 * REMOVED row -- the carrying stroke's first tempo count, given up from rep 2
 * onward -- which is how the calls were eventually counted, from the silence
 * they left rather than from anything written.
 *
 * `CadenceVoice` decides what is said and what is written; this file decides
 * only which beat carries it.
 */
data class CadencePlan(
    val beats: List<CadenceBeat>,
    /** Index of the beat after which a rep is complete. */
    val repCompleteAfterBeat: Int,
    /** Beat index carrying the rep announcement, or null when it is not spoken. */
    val announceOnBeat: Int?,
    /** True when the announcement is merged into a stroke's opening call. */
    val announceMerged: Boolean,
) {
    /** Seconds the metronome will actually play per rep. */
    val deliveredCycleS: Int get() = beats.sumOf { it.seconds }

    /**
     * Beats of the rep an announcement is ABOUT that are still to come when it
     * is spoken, counting the beat it opens; 0 when nothing is announced.
     *
     * Derived from the two indices rather than from the tempo, because the
     * tempo string does not decide either of them. `3010` puts the call at the
     * START of the announced rep on an eccentric-first incline press and at its
     * END on a concentric-first overhead press, and session 33 ran both.
     *
     * Three shapes, and the arithmetic is the same reading of one timeline:
     *
     * - [announceOnBeat] AFTER [repCompleteAfterBeat] -- a closing pause. The
     *   call is spoken in the PREVIOUS rep's tail, so the announced rep has not
     *   started and all of it is ahead.
     * - [announceOnBeat] before [repCompleteAfterBeat] -- the announced rep's
     *   opening stroke. What is left is that stroke and everything after it.
     * - the two EQUAL -- the announced rep's own last stroke. One beat left,
     *   and the lifter is already in it when they hear the words.
     */
    val beatsOfRepLeftWhenAnnounced: Int
        get() = when {
            announceOnBeat == null -> 0
            announceOnBeat > repCompleteAfterBeat -> repCompleteAfterBeat + 1
            else -> repCompleteAfterBeat - announceOnBeat + 1
        }

    /**
     * True when an announcement would open the LAST beat of the rep it is
     * about, so nothing of that rep is left to prepare for.
     *
     * This is what decides whether [LAST_REP] is spoken. It is a statement
     * about the schedule and never about the four digits: see
     * [beatsOfRepLeftWhenAnnounced] for why those are different things, and
     * `LastRepWarningTest` for the seventeen (tempo, lift) pairs that assert it
     * both ways.
     *
     * Not a threshold in SECONDS, deliberately. A three-second closing stroke
     * gives the lifter longer than a two-second one and no more of the rep. On
     * every schedule this has been observed on, the closing stroke is the
     * ECCENTRIC and the working stroke is therefore finished -- all thirteen
     * affected sets of session 33 are concentric-first -- but the predicate
     * does not test that. On an eccentric-first lift the closing stroke is the
     * CONCENTRIC: `1120` on an eccentric-first bench press suppresses the
     * warning with the whole two-second press still ahead. That shape is
     * pinned in `LastRepWarningTest` and appears nowhere in the corpus this was
     * derived from; whether the warning should be kept there is a `[Field]`
     * question this file does not answer.
     */
    val warningWouldOpenTheLastBeatOfItsOwnRep: Boolean
        get() = announceOnBeat != null && beatsOfRepLeftWhenAnnounced <= 1

    /**
     * What the guide says once rep [repsCompleted] of [plannedReps] is done, or
     * null when it says nothing.
     *
     * The decision, not the delivery: WHERE the returned words land is
     * [announceOnBeat]'s business, and on cases 2 and 3 that is one or two
     * strokes after this is decided.
     *
     * [plannedReps] is null on a set with no prescribed count, which has no
     * last rep to warn about and so only ever counts finished ones.
     *
     * The [LAST_REP] warning is withheld where it would arrive with only the
     * closing stroke of its own rep left -- issue #173, and the reasoning is
     * above under "What case 3 costs". The finished-rep count is not withheld
     * there or anywhere: it reports and instructs nothing.
     */
    fun announcementAfter(repsCompleted: Int, plannedReps: Int?): String? = when {
        announceOnBeat == null -> null
        plannedReps != null && repsCompleted == plannedReps - 1 ->
            LAST_REP.takeUnless { warningWouldOpenTheLastBeatOfItsOwnRep }
        else -> "$REP_CALL_PREFIX$repsCompleted"
    }

    companion object {
        /** The warning that the rep now due is the set's last. */
        const val LAST_REP = "Last rep"

        /** Prefix of a rep call, which counts FINISHED reps: `"Rep 3"`. */
        const val REP_CALL_PREFIX = "Rep "

        /**
         * Shortest stroke that can carry a merged announcement, either stroke.
         *
         * One threshold for cases 2 and 3 and not two, because the argument
         * below is about how LONG the stroke is and never about where in the
         * rep it sits. A two-second stroke leaves two seconds whether the next
         * rep opens on it or the current rep ends on it.
         *
         * The quantity that matters is the WINDOW the merged utterance gets
         * before anything else speaks and QUEUE_FLUSH cuts it off. A merged
         * call needs two seconds; a plain label needs one. A stroke of two
         * seconds or more leaves two, either because its first count is
         * suppressed or because it is too short to count aloud at all and is
         * silent until it ends.
         *
         * This is deliberately NOT written as [GuidedCadence.COUNT_ALOUD_FROM_S].
         * An earlier version was, on the reasoning that a stroke can only give
         * up a count it has -- but that ties an announcement-window quantity to
         * a counting-out-loud quantity, and the two come apart in both
         * directions. Raise COUNT_ALOUD_FROM_S to 3 and a two-second stroke
         * falls silent after its label, so its window is already two seconds
         * and merging is safe -- yet an aliased threshold would rise and drop
         * the announcement. Lower it to 1 and the window stays one second while
         * an aliased threshold would fall and merge unsafely.
         */
        const val MERGE_MIN_STROKE_S = 2

        /** Seconds an announcement needs when it is given a beat of its own. */
        const val ANNOUNCE_BEAT_S = 1

        const val HOLD = "HOLD"
        const val BREATHE = "BREATHE"

        fun of(schedule: TempoSchedule): CadencePlan {
            val firstS = strokeSeconds(schedule.first.seconds)
            val secondS = strokeSeconds(schedule.second.seconds)
            val firstPause = schedule.pauseAfterFirstS.toInt()
            // The prescription decides this, and nothing else may add to it.
            val closing = schedule.pauseAfterSecondS.toInt()

            val beats = mutableListOf<CadenceBeat>()
            beats += stroke(schedule.first.label, firstS)
            if (firstPause > 0) beats += CadenceBeat(HOLD, firstPause, "Hold", isStroke = false)
            beats += stroke(schedule.second.label, secondS)
            // The second stroke is the last beat of the rep itself. One index
            // because it is one beat: the rep is complete after it, and it is
            // the last stroke that can be given the call.
            val secondStroke = beats.lastIndex
            if (closing > 0) beats += CadenceBeat(BREATHE, closing, null, isStroke = false)

            // The rep call rides a closing pause when the prescription provides
            // one long enough to say it in.
            if (closing >= ANNOUNCE_BEAT_S) {
                return CadencePlan(beats, secondStroke, beats.lastIndex, announceMerged = false)
            }
            // Otherwise it opens the next rep's first stroke, which gives up its
            // first count to make room. Only a stroke that HAS a count can.
            if (firstS >= MERGE_MIN_STROKE_S) {
                beats[0] = beats[0].copy(suppressFirstCount = true)
                return CadencePlan(beats, secondStroke, announceOnBeat = 0, announceMerged = true)
            }
            // A one-second opener has no count to give up, so the call goes to
            // the other stroke on the same terms: a stroke later in the rep,
            // and not one second longer. Issue 147.
            if (secondS >= MERGE_MIN_STROKE_S) {
                beats[secondStroke] = beats[secondStroke].copy(suppressFirstCount = true)
                return CadencePlan(beats, secondStroke, announceOnBeat = secondStroke, announceMerged = true)
            }
            // Every second of the cycle already has a word in it. The screen
            // still carries the rep number.
            return CadencePlan(beats, secondStroke, announceOnBeat = null, announceMerged = false)
        }

        private fun stroke(label: String, seconds: Int) = CadenceBeat(
            label = label,
            seconds = seconds,
            spokenLabel = label.lowercase().replaceFirstChar { it.uppercase() },
            isStroke = true,
        )

        private fun strokeSeconds(seconds: Double?): Int = (seconds ?: 1.0).toInt().coerceAtLeast(1)
    }
}

/**
 * Constants the guided metronome and its plan share.
 *
 * The prep before a guided set used to be a `LEAD_IN_S` here, read by the runner
 * itself. It is [com.macrophage.barspeed.model.LeadInPolicy.DEFAULT_S] now, and
 * only a DEFAULT: the prep is a per-exercise decision the caller makes and
 * records on the set, so a constant read here could disagree with what the
 * record says was played.
 */
object GuidedCadence {
    /** Strokes at least this long get counted out loud second by second. */
    const val COUNT_ALOUD_FROM_S = 2
}
