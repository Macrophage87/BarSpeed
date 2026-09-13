package com.macrophage.barspeed.dsp

/**
 * One beat of a guided rep: a label held for a whole number of seconds.
 *
 * [spokenLabel] is what the voice says on entering the beat, or null when the
 * beat opens in silence. [isStroke] separates a movement stroke, which counts
 * itself out loud when long enough, from a pause, which does not.
 *
 * A `suppressFirstCount` flag used to sit here, dropping a stroke's opening
 * count to make room for a rep announcement merged into that stroke's own word.
 * It is deleted with the merge (#293): an announcement takes the word's second
 * outright now, so nothing has to be given up to make room and no beat needs a
 * per-beat exception. See [CadencePlan].
 */
data class CadenceBeat(
    val label: String,
    val seconds: Int,
    val spokenLabel: String?,
    val isStroke: Boolean,
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
 * ## Where the rep announcement goes: the start of the rep, in place of a word
 *
 * `VoiceCounter` speaks with `TextToSpeech.QUEUE_FLUSH`: every utterance
 * cancels the one before it. An announcement therefore needs the next second to
 * itself or it is cut off mid-word -- and the runner says something every
 * second, the stroke label and then a count on each following second. **The gap
 * between utterances is one second regardless of how long the stroke is**, so a
 * three-second eccentric buys no more room than a one-second drive. That is
 * measurable in the committed cue tracks: `Down` 594039, `1` 595041, `2`
 * 596043, `Up` 597043.
 *
 * So a second that carries a word cannot also carry a call, and there are only
 * two answers to that. Until #293 the answer was to find the call a second
 * SOMEWHERE ELSE -- a closing pause the prescription already had, or a stroke
 * long enough to give up its first tempo count so that "Down, rep three" had
 * two seconds to land in. The owner heard the result on a 3010 overhead press
 * and asked for the other answer: *"It's hard to follow. Have the rep number be
 * at the start of the rep, and replace the relevant up or down, etc."* The
 * number takes the word's second, and the word is not spoken.
 *
 * Two cases, and the second is not this file's to place:
 *
 * 1. **The rep's FIRST stroke, on every rep after the first, in place of that
 *    stroke's spoken label.** The lifter hears the rep begin: `"Rep 3"` where
 *    they would have heard `"Down"`, with the whole of rep 3 ahead of them.
 *    Costs no time, no beat and no tempo count -- it costs the stroke's WORD,
 *    on that rep, which is what the owner asked to spend. Applies to every
 *    prescription with a second to spare anywhere: a closing pause of at least
 *    [ANNOUNCE_BEAT_S], or a stroke of at least [CALL_MIN_STROKE_S] at either
 *    end of the rep.
 * 2. **Not spoken here.** Two one-second strokes and no closing pause -- `1010`
 *    on every lift, and `1110` on the geometries whose digits stay in
 *    prescription order -- so every second of the cycle already carries a word
 *    and replacing one would delete the only instruction the rep has. The owner
 *    ruled on those separately, 2026-09-12: *"We already ruled on the 1010
 *    question. Rep count is at lockout."* That is #266, the number at the end
 *    of the concentric with a prep note, and it is NOT built here: these plans
 *    still announce nothing, and the rep number is on screen only, driven by
 *    `onRepCounted`. See "The SCREEN named finished reps until #252" below.
 *
 * The behaviour before issue 106 was a third option and is not available: it
 * inserted a one-second beat the prescription did not ask for.
 *
 * ## What the replacement costs, beside the word
 *
 * **The tempo count of that stroke is renumbered rather than dropped.** The
 * number stands where the word stood, so it is the stroke's first count and the
 * rest continue from it: a three-second eccentric goes `"Rep 3"`, `2`, `3`
 * where it used to go `"Down, rep three"`, silence, `2`. The owner's own
 * example is that pair. A stroke whose word is NOT replaced -- rep 1's, and the
 * stroke the rep ends on -- counts from one as it always did, so a recorded
 * track reads `Down 1 2` on rep 1 and `Rep 2 2 3` on rep 2. Whether the lifter
 * hears that difference as a discrepancy is a `[Field]` question and is not
 * settled here.
 *
 * **The cue track loses a row per rep and gains one.** A merged call wrote TWO
 * rows at one instant, the stroke word and the call; a replacing call writes
 * ONE, the call, because the stroke word was not said and a cue row is what the
 * app SAID. So a newly recorded eccentric-first press carries one `Down` row
 * for the whole set -- rep 1's -- where every archive before this carries one
 * per rep. That is a published contract change, filed as the THIRD entry under
 * export 1.20, and it is the reason the change cannot be read as cosmetic:
 * `CueTrack.calledReps` in the test source set counts `Down` rows, so a rule
 * like it applied to a new capture would report one rep for a set of six. What
 * replaces that rule is in the same entry -- the call rows land on the first
 * second of each rep, one per rep after the first, and rep 1's own stroke word
 * is still there.
 *
 * ## No home moves a beat
 *
 * Both cases only decide what is SAID on seconds the prescription already asked
 * for. Neither lengthens a stroke, a pause or a cycle. That is the obligation
 * two shipped releases broke -- a flat allowance for everything after the first
 * stroke, then the one-second floor of issue 106, +1.00 s per rep on 31 of 31
 * captured sets -- and `CadencePlanTest` pins it against the prescription for
 * 1,380 (tempo, lift) pairs rather than against the cycle total, which a second
 * moved from one beat into another leaves unchanged.
 *
 * ## What the old placements cost, which is why #293 replaced them
 *
 * A place in the rep, and one tempo count. A call merged into the stroke the
 * rep ENDS on landed one stroke into the rep instead of at its start: a second
 * late on a `1030` leg curl, two on a `2011` with an isometric pause between
 * the strokes. A call in the closing pause landed a whole second BEFORE the rep
 * it named had begun.
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
 * Two sessions later the owner heard the other half of it on a `3010` overhead
 * press, whose call rode the three-second lowering: *"I'm not sure. That could
 * be the case. I think we might need to change the timing. It's hard to
 * follow."* #293 is the answer, and it ends the whole family of lateness rather
 * than tuning it -- every call now opens the rep it names.
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
 * #293 finishes that argument on the timing rather than the number: the warning
 * opens the final rep, so there is no version of it that can arrive with only a
 * stroke left. What #173 was reading in the report -- a warning too late to
 * warn -- is not reachable from this schedule.
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
 * The cost that paragraph used to name is gone with the merge. Speaking the
 * warning used to take back a tempo count, because the stroke carrying it gave
 * up its first count to make room: on a `1120` pushdown the second that carried
 * a `1` fell silent. Nothing is given up now, so that `1` is spoken on the
 * final rep as on every other, and what the final rep no longer says is the
 * word `Down`. `LastRepWarningTest` carries the rows both ways.
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
 * ## Rep 1 is announced on no plan, and the reason is not the old one
 *
 * The old reason is DELETED rather than kept beside the new one, because it was
 * an argument about where a call could fit: a call rode a beat of the rep it
 * named on the merged families and the PREVIOUS rep's closing pause on the
 * paused one, so announcing rep 1 was possible on some tempo families and
 * impossible on others, and where it was possible it cost rep 1 the only tempo
 * count those plans had (#147). Neither clause survives #293 -- the call takes
 * the opening stroke's word, every rep has one, and nothing is given up -- so
 * announcing rep 1 is now perfectly possible on every plan that speaks and is
 * still not done. Three reasons, and the third is the one that would cost data:
 *
 * 1. Rep 1's number is not in doubt. It follows the prep countdown, whose last
 *    word is `Brace`, and **the stroke word after that countdown IS rep 1
 *    beginning** -- the owner's *"I want to hear the rep begin"* is satisfied on
 *    rep 1 by the word already there.
 * 2. That word is under a contract of its own. `StartCuePolicy` in
 *    `:core:model` draws it on the screen for the whole prep and
 *    `StartCueVoiceContractTest` pins that beat 0's utterance is the same word
 *    (#241). A `"Rep 1"` would replace the one utterance that contract exists
 *    to align, and the lifter would get no spoken direction for the set at all.
 * 3. It keeps one first-stroke word per set in the RECORD. Stroke words are the
 *    only discriminator a reader of an archive has between this guide's track
 *    and the unguided counter's (`CueTrackOriginTest`, and the published
 *    `voiceCues` description), and a `Rep 1` row would additionally collide
 *    with every archive recorded before export 1.19, where `Rep 1` is what the
 *    guide said as rep 1 FINISHED.
 *
 * Silence about rep 1 is not a wrong number.
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
 * published in `voiceCues` and pinned by `CueTrackOriginTest`. From #293 a
 * guided rep carries ONE of the two stroke words rather than both -- the number
 * takes the other's second -- and rep 1 carries both, which is what the
 * discriminator now rests on.
 *
 * ## What this file does not place, and what was rejected
 *
 * A SCHEDULE of two one-second strokes with no closing pause has a word in
 * every second of its cycle and stays silent here -- case 2 above, which is
 * #266's. `1010` resolves to one on every lift. `1110` resolves to one only
 * when the digits are left in prescription order: `TempoSchedule.of` swaps the
 * two strokes whenever digit 1 is not the stroke the lift opens with, and the
 * swap carries digit 2's pause to the END of the rep, where it is a one-second
 * closing pause and the plan speaks. Across the four geometries
 * [com.macrophage.barspeed.model.ExerciseDef] can express, `1110` announces on
 * a concentric-first lift whose concentric is up and on an eccentric-first lift
 * whose concentric is down. An earlier version of this paragraph named `1110`
 * flatly as uncarryable, which is false on two of those four and is deleted
 * rather than softened. Four ways of forcing a call into such a schedule were
 * considered and rejected:
 *
 * - **Speak a bare digit** rather than `"Rep 3"`, on the theory that a shorter
 *   utterance survives a shorter window. Bare digits are already the most
 *   overloaded string in the cue vocabulary: the guided metronome's tempo
 *   counts, the unguided metronome's and the timed-set countdown all emit
 *   them, and `session-export.schema.json` gives `'3'` as an example cue
 *   meaning a tempo count. An earlier version of this bullet said that
 *   objection bound the closing-pause home only, because a merged call wrote no
 *   row at all and a bare digit there would be spoken and never recorded. Issue
 *   176 removed that escape: a call writes its own row, so a bare digit would
 *   land in the archive indistinguishable from a tempo count wherever it was
 *   said. The objection binds everywhere, and #293 sharpens it: the digits of
 *   the stroke the call opens now continue FROM the call, so a bare-digit call
 *   would be the first member of the very sequence it has to be told apart
 *   from. The audio objection stands
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
 * - **Replace the opening stroke's LABEL** rather than a tempo count, so the
 *   call can land on time. This bullet used to reject the idea and call it the
 *   dangerous one; **the owner ruled the other way (#293) and the rejection is
 *   deleted rather than left standing beside the behaviour.** What the bullet
 *   got right is the cost, and it is paid rather than avoided: the utterance
 *   replaced is a MOVEMENT INSTRUCTION, and on an eccentric-first press it is
 *   the `Down` row that the committed cue-track fixtures match and
 *   `CueTrack.calledReps` counts a rep as. Which word goes is lift-dependent --
 *   on a leg curl the first stroke is the `Down` of a pull-down drive, on a
 *   concentric-first overhead press it is the `Up` -- so a reader cannot
 *   predict from the tempo which row thins out. Three things make it payable
 *   where it was not: the call lands on the same second the word would have, so
 *   the rep boundary is still marked by a row; rep 1 keeps its word, so the
 *   set's geometry stays readable from the track; and export 1.20's THIRD entry
 *   publishes the change, so a reader is not left to discover it. The lifter
 *   still hears one movement word per rep -- the other stroke's -- and the rep
 *   number arrives exactly where the replaced one did.
 *
 * A mid-rep isometric pause of [CALL_MIN_STROKE_S] seconds or more could carry
 * a call the way case 3 does. No prescription in the corpus has one, and a home
 * nothing exercises is a home nothing checks, so it is named here and not
 * built.
 *
 * ## What a call writes to the cue track
 *
 * ONE row, the call, at the second the replaced word would have been said. The
 * stroke word is not written, because it was not said, and a cue row is what
 * the app SAID -- writing a word the lifter never heard would be the same
 * defect as issue 176 in the other direction.
 *
 * Issue 176 is the correction that came first and it is still the reason a call
 * is written at all. Until it was fixed, a merged call recorded the stroke word
 * and nothing else, so the call was spoken and written nowhere: on session 33
 * that was all eleven rep calls of a twelve-rep 1120 pushdown, and the string
 * `"Last rep"` did not appear once in a sixteen-set archive where the lifter
 * heard it on fifteen of the sixteen -- set 4 ended before the beat that would
 * have carried it. The one visible trace was a REMOVED row -- the carrying
 * stroke's first tempo count, given up from rep 2 onward -- which is how the
 * calls were eventually counted, from the silence they left rather than from
 * anything written.
 *
 * What #176 then settled, and #293 changes, is the SHAPE of a call's rows: two
 * at one instant, the stroke word unrenamed and the call beside it. It is one
 * now, and the difference is published as export 1.20's THIRD entry rather than
 * left for a reader to find. `MergedCallCueTrackTest` scores both shapes
 * against the same three 0.1.43 tracks.
 *
 * `CadenceVoice` decides what is said and what is written; this file decides
 * only which beat carries it.
 */
data class CadencePlan(
    val beats: List<CadenceBeat>,
    /** Index of the beat after which a rep is complete. */
    val repCompleteAfterBeat: Int,
    /**
     * Beat index carrying the rep announcement, or null when it is not spoken.
     *
     * It is 0 or null on every plan [of] builds -- the rep's first stroke, or
     * nothing -- and stays an INDEX rather than becoming a flag because #266 has
     * to put a call on a different beat: the end of the concentric, on the
     * plans this one leaves silent.
     *
     * An `announceMerged` flag used to sit beside it, true when the call rode a
     * stroke's own word. It is deleted with the merge (#293): a call replaces
     * the word, so the flag would be true of every speaking plan and false of
     * none, and a flag with one value is a fact nothing can check.
     */
    val announceOnBeat: Int?,
) {
    /** Seconds the metronome will actually play per rep. */
    val deliveredCycleS: Int get() = beats.sumOf { it.seconds }

    /**
     * Beats of the rep an announcement is ABOUT that are still to come when it
     * is spoken, counting the beat it opens; 0 when nothing is announced.
     *
     * It is the WHOLE rep on every plan that speaks, from #293, and the property
     * is kept rather than replaced by that sentence: it is the measurement the
     * sentence rests on, and it is what reds if a later change puts a call back
     * inside the rep.
     *
     * Three shapes were reachable before #293 and the arithmetic still reads one
     * timeline, so the two that are now unreachable are worth naming as the
     * history of this number rather than as live cases. A call in a closing
     * pause ([announceOnBeat] after [repCompleteAfterBeat]) was spoken in the
     * PREVIOUS rep's tail, so the named rep had not started; a call on the
     * rep's own last stroke (the two EQUAL) left ONE beat, and the lifter was
     * already in it when they heard the words. `3010` produced both, from one
     * tempo string: the first on a concentric-first overhead press, the second
     * -- the whole rep ahead -- on an eccentric-first incline press, and session
     * 33 ran both on one afternoon.
     *
     * It is a count of BEATS and not of seconds, deliberately. A three-second
     * closing stroke gives the lifter longer than a two-second one and no more
     * of the rep. Nor does it say which stroke is left; `LastRepWarningTest`
     * carries the geometry rows.
     */
    val beatsOfRepLeftWhenAnnounced: Int
        get() = if (announceOnBeat == null) 0 else repCompleteAfterBeat - announceOnBeat + 1

    /**
     * What the guide says about rep [repNowDue] of [plannedReps], or null when
     * it says nothing.
     *
     * The coordinate is the rep the call is ABOUT: the one now due, which the
     * lifter is about to start or is already in. Every caller asks once per rep
     * boundary, for the rep that follows it.
     *
     * The decision, not the delivery: WHERE the returned words land is
     * [announceOnBeat]'s business, and from #293 that is the first second of the
     * rep this names, in place of that rep's first stroke word.
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
         * Shortest stroke whose presence leaves a prescription able to speak a
         * rep call, at either end of the rep.
         *
         * Two, and only the VALUE carries over from the `MERGE_MIN_STROKE_S`
         * this replaces. That constant was a WINDOW quantity: a merged call
         * needed two seconds before the next utterance flushed it, and a stroke
         * of two seconds or more could give one up. Nothing merges from #293, so
         * no window argument applies to a call at all -- it takes a whole
         * second that used to carry a word.
         *
         * What the threshold decides now is which plans this file places a call
         * on. A cycle with a two-second stroke anywhere, or a closing pause, has
         * a second that is not already an instruction; a cycle of two one-second
         * strokes and no pause does not, and the owner ruled those count at
         * lockout instead (#266). The boundary is the owner's, not an
         * arithmetic consequence, and `CadencePlanTest` pins it by behaviour --
         * a two-second stroke at either end speaks, `1010` and the unswapped
         * `1110` do not -- rather than by this name.
         *
         * One threshold and not two, because the question is how LONG a stroke
         * is and never where in the rep it sits.
         *
         * This is deliberately NOT written as [GuidedCadence.COUNT_ALOUD_FROM_S].
         * An earlier version was, on the reasoning that a stroke can only give
         * up a count it has -- but that tied this to a counting-out-loud
         * quantity, and the two come apart in both directions: raise
         * COUNT_ALOUD_FROM_S to 3 and a two-second stroke falls silent after its
         * label, while an alias would rise and drop the announcement; lower it
         * to 1 and an alias would fall and let `1010` speak, which is #266's to
         * decide.
         */
        const val CALL_MIN_STROKE_S = 2

        /**
         * Seconds of closing pause that leave a prescription able to speak.
         *
         * The pause used to CARRY the call -- a beat of its own, silent
         * otherwise -- and one second was what an announcement needed there.
         * From #293 the call opens the next rep instead and the pause is silent
         * again, so this is a threshold on the prescription and no longer a
         * budget for an utterance.
         */
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

            // Whether this prescription has a second that is not already a
            // movement instruction: a closing pause, or a stroke long enough
            // that its seconds are not all word. Three ways to have one, and
            // they used to pick three different homes for the call -- the
            // pause, the opening stroke, the closing stroke. They pick the same
            // home now, so the test is a single condition (#293).
            val hasRoomForACall = closing >= ANNOUNCE_BEAT_S ||
                firstS >= CALL_MIN_STROKE_S ||
                secondS >= CALL_MIN_STROKE_S
            if (hasRoomForACall) {
                // Beat 0, the rep's first stroke: the call replaces that
                // stroke's spoken word on every rep after the first, so the
                // lifter hears the rep begin. `CadenceVoice` does the replacing
                // and renumbers that stroke's counts from the number.
                return CadencePlan(beats, secondStroke, announceOnBeat = 0)
            }
            // Two one-second strokes and no closing pause, so every second of
            // the cycle is an instruction and replacing one would delete it.
            // The rep number is on screen only here; the owner ruled these
            // count at lockout instead, which is #266 and not built yet.
            return CadencePlan(beats, secondStroke, announceOnBeat = null)
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
