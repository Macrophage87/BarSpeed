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
 *
 * @param setReps every rep of the analysis this result was computed over.
 *   A compliance result does not carry that count, so the caller says it;
 *   the screens reach this through [SetAnalysis.tempoScore], which pairs the
 *   result with the reps it was taken from.
 */
fun TempoComplianceResult.tempoScore(setReps: Int): TempoScore? = TempoScoreLabel.of(
    repsFullyCompliant = repsFullyCompliant,
    repsEvaluated = repsEvaluated,
    setReps = setReps,
    phases =
    phases.map {
        TempoScoreLabel.PhaseFacts(
            it.phase,
            prescribed = it.prescribedS != null,
            scored = it.scored,
            // The phase's own count, never the set's: the label compares it
            // with the count it takes coverage over to find reps the phase
            // went unmeasured on.
            repsResolved = it.repsEvaluated,
        )
    },
)

/**
 * The tempo chip for a set, as both screens draw it (#329).
 *
 * [SetAnalyzer.analyze] computes [SetAnalysis.tempoCompliance] over exactly
 * [SetAnalysis.reps], so the set's rep count is read here, beside the
 * result it belongs to, rather than at each screen. Null when the set had no
 * prescribed tempo, or when [TempoScoreLabel.of] has no ratio to draw.
 */
fun SetAnalysis.tempoScore(): TempoScore? = tempoCompliance?.tempoScore(setReps = reps.size)
