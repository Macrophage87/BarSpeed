package com.macrophage.barspeed.model

/**
 * Which stored coaching verdicts a screen shows, given the set's regime (#261).
 *
 * `CoachingRules.verdicts` in `:core:dsp` writes a set's coaching lines ONCE,
 * at record time, and `SetAnalysis` is `@Serializable`, so they are frozen into
 * the row's `analysisJson` and read back verbatim by every screen that shows
 * the set again. One of those lines is
 * `"High velocity loss (42.8%) — significant fatigue this set."`.
 *
 * #250 decided per set which question `velocityLoss_pct` answers, and made the
 * post-set chip and the history card draw range consistency instead of the
 * velocity pill on a [VelocityLossRegime.CONTROLLED] set -- a set whose
 * prescribed tempo fixed the drive's speed, so a slowing rep is a count held
 * poorly and not a lifter running out. That sentence went on being drawn on the
 * same card, under the withheld pill, saying the opposite in words. The lifter
 * reads no velocity number and "significant fatigue this set".
 *
 * Changing the rule that WRITES the sentence would not reach the archive: every
 * set already recorded carries it spelled out. So the suppression is a reader's
 * decision, and this is the one place it is made -- lifted into `:core:model`
 * so that a test runs on it on every push, rather than left in three Compose
 * call sites where nothing on the CI path can execute it.
 *
 * ## What is dropped, and what is deliberately not
 *
 * Only the fatigue sentence, recognised by [FATIGUE_PREFIX]. The figure in it
 * is interpolated, so the text before the figure is the whole of what a reader
 * can key on; `CoachingVerdictLinesTest` in `:core:dsp` pins the produced line
 * against this constant so the two cannot drift apart.
 *
 * The OTHER velocity-loss sentence -- "Velocity-loss stop (20.0%) was reached
 * at rep 3; later reps exceeded the plan." -- survives on both regimes, on
 * purpose. It fires only when the plan prescribed a stop, so it reports that a
 * threshold the lifter themselves asked to be told about was crossed, which is
 * true whatever fixed the drive's speed. It is also mutually exclusive with the
 * fatigue sentence by construction (the fatigue rule is guarded on
 * `velocityLossStopPct == null`), so dropping one can never drop the other.
 *
 * There is NO range-of-motion verdict to preserve: `romSpread_pct` is a chip
 * and an export key, and `CoachingRules.verdicts` writes no line about range.
 * Stated because the brief for this change asked for a pin that one survives.
 *
 * ## Absence
 *
 * A null regime shows every line, which is exactly what all three screens drew
 * before #250 existed. Absence claims nothing and withholds nothing.
 *
 * ## What this function is, and is not, evidence for
 *
 * The three call sites are Compose, and nothing on the CI path renders a
 * Compose screen, so what is gated on every push is this decision and the
 * prefix contract in `:core:dsp` -- never that the history card or the rest
 * screen actually came out shorter by one line. NO SCREENSHOT OF EITHER CARD
 * WAS TAKEN FOR THIS CHANGE: the bench emulator needs about 3 GB of free
 * memory and the machine held between 0.38 and 1.15 GB across a bounded
 * thirty-minute wait. That render is outstanding, not done.
 */
object CoachingVerdictPolicy {
    /**
     * The head of the fatigue sentence, up to the interpolated figure.
     *
     * Stable across the whole archive: the only commit that has ever added or
     * removed this string on `SetAnalyzer.kt` is "Rename project to BarSpeed;
     * add branch-protection script", by `git log -S` over that path.
     */
    const val FATIGUE_PREFIX = "High velocity loss ("

    /**
     * The verdict lines to draw for a set in [regime].
     *
     * Order is preserved and nothing is rewritten; a line is either shown or
     * not shown.
     */
    fun forRegime(verdicts: List<String>, regime: VelocityLossRegime?): List<String> = when {
        // Read the way every set read before #250: the regime is not decidable
        // for this row, so nothing here may decide anything about it.
        regime == null -> verdicts
        // Velocity loss IS the autoregulation figure here, so the sentence is
        // the reading the card already leads with.
        regime.readsVelocityLoss -> verdicts
        // The prescribed tempo fixed the drive's speed, so the sentence is a
        // fatigue claim about a set where a slowing rep is a count held
        // poorly. It is withheld; everything else the set was told is drawn,
        // in the order it was written.
        else -> verdicts.filterNot { it.startsWith(FATIGUE_PREFIX) }
    }
}
