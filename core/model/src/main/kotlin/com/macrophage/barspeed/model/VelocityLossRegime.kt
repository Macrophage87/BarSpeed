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
 * spend, and best-to-last measures how well they held the count.
 *
 * Field-38 is a whole session of such sets -- sixteen dynamic sets, every one
 * tempo-prescribed -- and the first archive recorded with two accelerometers on
 * every set, so it can ask what a SECOND unit would have published. Sets 12 and
 * 13 are the rows that carry the argument: two units bolted to ONE rail-guided
 * stack, one travel, agreeing exactly on rep count, and still 5.6 and 7.3 points
 * apart on velocity loss (41.9 against 36.3, and 27.0 against 19.7). One travel
 * and one lifter leaves no fatigue in that difference. Across the session the
 * choice of unit moves the published figure much further still -- 2.7 against
 * 74.1 on set 1 -- though that set is a dumbbell incline press, so its two units
 * are on two implements and two travels and are not a controlled comparison.
 *
 * ## The rule, from the owner
 *
 * "When the concentric digit is X, then it matters, or if it's straight reps or
 * explosive. Otherwise I don't look at it." Exactly three cases give
 * [MAX_INTENT] and everything else is [CONTROLLED]. The velocity TARGET plays
 * no part -- an earlier draft of #250 had it in the rule and the owner dropped
 * it.
 *
 * ## Reading the concentric digit: the PLANE first, the direction second
 *
 * Horizontal work is read by PHASE -- digit 1 the eccentric, digit 3 the
 * concentric -- because a seated row or a chest press has no up and no down
 * for a positional reading to attach to. Only VERTICAL work is POSITIONAL, and
 * there the concentric is digit 3 when the drive moves up and digit 1 when it
 * moves down: a leg curl, a lat pulldown, a pushdown. `TempoSchedule.of` says
 * the same two-step rule as `digit1IsConcentric = if (horizontal) false else
 * !concentricUp`, and `TempoAdjustPolicy.digits` says it a third time.
 * `VelocityLossRegimeTempoScheduleContractTest` in `:core:dsp` pins this file
 * equal to `TempoSchedule`, because `:core:dsp` is the only module that can
 * import both.
 *
 * CORRECTION, carried forward rather than reworded away. This section stated
 * the positional half as the whole rule -- "Tempo digits are POSITIONAL, so
 * the concentric is digit 3 when the drive moves up and digit 1 when it moves
 * down" -- and [of] was written to match, taking no plane at all. That is
 * round 1 finding 2 on this branch. A set declared `plane: horizontal` with
 * `concentric: down` and prescribed `30X0` came out [CONTROLLED] while the
 * voice guide called digit 3 the DRIVE and gave it no count, so the history
 * card withheld the velocity pill on a set whose drive speed nothing had
 * fixed. Reading digit 3 blindly gets every VERTICAL concentric-down lift
 * backwards, which is `PhaseTempoTarget`'s and `TempoSchedule`'s own lesson
 * (#127); reading the direction blindly got every horizontal one backwards,
 * which was this file's.
 *
 * WHAT TODAY'S CONTRACT ALLOWS, stated because the rule above is wider than it.
 * The plan schema's tempo pattern accepts `X` only in digit 3
 * (`[0-9]{2}[Xx][0-9]`) and [Tempo.parse] refuses a non-numeric digit 1. So a
 * VERTICAL concentric-DOWN lift cannot declare an explosive concentric at all:
 * its digit 1 is always a number and it is therefore always [CONTROLLED]
 * whenever it carries a tempo, and the branch that would return [MAX_INTENT]
 * for it is written and unreachable. HORIZONTAL work is not in that position.
 * Its concentric IS digit 3, whatever `concentric` the plan declared beside
 * `plane`, so `30X0` on a chest press or a chest-supported row is [MAX_INTENT]
 * under today's contract -- reachable, and the case the correction above
 * names. Widening the pattern for vertical concentric-down work is its own
 * piece of work, filed as #258, because it changes [Tempo]'s shape, the plan
 * schema under its unreleased number, the voice guide and the tempo scorer
 * together.
 *
 * ## Absence is a state
 *
 * [of] returns null where the regime is not decidable rather than picking a
 * word. Three inputs do that: a set whose geometry was never stored (every set
 * recorded before that column existed, and with it the plane and the drive
 * direction), a tempo string this build cannot parse, and a hold or a carry,
 * which has no concentric velocity for the question to
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
         * plan wrote; [concentricUp], [horizontal] and [kind] come from the
         * geometry FROZEN on the set's row when it was recorded, never from
         * the exercise definition as it stands today. The four inputs are all
         * stored, so this is derived at export time and needs no column of its
         * own -- unlike `rpeScale`, which records a question a lifter was shown
         * and has to be frozen.
         *
         * [horizontal] is nullable rather than the plain `Boolean` round 1
         * finding 2 asked for, and that is deliberate: `ResolvedGeometry` is
         * stored present-or-absent as a unit, so two of the three call sites
         * hold `geometry?.horizontal`, and defaulting a missing plane to
         * `false` would state a plane no row recorded. [kind] being null
         * already ends the decision before the plane is asked for, so the
         * nullability costs no reachable branch.
         */
        fun of(
            tempoPrescribed: String?,
            concentricUp: Boolean?,
            horizontal: Boolean?,
            kind: ExerciseKind?,
        ): VelocityLossRegime? = when {
            // No stored geometry: the set cannot be placed, and inventing a
            // direction reads exactly like a measured one.
            kind == null -> null
            // A hold and a carry have no concentric and no per-rep velocity,
            // so neither word is true of them.
            kind == ExerciseKind.HOLD || kind == ExerciseKind.CARRY -> null
            kind == ExerciseKind.EXPLOSIVE -> MAX_INTENT
            tempoPrescribed == null -> MAX_INTENT
            else -> ofTempo(Tempo.parseOrNull(tempoPrescribed), concentricUp, horizontal)
        }

        private fun ofTempo(tempo: Tempo?, concentricUp: Boolean?, horizontal: Boolean?): VelocityLossRegime? = when {
            // A prescription this build cannot read is not the same fact as no
            // prescription: something was asked for and the digits are not
            // available to say what.
            tempo == null -> null
            // Digit 3 is a number. Whichever digit the drive is, it is a
            // number -- digit 1 has no explosive form -- so neither the plane
            // nor the direction can change the answer and neither is consulted.
            !tempo.isExplosiveUpStroke -> CONTROLLED
            // Digit 3 is X, so the answer turns on whether digit 3 is the
            // concentric. The PLANE decides that outright on horizontal work,
            // where digit 3 is the concentric by phase and the declared drive
            // direction has nothing to attach to.
            horizontal == true -> MAX_INTENT
            // Vertical work is positional, so here the direction decides.
            horizontal == false && concentricUp == true -> MAX_INTENT
            horizontal == false && concentricUp == false -> CONTROLLED
            // No plane, or vertical with no direction: an X in digit 3 could be
            // either stroke and nothing on the row says which. Unreachable from
            // the three call sites -- ResolvedGeometry is stored as a unit, so
            // a null plane means a null kind, which returned above -- and null
            // rather than a guess for the same reason every other absence here
            // is.
            else -> null
        }
    }
}
