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
 * This arithmetic used to live inside GuidedCadenceRunner in `:app`, where no
 * test on the CI path reaches it, so nothing could assert that the cycle the
 * metronome plays is the cycle the plan prescribes. It was not, on every set the app has
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
 *    sensor's. It named FINISHED reps until #252 and names the rep in hand
 *    now, so it agrees with the voice on every plan that speaks. See "The
 *    SCREEN named finished reps until #252" below; it is stated once, there.
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
 * word the lifter is listening for, and it instructs no movement, so arriving
 * late cannot be mistaken for a cue to move.
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
 * ## What #173 concluded from that, and why #243 reverses it
 *
 * #173 read the report as a WARNING arriving too late to warn, and withheld
 * [LAST_REP] on the thirteen. The conclusion followed from the schedule around
 * it: every numbered call then counted FINISHED reps, so the beat that carries
 * a call was a beat the lifter had been taught, for a whole set, to hear as
 * being about a rep ALREADY OVER. `"Last rep"` in that beat referred to a rep
 * not yet started, and nothing in the eleven calls before it said the frame had
 * changed. That is what "with no rep in between" is the sound of.
 *
 * #243 moves every numbered call onto the rep it is calling FOR -- see
 * [announcementFor] -- so that beat means "the rep you are in" on every rep of
 * the set, and the warning in it is a statement about the rep in hand rather
 * than one still to come. The withholding is deleted rather than narrowed: it
 * answered a question this schedule no longer asks. The final rep is named on
 * every plan with a beat able to carry a call, and nowhere else -- a schedule
 * of two one-second strokes with no closing pause has a word in every second
 * of its cycle and still says nothing, on the last rep as on every other.
 *
 * The audio of the final rep is 0.1.43's again on the plans that had it
 * withheld. That is MEASURED on two of the thirteen and DERIVED on the rest:
 * `MergedCallCueTrackTest` compares the scripted final rep against sets 5 and
 * 13's own tracks, second by second, and the other eleven are the same rule
 * applied to plans read from the same `meta.json` rather than tracks anyone has
 * replayed. What is not the same on any of them is the eleven calls before it,
 * and that is the whole of the argument. Whether the lifter hears the
 * difference is a `[Field]` question -- the cue track records what was said, so
 * the next capture answers it -- and this file does not settle it in advance,
 * which is the mistake the deleted sentence above made.
 *
 * One cost is a row, and it runs the other way from #173's one gain: a carrying
 * stroke gives up its first tempo count only when an announcement actually
 * rides it, so speaking the warning takes back the count the suppression had
 * handed the final rep. On a `1120` pushdown the second that carried a `1`
 * falls silent, and the call rides the stroke word one second earlier: from
 * the start of the final rep, `Up` and the warning both at second 46 with
 * nothing at 47, against rep 1 of the same plan which still counts `1` at its
 * own offset 3. An earlier version of this paragraph said that second
 * "carries the call instead", which is false by one second and is deleted
 * rather than softened -- the call is not where the count was.
 *
 * ## The SCREEN named finished reps until #252, and now names the rep in hand
 *
 * `RecordScreen.GuidedSetStage` draws the line under the ring from
 * `GuidedRepCaption.forRing` in `:core:model`, handing it `RecordState
 * .manualReps` -- which `GuidedCadenceRunner` sets from `onRepCounted(rep)`,
 * fired at [repCompleteAfterBeat] with the rep just FINISHED. `forRing` names
 * `finishedReps + 1`, so the lifter in their seventh rep of twelve reads
 * `rep 7 of 12` and hears `"Rep 7"`.
 *
 * Between #243 and #252 the two disagreed for the whole of every set: #243
 * moved the voice onto the rep in hand and the screen still counted finished
 * reps, so a change that removed one off-by-one created a second one between
 * two things the lifter can see and hear at once. #252 moved the screen with
 * it, and the prep is a distinct state rather than rep zero -- while the
 * countdown runs the ring names how many reps are COMING, so nothing claims a
 * rep is in hand before one is.
 *
 * Nothing in `:core:dsp` decides the caption, and the two decisions are pinned
 * equal here rather than trusted: `RingVoiceAgreementTest` reads
 * [announcementFor] against `forRing` rep by rep -- `"Rep 7"` against
 * `rep 7 of 12`, [LAST_REP] against `last rep of 12` -- and it is on this side
 * because `:core:model` cannot see this file. `[Field]`: no device has drawn
 * the caption, and what that leaves unverified is recorded once, in
 * `GuidedRepCaption`'s KDoc, rather than repeated here.
 *
 * Rep 1 is announced on no plan, and that is the schedule rather than an
 * omission. A call rides a beat of the rep it names on cases 2 and 3 and the
 * PREVIOUS rep's closing pause on case 1, so announcing rep 1 would be possible
 * on some tempo families and impossible on others; and where it is possible it
 * would cost rep 1 the only tempo count those plans have (#147). Silence on rep
 * 1 is not a wrong number.
 *
 * Nothing here touches the UNGUIDED counter,
 * `VoiceMilestonePolicy.repMilestone` in `:core:model`. That one speaks at the
 * instant a rep is counted rather than on a metronome schedule, so its
 * `"Rep N"` counts FINISHED reps and its `"Last rep"` lands as rep
 * `plannedReps - 1` completes, with the whole final rep still ahead. The two
 * cannot speak on one set: `SetVoicePolicy.guidesFor` returns at most one guide
 * and excludes the sensor counter on a cued set. They are not distinguishable
 * from a cue ROW either, which matters from export 1.19 because the two now
 * name different reps; the discriminator is the stroke words this file places,
 * published in `voiceCues` and pinned by `CueTrackOriginTest`.
 *
 * ## What stays uncarryable, and what was rejected
 *
 * A SCHEDULE of two one-second strokes with no closing pause has a word in
 * every second of its cycle and keeps case 4. `1010` resolves to one on every
 * lift. `1110` resolves to one only when the digits are left in prescription
 * order: `TempoSchedule.of` swaps the two strokes whenever digit 1 is not the
 * stroke the lift opens with, and the swap carries digit 2's pause to the END
 * of the rep, where it is a one-second closing pause and case 1 takes it.
 * Across the four geometries [com.macrophage.barspeed.model.ExerciseDef] can
 * express, `1110` announces on a concentric-first lift whose concentric is up
 * and on an eccentric-first lift whose concentric is down. An earlier version
 * of this paragraph named `1110` flatly as uncarryable, which is false on two
 * of those four and is deleted rather than softened. Four ways of forcing a
 * call into a schedule that genuinely has no room were considered and
 * rejected:
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
 *   `GuidedCadenceRunner` in `:app`, which no test on the CI path reaches --
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
     *
     * It is a count of BEATS and not of seconds, deliberately. A three-second
     * closing stroke gives the lifter longer than a two-second one and no more
     * of the rep. Nor does it say which stroke is left: on every schedule this
     * has been observed on the closing stroke is the ECCENTRIC, so the working
     * stroke is finished -- all thirteen affected sets of session 33 are
     * concentric-first -- but on an eccentric-first lift it is the CONCENTRIC,
     * and `1120` on an eccentric-first bench press reaches one beat left with
     * the whole two-second press still ahead. `LastRepWarningTest` carries that
     * row.
     */
    val beatsOfRepLeftWhenAnnounced: Int
        get() = when {
            announceOnBeat == null -> 0
            announceOnBeat > repCompleteAfterBeat -> repCompleteAfterBeat + 1
            else -> repCompleteAfterBeat - announceOnBeat + 1
        }

    /**
     * What the guide says about rep [repNowDue] of [plannedReps], or null when
     * it says nothing.
     *
     * The coordinate is the rep the call is ABOUT: the one now due, which the
     * lifter is about to start or is already in. Every caller asks once per rep
     * boundary, for the rep that follows it.
     *
     * The decision, not the delivery: WHERE the returned words land is
     * [announceOnBeat]'s business, and on cases 2 and 3 that is one or two
     * strokes into the rep this names.
     *
     * [plannedReps] is null on a set with no prescribed count, which has no
     * last rep to warn about.
     *
     * The NUMBER returned is [repNowDue] itself: a lifter starting their
     * seventh hears `"Rep 7"`. It counted FINISHED reps until #243 -- that
     * lifter heard `"Rep 6"` -- which put the last number of a set two short of
     * the plan and is what the field report *"it seems to end one early"*
     * describes. `RepCallScheduleTest` holds both schedules against the same
     * seven recorded cue tracks.
     *
     * [LAST_REP] stands in for the number on the last rep and is spoken
     * wherever a beat can carry a call at all. It used to be withheld on the
     * plans whose only slot is the beat the rep ends on (#173); the reasoning
     * for withholding it, and for reversing that, is above under "What #173
     * concluded from that". Nothing is withheld here now: a plan either has a
     * home for a call, and says all of them, or has none, and says none.
     */
    fun announcementFor(repNowDue: Int, plannedReps: Int?): String? = when {
        announceOnBeat == null -> null
        plannedReps != null && repNowDue == plannedReps -> LAST_REP
        else -> "$REP_CALL_PREFIX$repNowDue"
    }

    companion object {
        /** The warning that the rep now due is the set's last. */
        const val LAST_REP = "Last rep"

        /** Prefix of a rep call, which names the rep now due: `"Rep 3"`. */
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
