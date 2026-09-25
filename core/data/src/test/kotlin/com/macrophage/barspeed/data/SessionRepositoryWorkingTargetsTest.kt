package com.macrophage.barspeed.data

import com.macrophage.barspeed.dsp.SetAnalysis
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What [SessionRepository.recordSet] writes into the five v20 columns: the
 * working targets a set ran against, the plan's frozen tempo, and the instant
 * the rest after it runs from (#157).
 *
 * A file of its own rather than more methods in
 * [SessionRepositoryRecordSetTest], which sits a few lines under detekt's
 * `LargeClass` limit.
 *
 * ## The values are chosen so no two can be confused
 *
 * Every working figure differs from its planned sibling and from the actual
 * one on the same set, so a mapping that copied the wrong field lands a
 * number the assertion names: a raised rep target (8 against a plan of 6), a
 * shortened hold (30 against 45), a raised load (102.5 against 100.0), and a
 * plan tempo ("3010") that differs from the working one ("2010"). The rest
 * instant is neither the set's start nor its end, so reading either of those
 * in its place fails too.
 *
 * `loadKg` is 97.5 here, apart from the working 102.5, only so that a mapping
 * reading `loadKg` into `workingLoadKg` is caught. At a real write the two are
 * equal -- `completedSetOf` passes the same frozen figure to both -- and they
 * part only when a rest-screen correction later overwrites the row's
 * `loadKg`.
 *
 * ## Absence first
 *
 * The absence pin arrives with the columns and passes against a mapping that
 * writes nothing, so on its own it guards only the future: a mapping that
 * fills a missing working figure from its planned sibling, or writes 0 for
 * "none", reds it.
 *
 * Nothing here executes Room, SQLite or Android. `SessionDao` is an interface
 * and the fake below stands in for it; what is verified is the repository's
 * own mapping, and nothing about what the database did with it.
 */
class SessionRepositoryWorkingTargetsTest {
    private class FakeSessionDao : SessionDao {
        val sets = mutableListOf<SetRecordEntity>()

        override suspend fun insertSession(session: SessionEntity): Long = 1L

        override suspend fun updateSession(session: SessionEntity) = Unit

        override suspend fun insertSet(set: SetRecordEntity): Long {
            sets += set
            return 7L
        }

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

        override suspend fun updateVoided(setId: Long, voided: Boolean, reason: String?) = Unit

        override suspend fun updateLimiter(setId: Long, limiter: String?, limiterNote: String?) = Unit

        override suspend fun updateWarmupMark(setId: Long, warmupMark: Boolean?) = Unit

        override suspend fun overrideReps(setId: Long, reps: Int) = Unit

        override suspend fun overrideLoad(setId: Long, loadKg: Double) = Unit

        override suspend fun overrideDuration(setId: Long, seconds: Int, endedBy: String?) = Unit

        override suspend fun sessionsInRange(fromMs: Long, toMs: Long): List<SessionEntity> = emptyList()

        override suspend fun deleteSession(id: Long) = Unit
    }

    private class FakeExerciseDao : ExerciseDao {
        override suspend fun insert(exercise: CustomExerciseEntity) = Unit

        override fun observeAll(): Flow<List<CustomExerciseEntity>> = flowOf(emptyList())

        override suspend fun all(): List<CustomExerciseEntity> = emptyList()

        override suspend fun byId(id: String): CustomExerciseEntity? = null
    }

    private val noReps =
        SetAnalysis(
            reps = emptyList(),
            sampleRateHz = 99.4,
            velocityLossPct = null,
            tempoCompliance = null,
            verdicts = emptyList(),
        )

    private fun completedSet(
        workingReps: Int?,
        workingDurationS: Int?,
        plannedTempo: String?,
        restStartedAtMs: Long?,
    ) = CompletedSet(
        exerciseId = "back_squat",
        exerciseName = "Back Squat",
        loadKg = 97.5,
        plannedLoadKg = 100.0,
        workingLoadKg = 102.5,
        plannedReps = 6,
        workingReps = workingReps,
        manualReps = 7,
        plannedDurationS = 45,
        workingDurationS = workingDurationS,
        actualDurationS = 31,
        tempo = "2010",
        plannedTempo = plannedTempo,
        targetMeanConVelMps = null,
        velocityLossStopPct = null,
        plannedRestS = 120,
        restStartedAtMs = restStartedAtMs,
        plannedPrepS = null,
        prepS = null,
        startedAtMs = 1_000L,
        endedAtMs = 61_000L,
        analysis = noReps,
        imuSamples = emptyList(),
        hrSamples = emptyList(),
    )

    private suspend fun rowFor(set: CompletedSet): SetRecordEntity {
        val dao = FakeSessionDao()
        SessionRepository(dao, FakeExerciseDao()).recordSet(sessionId = 1L, orderIdx = 0, set = set)
        return dao.sets.single()
    }

    /**
     * Each of the five reaches its own column, and no sibling stands in for it.
     *
     * A raised rep target that was met must read as met against 8, not as an
     * over-performance against the plan's 6; a hold shortened to 30 must not
     * read as a failed 45. The row can say that only if the working figure is
     * on it.
     */
    @Test
    fun `the working targets, the plan's tempo and the rest instant reach the row`() = runTest {
        val row =
            rowFor(
                completedSet(
                    workingReps = 8,
                    workingDurationS = 30,
                    plannedTempo = "3010",
                    restStartedAtMs = 62_500L,
                ),
            )
        assertEquals(8, row.workingReps, "the working rep target was dropped at the write")
        assertEquals(30, row.workingDurationS, "the working hold was dropped at the write")
        assertEquals(102.5, row.workingLoadKg, "the working load was dropped at the write")
        assertEquals("3010", row.plannedTempo, "the plan's tempo was dropped at the write")
        assertEquals(62_500L, row.restStartedAtMs, "the rest-start instant was dropped at the write")
    }

    /**
     * Absence stays absence. A set with no rep target, no hold, no declared
     * tempo and no rest instant writes null in each column -- never a 0, and
     * never the planned sibling copied across.
     */
    @Test
    fun `absent working targets are written as null, not as a value`() = runTest {
        val row =
            rowFor(
                completedSet(
                    workingReps = null,
                    workingDurationS = null,
                    plannedTempo = null,
                    restStartedAtMs = null,
                ),
            )
        assertNull(row.workingReps, "a set with no rep target was given one")
        assertNull(row.workingDurationS, "a set that is not a hold was given a working hold")
        assertNull(row.plannedTempo, "a set whose plan declared no tempo was given one")
        assertNull(row.restStartedAtMs, "a set with no rest instant was given one")
    }
}
