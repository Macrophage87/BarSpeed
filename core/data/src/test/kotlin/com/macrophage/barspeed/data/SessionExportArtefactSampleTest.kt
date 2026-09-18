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
 * WHAT THE EXPORT PUBLISHES ONCE A SAMPLE THE SENSOR CANNOT HAVE MEASURED IS
 * COUNTED. Issues #290 and #255, schema 1.21.
 *
 * Asserted against the exported TEXT, on the terms
 * `SessionExportRefusedDetectionTest` states for #125's pair: this is the
 * differential for the export half, and a typed assertion against a key the
 * exporter has not been wired to yet would fail to compile rather than fail.
 * What is asserted is what a coach opening the artifact sees.
 *
 * TWO THINGS ARE PINNED AND THE SECOND IS THE FIX. The counts are published --
 * per set on the summary artifact and per rep on the detailed one -- and the
 * SET'S PEAK PAIR is taken over the reps that carry none. field-42 set 2
 * published `peakPower_w` 3606.3 on a 24.9476 kg press because one rep of nine
 * carried one such sample and a peak is a maximum over its window.
 *
 * Nothing here executes Room, SQLite or Android. `FakeSessionDao` implements the
 * `SessionDao` interface, so what is pinned is `SessionExporter`'s own mapping
 * and the question it asks of a stored row -- never what the database did.
 */
class SessionExportArtefactSampleTest {
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
     * One rep, with the peak pair and the artefact count the case needs.
     *
     * The count is what the ANALYZER froze into this row when the set was
     * recorded. The exporter reads it and cannot recompute it: the judgement
     * needs the sample stream, which this class never inflates.
     */
    private fun rep(index: Int, peakPowerW: Double, peakConVelMps: Double, artefactSamples: Int?) = RepAnalysis(
        index = index,
        eccS = 2.0,
        bottomPauseS = 0.3,
        conS = 1.0,
        topPauseS = null,
        meanConVelMps = 0.5,
        peakConVelMps = peakConVelMps,
        meanEccVelMps = -0.25,
        peakEccVelMps = -0.4,
        romM = 0.5,
        peakPowerW = peakPowerW,
        meanConPowerW = 150.0,
        artefactSamples = artefactSamples,
    )

    private fun analysisOf(reps: List<RepAnalysis>, artefactSamples: Int?) = SetAnalysis(
        reps = reps,
        sampleRateHz = 100.0,
        velocityLossPct = null,
        tempoCompliance = null,
        verdicts = emptyList(),
        refusedDetections = 0,
        artefactSamples = artefactSamples,
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

    /** field-42 set 2's shape: nine reps, two of them carrying one such sample. */
    private fun fieldFortyTwoSetTwo() = analysisOf(
        reps = listOf(
            rep(0, 260.3, 0.929, 0),
            rep(1, 268.1, 1.022, 0),
            rep(2, 219.1, 0.851, 0),
            rep(3, 3606.3, 2.516, 1),
            rep(4, 297.3, 1.053, 0),
            rep(5, 1961.8, 1.424, 1),
            rep(6, 329.4, 1.155, 0),
            rep(7, 241.5, 0.874, 0),
            rep(8, 214.1, 0.773, 0),
        ),
        artefactSamples = 3,
    )

    /**
     * THE FIX, on the summary artifact. The peak pair is taken over the seven
     * reps that carry no such sample, so 3606.3 W and 2.516 m/s become 329.4 W
     * and 1.155 m/s -- from the same nine reps, none of them removed.
     */
    @Test
    fun `the set's peak pair is taken over the reps carrying no out-of-range sample`() = runTest {
        val t = summary(fieldFortyTwoSetTwo())
        assertTrue("\"peakPower_w\": 329.4" in t, "expected the eligible peak power, got: $t")
        assertTrue("\"peakConVel_mps\": 1.155" in t, "expected the eligible peak velocity, got: $t")
        assertTrue("3606.3" !in t, "the summary still publishes the artefact's power, got: $t")
        assertTrue("2.516" !in t, "the summary still publishes the artefact's velocity, got: $t")
        // And nothing else about the set moved: the count and the means are
        // computed over every rep exactly as before.
        assertTrue("\"reps\": 9" in t, "the rep count moved, got: $t")
        assertTrue("\"meanRom_m\": 0.5" in t, "meanRom_m is not a peak and must not move, got: $t")
    }

    /**
     * The set-level count, on the SUMMARY artifact rather than only the
     * detailed one: it qualifies the peak pair the summary publishes, so a
     * caveat visible only in the detailed artifact would leave the summary
     * reader holding the figures with the warning removed.
     */
    @Test
    fun `the set publishes how many of its samples the sensor cannot have measured`() = runTest {
        val t = summary(fieldFortyTwoSetTwo())
        assertTrue("\"artefactSamples\": 3" in t, "expected the set count in the wire form, got: $t")
    }

    /**
     * 0 and absent are different facts and both have to survive the wire. A set
     * analysed under this rule and carrying none publishes the zero; a set
     * analysed before the rule existed publishes no key at all, permanently.
     */
    @Test
    fun `a counted zero is published and an unmeasured set stays absent`() = runTest {
        val zero = summary(analysisOf(listOf(rep(0, 300.0, 0.9, 0)), artefactSamples = 0))
        assertTrue("\"artefactSamples\": 0" in zero, "expected the zero, got: $zero")

        val none = summary(analysisOf(listOf(rep(0, 300.0, 0.9, null)), artefactSamples = null))
        assertTrue("artefactSamples" !in none, "expected no key at all, got: $none")
    }

    /**
     * A REP ANALYSED BEFORE THE COUNT EXISTED IS KEPT IN THE PEAK POPULATION.
     *
     * The alternative -- reading a null as "might carry one" -- would make every
     * set already on disk publish no peak at all, which is worse than the defect
     * and indistinguishable from a set that measured nothing.
     */
    @Test
    fun `an archived set with no counts keeps the peak it has always published`() = runTest {
        val t = summary(
            analysisOf(
                reps = listOf(rep(0, 552.4, 1.044, null), rep(1, 300.0, 0.8, null)),
                artefactSamples = null,
            ),
        )
        assertTrue("\"peakPower_w\": 552.4" in t, "an archived set lost its peak, got: $t")
        assertTrue("\"peakConVel_mps\": 1.044" in t, "an archived set lost its peak velocity, got: $t")
    }

    /**
     * A set with no eligible rep publishes NO peak pair rather than a low one.
     *
     * Absence is the only honest answer there: every rep's peak was taken across
     * a reading the sensor cannot have measured, so the set has no peak it can
     * bound. A zero would read as a set the lifter moved nothing in.
     */
    @Test
    fun `a set whose every rep carries one publishes no peak pair`() = runTest {
        val t = summary(
            analysisOf(
                reps = listOf(rep(0, 4347.4, 1.246, 6), rep(1, 3430.7, 1.214, 2)),
                artefactSamples = 18,
            ),
        )
        assertTrue("peakPower_w" !in t, "expected no peak power at all, got: $t")
        assertTrue("peakConVel_mps" !in t, "expected no peak velocity at all, got: $t")
        assertTrue("\"artefactSamples\": 18" in t, "and the count that says why, got: $t")
        // The non-peak summary figures are still published, because none of them
        // is a maximum one sample can set.
        assertTrue("\"meanConVel_mps\": 0.5" in t, "the means went with the peaks, got: $t")
    }

    /**
     * The PER-REP count, on the detailed artifact, which is where a reader finds
     * out WHICH rows to distrust. The rows still publish the peaks their own
     * windows measured; this key is what says not to trust the pair.
     */
    @Test
    fun `each rep publishes its own count on the detailed artifact`() = runTest {
        val t = detailed(fieldFortyTwoSetTwo())
        assertTrue("\"artefactSamples\": 1" in t, "expected a marked rep, got: $t")
        assertTrue("\"artefactSamples\": 0" in t, "expected the counted-clean reps, got: $t")
        // The marked row keeps the figures it measured -- nothing is deleted.
        assertTrue("\"peakPower_w\": 3606.3" in t, "the marked rep lost its own figure, got: $t")
    }
}
