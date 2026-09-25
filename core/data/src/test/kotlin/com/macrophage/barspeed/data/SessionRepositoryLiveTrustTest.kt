package com.macrophage.barspeed.data

import com.macrophage.barspeed.dsp.SetAnalysis
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Where [SessionRepository.recordSet] stores the live tracker's latch (#302):
 * inside `analysisJson`, as `SetAnalysis.liveCountTrusted`, and nowhere else.
 *
 * NO COLUMN, deliberately. The row already carries one stored analysis blob,
 * and a column for one boolean would mint a database version for a fact that
 * fits in it. What is pinned here is the round trip through that blob -- the
 * value handed in as [CompletedSet.liveCountTrusted] is the value
 * [SessionRepository.decodeAnalysis] reads back -- and that a set with no
 * latch to state is stored exactly as it was before the field existed, with
 * no key in the blob at all.
 *
 * Nothing here executes Room, SQLite or Android. The DAO is an interface and
 * this fake stands in for it, so what is verified is the repository's own
 * mapping and nothing about what the database did with it.
 */
class SessionRepositoryLiveTrustTest {
    private class RecordingDao : SessionDao {
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

    private class StubExerciseDao : ExerciseDao {
        override suspend fun insert(exercise: CustomExerciseEntity) = Unit

        override fun observeAll(): Flow<List<CustomExerciseEntity>> = flowOf(emptyList())

        override suspend fun all(): List<CustomExerciseEntity> = emptyList()

        override suspend fun byId(id: String): CustomExerciseEntity? = null
    }

    private val noReps = SetAnalysis(emptyList(), 99.4, null, null, emptyList())

    private fun completedSet(liveCountTrusted: Boolean?) = CompletedSet(
        exerciseId = "deadlift",
        exerciseName = "Deadlift",
        loadKg = 120.0,
        plannedLoadKg = 120.0,
        plannedReps = 5,
        liveReps = 5,
        liveCountTrusted = liveCountTrusted,
        tempo = null,
        targetMeanConVelMps = null,
        velocityLossStopPct = null,
        plannedRestS = 180,
        plannedPrepS = null,
        prepS = null,
        startedAtMs = 1_000L,
        endedAtMs = 61_000L,
        analysis = noReps,
        imuSamples = emptyList(),
        hrSamples = emptyList(),
    )

    private suspend fun stored(liveCountTrusted: Boolean?): Pair<SessionRepository, SetRecordEntity> {
        val dao = RecordingDao()
        val repository = SessionRepository(dao, StubExerciseDao())
        repository.recordSet(sessionId = 1L, orderIdx = 0, set = completedSet(liveCountTrusted))
        return repository to dao.sets.single()
    }

    private fun blobKey(row: SetRecordEntity) =
        Json.parseToJsonElement(row.analysisJson).jsonObject["liveCountTrusted"]?.jsonPrimitive?.content

    @Test
    fun `a latched false is stored in the analysis blob and read back as false`() = runTest {
        val (repository, row) = stored(liveCountTrusted = false)

        assertEquals("false", blobKey(row), "the blob does not carry the latch")
        assertEquals(false, repository.decodeAnalysis(row)?.liveCountTrusted)
    }

    @Test
    fun `a held zero is stored in the analysis blob and read back as true`() = runTest {
        val (repository, row) = stored(liveCountTrusted = true)

        assertEquals("true", blobKey(row), "the blob does not carry the latch")
        assertEquals(true, repository.decodeAnalysis(row)?.liveCountTrusted)
    }

    /**
     * No tracker covered the set: the blob carries NO key, rather than a
     * false or a true. Absence here is the state every row written before the
     * field existed is in, and a set with nothing to state must stay
     * indistinguishable from those rather than claim either answer.
     */
    @Test
    fun `no latch to state writes no key at all`() = runTest {
        val (repository, row) = stored(liveCountTrusted = null)

        assertNull(blobKey(row), "a set with no live tracker was given a latch")
        assertEquals(
            Json.encodeToString(SetAnalysis.serializer(), noReps),
            row.analysisJson,
            "a set with no latch is not stored byte for byte as it was before the field existed",
        )
        assertNull(repository.decodeAnalysis(row)?.liveCountTrusted)
    }
}
