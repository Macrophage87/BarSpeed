package com.macrophage.barspeed.ui.screens

import androidx.compose.runtime.Composable
import com.macrophage.barspeed.dsp.SetAnalysis
import com.macrophage.barspeed.dsp.SetAnalyzer
import com.macrophage.barspeed.ui.components.ChipTone
import com.macrophage.barspeed.ui.components.VerdictChip

/**
 * How much the set's range of motion varied, in the slot velocity loss takes
 * on a max-intent set (#250).
 *
 * Drawn only in the CONTROLLED regime, on the rest screen and on the history
 * card, from one definition so the two cannot drift -- the arrangement
 * [tempoScoreOf] already uses for the chip beside it (#56).
 *
 * The figure is `SetAnalyzer.romSpreadPct`: the population standard deviation
 * of the reps' ranges as a percentage of their mean, to one decimal. It is
 * NULL below two reps and where the mean range is not positive, and nothing is
 * drawn then -- dispersion over one rep is undefined, and rendering an
 * undefined figure as a number is the defect this repository keeps
 * re-learning.
 *
 * NEUTRAL, ALWAYS, and that is a decision rather than an oversight. A tone
 * needs a threshold, and no threshold for this figure has been measured on
 * this corpus: `romSpreadPct`'s own KDoc says a large value cannot separate a
 * segmenter that mismeasured a good set from a lifter whose range genuinely
 * varied, and the only machine here with an independently known travel is the
 * leg curl rail. Colouring it would be a claim about the lifter drawn from a
 * number that is partly a claim about the measurement. The figure is shown;
 * the judgement is not made. Choosing a band is a [Field] question.
 */
@Composable
internal fun RangeConsistencyChip(analysis: SetAnalysis) {
    val spread = SetAnalyzer.romSpreadPct(analysis.reps) ?: return
    VerdictChip("ROM spread ${trimSpread(spread)}%", ChipTone.NEUTRAL)
}

/**
 * One decimal, and no trailing `.0` -- the form both screens already print
 * percentages in. Not `String.format`, which reads the default locale and
 * would print a comma on a phone set to one that uses one.
 */
private fun trimSpread(value: Double): String {
    val rounded = Math.round(value * 10.0) / 10.0
    return if (rounded == Math.floor(rounded)) rounded.toInt().toString() else rounded.toString()
}
