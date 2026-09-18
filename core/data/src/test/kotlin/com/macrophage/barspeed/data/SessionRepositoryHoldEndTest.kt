package com.macrophage.barspeed.data

import com.macrophage.barspeed.model.HoldEndSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What a duration correction writes beside the seconds. Issues #259 and #249,
 * and RED at the commit that adds this file.
 *
 * #249 measured the silence on field-38's two rope dead hangs: 31 s and 22 s
 * published against 36.228 s and 32.188 s of measured clock, both gaps exact
 * multiples of #168's 5 s correction step, and no key on the row or in the
 * export saying a correction had happened at all. A reader could not tell a
 * good correction from a double tap from a defect in the hold clock -- and once
 * a SENSOR can also shorten a hold, cannot tell any of those from a
 * measurement.
 *
 * ONE CALL, NOT TWO, which is why the pin is on the repository rather than on a
 * second DAO method: two statements are two chances for a row to hold a figure
 * and a word that disagree, and the window between them is the one in which the
 * app is killed.
 *
 * Its own file rather than a case on `SessionRepositoryRecordSetTest`, which
 * sits five lines under detekt's `LargeClass` limit -- measured, after the first
 * attempt put it there and reddened `:core:data:detekt` instead of the suite.
 *
 * Nothing here executes Room, SQLite or Android. `SessionDao` is an interface
 * and the fake below stands in for it, so what is verified is the repository's
 * own mapping and call shape -- never what the database did with it.
 */
class SessionRepositoryHoldEndTest {
    private class RecordingDao : SessionDao {
        /** Every duration restatement, in the order it is handed over. */
        val durationWrites = mutableListOf<Triple<Long, Int, String?>>()

        override suspend fun overrideDuration(setId: Long, seconds: Int, endedBy: String?) {
            durationWrites += Triple(setId, seconds, endedBy)
        }

        override suspend fun updateVoided(setId: Long, voided: Boolean, reason: String?) = Unit

        override suspend fun insertSession(session: SessionEntity): Long = 1L

        override suspend fun updateSession(session: SessionEntity) = Unit

        override suspend fun insertSet(set: SetRecordEntity): Long = 1L

        override suspend fun insertRawStream(stream: RawStreamEntity): Long = 1L

        override fun observeSessions(): Flow<List<SessionEntity>> = flowOf(emptyList())

        override suspend fun sessionById(id: Long): SessionEntity? = null

        override fun observeSession(id: Long): Flow<SessionEntity?> = flowOf(null)

        override suspend fun setsForSession(sessionId: Long): List<SetRecordEntity> = emptyList()

        override fun observeSetsForSession(sessionId: Long): Flow<List<SetRecordEntity>> = flowOf(emptyList())

        override suspend fun rawStreamsForSet(setId: Long): List<RawStreamEntity> = emptyList()

        override suspend fun updateRpe(
            setId: Long,
            rpe: Int?,
            failed: Boolean,
            failedByLifter: Boolean?,
            warmup: Boolean,
        ) = Unit

        override suspend fun updateLimiter(setId: Long, limiter: String?, limiterNote: String?) = Unit

        override suspend fun updateWarmupMark(setId: Long, warmupMark: Boolean?) = Unit

        override suspend fun overrideReps(setId: Long, reps: Int) = Unit

        override suspend fun overrideLoad(setId: Long, loadKg: Double) = Unit

        override suspend fun sessionsInRange(fromMs: Long, toMs: Long): List<SessionEntity> = emptyList()

        override suspend fun deleteSession(id: Long) = Unit
    }

    private class StubExerciseDao : ExerciseDao {
        override suspend fun insert(exercise: CustomExerciseEntity) = Unit

        override fun observeAll(): Flow<List<CustomExerciseEntity>> = flowOf(emptyList())

        override suspend fun all(): List<CustomExerciseEntity> = emptyList()

        override suspend fun byId(id: String): CustomExerciseEntity? = null
    }

    private fun repo(dao: SessionDao) = SessionRepository(dao, StubExerciseDao())

    @Test
    fun `a duration correction tells the row the lifter restated the figure`() = runTest {
        val dao = RecordingDao()
        repo(dao).overrideDuration(setId = 31L, seconds = 47)
        val expected: List<Triple<Long, Int, String?>> =
            listOf(Triple(31L, 47, HoldEndSource.CORRECTED.published))
        assertEquals(
            expected,
            dao.durationWrites,
            "the seconds reach the dao with no word saying who restated them",
        )
        assertEquals("corrected", dao.durationWrites.single().third, "the word the export publishes")
    }

    @Test
    fun `the word is the repository's and not the caller's`() = runTest {
        // Two corrections in a row, both the lifter's: `:app` has one duration
        // control and every tap of it is a restatement, so there is no call site
        // that could ask for a different word -- and none that could forget to
        // ask for one.
        val dao = RecordingDao()
        repo(dao).overrideDuration(setId = 31L, seconds = 47)
        repo(dao).overrideDuration(setId = 31L, seconds = 42)
        assertEquals(
            listOf("corrected", "corrected"),
            dao.durationWrites.map { it.third },
            "a second correction says something different from the first",
        )
    }
}
