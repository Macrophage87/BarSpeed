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
 *
 * This commit is behaviour-preserving. Both bodies reproduce exactly what the
 * three call sites do today, defect included, so that the pins below describe
 * current behaviour and the change that follows shows as a differential rather
 * than as a rewrite.
 */
object SetLoadPolicy {
    /**
     * The added load to record for a set, in kilograms.
     *
     * [plannedAddedKg] is the plan's declaration for the slot being recorded,
     * null when the plan named no load. [typedAddedKg] is the load text field
     * already parsed, null when it is blank or not a number.
     *
     * Today a null [plannedAddedKg] falls through to the typed field on a plan
     * set as well as an ad-hoc one, which is the behaviour this commit
     * preserves verbatim.
     */
    fun resolve(adHoc: Boolean, plannedAddedKg: Double?, typedAddedKg: Double?): Double =
        if (adHoc || plannedAddedKg == null) typedAddedKg ?: 0.0 else plannedAddedKg

    /**
     * The load to pre-fill the editable load field with before the next set, or
     * null to leave the field showing whatever it showed last.
     *
     * [nextDeclaredAddedKg] must be read from the upcoming slot's live
     * `loadKg`, which carries any in-rest edit already applied to it — never
     * from `plannedLoadKg`, which is frozen at the plan's declaration and would
     * silently discard those edits. [hasPlannedNext] distinguishes "the plan
     * has a next set" from "there is no next planned set at all".
     *
     * The two arms are identical today, because a caller with no next slot has
     * no declaration to pass either: `nextSlot?.loadKg` is null exactly when
     * `nextSlot` is. Splitting them here changes nothing and leaves the two
     * cases separable, which is the whole point of taking the flag.
     */
    fun seedAddedKg(hasPlannedNext: Boolean, nextDeclaredAddedKg: Double?, lastAddedKg: Double?): Double? =
        if (hasPlannedNext) nextDeclaredAddedKg ?: lastAddedKg else lastAddedKg
}
