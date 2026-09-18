package com.macrophage.barspeed.data

import com.macrophage.barspeed.dsp.SetAnalysis
import com.macrophage.barspeed.model.HoldEndSource
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
 * The stored word saying what ended a hold reaches BOTH published documents.
 * Issues #259 and #249, export 1.21.
 *
 * GREEN AT THE COMMIT THAT ADDS IT, and said so rather than implied. The
 * exporter wiring landed with the column, one commit before the fix, so there
 * is no state of this branch in which a row holding `sensor` published nothing
 * -- the red for #259 is on the decisions that PUT a word on the row
 * (`HoldEndPolicyDifferentialTest`, `SessionRepositoryHoldEndTest`), not on the
 * publication. What this file is for is the four rules publication has to obey,
 * every one of which a mutation can break.
 *
 * BOTH WRITERS, for [RpeScalePublishedTest]'s reason: the session document is
 * serialised and the archive's manifest is assembled as text in a different
 * function, and a key wired into one of them publishes half a record.
 *
 * Nothing here executes Room, SQLite or Android.
 */
class SessionExportHoldEndTest {
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

    /**
     * Field-38 set 17's published shape, with the provenance word varied.
     *
     * The figures are that hang's own, from the session archive's `meta.json`:
     * `rope_dead_hang`, 29.369 kg, no reps, 45 s planned, failed by the lifter,
     * clock 1788517883914 to 1788517920142. The recorded seconds are 29 here --
     * what the release its armed unit saw makes them -- rather than the 36 the
     * tap produced or the 31 the owner's correction produced.
     *
     * A timed set publishes no rep list, so the analysis is empty: that is what
     * a hold really stores, and `SetAnalysis(emptyList(), 0.0, ...)` is the shape
     * the write path produces for one.
     */
    private fun row(durationEndedBy: String?, actualDurationS: Int? = 29, workBegan: Boolean = true) = SetRecordEntity(
        id = 17L,
        sessionId = 1L,
        orderIdx = 16,
        exerciseId = "rope_dead_hang",
        exerciseName = "Rope Dead Hang",
        loadKg = 29.369,
        actualReps = 0,
        actualDurationS = actualDurationS,
        plannedDurationS = 45,
        durationEndedBy = durationEndedBy,
        failed = true,
        failedByLifter = true,
        workBegan = workBegan,
        prepS = 8,
        startedAtMs = 1_788_517_875_899L,
        endedAtMs = 1_788_517_920_142L,
        analysisJson = json.encodeToString(
            SetAnalysis.serializer(),
            SetAnalysis(
                reps = emptyList(),
                sampleRateHz = 99.375,
                velocityLossPct = null,
                tempoCompliance = null,
                verdicts = emptyList(),
            ),
        ),
    )

    private fun repositoryFor(row: SetRecordEntity): SessionRepository {
        val session = SessionEntity(id = 1L, startedAtMs = 1_788_517_000_000L, endedAtMs = 1_788_518_100_000L)
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
        val bytes = RawExporter(repo, SessionExporter(repo), appVersion = "0.1.54").buildZip(1L)!!
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

    @Test
    fun `a hold whose own unit timed the release publishes the word, in both documents`() = runTest {
        val word = HoldEndSource.SENSOR.published
        assertEquals(
            word,
            setObject(row(durationEndedBy = word))["durationEndedBy"]?.jsonPrimitive?.content,
            "the session document does not say what ended the hold",
        )
        assertEquals(
            word,
            manifestSet(row(durationEndedBy = word))["durationEndedBy"]?.jsonPrimitive?.content,
            "the archive's manifest does not say what ended the hold",
        )
        // And the figure the word qualifies is untouched beside it: 29, not the
        // 36 the tap produced.
        assertEquals(
            29,
            setObject(row(durationEndedBy = word)).getValue("duration_s").jsonPrimitive.content.toInt(),
            "duration_s moved",
        )
    }

    @Test
    fun `every word the app can store is published verbatim`() = runTest {
        for (source in HoldEndSource.entries) {
            assertEquals(
                source.published,
                setObject(row(durationEndedBy = source.published))["durationEndedBy"]?.jsonPrimitive?.content,
                "the exporter does not pass through the stored word ${source.published}",
            )
        }
    }

    @Test
    fun `a row with no word publishes none, and so does a word the app never wrote`() = runTest {
        // A set recorded before database v19. Absence is the answer, and the
        // export says nowhere that the lifter ended it -- which is the whole
        // distinction #249 asked for.
        assertFalse("durationEndedBy" in setObject(row(durationEndedBy = null)), "a word nobody stored")
        assertFalse("durationEndedBy" in manifestSet(row(durationEndedBy = null)), "the manifest invented one")
        // The column is TEXT and the published key is schema-constrained to four
        // words, so anything else is read back as absence rather than passed on
        // to fail ajv four steps later.
        assertFalse("durationEndedBy" in setObject(row(durationEndedBy = "tap")), "an unknown word was published")
        assertFalse("durationEndedBy" in setObject(row(durationEndedBy = "SENSOR")), "the words are exact")
    }

    @Test
    fun `a set that never entered its work phase publishes no word about its seconds`() = runTest {
        // #216: such a set publishes no duration_s at all, because the zero it
        // stores was never a measurement. A word qualifying a figure that is not
        // there would qualify nothing.
        val abandoned = row(durationEndedBy = HoldEndSource.LIFTER.published, actualDurationS = 0, workBegan = false)
        assertFalse("duration_s" in setObject(abandoned), "the abandoned-in-prep rule moved")
        assertFalse("durationEndedBy" in setObject(abandoned), "a word about seconds that are not published")
        assertFalse("durationEndedBy" in manifestSet(abandoned), "the manifest published one anyway")
    }
}
