package com.macrophage.barspeed.data

import com.macrophage.barspeed.dsp.SetAnalysis
import kotlinx.coroutines.Dispatchers
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
import kotlin.test.assertNull

/**
 * A timed set publishes NO `reps` key, in either export writer (#71, export
 * 1.23).
 *
 * WHAT WAS FALSE. The set schema required `reps` on every set, so a plank, a
 * dead hang or a farmer's walk was obliged to publish `"reps": 0` -- a number
 * nothing counted, which a reader cannot tell from a dynamic set that scored
 * no reps. The row's NOT NULL column holds the batch segmenter's count there,
 * which no counter produced; the fix withholds it at export and leaves the
 * database alone.
 *
 * THE TIMED MARKER is the row's `actualDurationS` column, the one `repsSource`
 * already reads: a set abandoned in its prep publishes no duration and is
 * still a timed set, so the PUBLISHED duration would call it a rep set.
 *
 * BOTH WRITERS, for [RepsSourcePublishedTest]'s reason: session.json is
 * serialised by kotlinx and the raw archive's meta.json is assembled as text
 * by a different function, and a rule wired into one of them publishes half a
 * record.
 *
 * RED WHEN WRITTEN: the three timed shapes below publish `"reps": 0` in both
 * documents. The two rep-set tests are guards, green before the fix and
 * after it: withholding the count must not reach a set that is counted in
 * reps, including one that scored none.
 *
 * Nothing here executes Room, SQLite or Android.
 */
class TimedSetRepsPublishedTest {
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

        override suspend fun setsForSession(sessionId: Long): List<SetRecordEntity> = rows

        override fun observeSetsForSession(sessionId: Long): Flow<List<SetRecordEntity>> = flowOf(rows)

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

    private val json = Json { ignoreUnknownKeys = true }

    /** What the analyzer makes of a hold: no reps, which is what the batch segmenter's count was taken from. */
    private val noReps =
        SetAnalysis(
            reps = emptyList(),
            sampleRateHz = 99.4,
            velocityLossPct = null,
            tempoCompliance = null,
            verdicts = emptyList(),
        )

    /**
     * A synthetic 45 s plank, the issue's own reproduction: `actualReps` 0 in
     * the NOT NULL column, `actualDurationS` 45.
     */
    private fun plank() = SetRecordEntity(
        id = 4L,
        sessionId = 1L,
        orderIdx = 0,
        exerciseId = "plank",
        exerciseName = "Plank",
        loadKg = 0.0,
        actualReps = 0,
        actualDurationS = 45,
        plannedDurationS = 45,
        workBegan = true,
        startedAtMs = 1_788_342_174_823L,
        endedAtMs = 1_788_342_220_675L,
        analysisJson = json.encodeToString(SetAnalysis.serializer(), noReps),
    )

    /** A dynamic set: no duration column, so it is counted in reps. */
    private fun squat(actualReps: Int) = plank().copy(
        exerciseId = "back_squat",
        exerciseName = "Back Squat",
        loadKg = 100.0,
        actualReps = actualReps,
        actualDurationS = null,
        plannedDurationS = null,
        plannedReps = 5,
    )

    private fun repositoryFor(row: SetRecordEntity): SessionRepository {
        val session = SessionEntity(id = 1L, startedAtMs = 1_788_342_000_000L, endedAtMs = 1_788_343_100_000L)
        return SessionRepository(FakeSessionDao(session = session, rows = listOf(row)), FakeExerciseDao())
    }

    /** The one set of the SESSION DOCUMENT. */
    private suspend fun setObject(row: SetRecordEntity): JsonObject {
        val exporter = SessionExporter(repositoryFor(row), dispatcher = Dispatchers.Default)
        val text = exporter.exportJson(1L, includeRepDetail = true)!!
        return Json.parseToJsonElement(text)
            .jsonObject.getValue("exercises").jsonArray.single()
            .jsonObject.getValue("sets").jsonArray.single().jsonObject
    }

    /** The one set of the RAW ARCHIVE'S manifest. */
    private suspend fun manifestSet(row: SetRecordEntity): JsonObject {
        val repo = repositoryFor(row)
        val bytes = RawExporter(repo, SessionExporter(repo), appVersion = "0.1.56").buildZip(1L)!!
        val entries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                entries[entry.name] = zin.readBytes().decodeToString()
            }
        }
        return Json.parseToJsonElement(entries.getValue("meta.json"))
            .jsonObject.getValue("sets").jsonArray.single().jsonObject
    }

    private fun JsonObject.reps(): Int? = this["reps"]?.jsonPrimitive?.content?.toInt()

    /** The issue's reproduction, in the session document. */
    @Test
    fun `a synthetic plank publishes no reps in the session document`() = runTest {
        val set = setObject(plank())
        assertEquals(45, set.getValue("duration_s").jsonPrimitive.content.toInt(), "the fixture is not a hold")
        assertFalse("reps" in set, "a timed set published a rep count nothing counted: $set")
    }

    /** The same set in the raw archive's manifest, which has no schema gating it at all. */
    @Test
    fun `a synthetic plank publishes no reps in the archive's manifest`() = runTest {
        val set = manifestSet(plank())
        assertEquals(45, set.getValue("duration_s").jsonPrimitive.content.toInt(), "the fixture is not a hold")
        assertFalse("reps" in set, "the manifest published a rep count nothing counted: $set")
    }

    /**
     * A hold ended in its prep publishes neither a duration nor a count.
     *
     * The near neighbour of the plank: `AbandonedSetPolicy` withholds the
     * duration on such a set, so an exporter that read the PUBLISHED duration
     * as the timed marker would call it a rep set and keep its 0.
     */
    @Test
    fun `a timed set abandoned in its prep publishes no reps either`() = runTest {
        val row = plank().copy(actualDurationS = 0, workBegan = false, prepS = 5)
        val set = setObject(row)
        assertNull(set["duration_s"], "the fixture is not the abandoned-in-prep shape")
        assertFalse("reps" in set, "an abandoned hold published a rep count: $set")
        assertFalse("reps" in manifestSet(row), "an abandoned hold published a rep count in the manifest")
    }

    /**
     * GUARD. A rep set that scored nothing still publishes 0, because there 0
     * is a count: the set was counted in reps and none was found.
     */
    @Test
    fun `a rep set still publishes its count, zero included`() = runTest {
        assertEquals(5, setObject(squat(5)).reps(), "a rep set lost its count")
        assertEquals(5, manifestSet(squat(5)).reps(), "a rep set lost its count in the manifest")
        assertEquals(0, setObject(squat(0)).reps(), "a rep set that scored none lost its 0")
        assertEquals(0, manifestSet(squat(0)).reps(), "a rep set that scored none lost its 0 in the manifest")
    }

    /**
     * GUARD. A REP set abandoned in its prep keeps its 0 -- the schema's
     * `abandonedInPrep` description says so -- because it is not timed; the
     * rule reads the duration column, not the abandonment.
     */
    @Test
    fun `a rep set abandoned in its prep still publishes reps 0`() = runTest {
        val row = squat(0).copy(workBegan = false, prepS = 5)
        val set = setObject(row)
        assertEquals(true, set["abandonedInPrep"]?.jsonPrimitive?.content?.toBoolean(), "not the abandoned shape")
        assertEquals(0, set.reps(), "an abandoned rep set lost its count")
        assertEquals(0, manifestSet(row).reps(), "an abandoned rep set lost its count in the manifest")
    }
}
