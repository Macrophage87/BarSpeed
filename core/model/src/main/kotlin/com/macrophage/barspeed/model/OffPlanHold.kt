package com.macrophage.barspeed.model

/**
 * The statements that stood for [slot] when an off-plan set was put in front
 * of it: a load, rep count, hold or tempo the lifter stated ([stated]) and a
 * side he chose ([side]).
 */
data class HeldStatements(
    val slot: NextSetSlot,
    val stated: NextSetStatements,
    val side: String?,
)

/** [inputs] for the slot an off-plan set was in front of, and the side to restore with them. */
data class ResumedStatements(val inputs: NextSetInputs, val side: String?)

/**
 * What the lifter said about a block survives an off-plan set run in the
 * middle of it (whole-release review, off-plan set mid-block).
 *
 * "Different exercise..." puts one set of another lift next ([isOffPlan]).
 * Every route past it asked [NextSetInputs.of] about (off-plan set, the
 * block's next slot): two exercises, so no block, so every statement dropped
 * and the slot re-seeded from the plan -- a load he corrected from 90 to 100
 * went back to 90 and recorded unless he noticed. Capture faithfully: what he
 * stated is what the set should record.
 *
 * So the statements standing for the displaced slot are held aside when the
 * off-plan set goes in ([hold]) and given back when that same slot comes up
 * again after it -- the off-plan set run, skipped or removed ([resumed]).
 * They were made FOR that slot, so they come back as they were, not re-decided
 * across a block boundary; the boxes show them ahead of the slot's own values,
 * the rule [NextSetInputs] keeps everywhere else. SWITCH EXERCISE moving a
 * whole block is not this and still clears.
 */
object OffPlanHold {
    /**
     * Whether a slot is an OFF-PLAN set: an added set that is its own one-set
     * block. ADD SET's appended set is added too but continues its block
     * (`setIndexInExercise` above 0), and carries statements the usual way.
     */
    fun isOffPlan(isAddedSet: Boolean, setIndexInExercise: Int, setsInExercise: Int): Boolean =
        isAddedSet && setIndexInExercise == 0 && setsInExercise == 1

    /** What to hold when an off-plan set displaces [displaced], or null when nothing was said for it. */
    fun hold(displaced: NextSetSlot?, stated: NextSetStatements, side: String?): HeldStatements? =
        if (displaced == null || (stated == NextSetStatements.NONE && side == null)) {
            null
        } else {
            HeldStatements(displaced, stated, side)
        }

    /**
     * The inputs for [next] given back from [held], or null when [next] is not
     * the slot they were held for (same exercise and set number) -- the
     * caller's own rule then stands.
     */
    fun resumed(held: HeldStatements?, next: NextSetSlot?): ResumedStatements? {
        if (held == null || next == null) return null
        if (next.exerciseId != held.slot.exerciseId || next.setIndexInExercise != held.slot.setIndexInExercise) {
            return null
        }
        val stated = held.stated
        val inputs =
            NextSetInputs(
                statedKg = stated.loadKg,
                statedReps = stated.reps,
                statedDurationS = stated.durationS,
                statedTempo = stated.tempo,
                loadKg = stated.loadKg ?: next.loadKg,
                reps = stated.reps ?: next.reps,
                durationS = stated.durationS ?: next.durationS,
                adHocTempo =
                TempoAdjustPolicy.seedTempo(hasPlannedNext = true, nextDeclaredTempo = next.tempo, lastRanTempo = null)
                    ?: "",
            )
        return ResumedStatements(inputs, held.side)
    }
}
