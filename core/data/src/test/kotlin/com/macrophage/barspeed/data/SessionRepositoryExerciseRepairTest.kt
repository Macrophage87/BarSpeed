package com.macrophage.barspeed.data

import com.macrophage.barspeed.model.StartPhase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `SessionRepository.ensureExerciseExists` against a row it stored earlier
 * (#59).
 *
 * A FILE OF ITS OWN with its own fakes, for the reason `AppendedSetRecordTest`
 * gives: `SessionRepositoryRecordSetTest`, where the other
 * `ensureExerciseExists` pins live, is over detekt's LargeClass threshold
 * with these two in it, and CI runs detekt before any test.
 *
 * Nothing here executes Room, SQLite or Android. The DAOs are interfaces and
 * the fakes stand in for them; this fake's insert overwrites by id, which is
 * the REPLACE conflict strategy the real DAO is given with the fix.
 */
class SessionRepositoryExerciseRepairTest {
    /**
     * #59. A row stored before the app's inference changed keeps the old
     * answer for ever: the row is written once, and until this nothing wrote
     * it again. `tricep_pushdown` was stored ECCENTRIC on every install that
     * saw it before "pushdown" joined the hints, so a plan omitting `start`
     * on it kept pairing every rep's phases backwards -- the drive counted as
     * the lowering -- however many releases later. The repair is a rewrite of
     * the stored row with what the app infers today, and it is safe because
     * every stored value is an inference: no screen lets a lifter write one.
     */
    @Test
    fun `a stored row that no longer says what the app infers is rewritten`() = runTest {
        val stale = CustomExerciseEntity(
            id = "tricep_pushdown",
            displayName = "Tricep pushdown",
            startsWith = StartPhase.ECCENTRIC.name,
        )
        val exercises = FakeExerciseDao(mutableMapOf(stale.id to stale))
        SessionRepository(FakeSessionDao(), exercises).ensureExerciseExists("tricep_pushdown")
        val row = exercises.inserted.single()
        assertEquals("tricep_pushdown", row.id)
        assertEquals(StartPhase.CONCENTRIC.name, row.startsWith)
        assertEquals(StartPhase.CONCENTRIC.name, exercises.byId("tricep_pushdown")!!.startsWith)
    }

    /** The neighbour: a stored row that already agrees is left alone, not rewritten every set. */
    @Test
    fun `a stored row that still says what the app infers is not written again`() = runTest {
        val current = CustomExerciseEntity(
            id = "tricep_pushdown",
            displayName = "Tricep pushdown",
            startsWith = StartPhase.CONCENTRIC.name,
        )
        val exercises = FakeExerciseDao(mutableMapOf(current.id to current))
        SessionRepository(FakeSessionDao(), exercises).ensureExerciseExists("tricep_pushdown")
        assertEquals(emptyList(), exercises.inserted)
    }

    private class FakeSessionDao(
        private val session: SessionEntity = SessionEntity(id = 1L, startedAtMs = 1_000L, endedAtMs = 61_000L),
        private val rows: List<SetRecordEntity> = emptyList(),
    ) : SessionDao {
        val inserted = mutableListOf<SetRecordEntity>()

        override suspend fun insertSession(session: SessionEntity): Long = 1L

        override suspend fun updateSession(session: SessionEntity) = Unit

        override suspend fun insertSet(set: SetRecordEntity): Long {
            inserted += set
            return 7L
        }

        override suspend fun insertRawStream(stream: RawStreamEntity): Long = 1L

        override fun observeSessions(): Flow<List<SessionEntity>> = flowOf(listOf(session))

        override suspend fun sessionById(id: Long): SessionEntity? = session.takeIf { it.id == id }

        override fun observeSession(id: Long): Flow<SessionEntity?> = flowOf(session)

        override suspend fun setsForSession(sessionId: Long): List<SetRecordEntity> =
            rows.filter { it.sessionId == sessionId }

        override fun observeSetsForSession(sessionId: Long): Flow<List<SetRecordEntity>> = flowOf(rows)

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

        override suspend fun updateVoided(setId: Long, voided: Boolean, reason: String?) = Unit

        override suspend fun overrideReps(setId: Long, reps: Int) = Unit

        override suspend fun overrideLoad(setId: Long, loadKg: Double) = Unit

        override suspend fun overrideDuration(setId: Long, seconds: Int, endedBy: String?) = Unit

        override suspend fun sessionsInRange(fromMs: Long, toMs: Long): List<SessionEntity> = emptyList()

        override suspend fun deleteSession(id: Long) = Unit
    }

    private class FakeExerciseDao(private val known: MutableMap<String, CustomExerciseEntity>) : ExerciseDao {
        val inserted = mutableListOf<CustomExerciseEntity>()

        override suspend fun insert(exercise: CustomExerciseEntity) {
            inserted += exercise
            known[exercise.id] = exercise
        }

        override fun observeAll(): Flow<List<CustomExerciseEntity>> = flowOf(known.values.toList())

        override suspend fun all(): List<CustomExerciseEntity> = known.values.toList()

        override suspend fun byId(id: String): CustomExerciseEntity? = known[id]
    }
}
