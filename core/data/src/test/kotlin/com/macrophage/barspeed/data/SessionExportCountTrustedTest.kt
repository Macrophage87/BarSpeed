package com.macrophage.barspeed.data

import com.macrophage.barspeed.dsp.SetAnalysis
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * WHAT THE EXPORT PUBLISHES ABOUT A SET'S LIVE INTEGRATOR, issue #302.
 *
 * DIFFERENTIALS. The two tests that expect the key FAIL at the commit that
 * writes this file: the row's stored analysis carries the latch
 * (`SetAnalysis.liveCountTrusted`, landed with its repository pin one commit
 * earlier), and the exporter publishes nothing from it. The absence test
 * passes on both sides on purpose -- it is what stops the fix from inventing
 * an answer for a set that has none.
 *
 * Asserted against the exported TEXT, on the terms
 * `SessionExportArtefactSampleTest` states: a typed assertion against a
 * `SetExport` field the exporter has not been given would fail to compile
 * rather than fail, and what is asserted is what a coach opening the artifact
 * sees.
 *
 * Nothing here executes Room, SQLite or Android. `FakeSessionDao` implements the
 * `SessionDao` interface, so what is pinned is `SessionExporter`'s own mapping
 * and the question it asks of a stored row -- never what the database did.
 */
class SessionExportCountTrustedTest {
    private class FakeSessionDao(
        private val session: SessionEntity,
        private val rows: List<SetRecordEntity>,
    ) : SessionDao {
        override suspend fun insertSession(session: SessionEntity): Long = 1L

        override suspend fun updateSession(session: SessionEntity) = Unit

        override suspend fun insertSet(set: SetRecordEntity): Long = 1L

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

    private class FakeExerciseDao : ExerciseDao {
        override suspend fun insert(exercise: CustomExerciseEntity) = Unit

        override fun observeAll(): Flow<List<CustomExerciseEntity>> = flowOf(emptyList())

        override suspend fun all(): List<CustomExerciseEntity> = emptyList()

        override suspend fun byId(id: String): CustomExerciseEntity? = null
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** A sensor-counted deadlift set, as the repository stores one with the latch in its blob. */
    private fun exporter(liveCountTrusted: Boolean?): SessionExporter {
        val analysis = SetAnalysis(
            reps = emptyList(),
            sampleRateHz = 99.4,
            velocityLossPct = null,
            tempoCompliance = null,
            verdicts = emptyList(),
            liveCountTrusted = liveCountTrusted,
        )
        val row = SetRecordEntity(
            id = 5L,
            sessionId = 1L,
            orderIdx = 0,
            exerciseId = "deadlift",
            exerciseName = "Deadlift",
            loadKg = 61.234969951354785,
            actualReps = 5,
            repsManual = false,
            liveReps = 5,
            plannedReps = 5,
            startedAtMs = 1_000L,
            endedAtMs = 31_000L,
            analysisJson = json.encodeToString(SetAnalysis.serializer(), analysis),
        )
        val dao = FakeSessionDao(
            session = SessionEntity(id = 1L, startedAtMs = 1_000L, endedAtMs = 31_000L),
            rows = listOf(row),
        )
        return SessionExporter(SessionRepository(dao, FakeExerciseDao()))
    }

    private suspend fun summary(liveCountTrusted: Boolean?): String =
        exporter(liveCountTrusted).exportJson(1L, includeRepDetail = false)!!

    private suspend fun detailed(liveCountTrusted: Boolean?): String =
        exporter(liveCountTrusted).exportJson(1L, includeRepDetail = true)!!

    /**
     * THE DIFFERENTIAL, on the summary artifact. A latched false is published,
     * because it qualifies figures -- velocityLoss_pct, summary -- that the
     * summary artifact publishes too.
     */
    @Test
    fun `a set whose live integrator lost its zero publishes false`() = runTest {
        val t = summary(liveCountTrusted = false)
        assertTrue("\"countTrusted\": false" in t, "expected the latch in the wire form, got: $t")
    }

    /** True is published too: it is an answer, and a reader must be able to tell it from no answer. */
    @Test
    fun `a set whose live integrator held its zero publishes true`() = runTest {
        val t = summary(liveCountTrusted = true)
        assertTrue("\"countTrusted\": true" in t, "expected the latch in the wire form, got: $t")
    }

    /** The detailed artifact carries it as well; asking for per-rep detail must not drop a set-level caveat. */
    @Test
    fun `the detailed artifact carries the flag as well`() = runTest {
        val t = detailed(liveCountTrusted = false)
        assertTrue("\"countTrusted\": false" in t, "expected the latch on the detailed artifact, got: $t")
    }

    /**
     * No live tracker covered the set, or the set was recorded before the key:
     * NO KEY, never a defaulted false or true. Green on both sides of the fix.
     */
    @Test
    fun `a set with no latch publishes no key at all`() = runTest {
        val t = summary(liveCountTrusted = null)
        assertTrue("countTrusted" !in t, "a set with no live tracker was given an answer, got: $t")
    }
}
