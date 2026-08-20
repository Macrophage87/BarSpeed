package com.macrophage.barspeed.dsp

/**
 * One second of the prep before a guided set starts.
 *
 * [spoken] is the utterance for that second, or null when the second passes in
 * silence. [cue] is what gets written to the set's cue track, or null when the
 * second is spoken but not recorded.
 *
 * The two are separate and independently nullable because the lead-in needs a
 * state [CadenceBeat] has no way to express: SAID BUT NOT WRITTEN DOWN. An
 * empty-string cue would be absence rendered as a value, and every cue-track
 * consumer would have to learn to skip it.
 */
data class LeadInBeat(val spoken: String?, val cue: String?)

/**
 * The seconds of prep before the first stroke of a guided set, one beat each.
 *
 * ## Why this is a type and not a loop in `:app`
 *
 * `GuidedCadenceRunner` lives in `:app`, which has no test source set. The last
 * time per-second arithmetic lived there it added a beat the prescription did
 * not ask for, on every set the app had ever paced, and nothing could assert
 * otherwise -- that is issue 106. So the arithmetic lives here and the runner
 * only walks it.
 *
 * ## Exactly one beat per second, silence included
 *
 * [beats] has exactly [prepS] entries even when most of them say nothing. A
 * sparse list would push "where does the silence go" back into `:app`, which is
 * the shape of the defect above. [secondsBeforeStart] is computed from the
 * index rather than stored, so it cannot disagree with a beat's position.
 *
 * ## The lead-in never picks the word the set opens on
 *
 * The opening stroke call is resolved by [TempoSchedule] from the plane, the
 * drive direction AND the start phase, and it can be any of `Down`, `Up`,
 * `Drive` or `Return` -- a seated row opens on `Drive`, a chest press on
 * `Return`. Nothing here may branch on start phase to guess it. The lead-in
 * stops before the first stroke and [CadencePlan]'s beat 0 says the word, which
 * is what makes the horizontal case right without anyone remembering to write
 * it.
 *
 * ## What the prep plays
 *
 * A prep of P seconds is exactly P one-second slots, filled from the END
 * backwards. `VoiceCounter` speaks with `TextToSpeech.QUEUE_FLUSH`, so every
 * utterance cancels the one before it and each slot holds exactly one thing to
 * say. That is measurable in the committed cue tracks: `Down` then `1` at
 * 1002 ms on `field-bench-rotating-6rep`.
 *
 * - The last second says [BRACE], the one before it [READY]. That is
 *   [PHRASE_S] seconds, and it is fixed to the END of the prep so `Brace`
 *   means the same thing whether the prep is 2 seconds or 20.
 * - Earlier slots, back to [COUNT_FROM_S], speak their own number, so the
 *   digit at T-k is k and agrees with the ring already on screen. `Ready` and
 *   `Brace` REPLACE what would have been "2" and "1"; they do not follow them.
 * - Earlier still is silence, except the first slot, which names the prep
 *   ("20 seconds") when P is at least `COUNT_FROM_S + PHRASE_S`. What is
 *   measured is the scheduling: one utterance per second, `Down` then `1` at
 *   1002 ms. That a shorter slot would truncate the words is inferred from
 *   QUEUE_FLUSH and has not been heard.
 *
 * The default prep therefore reads: five, four, three, ready, brace, and then
 * the stroke call. Degradation is not symmetric: at P = 1 only `Brace`
 * survives, because the brace-now beat immediately before movement is worth
 * more than the get-ready beat two seconds out. At P = 0 nothing is spoken.
 *
 * Prep LENGTH is unchanged by any of this -- it is still
 * [GuidedCadence.LEAD_IN_S] seconds and the first stroke lands where it always
 * did. What moves is `Ready`, from the top of the prep to [PHRASE_S] seconds
 * before the first stroke. Across the seven committed `-cues.csv` fixtures,
 * recorded before this change, `Ready` to the first movement cue measures
 * 5.001-5.004 s; afterwards it is a prescribed [PHRASE_S] s on every prep,
 * which is the cost noted below.
 *
 * No mid-point pings. The screen already counts the prep down visibly, and
 * whether 14 s of silence at P = 20 feels broken is a question for a gym
 * session, not for this file.
 *
 * ## What reaches the record
 *
 * Only words in [RECORDED]: `Ready` and `Brace`. NOT the countdown digits and
 * NOT the `"N seconds"` opener. That set is the canonical statement of the
 * rule and the reason it is written here rather than at the call site: a later
 * author will want to record the countdown digits too, and should not.
 *
 * - Bare digits are already an overloaded cue vocabulary. Three producers
 *   emit them: the guided metronome's tempo counts, the unguided metronome's,
 *   and the timed-set countdown. `session-export.schema.json` documents `'3'`
 *   as an example cue meaning a tempo count. A fourth producer emitting `5,4,3` immediately before the
 *   first `Down` would put ambiguous digits exactly where a consumer measures
 *   "time from the first cue to the first movement".
 * - The digits carry no boundary the phrase does not. With `Ready` rigidly at
 *   [PHRASE_S] seconds before the first stroke, every digit's time is
 *   derivable from the phrase.
 *
 * **The cost, stated rather than hidden:** prep length is no longer readable
 * from the cue track. `Ready` to the first movement cue is [PHRASE_S] seconds
 * for every prep, so a 20 s strap-up and a 2 s cable set produce identical cue
 * tracks. Any feature that makes prep length configurable must therefore
 * persist the prescribed prep on the set record.
 * Recording a `Prep 20` row was considered and rejected: one character from
 * `Rep 20` in a format humans grep, and it fabricates a row for a second in
 * which nothing was said.
 *
 * ## Bounds, for whoever makes prep length configurable
 *
 * Not implemented here and not validated here. Integer seconds; default
 * [GuidedCadence.LEAD_IN_S] when omitted; a warning below 2 naming what is
 * dropped; no ceiling is needed for correctness, though a warning above ~120 s
 * would catch a typo. A negative prep is not representable: [of] throws out of
 * `List(prepS)`, measured as `IllegalArgumentException: Illegal Capacity: -1`,
 * which is not a message anyone should see -- reject it before it gets here.
 */
data class LeadInPlan(val beats: List<LeadInBeat>, val prepS: Int) {
    /** Seconds between the start of the beat at [index] and the first stroke. */
    fun secondsBeforeStart(index: Int): Int = prepS - index

    companion object {
        /**
         * Seconds the launch phrase occupies at the END of the prep.
         *
         * This is deliberately NOT written as [CadencePlan.MERGE_MIN_STROKE_S]
         * or [GuidedCadence.COUNT_ALOUD_FROM_S]. All three are 2 and all three
         * mean different things: how long the launch phrase is, the shortest
         * stroke that can carry a merged rep announcement, and the shortest
         * stroke that gets counted out loud. [CadencePlan.MERGE_MIN_STROKE_S]
         * already carries this warning about the third; this is the same
         * warning for the first.
         *
         * The worked failure: alias this to COUNT_ALOUD_FROM_S, then raise
         * COUNT_ALOUD_FROM_S to 3 because three-second strokes are the only
         * ones worth counting. The launch phrase silently grows to three
         * seconds, a two-second prep stops saying `Ready` at all, and nothing
         * about counting out loud has anything to do with either.
         */
        const val PHRASE_S = 2

        /** Longest prep that is counted down digit by digit, from this number to 3. */
        const val COUNT_FROM_S = 5

        const val READY = "Ready"

        const val BRACE = "Brace"

        /**
         * The only lead-in words that reach the cue track.
         *
         * `Brace` rather than `Set`, which the owner chose: `Set` collides with
         * a set of reps, on screen where it reads `SET 3 OF 5` and in a cue
         * track that is entirely about sets.
         */
        val RECORDED = setOf(READY, BRACE)

        fun of(prepS: Int): LeadInPlan {
            val beats = List(prepS) { index ->
                val secondsLeft = prepS - index
                val spoken = when {
                    // The phrase is fixed to the END of the prep, so a prep
                    // too short for both keeps the beat nearest the movement.
                    secondsLeft == PHRASE_S -> READY
                    secondsLeft < PHRASE_S -> BRACE
                    // The digit spoken at T-k is k, so it is the number the
                    // ring is already showing.
                    secondsLeft <= COUNT_FROM_S -> secondsLeft.toString()
                    // A long prep names itself, but only with a silent second
                    // after it to keep QUEUE_FLUSH off the end of the word.
                    index == 0 && prepS >= COUNT_FROM_S + PHRASE_S -> "$prepS seconds"
                    else -> null
                }
                LeadInBeat(spoken = spoken, cue = spoken?.takeIf { it in RECORDED })
            }
            return LeadInPlan(beats, prepS)
        }
    }
}
