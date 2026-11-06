package com.macrophage.barspeed.model

/**
 * The two words an UNGUIDED set says at its edges (UX review 2026-11-06,
 * voice F7).
 *
 * A straight-reps set -- sensor- or tap-counted, [PrepCase.NONE] -- used to
 * start and end without a word: the first sound was "Rep 1" at the first
 * lockout, and END SET said nothing. A START tap that missed from a metre or
 * two away was found out only when rep 1 went uncalled, and an END tap that
 * missed kept the set recording through the walk away. Each tap now answers
 * out loud, when the lifter has the voice on.
 *
 * SPOKEN, NOT RECORDED. Neither word goes on the cue track. "Set ended" is
 * `SetEnd`'s terminal word in `:core:dsp`, and writing it on an unguided set
 * would bound a rep list #141 deliberately leaves unbounded there; saying it
 * without writing it confirms the tap and decides nothing about the record.
 *
 * Every other set already speaks at its edges: a guided set's lead-in and
 * `Done`, a timed set's start word and `Time` or `Set ended`. A timed set is
 * left out even where its prep case is NONE, because `SetEnd.terminalCall`
 * already answers its end.
 */
object UnguidedSetCall {
    const val STARTED = "Set started"
    const val ENDED = "Set ended"

    /** What START says, or null for a set that has its own lead-in or the voice off. */
    fun atStart(prepCase: PrepCase, timed: Boolean, audioCues: Boolean): String? =
        STARTED.takeIf { unguided(prepCase, timed) && audioCues }

    /**
     * What the END tap says, or null. `SetEnd.terminalCall` is never asked on
     * the sets this answers for -- it asks only guided and timed sets -- so
     * the set cannot end twice out loud.
     */
    fun atEnd(prepCase: PrepCase, timed: Boolean, audioCues: Boolean): String? =
        ENDED.takeIf { unguided(prepCase, timed) && audioCues }

    private fun unguided(prepCase: PrepCase, timed: Boolean) = prepCase == PrepCase.NONE && !timed
}
