package com.macrophage.barspeed.model

/**
 * How the load of a body-weight movement is SHOWN and ASKED FOR.
 *
 * On `bodyweight: true` work the set's load is what was ADDED — a plate on a
 * dip belt, or a NEGATIVE number for the band or assist machine taking weight
 * off. Rendered as a bare number that added load reads as the whole load: "10
 * kg" beside a pull-up says the lifter pulled ten kilograms. The notation here
 * says what it is instead — "BW + 10 kg", "BW − 50 kg", and bare "BW" when
 * nothing was added, which is the ZERO of the notation rather than an absence.
 * #160.
 *
 * This applies to EVERY body-weight exercise, with no fixed-BW subset: the plan
 * contract already permits a signed load on exactly this population and all of
 * it ([PlanFile.validate] passes `allowNegativeLoad = SetGeometryPolicy.bodyweightMount(...)`,
 * the seeded answer rather than the raw declaration, as of #227), so a dead
 * hang can take assistance and a push-up can take a plate whether or not the
 * plan happened to declare a load for them.
 *
 * DISPLAY AND INPUT ONLY. Nothing here is stored, exported or summed. The
 * export keeps its documented scale — [SessionExport]'s `load_kg` and
 * `plannedLoad_kg` are the body-weight-INCLUSIVE totals
 * [SetLoadPolicy.totalKg] computes — and this object never sees a body weight,
 * so it cannot move one. The display convention and the export scale are two
 * different statements about the same set, and conflating them is what this
 * KDoc exists to prevent.
 *
 * WHICH UNIT. Every load in the app renders in the unit the lifter has chosen
 * on the kg/lb chip, and this is no exception: [unit] is that chip's unit at
 * every call site. The issue's wording — "as the case may be in the plan" — is
 * honoured in the half that is representable, the SIGN and the magnitude the
 * plan declared; the plan's declared UNIT is not carried past import
 * (`PlanSetDef.resolvedLoadKg` returns kilograms and no slot keeps which field
 * it came from), and rendering this one figure in a unit different from the
 * plate line and the load box beside it would put two units on one card. Named
 * here rather than left to be discovered.
 */
object BodyweightLoadDisplay {
    /** The zero of the notation: body weight and nothing else. */
    const val BARE = "BW"

    /**
     * The added load of a body-weight set, in the notation above.
     *
     * [addedKg] null means the plan declared no load and the lifter has stated
     * none, which is the same fact as a declared zero — nothing was added — so
     * both give [BARE] rather than one of them giving an empty render. That is
     * the one case where absence and a zero DO share an answer, and they share
     * it because the answer says "body weight", not "0 kg": the load is
     * reported, it is simply the lifter.
     */
    fun label(addedKg: Double?, unit: WeightUnit): String {
        val magnitude = Math.abs(addedKg ?: 0.0)
        // Asked of the RENDERED magnitude, not the stored one. WeightUnit.format
        // quantises to a tenth of the display unit, so a load under half of that
        // has no digits left to show, and the sign would then decorate a zero:
        // "BW + 0 kg" states an addition of nothing. The zero of this notation
        // already has a spelling, and it is BARE. Comparing the formatted text
        // rather than the double is what makes the rule the same rule at both
        // units -- 0.02 kg is a rendered zero in kilograms and in pounds alike,
        // and no epsilon has to be chosen to say so.
        val text = unit.format(magnitude)
        if (text == unit.format(0.0)) return BARE
        val sign = if ((addedKg ?: 0.0) > 0) "+" else "−"
        return "$BARE $sign $text"
    }

    /**
     * What the editable load box takes, said in the box's own label so it
     * cannot be read as a total.
     *
     * The body-weight case is why this decision left `RecordScreen.kt`: a box
     * labelled "Load (kg)" on a pull-up invites the lifter to type their whole
     * loaded weight, which would be recorded as the ADDED load and then have
     * their body weight added to it again at [SetLoadPolicy.totalKg]. The two
     * loaded cases are its alternatives and travel with it, stated once,
     * because a second statement of "when does this say Total" in the screen
     * would be a second rule.
     *
     * The body-weight label is two words and not five. It was "Added to body
     * weight (kg)" until the gate on this branch read the 360dp / font-scale-2
     * harness shots and found that floated label and the dialog's "deviations
     * are recorded" caption drawn over one another, both illegible. "To body
     * weight" is carried by [fieldHint], which has a full-width line to wrap
     * into and is drawn on exactly this population.
     *
     * [implementCount] is the plan's declaration, read through
     * [ImplementLoad.count] so absence and a declared 1 give the same answer.
     * "Total" there is about the objects held, not about body weight; the two
     * words never appear together because a body-weight set's box takes the
     * added load whether it is on a belt or in two hands, and the split is
     * shown under the box rather than asked for in it.
     */
    fun fieldLabel(bodyweight: Boolean, implementCount: Int?, unit: WeightUnit): String = when {
        bodyweight -> "Added (${unit.suffix})"
        ImplementLoad.count(implementCount) > 1 -> "Total load (${unit.suffix})"
        else -> "Load (${unit.suffix})"
    }

    /**
     * The line under the load box saying whose weight the number is added to
     * and which way the sign runs, or null when there is neither to explain.
     *
     * It carries "to body weight" for [fieldLabel], which says only "Added
     * (kg)" so that it cannot composite with the caption above it at
     * font-scale 2 in a 360dp dialog.
     *
     * Null on loaded work is an absence and not an empty string: a barbell box
     * takes a positive number and has nothing to say about negatives, so it
     * gets no line rather than a blank one holding space open.
     *
     * The sentence exists because a signed box is only usable if the lifter
     * knows which direction assistance goes. Nothing else on the screen says
     * it: the plan contract permits the negative, the validator accepts it and
     * [SetLoadPolicy.totalKg] subtracts it, and until now the only place the
     * convention was written down was a KDoc.
     */
    fun fieldHint(bodyweight: Boolean): String? =
        "Added to body weight. Negative for a band or assist machine taking weight off"
            .takeIf { bodyweight }
}
