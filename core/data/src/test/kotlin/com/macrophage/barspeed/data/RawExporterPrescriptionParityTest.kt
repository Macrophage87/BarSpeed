package com.macrophage.barspeed.data

import com.macrophage.barspeed.dsp.SetAnalysis
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

/**
 * The raw archive's `meta.json` and its `session.json` publish the same
 * planned, working and rest keys with the same values, for every set (#219,
 * #157).
 *
 * #219 IS THIS FILE'S FIRST RED. The two documents are written by two
 * functions -- `session.json` is serialised by kotlinx, `meta.json` assembled
 * as text by `RawExporter.buildSetDescriptor` -- and each listed these keys for
 * itself. `plannedLoad_kg` has been in `session.json` and never in
 * `meta.json`, and so have `plannedDuration_s` and `rest_s`: a coach who
 * opened only the archive's manifest could not see what the plan asked for.
 * Export 1.23 adds five more keys to the same list, which is where the drift
 * would have happened again.
 *
 * BOTH TESTS FAIL WHEN THEY ARE WRITTEN. The parity test reds on
 * `plannedLoad_kg`, the first key the manifest lacks; the coverage test reds
 * because until export 1.23 `session.json` published none of the five 1.23
 * keys, which is also what keeps the parity test from passing vacuously on
 * keys absent from both documents.
 *
 * The keys are a LITERAL, the house rule for key pins: a list derived from
 * the export type would follow a rename silently, and would shrink with the
 * type rather than hold it to what both documents must carry.
 * `SetPrescriptionExportTest` pins the type against the same ten.
 *
 * The fixture is `SessionExportWorkingTargetsTest`'s four sets: a raised
 * squat later corrected, a lowered plank, a row written before v20, and a
 * last deadlift. Nothing here executes Room, SQLite or Android.
 */
class RawExporterPrescriptionParityTest {
    // ---- fakes -------------------------------------------------------------

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

    // ---- fixtures ----------------------------------------------------------

    private val noReps =
        Json.encodeToString(
            SetAnalysis.serializer(),
            SetAnalysis(
                reps = emptyList(),
                sampleRateHz = 99.4,
                velocityLossPct = null,
                tempoCompliance = null,
                verdicts = emptyList(),
            ),
        )

    private val raisedSquat =
        SetRecordEntity(
            id = 1L,
            sessionId = 1L,
            orderIdx = 0,
            exerciseId = "back_squat",
            exerciseName = "Back Squat",
            loadKg = 97.5,
            plannedLoadKg = 100.0,
            workingLoadKg = 102.5,
            actualReps = 8,
            plannedReps = 6,
            workingReps = 8,
            tempo = "2010",
            plannedTempo = "3010",
            plannedRestS = 120,
            restStartedAtMs = 61_500L,
            startedAtMs = 1_000L,
            endedAtMs = 61_000L,
            analysisJson = noReps,
        )

    private val loweredPlank =
        SetRecordEntity(
            id = 2L,
            sessionId = 1L,
            orderIdx = 1,
            exerciseId = "plank",
            exerciseName = "Plank",
            loadKg = 0.0,
            workingLoadKg = 0.0,
            actualReps = 0,
            actualDurationS = 30,
            plannedDurationS = 45,
            workingDurationS = 30,
            workBegan = true,
            plannedRestS = 90,
            restStartedAtMs = 249_750L,
            startedAtMs = 212_000L,
            endedAtMs = 250_000L,
            analysisJson = noReps,
        )

    private val beforeV20 =
        SetRecordEntity(
            id = 3L,
            sessionId = 1L,
            orderIdx = 2,
            exerciseId = "barbell_row",
            exerciseName = "Barbell Row",
            loadKg = 60.0,
            plannedLoadKg = 60.0,
            actualReps = 10,
            plannedReps = 10,
            tempo = "2011",
            plannedRestS = 90,
            startedAtMs = 400_000L,
            endedAtMs = 440_000L,
            analysisJson = noReps,
        )

    private val lastDeadlift =
        SetRecordEntity(
            id = 4L,
            sessionId = 1L,
            orderIdx = 3,
            exerciseId = "deadlift",
            exerciseName = "Deadlift",
            loadKg = 140.0,
            plannedLoadKg = 140.0,
            workingLoadKg = 140.0,
            actualReps = 5,
            plannedReps = 5,
            workingReps = 5,
            plannedRestS = 180,
            restStartedAtMs = 700_000L,
            startedAtMs = 650_000L,
            endedAtMs = 699_000L,
            analysisJson = noReps,
        )

    private val keys =
        listOf(
            "plannedLoad_kg", "plannedReps", "plannedDuration_s", "rest_s", "tempoPrescribed",
            "workingLoad_kg", "workingReps", "workingDuration_s", "plannedTempo", "restMeasured_s",
        )

    /** Both documents of one archive, each set keyed by its exercise. */
    private suspend fun archive(): Pair<Map<String, JsonObject>, Map<String, JsonObject>> {
        val session = SessionEntity(id = 1L, startedAtMs = 1_000L, endedAtMs = 800_000L)
        val rows = listOf(raisedSquat, loweredPlank, beforeV20, lastDeadlift)
        val repository = SessionRepository(FakeSessionDao(session, rows), FakeExerciseDao())
        val bytes = RawExporter(repository, SessionExporter(repository), appVersion = "0.1.56").buildZip(1L)!!
        val entries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                entries[entry.name] = zin.readBytes().decodeToString()
            }
        }
        val manifest = Json.parseToJsonElement(entries.getValue("meta.json")).jsonObject
            .getValue("sets").jsonArray.map { it.jsonObject }
            .associateBy { it.getValue("exercise").jsonPrimitive.content }
        val document = Json.parseToJsonElement(entries.getValue("session.json")).jsonObject
            .getValue("exercises").jsonArray.associate {
                val exercise = it.jsonObject
                val set = exercise.getValue("sets").jsonArray.single().jsonObject
                exercise.getValue("exercise").jsonPrimitive.content to set
            }
        return manifest to document
    }

    private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.content

    /**
     * RED. For every set and every key, the manifest says what the session
     * document says -- the same value, or absent in both.
     */
    @Test
    fun `meta_json carries each planned, working and rest key session_json carries, at the same value`() = runTest {
        val (manifest, document) = archive()
        assertEquals(document.keys, manifest.keys, "the two documents list different sets")
        val disagreements =
            document.keys.flatMap { exercise ->
                val fromDocument = document.getValue(exercise)
                val fromManifest = manifest.getValue(exercise)
                keys.filter { fromDocument.text(it) != fromManifest.text(it) }.map { key ->
                    "$exercise.$key: session.json ${fromDocument.text(key)}, meta.json ${fromManifest.text(key)}"
                }
            }
        assertEquals(emptyList(), disagreements, "the manifest and the session document disagree")
    }

    /**
     * RED. The fixture carries every key in at least one set of the session
     * document, so the parity above is tested on values and never only on two
     * absences.
     */
    @Test
    fun `session_json publishes every planned, working and rest key somewhere in the fixture`() = runTest {
        val (_, document) = archive()
        val published = document.values.flatMap { it.keys }.toSet()
        assertEquals(emptyList(), keys.filter { it !in published }, "keys no set of the fixture publishes")
    }
}
