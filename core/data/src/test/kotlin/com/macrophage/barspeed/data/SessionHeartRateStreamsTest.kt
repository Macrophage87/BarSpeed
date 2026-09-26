package com.macrophage.barspeed.data

import com.macrophage.barspeed.dsp.SetAnalysis
import com.macrophage.barspeed.model.HrSample
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which stored streams a session's derived HRV is read from, and when they
 * are read at all (#62 half (b)).
 *
 * GREEN WHEN WRITTEN, and deliberately so: [SessionRepository.storedHrWindows]
 * and [SessionRepository.sessionHeartRate] are new symbols that no reader
 * calls at the commit that adds them. The differential is the exporter's,
 * `SessionExportUnclosedHrvTest`.
 *
 * Nothing here executes Room, SQLite or Android. What is verified is the
 * repository's own selection and call shape, never what the database stored.
 */
class SessionHeartRateStreamsTest {
    private class FakeSessionDao(
        private val session: SessionEntity,
        private val rows: List<SetRecordEntity>,
        private val streams: Map<Long, List<RawStreamEntity>>,
    ) : SessionDao {
        var streamReads = 0

        override suspend fun insertSession(session: SessionEntity): Long = 1L

        override suspend fun updateSession(session: SessionEntity) = Unit

        override suspend fun insertSet(set: SetRecordEntity): Long = 1L

        override suspend fun insertRawStream(stream: RawStreamEntity): Long = 1L

        override fun observeSessions(): Flow<List<SessionEntity>> = flowOf(listOf(session))

        override suspend fun sessionById(id: Long): SessionEntity? = session.takeIf { it.id == id }

        override fun observeSession(id: Long): Flow<SessionEntity?> = flowOf(session)

        override suspend fun setsForSession(sessionId: Long): List<SetRecordEntity> = rows

        override fun observeSetsForSession(sessionId: Long): Flow<List<SetRecordEntity>> = flowOf(rows)

        override suspend fun rawStreamsForSet(setId: Long): List<RawStreamEntity> {
            streamReads++
            return streams[setId].orEmpty()
        }

        override suspend fun updateRpe(
            setId: Long,
            rpe: Int?,
            failed: Boolean,
            failedByLifter: Boolean?,
            warmup: Boolean,
        ) = Unit

        override suspend fun updateLimiter(setId: Long, limiter: String?, limiterNote: String?) = Unit

        override suspend fun updateWarmupMark(setId: Long, warmupMark: Boolean?) = Unit

        override suspend fun updateVoided(setId: Long, voided: Boolean, reason: String?) = Unit

        override suspend fun overrideReps(setId: Long, reps: Int) = Unit

        override suspend fun overrideLoad(setId: Long, loadKg: Double) = Unit

        override suspend fun overrideDuration(setId: Long, seconds: Int, endedBy: String?) = Unit

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

    private val noReps = SetAnalysis(emptyList(), 0.0, null, null, emptyList())

    private fun setRow(id: Long, orderIdx: Int) = SetRecordEntity(
        id = id,
        sessionId = 1L,
        orderIdx = orderIdx,
        exerciseId = "bench_press",
        exerciseName = "Bench Press",
        loadKg = 80.0,
        actualReps = 5,
        repsManual = true,
        plannedReps = 5,
        startedAtMs = 1_000L + orderIdx * 120_000L,
        endedAtMs = 61_000L + orderIdx * 120_000L,
        analysisJson = json.encodeToString(SetAnalysis.serializer(), noReps),
        hrAvgBpm = 120 + orderIdx * 20,
        hrMaxBpm = 150 + orderIdx * 15,
    )

    /** One notification per beat, each arriving one interval after the last. */
    private fun beats(startMs: Long, vararg rr: Double): List<HrSample> {
        var t = startMs
        return rr.map { interval ->
            t += interval.toLong()
            HrSample(timestampMs = t, bpm = 75, rrIntervalsMs = listOf(interval))
        }
    }

    private fun stream(setId: Long, kind: String, samples: List<HrSample>) =
        RawStreamEntity(setId = setId, kind = kind, csvGzip = Gzip.compress(HrCsv.encode(samples)))

    /** A set's rest window and its own stream, stored in that order. */
    private fun windows(setId: Long, rest: List<HrSample>, set: List<HrSample>) =
        listOf(stream(setId, RawStreamEntity.KIND_REST_BEFORE_HRM, rest), stream(setId, RawStreamEntity.KIND_HRM, set))

    private fun repository(
        session: SessionEntity,
        rows: List<SetRecordEntity>,
        streams: Map<Long, List<RawStreamEntity>>,
    ) = FakeSessionDao(session, rows, streams).let { it to SessionRepository(it, FakeExerciseDao()) }

    private val unclosed = SessionEntity(id = 1L, startedAtMs = 1_000L)

    // Two sets whose windows, joined in session order with the copied tail
    // counted once, are the beats 800 to 910 in steps of 10: eleven
    // differences of 10, an RMSSD of exactly 10.
    private val restOne = beats(0L, 800.0, 810.0, 820.0)
    private val setOne = beats(restOne.last().timestampMs, 830.0, 840.0, 850.0, 860.0)
    private val restTwo = setOne.subList(1, 4) + beats(setOne.last().timestampMs, 870.0, 880.0)
    private val setTwo = beats(restTwo.last().timestampMs, 890.0, 900.0, 910.0)

    /**
     * Each set's rest window, then its own stream, set by set -- whatever
     * order the streams come back in. Joined the other way round, a rest window
     * would follow the set it preceded.
     */
    @Test
    fun `the stored windows are each set's rest window then its set stream, in set order`() = runTest {
        val rows = listOf(setRow(5L, 0), setRow(6L, 1))
        val (_, repository) =
            repository(
                unclosed,
                rows,
                // Each set's own stream stored first, so the order read is
                // the rule's and not the order the streams come back in.
                mapOf(
                    5L to windows(5L, restOne, setOne).reversed(),
                    6L to windows(6L, restTwo, setTwo).reversed(),
                ),
            )

        assertEquals(listOf(restOne, setOne, restTwo, setTwo), repository.storedHrWindows(rows))
    }

    /**
     * Only the two kinds an unclosed session can have are windows. The
     * final rest is written only after the close, and a cue stream is not
     * heart rate at all.
     */
    @Test
    fun `the final rest window and non-heart-rate streams are not read as windows`() = runTest {
        val rows = listOf(setRow(5L, 0))
        val finalRest = stream(5L, RawStreamEntity.KIND_REST_AFTER_HRM, setTwo)
        val cues = stream(5L, RawStreamEntity.KIND_CUES, setTwo)
        val (_, repository) = repository(unclosed, rows, mapOf(5L to windows(5L, restOne, setOne) + finalRest + cues))

        assertEquals(listOf(restOne, setOne), repository.storedHrWindows(rows))
    }

    /** A stream that will not inflate contributes no window; it does not fail the read. */
    @Test
    fun `a stream that will not inflate contributes no window`() = runTest {
        val rows = listOf(setRow(5L, 0))
        val broken =
            RawStreamEntity(setId = 5L, kind = RawStreamEntity.KIND_REST_BEFORE_HRM, csvGzip = byteArrayOf(1, 2, 3))
        val (_, repository) =
            repository(unclosed, rows, mapOf(5L to listOf(broken, stream(5L, RawStreamEntity.KIND_HRM, setOne))))

        assertEquals(listOf(setOne), repository.storedHrWindows(rows))
    }

    /**
     * An unclosed session's summary carries the HRV its stored windows give:
     * 10, over the beats 800 to 910 counted once.
     */
    @Test
    fun `an unclosed session's summary carries the HRV its stored windows give`() = runTest {
        val rows = listOf(setRow(5L, 0), setRow(6L, 1))
        val (_, repository) =
            repository(
                unclosed,
                rows,
                mapOf(5L to windows(5L, restOne, setOne), 6L to windows(6L, restTwo, setTwo)),
            )

        val hr = repository.sessionHeartRate(unclosed, rows)

        assertEquals(130, hr.avgBpm)
        assertEquals(165, hr.maxBpm)
        assertEquals(10.0, hr.hrvRmssdMs!!, 1e-9)
    }

    /**
     * A closed session publishes the HRV its close stored, and no stream is
     * read to get it: the streams here would give 10.
     */
    @Test
    fun `a closed session's HRV is what its close stored, and no stream is read`() = runTest {
        val closed =
            SessionEntity(
                id = 1L,
                startedAtMs = 1_000L,
                endedAtMs = 300_000L,
                hrAvgBpm = 130,
                hrMaxBpm = 165,
                hrvRmssdMs = 14.8,
            )
        val rows = listOf(setRow(5L, 0), setRow(6L, 1))
        val (dao, repository) =
            repository(
                closed,
                rows,
                mapOf(5L to windows(5L, restOne, setOne), 6L to windows(6L, restTwo, setTwo)),
            )

        val hr = repository.sessionHeartRate(closed, rows)

        assertEquals(14.8, hr.hrvRmssdMs)
        assertEquals(0, dao.streamReads, "a closed session's streams were read")
    }
}
