package com.macrophage.barspeed.data

import com.macrophage.barspeed.dsp.SetAnalysis
import com.macrophage.barspeed.model.FinalRestWindowDecision
import com.macrophage.barspeed.model.HrSample
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What [SessionRepository.recordFinalRestWindow] writes, issue #109.
 *
 * The differentials for the one rest window `rest_before_hrm`'s forward
 * attachment cannot reach: the one after the last set, which has no following
 * set to ride into the database with. Field-36 (2026-09-01, v0.1.47) is the
 * measurement -- fourteen `rest_before_hrm` files and nothing after set 14 --
 * and the fixture below is that shape rather than an invented one.
 *
 * This is the second, non-atomic write `rest_before_hrm` was designed to
 * avoid, so the pins are as much about what it must NOT do as what it must:
 * it may not touch the set's own frozen heart-rate columns, and a replayed
 * close may not leave two windows on one set.
 *
 * Nothing here executes Room, SQLite or Android. [FakeSessionDao] implements
 * the DAO interface, so what is pinned is the repository's own reads, decision
 * and the entity it hands to the DAO -- never what the database did with it,
 * and never whether the insert and the session close commit together. They do
 * not: that is the design, and it is stated on the function.
 */
class SessionRepositoryFinalRestWindowTest {
    // ---- fakes -------------------------------------------------------------

    /**
     * Stateful, and it records every insert in order because several pins here
     * ask how many writes happened rather than what the last one said.
     */
    private class FakeSessionDao(
        private val rows: List<SetRecordEntity>,
        seedStreams: List<RawStreamEntity> = emptyList(),
    ) : SessionDao {
        val streams = seedStreams.toMutableList()
        val inserted = mutableListOf<RawStreamEntity>()
        val sessionUpdates = mutableListOf<SessionEntity>()
        val setColumnWrites = mutableListOf<String>()

        override suspend fun insertSession(session: SessionEntity): Long = 1L

        override suspend fun updateSession(session: SessionEntity) {
            sessionUpdates += session
        }

        override suspend fun insertSet(set: SetRecordEntity): Long = 1L

        override suspend fun insertRawStream(stream: RawStreamEntity): Long {
            inserted += stream
            streams += stream
            return (streams.size + 100).toLong()
        }

        override fun observeSessions(): Flow<List<SessionEntity>> = flowOf(emptyList())

        override suspend fun sessionById(id: Long): SessionEntity? = SessionEntity(id = 1L, startedAtMs = 1_000L)

        override fun observeSession(id: Long): Flow<SessionEntity?> = flowOf(null)

        override suspend fun setsForSession(sessionId: Long): List<SetRecordEntity> =
            rows.filter { it.sessionId == sessionId }.sortedBy { it.orderIdx }

        override fun observeSetsForSession(sessionId: Long): Flow<List<SetRecordEntity>> = flowOf(rows)

        override suspend fun rawStreamsForSet(setId: Long): List<RawStreamEntity> = streams.filter { it.setId == setId }

        override suspend fun updateRpe(
            setId: Long,
            rpe: Int?,
            failed: Boolean,
            failedByLifter: Boolean?,
            warmup: Boolean,
        ) {
            setColumnWrites += "updateRpe"
        }

        override suspend fun updateLimiter(setId: Long, limiter: String?, limiterNote: String?) {
            setColumnWrites += "updateLimiter"
        }

        override suspend fun updateWarmupMark(setId: Long, warmupMark: Boolean?) {
            setColumnWrites += "updateWarmupMark"
        }

        // Recorded like every other write this fake sees, though nothing in
        // this file calls it: SessionDao grew the member for #60 and Kotlin
        // requires it, and a fake that silently swallowed one write while
        // recording the rest would report a clean column list for a change
        // that had written to the row.
        override suspend fun updateVoided(setId: Long, voided: Boolean, reason: String?) {
            setColumnWrites += "updateVoided"
        }

        override suspend fun overrideReps(setId: Long, reps: Int) {
            setColumnWrites += "overrideReps"
        }

        // Conformance only: SessionDao grew this member for #205 and Kotlin
        // requires it. Nothing in this file calls it; tracked the same way
        // as its neighbours so a stray call would still fail the
        // setColumnWrites assertion below.
        override suspend fun overrideLoad(setId: Long, loadKg: Double) {
            setColumnWrites += "overrideLoad"
        }

        override suspend fun overrideDuration(setId: Long, seconds: Int) {
            setColumnWrites += "overrideDuration"
        }

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

    private val json = Json { encodeDefaults = true }

    /** field-36's count. */
    private val setCount = 14

    private fun row(idx: Int) = SetRecordEntity(
        id = (idx + 1).toLong(),
        sessionId = 1L,
        orderIdx = idx,
        exerciseId = "bench_press",
        exerciseName = "bench_press",
        loadKg = 60.0,
        actualReps = 5,
        hrEndOfSetBpm = 120 + idx,
        hrAvgBpm = 100 + idx,
        hrMaxBpm = 130 + idx,
        startedAtMs = 1_000L + idx * 200_000L,
        endedAtMs = 46_000L + idx * 200_000L,
        analysisJson =
        json.encodeToString(SetAnalysis.serializer(), SetAnalysis(emptyList(), 0.0, null, null, emptyList())),
    )

    private fun sessionRows(count: Int = setCount) = (0 until count).map(::row)

    /**
     * The window itself, in the shape a strap actually reports one: a settling
     * rate, and a REPEATED R-R interval, which is the strap re-sending its last
     * completed beat when none has arrived (#81). This buffer is a capture and
     * not an analysis, so the duplicate has to survive.
     */
    private val window =
        listOf(
            HrSample(3_000_000L, 96, listOf(625.0)),
            HrSample(3_001_000L, 88, listOf(682.0)),
            HrSample(3_002_000L, 88, listOf(682.0)),
            HrSample(3_003_000L, 81, listOf(741.0)),
        )

    private fun repoOf(dao: FakeSessionDao) = SessionRepository(dao, FakeExerciseDao())

    // ---- differentials -----------------------------------------------------

    /**
     * The write itself: one stream, of the trailing kind, on the LAST set.
     *
     * Fourteen sets in the fixture, so a write that attached to the first set
     * -- or to whichever row the DAO happened to return first -- is visible as
     * a wrong `setId` rather than passing on a one-set session.
     */
    @Test
    fun `the final rest window is written onto the last set of the session`() = runTest {
        val dao = FakeSessionDao(sessionRows())
        val decision = repoOf(dao).recordFinalRestWindow(1L, window)
        assertEquals(FinalRestWindowDecision.WRITE, decision)
        assertEquals(1, dao.inserted.size, "expected exactly one stream to be inserted")
        val stream = dao.inserted.single()
        assertEquals(RawStreamEntity.KIND_REST_AFTER_HRM, stream.kind, "the window was written under the wrong kind")
        assertEquals(14L, stream.setId, "the window did not go on the last set of the session")
    }

    /**
     * And the samples are stored raw, duplicates included.
     *
     * The repeated interval in the fixture is the strap re-sending a completed
     * beat. De-duplicating here would look entirely plausible and would destroy
     * the only capture anyone could measure #81's cost at resting rates with --
     * which is the population this window exists to record.
     */
    @Test
    fun `the window is stored raw, in order, with duplicates kept`() = runTest {
        val dao = FakeSessionDao(sessionRows())
        repoOf(dao).recordFinalRestWindow(1L, window)
        assertEquals(window, HrCsv.decode(Gzip.decompress(dao.inserted.single().csvGzip)))
    }

    /**
     * No rate and no role on the row.
     *
     * `sampleRateHz` means the rate the DSP analysed a stream at and there is
     * no analysis of this one; `role` names which accelerometer a capture came
     * from and this came from a strap. Either written here would label the row
     * with another stream's fact.
     */
    @Test
    fun `the window carries no sample rate and no sensor role`() = runTest {
        val dao = FakeSessionDao(sessionRows())
        repoOf(dao).recordFinalRestWindow(1L, window)
        val stream = dao.inserted.single()
        assertEquals(null, stream.sampleRateHz, "a rate was stated for a stream nothing analysed")
        assertEquals(null, stream.role, "a sensor role was stated for a stream that came from no accelerometer")
    }

    /**
     * Nothing arriving after the last set writes no file.
     *
     * An empty heart-rate CSV would claim a window was captured and that it was
     * silent, which is a different fact from the strap having been off.
     */
    @Test
    fun `an empty window writes nothing`() = runTest {
        val dao = FakeSessionDao(sessionRows())
        val decision = repoOf(dao).recordFinalRestWindow(1L, emptyList())
        assertEquals(FinalRestWindowDecision.NO_SAMPLES, decision)
        assertTrue(dao.inserted.isEmpty(), "an empty window reached the database")
    }

    /**
     * A session that recorded no set has no row to attach the window to.
     *
     * This is the window #109 does NOT close, and it is refused explicitly
     * rather than crashing on an empty list or inventing a row to hold it.
     */
    @Test
    fun `a session with no set row writes nothing`() = runTest {
        val dao = FakeSessionDao(rows = emptyList())
        val decision = repoOf(dao).recordFinalRestWindow(1L, window)
        assertEquals(FinalRestWindowDecision.NO_SET_TO_ATTACH_TO, decision)
        assertTrue(dao.inserted.isEmpty(), "a window was written to a session with no set")
    }

    /**
     * A replayed close does not write the window twice.
     *
     * `SessionCloser` replays a frozen close after a failure, so this path can
     * run more than once for one session. Two files of the same kind on one set
     * would leave a reader to guess which is the window; the second call
     * reports what it found instead.
     */
    @Test
    fun `a replayed close does not write the window twice`() = runTest {
        val dao = FakeSessionDao(sessionRows())
        val repo = repoOf(dao)
        assertEquals(FinalRestWindowDecision.WRITE, repo.recordFinalRestWindow(1L, window))
        assertEquals(FinalRestWindowDecision.ALREADY_WRITTEN, repo.recordFinalRestWindow(1L, window))
        assertEquals(1, dao.inserted.size, "the replayed close wrote a second copy of the window")
    }

    /**
     * A rest_before stream already on the last set is not mistaken for one.
     *
     * The near neighbour of the pin above and the reason the kinds are matched
     * by equality: every set after the first already carries a
     * `rest_before_hrm`, and reading that as "already written" would drop the
     * trailing window on every session that has one -- which is all of them.
     */
    @Test
    fun `a rest_before stream on the last set does not count as already written`() = runTest {
        val dao =
            FakeSessionDao(
                sessionRows(),
                seedStreams =
                listOf(
                    RawStreamEntity(
                        id = 1L,
                        setId = 14L,
                        kind = RawStreamEntity.KIND_REST_BEFORE_HRM,
                        csvGzip = Gzip.compress(HrCsv.encode(window)),
                    ),
                ),
            )
        assertEquals(FinalRestWindowDecision.WRITE, repoOf(dao).recordFinalRestWindow(1L, window))
        assertEquals(1, dao.inserted.size, "the trailing window was refused because a rest_before window existed")
    }

    /**
     * The set's own record is untouched.
     *
     * The window is not part of the set: the frozen `hrAvgBpm`, `hrMaxBpm` and
     * `hrEndOfSetBpm` columns describe what the lifter's heart did DURING set
     * 14, and folding a resting rate into them would be the mixed-population
     * defect this repository has already shipped once. Nor does this touch the
     * session row -- `endSession` owns that and runs before this.
     */
    @Test
    fun `writing the window touches no set column and no session row`() = runTest {
        val dao = FakeSessionDao(sessionRows())
        repoOf(dao).recordFinalRestWindow(1L, window)
        assertTrue(dao.setColumnWrites.isEmpty(), "the window write reached a set column: ${dao.setColumnWrites}")
        assertTrue(dao.sessionUpdates.isEmpty(), "the window write reached the session row")
    }
}
