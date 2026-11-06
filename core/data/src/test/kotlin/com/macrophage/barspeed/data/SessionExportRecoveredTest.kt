package com.macrophage.barspeed.data

import com.macrophage.barspeed.model.SessionExport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * What both export writers publish for a set written back into History from
 * an interrupted set's capture (#100, export 1.26, database 21).
 *
 * #100's rule: a recovered capture must never become an ordinary set
 * implicitly, and the export is where a coach reads it. The row's `recovered`
 * column is the fact; [SessionExporter] publishes it as the set's `recovered`
 * key and [RawExporter]'s `meta.json` as the same flag -- two writers, one
 * fact. Omitted when false, so an ordinary set's bytes do not move.
 *
 * Nothing here executes Room, SQLite or Android: the rows are handed to the
 * exporters through a fake DAO, so what is pinned is the writers' mapping.
 */
class SessionExportRecoveredTest {
    // ---- fakes -------------------------------------------------------------

    private class FakeSessionDao(
        private val session: SessionEntity,
        private val rows: List<SetRecordEntity>,
        private val streams: Map<Long, List<RawStreamEntity>>,
    ) : SessionDao {
        override suspend fun insertSession(session: SessionEntity): Long = 1L

        override suspend fun updateSession(session: SessionEntity) = Unit

        override suspend fun insertSet(set: SetRecordEntity): Long = 1L

        override suspend fun insertRawStream(stream: RawStreamEntity): Long = 1L

        override fun observeSessions(): Flow<List<SessionEntity>> = flowOf(listOf(session))

        override suspend fun sessionById(id: Long): SessionEntity? = session.takeIf { it.id == id }

        override fun observeSession(id: Long): Flow<SessionEntity?> = flowOf(session)

        override suspend fun setsForSession(sessionId: Long): List<SetRecordEntity> = rows

        override fun observeSetsForSession(sessionId: Long): Flow<List<SetRecordEntity>> = flowOf(rows)

        override suspend fun rawStreamsForSet(setId: Long): List<RawStreamEntity> = streams[setId].orEmpty()

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

    // ---- the rows ----------------------------------------------------------

    private val emptyAnalysis =
        """{"reps":[],"sampleRateHz":99.4,"velocityLossPct":null,"tempoCompliance":null,"verdicts":[]}"""

    private fun row(id: Long, orderIdx: Int, exerciseId: String, recovered: Boolean) = SetRecordEntity(
        id = id,
        sessionId = 1L,
        orderIdx = orderIdx,
        exerciseId = exerciseId,
        exerciseName = exerciseId,
        loadKg = 100.0,
        actualReps = 0,
        plannedReps = 5,
        startedAtMs = 1_000L + id * 100_000L,
        endedAtMs = 31_000L + id * 100_000L,
        analysisJson = emptyAnalysis,
        analysisSchemaVersion = "1.26",
        recovered = recovered,
    )

    /** Written back into History from an interrupted set's capture. */
    private val recovered = row(1L, 0, "back_squat", recovered = true)

    /** Written by its set ending, as every row before database v21 was. */
    private val ordinary = row(2L, 1, "bench_press", recovered = false)

    private fun repository(): SessionRepository {
        val session = SessionEntity(id = 1L, startedAtMs = 1_000L, endedAtMs = 400_000L)
        return SessionRepository(FakeSessionDao(session, listOf(recovered, ordinary), emptyMap()), FakeExerciseDao())
    }

    private fun SessionExport.set(exercise: String) = exercises.single { it.exercise == exercise }.sets.single()

    /** session.json's one set per exercise, keyed by the exercise it sits under. */
    private suspend fun encodedSets(): Map<String, JsonObject> {
        val text = assertNotNull(SessionExporter(repository()).exportJson(1L, includeRepDetail = false))
        return Json.parseToJsonElement(text).jsonObject.getValue("exercises").jsonArray
            .map { it.jsonObject }
            .associate {
                it.getValue("exercise").jsonPrimitive.content to it.getValue("sets").jsonArray.single().jsonObject
            }
    }

    private suspend fun manifestSets(): List<JsonObject> {
        val repository = repository()
        val bytes =
            assertNotNull(RawExporter(repository, SessionExporter(repository), appVersion = "0.2.0").buildZip(1L))
        ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                if (entry.name == "meta.json") {
                    val meta = Json.parseToJsonElement(zin.readBytes().decodeToString()).jsonObject
                    return meta.getValue("sets").jsonArray.map { it.jsonObject }
                }
            }
        }
        error("the archive carries no meta.json")
    }

    // ---- the pins ----------------------------------------------------------

    @Test
    fun `session json publishes a recovered set as recovered`() = runTest {
        val export = assertNotNull(SessionExporter(repository()).buildExport(1L, includeRepDetail = false))
        assertEquals(true, export.set("back_squat").recovered)
        assertEquals("true", encodedSets().getValue("back_squat")["recovered"]?.jsonPrimitive?.content)
    }

    @Test
    fun `meta json publishes a recovered set as recovered`() = runTest {
        val squat = manifestSets().single { it.getValue("exercise").jsonPrimitive.content == "back_squat" }
        assertEquals("true", squat["recovered"]?.jsonPrimitive?.content, "meta.json lost the recovered flag")
    }

    /** Omitted when false in both documents: an ordinary set's bytes do not move. */
    @Test
    fun `an ordinary set publishes no recovered key in either document`() = runTest {
        assertFalse("recovered" in encodedSets().getValue("bench_press"), "session.json flagged an ordinary set")
        val bench = manifestSets().single { it.getValue("exercise").jsonPrimitive.content == "bench_press" }
        assertFalse("recovered" in bench, "meta.json flagged an ordinary set")
    }
}
