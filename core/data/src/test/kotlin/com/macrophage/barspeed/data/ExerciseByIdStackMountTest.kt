package com.macrophage.barspeed.data

import com.macrophage.barspeed.model.ExerciseDef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The mount an ad-hoc set carries when no plan ever declared one (#223).
 *
 * `SessionRepository.exerciseById` serves the PLAN path only -- its one
 * caller is `PlanQueue.kt`'s `flattenPlan`. A set the lifter starts from the
 * picker with no plan at all does not reach this method: it resolves through
 * `RecordState.currentExercise`, which answers from [ExerciseDef.seedById]
 * -- the picker offers seeded ids only -- and no SEED entry sets
 * `sensorOnStack`, so it takes the plain `false` default and IS recorded
 * bar-mounted today. A plan that declares the key still overrides it, in
 * `SetGeometryPolicy.resolve`.
 *
 * A FILE OF ITS OWN with its own fakes, for the reason `AppendedSetRecordTest`
 * gives: the classes these assertions belong beside sit on detekt's LargeClass
 * threshold, and CI runs detekt before any test.
 *
 * Nothing here executes Room, SQLite or Android. The DAOs are interfaces and
 * the fakes stand in for them.
 */
class ExerciseByIdStackMountTest {
    @Test
    fun `an id the app seeds a mount for is stack-mounted with no plan at all`() = runTest {
        val repo = SessionRepository(FakeSessionDao(), FakeExerciseDao())
        assertEquals(true, repo.exerciseById("assisted_pull_up").sensorOnStack)
        assertEquals(true, repo.exerciseById("seated_leg_curl").sensorOnStack)
    }

    @Test
    fun `an id nothing seeds a mount for is not stack-mounted`() = runTest {
        val repo = SessionRepository(FakeSessionDao(), FakeExerciseDao())
        assertEquals(false, repo.exerciseById("back_squat").sensorOnStack)
        assertEquals(false, repo.exerciseById("leg_press").sensorOnStack)
    }

    /** The custom-exercise branch answers the same way as the unknown-id one. */
    @Test
    fun `a stored custom exercise on one of those ids is stack-mounted too`() = runTest {
        val dao = FakeExerciseDao(
            stored = CustomExerciseEntity(
                id = "lat_pulldown",
                displayName = "Lat pulldown",
                startsWith = "CONCENTRIC",
            ),
        )
        assertEquals(true, SessionRepository(FakeSessionDao(), dao).exerciseById("lat_pulldown").sensorOnStack)
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

        override suspend fun updateRpe(setId: Long, rpe: Int?, failed: Boolean, warmup: Boolean) = Unit

        override suspend fun updateLimiter(setId: Long, limiter: String?, limiterNote: String?) = Unit

        override suspend fun updateWarmupMark(setId: Long, warmupMark: Boolean?) = Unit

        override suspend fun overrideReps(setId: Long, reps: Int) = Unit

        override suspend fun overrideLoad(setId: Long, loadKg: Double) = Unit

        override suspend fun overrideDuration(setId: Long, seconds: Int) = Unit

        override suspend fun sessionsInRange(fromMs: Long, toMs: Long): List<SessionEntity> = emptyList()

        override suspend fun deleteSession(id: Long) = Unit
    }

    private class FakeExerciseDao(private val stored: CustomExerciseEntity? = null) : ExerciseDao {
        override suspend fun insert(exercise: CustomExerciseEntity) = Unit

        override fun observeAll(): Flow<List<CustomExerciseEntity>> = flowOf(emptyList())

        override suspend fun all(): List<CustomExerciseEntity> = emptyList()

        override suspend fun byId(id: String): CustomExerciseEntity? = stored?.takeIf { it.id == id }
    }
}
