package com.macrophage.barspeed.model

/**
 * One value on the set card: the plan's figure it displaced, and the figure the
 * set will actually record.
 *
 * [planned] is null when the value IS the plan's, which is the ordinary case
 * and draws exactly what the card drew before this type existed. When it is
 * present the card strikes it through and puts [stated] beside it, so the
 * deviation is read off the number rather than off a sentence somewhere else.
 *
 * [prefix] and [suffix] are the words around the figure -- "tempo", "reps",
 * "hold" -- and are drawn ONCE, outside the strike. "5 reps 8 reps" makes the
 * lifter read the word twice to find the digit that changed; "tempo 4010 6010"
 * with the middle struck does not. The load is the exception and carries its
 * unit inside both figures, because on body-weight work the two sides are
 * composites that do not share a tail: "BW" against "BW + 10 kg".
 */
data class SetCardValue(
    val stated: String,
    val planned: String? = null,
    val prefix: String = "",
    val suffix: String = "",
)

/**
 * The values a set card states, each carrying the plan's figure when the lifter
 * has changed it.
 *
 * This replaces the prose [SetDeviationSummary.parts] returned. Both said the
 * same thing; they said it in different places. `parts` produced sentences for
 * a line UNDER the card while the card went on stating the plan's numbers, so a
 * changed load was on screen twice -- once as a number with no sign it had
 * moved, once as a sentence explaining that it had. The owner's ruling for #204
 * is that the number carries it: strike the plan's figure, put the new one
 * beside it. A pair cannot be struck through halfway, which is why this returns
 * pairs and not strings.
 *
 * The edge cases are `parts`'s and are kept deliberately:
 *
 *  - A stated 0 against a 90 kg plan SPEAKS and a stated 0 against a plan that
 *    declared no load stays SILENT. The comparison is between what will be
 *    RECORDED, by [SetLoadPolicy.resolve]'s rule, and what was prescribed --
 *    never a truthiness test, which would silence exactly the lifter who
 *    stripped the bar.
 *  - Body-weight work keeps [BodyweightLoadDisplay]'s notation on BOTH sides.
 *  - Two spellings of one tempo are one prescription, dashes and all.
 *  - A declaration the plan never made has nothing to deviate from, so an
 *    appended set -- which carries no prescription at all -- strikes nothing.
 */
object SetCardValues {
    /**
     * The card's value line, in the order it is drawn: side, reps, hold, load,
     * tempo.
     *
     * Every "planned" argument is the FROZEN declaration -- `plannedLoadKg`,
     * `plannedTempo`, the slot's own `plannedReps`/`plannedDurationS` -- and
     * never the live field beside it, which carries the lifter's edit once
     * `advancedState` has baked it in. Comparing those two would compare a
     * number against itself and the card would go blank at the exact moment it
     * has something to say.
     *
     * [declaredLoadKg] is the slot's live `loadKg`, and it is what the card
     * drew before this function existed. It is read only where the lifter has
     * stated nothing, which is what keeps an APPENDED set -- whose
     * [plannedLoadKg] is null because no plan prescribed it -- drawing its load
     * instead of falling to nothing.
     */
    @Suppress("LongParameterList", "UNUSED_PARAMETER")
    fun of(
        kind: ExerciseKind,
        bodyweight: Boolean,
        timed: Boolean,
        unit: WeightUnit,
        side: String?,
        plannedLoadKg: Double?,
        statedLoadKg: Double?,
        declaredLoadKg: Double?,
        plannedReps: Int?,
        reps: Int?,
        plannedDurationS: Int?,
        durationS: Int?,
        plannedTempo: String?,
        tempo: String?,
    ): List<SetCardValue> {
        // DELIBERATELY WRONG, and replaced whole in the next commit. This
        // commit exists so SetCardValuesTest's pins are shown failing before
        // the implementation lands, which is the only durable evidence that
        // they can fail at all.
        return emptyList()
    }

    /**
     * The prep pair, or null when the prep is the plan's.
     *
     * Prep is the one deviation with no figure on the card to strike: the
     * card states what the set is, and the seconds before it starts are not
     * part of that. It is drawn on the card's SECONDARY line -- beside the
     * rest clock, which is the other duration there -- and only when it
     * deviates, so an untouched set gains nothing.
     */
    fun prep(plannedPrepS: Int, prepS: Int): SetCardValue? =
        // DELIBERATELY WRONG in the same way and for the same reason as [of]:
        // it pairs every prep, including one the lifter never touched, so the
        // pin that says an untouched prep has nothing to draw is shown failing
        // before the guard that makes it pass exists.
        SetCardValue(stated = "${prepS}s", planned = "${plannedPrepS}s", prefix = "prep")
}
