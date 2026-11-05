package com.macrophage.barspeed.data

import com.macrophage.barspeed.dsp.SetAnalysis
import com.macrophage.barspeed.model.ExerciseKind
import com.macrophage.barspeed.model.GeometrySource
import com.macrophage.barspeed.model.GeometrySources
import com.macrophage.barspeed.model.ResolvedGeometry
import com.macrophage.barspeed.model.StartPhase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A SET WITH NO STROKE PUBLISHES NO STROKE WORDS (#297, export 1.26).
 *
 * Field-42's rope farmers holds (sets 14-16, v0.1.52) published
 * `geometry.startsWith: "eccentric"` with source `inferred` and
 * `geometry.concentric: "up"` with source `default`. A static hold has no
 * eccentric, no concentric and no stroke direction, so those were an absence
 * rendered as a value.
 *
 * DIFFERENTIALS. The hold and the carry tests FAIL at the commit that writes
 * this file: the exporter writes the stored words on every kind. The dynamic
 * test passes on both sides on purpose -- it is what stops the fix dropping
 * the words from a lift that has a stroke.
 *
 * The stored geometry is the same object in all three, the kind aside, and it
 * is the one the field holds published: an eccentric start, a drive up.
 *
 * Nothing here executes Room, SQLite or Android. `FakeSessionDao` implements the
 * `SessionDao` interface, so what is pinned is `SessionExporter`'s own mapping
 * and the question it asks of a stored row -- never what the database did.
 */
class SessionExportHoldStrokeTest {
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

    private val stroke = listOf("startsWith", "concentric")

    private fun stored(kind: ExerciseKind) = ResolvedGeometry(
        startsWith = StartPhase.ECCENTRIC,
        concentricUp = true,
        horizontal = false,
        sensorOnStack = false,
        sensorInverted = false,
        travelRatio = 1.0,
        kind = kind,
        bodyweight = false,
        sources =
        GeometrySources(
            startsWith = GeometrySource.INFERRED,
            concentric = GeometrySource.DEFAULT,
            plane = GeometrySource.DEFAULT,
            kind = GeometrySource.DECLARED,
            travelRatio = GeometrySource.DEFAULT,
            sensorOnStack = GeometrySource.DEFAULT,
            bodyweight = GeometrySource.DEFAULT,
            sensorInverted = GeometrySource.DEFAULT,
        ),
    )

    private val held =
        SetAnalysis(
            reps = emptyList(),
            sampleRateHz = 99.4,
            velocityLossPct = null,
            tempoCompliance = null,
            verdicts = emptyList(),
        )

    private suspend fun geometryOf(kind: ExerciseKind): JsonObject {
        val timed = kind == ExerciseKind.HOLD || kind == ExerciseKind.CARRY
        val row = SetRecordEntity(
            id = 5L,
            sessionId = 1L,
            orderIdx = 0,
            exerciseId = "rope_farmers_hold",
            exerciseName = "Rope Farmers Hold",
            loadKg = 40.0,
            actualReps = if (timed) 0 else 8,
            repsManual = false,
            actualDurationS = if (timed) 30 else null,
            startedAtMs = 1_000L,
            endedAtMs = 31_000L,
            analysisJson = json.encodeToString(SetAnalysis.serializer(), held),
            geometryJson = json.encodeToString(ResolvedGeometry.serializer(), stored(kind)),
        )
        val dao = FakeSessionDao(
            session = SessionEntity(id = 1L, startedAtMs = 1_000L, endedAtMs = 31_000L),
            rows = listOf(row),
        )
        val text = SessionExporter(SessionRepository(dao, FakeExerciseDao())).exportJson(1L, includeRepDetail = true)!!
        return Json.parseToJsonElement(text).jsonObject.getValue("exercises").jsonArray[0].jsonObject
            .getValue("sets").jsonArray[0].jsonObject.getValue("geometry").jsonObject
    }

    private fun assertNoStroke(g: JsonObject) {
        assertTrue(stroke.none { it in g }, "a set with no stroke publishes a stroke word: $g")
        val source = g.getValue("source").jsonObject
        assertTrue(stroke.none { it in source }, "a set with no stroke publishes a stroke word's source: $source")
        assertEquals(6, source.size, "a source other than the two stroke words went missing: $source")
    }

    @Test
    fun `a hold publishes no stroke words and no source for them`() = runTest {
        val g = geometryOf(ExerciseKind.HOLD)
        assertEquals("hold", g.getValue("kind").jsonPrimitive.content)
        assertNoStroke(g)
    }

    @Test
    fun `a carry publishes no stroke words and no source for them`() = runTest {
        val g = geometryOf(ExerciseKind.CARRY)
        assertEquals("carry", g.getValue("kind").jsonPrimitive.content)
        assertNoStroke(g)
    }

    /** Green on both sides of the fix: a lift with a stroke keeps both words and both sources. */
    @Test
    fun `a dynamic lift still publishes both stroke words and their sources`() = runTest {
        val g = geometryOf(ExerciseKind.DYNAMIC)
        assertEquals("eccentric", g.getValue("startsWith").jsonPrimitive.content)
        assertEquals("up", g.getValue("concentric").jsonPrimitive.content)
        val source = g.getValue("source").jsonObject
        assertEquals("inferred", source.getValue("startsWith").jsonPrimitive.content)
        assertEquals("default", source.getValue("concentric").jsonPrimitive.content)
    }
}
