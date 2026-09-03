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
     * A rep count typed for one set used to reach that set and no further,
     * while a load typed for one set held for the block: "while adjustments to
     * the weight stay between sets, changing reps does not". So a lifter who
     * drops a set from 8 to 6 because the bar was heavier than the plan thought
     * had to say so again on every remaining set. #174.
     *
     * FOUR BOUNDARIES, TAKEN FROM [SetLoadPolicy.standingStatedAddedKg] AS IT
     * STOOD AT #174 AND NOT REDESIGNED. Read that function for the reasoning
     * behind the first three; what follows is what each one means for a count.
     * THE FOURTH HAS SINCE DIVERGED THERE AND NOT HERE: #143 made a load
     * carry across a plan's step as a distance, and a count does not. A rep
     * scheme is a prescription rather than a quantity on a bar, the numbers
     * are small enough that a correction of -4 on 10 / 8 / 6 would ask for two
     * reps and then none, and the descending scheme below is exactly what the
     * yield protects. So a plan declaring a different count for the next set
     * is still offered as written.
     *
     * [statedReps] is the statement as it stood when the set that just finished
     * was written: what the lifter typed for it, null when they typed nothing
     * or when the box held text that was not a number. Tested against null and
     * never for truthiness — though unlike a load, where zero is a real
     * statement, a zero rep count is not a set at all and the control does not
     * offer one; the null test is what the two policies share rather than a
     * claim that 0 is meaningful here.
     *
     * [sameExerciseBlock] bounds the carry and is [SetLoadPolicy.sameExerciseBlock]'s
     * answer, passed in.
     *
     * [lastDeclaredReps] and [nextDeclaredReps] are the two slots' FROZEN
     * declarations — `PlannedSlot.plannedReps`, never their `reps`, which the
     * bake has already written the statement into; comparing those would
     * compare a number against itself and the carry would never stop. THIS IS
     * THE RULE THAT KEEPS A DESCENDING SCHEME DESCENDING: a plan writing
     * 10 / 8 / 6 declares a different count for the next set, so it is
     * prescribing a change and it is the plan's number the lifter is offered.
     * Without it, changing set one to 12 would silently make the block
     * 12 / 12 / 12 — the failure a naive carry produces, and a far worse one
     * than the load's equivalent, because a rep scheme is usually written to
     * change and a load is usually written to repeat.
     *
     * A null on one side only is not that case: the plan declared no count for
     * one of the two sets rather than a different one. Null compares unequal to
     * a number, so the carry drops there. Null on BOTH sides compares equal,
     * which is a block written without rep targets — sets to failure, an AMRAP
     * tail — keeping the number the lifter supplied, because nothing else
     * offers them one.
     *
     * A carried count does not touch the frozen declaration. `plannedReps` is
     * stored beside the count actually performed for every set, so a carry is
     * visible afterwards as a deviation on each set it reached.
     */
    fun standingStatedReps(
        statedReps: Int?,
        sameExerciseBlock: Boolean,
        lastDeclaredReps: Int?,
        nextDeclaredReps: Int?,
    ): Int? = statedReps?.takeIf { sameExerciseBlock && lastDeclaredReps == nextDeclaredReps }

    /**
     * The same, for the seconds of a hold or a carry: a lifter who cuts a 45 s
     * plank to 30 has said something about the exercise and not about one set
     * of it, and a hold block that ramps 30 / 45 / 60 keeps its own seconds
     * when the opener is changed.
     *
     * One rule, written once above and applied here to the other target. The
     * separate name is what keeps a call site from handing the rep count to the
     * seconds decision: both are `Int?` and nothing else distinguishes them.
     */
    fun standingStatedDurationS(
        statedDurationS: Int?,
        sameExerciseBlock: Boolean,
        lastDeclaredDurationS: Int?,
        nextDeclaredDurationS: Int?,
    ): Int? = statedDurationS?.takeIf { sameExerciseBlock && lastDeclaredDurationS == nextDeclaredDurationS }
}
