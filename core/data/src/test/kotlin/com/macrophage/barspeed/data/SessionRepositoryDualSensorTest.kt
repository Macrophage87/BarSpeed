package com.macrophage.barspeed.data

import com.macrophage.barspeed.dsp.ImuCsv
import com.macrophage.barspeed.dsp.SetAnalysis
import com.macrophage.barspeed.model.DualShortfall
import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.RecordedSensors
import com.macrophage.barspeed.model.SensorRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What `recordSet` writes for a set recorded with two accelerometers (#156).
 *
 * DIFFERENTIALS. Every assertion in this file fails at the commit that
 * introduces it: the repository writes one IMU row, puts no role on it and
 * never touches `sensorsJson`, so the second stream handed to it is dropped on
 * the floor. The commit after this one is what makes them pass.
 *
 * Nothing here executes Room, SQLite or Android. The DAO is an interface and
 * this fake stands in for it, so what is verified is the repository's own
 * mapping and nothing about what the database did with it.
 */
class SessionRepositoryDualSensorTest {
    private val json = Json { ignoreUnknownKeys = true }

    private class RecordingDao : SessionDao {
        val sets = mutableListOf<SetRecordEntity>()
        val streams = mutableListOf<RawStreamEntity>()

        override suspend fun insertSession(session: SessionEntity): Long = 1L

        override suspend fun updateSession(session: SessionEntity) = Unit

        override suspend fun insertSet(set: SetRecordEntity): Long {
            sets += set
            return 7L
        }

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

        // Conformance only: SessionDao grew this member for #205 and Kotlin
        // requires it. Nothing in this file calls it.
        override suspend fun overrideLoad(setId: Long, loadKg: Double) = Unit

        override suspend fun overrideDuration(setId: Long, seconds: Int) = Unit

        override suspend fun sessionsInRange(fromMs: Long, toMs: Long): List<SessionEntity> = emptyList()

        override suspend fun deleteSession(id: Long) = Unit

        val imuRows: List<RawStreamEntity> get() = streams.filter { it.kind == RawStreamEntity.KIND_IMU }
    }

    private class StubExerciseDao : ExerciseDao {
        override suspend fun insert(exercise: CustomExerciseEntity) = Unit

        override fun observeAll(): Flow<List<CustomExerciseEntity>> = flowOf(emptyList())

        override suspend fun all(): List<CustomExerciseEntity> = emptyList()

        override suspend fun byId(id: String): CustomExerciseEntity? = null
    }

    /** Two samples, so the analysed and the secondary capture are told apart by content. */
    private val analysedSamples =
        listOf(
            ImuSample(1_000L, 0.01, -0.02, 0.98, 1.5, -2.5, 0.25, 10.0, -20.0, 30.0),
            ImuSample(1_010L, 0.03, -0.04, 1.02, -1.5, 2.5, -0.25, 11.0, -21.0, 31.0),
        )

    private val secondarySamples =
        listOf(
            ImuSample(1_002L, 0.51, -0.52, 0.58, 5.5, -6.5, 5.25, 50.0, -60.0, 70.0),
            ImuSample(1_012L, 0.53, -0.54, 0.62, -5.5, 6.5, -5.25, 51.0, -61.0, 71.0),
            ImuSample(1_022L, 0.55, -0.56, 0.66, -5.5, 6.5, -5.25, 52.0, -62.0, 72.0),
        )

    private fun analysis() = SetAnalysis(emptyList(), 98.5, null, null, emptyList())

    private fun completedSet(sensors: RecordedSensors?, secondary: SecondaryCapture?) = CompletedSet(
        exerciseId = "bench_press",
        exerciseName = "Bench Press",
        loadKg = 80.0,
        plannedLoadKg = 80.0,
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
        imuSamples = analysedSamples,
        hrSamples = emptyList(),
        sensors = sensors,
        secondary = secondary,
    )

    private suspend fun record(sensors: RecordedSensors?, secondary: SecondaryCapture?): RecordingDao {
        val dao = RecordingDao()
        SessionRepository(dao, StubExerciseDao())
            .recordSet(sessionId = 1L, orderIdx = 0, set = completedSet(sensors, secondary))
        return dao
    }

    private val dualDeclaration =
        RecordedSensors(
            count = 2,
            expected = listOf(SensorRole.A, SensorRole.B),
            analysed = SensorRole.A,
        )

    // ---- the two rows --------------------------------------------------------

    /**
     * Two IMU rows, the analysed one first, each carrying its own role.
     *
     * Order is asserted because the archive lists a set's files in stream order
     * and a reader of the zip sees them that way; analysed first matches every
     * other ordering decision in `recordSet`, where the set's own subject comes
     * before its context.
     */
    @Test
    fun `a dual set writes both captures as two imu rows, analysed first, each labelled`() = runTest {
        val dao = record(dualDeclaration, SecondaryCapture(SensorRole.B, secondarySamples))

        assertEquals(2, dao.imuRows.size, "the second capture never reached a row")
        assertEquals(listOf("a", "b"), dao.imuRows.map { it.role })
        assertEquals(
            listOf(analysedSamples.size, secondarySamples.size),
            dao.imuRows.map { ImuCsv.decode(Gzip.decompress(it.csvGzip)).size },
            "the two rows do not hold the two captures, in order",
        )
    }

    /**
     * The stored rate stays on the analysed row and the secondary's stays null.
     *
     * `sampleRateHz` is `analysis.sampleRateHz`, measured over the stream the
     * DSP analysed. There is no analysis of the secondary, so anything written
     * there is either a fabrication or a second, differently derived quantity
     * in a column that already means one thing. Null is not a loss: the
     * exporter measures a rate from the stream itself and treats this column
     * only as a fallback, and the CSV is persisted.
     */
    @Test
    fun `the secondary row states no sample rate rather than borrowing the analysed one`() = runTest {
        val dao = record(dualDeclaration, SecondaryCapture(SensorRole.B, secondarySamples))

        assertEquals(98.5, dao.imuRows.first { it.role == "a" }.sampleRateHz)
        assertNull(
            dao.imuRows.first { it.role == "b" }.sampleRateHz,
            "the secondary row was given a rate that was not measured from it",
        )
    }

    /** The declaration reaches the row, and decodes back to what was handed in. */
    @Test
    fun `the set row carries the declaration the caller stated`() = runTest {
        val dao = record(dualDeclaration, SecondaryCapture(SensorRole.B, secondarySamples))

        val stored = dao.sets.single().sensorsJson
        assertEquals(
            dualDeclaration,
            stored?.let { json.decodeFromString(RecordedSensors.serializer(), it) },
            "the sensor declaration did not reach the set row",
        )
    }

    /**
     * An armed role whose unit produced nothing writes no row -- and the
     * declaration still names it.
     *
     * The absence has to stay a distinct state: a row holding an empty CSV
     * would publish a stream that captured nothing, which is a claim, while a
     * declaration naming a role with no file is the honest shape. This is the
     * field case where a unit's battery dies between the arming and the first
     * rep.
     */
    @Test
    fun `an armed role that captured nothing writes no row and is still declared`() = runTest {
        val dao = record(dualDeclaration, SecondaryCapture(SensorRole.B, emptyList()))

        assertEquals(1, dao.imuRows.size, "an empty capture was written as a row")
        assertEquals(listOf("a"), dao.imuRows.map { it.role })
        val stored = json.decodeFromString(RecordedSensors.serializer(), dao.sets.single().sensorsJson!!)
        assertEquals(
            listOf(SensorRole.A, SensorRole.B),
            stored.expected,
            "the missing role was dropped from the declaration",
        )
    }

    /**
     * A set that met two paired units it could not tell apart records the
     * reason, and its single stream stays unlabelled.
     *
     * Both halves matter. The reason is unrecoverable from anything else, so it
     * is stored; the role is absent because nobody assigned one, and inventing
     * an `a` would state which physical unit the capture came from.
     */
    @Test
    fun `a shortfall records the reason and leaves its one stream unlabelled`() = runTest {
        val dao =
            record(
                RecordedSensors(count = 1, shortfall = DualShortfall.ROLES_COLLIDE),
                secondary = null,
            )

        assertEquals(1, dao.imuRows.size)
        assertNull(dao.imuRows.single().role, "a stream nobody labelled was labelled")
        val stored = json.decodeFromString(RecordedSensors.serializer(), dao.sets.single().sensorsJson!!)
        assertEquals(DualShortfall.ROLES_COLLIDE, stored.shortfall)
        assertEquals(1, stored.count)
        assertEquals(emptyList(), stored.expected)
    }

    /**
     * The one-sensor set is untouched, and this is the control the rest of the
     * file is read against.
     *
     * It is in this file rather than beside the characterization pins because
     * it is the same question asked of the same fixture: with no declaration
     * and no secondary, `recordSet` must produce exactly what it produced
     * before any of this existed -- one row, no role, no column.
     */
    @Test
    fun `a one-sensor set writes one unlabelled row and no declaration`() = runTest {
        val dao = record(sensors = null, secondary = null)

        assertEquals(1, dao.imuRows.size)
        assertNull(dao.imuRows.single().role)
        assertNull(dao.sets.single().sensorsJson)
    }
}
