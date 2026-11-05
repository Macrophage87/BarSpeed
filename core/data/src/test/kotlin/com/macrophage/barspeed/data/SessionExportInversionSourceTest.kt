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
import kotlin.test.assertFalse

/**
 * WHERE A SET'S `sensorInverted` CAME FROM, AS THE EXPORT PUBLISHES IT
 * (#289, export 1.26).
 *
 * DIFFERENTIALS. The two tests that expect `geometry.source.sensorInverted`
 * FAIL at the commit that writes this file: the stored geometry can carry the
 * source (`GeometrySources.sensorInverted`, added two commits earlier) and the
 * exporter publishes nothing from it. The absence test passes on both sides on
 * purpose -- it is what stops the fix inventing an answer for a set recorded
 * before any build stored one.
 *
 * Read off the exported document, parsed, so what is asserted is what a coach
 * opening the artifact sees, and the stored row is written in the form Room
 * holds it -- the repository's own `Json`, encoded from [ResolvedGeometry],
 * or, for the older row, hand-written.
 *
 * Nothing here executes Room, SQLite or Android. `FakeSessionDao` implements the
 * `SessionDao` interface, so what is pinned is `SessionExporter`'s own mapping
 * and the question it asks of a stored row -- never what the database did.
 */
class SessionExportInversionSourceTest {
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

    /** Field-41's pushdown shape: on the stack, driven down, vertical; the inversion is the rule's. */
    private fun pushdown(source: GeometrySource?) = ResolvedGeometry(
        startsWith = StartPhase.CONCENTRIC,
        concentricUp = false,
        horizontal = false,
        sensorOnStack = true,
        sensorInverted = true,
        travelRatio = 1.0,
        kind = ExerciseKind.DYNAMIC,
        bodyweight = false,
        sources =
        GeometrySources(
            startsWith = GeometrySource.DECLARED,
            concentric = GeometrySource.DECLARED,
            plane = GeometrySource.DEFAULT,
            kind = GeometrySource.INFERRED,
            travelRatio = GeometrySource.DEFAULT,
            sensorOnStack = GeometrySource.SEEDED,
            bodyweight = GeometrySource.DEFAULT,
            sensorInverted = source,
        ),
    )

    /** The same set as a v0.1.59 build stored it: a seven-key sources object, no inversion source. */
    private val storedBefore126 =
        """
        {"startsWith":"CONCENTRIC","concentricUp":false,"horizontal":false,"sensorOnStack":true,
         "sensorInverted":true,"travelRatio":1.0,"kind":"DYNAMIC","bodyweight":false,
         "sources":{"startsWith":"DECLARED","concentric":"DECLARED","plane":"DEFAULT","kind":"INFERRED",
                    "travelRatio":"DEFAULT","sensorOnStack":"SEEDED","bodyweight":"DEFAULT"}}
        """.trimIndent()

    private suspend fun geometryOf(geometryJson: String): JsonObject {
        val row = SetRecordEntity(
            id = 5L,
            sessionId = 1L,
            orderIdx = 0,
            exerciseId = "triceps_pushdown",
            exerciseName = "Triceps Pushdown",
            loadKg = 25.0,
            actualReps = 12,
            repsManual = false,
            plannedReps = 12,
            startedAtMs = 1_000L,
            endedAtMs = 31_000L,
            analysisJson = json.encodeToString(SetAnalysis.serializer(), noReps),
            geometryJson = geometryJson,
        )
        val dao = FakeSessionDao(
            session = SessionEntity(id = 1L, startedAtMs = 1_000L, endedAtMs = 31_000L),
            rows = listOf(row),
        )
        val text = SessionExporter(SessionRepository(dao, FakeExerciseDao())).exportJson(1L, includeRepDetail = false)!!
        return Json.parseToJsonElement(text).jsonObject.getValue("exercises").jsonArray[0].jsonObject
            .getValue("sets").jsonArray[0].jsonObject.getValue("geometry").jsonObject
    }

    /** The analysis is beside the point here; a set the segmenter resolved nothing in keeps the fixture small. */
    private val noReps =
        SetAnalysis(
            reps = emptyList(),
            sampleRateHz = 99.4,
            velocityLossPct = null,
            tempoCompliance = null,
            verdicts = emptyList(),
        )

    private fun stored(g: ResolvedGeometry) = json.encodeToString(ResolvedGeometry.serializer(), g)

    /** THE DIFFERENTIAL: the rule's inversion is published as inferred, beside the value it describes. */
    @Test
    fun `an inversion the stack rule set is published as inferred`() = runTest {
        val g = geometryOf(stored(pushdown(GeometrySource.INFERRED)))
        assertEquals("true", g.getValue("sensorInverted").jsonPrimitive.content)
        assertEquals("inferred", g.getValue("source").jsonObject["sensorInverted"]?.jsonPrimitive?.content)
    }

    /** A written key is published as declared, and the mapping cannot have read a sibling's source into it. */
    @Test
    fun `a written inversion is published as declared`() = runTest {
        val source = geometryOf(stored(pushdown(GeometrySource.DECLARED))).getValue("source").jsonObject
        assertEquals("declared", source["sensorInverted"]?.jsonPrimitive?.content)
        assertEquals("seeded", source.getValue("sensorOnStack").jsonPrimitive.content)
    }

    /**
     * A set recorded before 1.26: NO KEY, never a defaulted `default`, and the
     * seven siblings still published. Green on both sides of the fix.
     */
    @Test
    fun `a set stored before any build recorded the source publishes none`() = runTest {
        val g = geometryOf(storedBefore126)
        val source = g.getValue("source").jsonObject
        assertFalse("sensorInverted" in source, "a set with no stored answer was given one: $source")
        assertEquals(7, source.size, "a sibling source was dropped: $source")
        assertEquals("true", g.getValue("sensorInverted").jsonPrimitive.content)
    }
}
