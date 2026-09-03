package com.macrophage.barspeed.data

import com.macrophage.barspeed.dsp.ImuCsv
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
import kotlin.test.assertTrue

/**
 * What a set the lifter never performed publishes today, characterized before
 * anything is changed (#60).
 *
 * THE ROW IS FIELD-37 SET 13, and it is a real one. On 2026-09-02, app
 * 0.1.48, the plan held two dead hangs and the app re-armed the finished slot
 * (#195, fixed in v0.1.49), writing a thirteenth set nobody performed. The
 * owner confirmed it on #60. The figures below are that row's, taken from
 * #216's measurement of the same capture: `startedAt_ms` 1788343012005 to
 * `endedAt_ms` 1788343018340, `duration_s` 0, `plannedDuration_s` 20,
 * `failed` true, `prep_s` 12, `plannedPrep_s` 12, and no `rpe`.
 *
 * CHARACTERIZATION, NOT A DIFFERENTIAL. Every assertion here passes at the
 * commit that introduces it. They exist to state what the document says now,
 * so that the change which follows can be read as a difference rather than
 * asserted to be one -- and so that the half of the shape which must NOT move
 * is pinned before it is touched: the row's own figures, its raw streams and
 * its place in the exercise's set list all stay exactly as they are, and only
 * a marker is added beside them.
 *
 * WHAT #60 IS ABOUT is the last assertion: nothing in either document says the
 * set was not performed, and nothing in the app can say it afterwards.
 * `Daos.kt`'s writes against `set_records` can change rpe, failed, warmup, the
 * warm-up mark, the limiter, the rep count, the load and the duration; the
 * only other write is `DELETE FROM sessions`, which takes the whole session
 * and every gzipped stream in it. So the lifter's choices on this row are to
 * leave a hang they never attempted in their history, or to destroy the twelve
 * sets recorded beside it.
 *
 * Nothing here executes Room, SQLite or Android. The DAOs are interfaces and
 * the fakes below stand in for them, following [SessionExportLimiterTest],
 * whose fakes and manifest reader this file copies rather than shares -- each
 * test file in this module carries its own.
 */
class SessionExportUnperformedSetTest {
    // ---- fakes -------------------------------------------------------------

    private class FakeSessionDao(
        private val session: SessionEntity,
        private val rows: List<SetRecordEntity>,
        private val streams: List<RawStreamEntity> = emptyList(),
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

        override suspend fun rawStreamsForSet(setId: Long) = streams.filter { it.setId == setId }

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

    private val noReps =
        SetAnalysis(
            reps = emptyList(),
            sampleRateHz = 0.0,
            velocityLossPct = null,
            tempoCompliance = null,
            verdicts = emptyList(),
        )

    /** One gzipped IMU stream, so the archive has something to keep. */
    private fun imuStream() = RawStreamEntity(
        id = 1L,
        setId = 13L,
        kind = RawStreamEntity.KIND_IMU,
        csvGzip =
        Gzip.compress(
            """
            ${ImuCsv.HEADER}
            1000,0.10,0.02,9.79,0,0,0,0,0,0,0
            """.trimIndent(),
        ),
        sampleRateHz = 98.5,
    )

    /** Field-37 set 13: the rope dead hang the lifter never hung on. */
    private fun fabricatedRow(voided: Boolean = false, voidReason: String? = null) = SetRecordEntity(
        id = 13L,
        sessionId = 1L,
        orderIdx = 12,
        exerciseId = "rope_dead_hang",
        exerciseName = "Rope Dead Hang",
        loadKg = 0.0,
        actualReps = 0,
        actualDurationS = 0,
        plannedDurationS = 20,
        failed = true,
        plannedPrepS = 12,
        prepS = 12,
        plannedRestS = 120,
        startedAtMs = 1_788_343_012_005L,
        endedAtMs = 1_788_343_018_340L,
        analysisJson = json.encodeToString(SetAnalysis.serializer(), noReps),
        voided = voided,
        voidReason = voidReason,
    )

    private fun repository(
        voided: Boolean = false,
        voidReason: String? = null,
        streams: List<RawStreamEntity> = emptyList(),
    ): SessionRepository {
        val session =
            SessionEntity(
                id = 1L,
                startedAtMs = 1_788_340_000_000L,
                endedAtMs = 1_788_343_018_340L,
            )
        return SessionRepository(
            FakeSessionDao(
                session = session,
                rows = listOf(fabricatedRow(voided, voidReason)),
                streams = streams,
            ),
            FakeExerciseDao(),
        )
    }

    /** The one set of the SESSION DOCUMENT, which kotlinx serialises. */
    private suspend fun setObject(voided: Boolean = false, voidReason: String? = null): JsonObject {
        val exporter = SessionExporter(repository(voided, voidReason), dispatcher = Dispatchers.Default)
        val text = exporter.exportJson(1L, includeRepDetail = false)!!
        return Json.parseToJsonElement(text)
            .jsonObject.getValue("exercises").jsonArray.single()
            .jsonObject.getValue("sets").jsonArray.single().jsonObject
    }

    /** The one set of the RAW ARCHIVE's manifest, which is assembled as text. */
    private suspend fun archive(
        voided: Boolean = false,
        voidReason: String? = null,
        streams: List<RawStreamEntity> = emptyList(),
    ): Map<String, String> {
        val repo = repository(voided, voidReason, streams)
        val bytes = RawExporter(repo, SessionExporter(repo), appVersion = "0.1.49").buildZip(1L)!!
        val entries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                entries[entry.name] = zin.readBytes().decodeToString()
            }
        }
        return entries
    }

    private suspend fun manifestSet(voided: Boolean = false, voidReason: String? = null): JsonObject =
        Json.parseToJsonElement(archive(voided, voidReason).getValue("meta.json"))
            .jsonObject.getValue("sets").jsonArray.single().jsonObject

    // ---- what the document says today --------------------------------------

    /**
     * The set the lifter never performed is published as a hang that was
     * attempted and achieved nothing.
     *
     * Every figure here is true of the ROW and false of the session. That is
     * what makes the document unreadable rather than merely incomplete: a
     * consumer told `meta.analysisFile` is `session.json` reads a 20 second
     * prescription, a 0 second result and a failure, on a day the same lift
     * held 20 s and 30 s.
     */
    @Test
    fun `a set the lifter never performed publishes a prescription, a zero and a failure`() = runTest {
        val set = setObject()
        assertEquals(0, set.getValue("duration_s").jsonPrimitive.content.toInt())
        assertEquals(20, set.getValue("plannedDuration_s").jsonPrimitive.content.toInt())
        assertEquals(0, set.getValue("reps").jsonPrimitive.content.toInt())
        assertTrue(set.getValue("failed").jsonPrimitive.content.toBoolean())
    }

    /**
     * A set carrying no mark publishes no key saying it was not performed.
     *
     * The key set is asserted whole rather than key by key, because the
     * question is what a reader is given and not whether one name is missing:
     * a marker under any spelling would show up here. `rpe` and `limiter` are
     * the two that come closest and neither is one -- both are absent on this
     * row, and absence there already means "nobody was asked".
     *
     * `summary` is in the list and is the sharpest thing in it: a set with no
     * reps and no samples still publishes a summary block, so a reader that
     * looks for the block's presence to decide whether there is anything to
     * read finds one.
     */
    @Test
    fun `a set with no mark publishes no key saying it was not performed`() = runTest {
        val set = setObject()
        assertEquals(
            listOf(
                "duration_s", "failed", "load_kg", "load_lb", "plannedDuration_s",
                "plannedPrep_s", "prep_s", "reps", "rest_s", "summary",
            ).sorted(),
            set.keys.sorted(),
            "the published set gained or lost a key; this is a characterization of what it says today",
        )
    }

    /**
     * The manifest publishes no such key for that set either.
     *
     * Two writers, one fact, and this file pins both from the start: the
     * session document is serialised by kotlinx and the manifest is assembled
     * as text by a different function, and a marker wired into one of them
     * publishes half a record. Which half a coach sees depends on which file
     * they opened.
     */
    @Test
    fun `the manifest publishes no such key for a set with no mark`() = runTest {
        val set = manifestSet()
        for (candidate in listOf("voided", "voidReason", "performed", "excluded")) {
            assertFalse(candidate in set, "the manifest already carries $candidate")
        }
        assertEquals(0, set.getValue("duration_s").jsonPrimitive.content.toInt())
        assertTrue(set.getValue("failed").jsonPrimitive.content.toBoolean())
    }

    // ---- differentials: what a voided set must publish (#60) ---------------

    /**
     * The session document says the set was not performed, and why.
     *
     * DIFFERENTIAL. Fails at the commit that introduces it: `SetExport`
     * carries no such key, so the document a coach reads is byte for byte the
     * one the characterization above pins -- a 20 second prescription, a 0
     * second result and a failure, with nothing saying it never happened.
     *
     * The reason is published BESIDE the mark and never inside it, the way
     * `limiterNote` sits beside `limiter`: a reader grouping unperformed sets
     * needs a boolean it can filter on, and a free string in that position
     * would destroy exactly that.
     */
    @Test
    fun `a voided set publishes the mark and the reason in the session document`() = runTest {
        val set = setObject(voided = true, voidReason = "app re-armed the finished slot")
        assertTrue(
            set["voided"]?.jsonPrimitive?.content?.toBoolean() == true,
            "the session document does not say the set was not performed: ${set.keys}",
        )
        assertEquals("app re-armed the finished slot", set["voidReason"]?.jsonPrimitive?.content)
    }

    /**
     * A voided set is published WITH its figures rather than withheld.
     *
     * Dropping the set would make the export disagree with the app's own
     * history and would make a set that was recorded and not performed
     * indistinguishable from one that was never recorded -- a gap this
     * document cannot represent. So every figure the characterization pinned
     * is still there, and the mark is what tells a reader how to read them.
     */
    @Test
    fun `a voided set is still published, with the figures it always had`() = runTest {
        val set = setObject(voided = true, voidReason = "not performed")
        assertEquals(0, set.getValue("duration_s").jsonPrimitive.content.toInt())
        assertEquals(20, set.getValue("plannedDuration_s").jsonPrimitive.content.toInt())
        assertTrue(set.getValue("failed").jsonPrimitive.content.toBoolean())
    }

    /**
     * A voided set carrying no reason publishes the mark alone.
     *
     * Absence is a state. The reason is optional -- a lifter who knows the set
     * did not happen has nothing more to say about it -- and a default string
     * here would be words nobody wrote, published under their name.
     */
    @Test
    fun `a voided set with no reason publishes the mark and no reason key`() = runTest {
        val set = setObject(voided = true, voidReason = null)
        assertTrue(set["voided"]?.jsonPrimitive?.content?.toBoolean() == true)
        assertNull(set["voidReason"], "a set nobody gave a reason for published one")
    }

    /**
     * The raw archive's manifest carries both, not just the session document.
     *
     * Two writers, one fact. The session document is serialised by kotlinx and
     * this manifest is assembled as TEXT by a different function; `added`,
     * `warmup`, `limiter` and `plannedSide` each had to be written into both,
     * and a change wired into one publishes half a record -- which half
     * depending on which file the coach opened.
     */
    @Test
    fun `the raw archive's manifest publishes the mark and the reason too`() = runTest {
        val set = manifestSet(voided = true, voidReason = "app re-armed the finished slot")
        assertTrue(
            set["voided"]?.jsonPrimitive?.content?.toBoolean() == true,
            "the archive's manifest does not say the set was not performed: ${set.keys}",
        )
        assertEquals("app re-armed the finished slot", set["voidReason"]?.jsonPrimitive?.content)
    }

    /**
     * Voiding keeps the raw streams. This is the assertion that separates the
     * change from a deletion.
     *
     * The gzipped IMU, heart-rate and cue streams are the only copy of what
     * the sensors saw, and on a voided set they are the evidence for why it
     * was voided. A fix that dropped the set from the archive would be
     * unrecoverable in exactly the way #60 exists to avoid.
     */
    @Test
    fun `voiding keeps the set's raw streams in the archive`() = runTest {
        val kept = archive(voided = true, voidReason = "not performed", streams = listOf(imuStream()))
        val csvs = kept.keys.filter { it.endsWith(".csv") }
        assertTrue(csvs.isNotEmpty(), "the voided set's streams left the archive: ${kept.keys}")
    }

    /**
     * An unvoided set publishes neither key, in either writer.
     *
     * Kept for what it will catch rather than for what it proves now: a fix
     * that published `voided: false` on every set would change the bytes of
     * every export ever taken for no gain, and would make absence -- which on
     * a pre-v16 row means "the app could not ask" -- read as an answer.
     */
    @Test
    fun `an unvoided set publishes neither key in either writer`() = runTest {
        assertNull(setObject()["voided"], "an unvoided set published a mark")
        assertNull(setObject()["voidReason"], "an unvoided set published a reason")
        assertFalse("voided" in manifestSet(), "the manifest published a mark on an unvoided set")
        assertFalse("voidReason" in manifestSet(), "the manifest published a reason on an unvoided set")
    }
}
