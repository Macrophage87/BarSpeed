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
import kotlin.test.assertNull

/**
 * Export 1.26's `analysisSchemaVersion` (#38): each set publishes the
 * contract the build that RECORDED it analysed it under, read from database
 * v21's column, in session.json and in the raw archive's `meta.json` alike.
 *
 * Two sets in one session, which is the case the key exists for: one written
 * by a database-v21 build (`"1.26"` stored beside its analysis) and one an
 * earlier build wrote, whose column is null because nothing recorded it. The
 * older one must publish NO key -- not the exporting build's version, which
 * is #38's defect, and not a guess -- and neither writer may synthesise a
 * session-level figure.
 *
 * Nothing here executes Room, SQLite or Android: what is pinned is the two
 * writers' handling of the rows `FakeSessionDao` hands them.
 */
class SessionExportAnalysisProvenanceTest {
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

    private fun row(id: Long, orderIdx: Int, exerciseId: String, provenance: String?) = SetRecordEntity(
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
        analysisSchemaVersion = provenance,
    )

    /** Written by a database-v21 build: the column names the contract it analysed under. */
    private val recorded = row(1L, 0, "back_squat", "1.26")

    /** Written before database v21: the column is null on every such row, permanently. */
    private val older = row(2L, 1, "bench_press", null)

    private fun repository(): SessionRepository {
        val session = SessionEntity(id = 1L, startedAtMs = 1_000L, endedAtMs = 400_000L)
        return SessionRepository(FakeSessionDao(session, listOf(recorded, older), emptyMap()), FakeExerciseDao())
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

    /** The set nobody recorded a contract for publishes none, not the exporting build's. */
    @Test
    fun `a set recorded before database 21 publishes no provenance`() = runTest {
        val export = assertNotNull(SessionExporter(repository()).buildExport(1L, includeRepDetail = false))
        assertNull(export.set("bench_press").analysisSchemaVersion)
        assertEquals(SessionExport.SCHEMA_VERSION, export.schemaVersion, "the document's own version moved")
        assertFalse(
            "analysisSchemaVersion" in encodedSets().getValue("bench_press"),
            "session.json gave an unrecorded set a version",
        )
    }

    /** The same rule in the manifest: two writers, one fact. */
    @Test
    fun `meta json gives a set recorded before database 21 no provenance`() = runTest {
        val bench = manifestSets().single { it.getValue("exercise").jsonPrimitive.content == "bench_press" }
        assertFalse("analysisSchemaVersion" in bench, "meta.json gave an unrecorded set a version")
    }

    /** The stored contract reaches session.json, per set, beside the document's own version. */
    @Test
    fun `a set recorded under database 21 publishes the contract it was analysed under`() = runTest {
        val export = assertNotNull(SessionExporter(repository()).buildExport(1L, includeRepDetail = false))
        assertEquals("1.26", export.set("back_squat").analysisSchemaVersion)
        assertEquals(
            "1.26",
            encodedSets().getValue("back_squat")["analysisSchemaVersion"]?.jsonPrimitive?.content,
            "session.json dropped the stored version",
        )
    }

    /** The manifest carries the same stored value: two writers, one fact. */
    @Test
    fun `meta json carries the contract a set recorded under database 21 was analysed under`() = runTest {
        val squat = manifestSets().single { it.getValue("exercise").jsonPrimitive.content == "back_squat" }
        assertEquals("1.26", squat["analysisSchemaVersion"]?.jsonPrimitive?.content, "meta.json dropped it")
    }
}
