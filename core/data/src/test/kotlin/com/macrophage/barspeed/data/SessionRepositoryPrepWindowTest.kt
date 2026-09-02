package com.macrophage.barspeed.data

import com.macrophage.barspeed.dsp.SetAnalysis
import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.PrepWindow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Whether the prep window a set stated reaches the durable record (#185).
 *
 * Differentials. The window exists as a type and as a field on [CompletedSet]
 * from the two commits before this one, and the repository ignores it, so a set
 * that knows exactly where its prep was stores nothing about it and the archive
 * is no better off than before.
 *
 * The window is written as a `raw_streams` row rather than a column, which is
 * what kept this change from costing a `DATABASE_VERSION` bump at all. That
 * makes the count of rows a set writes part of what has to be pinned: every
 * assertion elsewhere in this package reaches a stream through a `firstOrNull`,
 * and a second row of a kind is invisible to all of them.
 *
 * Nothing here executes Room, SQLite or Android. The DAO is an interface and
 * the fake below stands in for it; what is verified is the repository's own
 * mapping and nothing about what the database did with it.
 */
class SessionRepositoryPrepWindowTest {
    private class RecordingDao : SessionDao {
        val streams = mutableListOf<RawStreamEntity>()

        override suspend fun insertSession(session: SessionEntity): Long = 1L

        override suspend fun updateSession(session: SessionEntity) = Unit

        override suspend fun insertSet(set: SetRecordEntity): Long = 7L

        override suspend fun insertRawStream(stream: RawStreamEntity): Long {
            streams += stream
            return streams.size.toLong()
        }

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

        // Conformance only: SessionDao grew this member for #205 and Kotlin
        // requires it. Nothing in this file calls it.
        override suspend fun overrideLoad(setId: Long, loadKg: Double) = Unit

        override suspend fun overrideDuration(setId: Long, seconds: Int) = Unit

        override suspend fun sessionsInRange(fromMs: Long, toMs: Long): List<SessionEntity> = emptyList()

        override suspend fun deleteSession(id: Long) = Unit
    }

    private class StubExerciseDao : ExerciseDao {
        override suspend fun insert(exercise: CustomExerciseEntity) = Unit

        override fun observeAll(): Flow<List<CustomExerciseEntity>> = flowOf(emptyList())

        override suspend fun all(): List<CustomExerciseEntity> = emptyList()

        override suspend fun byId(id: String): CustomExerciseEntity? = null
    }

    private val window = PrepWindow(startedAtMs = 1_000L, workStartedAtMs = 6_000L)

    private fun completedSet(prepWindow: PrepWindow?) = CompletedSet(
        exerciseId = "back_squat",
        exerciseName = "Back Squat",
        loadKg = 100.0,
        plannedReps = 5,
        tempo = "3010",
        plannedLoadKg = null,
        targetMeanConVelMps = null,
        velocityLossStopPct = null,
        plannedRestS = null,
        prepS = 5,
        plannedPrepS = 5,
        startedAtMs = 1_000L,
        endedAtMs = 46_000L,
        analysis = SetAnalysis(emptyList(), 0.0, null, null, emptyList()),
        imuSamples = listOf(ImuSample(1_000L, 0.01, -0.02, 0.98, 1.5, -2.5, 0.25, 10.0, -20.0, 30.0)),
        hrSamples = emptyList(),
        prepWindow = prepWindow,
    )

    private suspend fun record(prepWindow: PrepWindow?): RecordingDao {
        val dao = RecordingDao()
        SessionRepository(dao, StubExerciseDao())
            .recordSet(sessionId = 1L, orderIdx = 0, set = completedSet(prepWindow))
        return dao
    }

    /**
     * A set that stated a window writes exactly one prep row, carrying it.
     *
     * The instants are read back through the codec rather than compared as
     * bytes, so what is asserted is what a reader gets rather than what this
     * test happened to encode.
     */
    @Test
    fun `a set that stated a prep window writes one prep stream carrying it`() = runTest {
        val rows = record(window).streams.filter { it.kind == RawStreamEntity.KIND_PREP }
        assertEquals(1, rows.size, "the prep window did not reach the durable record as exactly one row")
        assertEquals(window, PrepWindowCsv.decode(Gzip.decompress(rows.single().csvGzip)))
    }

    /**
     * A set that stated no window writes no row at all.
     *
     * Every set recorded before this change is such a set, and so is one ended
     * during its prep. An empty prep file would be a bracket a reader would
     * then look for a stationary period inside.
     */
    @Test
    fun `a set that stated no prep window writes no prep stream`() = runTest {
        assertEquals(
            emptyList(),
            record(prepWindow = null).streams.filter { it.kind == RawStreamEntity.KIND_PREP },
            "a set with no prep window still wrote one",
        )
    }

    /**
     * The prep row carries no sample rate.
     *
     * `sampleRateHz` is measured over the stream the DSP analysed. This row is
     * one interval and has no rate to state; a figure here would label the
     * window with another stream's cadence, and the package's own selector for
     * "rows that should carry no rate" is a filter over every non-IMU kind.
     */
    @Test
    fun `the prep row carries no sample rate and no role`() = runTest {
        val row = record(window).streams.single { it.kind == RawStreamEntity.KIND_PREP }
        assertNull(row.sampleRateHz, "a rate reached a row it was not measured from")
        assertNull(row.role, "a sensor role reached a row that came from no sensor")
    }
}
