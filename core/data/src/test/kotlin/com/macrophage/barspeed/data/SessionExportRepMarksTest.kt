package com.macrophage.barspeed.data

import com.macrophage.barspeed.dsp.SetAnalysis
import com.macrophage.barspeed.model.VoiceCue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What the export says about WHEN each rep was counted (#158).
 *
 * A straight-rep set carries no tempo, so it runs no voice guide and produces
 * no cue track; the sensor's own reps carry an ordinal position and no clock.
 * The lifter's taps are therefore the only per-rep instants that exist for
 * such a set, and until this issue the export threw them away -- which is what
 * makes a field capture of one unscoreable without filming a wall clock.
 *
 * A separate file from [SessionExporterTest] because that class is already at
 * detekt's `LargeClass` limit, the same reason [SessionExportPrepTest] and
 * [SessionExportVelocityLossTest] are separate. The fakes are this file's own,
 * as every test file in this module keeps its own.
 *
 * Nothing here executes Room, SQLite or Android. What is verified is the
 * exporter's own mapping and nothing about what the database did with it.
 */
class SessionExportRepMarksTest {
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

        override suspend fun updateRpe(setId: Long, rpe: Int?, failed: Boolean, warmup: Boolean) = Unit

        override suspend fun overrideReps(setId: Long, reps: Int) = Unit

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

    // ---- fixtures ----------------------------------------------------------

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * A set the sensor resolved nothing from: the tap-counted case this issue
     * exists for. A rep mark is a fact about the lifter, not about the
     * samples, so nothing here depends on the DSP having measured anything.
     */
    private val noReps =
        SetAnalysis(
            reps = emptyList(),
            sampleRateHz = 0.0,
            velocityLossPct = null,
            tempoCompliance = null,
            verdicts = emptyList(),
        )

    private val cueStream =
        RawStreamEntity(
            id = 9L,
            setId = 5L,
            kind = RawStreamEntity.KIND_CUES,
            csvGzip = Gzip.compress(CueCsv.encode(listOf(VoiceCue(1_100L, "Rep 1")))),
        )

    private fun row() = SetRecordEntity(
        id = 5L,
        sessionId = 1L,
        orderIdx = 0,
        exerciseId = "back_squat",
        exerciseName = "Back Squat",
        loadKg = 100.0,
        actualReps = 3,
        repsManual = true,
        plannedReps = 3,
        startedAtMs = 1_000L,
        endedAtMs = 61_000L,
        analysisJson = json.encodeToString(SetAnalysis.serializer(), noReps),
    )

    private fun exporterOf(streams: List<RawStreamEntity>): SessionExporter {
        val dao =
            FakeSessionDao(
                session = SessionEntity(id = 1L, startedAtMs = 1_000L, endedAtMs = 61_000L),
                rows = listOf(row()),
                streams = mapOf(5L to streams),
            )
        return SessionExporter(SessionRepository(dao, FakeExerciseDao()), dispatcher = Dispatchers.Default)
    }

    /** The one set of an export built over a row carrying [streams]. */
    private suspend fun setObject(streams: List<RawStreamEntity>, includeRepDetail: Boolean = true): JsonObject {
        val text = exporterOf(streams).exportJson(1L, includeRepDetail)!!
        return Json.parseToJsonElement(text)
            .jsonObject.getValue("exercises").jsonArray.single()
            .jsonObject.getValue("sets").jsonArray.single().jsonObject
    }

    // ---- the key set, before anything moves --------------------------------

    /**
     * The set object's keys, exactly, in each export mode.
     *
     * The same instrument as `the export root states exactly the keys it
     * states today` in [SessionExporterTest], one level down, and the level
     * that actually moves: every schema version but 1.3 added or redefined a
     * key on the SET. `$defs.set` is `additionalProperties: false` in the
     * published contract, so a key appearing here without
     * `docs/schemas/session-export.schema.json` moving in the same change
     * makes every export the app writes invalid against the contract its own
     * reader was pointed at -- and ajv would not say so, because ajv validates
     * a hand-written example rather than anything this exporter produced.
     *
     * Both modes, because the difference between them is itself a published
     * fact: two keys are withheld from the summary export today and the rest
     * are not, and a third joining the withheld group quietly would take a
     * figure away from the summary reader with nothing failing.
     *
     * The fixture is the tap-counted set this file is about, so the key set
     * here is the one a straight-rep field capture produces.
     */
    @Test
    fun `the set object states exactly the keys it states today`() = runTest {
        assertEquals(
            setOf("load_kg", "load_lb", "reps", "repsManual", "plannedReps", "voiceCues", "summary"),
            setObject(listOf(cueStream), includeRepDetail = true).keys,
            "the detailed set object's key set moved",
        )
        assertEquals(
            setOf("load_kg", "load_lb", "reps", "repsManual", "plannedReps", "summary"),
            setObject(listOf(cueStream), includeRepDetail = false).keys,
            "the summary set object's key set moved",
        )
    }
}
