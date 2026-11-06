package com.macrophage.barspeed.record

import com.macrophage.barspeed.model.CorrectedRatingRow
import com.macrophage.barspeed.model.CountAndRatingDraft
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A Correct-popup SAVE whose write throws -- a full disk, any SQLite error --
 * must reach the tracker's failure handler, not the scope's, and must leave
 * the rest screen showing what the row still holds. UX review 2026-11-06,
 * data-layer F4.
 *
 * Before the change the throw escaped `appScope.launch` and ended the process
 * between sets. Here the scope's own handler stands in for that crash: an
 * error reaching it is the defect.
 *
 * What this cannot say is that Room rolled the three statements back
 * together; that is `SessionDao.correctCountAndRating`'s `@Transaction`, which
 * no test here executes. The writer below throws on the transactional call
 * itself, the shape the repository writer has.
 */
class CorrectionWriteFailureTest {
    private class FailingWriter : SetRowWriter {
        var rated: CorrectedRatingRow? = null

        override suspend fun rateSet(
            setId: Long,
            rpe: Int?,
            failed: Boolean,
            failedByLifter: Boolean?,
            warmup: Boolean,
        ) {
            rated = CorrectedRatingRow(rpe, failed, failedByLifter == true)
        }

        override suspend fun overrideReps(setId: Long, reps: Int) = Unit

        override suspend fun overrideDuration(setId: Long, seconds: Int) = Unit

        override suspend fun overrideLoad(setId: Long, loadKg: Double) = Unit

        override suspend fun setWarmupMark(setId: Long, warmupMark: Boolean?) = Unit

        override suspend fun setLimiter(setId: Long, limiter: String?, limiterNote: String?) = Unit

        override suspend fun correctCountAndRating(
            setId: Long,
            seconds: Int?,
            reps: Int?,
            rating: CorrectedRatingRow,
            warmup: Boolean,
        ) {
            throw IOException("disk full")
        }
    }

    @Test
    fun `a correction whose write throws is reported and leaves the screen as it was`() {
        val writer = FailingWriter()
        val reported = mutableListOf<Throwable>()
        val tracker = SetRatingTracker(writer) { reported += it }
        tracker.onSetRecorded(5, null, stoppedEarly = true, rating = null)
        tracker.attachTo(1L)
        val before = RecordState(lastSetRpe = null, lastSetFailed = true, lastSetTappedFailed = false)
        val stateFlow = MutableStateFlow(before)
        val crashed = mutableListOf<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + CoroutineExceptionHandler { _, e -> crashed += e })

        // Five reps against a plan of five clears the derived shortfall -- if it saved.
        val draft = CountAndRatingDraft(reps = 5, seconds = null, rpe = 8, tappedFailed = false, ratingChanged = true)
        applyCountAndRating(stateFlow, draft, tracker, scope, Dispatchers.Unconfined)

        assertTrue(crashed.isEmpty(), "the failed write reached the scope, which on a phone ends the process: $crashed")
        assertEquals(listOf("disk full"), reported.map { it.message })
        assertEquals(before, stateFlow.value, "the screen shows a correction the row does not hold")

        // The row kept its shortfall, so the tracker must have too: a re-rating
        // now still stores the failure the app derived.
        runBlocking { tracker.rate(rpe = 8, failed = false, warmup = false) }
        assertEquals(CorrectedRatingRow(rpe = null, failed = true, failedByLifter = false), writer.rated)
    }
}
