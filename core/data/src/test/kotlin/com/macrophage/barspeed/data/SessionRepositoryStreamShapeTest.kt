package com.macrophage.barspeed.data

import com.macrophage.barspeed.dsp.RepAnalysis
import com.macrophage.barspeed.dsp.SetAnalysis
import com.macrophage.barspeed.model.HrSample
import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.VoiceCue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * How MANY raw streams a set writes, and which of them carries what.
 *
 * A separate file from `SessionRepositoryRecordSetTest`, which asserts the
 * CONTENT of each stream and is already at detekt's LargeClass limit. The split
 * is by question rather than by convenience: every assertion over there reaches
 * a stream through `stream(kind)`, a `firstOrNull`, so a second row of the same
 * kind is invisible to all of them -- and the count is exactly what a second
 * accelerometer changes (#156).
 *
 * Characterization at the commit that introduces it: everything here is green
 * against the one-sensor recorder, and exists so that the commits which add a
 * second stream have something to be measured against.
 *
 * Nothing here executes Room, SQLite or Android. The DAO is an interface and
 * this fake stands in for it, so what is verified is the repository's own
 * mapping and nothing about what the database did with it.
 */
class SessionRepositoryStreamShapeTest {
    /** Collects the rows the repository hands over, in the order it hands them over. */
    private class RecordingDao : SessionDao {
        val streams = mutableListOf<RawStreamEntity>()

        override suspend fun insertSession(session: SessionEntity): Long = 1L

        override suspend fun updateSession(session: SessionEntity) = Unit

        override suspend fun insertSet(set: SetRecordEntity): Long = 7L

        override suspend fun insertRawStream(stream: RawStreamEntity): Long {
            streams += stream
            return streams.size.toLong()
        }

        override fun observeSessions(): Flow<List<SessionEntity>> = flowOf(emptyList())

        override suspend fun sessionById(id: Long): SessionEntity? = null

        override fun observeSession(id: Long): Flow<SessionEntity?> = flowOf(null)

        override suspend fun setsForSession(sessionId: Long): List<SetRecordEntity> = emptyList()

        override fun observeSetsForSession(sessionId: Long): Flow<List<SetRecordEntity>> = flowOf(emptyList())

        override suspend fun rawStreamsForSet(setId: Long): List<RawStreamEntity> = emptyList()

        override suspend fun updateRpe(setId: Long, rpe: Int?, failed: Boolean, warmup: Boolean) = Unit

        override suspend fun updateLimiter(setId: Long, limiter: String?, limiterNote: String?) = Unit

        override suspend fun updateWarmupMark(setId: Long, warmupMark: Boolean?) = Unit

        override suspend fun overrideReps(setId: Long, reps: Int) = Unit

        override suspend fun overrideDuration(setId: Long, seconds: Int) = Unit

        override suspend fun sessionsInRange(fromMs: Long, toMs: Long): List<SessionEntity> = emptyList()

        override suspend fun deleteSession(id: Long) = Unit
    }

    private class StubExerciseDao : ExerciseDao {
        override suspend fun insert(exercise: CustomExerciseEntity) = Unit

        override fun observeAll(): Flow<List<CustomExerciseEntity>> = flowOf(emptyList())

        override suspend fun all(): List<CustomExerciseEntity> = emptyList()

        override suspend fun byId(id: String): CustomExerciseEntity? = null
    }

    private val imu =
        listOf(
            ImuSample(1_000L, 0.01, -0.02, 0.98, 1.5, -2.5, 0.25, 10.0, -20.0, 30.0),
            ImuSample(1_010L, 0.03, -0.04, 1.02, -1.5, 2.5, -0.25, 11.0, -21.0, 31.0),
        )

    private val hr = listOf(HrSample(1_000L, bpm = 120, rrIntervalsMs = listOf(500.0)))

    private val cues = listOf(VoiceCue(1_100L, "Rep 1"))

    private fun analysis(sampleRateHz: Double = 98.5) = SetAnalysis(
        reps = listOf(
            RepAnalysis(
                index = 0,
                eccS = 2.0,
                bottomPauseS = 0.5,
                conS = 1.0,
                topPauseS = 0.25,
                meanConVelMps = 0.55,
                peakConVelMps = 0.80,
                meanEccVelMps = -0.30,
                peakEccVelMps = -0.45,
                romM = 0.42,
                peakPowerW = 700.0,
            ),
        ),
        sampleRateHz = sampleRateHz,
        velocityLossPct = 12.5,
        tempoCompliance = null,
        verdicts = emptyList(),
    )

    private fun completedSet() = CompletedSet(
        exerciseId = "back_squat",
        exerciseName = "Back Squat",
        loadKg = 102.5,
        plannedLoadKg = 100.0,
        plannedReps = 5,
        tempo = null,
        targetMeanConVelMps = null,
        velocityLossStopPct = null,
        plannedRestS = null,
        plannedPrepS = null,
        prepS = null,
        startedAtMs = 1_000L,
        endedAtMs = 2_000L,
        analysis = analysis(),
        imuSamples = imu,
        hrSamples = hr,
        restHrSamples = hr,
        voiceCues = cues,
        repMarks = listOf(1_100L),
    )

    private suspend fun record(): RecordingDao {
        val dao = RecordingDao()
        SessionRepository(dao, StubExerciseDao()).recordSet(sessionId = 1L, orderIdx = 0, set = completedSet())
        return dao
    }

    /**
     * One IMU row per set, and one only.
     *
     * The count, not the content. Every other assertion about the IMU stream in
     * this package reaches it through a `firstOrNull`, which is exactly the
     * shape in which the exporter's own `when (stream.kind)` branch keeps only
     * the last IMU stream it saw while the archive still looks complete.
     */
    @Test
    fun `a set writes exactly one imu stream`() = runTest {
        assertEquals(
            1,
            record().streams.count { it.kind == RawStreamEntity.KIND_IMU },
            "more than one IMU row per set is invisible to every firstOrNull in this package",
        )
    }

    /**
     * The sample rate belongs to the IMU row and to nothing else.
     *
     * The figure is `analysis.sampleRateHz`, measured over the stream the DSP
     * analysed, so putting it on any other row labels one stream with another's
     * rate. A second IMU stream has no analysis of its own to take one from,
     * which is why this rule needs pinning before there is a second row.
     */
    @Test
    fun `only the imu row carries a sample rate`() = runTest {
        val dao = record()
        assertEquals(98.5, dao.streams.first { it.kind == RawStreamEntity.KIND_IMU }.sampleRateHz)
        assertEquals(
            emptyList(),
            dao.streams.filter { it.kind != RawStreamEntity.KIND_IMU && it.sampleRateHz != null }.map { it.kind },
            "a rate reached a row it was not measured from",
        )
    }

    /**
     * Every kind this set produced, once each, in the order the archive lists
     * them.
     *
     * Duplicated in spirit by `streams are written imu first, then heart rate,
     * then cues` and its neighbours, and kept anyway: those assert the order of
     * a subset each, and this is the whole list for one set that produced all
     * five. It is what a second IMU row has to be added to deliberately.
     */
    @Test
    fun `a one-sensor set writes one row of each kind it produced`() = runTest {
        assertEquals(
            listOf(
                RawStreamEntity.KIND_IMU,
                RawStreamEntity.KIND_HRM,
                RawStreamEntity.KIND_REST_BEFORE_HRM,
                RawStreamEntity.KIND_CUES,
                RawStreamEntity.KIND_REPS,
            ),
            record().streams.map { it.kind },
        )
    }
}
