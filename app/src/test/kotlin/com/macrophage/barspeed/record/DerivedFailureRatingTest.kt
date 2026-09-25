package com.macrophage.barspeed.record

import com.macrophage.barspeed.model.CorrectedRatingRow
import com.macrophage.barspeed.model.CountAndRatingDraft
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every write [SetRatingTracker] issues that can pair a rating with a failure
 * the app DERIVED stores no rpe (#313): the set write, the rest screen's
 * re-rating, and the Correct popup's SAVE.
 *
 * The owner's rule: "Don't ask for an RPE on failed sets, if you can't do it,
 * it's failed." `failedByLifter` never moves for it -- a derived failure stays
 * the app's.
 *
 * 35 s is field-45 set 13's plan; 30 of 35 is under 90 % and derives short.
 * What this cannot say is what Room stored or what a phone drew: the writer
 * here is a fake that keeps the last rating statement, and
 * `RecordViewModel`'s own reading of the returned row is compile-gated only.
 */
class DerivedFailureRatingTest {
    private class LastRowWriter : SetRowWriter {
        var row: CorrectedRatingRow? = null

        override suspend fun rateSet(
            setId: Long,
            rpe: Int?,
            failed: Boolean,
            failedByLifter: Boolean?,
            warmup: Boolean,
        ) {
            row = CorrectedRatingRow(rpe, failed, failedByLifter == true)
        }

        override suspend fun overrideReps(setId: Long, reps: Int) = Unit

        override suspend fun overrideDuration(setId: Long, seconds: Int) = Unit

        override suspend fun overrideLoad(setId: Long, loadKg: Double) = Unit

        override suspend fun setWarmupMark(setId: Long, warmupMark: Boolean?) = Unit

        override suspend fun setLimiter(setId: Long, limiter: String?, limiterNote: String?) = Unit
    }

    private val derivedFailure = CorrectedRatingRow(rpe = null, failed = true, failedByLifter = false)

    @Test
    fun `a set written short of its target stores no rpe beside the derived failure`() {
        val tracker = SetRatingTracker(LastRowWriter())
        assertEquals(
            derivedFailure,
            tracker.onSetRecorded(null, 35, stoppedEarly = true, rating = SetRating(8, failed = false)),
        )
    }

    @Test
    fun `a rest-screen rating on a set the app judged short is not stored`() = runBlocking {
        val writer = LastRowWriter()
        val tracker = SetRatingTracker(writer)
        tracker.onSetRecorded(null, 35, stoppedEarly = true, rating = null)
        tracker.attachTo(1L)
        assertEquals(derivedFailure, tracker.rate(8, failed = false, warmup = false), "what the screen mirrors")
        assertEquals(derivedFailure, writer.row, "what the row was written with")
    }

    @Test
    fun `a rated hold corrected below 90 percent loses its rpe in the one SAVE`() = runBlocking {
        val writer = LastRowWriter()
        val tracker = SetRatingTracker(writer)
        tracker.onSetRecorded(null, 35, stoppedEarly = false, rating = SetRating(8, failed = false))
        tracker.attachTo(1L)
        val draft = CountAndRatingDraft(reps = null, seconds = 30, rpe = 8, tappedFailed = false, ratingChanged = false)
        assertEquals(derivedFailure, tracker.correct(draft, warmup = false))
        assertEquals(derivedFailure, writer.row)
    }

    // ---- what must not move ------------------------------------------------

    @Test
    fun `a set that met its target keeps the rating it ended with`() {
        val tracker = SetRatingTracker(LastRowWriter())
        assertEquals(
            CorrectedRatingRow(rpe = 8, failed = false, failedByLifter = false),
            tracker.onSetRecorded(5, null, stoppedEarly = false, rating = SetRating(8, failed = false)),
        )
    }

    @Test
    fun `a rest-screen rating on a set that did not fail is stored`() = runBlocking {
        val writer = LastRowWriter()
        val tracker = SetRatingTracker(writer)
        tracker.onSetRecorded(null, 35, stoppedEarly = false, rating = null)
        tracker.attachTo(1L)
        tracker.rate(8, failed = false, warmup = false)
        assertEquals(CorrectedRatingRow(rpe = 8, failed = false, failedByLifter = false), writer.row)
    }
}
