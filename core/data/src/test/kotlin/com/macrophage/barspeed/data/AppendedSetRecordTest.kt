package com.macrophage.barspeed.data

import com.macrophage.barspeed.dsp.SetAnalysis
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The `added` flag's three hand-written hops: [CompletedSet] to the row,
 * the row to `session.json`, and the row to the raw archive's `meta.json`
 * (#177).
 *
 * A FILE OF ITS OWN, AND NOT BY PREFERENCE. These belong beside their
 * neighbours in `SessionRepositoryRecordSetTest`, `SessionExporterTest` and
 * `RawExporterTest`, and that is where they were written first. All three of
 * those classes are within a few lines of detekt's `LargeClass` threshold, and
 * adding to any of them reds `:core:data:detekt` -- which is CI's FIRST step,
 * so the red-before-green evidence these tests exist to produce would never
 * have been recorded: the run would have died on a formatting rule before a
 * single test executed. `SessionExportSensorsTest`, `SessionExportPrepTest` and
 * `SessionRepositorySensorsTest` are the same split for the same reason, and
 * each carries its own fakes exactly as this does.
 *
 * Nothing here executes Room, SQLite or Android. The DAOs are interfaces and
 * the fake stands in for them, so what is verified is the mapping code's own
 * behaviour and nothing about what the database did with it. Whether SQLite
 * accepts `MIGRATION_11_12` is a bench question and is stated as one.
 *
 * WHY THE HOPS ARE PINNED SEPARATELY. Each is a hand-written field-by-field
 * copy, and each fails the same silent way: the flag is declared in the
 * published schema and present on every type it needs to be on, so a reader is
 * told to expect it -- and every set publishes nothing, because one copy in the
 * middle was never written. An end-to-end assertion would catch that too, but
 * would not say which hop dropped it.
 */
class AppendedSetRecordTest {
    private val json = Json { ignoreUnknownKeys = true }

    private class FakeSessionDao(
        private val session: SessionEntity = SessionEntity(id = 1L, startedAtMs = 1_000L, endedAtMs = 61_000L),
        private val rows: List<SetRecordEntity> = emptyList(),
    ) : SessionDao {
        val inserted = mutableListOf<SetRecordEntity>()

        override suspend fun insertSession(session: SessionEntity): Long = 1L

        override suspend fun updateSession(session: SessionEntity) = Unit

        override suspend fun insertSet(set: SetRecordEntity): Long {
            inserted += set
            return 7L
        }

        override suspend fun insertRawStream(stream: RawStreamEntity): Long = 1L

        override fun observeSessions(): Flow<List<SessionEntity>> = flowOf(listOf(session))

        override suspend fun sessionById(id: Long): SessionEntity? = session.takeIf { it.id == id }

        override fun observeSession(id: Long): Flow<SessionEntity?> = flowOf(session)

        override suspend fun setsForSession(sessionId: Long): List<SetRecordEntity> =
            rows.filter { it.sessionId == sessionId }

        override fun observeSetsForSession(sessionId: Long): Flow<List<SetRecordEntity>> = flowOf(rows)

        override suspend fun rawStreamsForSet(setId: Long): List<RawStreamEntity> = emptyList()

        override suspend fun updateRpe(setId: Long, rpe: Int?, failed: Boolean, warmup: Boolean) = Unit

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

    private val analysis = SetAnalysis(emptyList(), 0.0, null, null, listOf("Held on."))

    private fun completedSet(added: Boolean) = CompletedSet(
        exerciseId = "seated_overhead_press",
        exerciseName = "Seated Overhead Press",
        loadKg = 13.6,
        plannedLoadKg = if (added) null else 18.1,
        plannedReps = if (added) null else 8,
        tempo = null,
        targetMeanConVelMps = null,
        velocityLossStopPct = null,
        plannedRestS = null,
        plannedPrepS = null,
        prepS = null,
        startedAtMs = 1_000L,
        endedAtMs = 61_000L,
        analysis = analysis,
        imuSamples = emptyList(),
        hrSamples = emptyList(),
        added = added,
        // Each of the three set to a DIFFERENT value, because they are written
        // by one insert and the near neighbour of adding a flag is crossing it
        // with the one beside it. A fixture with all three true passes a
        // mapping that reads `warmup` into `added`.
        warmup = false,
        failed = true,
    )

    private fun row(added: Boolean) = SetRecordEntity(
        id = 5L,
        sessionId = 1L,
        orderIdx = 0,
        exerciseId = "seated_overhead_press",
        exerciseName = "Seated Overhead Press",
        loadKg = 13.6,
        actualReps = 8,
        startedAtMs = 1_000L,
        endedAtMs = 61_000L,
        analysisJson = json.encodeToString(SetAnalysis.serializer(), analysis),
        added = added,
    )

    private fun exporterOf(added: Boolean): SessionExporter =
        SessionExporter(SessionRepository(FakeSessionDao(rows = listOf(row(added))), FakeExerciseDao()))

    private suspend fun metaJson(added: Boolean): String {
        val repo = SessionRepository(FakeSessionDao(rows = listOf(row(added))), FakeExerciseDao())
        val bytes = RawExporter(repo, SessionExporter(repo), appVersion = "0.1.43").buildZip(1L)!!
        ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                if (entry.name == "meta.json") return zin.readBytes().decodeToString()
            }
        }
        error("the archive carries no meta.json")
    }

    // ---- hop 1: CompletedSet to the row -------------------------------------

    /**
     * RED before the fix. A set the lifter appended is stored as appended.
     *
     * Without this hop the flag exists on both types, is described in the
     * published schema, and is always false -- which is worse than not having
     * it, because a reader is told the app can distinguish the two and it
     * cannot.
     */
    @Test
    fun `a set the lifter appended is stored as appended`() = runTest {
        val dao = FakeSessionDao()
        SessionRepository(dao, FakeExerciseDao())
            .recordSet(sessionId = 1L, orderIdx = 0, set = completedSet(added = true))
        val stored = dao.inserted.single()
        assertEquals(true, stored.added, "the appended flag never reached the row")
        assertEquals(false, stored.warmup, "the appended flag was written into warmup")
        assertEquals(true, stored.failed)
    }

    /** Green both sides: a set the plan prescribed is stored unmarked. */
    @Test
    fun `a set the plan prescribed is stored unmarked`() = runTest {
        val dao = FakeSessionDao()
        SessionRepository(dao, FakeExerciseDao())
            .recordSet(sessionId = 1L, orderIdx = 0, set = completedSet(added = false))
        assertEquals(false, dao.inserted.single().added)
    }

    // ---- hop 2: the row to session.json --------------------------------------

    /**
     * RED before the fix. A row stored as appended publishes `added: true`.
     *
     * Both halves are asserted. The wire is the reader's fact; the [SetExport]
     * is where a mapping bug actually lives, and a wire-only assertion would go
     * on passing if `@EncodeDefault` were later added to paper over a missing
     * mapping by writing `false` onto every set of every export.
     */
    @Test
    fun `a row stored as appended publishes the appended flag`() = runTest {
        val exporter = exporterOf(added = true)
        val set = exporter.buildExport(1L, includeRepDetail = true)!!.exercises.single().sets.single()
        assertEquals(true, set.added, "the appended flag never reached the SetExport")
        val wire =
            Json.parseToJsonElement(exporter.exportJson(1L, includeRepDetail = true)!!)
                .jsonObject.getValue("exercises").jsonArray.single()
                .jsonObject.getValue("sets").jsonArray.single().jsonObject
        assertEquals("true", assertNotNull(wire["added"]).jsonPrimitive.content)
    }

    /**
     * Green both sides, and the reason `added` may never carry
     * `@EncodeDefault`: a prescribed set publishes no key at all, so every
     * export of every session already recorded is byte-for-byte what it was.
     */
    @Test
    fun `a prescribed set publishes no appended key`() = runTest {
        val wire =
            Json.parseToJsonElement(exporterOf(added = false).exportJson(1L, includeRepDetail = true)!!)
                .jsonObject.getValue("exercises").jsonArray.single()
                .jsonObject.getValue("sets").jsonArray.single().jsonObject
        assertNull(wire["added"])
    }

    // ---- hop 3: the row to the raw archive's manifest -------------------------

    /**
     * RED before the fix. A row stored as appended says so in the manifest.
     *
     * The archive has to stand on its own -- that is why `plannedPrep_s` and
     * the geometry block are duplicated into it -- and whether a set was
     * appended is exactly the kind of fact a reader holding only the CSVs and
     * `meta.json` can recover from nowhere else.
     */
    @Test
    fun `a row stored as appended says so in the manifest`() = runTest {
        val text = metaJson(added = true)
        val set = Json.parseToJsonElement(text).jsonObject.getValue("sets").jsonArray.single().jsonObject
        assertEquals(
            "true",
            assertNotNull(set["added"], "the appended flag never reached the manifest").jsonPrimitive.content,
        )
    }

    /**
     * Green both sides, and the manifest's own version of the omission rule:
     * written through `flag()`, so a false is absent rather than present as
     * `false`, and no archive of a past session changes a byte.
     */
    @Test
    fun `a prescribed set names no appended flag in the manifest`() = runTest {
        val text = metaJson(added = false)
        assertNull(Json.parseToJsonElement(text).jsonObject.getValue("sets").jsonArray.single().jsonObject["added"])
        assertEquals(false, "added" in text, "a prescribed set names the appended flag anyway:\n$text")
    }
}
