package com.macrophage.barspeed.data

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
import kotlin.test.assertTrue

/**
 * Differentials for the lifter's own warm-up mark reaching the export (#194).
 *
 * SEVEN OF THE EIGHT FAILED WHEN THEY WERE WRITTEN, at 1264085 (CI run
 * 33315140693, conclusion failure). The `warmupMark` column existed and a row
 * carried it from d190aea; both export writers still read `warmup` alone,
 * which since #187 is the PLAN's declaration and nothing else. So a warm-up
 * added at the rack -- the #177 append, the case this issue exists for --
 * could be marked and still exported as a working set.
 *
 * The eighth passed there and is kept for what it will catch LATER: a
 * plan-declared warm-up nobody marked must go on publishing exactly what it
 * published before. That is the case #187 spent a change getting right, it is
 * every set on a declared plan, and a fix that composed the two facts the
 * wrong way round would move it.
 *
 * BOTH WRITERS AGAIN, for [SessionExportLimiterTest]'s reason: the session
 * document is serialised and the archive's manifest is assembled as text, in a
 * different function.
 *
 * Nothing here executes Room, SQLite or Android.
 */
class SessionExportWarmupMarkTest {
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

    private fun row(declared: Boolean, mark: Boolean?, rpe: Int? = null) = SetRecordEntity(
        id = 5L,
        sessionId = 1L,
        orderIdx = 0,
        exerciseId = "lat_pulldown",
        exerciseName = "Lat Pulldown",
        loadKg = 27.2,
        actualReps = 8,
        warmup = declared,
        warmupMark = mark,
        rpe = rpe,
        startedAtMs = 1_000L,
        endedAtMs = 41_000L,
        analysisJson = json.encodeToString(SetAnalysis.serializer(), noReps),
    )

    private fun repositoryFor(declared: Boolean, mark: Boolean?, rpe: Int?): SessionRepository {
        val session = SessionEntity(id = 1L, startedAtMs = 1_000L, endedAtMs = 41_000L)
        return SessionRepository(
            FakeSessionDao(session = session, rows = listOf(row(declared, mark, rpe))),
            FakeExerciseDao(),
        )
    }

    /** The one set of the SESSION DOCUMENT. */
    private suspend fun setObject(declared: Boolean, mark: Boolean?, rpe: Int? = null): JsonObject {
        val exporter = SessionExporter(repositoryFor(declared, mark, rpe), dispatcher = Dispatchers.Default)
        val text = exporter.exportJson(1L, includeRepDetail = false)!!
        return Json.parseToJsonElement(text)
            .jsonObject.getValue("exercises").jsonArray.single()
            .jsonObject.getValue("sets").jsonArray.single().jsonObject
    }

    /** The one set of the RAW ARCHIVE'S manifest. */
    private suspend fun manifestSet(declared: Boolean, mark: Boolean?): JsonObject {
        val repo = repositoryFor(declared, mark, null)
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
     * The case the issue exists for: a set no plan declared, that the lifter
     * marked at the rack.
     *
     * Since #187 the plan is the only producer of `warmup`, so at 1264085 (CI
     * run 33315140693, conclusion failure) this set exported indistinguishable
     * from a working set -- which corrupts volume and progression, the two
     * readings the flag was kept for.
     */
    @Test
    fun `a set the lifter marked publishes warmup though the plan declared nothing`() = runTest {
        val set = setObject(declared = false, mark = true)
        assertEquals(
            true,
            set["warmup"]?.jsonPrimitive?.content?.toBoolean(),
            "the lifter's mark never reached the wire",
        )
    }

    /**
     * The other direction, which is what makes the mark a statement rather
     * than a one-way switch: a set the plan called a warm-up and the lifter
     * says was not.
     */
    @Test
    fun `a plan warm-up the lifter unmarked publishes no warmup`() = runTest {
        assertFalse(
            "warmup" in setObject(declared = true, mark = false),
            "the lifter's denial was overridden by the plan",
        )
    }

    /**
     * Which of the two facts a document carries is stated, not left to be
     * guessed.
     *
     * Without it, a reader cannot tell a plan-declared ramp from one the
     * lifter improvised, and the plan's own declaration -- overwritten in the
     * published value by the mark -- would vanish with nothing saying it had.
     */
    @Test
    fun `a marked set says the answer is the lifter's own`() = runTest {
        val set = setObject(declared = false, mark = true)
        assertEquals(
            true,
            set["warmupByLifter"]?.jsonPrimitive?.content?.toBoolean(),
            "a marked set does not say the mark is the lifter's",
        )
        val denied = setObject(declared = true, mark = false)
        assertEquals(
            true,
            denied["warmupByLifter"]?.jsonPrimitive?.content?.toBoolean(),
            "an unmarked plan warm-up does not say the lifter spoke",
        )
    }

    /**
     * THIS ONE PASSES TODAY and is here to fail later.
     *
     * A plan-declared warm-up nobody marked is every set on a declared plan.
     * It must publish exactly what it published before #194, with no lifter
     * mark claimed on it: a composition the wrong way round would move the
     * case #187 spent a whole change getting right.
     */
    @Test
    fun `a plan warm-up nobody marked publishes what it always did`() = runTest {
        val set = setObject(declared = true, mark = null)
        assertEquals(true, set["warmup"]?.jsonPrimitive?.content?.toBoolean(), "a declared warm-up stopped publishing")
        assertFalse("warmupByLifter" in set, "a set nobody marked claims a lifter's mark")
        val plain = setObject(declared = false, mark = null)
        assertFalse("warmup" in plain, "a working set started publishing a warm-up")
        assertFalse("warmupByLifter" in plain, "a working set claims a lifter's mark")
    }

    /**
     * The archive's manifest carries the same answer as the session document.
     *
     * Two writers, one fact -- the near-neighbour class in the shape this
     * repository has already produced it in twice.
     */
    @Test
    fun `the raw archive's manifest carries the lifter's mark too`() = runTest {
        assertEquals(
            true,
            manifestSet(declared = false, mark = true)["warmup"]?.jsonPrimitive?.content?.toBoolean(),
            "the manifest publishes the plan's declaration and ignores the lifter",
        )
        assertFalse(
            "warmup" in manifestSet(declared = true, mark = false),
            "the manifest publishes a warm-up the lifter denied",
        )
    }

    /**
     * The mark COMPOSES with a rating and never occupies its slot.
     *
     * This is the requirement #194 states in as many words and the whole
     * principle #187 established: a warm-up set that felt hard is a real,
     * knowable fact, and the old effort tile threw it away by writing
     * `warmup = true` and `rpe = null` together. Marking a set at the rack
     * must not rebuild that coupling in a new place.
     */
    @Test
    fun `a marked warm-up still publishes the rating it was given`() = runTest {
        val set = setObject(declared = false, mark = true, rpe = 6)
        assertEquals(true, set["warmup"]?.jsonPrimitive?.content?.toBoolean(), "the mark did not reach the wire")
        assertEquals(6, set.getValue("rpe").jsonPrimitive.content.toInt(), "the mark ate the rating")
    }

    /**
     * A mark that agrees with the plan changes the published value not at all,
     * and still says the lifter spoke.
     *
     * The agreeing case is the one a composition built out of "did they
     * disagree" would get wrong, and it is reachable in one tap: mark, then
     * mark back.
     */
    @Test
    fun `a mark agreeing with the plan changes the value but not the attribution`() = runTest {
        val set = setObject(declared = true, mark = true)
        assertEquals(true, set["warmup"]?.jsonPrimitive?.content?.toBoolean(), "an agreeing mark dropped the warm-up")
        assertTrue(
            set["warmupByLifter"]?.jsonPrimitive?.content?.toBoolean() == true,
            "an agreeing mark is not reported as the lifter having spoken",
        )
    }

    /**
     * A working set the lifter marked and then unmarked publishes no warm-up,
     * and says so was the lifter's doing.
     */
    @Test
    fun `a working set the lifter marked back publishes no warmup but records the mark`() = runTest {
        val set = setObject(declared = false, mark = false)
        assertFalse("warmup" in set, "an unmarked working set published a warm-up")
        assertEquals(
            true,
            set["warmupByLifter"]?.jsonPrimitive?.content?.toBoolean(),
            "the lifter's own denial is not reported",
        )
    }
}
