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
 * This replaces the prose `SetDeviationSummary.parts` returned before #204
 * deleted it. Both said the same thing; they said it in different places.
 * `parts` produced sentences for a line UNDER the card while the card went on
 * stating the plan's numbers, so a changed load was on screen twice -- once as
 * a number with no sign it had moved, once as a sentence explaining that it
 * had. The owner's ruling is that the number carries it: strike the plan's
 * figure, put the new one beside it. A pair cannot be struck through halfway,
 * which is why this returns pairs and not strings.
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
        // The side the set will WORK, and beside it the one the plan asked
        // for -- frozen the way plannedReps and plannedTempo are, so the card
        // can strike one against the other. NOT READ AT THIS COMMIT: the
        // strike is #215's fix and the pins for it are red until then.
        plannedSide: String? = null,
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
        val sideValue = side?.replaceFirstChar { it.uppercase() }?.let { SetCardValue(stated = it) }
        val repsValue =
            reps?.let {
                val changed = plannedReps != null && it != plannedReps
                SetCardValue(
                    stated = it.toString(),
                    planned = plannedReps?.toString().takeIf { _ -> changed },
                    suffix = "reps",
                )
            }
        val holdValue =
            durationS?.let {
                val changed = plannedDurationS != null && it != plannedDurationS
                SetCardValue(
                    stated = "${it}s",
                    planned = "${plannedDurationS}s".takeIf { _ -> changed },
                    suffix = if (kind == ExerciseKind.CARRY) "carry" else "hold",
                )
            }
        // What will be RECORDED, by SetLoadPolicy.resolve's own rule for a
        // planned set: the statement if there is one, else the declaration,
        // else nothing added. Comparing THAT against the frozen prescription
        // is what keeps a stated 0 on a loadless plan silent while a stated 0
        // on a 90 kg plan speaks. A truthiness guard here would silence
        // exactly the lifter who stripped the bar.
        val recordedKg = statedLoadKg ?: declaredLoadKg
        val loadChanged = (recordedKg ?: 0.0) != (plannedLoadKg ?: 0.0)
        val statedLoad = loadLabel(bodyweight, timed, unit, recordedKg, speaksZero = loadChanged)
        val plannedLoad = loadLabel(bodyweight, timed, unit, plannedLoadKg, speaksZero = loadChanged)
        val loadValue =
            statedLoad?.let { SetCardValue(stated = it, planned = plannedLoad.takeIf { _ -> loadChanged }) }
        val tempoValue =
            tempo?.let {
                val changed = plannedTempo != null && !sameTempo(plannedTempo, it)
                SetCardValue(stated = it, planned = plannedTempo.takeIf { _ -> changed }, prefix = "tempo")
            }
        return listOfNotNull(sideValue, repsValue, holdValue, loadValue, tempoValue)
    }

    /**
     * [of]'s values as one plain string, with nothing struck.
     *
     * The BASE TEXT of a set: the words and the standing figures, in the order
     * [of] returns them, separated by " · ". A [SetCardValue.planned] figure is
     * DROPPED here rather than rendered -- a plain string cannot strike a
     * figure through, and a set that showed both figures unmarked would be
     * telling the lifter to lift two loads.
     *
     * This is what the session preview reads (#202). The preview draws sets no
     * lifter has deviated from yet, so it needs the base and never the strike,
     * and routing it through this function is what stops the preview and the
     * record flow's "Up next" card phrasing one set two ways.
     * [SessionPreviewPolicy.setLine] is its one caller.
     *
     * `RecordScreen.struckLine` in `:app` lays the same words out into an
     * `AnnotatedString`, adding the struck half where [SetCardValue.planned] is
     * present. The two agree on the plain case BY INSPECTION AND NOT BY ANY
     * TEST: nothing on the CI path can build an `AnnotatedString`. Change one
     * and read the other.
     */
    fun plain(values: List<SetCardValue>): String = values.joinToString(" · ") { value ->
        listOf(value.prefix, value.stated, value.suffix).filter { it.isNotEmpty() }.joinToString(" ")
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
        SetCardValue(stated = "${prepS}s", planned = "${plannedPrepS}s", prefix = "prep")
            .takeIf { prepS != plannedPrepS }

    /**
     * How the card says a load, on either side of a strike.
     *
     * The first three arms are the card's own rule, unchanged: body-weight
     * work keeps [BodyweightLoadDisplay]'s notation, a positive load is the
     * number, and a timed set with nothing added is loaded by the lifter.
     *
     * [speaksZero] is the fourth. A non-positive load on loaded work draws
     * NOTHING on an ordinary card -- there is no plate to name -- but when the
     * lifter has stripped the bar against a plan that asked for 90, a struck
     * "90 kg" with an empty space beside it says less than the sentence this
     * replaced. So the zero is spelled out on exactly the sets where it is a
     * statement, and stays invisible on the sets where it is an absence.
     */
    private fun loadLabel(
        bodyweight: Boolean,
        timed: Boolean,
        unit: WeightUnit,
        kg: Double?,
        speaksZero: Boolean,
    ): String? = when {
        bodyweight -> BodyweightLoadDisplay.label(kg, unit)
        kg != null && kg > 0 -> unit.format(kg)
        speaksZero && kg != null -> unit.format(kg)
        timed -> "bodyweight"
        else -> null
    }

    /** Whether two tempo strings are the same prescription, dashes and all. */
    private fun sameTempo(a: String, b: String): Boolean {
        val left = TempoAdjustPolicy.wheelValues(a)?.joinToString("") ?: a
        val right = TempoAdjustPolicy.wheelValues(b)?.joinToString("") ?: b
        return left == right
    }
}
