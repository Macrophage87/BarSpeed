package com.macrophage.barspeed.data

import com.macrophage.barspeed.dsp.RepAnalysis
import com.macrophage.barspeed.dsp.SetAnalysis
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Schema 1.16 publishes exactly ONE pause per rep -- the turnaround the rep's
 * own two phases contain -- so on any given lift one of the two keys has no
 * value. It must be ABSENT from the wire, not written as
 * `"bottomPause_s": null`: the published schema declares both as `number`, so
 * an emitted null would be invalid against the app's own document, and the
 * ajv step cannot catch it because it only ever validates a hand-written
 * example, never app output.
 *
 * Separate from [SessionExporterTest] for the reason
 * [SessionExportVelocityLossTest] states: that class is already at the size
 * detekt's LargeClass rule allows and one more test pushes it over. Each test
 * file in this module carries its own fakes.
 *
 * Nothing here executes Room, SQLite or Android. [FakeSessionDao] implements
 * the [SessionDao] interface, so what is pinned is [SessionExporter]'s own
 * mapping and the document it emits -- never what the database did with it.
 *
 * What this pins is the emitted DOCUMENT, not either serializer setting, and
 * the mutation numbers are run rather than asserted. Flipping BOTH
 * `encodeDefaults` and `explicitNulls` on the exporter's `Json` reds this
 * test; flipping `encodeDefaults` alone does not, and flipping `explicitNulls`
 * alone does not, because a property whose default is null is dropped by
 * either setting on its own. So do not read it as a guard on those two lines
 * individually -- it is a guard on the wire form they happen to produce
 * together. Issue #93.
 */
class SessionExportPauseKeyTest {
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

        override suspend fun setsForSession(sessionId: Long): List<SetRecordEntity> =
            rows.filter { it.sessionId == sessionId }

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

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * A rep of a concentric-first lift under the 1.16 rule: its two phases meet
     * at the TOP, so it has a top turnaround and no bottom pause at all. Null
     * rather than 0.0 is the whole point -- an unmeasured interval is not a
     * short one.
     */
    private fun conFirstRep() = RepAnalysis(
        index = 0,
        eccS = 2.0,
        bottomPauseS = null,
        conS = 1.0,
        topPauseS = 1.0,
        meanConVelMps = 0.6,
        peakConVelMps = 0.9,
        meanEccVelMps = -0.25,
        peakEccVelMps = -0.4,
        romM = 0.5,
        peakPowerW = 800.0,
        meanConPowerW = 500.0,
    )

    /**
     * The same rep as the archive actually holds it: RECORDED before 1.16, so
     * its stored analysis carries both pause values, the bottom one measured
     * over the old interval that runs to the next drive.
     */
    private fun preRuleRep() = conFirstRep().copy(bottomPauseS = 0.4)

    private fun exporter(analysis: SetAnalysis): SessionExporter {
        val row =
            SetRecordEntity(
                id = 5L,
                sessionId = 1L,
                orderIdx = 0,
                exerciseId = "seated_leg_curl",
                exerciseName = "Seated Leg Curl",
                loadKg = 40.0,
                actualReps = 1,
                repsManual = true,
                plannedReps = 1,
                startedAtMs = 1_000L,
                endedAtMs = 61_000L,
                analysisJson = json.encodeToString(SetAnalysis.serializer(), analysis),
            )
        val dao =
            FakeSessionDao(
                session = SessionEntity(id = 1L, startedAtMs = 1_000L, endedAtMs = 61_000L),
                rows = listOf(row),
            )
        return SessionExporter(SessionRepository(dao, FakeExerciseDao()))
    }

    @Test
    fun `the pause a rep does not have is omitted from the JSON, not written as null`() = runTest {
        val analysis =
            SetAnalysis(
                reps = listOf(conFirstRep()),
                sampleRateHz = 100.0,
                velocityLossPct = null,
                tempoCompliance = null,
                verdicts = emptyList(),
            )
        val text = exporter(analysis).exportJson(1L, includeRepDetail = true)!!
        assertTrue("\"topPause_s\"" in text, "the pause the rep HAS must be published")
        assertTrue("bottomPause_s" !in text, "expected the absent pause key to be gone entirely")
        assertTrue("null" !in text, "the exporter must never write a null literal")
    }

    /**
     * The retroactivity claim, which is the load-bearing sentence in all three
     * published copies of the 1.16 contract -- the schema description,
     * [com.macrophage.barspeed.model.SessionExport]'s log and `PLAN_PROMPT`.
     * `repMetrics` is built at export time but COPIED from the analysis frozen
     * into the row at record time, so a set recorded before 1.16 keeps both
     * keys inside a document declaring 1.16. A reader is told to treat a rep
     * carrying both as pre-1.16 data; if the exporter ever started dropping
     * one of them from such a row, that instruction would send the reader
     * looking for a marker the document no longer has. Nothing else detects
     * it.
     */
    @Test
    fun `a set recorded before 1_16 still publishes both pause keys under 1_16`() = runTest {
        val analysis =
            SetAnalysis(
                reps = listOf(preRuleRep()),
                sampleRateHz = 100.0,
                velocityLossPct = null,
                tempoCompliance = null,
                verdicts = emptyList(),
            )
        val text = exporter(analysis).exportJson(1L, includeRepDetail = true)!!
        assertTrue("\"schemaVersion\": \"1.16\"" in text, "the document must declare 1.16")
        assertTrue("\"bottomPause_s\": 0.4" in text, "a pre-1.16 row's bottom pause must survive export")
        assertTrue("\"topPause_s\": 1.0" in text, "a pre-1.16 row's top pause must survive export")
    }
}
