package com.macrophage.barspeed.data

import com.macrophage.barspeed.dsp.SetAnalysis
import com.macrophage.barspeed.model.PrepWindow
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
import kotlin.test.assertTrue

/**
 * Where the raw archive says the set's still period was (#185).
 *
 * A separate file from [RawExporterTest], which is already 1,137 lines and
 * carries the exact-key-set pins for the manifest as a whole; this one asks a
 * single question of the same document, in the shape this module's other
 * one-question files are written in ([SessionExportPrepTest],
 * [SessionExportRepMarksTest]).
 *
 * The question is not "how long was the prep" -- the manifest has answered that
 * with `prep_s` since v10 of the database -- but WHEN it was, on the clock the
 * IMU rows are stamped with. field-34 measured what its absence costs: three of
 * four streams in the first dual-sensor capture carried no derivable stationary
 * window, because a reader of the archive has to guess where the bar stopped
 * being handled and where the set began.
 *
 * Nothing here executes Room, SQLite or Android. The DAO is an interface and
 * the fakes below stand in for it; what is verified is the exporter's own
 * mapping and nothing about what the database did with it.
 */
class RawExporterPrepWindowTest {
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

        override suspend fun updateRpe(
            setId: Long,
            rpe: Int?,
            failed: Boolean,
            failedByLifter: Boolean?,
            warmup: Boolean,
        ) = Unit

        override suspend fun updateLimiter(setId: Long, limiter: String?, limiterNote: String?) = Unit

        override suspend fun updateWarmupMark(setId: Long, warmupMark: Boolean?) = Unit

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

    // ---- fixtures ----------------------------------------------------------

    private val json = Json { encodeDefaults = true }

    private fun row(id: Long = 5L, startedAtMs: Long = 1_000L, endedAtMs: Long = 46_000L) = SetRecordEntity(
        id = id,
        sessionId = 1L,
        orderIdx = 0,
        exerciseId = "back_squat",
        exerciseName = "back_squat",
        loadKg = 100.0,
        actualReps = 5,
        prepS = 5,
        plannedPrepS = 5,
        startedAtMs = startedAtMs,
        endedAtMs = endedAtMs,
        analysisJson =
        json.encodeToString(SetAnalysis.serializer(), SetAnalysis(emptyList(), 0.0, null, null, emptyList())),
    )

    private suspend fun zipOf(
        rows: List<SetRecordEntity>,
        streams: Map<Long, List<RawStreamEntity>>,
    ): Map<String, String> {
        val dao =
            FakeSessionDao(
                session = SessionEntity(id = 1L, startedAtMs = 1_000L, endedAtMs = 46_000L),
                rows = rows,
                streams = streams,
            )
        val repo = SessionRepository(dao, FakeExerciseDao())
        val bytes = RawExporter(repo, SessionExporter(repo), appVersion = "0.1.48").buildZip(1L)!!
        val entries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                entries[entry.name] = zin.readBytes().decodeToString()
            }
        }
        return entries
    }

    private suspend fun meta(rows: List<SetRecordEntity>, streams: Map<Long, List<RawStreamEntity>>): JsonObject =
        Json.parseToJsonElement(zipOf(rows, streams).getValue("meta.json")).jsonObject

    private fun JsonObject.set(index: Int): JsonObject = getValue("sets").jsonArray[index].jsonObject

    private fun JsonObject.num(key: String): Double? = get(key)?.jsonPrimitive?.content?.toDouble()

    private fun JsonObject.text(key: String): String? = get(key)?.jsonPrimitive?.content

    private fun prepStream(setId: Long, window: PrepWindow) = RawStreamEntity(
        id = 9L,
        setId = setId,
        kind = RawStreamEntity.KIND_PREP,
        csvGzip = Gzip.compress(PrepWindowCsv.encode(window)),
    )

    // ---- characterization --------------------------------------------------

    /**
     * `startedAt_ms` is the row's own instant, and it is the OPENING half of
     * the still window.
     *
     * Pinned before anything reads it that way. `beginSet` takes this instant
     * before it opens the set journal, so the prep -- and the stationary
     * period inside it -- begins here; a change that made this key mean the
     * set's clock instead would move the window's start without moving the
     * key, and every reader would go on believing it.
     */
    @Test
    fun `the descriptor's startedAt_ms is the instant the set began recording`() = runTest {
        assertEquals(
            7_777.0,
            meta(listOf(row(startedAtMs = 7_777L, endedAtMs = 60_000L)), emptyMap()).set(0).num("startedAt_ms"),
            "startedAt_ms is not the row's own start instant",
        )
    }

    /**
     * A set with no stored window says nothing about where its work began.
     *
     * Every set already on disk is such a set, and so is every set recorded
     * with no prep at all. Absence must stay absence here: an invented instant
     * would be indistinguishable from a measured one, and the whole reason
     * #185 exists is that an analysis currently has to GUESS this boundary
     * from the samples.
     */
    @Test
    fun `a set with no stored window publishes no work-start key`() = runTest {
        val set = meta(listOf(row()), emptyMap()).set(0)
        assertTrue("workStartedAt_ms" !in set, "a set with no stored prep window published a work-start instant")
        assertTrue("prepStartedAt_ms" !in set, "a set with no stored prep window published a prep-start instant")
    }

    // ---- differentials -----------------------------------------------------

    /**
     * The manifest brackets the set's prep, on the clock the IMU rows carry.
     *
     * Epoch milliseconds and not offsets, because that is what `timestamp_ms`
     * in the CSV is and what `startedAt_ms` beside it already is: an offset
     * would need a base instant, and the manifest's only top-level one is the
     * SESSION's start, not this set's. A reader who opens the zip and no other
     * document can select the rows of `imu.csv` that fall in the window and
     * read a gravity vector out of them.
     *
     * Both halves, and unequal to `startedAt_ms`'s neighbour keys, so a
     * descriptor that published one instant twice cannot pass.
     */
    @Test
    fun `the manifest brackets the prep window in epoch milliseconds`() = runTest {
        val window = PrepWindow(startedAtMs = 1_000L, workStartedAtMs = 6_000L)
        val set = meta(listOf(row()), mapOf(5L to listOf(prepStream(5L, window)))).set(0)
        assertEquals(1_000.0, set.num("prepStartedAt_ms"), "the manifest lost where the prep began")
        assertEquals(6_000.0, set.num("workStartedAt_ms"), "the manifest lost where the set's work began")
    }

    /**
     * The window is in the archive as a file too, and the manifest says how to
     * read it.
     *
     * The zip half needs no exporter change -- every stream is written as
     * `set%02d_<exercise>_<kind>.csv` -- but the manifest publishes a header
     * per format it can contain, and a file whose column layout is stated
     * nowhere is a file a reader has to guess at. A fifth format arriving
     * without a `csvHeader*` key is the near neighbour this pins.
     */
    @Test
    fun `the archive carries the window as a file the manifest describes`() = runTest {
        val window = PrepWindow(startedAtMs = 1_000L, workStartedAtMs = 6_000L)
        val entries = zipOf(listOf(row()), mapOf(5L to listOf(prepStream(5L, window))))
        assertEquals(window, PrepWindowCsv.decode(entries.getValue("set01_back_squat_prep.csv")))
        val manifest = Json.parseToJsonElement(entries.getValue("meta.json")).jsonObject
        assertEquals(PrepWindowCsv.HEADER, manifest.text("csvHeaderPrep"))
        assertEquals(
            listOf("set01_back_squat_prep.csv"),
            manifest.set(0).getValue("files").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    /**
     * A prep file that will not read publishes no window rather than half of
     * one.
     *
     * The archive is assembled from whatever survived, and a truncated row is
     * exactly what a process killed mid-write leaves. The set's other keys must
     * still be there: a manifest is not worth failing an export over, which is
     * the shape every other figure read from a stream in this document uses.
     */
    @Test
    fun `a prep stream that will not parse publishes no window`() = runTest {
        val broken =
            RawStreamEntity(
                id = 9L,
                setId = 5L,
                kind = RawStreamEntity.KIND_PREP,
                csvGzip =
                Gzip.compress(
                    """
                    prep_started_ms,work_started_ms
                    1000
                    """.trimIndent(),
                ),
            )
        val set = meta(listOf(row()), mapOf(5L to listOf(broken))).set(0)
        assertTrue("prepStartedAt_ms" !in set, "a half-written window was published anyway")
        assertTrue("workStartedAt_ms" !in set, "a half-written window was published anyway")
        assertEquals(1_000.0, set.num("startedAt_ms"), "an unreadable prep file cost the set its other keys")
    }
}
