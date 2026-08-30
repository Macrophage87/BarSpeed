package com.macrophage.barspeed.data

import com.macrophage.barspeed.dsp.RepAnalysis
import com.macrophage.barspeed.dsp.SetAnalysis
import com.macrophage.barspeed.model.SessionExport
import com.macrophage.barspeed.model.SetExport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the export says about `velocityLoss_pct`, and where it gets the answer.
 *
 * Separate from [SessionExporterTest] because that class is already at the
 * size detekt's LargeClass rule allows and these tests would push it over --
 * not because they are a different subject. Each test file in this module
 * carries its own fakes, as [SessionRepositoryRecordSetTest] and
 * [SessionRepositoryEndSessionTest] both do.
 *
 * Nothing here executes Room, SQLite or Android. [FakeSessionDao] implements
 * the [SessionDao] interface, so what is pinned is [SessionExporter]'s own
 * mapping and the question it asks of a stored row -- never what the database
 * did with it.
 */
class SessionExportVelocityLossTest {
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

    private fun rep(index: Int, meanConVelMps: Double) = RepAnalysis(
        index = index,
        eccS = 2.0,
        bottomPauseS = 0.3,
        conS = 1.0,
        topPauseS = 0.4,
        meanConVelMps = meanConVelMps,
        peakConVelMps = meanConVelMps,
        meanEccVelMps = -0.25,
        peakEccVelMps = -0.4,
        romM = 0.5,
        peakPowerW = 800.0,
        meanConPowerW = 500.0,
    )

    /**
     * An analysis whose drive velocities are chosen, so the case the export has
     * to name can be picked.
     *
     * [storedVelocityLossPct] is what a row recorded before this rule existed
     * carries frozen in its `analysisJson`, and is deliberately allowed to
     * disagree with the reps beside it -- that disagreement is the whole
     * subject of the first test below.
     */
    private fun analysisOf(vararg meanConVelMps: Double, storedVelocityLossPct: Double?) = SetAnalysis(
        reps = meanConVelMps.mapIndexed { i, v -> rep(i, v) },
        sampleRateHz = 100.0,
        velocityLossPct = storedVelocityLossPct,
        tempoCompliance = null,
        verdicts = emptyList(),
    )

    private fun exporter(analysis: SetAnalysis): SessionExporter {
        val row =
            SetRecordEntity(
                id = 5L,
                sessionId = 1L,
                orderIdx = 0,
                exerciseId = "seated_leg_curl",
                exerciseName = "Seated Leg Curl",
                loadKg = 40.0,
                actualReps = 12,
                repsManual = true,
                plannedReps = 12,
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

    private suspend fun exportedSet(analysis: SetAnalysis): SetExport =
        exporter(analysis).buildExport(1L, includeRepDetail = false)!!
            .exercises.single().sets.single()

    /**
     * A set recorded before this rule existed carries `velocityLossPct = 0.0`
     * frozen into its stored `analysisJson`. The export re-asks the question of
     * that row's stored REPS rather than republishing the stored scalar.
     *
     * Without this, the change reaches only sets recorded after it ships, and
     * every session already on disk -- which is all of them -- goes on
     * publishing a green 0.0 on every re-export. The judgement is a pure
     * function of the rep list the row already holds, which is what makes it
     * answerable at export time at all: the same arrangement issue #83 and
     * schema 1.9 use for the heart-rate block.
     */
    @Test
    fun `an old stored analysis carrying zero is re-judged from its reps, not republished`() = runTest {
        // Last rep 0.50 is the fastest of the three, so this set is in the
        // withheld case however its stored column reads.
        val stored = analysisOf(0.40, 0.35, 0.50, storedVelocityLossPct = 0.0)
        val set = exportedSet(stored)
        assertNull(set.velocityLossPct, "the stored 0.0 must not be republished")
        assertEquals("terminalRepIsFastest", set.velocityLossBasis)
        val text = exporter(stored).exportJson(1L, includeRepDetail = false)!!
        assertTrue("velocityLoss_pct" !in text, "expected the key to be absent entirely, got:\n$text")
    }

    /**
     * A set whose reps do support a figure still publishes one, and publishes
     * it re-derived rather than passed through.
     *
     * Paired with the test above so "re-derived from the reps" cannot pass by
     * withholding everything. The stored column here says 99.9, which no rep
     * list in this file supports; what comes out is 16.7, computed from
     * 0.60 -> 0.50.
     */
    @Test
    fun `a set whose reps support a figure publishes the re-derived one`() = runTest {
        val set = exportedSet(analysisOf(0.60, 0.55, 0.50, storedVelocityLossPct = 99.9))
        assertEquals(16.7, set.velocityLossPct)
        assertEquals("measured", set.velocityLossBasis)
    }

    /**
     * `velocityLossBasis` names the case on every set that resolved reps, not
     * only on the ones where the figure is withheld.
     *
     * A key appearing only in the withheld cases would be indistinguishable
     * from a key an older app version never wrote, so a reader could not tell
     * an export that applied this rule from one that predates it -- and saying
     * which is the whole job of the key.
     */
    @Test
    fun `velocityLossBasis names the case on every set that resolved reps`() = runTest {
        val cases =
            listOf(
                analysisOf(0.60, 0.55, 0.50, storedVelocityLossPct = 16.7) to "measured",
                analysisOf(0.40, 0.35, 0.50, storedVelocityLossPct = 0.0) to "terminalRepIsFastest",
                analysisOf(0.40, storedVelocityLossPct = null) to "notEnoughReps",
                analysisOf(0.0, 0.0, storedVelocityLossPct = null) to "noReference",
            )
        for ((stored, expected) in cases) {
            assertEquals(expected, exportedSet(stored).velocityLossBasis, "basis for ${stored.reps.size} reps")
        }
        // Every published name is exercised above, so renaming one on the DSP
        // side without renaming it here reds this test rather than quietly
        // shipping a vocabulary the schema does not declare.
        assertEquals(SessionExport.VALID_VELOCITY_LOSS_BASES, cases.map { it.second }.toSet())
    }

    /**
     * Absent, not `notEnoughReps`, when the sensor resolved nothing at all.
     *
     * There is no rep list for the key to be a statement about, and this is the
     * same condition under which `repMetricsComplete` is absent. `explicitNulls
     * = false` means absent is the only way this exporter can say "not stated";
     * the wire form is asserted because that is what a coach opens.
     */
    @Test
    fun `a set with no segmented reps carries no basis at all`() = runTest {
        val none = analysisOf(storedVelocityLossPct = null)
        assertNull(exportedSet(none).velocityLossBasis)
        val text = exporter(none).exportJson(1L, includeRepDetail = false)!!
        assertTrue("velocityLossBasis" !in text, "expected the key to be absent entirely, got:\n$text")
    }
}
