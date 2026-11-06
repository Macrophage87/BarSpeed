package com.macrophage.barspeed.model

/**
 * What the in-set voice says at a phase second and at a lockout, and what the
 * two latches behind it become.
 *
 * Lifted out of `RecordViewModel` unchanged. That class is measured AT
 * detekt's `LargeClass` limit -- `:app:detekt` reported it over the default
 * 600 on this branch -- and the two functions that lived there were the
 * largest block in it that decides nothing about recording. Here they run on
 * every push; in `:app` nothing reached them.
 *
 * BOTH ANSWERS ARE LATCH TRANSITIONS, not events. The caller holds the two
 * latches and this says what they become, so a caller cannot advance one
 * without taking the word that goes with it.
 *
 * WHAT IS NOT DECIDED HERE. Whether the voice is switched on at all, and
 * whether the sensor rather than the guide is the counter, are the caller's
 * gates and stay there: this is asked only once those hold.
 */
object VoiceMilestonePolicy {
    /**
     * The counted phase, the last second spoken in it, and the word to speak
     * now -- null where nothing is said.
     */
    data class PhaseCount(val phase: Phase, val second: Int, val speak: String?)

    /**
     * One frame of the tempo count: speaks 1, 2, 3... through each moving
     * phase.
     *
     * [countedPhase] and [spokenSecond] are the latches as they stand; the
     * returned pair is what they become. A phase change resets the second, and
     * a phase that is not [Phase.ECCENTRIC] or [Phase.CONCENTRIC] still moves
     * [countedPhase] -- so the first second of the next moving phase is spoken
     * rather than swallowed by a second left over from the previous one.
     *
     * The second is [elapsedS] truncated, so nothing is spoken before one full
     * second of the phase has passed.
     */
    fun phaseCount(phase: Phase, elapsedS: Double, countedPhase: Phase, spokenSecond: Int): PhaseCount {
        val carried = if (phase == countedPhase) spokenSecond else 0
        if (phase != Phase.ECCENTRIC && phase != Phase.CONCENTRIC) return PhaseCount(phase, carried, null)
        val second = elapsedS.toInt()
        if (second < 1 || second == carried) return PhaseCount(phase, carried, null)
        return PhaseCount(phase, second, second.toString())
    }

    /**
     * Which words a counter may say at and just before the planned count.
     *
     * Two answers because the two kinds of counter can be wrong in different
     * directions. The lifter's own tap is the count, so the tap counter may
     * call the set over. A sensor can reach the planned count before the
     * lifter has finished, and on a straight-reps set the lifter may go past
     * it, so the sensor's voice never calls the set over.
     * `CountingPolicy.milestones` says which a set gets.
     */
    enum class Milestones {
        /** `"Last rep"` one before the planned count and `"Done"` at it. */
        CALLS_SET_OVER,

        /** The sensor's: every rep is named, and nothing calls the set over. */
        MARKS_TARGET,
    }

    /**
     * What is said as rep [repCount] completes, or null where nothing is said
     * and the announced-rep latch does not move.
     *
     * Null on a repeat of the rep already announced and on zero, so a count
     * that stands still says nothing. [plannedReps] null is a set with no
     * planned count -- every rep is "Rep N" and none is the last.
     *
     * [milestones] is which words the planned count gets; see [Milestones].
     */
    fun repMilestone(
        repCount: Int,
        announcedRep: Int,
        plannedReps: Int?,
        milestones: Milestones = Milestones.CALLS_SET_OVER,
    ): String? {
        if (repCount == announcedRep || repCount == 0) return null
        if (milestones == Milestones.MARKS_TARGET) return "Rep $repCount"
        return when {
            plannedReps != null && repCount == plannedReps -> "Done"
            plannedReps != null && repCount == plannedReps - 1 && plannedReps > 1 -> "Last rep"
            else -> "Rep $repCount"
        }
    }
}
