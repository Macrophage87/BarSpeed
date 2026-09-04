package com.macrophage.barspeed.data

import com.macrophage.barspeed.dsp.RepAnalysis
import com.macrophage.barspeed.dsp.SetAnalysis
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The two keys issue #125's refusal has to ride out on, asserted against the
 * exported TEXT.
 *
 * Text and not a typed field on purpose: this is the differential for the
 * export half, written where `SetExport` does not yet declare either key, so
 * a typed assertion would not compile and could not be shown failing. What is
 * asserted is what a coach opening the artifact sees.
 *
 * Nothing here executes Room, SQLite or Android. `FakeSessionDao` implements
 * the `SessionDao` interface, so what is pinned is `SessionExporter`'s own
 * mapping and the question it asks of a stored row -- never what the database
 * did with it.
 */
class SessionExportRefusedDetectionTest {
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

        override suspend fun updateRpe(
            setId: Long,
            rpe: Int?,
            failed: Boolean,
            failedByLifter: Boolean?,
            warmup: Boolean,
        ) = Unit

        override suspend fun updateLimiter(setId: Long, limiter: String?, limiterNote: String?) = Unit

        override suspend fun updateWarmupMark(setId: Long, warmupMark: Boolean?) = Unit

        // Conformance only: SessionDao grew this member for #60 and Kotlin
        // requires it. Nothing in this file calls it.
        override suspend fun updateVoided(setId: Long, voided: Boolean, reason: String?) = Unit

        override suspend fun overrideReps(setId: Long, reps: Int) = Unit

        // Conformance only: SessionDao grew this member for #205 and Kotlin
        // requires it. Nothing in this file calls it.
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

    private val json = Json { ignoreUnknownKeys = true }

    private fun rep(index: Int) = RepAnalysis(
        index = index,
        eccS = 2.0,
        bottomPauseS = 0.3,
        conS = 1.0,
        topPauseS = 0.4,
        meanConVelMps = 0.6 - index * 0.05,
        peakConVelMps = 0.7 - index * 0.05,
        meanEccVelMps = -0.25,
        peakEccVelMps = -0.4,
        romM = 0.5,
        peakPowerW = 800.0,
        meanConPowerW = 500.0,
    )

    /**
     * [refused] and [reason] are what the analyzer FROZE into this row's
     * stored analysis when the set was recorded. The exporter reads them; it
     * cannot recompute them, because the rep list it holds is the list after
     * the refusal and so cannot say how many were removed to produce it.
     */
    private fun analysisOf(reps: Int, refused: Int?, reason: String?, beforeWorkStart: Int? = null) = SetAnalysis(
        reps = (0 until reps).map { rep(it) },
        sampleRateHz = 100.0,
        velocityLossPct = null,
        tempoCompliance = null,
        verdicts = emptyList(),
        refusedDetections = refused,
        refusedDetectionReason = reason,
        detectionsBeforeWorkStart = beforeWorkStart,
    )

    private fun exporter(analysis: SetAnalysis): SessionExporter {
        val row = SetRecordEntity(
            id = 5L,
            sessionId = 1L,
            orderIdx = 0,
            exerciseId = "seated_leg_curl",
            exerciseName = "Seated Leg Curl",
            loadKg = 40.0,
            actualReps = analysis.reps.size,
            repsManual = false,
            plannedReps = 12,
            startedAtMs = 1_000L,
            endedAtMs = 61_000L,
            analysisJson = json.encodeToString(SetAnalysis.serializer(), analysis),
        )
        val dao = FakeSessionDao(
            session = SessionEntity(id = 1L, startedAtMs = 1_000L, endedAtMs = 61_000L),
            rows = listOf(row),
        )
        return SessionExporter(SessionRepository(dao, FakeExerciseDao()))
    }

    private suspend fun text(analysis: SetAnalysis): String =
        exporter(analysis).exportJson(1L, includeRepDetail = false)!!

    /**
     * Published on the SUMMARY export, not gated on `includeRepDetail`: the
     * count qualifies `summary`, `velocityLoss_pct` and `velocityLossBasis`,
     * which the summary-only artifact publishes, so a caveat that appeared
     * only in the detailed one would leave that reader holding the figures
     * with the warning removed.
     */
    @Test
    fun `a set whose analyzer refused a detection publishes the count and the word`() = runTest {
        val t = text(analysisOf(reps = 4, refused = 1, reason = "unpairedRangeOutlier"))
        assertTrue("\"refusedDetections\": 1" in t, "expected the count in the wire form, got: $t")
        assertTrue(
            "\"refusedDetectionReason\": \"unpairedRangeOutlier\"" in t,
            "expected the word in the wire form, got: $t",
        )
    }

    /**
     * 0 and absent are different facts and both have to survive the wire.
     * A set that had a bound derived and refused nothing publishes the zero;
     * a set too small for a bound publishes no key at all, which is also what
     * every set recorded before this shipped publishes, permanently.
     */
    @Test
    fun `a zero is published and an absence stays absent`() = runTest {
        val zero = text(analysisOf(reps = 4, refused = 0, reason = null))
        assertTrue("\"refusedDetections\": 0" in zero, "expected the zero in the wire form, got: $zero")
        assertTrue("refusedDetectionReason" !in zero, "no reason when nothing was refused, got: $zero")

        val none = text(analysisOf(reps = 3, refused = null, reason = null))
        assertTrue("refusedDetections" !in none, "expected no key at all, got: $none")
    }

    /**
     * The head-of-stream count (#245), on the same wire and under the same
     * three-state contract. A GREEN pin on a key the wiring commit introduced
     * rather than a differential.
     *
     * Published on the SUMMARY export for `refusedDetections`' reason: it
     * qualifies the same three figures.
     */
    @Test
    fun `a set that resolved detections before its work began publishes the count`() = runTest {
        val t = text(analysisOf(reps = 4, refused = 0, reason = null, beforeWorkStart = 3))
        assertTrue("\"detectionsBeforeWorkStart\": 3" in t, "expected the count in the wire form, got: $t")
    }

    /**
     * The two counts are INDEPENDENT, which is the reason they are two keys.
     *
     * A three-detection set with a known work-start instant publishes a
     * head-of-stream count and NO `refusedDetections`: no range bound could be
     * derived from three detections, and that absence is a fact about the set
     * that a single merged count could not have carried alongside the 1.
     */
    @Test
    fun `a small set can carry one count and not the other`() = runTest {
        val t = text(analysisOf(reps = 3, refused = null, reason = null, beforeWorkStart = 1))
        assertTrue("\"detectionsBeforeWorkStart\": 1" in t, "expected the head count, got: $t")
        assertTrue("refusedDetections" !in t, "no range bound could be derived, so no key, got: $t")
    }

    /**
     * Zero and absent, the same pair `refusedDetections` carries. Absent here
     * means the set has no work-start instant, which is every ad-hoc set and
     * every set recorded before database v15 (#216).
     */
    @Test
    fun `a head-of-stream zero is published and an absent instant stays absent`() = runTest {
        val zero = text(analysisOf(reps = 4, refused = 0, reason = null, beforeWorkStart = 0))
        assertTrue("\"detectionsBeforeWorkStart\": 0" in zero, "expected the zero, got: $zero")

        val none = text(analysisOf(reps = 4, refused = 0, reason = null, beforeWorkStart = null))
        assertTrue("detectionsBeforeWorkStart" !in none, "expected no key at all, got: $none")
    }
}
