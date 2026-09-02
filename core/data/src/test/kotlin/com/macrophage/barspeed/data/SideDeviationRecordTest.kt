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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A set worked on the arm the plan did not ask for, from the write all the way
 * to both published documents (#215, #144).
 *
 * DIFFERENTIALS. The three that read a prescription fail at the commit that
 * introduces them: `set_records` has no `plannedSide` column, the repository
 * passes nothing into one, and neither export writer publishes it. Until then
 * a lifter who swapped arm order was recorded as having done exactly what the
 * plan said, and #144's whole point is that no reading of the row could tell.
 *
 * BOTH WRITERS, for [SessionExportWarmupMarkTest]'s reason: the session
 * document is serialised from a data class and the raw archive's manifest is
 * assembled as text, in a different function, so a key added to one is not
 * added to the other.
 *
 * THE ROW IS BUILT BY THE REPOSITORY, never by this file. A test that
 * constructed a `SetRecordEntity` with the new column would not compile before
 * the column exists, and one that constructed it afterwards would prove only
 * that the exporter can read a field the test itself filled in. Everything
 * here goes in as a [CompletedSet] -- what the record flow actually hands over
 * -- and comes out as a document.
 *
 * Nothing here executes Room, SQLite or Android. The DAO is an interface and
 * this fake stands in for it; what the database would do with the column is
 * the emulator exercise's business, and the fix commit records that run.
 */
class SideDeviationRecordTest {
    // ---- fakes -------------------------------------------------------------

    /** Captures what the repository writes, then serves it back to the exporters. */
    private class FakeSessionDao : SessionDao {
        val sets = mutableListOf<SetRecordEntity>()
        private val session = SessionEntity(id = 1L, startedAtMs = 1_000L, endedAtMs = 41_000L)

        override suspend fun insertSession(session: SessionEntity): Long = 1L

        override suspend fun updateSession(session: SessionEntity) = Unit

        override suspend fun insertSet(set: SetRecordEntity): Long {
            sets += set.copy(id = sets.size + 1L)
            return sets.size.toLong()
        }

        override suspend fun insertRawStream(stream: RawStreamEntity): Long = 1L

        override fun observeSessions(): Flow<List<SessionEntity>> = flowOf(listOf(session))

        override suspend fun sessionById(id: Long): SessionEntity? = session.takeIf { it.id == id }

        override fun observeSession(id: Long): Flow<SessionEntity?> = flowOf(session)

        override suspend fun setsForSession(sessionId: Long): List<SetRecordEntity> = sets

        override fun observeSetsForSession(sessionId: Long): Flow<List<SetRecordEntity>> = flowOf(sets)

        override suspend fun rawStreamsForSet(setId: Long): List<RawStreamEntity> = emptyList()

        override suspend fun updateRpe(setId: Long, rpe: Int?, failed: Boolean, warmup: Boolean) = Unit

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

    private val noReps =
        SetAnalysis(
            reps = emptyList(),
            sampleRateHz = 100.0,
            velocityLossPct = null,
            tempoCompliance = null,
            verdicts = emptyList(),
        )

    private fun curlSet(side: String?, plannedSide: String?) = CompletedSet(
        exerciseId = "dumbbell_curl",
        exerciseName = "Dumbbell Curl",
        loadKg = 14.0,
        plannedLoadKg = 14.0,
        plannedReps = 10,
        side = side,
        plannedSide = plannedSide,
        tempo = null,
        targetMeanConVelMps = null,
        velocityLossStopPct = null,
        plannedRestS = 90,
        plannedPrepS = null,
        prepS = null,
        startedAtMs = 1_000L,
        endedAtMs = 41_000L,
        analysis = noReps,
        imuSamples = emptyList(),
        hrSamples = emptyList(),
    )

    /**
     * The prescribed side off the stored row, read by field NAME.
     *
     * Reflection, deliberately, and it is the one place in this file that uses
     * it: a typed `row.plannedSide` does not compile until the column exists,
     * so this assertion could not be written BEFORE the change it guards. Read
     * by name, it fails three ways -- no such field, the field left unwritten,
     * or the wrong value in it -- and only the last of those is a fix that got
     * halfway.
     */
    private fun prescribedSideOf(row: SetRecordEntity): String? {
        val field =
            SetRecordEntity::class.java.declaredFields.firstOrNull { it.name == "plannedSide" }
                ?: return null
        field.isAccessible = true
        return field.get(row) as String?
    }

    private fun repo(dao: SessionDao) = SessionRepository(dao, FakeExerciseDao())

    /** The one set of the SESSION DOCUMENT, written through the repository first. */
    private suspend fun setObject(side: String?, plannedSide: String?): JsonObject {
        val dao = FakeSessionDao()
        val repository = repo(dao)
        repository.recordSet(sessionId = 1L, orderIdx = 0, set = curlSet(side, plannedSide))
        val text = SessionExporter(repository, dispatcher = Dispatchers.Default)
            .exportJson(1L, includeRepDetail = false)!!
        return Json.parseToJsonElement(text)
            .jsonObject.getValue("exercises").jsonArray.single()
            .jsonObject.getValue("sets").jsonArray.single().jsonObject
    }

    /** The one set of the RAW ARCHIVE'S manifest, written through the repository first. */
    private suspend fun manifestSet(side: String?, plannedSide: String?): JsonObject {
        val dao = FakeSessionDao()
        val repository = repo(dao)
        repository.recordSet(sessionId = 1L, orderIdx = 0, set = curlSet(side, plannedSide))
        val bytes = RawExporter(repository, SessionExporter(repository), appVersion = "0.1.49").buildZip(1L)!!
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
     * The row keeps both facts: the arm worked and the arm prescribed.
     *
     * The load-bearing one. Everything the DSP derives can be recomputed from
     * the persisted capture; which limb moved cannot be recovered from
     * anything, so a set written without it is a set nobody can ever repair.
     */
    @Test
    fun `the row records the arm worked and the arm the plan asked for`() = runTest {
        val dao = FakeSessionDao()
        repo(dao).recordSet(sessionId = 1L, orderIdx = 0, set = curlSet(side = "right", plannedSide = "left"))

        val row = dao.sets.single()
        assertEquals("right", row.side, "the row does not carry the arm the lifter worked")
        assertEquals(
            "left",
            prescribedSideOf(row),
            "the row does not carry the arm the plan prescribed, so the deviation is unreadable",
        )
    }

    /** The session document publishes the pair. */
    @Test
    fun `the session document publishes the worked arm and the prescription`() = runTest {
        val set = setObject(side = "right", plannedSide = "left")

        assertEquals("right", set.getValue("side").jsonPrimitive.content)
        assertEquals(
            "left",
            set["plannedSide"]?.jsonPrimitive?.content,
            "the session document publishes no prescription beside the side",
        )
    }

    /** The archive's manifest publishes the pair too, from its own writer. */
    @Test
    fun `the archive manifest publishes the worked arm and the prescription`() = runTest {
        val set = manifestSet(side = "right", plannedSide = "left")

        assertEquals("right", set.getValue("side").jsonPrimitive.content)
        assertEquals(
            "left",
            set["plannedSide"]?.jsonPrimitive?.content,
            "the archive's manifest publishes no prescription beside the side",
        )
    }

    /**
     * A set worked exactly as prescribed still publishes both.
     *
     * The prescription is written whenever the plan made one, not only when
     * the lifter deviated. A pair published only on deviation would make
     * absence mean two different things at once -- "no prescription" and "no
     * deviation" -- and a reader could not count adherence at all.
     */
    @Test
    fun `an arm worked as prescribed still publishes the pair`() = runTest {
        val set = setObject(side = "left", plannedSide = "left")

        assertEquals("left", set.getValue("side").jsonPrimitive.content)
        assertEquals("left", set["plannedSide"]?.jsonPrimitive?.content)
    }

    /**
     * Bilateral work publishes neither key, and stores nothing in either
     * column.
     *
     * Absence stays absence: a set that used both limbs has no side and no
     * prescribed side, and a null written as a word would put a limb on it.
     */
    @Test
    fun `a bilateral set publishes no side at all`() = runTest {
        val dao = FakeSessionDao()
        repo(dao).recordSet(sessionId = 1L, orderIdx = 0, set = curlSet(side = null, plannedSide = null))
        val row = dao.sets.single()

        assertNull(row.side)
        assertNull(prescribedSideOf(row))

        val set = setObject(side = null, plannedSide = null)
        assertTrue("side" !in set, "a bilateral set published a side")
        assertTrue("plannedSide" !in set, "a bilateral set published a prescribed side")
    }
}
