package com.macrophage.barspeed.data

import com.macrophage.barspeed.dsp.SetAnalysis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What `session.json` says about how the whole session felt (#159).
 *
 * DIFFERENTIALS. Three of these five fail at the commit that introduces them:
 * the exporter reads the row's rating nowhere and writes no such key. The
 * commit after this one is what makes them pass. The two that do NOT fail here
 * are named in this file at the tests themselves, because a test that passes
 * inside a red commit passes for a reason that will stop being true -- the key
 * is absent because NOTHING writes it, not because absence was chosen -- and
 * that is worth writing down rather than leaving to be inferred from a green
 * tick.
 *
 * A separate file from [SessionExporterTest] because that class is already at
 * detekt's LargeClass limit, the same reason [SessionExportPrepTest],
 * [SessionExportRepMarksTest] and [SessionExportSensorsTest] are separate. The
 * fakes are this file's own, as every test file in this module keeps its own.
 *
 * Nothing here executes Room, SQLite or Android. What is verified is the
 * exporter's own mapping and nothing about what the database did with it.
 */
class SessionExportSessionRpeTest {
    private class FakeSessionDao(private val session: SessionEntity, private val rows: List<SetRecordEntity>) :
        SessionDao {
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

        override suspend fun updateLimiter(setId: Long, limiter: String?, limiterNote: String?) = Unit

        override suspend fun updateWarmupMark(setId: Long, warmupMark: Boolean?) = Unit

        // Conformance only: SessionDao grew this member for #60 and Kotlin
        // requires it. Nothing in this file calls it.
        override suspend fun updateVoided(setId: Long, voided: Boolean, reason: String?) = Unit

        override suspend fun overrideReps(setId: Long, reps: Int) = Unit

        // Conformance only: SessionDao grew this member for #205 and Kotlin
        // requires it. Nothing in this file calls it.
        override suspend fun overrideLoad(setId: Long, loadKg: Double) = Unit

        override suspend fun overrideDuration(setId: Long, seconds: Int) = Unit

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

    private fun session(sessionRpe: Int?, hrAvgBpm: Int? = 112, hrMaxBpm: Int? = 156) = SessionEntity(
        id = 1L,
        startedAtMs = 1_000L,
        endedAtMs = 61_000L,
        hrAvgBpm = hrAvgBpm,
        hrMaxBpm = hrMaxBpm,
        sessionRpe = sessionRpe,
    )

    /** One ordinary set, rated on the PER-SET scale, so both ratings are in one document. */
    private fun setRow(rpe: Int? = 8) = SetRecordEntity(
        id = 5L,
        sessionId = 1L,
        orderIdx = 0,
        exerciseId = "bench_press",
        exerciseName = "Bench Press",
        loadKg = 80.0,
        actualReps = 5,
        repsManual = true,
        plannedReps = 5,
        rpe = rpe,
        startedAtMs = 1_000L,
        endedAtMs = 61_000L,
        analysisJson = json.encodeToString(SetAnalysis.serializer(), noReps),
    )

    private suspend fun document(session: SessionEntity, rows: List<SetRecordEntity> = listOf(setRow())): JsonObject {
        val dao = FakeSessionDao(session, rows)
        val exporter = SessionExporter(SessionRepository(dao, FakeExerciseDao()), dispatcher = Dispatchers.Default)
        return Json.parseToJsonElement(exporter.exportJson(1L, includeRepDetail = true)!!).jsonObject
    }

    // ---- the rating reaches the document ------------------------------------

    /**
     * A rated session publishes the rating the row carries. DIFFERENTIAL:
     * fails at this commit, where the exporter never reads the column.
     */
    @Test
    fun `a rated session publishes the rating the row carries`() = runTest {
        assertEquals(7, document(session(sessionRpe = 7)).getValue("sessionRpe").jsonPrimitive.int)
    }

    /**
     * The bottom of the scale reaches the wire. DIFFERENTIAL: fails at this
     * commit.
     *
     * 1 is a real answer -- the session that barely touched the lifter -- and
     * the exporter writes JSON with `encodeDefaults = false` and
     * `explicitNulls = false`. A rating mapped through anything that treats a
     * low number as a default, or a column typed non-nullable with 0 standing
     * in for absence, publishes nothing here and the lifter's easiest session
     * becomes an unrated one. The two states this key has to keep apart are
     * "1" and "not stated", and they are one character apart at the call site.
     */
    @Test
    fun `the easiest rating on the scale is published rather than dropped as a default`() = runTest {
        assertEquals(1, document(session(sessionRpe = 1)).getValue("sessionRpe").jsonPrimitive.int)
    }

    /**
     * The rating survives a session whose heart-rate block is withheld.
     * DIFFERENTIAL: fails at this commit.
     *
     * The near neighbour, and it is a live one rather than a hypothetical. The
     * session-level `heartRate` block is withheld outright when a session has
     * sets and not one of them publishes an `hr` block (#83), and the rating
     * sits directly beside that gate in the same constructor call. A rating
     * folded inside that condition would vanish for exactly the sessions
     * recorded without a strap, which is most of them.
     */
    @Test
    fun `the rating survives a session that publishes no heart rate at all`() = runTest {
        val document = document(session(sessionRpe = 9, hrAvgBpm = null, hrMaxBpm = null))

        assertFalse("heartRate" in document, "this session was supposed to publish no heart-rate block")
        assertEquals(9, document.getValue("sessionRpe").jsonPrimitive.int)
    }

    // ---- absence, and the other scale ---------------------------------------

    /**
     * An unrated session publishes no rating key.
     *
     * NOT a differential: this passes at this commit for the reason nothing
     * writes the key at all, and it starts meaning something one commit later.
     * It is added here so the pair arrives together -- absence is the ordinary
     * state of this key, since the capture is skippable, and a suite that pins
     * only the present case cannot tell a skipped rating from a default one.
     */
    @Test
    fun `an unrated session publishes no rating key rather than a neutral number`() = runTest {
        val document = document(session(sessionRpe = null))

        assertFalse("sessionRpe" in document, "an unrated session published a rating: $document")
    }

    /**
     * The session rating is published at the session and the set rating at the
     * set, and neither appears where the other belongs.
     *
     * NOT a differential either, and it is the pin the owner's ruling asks
     * for: two things called RPE in one document, over overlapping ranges,
     * distinguishable only by where they sit and what their descriptions say.
     * A rating copied onto every set -- the shape a "convenience" flattening
     * takes -- would be read as a per-set effort by anything aggregating sets.
     */
    @Test
    fun `the session rating is not written onto any set`() = runTest {
        val document = document(session(sessionRpe = 7), listOf(setRow(rpe = 8)))
        val sets = document.getValue("exercises").jsonArray.flatMap { it.jsonObject.getValue("sets").jsonArray }

        assertTrue(sets.isNotEmpty(), "this document was supposed to carry a set")
        sets.forEach { set ->
            assertFalse("sessionRpe" in set.jsonObject, "a set carries the session's rating: $set")
            assertEquals(8, set.jsonObject.getValue("rpe").jsonPrimitive.int, "the set lost its own rating")
        }
    }
}
