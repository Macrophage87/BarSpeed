package com.macrophage.barspeed.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.macrophage.barspeed.dsp.SetAnalysis
import com.macrophage.barspeed.model.TempoScore
import com.macrophage.barspeed.model.TempoScoreLabel
import com.macrophage.barspeed.model.TempoScoreTone
import com.macrophage.barspeed.ui.BarColors
import com.macrophage.barspeed.ui.components.ChipTone

/**
 * The tempo chip, in the one place both screens read it from.
 *
 * The decision itself is [TempoScoreLabel] in `:core:model`, where a test runs
 * on every push; everything here is mechanical. The rest screen and the history
 * screen each carried their own copy of it and neither copy was reachable by
 * any test on the CI path -- issue #56.
 */
internal fun tempoScoreOf(analysis: SetAnalysis): TempoScore? {
    val compliance = analysis.tempoCompliance ?: return null
    return TempoScoreLabel.of(
        repsFullyCompliant = compliance.repsFullyCompliant,
        repsEvaluated = compliance.repsEvaluated,
        phases =
        compliance.phases.map {
            TempoScoreLabel.PhaseFacts(it.phase, prescribed = it.prescribedS != null, scored = it.scored)
        },
    )
}

internal fun TempoScoreTone.chipTone(): ChipTone = when (this) {
    TempoScoreTone.ON_TEMPO -> ChipTone.OK
    // Neither green nor a judgement on the lifter: the reps that were
    // graded were in tolerance, and the sentence below the chip says what
    // was not graded.
    TempoScoreTone.PARTIAL -> ChipTone.NEUTRAL
    TempoScoreTone.OFF_TEMPO -> ChipTone.WARN
}

/**
 * The sentence that says what the ratio beside it does not cover.
 *
 * Drawn under the chip row on both screens. Renders nothing when the set was
 * graded on everything it prescribed.
 */
@Composable
internal fun TempoCoverageNote(analysis: SetAnalysis) {
    tempoScoreOf(analysis)?.ungradedNote?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = BarColors.Sub)
    }
}
