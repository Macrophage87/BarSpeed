package com.macrophage.barspeed.data

import com.macrophage.barspeed.dsp.RepAnalysis
import com.macrophage.barspeed.dsp.SetAnalysis
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What [SessionRepository.recordSet] writes for a set the SENSOR counted
 * (#286).
 *
 * A file of its own rather than four more methods in
 * [SessionRepositoryRecordSetTest]: that class is measured at detekt's
 * `LargeClass` limit and adding these to it reds `:core:data:detekt` before a
 * single test runs.
 *
 * ## The precedence being pinned
 *
 * `actualReps = manualReps ?: liveReps ?: analysis.reps.size`, and the order is
 * the TRUST order rather than an accident: a count a person stated wins, then
 * the count the sensor called while the lifter was watching it, then the batch
 * segmenter's figure taken after the set from the archived stream.
 *
 * The analysis fixture here resolves THREE reps deliberately, so a live count
 * read as absence falls through to 3 and the assertion says which figure
 * landed. That is the *absence rendered as a value* class in the direction that
 * matters: a sensor-counted set whose detector called nothing must record 0,
 * not the batch figure.
 *
 * Nothing here executes Room, SQLite or Android. The DAOs are interfaces and
 * these fakes stand in for them; what is verified is the repository's own
 * mapping, and nothing about what the database did with it.
 */
class SessionRepositoryLiveRepsTest {
    private class FakeSessionDao : SessionDao {
        val sets = mutableListOf<SetRecordEntity>()

        override suspend fun insertSession(session: SessionEntity): Long = 1L

        override suspend fun updateSession(session: SessionEntity) = Unit

        override suspend fun insertSet(set: SetRecordEntity): Long {
            sets += set
            return 7L
        }

        override suspend fun insertRawStream(stream: RawStreamEntity): Long = 1L

        override fun observeSessions(): Flow<List<SessionEntity>> = flowOf(emptyList())

        override suspend fun sessionById(id: Long): SessionEntity? = null

        override fun observeSession(id: Long): Flow<SessionEntity?> = flowOf(null)

        override suspend fun setsForSession(sessionId: Long): List<SetRecordEntity> = emptyList()

        override fun observeSetsForSession(sessionId: Long): Flow<List<SetRecordEntity>> = flowOf(emptyList())

        override suspend fun rawStreamsForSet(setId: Long): List<RawStreamEntity> = emptyList()

        override suspend fun updateRpe(
            setId: Long,
            rpe: Int?,
            failed: Boolean,
            failedByLifter: Boolean?,
            warmup: Boolean,
        ) = Unit

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

    /** Three reps, so a live count read as absence is visible as a 3. */
    private val threeReps =
        SetAnalysis(
            reps = (1..3).map { i ->
                RepAnalysis(
                    index = i,
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
                )
            },
            sampleRateHz = 99.4,
            velocityLossPct = null,
            tempoCompliance = null,
            verdicts = emptyList(),
        )

    private fun completedSet(manualReps: Int? = null, liveReps: Int? = null) = CompletedSet(
        exerciseId = "deadlift",
        exerciseName = "Deadlift",
        loadKg = 120.0,
        plannedLoadKg = 120.0,
        plannedReps = 5,
        manualReps = manualReps,
        liveReps = liveReps,
        tempo = null,
        targetMeanConVelMps = null,
        velocityLossStopPct = null,
        plannedRestS = 180,
        plannedPrepS = null,
        prepS = null,
        startedAtMs = 1_000L,
        endedAtMs = 61_000L,
        analysis = threeReps,
        imuSamples = emptyList(),
        hrSamples = emptyList(),
    )

    private suspend fun rowFor(manualReps: Int? = null, liveReps: Int? = null): SetRecordEntity {
        val dao = FakeSessionDao()
        SessionRepository(dao, FakeExerciseDao())
            .recordSet(sessionId = 1L, orderIdx = 0, set = completedSet(manualReps, liveReps))
        return dao.sets.single()
    }

    /** The sensor's live count is what a sensor-counted set is recorded as. */
    @Test
    fun `a live count is the recorded count and is not flagged as the lifter's`() = runTest {
        val row = rowFor(liveReps = 5)
        assertEquals(5, row.actualReps, "the live count did not reach actualReps")
        assertEquals(false, row.repsManual, "a sensor count was flagged as the lifter's")
        assertEquals(5, row.liveReps)
    }

    /**
     * A live count of zero is recorded as zero.
     *
     * With the batch analysis resolving three, reading the zero as absence
     * would publish 3 for a set whose detector called nothing -- and the
     * export would then read `analysis` where the sensor had in fact counted.
     */
    @Test
    fun `a live count of zero is recorded rather than falling through to the analysis`() = runTest {
        val row = rowFor(liveReps = 0)
        assertEquals(0, row.actualReps)
        assertEquals(0, row.liveReps)
        assertEquals(false, row.repsManual)
    }

    /**
     * A stated count wins, and what it corrected survives beside it.
     *
     * The pair is what the export reads as `corrected`, and it is what a hand
     * count is scored against afterwards on the sets where the detector was
     * wrong.
     */
    @Test
    fun `a stated count wins over a live one and the live one survives beside it`() = runTest {
        val row = rowFor(manualReps = 6, liveReps = 5)
        assertEquals(6, row.actualReps)
        assertEquals(true, row.repsManual)
        assertEquals(5, row.liveReps, "the correction erased what the sensor counted")
    }

    /** With neither figure the batch segmenter's count is stored, as it always was. */
    @Test
    fun `neither figure leaves the batch count and no live figure at all`() = runTest {
        val row = rowFor()
        assertEquals(3, row.actualReps)
        assertEquals(false, row.repsManual)
        assertNull(row.liveReps, "a set with no live counter claims a live count")
    }
}
