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
 * WHAT THE EXPORT PUBLISHES ONCE A REP'S DISPLACEMENT MAY BE UNBOUNDED. Issue
 * #291, schema 1.21. RED at the commit that adds this file.
 *
 * Asserted against the exported TEXT, on the terms
 * `SessionExportArtefactSampleTest` states: a typed assertion against a key the
 * exporter has not been wired to yet would fail to compile rather than fail, and
 * what a coach opening the artifact sees is the claim under review.
 *
 * TWO SET-LEVEL CLAIMS NARROW and nothing else does. `summary.meanRom_m` and
 * `summary.romSpread_pct` are taken over the reps whose displacement the
 * analysis can bound -- `RomBound` holds the rule -- and are absent below
 * `RomBound.MIN_BOUNDED_REPS` of them. The per-rep `rom_m` rows are published
 * unchanged, which is `AccelArtefact.peakEligible`'s own division: the rows are
 * what their windows measured and the summary line is the claim.
 *
 * Nothing here executes Room, SQLite or Android. `FakeSessionDao` implements the
 * `SessionDao` interface, so what is pinned is `SessionExporter`'s own mapping
 * and the question it asks of a stored row -- never what the database did.
 */
class SessionExportRomBoundTest {
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

    /**
     * One rep, with the displacement and the bound the case needs.
     *
     * The flag is what the ANALYZER froze into this row when the set was
     * recorded. The exporter reads it and cannot recompute it: the judgement
     * needs the sample series and the accepted anchors, neither of which this
     * class inflates.
     */
    private fun rep(index: Int, romM: Double, romBounded: Boolean?) = RepAnalysis(
        index = index,
        eccS = 2.0,
        bottomPauseS = 0.3,
        conS = 1.0,
        topPauseS = null,
        meanConVelMps = 0.5,
        peakConVelMps = 0.9,
        meanEccVelMps = -0.25,
        peakEccVelMps = -0.4,
        romM = romM,
        peakPowerW = 300.0,
        meanConPowerW = 150.0,
        romBounded = romBounded,
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
     * THE FIX on the summary artifact. Two reps are bounded and read 0.3 and
     * 0.5 m; a third is not and reads 2.0 m. The mean is the bounded pair's
     * 0.4 m and the spread is their 25.0 %, where today both are taken over all
     * three and read 0.933 m and 81.3 %.
     */
    @Test
    fun `the summary's range figures are taken over the reps the analysis can bound`() = runTest {
        val t = summary(analysisOf(listOf(rep(0, 0.3, true), rep(1, 0.5, true), rep(2, 2.0, false))))
        assertTrue("\"meanRom_m\": 0.4" in t, "expected the bounded mean, got: $t")
        assertTrue("\"romSpread_pct\": 25.0" in t, "expected the bounded spread, got: $t")
        assertTrue("0.933" !in t, "the summary still averages the unbounded rep, got: $t")
        assertTrue("81.3" !in t, "the summary still spreads over the unbounded rep, got: $t")
        // And the rep count is untouched, which is the whole terms of the trade.
        assertTrue("\"reps\": 3" in t, "the rep count moved, got: $t")
    }

    /**
     * A set with fewer than two bounded reps publishes NEITHER figure. Absent,
     * not 0.0 and not the all-reps number: on the committed field corpus this is
     * every capture, which is the post-set chip going dark instead of reading
     * 98.1 % on six bench reps performed to a count.
     */
    @Test
    fun `a set the analysis cannot bound publishes no range figures at all`() = runTest {
        val t = summary(analysisOf(listOf(rep(0, 0.4, false), rep(1, 1.9, false), rep(2, 0.5, false))))
        assertTrue("meanRom_m" !in t, "expected no mean at all, got: $t")
        assertTrue("romSpread_pct" !in t, "expected no spread at all, got: $t")
        assertTrue("\"reps\": 3" in t, "the rep count moved, got: $t")
    }

    /**
     * THE PER-REP ROWS ARE PUBLISHED UNCHANGED, and each says whether its own
     * displacement is bounded. A reader who takes the mean over
     * `repMetrics[].rom_m` will not reproduce `summary.meanRom_m`, and this key
     * is what tells them why.
     */
    @Test
    fun `each rep row publishes its own displacement and whether it is bounded`() = runTest {
        val t = detailed(analysisOf(listOf(rep(0, 0.3, true), rep(1, 2.0, false))))
        assertTrue("\"rom_m\": 0.3" in t, "expected the bounded row's displacement, got: $t")
        assertTrue("\"rom_m\": 2.0" in t, "expected the unbounded row's displacement, got: $t")
        assertTrue("\"romBounded\": true" in t, "expected the bounded row's flag, got: $t")
        assertTrue("\"romBounded\": false" in t, "expected the unbounded row's flag, got: $t")
    }

    /**
     * AN ARCHIVED SET KEEPS THE FIGURES IT HAS ALWAYS PUBLISHED. Its reps carry
     * no flag, permanently: the answer is frozen into the stored analysis and
     * nothing re-runs the estimator at export time. Reading a null as "might be
     * unbounded" would delete the range figures from every set on disk, which is
     * worse than the defect and indistinguishable from a set that measured
     * nothing.
     */
    @Test
    fun `an archived set with no flags keeps its mean and its spread, and publishes no key`() = runTest {
        val t = summary(analysisOf(listOf(rep(0, 0.3, null), rep(1, 0.5, null))))
        assertTrue("\"meanRom_m\": 0.4" in t, "expected the archived mean, got: $t")
        assertTrue("\"romSpread_pct\": 25.0" in t, "expected the archived spread, got: $t")
        val rows = detailed(analysisOf(listOf(rep(0, 0.3, null), rep(1, 0.5, null))))
        assertTrue("romBounded" !in rows, "expected no flag key at all, got: $rows")
    }

    /**
     * True and false are both published where they were measured. A bounded rep
     * saying so is not the same fact as a rep that was never asked, which is the
     * three-state doctrine `artefactSamples` already carries.
     */
    @Test
    fun `a bounded rep publishes true rather than omitting the key`() = runTest {
        val t = detailed(analysisOf(listOf(rep(0, 0.4, true), rep(1, 0.45, true))))
        assertTrue("\"romBounded\": true" in t, "expected the flag, got: $t")
        assertTrue("\"romBounded\": false" !in t, "no row should read false here, got: $t")
    }
}
