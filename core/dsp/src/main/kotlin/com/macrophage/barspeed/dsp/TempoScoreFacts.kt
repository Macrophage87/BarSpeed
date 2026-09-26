package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.TempoScore
import com.macrophage.barspeed.model.TempoScoreLabel

/**
 * The tempo chip's decision over a compliance result, in the one place both
 * screens reach it from.
 *
 * The mapping from [PhaseComplianceResult] to [TempoScoreLabel.PhaseFacts]
 * lived in `:app`'s `TempoScoreView`, where no test runs, and a test that
 * wanted it had to write its own copy. A field dropped at that hand-off is a
 * field the label never sees, and the label's own tests stay green over it --
 * the near neighbour of whatever the label is taught next. Here, a test on the
 * CI path calls the same function the screens do.
 */
fun TempoComplianceResult.tempoScore(): TempoScore? = TempoScoreLabel.of(
    repsFullyCompliant = repsFullyCompliant,
    repsEvaluated = repsEvaluated,
    phases =
    phases.map {
        TempoScoreLabel.PhaseFacts(
            it.phase,
            prescribed = it.prescribedS != null,
            scored = it.scored,
            // Carried, and not yet read by the label.
            repsResolved = it.repsEvaluated,
        )
    },
)
