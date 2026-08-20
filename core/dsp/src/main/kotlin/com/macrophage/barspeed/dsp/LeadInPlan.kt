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
 * ## What today's prep actually is
 *
 * `Ready` at the top, then silence until the first stroke. Measured across the
 * seven committed `-cues.csv` fixtures, `Ready` to the first movement cue is
 * 5.001-5.004 s. That is what [of] currently builds. The launch phrase the
 * owner asked for -- a numeric countdown into "ready, brace" -- is not built
 * yet; do not read the constants below as a description of what is played.
 *
 * ## What reaches the record
 *
 * Only words in [RECORDED]. That set is the canonical statement of the rule and
 * the reason it is written here rather than at the call site: a later author
 * will want to record the countdown digits too, and should not.
 *
 * - Bare digits are already an overloaded cue vocabulary. The metronome's own
 *   tempo counts, the timed-set countdown and the rep counter all emit them,
 *   and `session-export.schema.json` documents `'3'` as an example cue meaning
 *   a tempo count. A fourth producer emitting `5,4,3` immediately before the
 *   first `Down` would put ambiguous digits exactly where a consumer measures
 *   "time from the first cue to the first movement".
 * - The digits carry no boundary the phrase does not. With `Ready` rigidly at
 *   [PHRASE_S] seconds before the first stroke, every digit's time is
 *   derivable from the phrase.
 *
 * **The cost, stated rather than hidden:** once the phrase lands, prep length
 * stops being readable from the cue track. `Ready` to the first movement cue
 * becomes [PHRASE_S] seconds for every prep, so a 20 s strap-up and a 2 s cable
 * set would produce identical cue tracks. Any feature that makes prep length
 * configurable must therefore persist the prescribed prep on the set record.
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
                // Today's behaviour, transcribed: one word at the top of the
                // prep, then silence until the first stroke.
                val spoken = READY.takeIf { index == 0 }
                LeadInBeat(spoken = spoken, cue = spoken?.takeIf { it in RECORDED })
            }
            return LeadInPlan(beats, prepS)
        }
    }
}
