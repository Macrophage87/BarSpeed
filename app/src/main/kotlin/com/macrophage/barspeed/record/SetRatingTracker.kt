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
     * Work out the failed verdict for a set that is about to be stored, and
     * remember the two facts behind it. [stoppedEarly] is the objective
     * shortfall verdict, judged by the caller only where the rep or second
     * count is trustworthy.
     *
     * Writes nothing. The rating is now stored with the set row itself rather
     * than updated onto it afterwards, so the caller passes the returned flag
     * into the insert. This used to issue `rateSet` as a second statement, and
     * only `if (rating != null || stoppedEarly)`; when that condition was false
     * the values it skipped writing were exactly the row's defaults, so storing
     * them unconditionally with the row stores the same thing. What it removes
     * is the window in between, where a set the lifter had just tapped as
     * failed existed in the database rated as nothing, permanently, because no
     * screen can edit a set's rating once the rest screen is gone.
     */
    fun onSetRecorded(plannedReps: Int?, stoppedEarly: Boolean, rating: SetRating?): Boolean {
        this.plannedReps = plannedReps
        autoFailed = stoppedEarly
        tappedFailed = rating?.failed == true
        return tappedFailed || autoFailed
    }

    /**
     * Point the rest-screen corrections at the row that was just stored.
     *
     * Separate from [onSetRecorded] because the id does not exist until the
     * insert returns, and because a set-end write that fails must not leave
     * [rate] and [correctReps] aimed at a row that was never written.
     */
    fun attachTo(setId: Long) {
        this.setId = setId
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
