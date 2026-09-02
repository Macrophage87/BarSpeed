package com.macrophage.barspeed.model

/** How a tempo ratio should be toned, which is not the same question as what it covers. */
enum class TempoScoreTone {
    /** Every graded rep was in tolerance, on every phase the set prescribed. */
    ON_TEMPO,

    /** Every graded rep was in tolerance, but a prescribed phase went ungraded. */
    PARTIAL,

    /** At least one graded rep was outside tolerance. */
    OFF_TEMPO,
}

/**
 * The tempo chip's text and tone, plus the sentence that must accompany it.
 *
 * [ungradedPhases] is the fact -- which prescribed movement phases the set was
 * never graded on -- and [ungradedNote] is the sentence a screen draws for it,
 * null when there is nothing to qualify. A caller that drops the note renders
 * the same overstatement this type exists to end, so it is a separate field
 * rather than an optional suffix on [text].
 */
data class TempoScore(
    val text: String,
    val tone: TempoScoreTone,
    val ungradedPhases: List<String>,
    val ungradedNote: String?,
)

/**
 * What a screen may claim about a set's tempo compliance.
 *
 * Pure, and in `:core:model` rather than in the two composables that draw it,
 * because nothing on the CI path reaches `:app`'s screens: the same decision
 * was written twice, in `RecordScreen` and in `SessionDetailScreen`, and
 * neither copy could be tested. Issue #56.
 *
 * The input is a flat description of what the analyzer already decided --
 * names and two booleans -- rather than a `:core:dsp` type, so this module
 * keeps no dependency on the analyzer and nothing here re-derives a
 * prescription.
 */
object TempoScoreLabel {
    /**
     * The phases a set can be GRADED on, in the order they are reported.
     *
     * The names are the ones `SetAnalyzer.complianceFor` writes and the export
     * publishes in `scoredPhases`. Pauses are measured and reported but never
     * scored, so a prescribed pause nothing measured is not a gap in the
     * ratio's coverage and must not be named as one.
     * `TempoScoreWiringTest` in `:core:dsp` pins these strings against what the
     * analyzer actually emits; drift there would leave every set matching no
     * phase, and a set matching no phase would tick unconditionally.
     */
    val MOVEMENT_PHASES = listOf("eccentric", "concentric")

    /**
     * One phase of a recorded set, as far as the label is concerned.
     *
     * @param name the analyzer's phase name.
     * @param prescribed whether the prescription named a duration for it.
     * @param scored whether the set was actually graded on it.
     */
    data class PhaseFacts(
        val name: String,
        val prescribed: Boolean,
        val scored: Boolean,
    )

    /**
     * @param repsFullyCompliant reps in tolerance on every scored phase they resolved.
     * @param repsEvaluated reps that resolved at least one scored phase.
     * @param phases every phase the analyzer reported for the set, pauses included.
     * @return null when there is no ratio to draw at all. No gradeable rep
     *   means no ratio: drawing it anyway printed "Tempo 0/0 ✓" in the OK tone,
     *   because 0 == 0, a green tick over a set nothing graded. On the history
     *   screen that tick sat in the same Card as the "No reps detected"
     *   verdict; on the rest screen the verdict text does not render for a rep
     *   set, so it appeared beside a "0 ×" header with nothing to contradict
     *   it. Both screens carried that guard separately; it is here now.
     */
    fun of(repsFullyCompliant: Int, repsEvaluated: Int, phases: List<PhaseFacts>): TempoScore? {
        if (repsEvaluated <= 0) return null
        val onRatio = repsFullyCompliant >= repsEvaluated
        return TempoScore(
            text = "Tempo $repsFullyCompliant/$repsEvaluated" + if (onRatio) " ✓" else "",
            tone = if (onRatio) TempoScoreTone.ON_TEMPO else TempoScoreTone.OFF_TEMPO,
            ungradedPhases = ungradedMovementPhases(phases),
            ungradedNote = null,
        )
    }

    /**
     * Prescribed movement phases the set was never graded on.
     *
     * A prescribed PAUSE that nothing measured is not in here: pauses are
     * deliberately never scored, so their absence from the ratio is the design
     * and not a gap in it.
     */
    private fun ungradedMovementPhases(phases: List<PhaseFacts>): List<String> =
        phases.filter { it.name in MOVEMENT_PHASES && it.prescribed && !it.scored }.map { it.name }
}
