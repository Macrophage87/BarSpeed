package com.macrophage.barspeed.data

import com.macrophage.barspeed.dsp.SetAnalysis
import com.macrophage.barspeed.model.RecordedSensors
import com.macrophage.barspeed.model.SensorRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The sensor declaration on a stored set: how it decodes back, and what its
 * absence is allowed to mean (#156).
 *
 * Nothing here executes Room, SQLite or Android -- the DAOs are interfaces and
 * the fakes stand in for them, so what is verified is the repository's own
 * mapping and nothing about what the database did with it.
 */
class SessionRepositorySensorsTest {
    private val json = Json { ignoreUnknownKeys = true }

    private class EmptyDao : SessionDao {
        override suspend fun insertSession(session: SessionEntity): Long = 1L

        override suspend fun updateSession(session: SessionEntity) = Unit

        override suspend fun insertSet(set: SetRecordEntity): Long = 1L

        override suspend fun insertRawStream(stream: RawStreamEntity): Long = 1L

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

    private class EmptyExerciseDao : ExerciseDao {
        override suspend fun insert(exercise: CustomExerciseEntity) = Unit

        override fun observeAll(): Flow<List<CustomExerciseEntity>> = flowOf(emptyList())

        override suspend fun all(): List<CustomExerciseEntity> = emptyList()

        override suspend fun byId(id: String): CustomExerciseEntity? = null
    }

    private val repo = SessionRepository(EmptyDao(), EmptyExerciseDao())

    private val emptyAnalysisJson =
        json.encodeToString(SetAnalysis.serializer(), SetAnalysis(emptyList(), 0.0, null, null, emptyList()))

    private fun row(sensorsJson: String?) = SetRecordEntity(
        id = 1L,
        sessionId = 1L,
        orderIdx = 0,
        exerciseId = "back_squat",
        exerciseName = "Back Squat",
        loadKg = 100.0,
        actualReps = 5,
        startedAtMs = 1_000L,
        endedAtMs = 2_000L,
        analysisJson = emptyAnalysisJson,
        sensorsJson = sensorsJson,
    )

    @Test
    fun `a stored declaration decodes back to what was written`() {
        val declared =
            RecordedSensors(
                plannedCount = 2,
                count = 2,
                expected = listOf(SensorRole.B, SensorRole.A),
                analysed = SensorRole.B,
            )

        val decoded = repo.decodeSensors(row(json.encodeToString(RecordedSensors.serializer(), declared)))

        assertEquals(declared, decoded)
        assertEquals(SensorRole.A, decoded?.secondaryRole, "the partner of the analysed role is the other one")
    }

    /**
     * A set that asked for two and armed one keeps the ask.
     *
     * The one case where `count` and `expected.size` legitimately differ, and
     * the reason the count is stored rather than read off the list: without
     * it, a shortfall would decode as a set that armed nothing.
     */
    @Test
    fun `a shortfall decodes as one sensor with no role and the ask intact`() {
        val declared = RecordedSensors(plannedCount = 2, count = 1)

        val decoded = repo.decodeSensors(row(json.encodeToString(RecordedSensors.serializer(), declared)))

        assertEquals(2, decoded?.plannedCount)
        assertEquals(1, decoded?.count)
        assertEquals(emptyList(), decoded?.expected)
        assertNull(decoded?.analysed)
        assertNull(decoded?.secondaryRole)
    }

    /**
     * A null column reads as null, and so does one that will not parse.
     *
     * Three cases collapse into one answer and none is softened into a value:
     * a row written before the column existed, an ordinary one-sensor set, and
     * a column written by a build this one does not understand. All three mean
     * the same thing to a reader -- one stream, no role -- and a plausible
     * default here would be a claim that the set declared something.
     */
    @Test
    fun `an absent or unreadable declaration reads as absent, never as a default`() {
        assertNull(repo.decodeSensors(row(null)))
        assertNull(repo.decodeSensors(row("{")))
        assertNull(repo.decodeSensors(row("""{"plannedCount":"two","count":1}""")))
    }

    /**
     * A role this build does not know does not become a role it does.
     *
     * The forward-compatibility case: a later build writing a third role would
     * otherwise decode here as whichever enum entry happened to be first, and
     * relabel somebody's capture.
     */
    /**
     * A stored declaration carrying a key this build does not know still
     * decodes, rather than reading as absent.
     *
     * The backward-compatibility half of the case above, and the one nothing
     * pinned. `decodeSensors` catches every exception and answers null, so a
     * strict decoder here would turn every row written by a build with one
     * more key into "this set declared nothing" -- silent, permanent, and
     * indistinguishable from an ordinary single-sensor set. What keeps that
     * from happening is `ignoreUnknownKeys` on the repository's own `Json`,
     * which is a configuration line nothing asserts.
     *
     * Taken as a characterization before #198 retires a key from this object:
     * every row already on a phone carries it, and they have to keep decoding
     * after it stops being a field.
     *
     * The roles are spelled `A` and `B` here, uppercase, because this column
     * is `RecordedSensors`' own serializer output and not the export wire form
     * -- `SensorCapturePolicy.wireOf` lowercases, and that vocabulary belongs
     * to the two published documents rather than to this row.
     */
    @Test
    fun `a stored declaration carrying a key this build does not know still decodes`() {
        val decoded =
            repo.decodeSensors(
                row("""{"plannedCount":2,"count":2,"expected":["A","B"],"analysed":"A","futureKey":7}"""),
            )

        assertEquals(2, decoded?.count, "an unknown key made the whole declaration unreadable")
        assertEquals(listOf(SensorRole.A, SensorRole.B), decoded?.expected)
        assertEquals(SensorRole.A, decoded?.analysed)
    }

    @Test
    fun `a declaration naming an unknown role is refused rather than mapped onto a known one`() {
        val decoded =
            repo.decodeSensors(row("""{"plannedCount":2,"count":2,"expected":["a","c"],"analysed":"a"}"""))

        assertNull(decoded, "an unknown role decoded into a known one")
    }
}
