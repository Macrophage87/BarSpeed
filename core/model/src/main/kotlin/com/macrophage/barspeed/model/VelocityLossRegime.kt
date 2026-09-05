package com.macrophage.barspeed.model

import kotlinx.serialization.Serializable

/**
 * Which question a set's velocity loss is an answer to (#250).
 *
 * `velocityLoss_pct` -- best rep to last rep -- is published on every dynamic
 * set and read everywhere as fatigue. That reading assumes the lifter drove
 * every concentric as hard as they could, so a slowing rep means a tiring
 * lifter. It is the right model on a free-tempo barbell lift.
 *
 * On a tempo-prescribed controlled movement the concentric speed IS the
 * prescription. A `2011` fly asks for a one-second stroke on every rep, so the
 * velocity is an instruction the lifter follows rather than a capacity they
 * spend, and best-to-last measures how well they held the count. Field-38 is a
 * whole session of such sets: sixteen dynamic sets, every one tempo-prescribed,
 * whose velocity-loss figures behave like noise -- 2.7 against 74.1 from the
 * two units on set 1, and 41.9 against 36.3 from two units bolted to one stack
 * on set 12. Two sensors on one bar cannot disagree by 71 points about how
 * tired the lifter got; they can easily disagree that much about a figure that
 * is not measuring fatigue in the first place.
 *
 * ## The rule, from the owner
 *
 * "When the concentric digit is X, then it matters, or if it's straight reps or
 * explosive. Otherwise I don't look at it." Exactly three cases give
 * [MAX_INTENT] and everything else is [CONTROLLED]. The velocity TARGET plays
 * no part -- an earlier draft of #250 had it in the rule and the owner dropped
 * it.
 *
 * ## Reading the concentric digit
 *
 * Tempo digits are POSITIONAL, so the concentric is digit 3 when the drive
 * moves up and digit 1 when it moves down -- a leg curl, a lat pulldown, a
 * pushdown. Reading digit 3 blindly gets every concentric-down lift backwards,
 * which is `PhaseTempoTarget`'s and `TempoSchedule`'s own lesson (#127).
 *
 * WHAT TODAY'S CONTRACT ALLOWS, stated because the rule above is wider than it.
 * The plan schema's tempo pattern accepts `X` only in digit 3
 * (`[0-9]{2}[Xx][0-9]`) and [Tempo.parse] refuses a non-numeric digit 1, so a
 * concentric-DOWN lift cannot declare an explosive concentric at all: its
 * digit 1 is always a number and it is therefore always [CONTROLLED] whenever
 * it carries a tempo. That is a limit of the contract, not of this decision --
 * the branch that would return [MAX_INTENT] for it is written and unreachable.
 * Widening the pattern is its own piece of work, filed as #258, because it
 * changes [Tempo]'s shape, the plan schema under its unreleased number, the
 * voice guide and the tempo scorer together.
 *
 * ## Absence is a state
 *
 * [of] returns null where the regime is not decidable rather than picking a
 * word. Three inputs do that: a set whose geometry was never stored (every set
 * recorded before that column existed), a tempo string this build cannot parse,
 * and a hold or a carry, which has no concentric velocity for the question to
 * be about. A reader that finds no word reads the set the way every reader read
 * every set before this key existed, which is [MAX_INTENT]'s reading -- so
 * absence loses nothing and claims nothing.
 */
@Serializable
enum class VelocityLossRegime(
    /** The word published in the session export. */
    val wireName: String,
) {
    /**
     * Velocity loss is the autoregulation figure, as it has always been:
     * straight reps, an explosive lift, or a prescribed tempo whose concentric
     * digit is `X`.
     */
    MAX_INTENT("maxIntent"),

    /**
     * The concentric speed is the prescription, so velocity loss measures
     * compliance rather than fatigue. The set is read on `tempoCompliance`,
     * `romSpread_pct` and the effort rating instead.
     */
    CONTROLLED("controlled"),
    ;

    /**
     * Whether velocity loss is the figure to lead with on a set in this regime.
     *
     * The screens read this rather than comparing against a member, so the two
     * of them cannot come apart and neither can decide it for itself.
     */
    val readsVelocityLoss: Boolean get() = this == MAX_INTENT

    companion object {
        /**
         * The regime of a set, or null where it is not decidable.
         *
         * [tempoPrescribed] is the set's own prescription in the notation the
         * plan wrote; [concentricUp] and [kind] come from the geometry FROZEN
         * on the set's row when it was recorded, never from the exercise
         * definition as it stands today. The three inputs are all stored, so
         * this is derived at export time and needs no column of its own --
         * unlike `rpeScale`, which records a question a lifter was shown and
         * has to be frozen.
         */
        fun of(tempoPrescribed: String?, concentricUp: Boolean?, kind: ExerciseKind?): VelocityLossRegime? = when {
            // No stored geometry: the set cannot be placed, and inventing a
            // direction reads exactly like a measured one.
            kind == null -> null
            // A hold and a carry have no concentric and no per-rep velocity,
            // so neither word is true of them.
            kind == ExerciseKind.HOLD || kind == ExerciseKind.CARRY -> null
            kind == ExerciseKind.EXPLOSIVE -> MAX_INTENT
            tempoPrescribed == null -> MAX_INTENT
            else -> ofTempo(Tempo.parseOrNull(tempoPrescribed), concentricUp)
        }

        private fun ofTempo(tempo: Tempo?, concentricUp: Boolean?): VelocityLossRegime? = when {
            // A prescription this build cannot read is not the same fact as no
            // prescription: something was asked for and the digits are not
            // available to say what.
            tempo == null -> null
            // Digit 3 is a number. Whichever digit the drive is, it is a
            // number -- digit 1 has no explosive form -- so the direction
            // cannot change the answer and is not consulted.
            !tempo.isExplosiveUpStroke -> CONTROLLED
            // Digit 3 is X, so the answer turns on which stroke the drive is.
            concentricUp == true -> MAX_INTENT
            concentricUp == false -> CONTROLLED
            else -> null
        }
    }
}
