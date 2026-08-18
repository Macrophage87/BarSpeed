package com.macrophage.barspeed.model

/**
 * Pure decisions about which load a set is recorded and pre-filled against.
 *
 * These live here rather than beside their callers in `:app` because no plain
 * JVM test can reach them where they were written. `:app` has no test source
 * set today, and adding one would not be enough by itself: both callers sit
 * inside an `AndroidViewModel` and a `@Composable`, so reaching them in place
 * would need Robolectric on the CI path. Nothing in this file touches Android,
 * Room or a sensor. Callers pass what the plan declared and what the lifter
 * typed, and get a number back.
 *
 * Every load handled here is the ADDED load — the number a plan writes as
 * `load_kg`, which may be negative on assisted body-weight work. The load that
 * actually travelled on a body-weight movement is body weight plus that, and
 * that sum stays at the call site; nothing here knows the lifter's mass.
 */
object SetLoadPolicy {
    /**
     * The added load to record for a set, in kilograms.
     *
     * [plannedAddedKg] is the plan's declaration for the slot being recorded.
     * Null there means the plan named no load, which is contract rather than a
     * gap: `PlanSetDef.validate` never requires one, and both the schema and
     * the in-app guide tell the plan writer to omit both load fields for
     * body-weight work. So null means nothing was added, and the answer is 0.
     *
     * [typedAddedKg] is the load text field already parsed, null when it is
     * blank or not a number. It is consulted only for ad-hoc sets, where it is
     * the only declaration there is. On a plan set it is not evidence, and
     * [statedAddedKg] rather than this parameter is what carries a number the
     * lifter gave for a planned set.
     *
     * [statedAddedKg] is the added load the lifter typed FOR THE SET BEING SET
     * UP, and null when they have typed nothing for it. It is a different fact
     * from [typedAddedKg], which is one string reused across every set of a
     * session: this one has no default that means anything, is written only by
     * a keystroke, and is cleared by every path that changes which set is being
     * set up. That is what lets a plan set honour a load the lifter gave while
     * still refusing to read a value left behind by an earlier set.
     *
     * A 0 returned here is a real measurement of the added load, not a stand-in
     * for an unknown one, so it is a number rather than a null. Nothing
     * downstream reads it as data that was not collected: `SetAnalyzer` guards
     * bar power with `takeIf { it > 0 }`, so a zero added load on a non-
     * body-weight set suppresses the power figure instead of publishing 0 W.
     */
    fun resolve(adHoc: Boolean, plannedAddedKg: Double?, typedAddedKg: Double?, statedAddedKg: Double?): Double =
        if (adHoc) typedAddedKg ?: 0.0 else statedAddedKg ?: plannedAddedKg ?: 0.0

    /**
     * The load to pre-fill the editable load field with before the next set, or
     * null to leave the field showing whatever it showed last.
     *
     * [nextDeclaredAddedKg] must be read from the upcoming slot's live
     * `loadKg`, which carries any in-rest edit already applied to it — never
     * from `plannedLoadKg`, which is frozen at the plan's declaration and would
     * silently discard those edits.
     *
     * [hasPlannedNext] separates two facts that must not share an answer. "The
     * next planned set declares no load" is a declaration, and seeds 0. "There
     * is no next planned set at all" is the ad-hoc case and the end of a plan,
     * where the last load is the only thing to go on and carrying it forward is
     * what the lifter expects. Conflating them is what leaks one exercise's
     * load into the next, because the seeded field is baked back into the
     * upcoming slot and read straight back out as if the plan had declared it.
     *
     * [lastAddedKg] is the ADDED load of the set just finished, not the total
     * that travelled. On body-weight work the difference is the lifter's mass,
     * and seeding from the total is what makes a loadless block climb set over
     * set.
     */
    fun seedAddedKg(hasPlannedNext: Boolean, nextDeclaredAddedKg: Double?, lastAddedKg: Double?): Double? =
        if (hasPlannedNext) nextDeclaredAddedKg ?: 0.0 else lastAddedKg

    /**
     * The added load the upcoming planned slot carries once the lifter taps
     * through to it, replacing what the plan declared.
     *
     * [declaredAddedKg] is the slot's own load, still the plan's number because
     * this slot has not been through this function yet.
     *
     * The rest screen's load field is deliberately NOT a parameter. It used to
     * be what this read, and that is the whole of #45: the field is seeded from
     * the declaration through inputValue, which quantises to 0.1 of the DISPLAY
     * unit, so reading it back replaced the plan's own number with a rounded
     * one even when the lifter never touched the box. A plan declaring 175 lb
     * recorded 79.4 against a plannedLoadKg of 79.3786647517562, and the
     * session detail screen printed a deviation the lifter did not make.
     *
     * [statedAddedKg] is what they actually said, null when they said nothing,
     * and it is the only thing that displaces the declaration. An untouched
     * field is no longer in the path at all.
     */
    fun carriedIntoNextSet(declaredAddedKg: Double?, statedAddedKg: Double?): Double? = statedAddedKg ?: declaredAddedKg

    /**
     * The load actually borne by a lifter's body on a body-weight movement:
     * their own mass plus whatever was added, which the plan and the lifter
     * may state as negative for band or machine assistance. Loaded work has
     * no body in the path, so this is [addedKg] unchanged.
     *
     * [bodyWeightKg] null means the lifter has never recorded a body weight
     * -- #61's silent-default gap, not fixed here. The body-weight term is
     * simply 0 in that state, so a body-weight set with no recorded body
     * weight records its added load alone. This function does not widen
     * that gap or narrow it: whatever [bodyWeightKg] holds when it runs is
     * exactly what both the actual load and its paired planned load below
     * are computed from, so the two stay on the same scale regardless of
     * whether it is null.
     */
    fun totalKg(bodyweight: Boolean, bodyWeightKg: Double?, addedKg: Double): Double =
        if (bodyweight) (bodyWeightKg ?: 0.0) + addedKg else addedKg

    /**
     * The planned load to store beside [totalKg] when a finished set is
     * written, so the two are comparable.
     *
     * Runs [plannedAddedKg] through the same [totalKg] that computed the
     * actual load, from the same [bodyWeightKg] reading, so a set recorded
     * exactly as the plan prescribed cannot disagree with itself. A null
     * [plannedAddedKg] -- a plan slot that declared no load at all -- stays
     * null rather than becoming a declared zero: there is no planned load to
     * pair on either scale. #25.
     */
    fun recordedPlannedLoadKg(bodyweight: Boolean, bodyWeightKg: Double?, plannedAddedKg: Double?): Double? =
        plannedAddedKg?.let { totalKg(bodyweight, bodyWeightKg, it) }
}
