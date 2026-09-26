package com.macrophage.barspeed.data

import com.macrophage.barspeed.dsp.SetAnalysis
import com.macrophage.barspeed.model.SkippedSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
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
 * What `session.json` says about a prescribed set the lifter skipped (#300,
 * export 1.21).
 *
 * DIFFERENTIALS. The tests that read a `skippedSets` key out of the document
 * fail at the commit that introduces them: the exporter never reads the column
 * and writes no such key. The commit after this one is what makes them pass.
 * The ones that do NOT fail here are named at the tests themselves, because a
 * test that passes inside a red commit passes for a reason that will stop being
 * true -- the key is absent because NOTHING writes it, not because absence was
 * chosen -- and #159's rating differentials are where this practice comes from.
 *
 * A separate file from [SessionExporterTest] because that class is already at
 * detekt's LargeClass limit, the same reason [SessionExportSessionRpeTest] and
 * [SessionExportPrepTest] are separate. The fakes are this file's own, as every
 * test file in this module keeps its own.
 *
 * Nothing here executes Room, SQLite or Android. What is verified is the
 * exporter's own mapping out of a row it is handed, and nothing about what the
 * database did with that row -- the migration that adds the column is
 * [Migration18To19Test], which does not execute SQL either.
 */
class SessionExportSkippedSetTest {
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

    /** The column as the repository will write it: the `:core:model` type, encoded. */
    private fun encoded(vararg skips: SkippedSet) =
        json.encodeToString(ListSerializer(SkippedSet.serializer()), skips.toList())

    private fun session(skippedSetsJson: String?) = SessionEntity(
        id = 1L,
        startedAtMs = 1_000L,
        endedAtMs = 61_000L,
        planName = "Hypertrophy Block W3",
        planSessionName = "Lower A",
        skippedSetsJson = skippedSetsJson,
    )

    /** Two squat sets recorded of a block the plan prescribed four of. */
    private fun squatRows() = listOf(0, 1).map { order ->
        SetRecordEntity(
            id = 5L + order,
            sessionId = 1L,
            orderIdx = order,
            exerciseId = "back_squat",
            exerciseName = "Back squat",
            loadKg = 100.0,
            actualReps = 5,
            repsManual = true,
            plannedReps = 5,
            startedAtMs = 1_000L + order,
            endedAtMs = 61_000L + order,
            analysisJson = json.encodeToString(SetAnalysis.serializer(), noReps),
        )
    }

    private suspend fun document(session: SessionEntity, rows: List<SetRecordEntity> = squatRows()): JsonObject {
        val dao = FakeSessionDao(session, rows)
        val exporter = SessionExporter(SessionRepository(dao, FakeExerciseDao()), dispatcher = Dispatchers.Default)
        return Json.parseToJsonElement(exporter.exportJson(1L, includeRepDetail = true)!!).jsonObject
    }

    // ---- the skip reaches the document --------------------------------------

    /**
     * A session whose row carries a skip publishes it. DIFFERENTIAL: fails at
     * this commit, where the exporter never reads the column.
     *
     * This is the whole reading the key exists for: the document says two squat
     * sets were recorded AND that sets 3 and 4 were prescribed and dropped on
     * purpose, so a coach counting adherence as recorded-and-not-voided over
     * prescribed gets 2 of 4 with the reason, instead of three readings it
     * cannot tell apart.
     */
    @Test
    fun `a session that skipped sets publishes them with the exercise and the set number`() = runTest {
        val document = document(
            session(encoded(SkippedSet("back_squat", 3), SkippedSet("back_squat", 4))),
        )

        val skips = document.getValue("skippedSets").jsonArray.map { it.jsonObject }
        assertEquals(2, skips.size, "the document lost a skip: $skips")
        assertEquals(
            listOf("back_squat" to 3, "back_squat" to 4),
            skips.map {
                it.getValue("exercise").jsonPrimitive.content to it.getValue("setNumber").jsonPrimitive.int
            },
            "the published skips are not the ones the row carries, in the order it carries them",
        )
    }

    /**
     * The skip is published at the SESSION and nowhere near the sets.
     * DIFFERENTIAL.
     *
     * The near neighbour, and the one that would corrupt the reading rather than
     * omit it: a skipped set written into `exercises` as a set of zero reps would
     * be counted as performed by everything that counts sets, and would carry a
     * `plannedReps` it never worked to. There is no row for it and there must be
     * no entry for it.
     */
    @Test
    fun `a skipped set is published at the session and adds no set to any exercise`() = runTest {
        val document = document(session(encoded(SkippedSet("back_squat", 4))))
        val exercises = document.getValue("exercises").jsonArray.map { it.jsonObject }

        assertEquals(1, exercises.size, "the skip invented an exercise block: $exercises")
        assertEquals(
            2,
            exercises.single().getValue("sets").jsonArray.size,
            "the skip was published as a set of the exercise, which counts as performed",
        )
        exercises.single().getValue("sets").jsonArray.forEach { set ->
            assertFalse("skippedSets" in set.jsonObject, "a set carries the session's skip list: $set")
        }
    }

    /**
     * The exercise ID is what is published, so a reader can join the skip to the
     * sets. DIFFERENTIAL.
     *
     * The rows carry `exerciseName` = "Back squat" beside `exerciseId` =
     * "back_squat", and `ExerciseExport.exercise` publishes the ID. A skip
     * published under the display name would be unjoinable with the sets in the
     * same document, which is the whole use of the key.
     */
    @Test
    fun `the published skip names the exercise the same way the exported sets do`() = runTest {
        val document = document(session(encoded(SkippedSet("back_squat", 4))))
        val exercises = document.getValue("exercises").jsonArray
            .map { it.jsonObject.getValue("exercise").jsonPrimitive.content }
        val skipped = document.getValue("skippedSets").jsonArray
            .map { it.jsonObject.getValue("exercise").jsonPrimitive.content }

        assertTrue(skipped.all { it in exercises }, "the skip names $skipped, the sets name $exercises")
    }

    // ---- absence, and a column that will not decode -------------------------

    /**
     * A session that skipped nothing publishes no key.
     *
     * NOT a differential: this passes at this commit for the reason nothing
     * writes the key at all, and it starts meaning something one commit later.
     * It is here so the pair arrives together -- absence is the ORDINARY state
     * of this key, since most sessions skip nothing and every session recorded
     * before v19 has a null column -- and a suite pinning only the present case
     * cannot tell an empty array from a session nobody asked.
     */
    @Test
    fun `a session that skipped nothing publishes no skip key rather than an empty array`() = runTest {
        val document = document(session(null))

        assertFalse("skippedSets" in document, "a session with no skips published a skip list: $document")
    }

    /**
     * A column that will not decode publishes no key and does not take the
     * export down with it.
     *
     * NOT a differential either, and it is a decision rather than an accident:
     * `SessionRepository.decodeGeometry` and `decodeSensors` already answer a
     * corrupt blob with null on the same reasoning, and the alternative here is
     * worse than losing the skip list. An export that throws loses the session's
     * sets, its rest windows and its raw streams -- everything that cannot be
     * recomputed -- to recover a list of set numbers that can be re-read off the
     * plan.
     */
    @Test
    fun `a skip list that will not decode costs the list and not the export`() = runTest {
        val document = document(session("{\"not\": \"an array\"}"))

        assertFalse("skippedSets" in document, "a corrupt skip list reached the wire: $document")
        assertEquals(
            2,
            document.getValue("exercises").jsonArray.single().jsonObject.getValue("sets").jsonArray.size,
            "a corrupt skip list cost the session its sets",
        )
    }

    /**
     * The document declares 1.24, and 1.21 is the version that added the key.
     *
     * It is asserted HERE so a session document carrying a skip cannot
     * advertise a version whose contract does not describe one: a reader of a
     * 1.20 document is entitled to assume no such key exists. The literal read
     * 1.21, the number that minted the key; #260's mint moved what the exporter
     * writes to 1.22, #157's to 1.23 and #62's to 1.24, and each log carries every entry the
     * one before it did, so the document still declares a contract that
     * describes the key. This literal
     * moves at every mint, which is the price of asserting the emitted number
     * rather than the constant that produced it -- asserting the constant would
     * be an equality with itself.
     */
    @Test
    fun `a document carrying a skip declares the version that describes it`() = runTest {
        assertEquals(
            "1.24",
            document(session(encoded(SkippedSet("back_squat", 4))))
                .getValue("schemaVersion").jsonPrimitive.content,
            "the exported document does not declare the version the skip key rides under",
        )
    }
}
