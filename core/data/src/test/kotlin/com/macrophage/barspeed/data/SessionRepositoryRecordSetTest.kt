package com.macrophage.barspeed.data

import com.macrophage.barspeed.dsp.ImuCsv
import com.macrophage.barspeed.dsp.RepAnalysis
import com.macrophage.barspeed.dsp.SetAnalysis
import com.macrophage.barspeed.model.ExerciseDef
import com.macrophage.barspeed.model.HrSample
import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.StartPhase
import com.macrophage.barspeed.model.VoiceCue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What [SessionRepository.recordSet] writes for one finished set.
 *
 * This is the most consequential write in the app and it had no test of any
 * kind. Everything the DSP derives is recoverable, because the gzipped IMU CSV
 * is persisted per set and can be reprocessed; the row and the stream beside it
 * are what make that true, so this is the function that has to be pinned before
 * anything is allowed to move underneath it.
 *
 * Assertions here are field by field, never on whole entities, and that is not
 * style. [RawStreamEntity.equals] is `other is RawStreamEntity && other.id == id`
 * and `id` defaults to 0 until Room assigns one, so every pre-insert stream
 * compares equal to every other one: `assertEquals(imuStream, hrmStream)` passes.
 * `csvGzip` is a `ByteArray` besides, so no entity-level equality works at all.
 * Streams are checked on `kind`, `setId`, `sampleRateHz` and their decompressed
 * text.
 *
 * Nothing here executes Room, SQLite or Android. The DAOs are interfaces, so
 * these fakes stand in for them; what is verified is the repository's own
 * mapping and call shape, and nothing about what the database did with it.
 */
class SessionRepositoryRecordSetTest {
    // ---- fakes -------------------------------------------------------------

    /**
     * Records the calls the repository makes and the entities it hands over.
     *
     * [calls] holds method names in order. It is the only assertion in this file
     * about call SHAPE rather than content, and it is here because the shape is
     * the thing that carries atomicity: a set row and its raw streams inserted
     * through separate DAO calls cannot be one transaction, whatever the DAO is
     * annotated with.
     */
    private class FakeSessionDao : SessionDao {
        val calls = mutableListOf<String>()
        val sets = mutableListOf<SetRecordEntity>()
        val streams = mutableListOf<RawStreamEntity>()
        var nextSetId = 7L

        override suspend fun insertSession(session: SessionEntity): Long {
            calls += "insertSession"
            return 1L
        }

        override suspend fun updateSession(session: SessionEntity) {
            calls += "updateSession"
        }

        override suspend fun insertSet(set: SetRecordEntity): Long {
            calls += "insertSet"
            sets += set
            return nextSetId
        }

        override suspend fun insertRawStream(stream: RawStreamEntity): Long {
            calls += "insertRawStream"
            streams += stream
            return streams.size.toLong()
        }

        /**
         * Overridden only to record that it was called, then delegated to the
         * real default body so the id-stamping under test is the DAO's and not
         * a copy of it.
         *
         * Without this override the transaction boundary is invisible here. A
         * fake that inherits the default runs that body against its own
         * `insertSet` and `insertRawStream`, producing exactly the call
         * sequence the un-transactional path produces -- so an assertion on
         * those names alone stays green whether the write is wrapped or not.
         */
        override suspend fun insertSetWithStreams(set: SetRecordEntity, streams: List<RawStreamEntity>): Long {
            calls += "insertSetWithStreams"
            return super.insertSetWithStreams(set, streams)
        }

        fun stream(kind: String): RawStreamEntity? = streams.firstOrNull { it.kind == kind }

        override fun observeSessions(): Flow<List<SessionEntity>> = flowOf(emptyList())

        override suspend fun sessionById(id: Long): SessionEntity? = null

        override fun observeSession(id: Long): Flow<SessionEntity?> = flowOf(null)

        override suspend fun setsForSession(sessionId: Long): List<SetRecordEntity> = emptyList()

        override fun observeSetsForSession(sessionId: Long): Flow<List<SetRecordEntity>> = flowOf(emptyList())

        override suspend fun rawStreamsForSet(setId: Long): List<RawStreamEntity> = emptyList()

        override suspend fun updateRpe(setId: Long, rpe: Int?, failed: Boolean, warmup: Boolean) {
            calls += "updateRpe"
        }

        override suspend fun overrideReps(setId: Long, reps: Int) {
            calls += "overrideReps"
        }

        override suspend fun sessionsInRange(fromMs: Long, toMs: Long): List<SessionEntity> = emptyList()

        override suspend fun deleteSession(id: Long) {
            calls += "deleteSession"
        }
    }

    private class FakeExerciseDao(private val known: MutableMap<String, CustomExerciseEntity> = mutableMapOf()) :
        ExerciseDao {
        val inserted = mutableListOf<CustomExerciseEntity>()

        override suspend fun insert(exercise: CustomExerciseEntity) {
            inserted += exercise
            known[exercise.id] = exercise
        }

        override fun observeAll(): Flow<List<CustomExerciseEntity>> = flowOf(known.values.toList())

        override suspend fun all(): List<CustomExerciseEntity> = known.values.toList()

        override suspend fun byId(id: String): CustomExerciseEntity? = known[id]
    }

    // ---- fixtures ----------------------------------------------------------

    private val imu =
        listOf(
            ImuSample(1_000L, 0.01, -0.02, 0.98, 1.5, -2.5, 0.25, 10.0, -20.0, 30.0),
            ImuSample(1_010L, 0.03, -0.04, 1.02, -1.5, 2.5, -0.25, 11.0, -21.0, 31.0),
        )

    private val hr =
        listOf(
            HrSample(1_000L, bpm = 120, rrIntervalsMs = listOf(500.0, 505.0)),
            HrSample(1_500L, bpm = 150, rrIntervalsMs = listOf(400.0)),
            HrSample(2_000L, bpm = 141, rrIntervalsMs = emptyList()),
        )

    private val cues = listOf(VoiceCue(1_100L, "Rep 1"), VoiceCue(1_600L, "Last rep"))

    private fun rep(index: Int) = RepAnalysis(
        index = index,
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
    )

    private fun analysis(reps: Int = 3, sampleRateHz: Double = 98.5) = SetAnalysis(
        reps = (0 until reps).map { rep(it) },
        sampleRateHz = sampleRateHz,
        velocityLossPct = 12.5,
        tempoCompliance = null,
        verdicts = listOf("Bar speed held up well."),
    )

    private fun completedSet(
        manualReps: Int? = null,
        analysis: SetAnalysis = analysis(),
        imuSamples: List<ImuSample> = imu,
        hrSamples: List<HrSample> = hr,
        voiceCues: List<VoiceCue> = cues,
    ) = CompletedSet(
        exerciseId = "back_squat",
        exerciseName = "Back Squat",
        loadKg = 102.5,
        plannedLoadKg = 100.0,
        plannedReps = 5,
        manualReps = manualReps,
        actualDurationS = null,
        plannedDurationS = null,
        side = "left",
        tempo = "3-1-X-1",
        targetMeanConVelMps = 0.6,
        velocityLossStopPct = 20.0,
        plannedRestS = 180,
        startedAtMs = 1_000L,
        endedAtMs = 2_000L,
        analysis = analysis,
        imuSamples = imuSamples,
        hrSamples = hrSamples,
        voiceCues = voiceCues,
    )

    private fun repo(dao: SessionDao, exercises: ExerciseDao = FakeExerciseDao()) = SessionRepository(dao, exercises)

    // ---- the row -----------------------------------------------------------

    @Test
    fun `recordSet returns the id insertSet generated`() = runTest {
        val dao = FakeSessionDao().apply { nextSetId = 4_242L }
        assertEquals(4_242L, repo(dao).recordSet(sessionId = 9L, orderIdx = 2, set = completedSet()))
    }

    @Test
    fun `the set row carries the session, the order and the prescription unchanged`() = runTest {
        val dao = FakeSessionDao()
        repo(dao).recordSet(sessionId = 9L, orderIdx = 2, set = completedSet())
        val row = dao.sets.single()
        assertEquals(9L, row.sessionId)
        assertEquals(2, row.orderIdx)
        assertEquals("back_squat", row.exerciseId)
        assertEquals("Back Squat", row.exerciseName)
        assertEquals(102.5, row.loadKg)
        assertEquals(100.0, row.plannedLoadKg)
        assertEquals(5, row.plannedReps)
        assertEquals("left", row.side)
        assertEquals("3-1-X-1", row.tempo)
        assertEquals(0.6, row.targetMeanConVelMps)
        assertEquals(20.0, row.velocityLossStopPct)
        assertEquals(180, row.plannedRestS)
        assertEquals(1_000L, row.startedAtMs)
        assertEquals(2_000L, row.endedAtMs)
    }

    @Test
    fun `a timed set carries both durations and no rep prescription of its own`() = runTest {
        val dao = FakeSessionDao()
        val plank =
            completedSet(analysis = analysis(reps = 0), imuSamples = emptyList())
                .copy(actualDurationS = 47, plannedDurationS = 60, plannedReps = null)
        repo(dao).recordSet(sessionId = 1L, orderIdx = 0, set = plank)
        val row = dao.sets.single()
        assertEquals(47, row.actualDurationS)
        assertEquals(60, row.plannedDurationS)
        assertNull(row.plannedReps)
    }

    @Test
    fun `the sensor rep count is stored when the lifter did not count`() = runTest {
        val dao = FakeSessionDao()
        repo(dao).recordSet(sessionId = 1L, orderIdx = 0, set = completedSet(manualReps = null))
        assertEquals(3, dao.sets.single().actualReps)
        assertEquals(false, dao.sets.single().repsManual)
    }

    @Test
    fun `a manual count overrides the sensor count and is flagged as the lifter's`() = runTest {
        val dao = FakeSessionDao()
        repo(dao).recordSet(sessionId = 1L, orderIdx = 0, set = completedSet(manualReps = 5))
        assertEquals(5, dao.sets.single().actualReps)
        assertEquals(true, dao.sets.single().repsManual)
    }

    @Test
    fun `a manual count of zero is stored as zero, not treated as absent`() = runTest {
        val dao = FakeSessionDao()
        repo(dao).recordSet(sessionId = 1L, orderIdx = 0, set = completedSet(manualReps = 0))
        assertEquals(0, dao.sets.single().actualReps)
        assertEquals(true, dao.sets.single().repsManual)
    }

    /**
     * The rating the lifter tapped must travel with the row, not follow it.
     *
     * The effort tile IS the end-set control, so the rating is part of the same
     * gesture that ended the set, and it is captured once: no screen can edit a
     * stored set's rating once the rest screen is gone. Writing it as a second
     * statement against the row just inserted leaves a window in which the set
     * exists rated as nothing, and a set the lifter tapped as failed reads
     * afterwards as an unremarkable set.
     */
    @Test
    fun `the rating the set ended with is stored on the row`() = runTest {
        val dao = FakeSessionDao()
        repo(dao).recordSet(
            sessionId = 1L,
            orderIdx = 0,
            set = completedSet().copy(rpe = 9, failed = true, warmup = false),
        )
        val row = dao.sets.single()
        assertEquals(9, row.rpe)
        assertEquals(true, row.failed)
        assertEquals(false, row.warmup)
    }

    @Test
    fun `a warm-up set is stored as a warm-up`() = runTest {
        val dao = FakeSessionDao()
        repo(dao).recordSet(
            sessionId = 1L,
            orderIdx = 0,
            set = completedSet().copy(rpe = null, failed = false, warmup = true),
        )
        assertEquals(true, dao.sets.single().warmup)
    }

    /**
     * No second statement. `updateRpe` is what the rest screen uses to correct a
     * rating later; the set-end path must not need it, because a failure between
     * the insert and the update is exactly the window this closes.
     */
    @Test
    fun `storing a set issues no separate rating update`() = runTest {
        val dao = FakeSessionDao()
        repo(dao).recordSet(
            sessionId = 1L,
            orderIdx = 0,
            set = completedSet().copy(rpe = 7, failed = false, warmup = false),
        )
        assertTrue("updateRpe" !in dao.calls)
    }

    /** An unrated set is stored exactly as it was before the rating moved. */
    @Test
    fun `a set with no rating keeps the entity defaults`() = runTest {
        val dao = FakeSessionDao()
        repo(dao).recordSet(sessionId = 1L, orderIdx = 0, set = completedSet())
        val row = dao.sets.single()
        assertNull(row.rpe)
        assertEquals(false, row.failed)
        assertEquals(false, row.warmup)
        assertTrue("updateRpe" !in dao.calls)
    }

    @Test
    fun `the stored analysis decodes back to the analysis that was handed in`() = runTest {
        val dao = FakeSessionDao()
        val repository = repo(dao)
        val set = completedSet()
        repository.recordSet(sessionId = 1L, orderIdx = 0, set = set)
        assertEquals(set.analysis, repository.decodeAnalysis(dao.sets.single()))
    }

    // ---- heart rate --------------------------------------------------------

    @Test
    fun `heart rate is summarised onto the row as end, mean and max`() = runTest {
        val dao = FakeSessionDao()
        repo(dao).recordSet(sessionId = 1L, orderIdx = 0, set = completedSet())
        val row = dao.sets.single()
        assertEquals(141, row.hrEndOfSetBpm)
        // (120 + 150 + 141) / 3 = 137, truncated from 137.0.
        assertEquals(137, row.hrAvgBpm)
        assertEquals(150, row.hrMaxBpm)
    }

    /**
     * A set recorded with no HRM connected has no heart rate, and that is a
     * different fact from a heart rate of zero. Absence stays absent.
     */
    @Test
    fun `no heart-rate samples leaves the summary null rather than zero`() = runTest {
        val dao = FakeSessionDao()
        repo(dao).recordSet(sessionId = 1L, orderIdx = 0, set = completedSet(hrSamples = emptyList()))
        val row = dao.sets.single()
        assertNull(row.hrEndOfSetBpm)
        assertNull(row.hrAvgBpm)
        assertNull(row.hrMaxBpm)
    }

    // ---- the raw streams ---------------------------------------------------

    @Test
    fun `the imu stream is gzipped canonical CSV, attached to the new set row`() = runTest {
        val dao = FakeSessionDao().apply { nextSetId = 31L }
        val set = completedSet()
        repo(dao).recordSet(sessionId = 1L, orderIdx = 0, set = set)
        val stream = requireNotNull(dao.stream(RawStreamEntity.KIND_IMU))
        assertEquals(31L, stream.setId)
        assertEquals(98.5, stream.sampleRateHz)
        val text = Gzip.decompress(stream.csvGzip)
        assertEquals(ImuCsv.HEADER, text.lineSequence().first())
        assertEquals(set.imuSamples, ImuCsv.decode(text))
    }

    @Test
    fun `the heart-rate stream keeps every beat and its rr intervals`() = runTest {
        val dao = FakeSessionDao().apply { nextSetId = 31L }
        val set = completedSet()
        repo(dao).recordSet(sessionId = 1L, orderIdx = 0, set = set)
        val stream = requireNotNull(dao.stream(RawStreamEntity.KIND_HRM))
        assertEquals(31L, stream.setId)
        // The sample rate belongs to the IMU stream; an HR stream has no such rate
        // and says so with null rather than with a number that means nothing.
        assertNull(stream.sampleRateHz)
        assertEquals(set.hrSamples, HrCsv.decode(Gzip.decompress(stream.csvGzip)))
    }

    @Test
    fun `the cue stream keeps what was spoken and when`() = runTest {
        val dao = FakeSessionDao().apply { nextSetId = 31L }
        val set = completedSet()
        repo(dao).recordSet(sessionId = 1L, orderIdx = 0, set = set)
        val stream = requireNotNull(dao.stream(RawStreamEntity.KIND_CUES))
        assertEquals(31L, stream.setId)
        assertNull(stream.sampleRateHz)
        assertEquals(set.voiceCues, CueCsv.decode(Gzip.decompress(stream.csvGzip)))
    }

    @Test
    fun `an empty stream is not written as an empty row`() = runTest {
        val dao = FakeSessionDao()
        repo(dao).recordSet(
            sessionId = 1L,
            orderIdx = 0,
            set = completedSet(imuSamples = emptyList(), hrSamples = emptyList(), voiceCues = emptyList()),
        )
        assertEquals(emptyList(), dao.streams.map { it.kind })
    }

    @Test
    fun `a sensorless manual set still stores its row`() = runTest {
        val dao = FakeSessionDao()
        repo(dao).recordSet(
            sessionId = 1L,
            orderIdx = 0,
            set =
            completedSet(
                manualReps = 8,
                analysis = analysis(reps = 0, sampleRateHz = 0.0),
                imuSamples = emptyList(),
                hrSamples = emptyList(),
                voiceCues = emptyList(),
            ),
        )
        assertEquals(8, dao.sets.single().actualReps)
        assertEquals(emptyList(), dao.streams.map { it.kind })
    }

    // ---- call shape --------------------------------------------------------

    /**
     * The set row and every stream belonging to it must reach the DAO through
     * one call, so that Room's `@Transaction` spans the whole write.
     *
     * This does NOT verify that anything committed or rolled back. A fake
     * cannot: the transaction is Room's, generated around the DAO method, and
     * nothing at this seam executes SQLite. What it verifies is the property
     * that carries atomicity -- that the repository has no interleaving point
     * left between the row and its streams. Without that there is nothing for
     * `@Transaction` to span, and a throw partway leaves a set row in history
     * whose gzipped IMU stream, the artifact everything derived stays
     * recoverable from, is simply absent.
     *
     * It is also what stops a fourth stream kind being added outside the
     * wrapper later with nothing going red.
     *
     * The corrected pin. The version this replaces asserted the old sequence
     * `insertSet` then three `insertRawStream`, and its commit body claimed it
     * would be inverted by the transaction change. That claim was wrong and is
     * retracted in this commit's body: the default `@Transaction` body issues
     * exactly those same calls against the fake, so the old assertion passed
     * both before and after the change and detected nothing.
     */
    @Test
    fun `the row and its streams reach the DAO as one transactional call`() = runTest {
        val dao = FakeSessionDao()
        repo(dao).recordSet(sessionId = 1L, orderIdx = 0, set = completedSet())
        assertEquals(
            listOf("insertSetWithStreams", "insertSet", "insertRawStream", "insertRawStream", "insertRawStream"),
            dao.calls,
        )
    }

    @Test
    fun `streams are written imu first, then heart rate, then cues`() = runTest {
        val dao = FakeSessionDao()
        repo(dao).recordSet(sessionId = 1L, orderIdx = 0, set = completedSet())
        assertEquals(
            listOf(RawStreamEntity.KIND_IMU, RawStreamEntity.KIND_HRM, RawStreamEntity.KIND_CUES),
            dao.streams.map { it.kind },
        )
    }

    // ---- ensureExerciseExists ----------------------------------------------

    /**
     * The other write on the set-end path, and the one a retry would repeat.
     * Its idempotence is by construction rather than by a database constraint,
     * so it is pinned here: a second call for the same id must insert nothing.
     */
    @Test
    fun `a seeded exercise is never inserted as a custom one`() = runTest {
        val exercises = FakeExerciseDao()
        repo(FakeSessionDao(), exercises).ensureExerciseExists(ExerciseDef.SEED.first().id)
        assertEquals(emptyList(), exercises.inserted.map { it.id })
    }

    @Test
    fun `an unknown exercise is inserted once with a readable name and a start phase`() = runTest {
        val exercises = FakeExerciseDao()
        val repository = repo(FakeSessionDao(), exercises)
        repository.ensureExerciseExists("cable_fly")
        val row = exercises.inserted.single()
        assertEquals("cable_fly", row.id)
        assertEquals("Cable fly", row.displayName)
        assertEquals(ExerciseDef.inferStartPhase("cable_fly").name, row.startsWith)
        assertTrue(row.startsWith in setOf(StartPhase.ECCENTRIC.name, StartPhase.CONCENTRIC.name))
    }

    @Test
    fun `ensureExerciseExists is safe to repeat for the same exercise`() = runTest {
        val exercises = FakeExerciseDao()
        val repository = repo(FakeSessionDao(), exercises)
        repository.ensureExerciseExists("cable_fly")
        repository.ensureExerciseExists("cable_fly")
        assertEquals(listOf("cable_fly"), exercises.inserted.map { it.id })
    }
}
