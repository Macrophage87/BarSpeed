package com.macrophage.barspeed.data

import com.macrophage.barspeed.dsp.SetAnalysis
import com.macrophage.barspeed.model.DualShortfall
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

        override suspend fun updateRpe(
            setId: Long,
            rpe: Int?,
            failed: Boolean,
            failedByLifter: Boolean?,
            warmup: Boolean,
        ) = Unit

        override suspend fun updateLimiter(setId: Long, limiter: String?, limiterNote: String?) = Unit

        override suspend fun updateWarmupMark(setId: Long, warmupMark: Boolean?) = Unit

        override suspend fun overrideReps(setId: Long, reps: Int) = Unit

        // Conformance only: SessionDao grew this member for #205 and Kotlin
        // requires it. Nothing in this file calls it.
        override suspend fun overrideLoad(setId: Long, loadKg: Double) = Unit

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
                count = 2,
                expected = listOf(SensorRole.B, SensorRole.A),
                analysed = SensorRole.B,
            )

        val decoded = repo.decodeSensors(row(json.encodeToString(RecordedSensors.serializer(), declared)))

        assertEquals(declared, decoded)
        assertEquals(SensorRole.A, decoded?.secondaryRole, "the partner of the analysed role is the other one")
    }

    /**
     * DIFFERENTIAL, issue #198. A set that recorded one stream because two
     * units could not be told apart keeps the REASON, which is what the ask
     * used to stand in for.
     *
     * `a shortfall decodes as one sensor with no role and the ask intact` is
     * the same case with plannedCount as its carrier. There is no ask left, so
     * either the reason is stored or the row becomes an ordinary single-sensor
     * set forever -- the gap-that-cannot-be-represented class, applied to the
     * one distinction a coach reading the corpus needs here.
     *
     * `count` and `expected.size` still legitimately differ, and that is still
     * why the count is stored rather than read off the list.
     */
    @Test
    fun `a shortfall decodes as one sensor with no role and the reason intact`() {
        val declared = RecordedSensors(count = 1, shortfall = DualShortfall.ROLES_UNASSIGNED)

        val decoded = repo.decodeSensors(row(json.encodeToString(RecordedSensors.serializer(), declared)))

        assertEquals(DualShortfall.ROLES_UNASSIGNED, decoded?.shortfall)
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
     *
     * The unparseable case used to be `{"plannedCount":"two","count":1}` and
     * is `{"count":"two"}` now. Not a weakening: #198 retires plannedCount, so
     * the old fixture stopped being unparseable at all -- an unknown key with
     * a nonsense value is skipped rather than refused, which is the next case
     * down. The type error has to sit on a key this build still reads or the
     * test asserts nothing about parsing.
     */
    @Test
    fun `an absent or unreadable declaration reads as absent, never as a default`() {
        assertNull(repo.decodeSensors(row(null)))
        assertNull(repo.decodeSensors(row("{")))
        assertNull(repo.decodeSensors(row("""{"count":"two"}""")))
    }

    /**
     * DIFFERENTIAL, issue #198. A row whose RETIRED key holds garbage still
     * decodes, rather than losing the declaration beside it.
     *
     * The upgrade case, and the reason it is worth a test of its own: an
     * installed build wrote `plannedCount` on every dual set, and after this
     * change the decoder does not know the key. `ignoreUnknownKeys` skips it
     * whatever it holds, so the count, roles and analysed role beside it
     * survive; today the same document is refused outright by the type error
     * and `decodeSensors` answers null, which would read as an ordinary
     * one-sensor set for the life of that row.
     */
    @Test
    fun `a row whose retired key holds garbage keeps the declaration beside it`() {
        val decoded =
            repo.decodeSensors(row("""{"plannedCount":"two","count":2,"expected":["A","B"],"analysed":"A"}"""))

        assertEquals(2, decoded?.count, "a garbage value in a retired key sank the whole declaration")
        assertEquals(listOf(SensorRole.A, SensorRole.B), decoded?.expected)
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
