package com.macrophage.barspeed.model

/**
 * Pure decisions about which load a set is recorded and pre-filled against.
 *
 * These live here rather than beside their callers in `:app` because no plain
 * JVM test can reach them where they were written. `:app` DOES have a test
 * source set as of `ed274bd`, which falsifies the sentence that stood here --
 * it said `:app` had none -- but not the conclusion: the callers sit inside an
 * `AndroidViewModel`, a `@Composable`, and file-private functions in
 * `RecordViewModel.kt` that no test in another file can name, so reaching them
 * in place would still need Robolectric on the CI path. Nothing in this file touches Android,
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
     * [statedAddedKg] is the added load the lifter has stated FOR THE SET BEING
     * SET UP, and null when they have stated nothing for it. It is a different
     * fact from [typedAddedKg], which is one string reused across every set of
     * a session: this one has no default that means anything, is written only
     * by a keystroke, and is re-decided by [standingStatedAddedKg] on every
     * rest transition, so it cannot outlive the span that function allows it.
     * That is what lets a plan set honour a load the lifter gave while still
     * refusing to read a value left behind by an earlier exercise.
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
     * Whether the set just finished and the set coming up belong to the same
     * BLOCK of one exercise -- the span a load the lifter states is allowed to
     * hold for.
     *
     * A block, not an exercise. `flattenPlan` walks a session's exercises in
     * order and emits one slot per set of each, and nothing stops the same
     * movement appearing in two of them; the second block is a fresh
     * prescription rather than a continuation of the first.
     * [nextSetIndexInExercise] is 0 on exactly the first set of a block, which
     * separates the two cases without needing a block identity that no slot
     * carries.
     *
     * `isExerciseChange` is deliberately not what this reads. That flag is
     * computed as `setIdx == 0 && exerciseIdx > 0` where the queue is
     * flattened and as `prevId != slot.exercise.id` where it is reordered by
     * the switch-exercise route, so on a session running one movement in two
     * consecutive blocks the two disagree about it.
     *
     * A null on either id, or on the index, means there is no such pair: the
     * queue ran out, or the set just finished was ad-hoc and belongs to no
     * block at all. Nothing carries across that.
     */
    fun sameExerciseBlock(lastExerciseId: String?, nextExerciseId: String?, nextSetIndexInExercise: Int?): Boolean =
        lastExerciseId != null &&
            lastExerciseId == nextExerciseId &&
            nextSetIndexInExercise != null &&
            nextSetIndexInExercise > 0

    /**
     * The added load the lifter STATED that still stands for the set coming up,
     * or null when that set is offered whatever its own slot declares.
     *
     * A load typed for one set used to reach that set and no further: the rest
     * transition cleared the statement and re-seeded the load field from the
     * plan, so a lifter who moved up in weight and did not retype it on every
     * subsequent set had the prescription recorded against reps done at another
     * load, with nothing on screen marking the change. #124.
     *
     * [statedAddedKg] is the statement as it stood when the set that just
     * finished was written: what the lifter typed for it, null when they typed
     * nothing. Zero is a statement rather than an absence, the same way it is
     * in [resolve] -- a lifter who stripped the bar has said something -- so
     * this parameter is tested against null and never for truthiness.
     *
     * [sameExerciseBlock] bounds the carry; see that function for what a block
     * is.
     *
     * [lastDeclaredAddedKg] and [nextDeclaredAddedKg] are the two slots'
     * `plannedLoadKg`, frozen at what the plan declared and never written back
     * to. NOT their `loadKg`, which carries the statement itself once
     * [carriedIntoNextSet] has baked it in -- comparing those two would compare
     * a number against itself. A plan that declares a DIFFERENT load for the
     * next set is prescribing a change, and it is that number the lifter is
     * offered: a warm-up corrected upward must not become the working set's
     * load, and a block written 60/80/100 must still climb after its opener is
     * adjusted. One keystroke still displaces it, exactly as before.
     *
     * A null on one side only is not that case and is not claimed to be: the
     * plan declared no load for one of the two sets rather than a different
     * one. Null compares unequal to a number, so the carry drops there too --
     * the right direction, and stated as what it is rather than as a
     * prescription. Null on BOTH sides compares equal, which is the loadless
     * block carrying an added load the lifter supplied.
     *
     * The two declarations are compared with `==` on Double, which is exact
     * here rather than approximate: both are `PlanSetDef.resolvedLoadKg`
     * applied to the plan's own text, so two slots declaring the same load in
     * the same unit hold the same bits, and nothing arithmetic happens to
     * either on the way in. In the SAME UNIT: `resolvedLoadKg` is `loadKg ?:
     * loadLb?.let { it / LB_PER_KG }`, so one slot written `load_lb: 90` and
     * the next written `load_kg: 40.8233133` are the same weight and compare
     * unequal. The carry drops there, which is the safe direction and not the
     * intended one.
     *
     * A carried load does not touch `plannedLoad_kg`. The plan's prescription
     * is stored beside the load actually recorded for every set, so a carry is
     * visible afterwards as a deviation on each set it reached.
     */
    fun standingStatedAddedKg(
        statedAddedKg: Double?,
        sameExerciseBlock: Boolean,
        lastDeclaredAddedKg: Double?,
        nextDeclaredAddedKg: Double?,
    ): Double? = statedAddedKg?.takeIf { sameExerciseBlock && lastDeclaredAddedKg == nextDeclaredAddedKg }

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

    // ---- Correcting the load of the set that has just been recorded (#205) ----
    //
    // THE SET JUST FINISHED, FROM THE REST SCREEN, AND NOTHING ELSE. Editing an
    // arbitrary past set from the history screen is a larger and different
    // thing -- it has no standing load to reconcile, no rest window bounding
    // it, and no `SetRatingTracker` already pointed at the row -- and it is
    // deliberately out of scope here. Every function below assumes the row
    // being corrected is the one the rest screen is resting after.
    //
    // WHY LOAD AND NOT SOMETHING ELSE. It is the only value in a set the app
    // cannot observe. Reps are counted, seconds are clocked, effort and the
    // limiter are asked; the load is whatever was typed or carried BEFORE the
    // set ran, so a lifter who set the app to 60 kg and put 65 on the bar had
    // no way to say so once the set was over.
    //
    // BODY-WEIGHT WORK IS CORRECTED ON THE ADDED PORTION, like every other
    // load in this object, and the added portion may be negative for band or
    // machine assistance. [correctedTotalKg] is what puts it back on the
    // body-weight-inclusive scale the row stores.

    /**
     * DELIBERATELY WRONG (#205 c2). How much one tap of the rest screen's load
     * correction moves the recorded load, in kilograms.
     */
    fun correctionStepKg(unit: WeightUnit): Double = unit.toKg(1.0)

    /**
     * DELIBERATELY WRONG (#205 c2). The added load the finished set stands at
     * after one tap.
     */
    fun correctedAddedKg(recordedAddedKg: Double, deltaKg: Double, bodyweight: Boolean): Double =
        if (bodyweight) recordedAddedKg - deltaKg else recordedAddedKg + deltaKg

    /**
     * DELIBERATELY WRONG (#205 c2). The body-weight-inclusive total to store
     * once the added load has been corrected.
     */
    fun correctedTotalKg(recordedTotalKg: Double, recordedAddedKg: Double, correctedAddedKg: Double): Double =
        recordedTotalKg - recordedAddedKg - correctedAddedKg

    /**
     * DELIBERATELY WRONG (#205 c2). Whether the load standing for the set
     * coming up should move with this correction.
     */
    fun carryFollowsCorrection(standingAddedKg: Double?, recordedAddedKg: Double, unit: WeightUnit): Boolean =
        standingAddedKg != null && unit.inputValue(standingAddedKg) != unit.inputValue(recordedAddedKg)

    /** DELIBERATELY WRONG (#205 c2). What the correction says it changes. */
    fun correctionCaption(carryFollows: Boolean): String = "Corrects the next set" + if (carryFollows) " too" else ""

    /** DELIBERATELY WRONG (#205 c2). The row's own label. */
    fun correctionLabel(corrected: Boolean): String = if (corrected) "Corrected" else "Load"
}
