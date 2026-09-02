package com.macrophage.barspeed.data

import com.macrophage.barspeed.dsp.ImuCsv
import com.macrophage.barspeed.dsp.RepAnalysis
import com.macrophage.barspeed.dsp.SetAnalysis
import com.macrophage.barspeed.model.ExerciseDef
import com.macrophage.barspeed.model.ExerciseKind
import com.macrophage.barspeed.model.GeometrySource
import com.macrophage.barspeed.model.GeometrySources
import com.macrophage.barspeed.model.HrSample
import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.ResolvedGeometry
import com.macrophage.barspeed.model.StartPhase
import com.macrophage.barspeed.model.VoiceCue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
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

        override suspend fun updateLimiter(setId: Long, limiter: String?, limiterNote: String?) {
            calls += "updateLimiter"
        }

        override suspend fun updateWarmupMark(setId: Long, warmupMark: Boolean?) {
            calls += "updateWarmupMark"
        }

        override suspend fun overrideReps(setId: Long, reps: Int) {
            calls += "overrideReps"
        }

        var loadOverrides = mutableListOf<Pair<Long, Double>>()

        override suspend fun overrideLoad(setId: Long, loadKg: Double) {
            calls += "overrideLoad"
            loadOverrides += setId to loadKg
        }

        var durationOverrides = mutableListOf<Pair<Long, Int>>()

        override suspend fun overrideDuration(setId: Long, seconds: Int) {
            calls += "overrideDuration"
            durationOverrides += setId to seconds
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

    /** A plan-declared seated leg curl: drive DOWN, sensor on a 2:1 cable stack. */
    private val legCurlGeometry =
        ResolvedGeometry(
            startsWith = StartPhase.CONCENTRIC,
            concentricUp = false,
            horizontal = false,
            sensorOnStack = true,
            sensorInverted = true,
            travelRatio = 2.0,
            kind = ExerciseKind.DYNAMIC,
            bodyweight = false,
            sources =
            GeometrySources(
                startsWith = GeometrySource.DECLARED,
                concentric = GeometrySource.DECLARED,
                plane = GeometrySource.DEFAULT,
                kind = GeometrySource.INFERRED,
                travelRatio = GeometrySource.DECLARED,
            ),
        )

    private fun completedSet(
        manualReps: Int? = null,
        analysis: SetAnalysis = analysis(),
        imuSamples: List<ImuSample> = imu,
        hrSamples: List<HrSample> = hr,
        voiceCues: List<VoiceCue> = cues,
        geometry: ResolvedGeometry? = null,
        restHrSamples: List<HrSample> = emptyList(),
        repMarks: List<Long> = emptyList(),
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
        plannedPrepS = 5,
        prepS = 20,
        startedAtMs = 1_000L,
        endedAtMs = 2_000L,
        analysis = analysis,
        geometry = geometry,
        imuSamples = imuSamples,
        hrSamples = hrSamples,
        restHrSamples = restHrSamples,
        voiceCues = voiceCues,
        repMarks = repMarks,
    )

    private fun repo(dao: SessionDao, exercises: ExerciseDao = FakeExerciseDao()) = SessionRepository(dao, exercises)

    // ---- the post-set corrections -------------------------------------------

    /**
     * The rest screen's duration correction reaches the DAO with the set it
     * names and the seconds it was given, and issues nothing else.
     *
     * #168's seam. A hold now ends when its clock reaches the seconds it was
     * working to, so the rare genuine overage is stated afterwards on the rest
     * screen -- the surface every other post-set correction already lives on
     * -- and this is the write it turns into. Pinned at the seam, before a
     * caller exists, so the caller is wired to something already measured.
     *
     * Reds if the passthrough starts inventing an id or a figure, and reds if
     * it grows a second statement: a correction that also re-wrote the rating
     * would erase the lifter's own tap, which is the mistake
     * `SetRatingTracker` keeps two separate fields to avoid.
     *
     * Nothing here executes Room or SQLite; the DAO is an interface and this
     * fake stands in for it. What the UPDATE did to the row is not tested by
     * anything in this repository.
     */
    @Test
    fun `a duration correction reaches the dao with the set and the seconds it names`() = runTest {
        val dao = FakeSessionDao()
        repo(dao).overrideDuration(setId = 31L, seconds = 47)
        assertEquals(listOf(31L to 47), dao.durationOverrides)
        assertEquals(listOf("overrideDuration"), dao.calls)
    }

    /**
     * The load correction the rest screen makes on the set just finished
     * (#205), pinned at the seam where it leaves the repository.
     *
     * [SessionRepository.overrideLoad] takes the body-weight-INCLUSIVE total,
     * which is the scale `loadKg` is already on, and its whole job is to hand
     * that one number to the DAO against that one id.
     *
     * THE CALL LIST IS HALF THE ASSERTION. A load correction must not reach
     * `overrideReps`, `updateRpe` or anything else on the way past: the rep
     * count and the failed verdict are separate facts about the set, and the
     * plan's `plannedLoadKg` is what the correction is a deviation FROM, so a
     * second write folded in here would erase the deviation the row exists to
     * record. Exactly one call, and it is this one.
     *
     * Nothing here executes Room or SQLite; the DAO is an interface and this
     * fake stands in for it. What the UPDATE did to the row is not tested by
     * anything in this repository.
     */
    @Test
    fun `a load correction reaches the dao with the set and the total it names`() = runTest {
        val dao = FakeSessionDao()
        repo(dao).overrideLoad(setId = 31L, loadKg = 92.5)
        assertEquals(listOf(31L to 92.5), dao.loadOverrides)
        assertEquals(listOf("overrideLoad"), dao.calls)
    }

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

    /**
     * The prep is a planned/actual pair and both halves reach the row.
     *
     * The fixture declares 5 and plays 20, which is the only combination that
     * can catch a mapping that crossed the two: equal values pass whichever way
     * round they are wired, and that is how a "planned" column ends up holding
     * what was played. The pair exists so an adjustment is visible at all --
     * whenever they differ, the lifter adjusted the prep.
     */
    @Test
    fun `the set row carries the prep prescribed and the prep played, unswapped`() = runTest {
        val dao = FakeSessionDao()
        repo(dao).recordSet(sessionId = 1L, orderIdx = 0, set = completedSet())
        val row = dao.sets.single()
        assertEquals(5, row.plannedPrepS)
        assertEquals(20, row.prepS)
    }

    /**
     * A set with no voice guide played no lead-in, and the row says so by
     * holding nothing.
     *
     * Zero is not available as the way to say it. Zero is a real prep -- the one
     * where nothing is spoken before the first stroke call -- so a row holding 0
     * would claim a measurement that was never taken, and a reader averaging
     * prep across a session would count sets that never had one.
     */
    @Test
    fun `a set that played no lead-in stores neither prep, never a zero`() = runTest {
        val dao = FakeSessionDao()
        val unguided = completedSet().copy(plannedPrepS = null, prepS = null)
        repo(dao).recordSet(sessionId = 1L, orderIdx = 0, set = unguided)
        val row = dao.sets.single()
        assertNull(row.plannedPrepS)
        assertNull(row.prepS)
    }

    /**
     * A prep of zero is stored as zero, on the same terms a manual count of zero
     * is. It is the shortest prep the lead-in can express, not the absence of
     * one, and the two are distinguished above by null.
     */
    @Test
    fun `a prep of zero is stored as zero, not folded into absence`() = runTest {
        val dao = FakeSessionDao()
        repo(dao).recordSet(
            sessionId = 1L,
            orderIdx = 0,
            set = completedSet().copy(plannedPrepS = 0, prepS = 0),
        )
        val row = dao.sets.single()
        assertEquals(0, row.plannedPrepS)
        assertEquals(0, row.prepS)
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

    /**
     * The direction and mounting the analysis ran with is stored with the row,
     * and its absence is stored as absence.
     *
     * Written as one test over both states on purpose. Asserting only the
     * stored case would pass a repository that wrote a plausible default for
     * every set, which is the failure this whole change exists to remove --
     * a fabricated "vertical, drive up, sensor on the bar" is the most
     * reassuring wrong answer available, and it is indistinguishable from a
     * squat that really was measured that way.
     *
     * It has to be captured here rather than looked up at export time: the
     * resolution combines a plan's declarations with the built-in definition,
     * and neither is guaranteed to still say the same thing weeks later when
     * the session is shared.
     */
    @Test
    fun `the geometry the set was analysed against is stored, and its absence is not filled in`() = runTest {
        val stated = FakeSessionDao()
        val repository = repo(stated)
        repository.recordSet(sessionId = 1L, orderIdx = 0, set = completedSet(geometry = legCurlGeometry))
        assertEquals(legCurlGeometry, repository.decodeGeometry(stated.sets.single()))

        val unstated = FakeSessionDao()
        val other = repo(unstated)
        other.recordSet(sessionId = 1L, orderIdx = 0, set = completedSet(geometry = null))
        assertNull(unstated.sets.single().geometryJson, "an unstated geometry was filled in")
        assertNull(other.decodeGeometry(unstated.sets.single()))
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

    /**
     * Fed the WORST stream on hand, not a healthy one, and that is the whole
     * point of the test.
     *
     * The summary columns are entitled to ignore a sample that reports bpm 0 or
     * an R-R interval of zero. The stream is not. This gzipped CSV is the only
     * copy of what the strap actually sent, and a stream quietly filtered to
     * the samples the summary believed would be indistinguishable from a
     * stream off a strap that behaved -- the evidence that the session was
     * mismeasured would be gone, and with it any chance of reprocessing it.
     *
     * With `completedSet()`'s default samples this test could not tell: all
     * three are trustworthy, so filtering them changes nothing and the
     * assertion held whether or not the property did. Session 28's first set
     * has 26 samples reporting bpm 0 and 46 reporting a zero-length R-R, so
     * dropping either kind is now visible.
     */
    @Test
    fun `the heart-rate stream keeps every beat and its rr intervals`() = runTest {
        val dao = FakeSessionDao().apply { nextSetId = 31L }
        val set = completedSet(hrSamples = HrFixtures.unworn(1))
        repo(dao).recordSet(sessionId = 1L, orderIdx = 0, set = set)
        val stream = requireNotNull(dao.stream(RawStreamEntity.KIND_HRM))
        assertEquals(31L, stream.setId)
        // The sample rate belongs to the IMU stream; an HR stream has no such rate
        // and says so with null rather than with a number that means nothing.
        assertNull(stream.sampleRateHz)
        val decoded = HrCsv.decode(Gzip.decompress(stream.csvGzip))
        assertEquals(72, decoded.size, "the persisted stream lost samples")
        assertEquals(26, decoded.count { it.bpm == 0 }, "the persisted stream lost its zero-bpm samples")
        assertEquals(
            46,
            decoded.count { it.rrIntervalsMs == listOf(0.0) },
            "the persisted stream lost its zero-length R-R samples",
        )
        assertEquals(set.hrSamples, decoded)
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

    // ---- the CSV round trip recovery will depend on ------------------------

    /**
     * A set rebuilt from the canonical CSV stores the same bytes as the live one.
     *
     * Nothing in the app does this today, and it is pinned before anything does,
     * because it is the property that any recovery of an interrupted set rests
     * on. If a capture that has been through [ImuCsv.encode] and back is not the
     * same input to [SessionRepository.recordSet] as the list the collector
     * built, then a set recovered from a file is a different set from the one
     * the lifter performed, and no amount of care at the recovery end fixes
     * that.
     *
     * Byte identity of `csvGzip`, not sample equality, and the difference is the
     * whole value of the test. [ImuSample] equality passes on a re-encode that
     * rounds differently -- the decoder parses `0.010000` and `0.01` to the same
     * Double -- while the stored artifact differs. It is the artifact that is
     * shipped to an LLM and compared against a `field-*.csv` fixture, so the
     * artifact is what has to match.
     *
     * All three streams, not just the IMU one. `HrCsv` renders R-R intervals
     * through `%.1f` and `CueCsv` strips commas from cue text; both are lossy
     * transformations that a round trip exposes only if it is asserted on.
     */
    @Test
    fun `a set rebuilt from canonical CSV stores byte-identical streams`() = runTest {
        val live = FakeSessionDao().apply { nextSetId = 31L }
        val reread = FakeSessionDao().apply { nextSetId = 31L }
        val set = completedSet()
        repo(live).recordSet(sessionId = 1L, orderIdx = 0, set = set)
        repo(reread).recordSet(
            sessionId = 1L,
            orderIdx = 0,
            set =
            set.copy(
                imuSamples = ImuCsv.decode(ImuCsv.encode(set.imuSamples)),
                hrSamples = HrCsv.decode(HrCsv.encode(set.hrSamples)),
                voiceCues = CueCsv.decode(CueCsv.encode(set.voiceCues)),
            ),
        )
        for (kind in listOf(RawStreamEntity.KIND_IMU, RawStreamEntity.KIND_HRM, RawStreamEntity.KIND_CUES)) {
            assertContentEquals(
                requireNotNull(live.stream(kind)).csvGzip,
                requireNotNull(reread.stream(kind)).csvGzip,
                "the $kind stream differs after a canonical CSV round trip",
            )
        }
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

    /**
     * The rest window before a set becomes a fourth stream on that set's row,
     * written in the same insert as the other three.
     *
     * Order is asserted because the manifest lists files in stream order and a
     * reader of the zip sees them that way: the set's own capture first, then
     * the window that preceded it, then what the app said. Rest AFTER own is
     * deliberate -- the set is the subject of the row and the rest window is
     * context for it.
     */
    @Test
    fun `the rest window before a set is written as a fourth stream`() = runTest {
        val dao = FakeSessionDao()
        val rest = listOf(HrSample(500L, 64, listOf(937.5)), HrSample(1_000L, 63, listOf(952.4)))
        repo(dao).recordSet(sessionId = 1L, orderIdx = 0, set = completedSet(restHrSamples = rest))
        assertEquals(
            listOf(
                RawStreamEntity.KIND_IMU,
                RawStreamEntity.KIND_HRM,
                RawStreamEntity.KIND_REST_BEFORE_HRM,
                RawStreamEntity.KIND_CUES,
            ),
            dao.streams.map { it.kind },
        )
    }

    /**
     * The stream round-trips through the canonical HR CSV, unchanged.
     *
     * DUPLICATES SURVIVE, and that is the assertion that matters. The fixture
     * repeats 937.5 ms three times, which is what this strap does when no new
     * beat has arrived (issue #81). The analysis accumulators de-duplicate
     * that; the raw capture must not, because it is the only irreplaceable
     * artifact and because a de-duplicated rest stream would be the only
     * evidence anyone could ever use to measure #81's cost at resting rates,
     * destroyed while looking entirely plausible.
     */
    @Test
    fun `the rest stream keeps every notification, duplicates included`() = runTest {
        val dao = FakeSessionDao()
        val rest =
            listOf(
                HrSample(500L, 64, listOf(937.5)),
                HrSample(1_000L, 64, listOf(937.5)),
                HrSample(1_500L, 64, listOf(937.5)),
                HrSample(2_000L, 63, listOf(952.4)),
            )
        repo(dao).recordSet(sessionId = 1L, orderIdx = 0, set = completedSet(restHrSamples = rest))
        val stream = dao.streams.single { it.kind == RawStreamEntity.KIND_REST_BEFORE_HRM }
        assertEquals(rest, HrCsv.decode(Gzip.decompress(stream.csvGzip)))
        assertEquals(4, HrCsv.decode(Gzip.decompress(stream.csvGzip)).size, "a duplicate was collapsed")
    }

    /** No rest window captured writes no stream at all -- absence, not an empty file. */
    @Test
    fun `a set with no rest window writes only its own three streams`() = runTest {
        val dao = FakeSessionDao()
        repo(dao).recordSet(sessionId = 1L, orderIdx = 0, set = completedSet(restHrSamples = emptyList()))
        assertEquals(
            listOf(RawStreamEntity.KIND_IMU, RawStreamEntity.KIND_HRM, RawStreamEntity.KIND_CUES),
            dao.streams.map { it.kind },
        )
    }

    /**
     * The instants the lifter counted become a fifth stream on the row, in
     * the same transactional insert as the rest (#158).
     *
     * Last in the order, after the cue track, because the order is the order
     * the manifest lists files in and a reader of the zip sees them that way:
     * the set's own capture, then the window before it, then what the app
     * said, then what was counted. The two clocks at the end sit beside each
     * other on purpose -- a cue is what the app SAID, on a schedule, and a
     * mark is what was COUNTED, and reading one for the other is the whole
     * reason they are not one file.
     *
     * The stored bytes are asserted, not just the kind. A stream stored in
     * some other format is a stream nothing downstream can read, and the
     * exporter is a separate class that will not fail for it.
     */
    @Test
    fun `the instants a rep was counted at are written as a fifth stream`() = runTest {
        val dao = FakeSessionDao()
        val marks = listOf(1_100L, 4_350L, 8_020L)
        val rest = listOf(HrSample(500L, 64, listOf(937.5)))
        repo(dao).recordSet(
            sessionId = 1L,
            orderIdx = 0,
            set = completedSet(restHrSamples = rest, repMarks = marks),
        )
        assertEquals(
            listOf(
                RawStreamEntity.KIND_IMU,
                RawStreamEntity.KIND_HRM,
                RawStreamEntity.KIND_REST_BEFORE_HRM,
                RawStreamEntity.KIND_CUES,
                RawStreamEntity.KIND_REPS,
            ),
            dao.streams.map { it.kind },
        )
        val stream = dao.streams.single { it.kind == RawStreamEntity.KIND_REPS }
        assertEquals(marks, RepMarkCsv.decode(Gzip.decompress(stream.csvGzip)))
    }

    /**
     * A set that counted nothing out loud writes no rep stream at all.
     *
     * Absence, not an empty file, for the reason every other stream here uses
     * it: an empty document is a claim that the app counted and counted
     * nothing, which is false on the ordinary sensor-counted set, where
     * nothing counts out loud in the first place.
     */
    @Test
    fun `a set with no rep marks writes no rep stream`() = runTest {
        val dao = FakeSessionDao()
        repo(dao).recordSet(sessionId = 1L, orderIdx = 0, set = completedSet(repMarks = emptyList()))
        assertEquals(
            listOf(RawStreamEntity.KIND_IMU, RawStreamEntity.KIND_HRM, RawStreamEntity.KIND_CUES),
            dao.streams.map { it.kind },
        )
    }

    /**
     * The marks are stored for a set the sensor never saw.
     *
     * The straight-rep, sensorless case #158 exists for: no IMU capture, no
     * cue track, and the taps are the only record of the set's shape. A write
     * path that hung the rep stream off the presence of another one would
     * drop exactly the case that needs it.
     */
    @Test
    fun `rep marks are stored even when the set captured nothing else`() = runTest {
        val dao = FakeSessionDao()
        val marks = listOf(1_100L, 4_350L)
        repo(dao).recordSet(
            sessionId = 1L,
            orderIdx = 0,
            set =
            completedSet(
                imuSamples = emptyList(),
                hrSamples = emptyList(),
                voiceCues = emptyList(),
                repMarks = marks,
            ),
        )
        assertEquals(listOf(RawStreamEntity.KIND_REPS), dao.streams.map { it.kind })
        assertEquals(marks, RepMarkCsv.decode(Gzip.decompress(dao.streams.single().csvGzip)))
    }

    /**
     * A set with a rest window and no strap during the set itself still stores
     * the rest window. The two are separate captures and one does not gate the
     * other.
     */
    @Test
    fun `a rest window is stored even when the set itself recorded no heart rate`() = runTest {
        val dao = FakeSessionDao()
        val rest = listOf(HrSample(500L, 58, listOf(1_034.5)))
        repo(dao).recordSet(
            sessionId = 1L,
            orderIdx = 0,
            set = completedSet(hrSamples = emptyList(), restHrSamples = rest),
        )
        assertEquals(
            listOf(RawStreamEntity.KIND_IMU, RawStreamEntity.KIND_REST_BEFORE_HRM, RawStreamEntity.KIND_CUES),
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

    // ---- heart rate: the unworn strap ---------------------------------------

    /**
     * A strap that sat on a table for three sets publishes no heart rate for
     * the two sets where nothing it reported is a measurement.
     *
     * These three assertions were the characterization of what shipped -- the
     * figures below used to be 49/31/50, 47/48/49 and 46/46/47 -- and this
     * commit inverts them. Set 3 is deliberately NOT inverted all the way: 47
     * of its 91 samples carry plausible-band R-R, nothing here reaches them,
     * and its mean and maximum still come out at 46. That remainder is issue
     * #83 and it is asserted rather than hidden, so that the size of this
     * change cannot be mistaken.
     */
    @Test
    fun `unworn set 1 publishes no heart rate`() = runTest {
        val dao = FakeSessionDao()
        repo(dao).recordSet(sessionId = 1L, orderIdx = 0, set = completedSet(hrSamples = HrFixtures.unworn(1)))
        val row = dao.sets.single()
        assertNull(row.hrEndOfSetBpm)
        assertNull(row.hrAvgBpm)
        assertNull(row.hrMaxBpm)
    }

    @Test
    fun `unworn set 2 publishes no heart rate`() = runTest {
        val dao = FakeSessionDao()
        repo(dao).recordSet(sessionId = 1L, orderIdx = 0, set = completedSet(hrSamples = HrFixtures.unworn(2)))
        val row = dao.sets.single()
        assertNull(row.hrEndOfSetBpm)
        assertNull(row.hrAvgBpm)
        assertNull(row.hrMaxBpm)
    }

    @Test
    fun `unworn set 3 stores no heart rate at all`() = runTest {
        val dao = FakeSessionDao()
        repo(dao).recordSet(sessionId = 1L, orderIdx = 0, set = completedSet(hrSamples = HrFixtures.unworn(3)))
        val row = dao.sets.single()
        // Issue #83. These were null, 46 and 46: the 47 samples carrying
        // plausible-band R-R survived the sample-level rule, and the stream
        // they came from covers only 17% of the time the set lasted.
        assertNull(row.hrEndOfSetBpm)
        assertNull(row.hrAvgBpm)
        assertNull(row.hrMaxBpm)
    }

    // ---- heart rate: what a sample has to be to count ----------------------

    @Test
    fun `a bpm of zero is not averaged in as a value`() = runTest {
        val dao = FakeSessionDao()
        val samples =
            listOf(
                HrSample(1_000L, bpm = 100, rrIntervalsMs = listOf(600.0)),
                HrSample(1_500L, bpm = 0, rrIntervalsMs = emptyList()),
                HrSample(2_000L, bpm = 120, rrIntervalsMs = listOf(500.0)),
            )
        repo(dao).recordSet(sessionId = 1L, orderIdx = 0, set = completedSet(hrSamples = samples))
        // (100 + 120) / 2, not (100 + 0 + 120) / 3 = 73.
        assertEquals(110, dao.sets.single().hrAvgBpm)
    }

    @Test
    fun `a sample reporting an impossible interval does not raise the maximum`() = runTest {
        val dao = FakeSessionDao()
        val samples =
            listOf(
                HrSample(1_000L, bpm = 100, rrIntervalsMs = listOf(600.0)),
                HrSample(1_500L, bpm = 190, rrIntervalsMs = listOf(0.0)),
                HrSample(2_000L, bpm = 120, rrIntervalsMs = listOf(500.0)),
            )
        repo(dao).recordSet(sessionId = 1L, orderIdx = 0, set = completedSet(hrSamples = samples))
        assertEquals(120, dao.sets.single().hrMaxBpm)
    }

    @Test
    fun `the end-of-set reading is omitted when the final sample is untrusted`() = runTest {
        val dao = FakeSessionDao()
        val samples =
            listOf(
                HrSample(1_000L, bpm = 100, rrIntervalsMs = listOf(600.0)),
                HrSample(1_500L, bpm = 118, rrIntervalsMs = listOf(500.0)),
                HrSample(2_000L, bpm = 46, rrIntervalsMs = listOf(0.0)),
            )
        repo(dao).recordSet(sessionId = 1L, orderIdx = 0, set = completedSet(hrSamples = samples))
        val row = dao.sets.single()
        assertNull(row.hrEndOfSetBpm, "not backfilled from the last trusted sample")
        assertEquals(109, row.hrAvgBpm, "the rest of the summary survives")
        assertEquals(118, row.hrMaxBpm)
    }

    /**
     * The worn control, summarised the way the app summarises it today.
     *
     * Every one of these seventeen sets is a real measurement and every figure
     * here is correct. They are pinned before anything moves so that a rule
     * aimed at the unworn capture cannot quietly cost a worn one: what must not
     * change is not an average in the abstract, it is these numbers.
     */
    @Test
    fun `the worn control summarises to seventeen sets of real heart rate`() = runTest {
        val published =
            HrFixtures.allWorn().map { samples ->
                val dao = FakeSessionDao()
                repo(dao).recordSet(sessionId = 1L, orderIdx = 0, set = completedSet(hrSamples = samples))
                val row = dao.sets.single()
                Triple(row.hrEndOfSetBpm, row.hrAvgBpm, row.hrMaxBpm)
            }
        assertEquals(
            listOf(
                Triple(102, 98, 110), Triple(110, 101, 114), Triple(110, 103, 116),
                Triple(120, 105, 120), Triple(113, 108, 115), Triple(111, 104, 111),
                Triple(113, 102, 113), Triple(104, 105, 110), Triple(111, 110, 115),
                Triple(112, 106, 113), Triple(108, 106, 111), Triple(105, 103, 110),
                Triple(112, 108, 116), Triple(128, 125, 134), Triple(110, 118, 125),
                Triple(122, 118, 124), Triple(120, 117, 122),
            ),
            published,
        )
    }
}
