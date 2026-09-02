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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Differentials for publishing the prep a set ran with.
 *
 * None of these passes when it is written. The columns exist and the row
 * carries them from the commit before this one, but the exporter never reads
 * them, so a capture records the prep and publishes nothing about it -- which
 * is the whole of the defect, because the export is the document the coach
 * reads and the row is not.
 *
 * That matters now rather than later. Once the prep is declarable, the seconds
 * between READY and the first movement cue are `LeadInPlan.PHRASE_S` on every
 * prep, so a 20 s strap-up and a 2 s cable set produce identical cue tracks;
 * `LeadInPlan`'s own KDoc says so, and names the consequence: from this version
 * on, prep length is not readable from the cue track. If it is not on the set
 * record and in the export, it is nowhere.
 *
 * The fixture declares 5 and plays 20 throughout. Equal values pass whichever
 * way round the two are wired, and a crossed pair is exactly how a key called
 * "planned" ends up holding what was played.
 *
 * Nothing here executes Room, SQLite or Android. The DAOs are interfaces, so
 * the fakes below stand in for them; what is verified is the exporter's own
 * mapping and nothing about what the database did with it. Each test file in
 * this module carries its own fakes, as [SessionExporterTest] and
 * [SessionRepositoryRecordSetTest] both do.
 */
class SessionExportPrepTest {
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

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * A set the sensor resolved nothing from. The prep is a property of the
     * prescription and the lifter, not of the samples, so the analysis is
     * deliberately empty: nothing here should depend on the DSP having measured
     * anything.
     */
    private val noReps =
        SetAnalysis(
            reps = emptyList(),
            sampleRateHz = 100.0,
            velocityLossPct = null,
            tempoCompliance = null,
            verdicts = emptyList(),
        )

    private fun row(plannedPrepS: Int?, prepS: Int?) = SetRecordEntity(
        id = 5L,
        sessionId = 1L,
        orderIdx = 0,
        exerciseId = "romanian_deadlift",
        exerciseName = "Romanian Deadlift",
        loadKg = 100.0,
        actualReps = 5,
        plannedReps = 5,
        tempo = "3-0-1-0",
        plannedPrepS = plannedPrepS,
        prepS = prepS,
        startedAtMs = 1_000L,
        endedAtMs = 61_000L,
        analysisJson = json.encodeToString(SetAnalysis.serializer(), noReps),
    )

    /** The one set of an export built over a row carrying the given prep pair. */
    private suspend fun setObject(plannedPrepS: Int?, prepS: Int?): JsonObject {
        val dao =
            FakeSessionDao(
                session = SessionEntity(id = 1L, startedAtMs = 1_000L, endedAtMs = 61_000L),
                rows = listOf(row(plannedPrepS, prepS)),
            )
        val exporter =
            SessionExporter(SessionRepository(dao, FakeExerciseDao()), dispatcher = Dispatchers.Default)
        val text = exporter.exportJson(1L, includeRepDetail = false)!!
        return Json.parseToJsonElement(text)
            .jsonObject.getValue("exercises").jsonArray.single()
            .jsonObject.getValue("sets").jsonArray.single().jsonObject
    }

    // ---- the pair ----------------------------------------------------------

    /**
     * Both halves reach the wire, under the names the document already uses for
     * a prescription and for what happened.
     *
     * `plannedPrep_s` / `prep_s` and not a single key, because the difference
     * between them IS the record of the lifter's adjustment -- one key holding
     * whichever value was played would publish the deviation as though it were
     * the plan, which is what issue #76 describes `rest_s` doing today.
     */
    @Test
    fun `the export publishes the prep prescribed and the prep played, unswapped`() = runTest {
        val set = setObject(plannedPrepS = 5, prepS = 20)
        assertEquals(5, set.getValue("plannedPrep_s").jsonPrimitive.content.toInt())
        assertEquals(20, set.getValue("prep_s").jsonPrimitive.content.toInt())
    }

    /**
     * A set that played no lead-in publishes neither key.
     *
     * Omission, not zero. Zero is the prep in which nothing is spoken before the
     * first stroke call, so a zero here would be indistinguishable from a set
     * that ran a real, silent prep -- and a reader averaging prep across a
     * session would count sets that never had one.
     */
    @Test
    fun `a set that played no lead-in publishes neither prep key`() = runTest {
        val set = setObject(plannedPrepS = null, prepS = null)
        assertFalse("plannedPrep_s" in set, "an unguided set published a planned prep it never had")
        assertFalse("prep_s" in set, "an unguided set published a prep it never played")
    }

    /**
     * A prep of zero is published as zero.
     *
     * The exporter writes with `encodeDefaults = false`, so a key whose value
     * equals its Kotlin default is dropped from the wire. A default of `0` would
     * therefore make the shortest real prep vanish and read as "not stated" --
     * the same defect [GeometryExport]'s KDoc records for its own booleans. A
     * default of null keeps 0 on the wire and keeps absence separate.
     */
    @Test
    fun `a prep of zero survives to the wire rather than being dropped as a default`() = runTest {
        val set = setObject(plannedPrepS = 0, prepS = 0)
        assertTrue("prep_s" in set, "a prep of zero was dropped from the export")
        assertEquals(0, set.getValue("plannedPrep_s").jsonPrimitive.content.toInt())
        assertEquals(0, set.getValue("prep_s").jsonPrimitive.content.toInt())
    }
}
