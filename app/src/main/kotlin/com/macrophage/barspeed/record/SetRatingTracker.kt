package com.macrophage.barspeed.record

import com.macrophage.barspeed.data.SessionRepository

/**
 * Effort-rating bookkeeping for the set that just finished.
 *
 * Two independent facts decide whether a set counts as failed: what the lifter
 * tapped ("that was a grinder", "I dropped it") and the objective verdict that
 * the set ended short of its target. The rest screen can correct either one, so
 * they are tracked apart and OR-ed on the way to the database — re-tapping the
 * effort must not erase a real shortfall, and correcting a miscounted rep total
 * must clear a shortfall that never actually happened.
 *
 * Every method returns the effective failed flag to mirror into UI state, or
 * null when there is no recorded set to rate.
 */
class SetRatingTracker(private val repository: SessionRepository) {
    private var setId: Long? = null
    private var autoFailed = false
    private var tappedFailed = false
    private var plannedReps: Int? = null

    /**
     * Attach [rating] to the freshly persisted set. [stoppedEarly] is the
     * objective shortfall verdict, judged by the caller only where the rep or
     * second count is trustworthy.
     */
    suspend fun onSetRecorded(setId: Long, plannedReps: Int?, stoppedEarly: Boolean, rating: SetRating?): Boolean {
        this.setId = setId
        this.plannedReps = plannedReps
        autoFailed = stoppedEarly
        tappedFailed = rating?.failed == true
        val failed = tappedFailed || autoFailed
        if (rating != null || stoppedEarly) {
            repository.rateSet(setId, rpe = rating?.rpe, failed = failed, warmup = rating?.warmup == true)
        }
        return failed
    }

    /** Correct how the set FELT. The shortfall verdict survives the correction. */
    suspend fun rate(rpe: Int?, failed: Boolean, warmup: Boolean): Boolean? {
        val id = setId ?: return null
        tappedFailed = failed
        val effective = failed || autoFailed
        repository.rateSet(id, rpe, effective, warmup)
        return effective
    }

    /**
     * Correct a miscounted (or uncounted) rep total. The shortfall was derived
     * from the count, so it is re-derived here — otherwise a set the sensor
     * under-counted stays marked failed forever.
     */
    suspend fun correctReps(reps: Int, rpe: Int?, warmup: Boolean): Boolean? {
        val id = setId ?: return null
        val planned = plannedReps
        autoFailed = planned != null && reps < planned
        val effective = tappedFailed || autoFailed
        repository.overrideReps(id, reps)
        repository.rateSet(id, rpe = rpe, failed = effective, warmup = warmup)
        return effective
    }
}
