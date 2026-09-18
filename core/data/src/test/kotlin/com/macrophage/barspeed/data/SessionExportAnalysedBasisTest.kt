package com.macrophage.barspeed.data

import com.macrophage.barspeed.dsp.ImuCsv
import com.macrophage.barspeed.dsp.SetAnalysis
import com.macrophage.barspeed.model.ImuSample
import kotlinx.coroutines.Dispatchers
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
import kotlin.test.assertNull

/**
 * That `session.json` publishes WHY a set's figures came from the role they
 * came from, read off the row and never re-decided here. Issue #278.
 *
 * DIFFERENTIALS. Both assertions that expect a basis FAIL at the commit that
 * writes this file: `RecordedSensors` carries no such field, the row's JSON is
 * therefore written here by hand, and `SessionRepository`'s decoder is
 * configured with `ignoreUnknownKeys = true`, so today the key is read past and
 * the export publishes nothing. That is the point -- these fail on an
 * assertion rather than on a compile error, so the red is a measurement and not
 * a missing symbol. The commit after this one adds the field and publishes it.
 *
 * WHY IT IS READ OFF THE ROW. The basis is decided when the set is RECORDED,
 * from the two streams as they stood then, and the analysis it selected is
 * frozen into that row. Re-deciding at export time would attribute today's rule
 * to figures produced by yesterday's, which is the mistake `analysedFellBack`
 * already refuses to make.
 *
 * Nothing here executes Room, SQLite or Android. What is verified is the
 * exporter's own mapping. The fakes are this file's own, as every test file in
 * this module keeps its own.
 */
class SessionExportAnalysedBasisTest {
    private class FakeSessionDao(
        private val rows: List<SetRecordEntity>,
        private val streams: Map<Long, List<RawStreamEntity>>,
    ) : SessionDao {
        private val session = SessionEntity(id = 1L, startedAtMs = 1_000L, endedAtMs = 61_000L)

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

    private val noReps = SetAnalysis(emptyList(), 0.0, null, null, emptyList())

    private fun samples(count: Int) = (0 until count).map { i ->
        ImuSample(1_000L + i * 10L, 0.01, -0.02, 0.98, 1.5, -2.5, 0.25, 10.0 + i, -20.0, 30.0)
    }

    private fun imuStream(id: Long, role: String) = RawStreamEntity(
        id = id,
        setId = 5L,
        kind = RawStreamEntity.KIND_IMU,
        csvGzip = Gzip.compress(ImuCsv.encode(samples(100))),
        sampleRateHz = 98.5,
        role = role,
    )

    /**
     * The row's sensor declaration as JSON TEXT rather than as an encoded
     * `RecordedSensors`, because the field this expects does not exist yet.
     *
     * Written out longhand for exactly that reason: the fix adds the field and
     * this text keeps decoding unchanged, so the same fixture states the
     * expectation before and after.
     */
    private fun row(sensorsJson: String) = SetRecordEntity(
        id = 5L,
        sessionId = 1L,
        orderIdx = 0,
        exerciseId = "seated_cable_row",
        exerciseName = "Seated Cable Row",
        loadKg = 34.019427750752655,
        actualReps = 8,
        repsManual = true,
        plannedReps = 8,
        startedAtMs = 1_000L,
        endedAtMs = 61_000L,
        analysisJson = json.encodeToString(SetAnalysis.serializer(), noReps),
        sensorsJson = sensorsJson,
    )

    private suspend fun sensorsObject(sensorsJson: String): JsonObject {
        val dao =
            FakeSessionDao(
                listOf(row(sensorsJson)),
                mapOf(5L to listOf(imuStream(1L, "a"), imuStream(2L, "b"))),
            )
        val exporter = SessionExporter(SessionRepository(dao, FakeExerciseDao()), dispatcher = Dispatchers.Default)
        val text = exporter.exportJson(1L, includeRepDetail = true)!!
        return Json.parseToJsonElement(text)
            .jsonObject.getValue("exercises").jsonArray.single()
            .jsonObject.getValue("sets").jsonArray.single()
            .jsonObject.getValue("sensors").jsonObject
    }

    /**
     * A set whose analysed role was chosen by the two units' roll says so, and
     * names role `b` with no fallback flag beside it.
     *
     * This is field-42's seated cable row in the shape the row stores it: the
     * set armed `a`, both units delivered, and the figures come from `b`, the
     * one whose roll says it rode the stack. `analysedFellBack` is ABSENT, and
     * that is the coupling this pin protects -- the flag means the armed unit
     * went quiet, and #247's refusal blanks a set on it.
     */
    @Test
    fun `a set whose role was chosen by the roll signature publishes that basis`() = runTest {
        val sensors =
            sensorsObject(
                """{"count":2,"expected":["A","B"],"analysed":"B","analysedRoleBasis":"STACK_SIGNATURE"}""",
            )

        assertEquals("b", sensors.getValue("analysedRole").jsonPrimitive.content)
        assertEquals(
            "stackSignature",
            sensors["analysedRoleBasis"]?.jsonPrimitive?.content,
            "the export does not say why role b was analysed",
        )
        assertNull(sensors["analysedFellBack"], "a signature verdict was published as a frame-count fallback")
    }

    /** The other two words reach the document as themselves, read off the row. */
    @Test
    fun `the declared and fallback bases reach the document unchanged`() = runTest {
        val declared =
            sensorsObject("""{"count":2,"expected":["A","B"],"analysed":"A","analysedRoleBasis":"DECLARED"}""")
        val fellBack =
            sensorsObject(
                """
                {"count":2,"expected":["A","B"],"analysed":"B","analysedFellBack":true,
                 "analysedRoleBasis":"FALLBACK"}
                """.trimIndent(),
            )

        assertEquals("declared", declared["analysedRoleBasis"]?.jsonPrimitive?.content)
        assertEquals("fallback", fellBack["analysedRoleBasis"]?.jsonPrimitive?.content)
        assertEquals(true, fellBack.getValue("analysedFellBack").jsonPrimitive.content.toBoolean())
    }

    /**
     * A ROW WRITTEN BY AN EARLIER BUILD PUBLISHES NO BASIS, and this one passes
     * today as well as after the fix.
     *
     * Absence rather than a defaulted `declared`: no build before this could
     * decide the question, so saying `declared` on such a row would claim a rule
     * ran over a set nothing looked at.
     */
    @Test
    fun `a row that stored no basis publishes none`() = runTest {
        val sensors = sensorsObject("""{"count":2,"expected":["A","B"],"analysed":"A"}""")

        assertEquals("a", sensors.getValue("analysedRole").jsonPrimitive.content)
        assertNull(sensors["analysedRoleBasis"], "a set recorded before this key was given one anyway")
    }
}
