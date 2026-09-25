package com.macrophage.barspeed.record

import com.macrophage.barspeed.data.SessionRepository
import com.macrophage.barspeed.model.CorrectedRatingRow
import com.macrophage.barspeed.model.CountAndRatingDraft
import com.macrophage.barspeed.model.SetCorrectionPolicy

/**
 * Every write [SetRatingTracker] issues against the finished set's row.
 *
 * An interface so the ORDER of those writes can be pinned (#310): a test hands
 * the tracker a writer whose statements suspend and return when the test says,
 * which is the one way to reach an interleaving Room's pool would otherwise
 * decide. Production passes the repository, unchanged, through the tracker's
 * secondary constructor. Nothing here executes Room.
 */
interface SetRowWriter {
    suspend fun rateSet(setId: Long, rpe: Int?, failed: Boolean, failedByLifter: Boolean?, warmup: Boolean)
    suspend fun overrideReps(setId: Long, reps: Int)
    suspend fun overrideDuration(setId: Long, seconds: Int)
    suspend fun overrideLoad(setId: Long, loadKg: Double)
    suspend fun setWarmupMark(setId: Long, warmupMark: Boolean?)
    suspend fun setLimiter(setId: Long, limiter: String?, limiterNote: String?)
}

/** The repository as a [SetRowWriter]: each call is the repository's own, as before. */
private class RepositoryRowWriter(private val repository: SessionRepository) : SetRowWriter {
    override suspend fun rateSet(setId: Long, rpe: Int?, failed: Boolean, failedByLifter: Boolean?, warmup: Boolean) {
        repository.rateSet(setId, rpe, failed, failedByLifter, warmup)
    }

    override suspend fun overrideReps(setId: Long, reps: Int) {
        repository.overrideReps(setId, reps)
    }

    override suspend fun overrideDuration(setId: Long, seconds: Int) {
        repository.overrideDuration(setId, seconds)
    }

    override suspend fun overrideLoad(setId: Long, loadKg: Double) {
        repository.overrideLoad(setId, loadKg)
    }

    override suspend fun setWarmupMark(setId: Long, warmupMark: Boolean?) {
        repository.setWarmupMark(setId, warmupMark)
    }

    override suspend fun setLimiter(setId: Long, limiter: String?, limiterNote: String?) {
        repository.setLimiter(setId, limiter, limiterNote)
    }
}

/**
 * Rest-screen bookkeeping for the set that just finished: the two failure
 * facts, and every correction the rest screen can write onto its row --
 * reps, held seconds, why it ended, the warm-up mark and, since #205, the
 * load.
 *
 * Two independent facts decide whether a set counts as failed: what the lifter
 * tapped ("that was a grinder", "I dropped it") and the objective verdict that
 * the set ended short of its target. The rest screen can correct either one, so
 * they are tracked apart and OR-ed on the way to the database — re-tapping the
 * effort must not erase a real shortfall, and correcting a miscounted rep total
 * must clear a shortfall that never actually happened.
 *
 * Every method returns what to mirror into UI state -- the effective failed
 * flag, or from [correct] the whole row it wrote -- or null when there is no
 * recorded set to rate.
 */
class SetRatingTracker(private val writer: SetRowWriter) {
    constructor(repository: SessionRepository) : this(RepositoryRowWriter(repository))

    private var setId: Long? = null
    private var autoFailed = false
    private var tappedFailed = false
    private var plannedReps: Int? = null
    private var plannedDurationS: Int? = null

    /**
     * Work out the failed verdict for a set that is about to be stored, and
     * remember the two facts behind it. [stoppedEarly] is the objective
     * shortfall verdict, judged by the caller only where the rep or second
     * count is trustworthy.
     *
     * Returns the whole rating half of the row -- the rpe, the OR and the
     * lifter's own half -- so the set write stores one answer rather than
     * re-reading the rating it was handed beside a verdict worked out here
     * (#313). The rpe is the rating the set ended with, which is what the
     * write stored before this returned it. The lifter's half is the tap the
     * row stores beside the OR (#216); it was a separate `lifterCalledFailure`
     * property read straight after this call, and folding it into the answer
     * leaves nothing to read at the wrong moment. The two facts have always
     * been kept apart here and only the OR reached the database before #216,
     * which is why a set the lifter called a grinder and one the app derived a
     * shortfall for were indistinguishable in every export written before it.
     *
     * Writes nothing. The rating is now stored with the set row itself rather
     * than updated onto it afterwards, so the caller passes the returned row
     * into the insert. This used to issue `rateSet` as a second statement, and
     * only `if (rating != null || stoppedEarly)`; when that condition was false
     * the values it skipped writing were exactly the row's defaults, so storing
     * them unconditionally with the row stores the same thing. What it removes
     * is the window in between, where a set the lifter had just tapped as
     * failed existed in the database rated as nothing, permanently, because no
     * screen can edit a set's rating once the rest screen is gone.
     */
    fun onSetRecorded(
        plannedReps: Int?,
        plannedDurationS: Int?,
        stoppedEarly: Boolean,
        rating: SetRating?,
    ): CorrectedRatingRow {
        this.plannedReps = plannedReps
        this.plannedDurationS = plannedDurationS
        autoFailed = stoppedEarly
        tappedFailed = rating?.failed == true
        return CorrectedRatingRow(rpe = rating?.rpe, failed = tappedFailed || autoFailed, failedByLifter = tappedFailed)
    }

    /**
     * Point the rest-screen corrections at the row that was just stored.
     *
     * Separate from [onSetRecorded] because the id does not exist until the
     * insert returns, and because a set-end write that fails must not leave
     * [rate] and [correct] aimed at a row that was never written.
     */
    fun attachTo(setId: Long) {
        this.setId = setId
    }

    /**
     * The lifter's own statement about whether the set was preparatory (#194).
     *
     * Its own statement, beside [limit] and for the same reason. It does not
     * touch `warmup`, which is what the PLAN declared and is frozen at the
     * write, and it does not touch either failure fact: what a set was FOR is
     * orthogonal to how it went, which is the whole of #187 and the reason
     * this is not a seventh effort tile.
     *
     * Returns null when there is no recorded set to mark.
     */
    suspend fun markWarmup(mark: Boolean?): Boolean? {
        val id = setId ?: return null
        writer.setWarmupMark(id, mark)
        return true
    }

    /**
     * Record, change or clear why the set ENDED (#189).
     *
     * Its own statement rather than an argument of [rate], and the separation
     * is the point: the reason is asked on a page after the set is stored and
     * is corrected on the rest screen independently of the effort. Folded into
     * the rating write, every effort correction would carry a reason the
     * lifter did not touch, and every reason would carry an effort they did
     * not restate.
     *
     * Neither failure fact is read or written here. Why a set ended is not a
     * verdict on whether it failed -- a lifter naming "stopped for an outside
     * reason" is saying the set is not a training signal, not that it did not
     * end short -- so the OR that decides [failed] is left exactly where it
     * was. Returns null when there is no recorded set to mark.
     */
    suspend fun limit(limiter: String?, note: String?): Boolean? {
        val id = setId ?: return null
        writer.setLimiter(id, limiter, note)
        return true
    }

    /**
     * Correct how the set FELT. The shortfall verdict survives the correction.
     *
     * Returns the rating row it wrote, as [correct] does, so the rest screen
     * mirrors what the row holds rather than what the tap carried.
     */
    suspend fun rate(rpe: Int?, failed: Boolean, warmup: Boolean): CorrectedRatingRow? {
        val id = setId ?: return null
        tappedFailed = failed
        val row = CorrectedRatingRow(rpe = rpe, failed = failed || autoFailed, failedByLifter = failed)
        writer.rateSet(id, row.rpe, row.failed, failedByLifter = row.failedByLifter, warmup = warmup)
        return row
    }

    /**
     * One Correct-popup SAVE's count or hold and its rating, as ONE rating
     * write (#310).
     *
     * The shortfall was derived from the count, so a corrected count or hold
     * re-derives it -- otherwise a set the sensor under-counted stays marked
     * failed forever, and a lifter who states they carried on past the target
     * is left failed on a figure that has since moved (#168). Seconds are
     * judged by `TimedSetEndPolicy.fellShort` through
     * [SetCorrectionPolicy.shortfall], the same boundary the set write asked.
     *
     * The tapped half comes off the draft and nothing else. The popup seeds
     * the draft from what stands, so a SAVE that only corrects the count
     * carries the lifter's own tap through untouched and the two facts stay
     * two facts.
     *
     * Both fields move BEFORE the first suspension and the statements are
     * issued in sequence, each awaited: the count or hold first, then the
     * one `rateSet` [SetCorrectionPolicy.row] folded. There is no second
     * rating statement for Room's pool to reorder, and no rating read before a
     * suspension to go stale across it -- the mechanism #310 names: the count's
     * `rateSet` carried the rpe read before `overrideDuration` suspended, and
     * could land after the lifter's new rating. Read from source; the order
     * Room ran them in on a phone was never observed.
     */
    suspend fun correct(draft: CountAndRatingDraft, warmup: Boolean): CorrectedRatingRow? {
        val id = setId ?: return null
        autoFailed = SetCorrectionPolicy.shortfall(draft, plannedReps, plannedDurationS, standing = autoFailed)
        val row = SetCorrectionPolicy.row(draft, autoFailed)
        tappedFailed = row.failedByLifter
        draft.seconds?.let { writer.overrideDuration(id, it) }
        draft.reps?.let { writer.overrideReps(id, it) }
        writer.rateSet(id, row.rpe, row.failed, failedByLifter = row.failedByLifter, warmup = warmup)
        return row
    }

    /**
     * Correct the load the set was recorded at, from the rest screen (#205).
     *
     * [loadKg] is the body-weight-inclusive total for the row, already
     * computed; this only writes it. Returns null when there is no recorded
     * set to correct, the same as every other method here.
     *
     * NEITHER FAILURE FACT IS READ OR WRITTEN, unlike [correct], which
     * re-derives the shortfall. The shortfall is a verdict on the rep count or
     * the seconds against their targets, and no plan target anywhere is a load: a set done at the wrong weight is not
     * thereby a set that fell short. Re-deriving here would either be a no-op
     * or would quietly re-OR a verdict this correction says nothing about.
     */
    suspend fun correctLoad(loadKg: Double): Boolean? {
        val id = setId ?: return null
        writer.overrideLoad(id, loadKg)
        return true
    }
}
