package com.macrophage.barspeed.model

/** How a tempo ratio should be toned, which is not the same question as what it covers. */
enum class TempoScoreTone {
    /** Every graded rep was in tolerance, and every prescribed movement phase was measured on every rep of the set. */
    ON_TEMPO,

    /**
     * Every graded rep was in tolerance, but a prescribed movement phase went
     * unmeasured on some or all of the set's reps.
     */
    PARTIAL,

    /** At least one graded rep was outside tolerance. */
    OFF_TEMPO,
}

/**
 * The tempo chip's text and tone, plus the sentence that must accompany it.
 *
 * [ungradedPhases] is the fact -- which prescribed movement phases the set was
 * never graded on -- and [ungradedNote] is the sentence a screen draws for it
 * and for any phase graded on only some reps (#230), null when there is
 * nothing to qualify. A caller that drops the note renders
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
 * names, two booleans and a count -- rather than a `:core:dsp` type, so this module
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
     * @param repsResolved how many reps resolved this phase: the analyzer's
     *   stored per-phase `repsEvaluated`, read rather than recounted. No
     *   default, so a caller cannot build these facts without saying it.
     */
    data class PhaseFacts(
        val name: String,
        val prescribed: Boolean,
        val scored: Boolean,
        val repsResolved: Int,
    )

    /**
     * @param repsFullyCompliant reps in tolerance on every scored phase they resolved.
     * @param repsEvaluated reps that resolved at least one scored phase.
     * @param setReps every rep of the analysis the ratio was taken from,
     *   graded or not -- the count the eccentric caption (#89) and the
     *   verdict line (#328) take [PhaseCoverage] over. No default, so a
     *   caller cannot build the chip without saying it.
     * @param phases every phase the analyzer reported for the set, pauses included.
     * @return null when there is no ratio to draw at all. No gradeable rep
     *   means no ratio: drawing it anyway printed "Tempo 0/0 ✓" in the OK tone,
     *   because 0 == 0, a green tick over a set nothing graded. On the history
     *   screen that tick sat in the same Card as the "No reps detected"
     *   verdict; on the rest screen the verdict text does not render for a rep
     *   set, so it appeared beside a "0 ×" header with nothing to contradict
     *   it. Both screens carried that guard separately; it is here now.
     */
    fun of(repsFullyCompliant: Int, repsEvaluated: Int, setReps: Int, phases: List<PhaseFacts>): TempoScore? {
        if (repsEvaluated <= 0) return null
        val onRatio = repsFullyCompliant >= repsEvaluated
        val ungraded = ungradedMovementPhases(phases)
        // Over EVERY rep of the set, not the reps the ratio graded (#329). A
        // rep that resolved no scored phase is outside repsEvaluated, so a
        // count taken over it could never see that rep, and the chip ticked
        // over it. The set's reps are the count the eccentric caption (#89)
        // and the verdict line (#328) take coverage over, so the chip's note
        // and the lines beside it state one gap with one number.
        val partial = partlyGraded(phases, setReps)
        // The tick is a claim about the SET, so it needs both: every graded rep
        // in tolerance, and every phase the set prescribed actually measured ON
        // EVERY REP OF THE SET. The ratio alone answers only the first, and a set
        // graded on its drives alone satisfies it while the eccentric behind it
        // was never measured. PhaseFacts.scored is set-level -- SetAnalyzer's
        // own flag, true the moment ONE rep resolves the phase -- so it cannot
        // say a phase went unmeasured on some reps; repsResolved can, and
        // PhaseCoverage is the rule that reads it (#230).
        val covered = ungraded.isEmpty() && partial.isEmpty()
        val complete = onRatio && covered
        return TempoScore(
            text = "Tempo $repsFullyCompliant/$repsEvaluated" + if (complete) " ✓" else "",
            // Compliance and coverage are separate questions and are answered
            // separately: a rep outside tolerance is a miss whatever went
            // ungraded beside it, so a real miss is never softened to PARTIAL.
            tone =
            when {
                !onRatio -> TempoScoreTone.OFF_TEMPO
                covered -> TempoScoreTone.ON_TEMPO
                else -> TempoScoreTone.PARTIAL
            },
            ungradedPhases = ungraded,
            ungradedNote =
            listOfNotNull(
                noteFor(ungraded, gradedMovementPhases(phases)),
                partialNoteFor(partial),
            ).joinToString(" ").ifEmpty { null },
        )
    }

    /**
     * The sentence for phases measured on some graded reps and not others,
     * or null when there are none: each phase's count and its gap, the gap in
     * [PhaseCoverage]'s words.
     */
    private fun partialNoteFor(partial: List<Pair<String, PhaseCoverage>>): String? {
        if (partial.isEmpty()) return null
        return partial.joinToString(" ") { (name, coverage) ->
            "${name.replaceFirstChar { it.uppercase() }}: ${coverage.measured} of ${coverage.of} reps measured · " +
                "${coverage.notMeasuredClause}."
        }
    }

    /**
     * Prescribed movement phases the set WAS graded on, but not on every rep
     * in [of], each with its [PhaseCoverage] over [of].
     *
     * Only scored phases: a phase no rep resolved is [ungradedMovementPhases]'
     * and already has its sentence.
     */
    private fun partlyGraded(phases: List<PhaseFacts>, of: Int): List<Pair<String, PhaseCoverage>> {
        val scored = phases.filter { it.name in MOVEMENT_PHASES && it.prescribed && it.scored }
        return scored
            .map { it.name to PhaseCoverage(measured = it.repsResolved, of = of) }
            .filter { (_, coverage) -> !coverage.complete }
    }

    /**
     * The sentence a screen draws under the chip, or null when the set was
     * graded on everything it prescribed.
     *
     * It names the phase rather than hedging generally, because the lifter's
     * next question is which half of the rep the app is silent about.
     */
    private fun noteFor(ungraded: List<String>, graded: List<String>): String? {
        if (ungraded.isEmpty()) return null
        val subject = ungraded.joinToString(" and ").replaceFirstChar { it.uppercase() }
        val covers =
            if (graded.isEmpty()) "." else " -- the ratio covers the ${graded.joinToString(" and ")} only."
        return "$subject not measured this set$covers"
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

    /** Prescribed movement phases the set WAS graded on -- what the ratio does cover. */
    private fun gradedMovementPhases(phases: List<PhaseFacts>): List<String> =
        phases.filter { it.name in MOVEMENT_PHASES && it.prescribed && it.scored }.map { it.name }
}
