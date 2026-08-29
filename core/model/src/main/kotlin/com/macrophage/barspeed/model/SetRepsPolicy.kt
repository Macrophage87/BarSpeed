package com.macrophage.barspeed.model

/**
 * Pure decisions about the COUNT a set is asked for: the rep target on a
 * counted set, and the seconds on a hold or a carry.
 *
 * Beside [SetLoadPolicy] and for its reasons — the callers sit inside an
 * `AndroidViewModel` and file-private functions in `RecordViewModel.kt` that no
 * plain JVM test can name, so the decision is lifted to where a test on every
 * push can reach it. Nothing here touches Android, Room or a sensor.
 *
 * TWO TARGETS, ONE RULE, TWO NAMES. Reps and hold seconds have the identical
 * shape — an `Int?` the plan declares per set, which the lifter may replace
 * from the change-set dialog — and each rule below is written once and given a
 * named entry point per target, so a call site cannot silently hand the rep
 * count to the seconds decision or the other way round. What must never happen
 * is the rule itself existing twice; two copies of "how long does a statement
 * hold" is two rules that can disagree.
 *
 * THE BLOCK BOUNDARY IS NOT RE-DERIVED HERE. [SetLoadPolicy.sameExerciseBlock]
 * decides it, is passed in, and is asked ONCE per rest transition for the load,
 * the tempo and these two together: three carries bounded by the same block is
 * one question, and the way they diverge is one of them stopping at a boundary
 * another runs past.
 */
object SetRepsPolicy {
    /**
     * The rep target the upcoming planned slot carries once the lifter taps
     * through to it, replacing what the plan declared.
     *
     * [declaredReps] is the slot's own count, still the plan's number because
     * this slot has not been through this function yet. [statedReps] is what the
     * lifter said, null when they said nothing or when the box holds text that
     * is not a number, and it is the only thing that displaces the declaration.
     *
     * The mirror of [SetLoadPolicy.carriedIntoNextSet], and behaviour-identical
     * to the expression it replaces in `bakedState`.
     */
    fun carriedIntoNextSet(declaredReps: Int?, statedReps: Int?): Int? = statedReps ?: declaredReps

    /**
     * The same, for the seconds of a hold or a carry. Named separately from
     * [carriedIntoNextSet] because both targets are `Int?` and nothing but the
     * name distinguishes them at a call site.
     */
    fun carriedDurationIntoNextSet(declaredDurationS: Int?, statedDurationS: Int?): Int? =
        statedDurationS ?: declaredDurationS

    /**
     * The rep count the lifter STATED that still stands for the set coming up,
     * or null when that set is offered whatever its own slot declares.
     *
     * THIS IS THE SHIPPED BEHAVIOUR, WRITTEN DOWN, NOT THE FIX. Today a rep
     * count typed for one set reaches that set and no further: the rest
     * transition re-seeds `repsInput` from the plan and nothing carries the
     * statement, which is #174's report — "while adjustments to the weight stay
     * between sets, changing reps does not". Returning null unconditionally is
     * exactly that, in a place a test can state it, so the boundary rules the
     * carry must inherit can be pinned as differentials against it rather than
     * asserted against a symbol that does not exist yet.
     *
     * The parameters are [SetLoadPolicy.standingStatedAddedKg]'s, one target
     * over, and they are already in the signature because the fix must not be
     * able to change the shape of the question — only the answer.
     */
    // Both suppressions are TRANSIENT and belong to the constant body, not to
    // the signature: detekt is right that a function returning a constant and
    // ignoring its arguments is a stub, and it is one. The commit that
    // implements the carry deletes both.
    @Suppress("UNUSED_PARAMETER", "FunctionOnlyReturningConstant")
    fun standingStatedReps(
        statedReps: Int?,
        sameExerciseBlock: Boolean,
        lastDeclaredReps: Int?,
        nextDeclaredReps: Int?,
    ): Int? = null

    /**
     * The same, for the seconds of a hold or a carry, and shipped in the same
     * state: a hold the lifter shortened is shortened for one set only.
     */
    // Both suppressions are TRANSIENT and belong to the constant body, not to
    // the signature: detekt is right that a function returning a constant and
    // ignoring its arguments is a stub, and it is one. The commit that
    // implements the carry deletes both.
    @Suppress("UNUSED_PARAMETER", "FunctionOnlyReturningConstant")
    fun standingStatedDurationS(
        statedDurationS: Int?,
        sameExerciseBlock: Boolean,
        lastDeclaredDurationS: Int?,
        nextDeclaredDurationS: Int?,
    ): Int? = null
}
