package com.macrophage.barspeed.data

import com.macrophage.barspeed.dsp.SetAnalysis
import com.macrophage.barspeed.model.SetLimiter
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
import kotlin.test.assertTrue

/**
 * Differentials for publishing why a set ended (#189).
 *
 * FOUR OF THESE SIX FAILED WHEN THEY WERE WRITTEN, at b5fdb50 (CI run
 * 33313909287, conclusion failure), and that was the point of the commit
 * carrying them: the columns existed and a row carried them from the commit
 * before it, and nothing read them. So the reason a lifter gave would be
 * recorded and published nowhere, which is the whole of the defect -- the
 * export is the document a coach reads and the row is not.
 *
 * The two absence assertions passed there, because nothing wrote the keys at
 * all. They are kept to fail LATER, if a fix ever gives a skipped question a
 * default answer.
 *
 * BOTH WRITERS ARE COVERED HERE, DELIBERATELY. The session document is
 * serialised by kotlinx; the raw archive's `meta.json` is assembled as TEXT by
 * a different function in the same file. Wiring one and not the other is this
 * repository's near-neighbour class in its exact shape: `added` and `warmup`
 * each had to be written into both, and a test that read only the first would
 * have passed on half a change.
 *
 * The note is the harder half. It is the first free text ever to reach the
 * text-assembled manifest, whose string writer escapes nothing, so "published
 * verbatim" is a claim about that writer and not only about kotlinx.
 *
 * Nothing here executes Room, SQLite or Android. The DAOs are interfaces and
 * the fakes below stand in for them; what is verified is the exporters' own
 * mapping. Each test file in this module carries its own fakes, as
 * [SessionExportPrepTest] and [SessionExporterTest] both do.
 */
class SessionExportLimiterTest {
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

        override suspend fun updateRpe(setId: Long, rpe: Int?, failed: Boolean, warmup: Boolean) = Unit

        override suspend fun updateLimiter(setId: Long, limiter: String?, limiterNote: String?) = Unit

        override suspend fun updateWarmupMark(setId: Long, warmupMark: Boolean?) = Unit

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

    private val noReps =
        SetAnalysis(
            reps = emptyList(),
            sampleRateHz = 100.0,
            velocityLossPct = null,
            tempoCompliance = null,
            verdicts = emptyList(),
        )

    private fun row(limiter: String?, note: String?) = SetRecordEntity(
        id = 5L,
        sessionId = 1L,
        orderIdx = 0,
        exerciseId = "lat_pulldown",
        exerciseName = "Lat Pulldown",
        loadKg = 40.0,
        actualReps = 3,
        plannedReps = 8,
        failed = true,
        limiter = limiter,
        limiterNote = note,
        startedAtMs = 1_000L,
        endedAtMs = 41_000L,
        analysisJson = json.encodeToString(SetAnalysis.serializer(), noReps),
    )

    private fun repositoryFor(limiter: String?, note: String?): SessionRepository {
        val session = SessionEntity(id = 1L, startedAtMs = 1_000L, endedAtMs = 41_000L)
        return SessionRepository(
            FakeSessionDao(session = session, rows = listOf(row(limiter, note))),
            FakeExerciseDao(),
        )
    }

    /** The one set of the SESSION DOCUMENT, which kotlinx serialises. */
    private suspend fun setObject(limiter: String?, note: String? = null): JsonObject {
        val exporter = SessionExporter(repositoryFor(limiter, note), dispatcher = Dispatchers.Default)
        val text = exporter.exportJson(1L, includeRepDetail = false)!!
        return Json.parseToJsonElement(text)
            .jsonObject.getValue("exercises").jsonArray.single()
            .jsonObject.getValue("sets").jsonArray.single().jsonObject
    }

    /** The one set of the RAW ARCHIVE'S manifest, which is assembled as text. */
    private suspend fun manifestSet(limiter: String?, note: String? = null): JsonObject {
        val repo = repositoryFor(limiter, note)
        val bytes = RawExporter(repo, SessionExporter(repo), appVersion = "0.1.45").buildZip(1L)!!
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
     * The session document publishes the reason, under the stored name of the
     * answer the lifter tapped.
     *
     * A closed vocabulary and not a sentence, because grouping failures by
     * reason is the only thing this field is for.
     */
    @Test
    fun `the session document publishes the reason a set ended`() = runTest {
        val set = setObject(SetLimiter.GRIP.stored)
        assertEquals(SetLimiter.GRIP.stored, set.getValue("limiter").jsonPrimitive.content)
    }

    /**
     * A set with no reason publishes no key.
     *
     * Absence is a state, not a value. A default answer here would be counted
     * by exactly the grouping the field exists for, and a skipped question
     * would become an answer nobody gave.
     */
    @Test
    fun `a set carrying no reason publishes no reason key`() = runTest {
        val set = setObject(null)
        assertFalse("limiter" in set, "a set nobody gave a reason for published one")
        assertFalse("limiterNote" in set, "a set with no note published one")
    }

    /**
     * The free text is published BESIDE the answer, never inside it.
     *
     * A note stored as a value of the enum key would destroy the grouping. So
     * both keys are on the wire, and the enum key still reads `other`.
     */
    @Test
    fun `the free text is published beside the reason rather than inside it`() = runTest {
        val set = setObject(SetLimiter.OTHER.stored, "rack was taken for a photo shoot")
        assertEquals(SetLimiter.OTHER.stored, set.getValue("limiter").jsonPrimitive.content)
        assertEquals("rack was taken for a photo shoot", set.getValue("limiterNote").jsonPrimitive.content)
    }

    /**
     * The raw archive's manifest carries both, not just the session document.
     *
     * Two writers, one fact. `added` and `warmup` each had to be written into
     * both; a change wired into one of them publishes half a record, and which
     * half depends on which file the coach opened.
     */
    @Test
    fun `the raw archive's manifest publishes the reason and its note too`() = runTest {
        val set = manifestSet(SetLimiter.PAIN.stored)
        assertEquals(SetLimiter.PAIN.stored, set.getValue("limiter").jsonPrimitive.content)
        val other = manifestSet(SetLimiter.OTHER.stored, "shoulder felt wrong at the top")
        assertEquals("shoulder felt wrong at the top", other.getValue("limiterNote").jsonPrimitive.content)
    }

    /**
     * A note survives the manifest byte for byte, including the characters
     * that made the cap and the normalizer necessary.
     *
     * The manifest is assembled as text and its string writer escapes nothing,
     * so this is the only place the round-trip claim can be checked at all. If
     * the exporter published a raw note, the assertion below would not merely
     * fail -- `meta.json` would not parse, for every set in the session.
     */
    @Test
    fun `a note survives the manifest that escapes nothing`() = runTest {
        val typed = "bar \"rolled\" off the hooks \\ caught it"
        val stored = SetLimiter.normalizeNote(typed)!!
        val set = manifestSet(SetLimiter.OTHER.stored, stored)
        assertEquals(stored, set.getValue("limiterNote").jsonPrimitive.content)
        assertTrue(
            '"' !in stored && '\\' !in stored,
            "the normalizer let through a character the manifest cannot carry",
        )
    }

    /**
     * A note with no answer beside it is not published.
     *
     * Only `other` carries words. A note orphaned from its answer would be a
     * free string a reader cannot group and cannot attribute.
     */
    @Test
    fun `a note with no reason beside it is not published`() = runTest {
        assertFalse("limiterNote" in setObject(null, "typed then skipped"), "an orphaned note reached the wire")
    }

    // ---- the publish boundary ---------------------------------------------

    /**
     * A stored answer this build has never heard of is published as NO answer.
     *
     * `limiter` is a TEXT column and the published schema declares it a CLOSED
     * enum. Nothing between them checks: both writers put `record.limiter` on
     * the wire raw. SetRecordEntity.limiter's own KDoc names the case that
     * breaks it -- a row written by a LATER build -- and SetLimiter.ofStored
     * exists precisely so such a row reads as an unrecognised string instead
     * of throwing.
     *
     * The closed enum is the entire reason this field exists, because grouping
     * failures by reason is impossible over an open vocabulary. So an
     * unrecognised value is published as absence, which the schema's own
     * description already defines as "not asked", rather than as a member of a
     * vocabulary it is not in.
     */
    @Test
    fun `an answer the vocabulary does not know is published as no answer`() = runTest {
        assertFalse("limiter" in setObject("teleported"), "an unrepresentable answer reached the session document")
        assertFalse("limiter" in manifestSet("teleported"), "an unrepresentable answer reached the manifest")
    }

    /**
     * Words stored beside an answer this build cannot read are not published
     * either.
     *
     * A note is readable only as the elaboration of the answer it was typed
     * for. Beside no answer it is free text a reader can neither group nor
     * attribute, which `a note with no reason beside it is not published`
     * already refuses; this is the same refusal for an answer present in the
     * column and absent from the vocabulary.
     */
    @Test
    fun `words beside an unknown answer are not published either`() = runTest {
        assertFalse("limiterNote" in setObject("teleported", "beamed up"), "an orphaned note reached the wire")
    }

    /**
     * A BACKSLASH IN THE COLUMN CANNOT DESTROY THE WHOLE MANIFEST.
     *
     * meta.json is assembled as text, and its string writer maps a double
     * quote to an apostrophe and escapes nothing else. Today the note is safe
     * there only because normalizeNote removed the backslash at the WRITE --
     * an assumption about the writer, relied on at the reader. A row this
     * build did not write, and the tree's own KDoc names one shape of those,
     * makes meta.json unparseable for EVERY SET IN THE SESSION rather than
     * corrupting one note.
     *
     * A whole archive lost against one bad column is the silent-data-loss
     * ranking, so the normalization is applied on the way out as well as on
     * the way in.
     */
    @Test
    fun `a stored note the write path could not have produced cannot break the manifest`() = runTest {
        val stored = "bar hit the pin " + BACKSLASH + " twice"
        val set = manifestSet("other", stored)
        assertEquals("bar hit the pin twice", set["limiterNote"]?.jsonPrimitive?.content)
    }

    private companion object {
        /** The one character the manifest's string writer does not escape. */
        const val BACKSLASH = "\\"
    }
}
