package com.macrophage.barspeed.record

import com.macrophage.barspeed.model.CountAndRatingDraft
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One Correct-popup SAVE that changes the count or the hold AND the rating
 * must leave the row carrying the rating the lifter saved (#310).
 *
 * Field-45's set 13, a rope dead hang, stored rpe 10 beside failedByLifter
 * true, which no single write this code issues pairs. The path read from
 * source, inferred and not observed: a standing 10, then one SAVE that
 * stepped the hold down 5 s and carried the failure tile. The hold write read
 * the standing 10 before it suspended in Room and wrote it back after the
 * rating write had stored the failure.
 *
 * The writer here suspends every count or hold statement until the test opens
 * a gate, so the statement issued FIRST completes AFTER anything issued behind
 * it -- the order Room's pool is free to pick and that field-45 is inferred to
 * have hit. Dispatchers.Unconfined stands in for Main.immediate: a launch runs
 * on the calling thread up to its first suspension, and a resumption runs on
 * the thread that opens the gate, so every interleaving here is the test's.
 *
 * What this cannot say is what Room or a phone did. It pins the order of the
 * statements this code issues against a writer that answers when told; the
 * field instance is inferred from its stored row, not observed.
 */
class CountAndRatingOrderTest {
    private data class Row(val rpe: Int?, val failed: Boolean, val failedByLifter: Boolean?)

    private class GatedWriter : SetRowWriter {
        val gate = CompletableDeferred<Unit>()
        var row: Row? = null

        override suspend fun rateSet(
            setId: Long,
            rpe: Int?,
            failed: Boolean,
            failedByLifter: Boolean?,
            warmup: Boolean,
        ) {
            row = Row(rpe, failed, failedByLifter)
        }

        override suspend fun overrideReps(setId: Long, reps: Int) {
            gate.await()
        }

        override suspend fun overrideDuration(setId: Long, seconds: Int) {
            gate.await()
        }

        override suspend fun overrideLoad(setId: Long, loadKg: Double) = Unit

        override suspend fun setWarmupMark(setId: Long, warmupMark: Boolean?) = Unit

        override suspend fun setLimiter(setId: Long, limiter: String?, limiterNote: String?) = Unit
    }

    /**
     * Rate the set 10, save one draft carrying [reps] or [seconds] beside the
     * rating [rpe] / [tappedFailed], then let the count or hold statement
     * return last. Answers the row the writer was left holding.
     */
    private fun saveWithCountReturningLast(
        plan: Pair<Int?, Int?>,
        reps: Int? = null,
        seconds: Int? = null,
        rpe: Int?,
        tappedFailed: Boolean,
    ): Row? {
        val writer = GatedWriter()
        val tracker = SetRatingTracker(writer)
        tracker.onSetRecorded(plan.first, plan.second, stoppedEarly = false, rating = SetRating(10, failed = false))
        tracker.attachTo(1L)
        val stateFlow = MutableStateFlow(RecordState(lastSetRpe = 10, lastSetTappedFailed = false))
        val errors = mutableListOf<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + CoroutineExceptionHandler { _, e -> errors += e })
        val draft = CountAndRatingDraft(reps, seconds, rpe, tappedFailed, ratingChanged = true)

        applyCountAndRating(stateFlow, draft, tracker, scope, Dispatchers.Unconfined)
        writer.gate.complete(Unit)

        assertTrue(errors.isEmpty(), "a correction coroutine threw: $errors")
        return writer.row
    }

    // plan = planned reps to planned seconds; 35 s is field-45's hang.
    private val hold35 = null to 35
    private val fiveReps = 5 to null

    @Test
    fun `a shorter hold saved with the failure tile stores no rpe when the hold write returns last`() {
        val row = saveWithCountReturningLast(hold35, seconds = 30, rpe = null, tappedFailed = true)
        assertEquals(Row(rpe = null, failed = true, failedByLifter = true), row)
    }

    @Test
    fun `a longer hold saved with a new rung stores that rung when the hold write returns last`() {
        val row = saveWithCountReturningLast(hold35, seconds = 40, rpe = 8, tappedFailed = false)
        assertEquals(Row(rpe = 8, failed = false, failedByLifter = false), row)
    }

    @Test
    fun `a lower rep count saved with the failure tile stores no rpe when the count write returns last`() {
        val row = saveWithCountReturningLast(fiveReps, reps = 4, rpe = null, tappedFailed = true)
        assertEquals(Row(rpe = null, failed = true, failedByLifter = true), row)
    }

    @Test
    fun `a higher rep count saved with a new rung stores that rung when the count write returns last`() {
        val row = saveWithCountReturningLast(fiveReps, reps = 6, rpe = 8, tappedFailed = false)
        assertEquals(Row(rpe = 8, failed = false, failedByLifter = false), row)
    }
}
