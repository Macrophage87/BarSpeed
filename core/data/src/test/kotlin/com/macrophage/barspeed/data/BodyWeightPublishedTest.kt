package com.macrophage.barspeed.data

import com.macrophage.barspeed.dsp.RepAnalysis
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
 * Differentials for the publication half of #220: the row now holds the body
 * weight its load was computed with, and neither export writer says so.
 *
 * FOUR OF THE SEVEN FAIL WHEN THEY ARE WRITTEN. `SetRecordEntity.bodyWeightKg`
 * exists and is filled from the commit before this one, `SetExport.bodyWeightKg`
 * is introduced here with no writer, and neither `SessionExporter` nor
 * `RawExporter` has a line for it -- so a reader still meets `load_kg: 30.25`
 * on an assisted pull-up with no way to know that 116.43 kg of it was the
 * lifter.
 *
 * THE FIXTURE IS FIELD-37 SET 8'S PUBLISHED SHAPE, read from
 * `BarSpeed-field-captures/field-37/extracted/session.json` and its
 * `meta.json` (app 0.1.48, export 1.16): `assisted_pull_up`, `load_kg`
 * 30.25, 5 reps against 8 planned, failed, `repsManual` true. Its body weight
 * is NOT read from that archive, because the archive does not contain one --
 * 116.43 is the issue's inversion from float identities (116.43 - 86.18 ==
 * 30.25 exactly), and it is used here as a plausible figure for a column the
 * app now stores, not as a measurement anything published.
 *
 * BOTH WRITERS, for [SessionExportAbandonedSetTest]'s reason: the session
 * document is serialised and the archive's manifest is assembled as text, in a
 * different function, and a change wired into one of them publishes half a
 * record.
 *
 * THE THREE THAT PASS HERE are kept for what they catch later. A loaded set
 * must publish no body weight at all -- there is no body in its load path, and
 * a key there would invite a reader to subtract it; a row written before v17
 * must publish nothing either, since the app never stored one; and `load_kg`
 * itself must go on being published unchanged, because the sum is what every
 * existing reader reads.
 *
 * Nothing here executes Room, SQLite or Android.
 */
class BodyWeightPublishedTest {
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

    /** Field-37 set 8's published shape, with the stored body weight varied. */
    private fun row(bodyWeightKg: Double?, loadKg: Double = 30.25) = SetRecordEntity(
        id = 8L,
        sessionId = 1L,
        orderIdx = 7,
        exerciseId = "assisted_pull_up",
        exerciseName = "Assisted Pull-up",
        loadKg = loadKg,
        bodyWeightKg = bodyWeightKg,
        actualReps = 5,
        repsManual = true,
        plannedReps = 8,
        failed = true,
        workBegan = true,
        startedAtMs = 1_788_342_174_823L,
        endedAtMs = 1_788_342_220_675L,
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
     * RED. The case the issue exists for: the largest term in the recorded
     * load reaches the session document.
     */
    @Test
    fun `a body-weight set publishes the body weight its load was computed with`() = runTest {
        val set = setObject(row(bodyWeightKg = 116.43))
        assertEquals(
            116.43,
            set["bodyWeight_kg"]?.jsonPrimitive?.content?.toDouble(),
            "the body weight the load arithmetic used is still published nowhere",
        )
    }

    /**
     * RED. The manifest says it too. It is the document a reader who opens the
     * CSVs alone has, and field-37's `meta.json` published `load_kg: 30.25`
     * with no term beside it.
     */
    @Test
    fun `the raw archive's manifest publishes the body weight too`() = runTest {
        assertEquals(
            116.43,
            manifestSet(row(bodyWeightKg = 116.43))["bodyWeight_kg"]?.jsonPrimitive?.content?.toDouble(),
            "the archive's manifest still carries only the sum",
        )
    }

    /**
     * RED. The added load is recoverable from the pair, which is the whole
     * point of publishing one and not both.
     *
     * Field-37 set 8 is the plan's -190 lb assistance written as -86.18 kg, and
     * `116.43 - 86.18 == 30.25` exactly in IEEE double. Subtracting back is
     * exact for this fixture; it is a subtraction of two published doubles in
     * general, so a reader gets the added load to within rounding rather than
     * bit-exactly, and nothing here claims more than that.
     */
    @Test
    fun `the assistance is recoverable from the published pair`() = runTest {
        val set = setObject(row(bodyWeightKg = 116.43))
        val load = set.getValue("load_kg").jsonPrimitive.content.toDouble()
        val bodyWeight = set.getValue("bodyWeight_kg").jsonPrimitive.content.toDouble()
        assertEquals(-86.18, load - bodyWeight, 1e-9, "the pair does not reconstruct the plan's assistance")
    }

    /**
     * RED. The sum goes on being published unchanged beside it.
     *
     * Nothing about `load_kg` moves: it is what every existing reader reads,
     * and this key explains it rather than replacing it.
     */
    @Test
    fun `the recorded load is unchanged beside the new key`() = runTest {
        val set = setObject(row(bodyWeightKg = 116.43))
        assertEquals(30.25, set.getValue("load_kg").jsonPrimitive.content.toDouble(), "load_kg moved")
        assertTrue("load_lb" in set, "load_lb stopped being published")
    }

    /**
     * GREEN at the introducing commit, and marked as such. Loaded work has no
     * body in the load path, so there is no term to publish and a key here
     * would invite a reader to subtract one.
     */
    @Test
    fun `a loaded set publishes no body weight`() = runTest {
        assertFalse(
            "bodyWeight_kg" in setObject(row(bodyWeightKg = null, loadKg = 47.6)),
            "a loaded set published a body-weight term it never had",
        )
    }

    /**
     * GREEN, and the whole historical corpus. A row written before database
     * v17 stored no body weight, and absence is the only honest answer: no
     * artifact records what the lifter weighed on a past date.
     */
    @Test
    fun `a row written before the column publishes nothing`() = runTest {
        assertFalse("bodyWeight_kg" in setObject(row(bodyWeightKg = null)), "a figure nobody stored was published")
    }

    /** GREEN. The manifest withholds it on the same two rows, for the same reasons. */
    @Test
    fun `the manifest publishes no body weight where the row holds none`() = runTest {
        assertFalse("bodyWeight_kg" in manifestSet(row(bodyWeightKg = null)), "the manifest invented a body weight")
    }
}
