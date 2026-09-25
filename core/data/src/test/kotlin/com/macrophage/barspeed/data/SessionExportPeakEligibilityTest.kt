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
 * WHAT THE EXPORT PUBLISHES AS A SET'S PEAKS ONCE A REP'S BAND AND ITS BOUND
 * COUNT. Issue #306, schema 1.22. RED at the commit that adds this file.
 *
 * Asserted against the exported TEXT, on the terms
 * `SessionExportArtefactSampleTest` states: what a coach opening the artifact
 * sees is the claim under review.
 *
 * `summary.peakConVel_mps` and `summary.peakPower_w` are taken over the reps
 * `AccelArtefact.isPeakEligible` admits -- no artefact sample in the span, none
 * in the guard band before it, a displacement the analysis can bound -- and
 * withheld where the result would fall below the mean of the same quantity
 * the summary publishes. Each row publishes its own band count as
 * `guardArtefactSamples`, so a reader can see why a row with no artefact in
 * its span was left out.
 *
 * Nothing here executes Room, SQLite or Android. `FakeSessionDao` implements the
 * `SessionDao` interface, so what is pinned is `SessionExporter`'s own mapping
 * and the question it asks of a stored row -- never what the database did.
 */
class SessionExportPeakEligibilityTest {
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

    /** One stored rep carrying what the rule reads, as the analyzer froze it. */
    private fun rep(index: Int, mean: Double, peak: Double, power: Double, band: Int?, bounded: Boolean?) =
        RepAnalysis(
            index = index,
            eccS = 2.0,
            bottomPauseS = 0.3,
            conS = 1.0,
            topPauseS = null,
            meanConVelMps = mean,
            peakConVelMps = peak,
            meanEccVelMps = -0.25,
            peakEccVelMps = -0.4,
            romM = 0.5,
            peakPowerW = power,
            meanConPowerW = 150.0,
            artefactSamples = if (band == null) null else 0,
            guardArtefactSamples = band,
            romBounded = bounded,
        )

    private fun analysisOf(reps: List<RepAnalysis>) = SetAnalysis(
        reps = reps,
        sampleRateHz = 100.0,
        velocityLossPct = null,
        tempoCompliance = null,
        verdicts = emptyList(),
        refusedDetections = 0,
    )

    private fun exporter(analysis: SetAnalysis): SessionExporter {
        val row = SetRecordEntity(
            id = 5L,
            sessionId = 1L,
            orderIdx = 0,
            exerciseId = "seated_overhead_press",
            exerciseName = "Seated Overhead Press",
            loadKg = 24.94758035055195,
            actualReps = analysis.reps.size,
            repsManual = true,
            plannedReps = 8,
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

    private suspend fun summary(analysis: SetAnalysis): String =
        exporter(analysis).exportJson(1L, includeRepDetail = false)!!

    private suspend fun detailed(analysis: SetAnalysis): String =
        exporter(analysis).exportJson(1L, includeRepDetail = true)!!

    /**
     * THE FIX on the summary artifact. Rep 0 is clean and bounded; rep 1 is
     * clean inside its span but unbounded and reads 1.9 m/s and 2000 W; rep 2
     * is bounded but carries two samples in the band before it and reads
     * 1.2 m/s and 900 W. The pair published is rep 0's, where the #290 rule
     * alone published rep 1's.
     */
    @Test
    fun `the summary's peak pair is taken over the reps the rule admits`() = runTest {
        val t = summary(
            analysisOf(
                listOf(
                    rep(0, 0.5, 0.8, 600.0, band = 0, bounded = true),
                    rep(1, 0.5, 1.9, 2000.0, band = 0, bounded = false),
                    rep(2, 0.5, 1.2, 900.0, band = 2, bounded = true),
                ),
            ),
        )
        assertTrue("\"peakConVel_mps\": 0.8" in t, "expected the eligible rep's peak velocity, got: $t")
        assertTrue("\"peakPower_w\": 600.0" in t, "expected the eligible rep's peak power, got: $t")
        assertTrue("2000.0" !in t, "the unbounded rep still sets the peak power, got: $t")
        assertTrue("900.0" !in t, "the rep after a floor contact still sets the peak power, got: $t")
        assertTrue("\"reps\": 3" in t, "the rep count moved, got: $t")
    }

    /**
     * A peak BELOW the mean the same summary publishes is withheld. The one
     * eligible rep peaks at 0.52 m/s; the set's mean drive velocity, over
     * every rep, is 0.7 m/s. A peak below a mean is not a peak.
     */
    @Test
    fun `a set peak below the set's own mean is not published`() = runTest {
        val t = summary(
            analysisOf(
                listOf(
                    rep(0, 0.5, 0.52, 310.0, band = 0, bounded = true),
                    rep(1, 0.9, 1.5, 1400.0, band = 0, bounded = false),
                ),
            ),
        )
        assertTrue("\"meanConVel_mps\": 0.7" in t, "expected the mean over every rep, got: $t")
        assertTrue("peakConVel_mps" !in t, "a peak velocity below the mean is published, got: $t")
    }

    /** Each row says how many samples above the bound sat in the band before it. */
    @Test
    fun `each rep row publishes its own guard-band count`() = runTest {
        val t = detailed(
            analysisOf(
                listOf(
                    rep(0, 0.5, 0.8, 600.0, band = 0, bounded = true),
                    rep(1, 0.5, 1.2, 900.0, band = 2, bounded = true),
                ),
            ),
        )
        assertTrue("\"guardArtefactSamples\": 0" in t, "expected the clean row's count, got: $t")
        assertTrue("\"guardArtefactSamples\": 2" in t, "expected the marked row's count, got: $t")
        assertTrue("\"peakPower_w\": 900.0" in t, "the marked row's own peak is not published, got: $t")
    }

    /**
     * AN ARCHIVED SET KEEPS THE PEAK IT HAS ALWAYS PUBLISHED. Its reps were
     * analysed before any of the three questions existed and carry null for
     * all of them, which the rule keeps; and its rows publish no band key.
     */
    @Test
    fun `an archived set keeps its peak pair and publishes no band key`() = runTest {
        val archived = listOf(
            rep(0, 0.5, 0.8, 600.0, band = null, bounded = null),
            rep(1, 0.5, 1.1, 700.0, band = null, bounded = null),
        )
        val t = summary(analysisOf(archived))
        assertTrue("\"peakConVel_mps\": 1.1" in t, "expected the archived peak velocity, got: $t")
        assertTrue("\"peakPower_w\": 700.0" in t, "expected the archived peak power, got: $t")
        val rows = detailed(analysisOf(archived))
        assertTrue("guardArtefactSamples" !in rows, "expected no band key at all, got: $rows")
    }
}
