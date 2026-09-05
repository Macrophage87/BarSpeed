package com.macrophage.barspeed.data

import com.macrophage.barspeed.dsp.RepAnalysis
import com.macrophage.barspeed.dsp.SetAnalysis
import com.macrophage.barspeed.model.SessionExport
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

/**
 * Differentials for the publication half of #244: the row now says which
 * question the set was rated on, and neither export writer says so.
 *
 * RED AT THIS COMMIT. `SetRecordEntity.rpeScale` does not exist yet, so this
 * file cannot construct a row carrying one -- the fixture below passes the
 * word through the constructor and the whole class fails to compile until the
 * column lands, which is the same differential every other test in this file
 * makes and is the honest one for a column that cannot be faked.
 *
 * ## What a reader loses without it
 *
 * `rpe` is one integer carrying three different claims. A 6 on a `weight`
 * exercise says a plate pair was left; on a `reps` exercise it says three or
 * four reps were; on a `"none"` exercise it says the set felt comfortable and
 * names no quantity. Nothing else in the document separates them: the plan is
 * not in the export, and a plan can be edited or deleted after the session it
 * drove.
 *
 * BOTH WRITERS, for [BodyWeightPublishedTest]'s reason: the session document
 * is serialised and the archive's manifest is assembled as text in a different
 * function, and a key wired into one of them publishes half a record.
 *
 * ## The rule that is not obvious
 *
 * A SCALE WORD IS PUBLISHED ONLY BESIDE A RATING. The column is written on
 * every set, rated or not -- the app always knows which grid it drew -- but a
 * word alone in the document says only which tiles were on screen, which no
 * reader asked for, and it reads as though the set had been rated.
 * `failedByLifter` is published beside `failed` on the same argument.
 *
 * Nothing here executes Room, SQLite or Android.
 */
class RpeScalePublishedTest {
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

        // Conformance only: SessionDao grew this member on main for #60 and
        // Kotlin requires every implementation to carry it. Nothing here
        // calls it, and a voided set is not what this file is about.
        override suspend fun updateVoided(setId: Long, voided: Boolean, reason: String?) = Unit

        override suspend fun updateLimiter(setId: Long, limiter: String?, limiterNote: String?) = Unit

        override suspend fun updateWarmupMark(setId: Long, warmupMark: Boolean?) = Unit

        override suspend fun overrideReps(setId: Long, reps: Int) = Unit

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

    // ---- fixtures ----------------------------------------------------------

    private val json = Json { ignoreUnknownKeys = true }

    /** One rep, so the set publishes an ordinary summary and nothing is empty. */
    private val oneRep =
        SetAnalysis(
            reps =
            listOf(
                RepAnalysis(
                    index = 1,
                    eccS = 2.0,
                    bottomPauseS = null,
                    conS = 1.0,
                    topPauseS = null,
                    meanConVelMps = 0.42,
                    peakConVelMps = 0.61,
                    meanEccVelMps = -0.3,
                    peakEccVelMps = -0.5,
                    romM = 0.55,
                    peakPowerW = null,
                ),
            ),
            sampleRateHz = 99.4,
            velocityLossPct = null,
            tempoCompliance = null,
            verdicts = emptyList(),
        )

    /**
     * Field-37 set 8's published shape, with the stored scale word varied.
     *
     * The same row [BodyWeightPublishedTest] uses, for the same reason: it is
     * a shape the app really wrote (`assisted_pull_up`, `load_kg` 30.25, 5 reps
     * against 8 planned, failed, `repsManual` true, app 0.1.48, export 1.16).
     * The scale word is NOT from that archive -- no build stored one -- and
     * `reps` is the word an assisted pull-up would carry today, since it is
     * exactly the bodyweight work the lifter cannot add plates to.
     */
    private fun row(rpeScale: String?, rpe: Int? = 6) = SetRecordEntity(
        id = 8L,
        sessionId = 1L,
        orderIdx = 7,
        exerciseId = "assisted_pull_up",
        exerciseName = "Assisted Pull-up",
        loadKg = 30.25,
        actualReps = 5,
        repsManual = true,
        plannedReps = 8,
        rpe = rpe,
        failed = true,
        workBegan = true,
        startedAtMs = 1_788_342_174_823L,
        endedAtMs = 1_788_342_220_675L,
        rpeScale = rpeScale,
        analysisJson = json.encodeToString(SetAnalysis.serializer(), oneRep),
    )

    private fun repositoryFor(row: SetRecordEntity): SessionRepository {
        val session = SessionEntity(id = 1L, startedAtMs = 1_788_342_000_000L, endedAtMs = 1_788_343_100_000L)
        return SessionRepository(FakeSessionDao(session = session, rows = listOf(row)), FakeExerciseDao())
    }

    /** The one set of the SESSION DOCUMENT. */
    private suspend fun setObject(row: SetRecordEntity): JsonObject {
        val exporter = SessionExporter(repositoryFor(row), dispatcher = Dispatchers.Default)
        val text = exporter.exportJson(1L, includeRepDetail = false)!!
        return Json.parseToJsonElement(text)
            .jsonObject.getValue("exercises").jsonArray.single()
            .jsonObject.getValue("sets").jsonArray.single().jsonObject
    }

    /** The one set of the RAW ARCHIVE'S manifest. */
    private suspend fun manifestSet(row: SetRecordEntity): JsonObject {
        val repo = repositoryFor(row)
        val bytes = RawExporter(repo, SessionExporter(repo), appVersion = "0.1.50").buildZip(1L)!!
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

    // ---- the differentials -------------------------------------------------

    /**
     * RED. The case the issue exists for: a 6 on a pull-up reaches the coach
     * with the word saying it was three or four REPS and not a plate pair.
     */
    @Test
    fun `a rated set publishes the scale its rating was given on`() = runTest {
        assertEquals(
            "reps",
            setObject(row(rpeScale = "reps"))["rpeScale"]?.jsonPrimitive?.content,
            "the scale the rating was given on is still published nowhere",
        )
    }

    /** RED. The manifest says it too, for the reader who opens the CSVs alone. */
    @Test
    fun `the raw archive's manifest publishes the scale word too`() = runTest {
        assertEquals(
            "reps",
            manifestSet(row(rpeScale = "reps"))["rpeScale"]?.jsonPrimitive?.content,
            "the archive's manifest still carries a bare rpe",
        )
    }

    /** RED. Every word the column can hold reaches the document unchanged. */
    @Test
    fun `every scale word the app can store is published verbatim`() = runTest {
        for (word in SessionExport.VALID_RPE_SCALES) {
            assertEquals(
                word,
                setObject(row(rpeScale = word))["rpeScale"]?.jsonPrimitive?.content,
                "the exporter does not pass through the stored word $word",
            )
        }
    }

    /**
     * RED, and the near neighbour: `rpe` itself must go on being published
     * exactly as it was. This key explains that number rather than replacing
     * it, and a reader that ignores the word must read the document as before.
     */
    @Test
    fun `the rating is unchanged beside the new key`() = runTest {
        val set = setObject(row(rpeScale = "reps"))
        assertEquals(6, set.getValue("rpe").jsonPrimitive.content.toInt(), "rpe moved")
    }

    /**
     * RED. A word with no rating beside it is withheld from BOTH writers.
     *
     * The column is written on every set the app records, including the ones
     * nobody rated, so this is a real state and not a hypothetical: a word
     * alone would tell a reader which tiles were drawn, which is not a fact
     * about the set, and would read as a rating that was never given.
     */
    @Test
    fun `an unrated set publishes no scale word`() = runTest {
        assertFalse(
            "rpeScale" in setObject(row(rpeScale = "reps", rpe = null)),
            "a set nobody rated published the scale it would have been rated on",
        )
        assertFalse(
            "rpeScale" in manifestSet(row(rpeScale = "reps", rpe = null)),
            "the manifest published a scale word for a rating that was never given",
        )
    }

    /**
     * RED. A row recorded before the column publishes nothing, and the
     * document says elsewhere how to read that: `load` on a dynamic set,
     * `time` on a timed one, which is what the app asked before this key.
     *
     * Absent is never a fifth value. Nothing backfills it, because a past
     * set's progression is not recoverable from anything stored.
     */
    @Test
    fun `a row written before the column publishes nothing`() = runTest {
        assertFalse("rpeScale" in setObject(row(rpeScale = null)), "a word nobody stored was published")
        assertFalse("rpeScale" in manifestSet(row(rpeScale = null)), "the manifest invented a scale")
    }
}
