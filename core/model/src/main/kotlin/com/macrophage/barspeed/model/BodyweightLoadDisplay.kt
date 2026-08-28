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
 * it ([PlanFile.validate] passes `allowNegativeLoad = exercise.bodyweight`), so
 * a dead hang can take assistance and a push-up can take a plate whether or not
 * the plan happened to declare a load for them.
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
    @Suppress("UnusedParameter")
    fun label(addedKg: Double?, unit: WeightUnit): String =
        // SEAM ONLY. This is what the plan screen renders today, moved here
        // unchanged so the differentials that add the sign and the prefix can
        // be pushed red against it first: a positive added load renders as a
        // bare total and a negative one disappears entirely.
        addedKg?.takeIf { it > 0 }?.let { unit.format(it) } ?: BARE

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
     * [implementCount] is the plan's declaration, read through
     * [ImplementLoad.count] so absence and a declared 1 give the same answer.
     * "Total" there is about the objects held, not about body weight; the two
     * words never appear together because a body-weight set's box takes the
     * added load whether it is on a belt or in two hands, and the split is
     * shown under the box rather than asked for in it.
     */
    @Suppress("UnusedParameter")
    fun fieldLabel(bodyweight: Boolean, implementCount: Int?, unit: WeightUnit): String =
        // SEAM ONLY: today's two answers, with the body-weight case still
        // falling through to them so its differential can be pushed red.
        if (ImplementLoad.count(implementCount) > 1) "Total load (${unit.suffix})" else "Load (${unit.suffix})"

    /**
     * The line under the load box saying which way the sign runs, or null when
     * there is no sign to explain.
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
    // SEAM ONLY, and both suppressions are the marker for it: today no screen
    // says this at all, so the seam says nothing and the differential that
    // gives the body-weight case a sentence can be pushed red. Both leave with
    // that sentence.
    @Suppress("UnusedParameter", "FunctionOnlyReturningConstant")
    fun fieldHint(bodyweight: Boolean): String? = null
}
