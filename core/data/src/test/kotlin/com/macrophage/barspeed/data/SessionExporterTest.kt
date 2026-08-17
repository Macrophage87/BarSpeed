package com.macrophage.barspeed.data

import com.macrophage.barspeed.dsp.PhaseComplianceResult
import com.macrophage.barspeed.dsp.RepAnalysis
import com.macrophage.barspeed.dsp.SetAnalysis
import com.macrophage.barspeed.dsp.TempoComplianceResult
import com.macrophage.barspeed.model.SetExport
import com.macrophage.barspeed.model.Tempo
import com.macrophage.barspeed.model.VoiceCue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What [SessionExporter] publishes for one set, and how the summary-only export
 * differs from the detailed one.
 *
 * The two exports are two different artifacts the lifter shares from the
 * session-detail screen -- `summary.json` and `detailed.json` -- and both are
 * read by a coach or a model that was not in the gym. Everything asserted here
 * is about which facts and which caveats reach that reader.
 *
 * The analyses are built by hand rather than by running [SetAnalyzer] over
 * samples. The exporter's job is mapping, not measuring: a fixture that went
 * through the DSP would couple these assertions to segmenter behaviour, and a
 * segmenter change would then red a test about JSON field placement.
 *
 * Nothing here executes Room, SQLite or Android. The DAOs are interfaces, so
 * the fake below stands in for them; what is verified is the exporter's own
 * mapping, and nothing about what the database did with it. Each test file in
 * this module carries its own fakes, as [SessionRepositoryRecordSetTest] and
 * [SessionRepositoryEndSessionTest] both do.
 */
class SessionExporterTest {
    // ---- fakes -------------------------------------------------------------

    private class FakeSessionDao(
        private val session: SessionEntity,
        private val rows: List<SetRecordEntity>,
        private val streams: Map<Long, List<RawStreamEntity>> = emptyMap(),
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

        override suspend fun rawStreamsForSet(setId: Long): List<RawStreamEntity> = streams[setId].orEmpty()

        override suspend fun updateRpe(setId: Long, rpe: Int?, failed: Boolean, warmup: Boolean) = Unit

        override suspend fun overrideReps(setId: Long, reps: Int) = Unit

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

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Mean and peak drive velocity per rep, written as literals rather than
     * derived from each other.
     *
     * `peakConVel_mps` is the one summary field the exporter passes through
     * unrounded (`reps.maxOfOrNull { it.peakConVelMps }`), so a peak computed
     * as `mean + 0.3` arrives as 0.8999999999999999 and an assertion on 0.9
     * fails. That is the exporter behaving as written; the fixture was wrong.
     * Pinned as its own fact below.
     */
    private val repVelocities =
        listOf(0.60 to 0.90, 0.55 to 0.85, 0.50 to 0.80, 0.45 to 0.75, 0.40 to 0.70)

    private fun rep(index: Int) = RepAnalysis(
        index = index,
        eccS = 2.0,
        bottomPauseS = 0.3,
        conS = 1.0,
        topPauseS = 0.4,
        meanConVelMps = repVelocities[index].first,
        peakConVelMps = repVelocities[index].second,
        meanEccVelMps = -0.25,
        peakEccVelMps = -0.4,
        romM = 0.5,
        peakPowerW = 800.0,
        meanConPowerW = 500.0,
    )

    private val compliance =
        TempoComplianceResult(
            prescribed = Tempo.parse("2-0-1-0"),
            toleranceS = 0.5,
            phases =
            listOf(
                PhaseComplianceResult("eccentric", 2.0, 2.0, 0.1, 3, 3, scored = true),
                PhaseComplianceResult("bottomPause", 0.0, 0.3, 0.3, 0, 3, scored = false),
            ),
            repsFullyCompliant = 2,
            repsEvaluated = 3,
            prescribedEccConRatio = 2.0,
            actualEccConRatio = 2.1,
        )

    /** A set the sensor segmented into [segmentedReps] reps, with tempo and velocity loss. */
    private fun analysis(segmentedReps: Int) = SetAnalysis(
        reps = (0 until segmentedReps).map { rep(it) },
        sampleRateHz = 100.0,
        velocityLossPct = if (segmentedReps >= 2) 16.7 else null,
        tempoCompliance = if (segmentedReps >= 1) compliance else null,
        verdicts = listOf("Bar speed held up well."),
    )

    private fun row(analysis: SetAnalysis, actualReps: Int, repsManual: Boolean) = SetRecordEntity(
        id = 5L,
        sessionId = 1L,
        orderIdx = 0,
        exerciseId = "back_squat",
        exerciseName = "Back Squat",
        loadKg = 100.0,
        actualReps = actualReps,
        repsManual = repsManual,
        plannedReps = 10,
        tempo = "2-0-1-0",
        startedAtMs = 1_000L,
        endedAtMs = 61_000L,
        analysisJson = json.encodeToString(SetAnalysis.serializer(), analysis),
    )

    private val cueStream =
        RawStreamEntity(
            id = 9L,
            setId = 5L,
            kind = RawStreamEntity.KIND_CUES,
            csvGzip = Gzip.compress(CueCsv.encode(listOf(VoiceCue(1_100L, "Rep 1")))),
        )

    private fun exporter(analysis: SetAnalysis, actualReps: Int, repsManual: Boolean): SessionExporter {
        val dao =
            FakeSessionDao(
                session = SessionEntity(id = 1L, startedAtMs = 1_000L, endedAtMs = 61_000L),
                rows = listOf(row(analysis, actualReps, repsManual)),
                streams = mapOf(5L to listOf(cueStream)),
            )
        return SessionExporter(SessionRepository(dao, FakeExerciseDao()))
    }

    private suspend fun setOf(
        analysis: SetAnalysis,
        actualReps: Int,
        repsManual: Boolean,
        includeRepDetail: Boolean,
    ): SetExport = exporter(analysis, actualReps, repsManual)
        .buildExport(1L, includeRepDetail)!!
        .exercises.single().sets.single()

    // ---- what the two modes differ by, and what they must not -------------

    /**
     * The only fields the export mode is allowed to decide.
     *
     * This is the pin the [SetExport.repMetricsComplete] change is measured
     * against: everything NOT listed here has to be identical in both
     * artifacts, because it describes the set rather than the level of detail
     * requested.
     */
    @Test
    fun `only the per-rep array and the voice cues depend on the export mode`() = runTest {
        val summary = setOf(analysis(3), actualReps = 3, repsManual = false, includeRepDetail = false)
        val detailed = setOf(analysis(3), actualReps = 3, repsManual = false, includeRepDetail = true)
        assertNull(summary.repMetrics)
        assertNull(summary.voiceCues)
        assertEquals(3, detailed.repMetrics?.size)
        assertEquals(listOf(VoiceCue(1_100L, "Rep 1")), detailed.voiceCues)
        // Everything the flag qualifies is mode-independent and must stay so.
        assertEquals(detailed.velocityLossPct, summary.velocityLossPct)
        assertEquals(detailed.tempoCompliance, summary.tempoCompliance)
        assertEquals(detailed.summary, summary.summary)
        assertEquals(detailed.reps, summary.reps)
        assertEquals(detailed.repsManual, summary.repsManual)
    }

    /**
     * The three figures the coverage flag warns about are published in BOTH
     * modes and all three come from the same segmenter rep list.
     *
     * Named individually rather than folded into the equality assertion above,
     * because "identical in both modes" would also be satisfied by all three
     * being absent in both. What matters is that they are PRESENT in the
     * summary-only export while being drawn from a rep list the reader cannot
     * see.
     */
    @Test
    fun `velocity loss, tempo compliance and the summary are published without per-rep detail`() = runTest {
        val summary = setOf(analysis(3), actualReps = 10, repsManual = true, includeRepDetail = false)
        assertEquals(16.7, summary.velocityLossPct)
        assertEquals("2010", summary.tempoCompliance?.prescribed)
        assertEquals(3, summary.tempoCompliance?.of)
        assertEquals(2, summary.tempoCompliance?.withinTolerance)
        assertEquals(listOf("eccentric"), summary.tempoCompliance?.scoredPhases)
        assertEquals(0.55, summary.summary.meanConVelMps)
        assertEquals(0.9, summary.summary.peakConVelMps)
        assertEquals(0.5, summary.summary.meanRomM)
        assertEquals(800.0, summary.summary.peakPowerW)
    }

    /**
     * Six of the seven summary figures are rounded on the way out and
     * `peakConVel_mps` is not.
     *
     * Pinned because it is not obvious from the field list and it cost this
     * file a red on its first run: an averaged figure lands on a clean decimal
     * only because `round3` puts it there, while the peak is whichever double
     * the DSP produced. A reader comparing `meanConVel_mps` and
     * `peakConVel_mps` is not looking at two figures of the same precision.
     */
    @Test
    fun `the summary rounds every figure it averages and passes the peak through raw`() = runTest {
        val raw = 0.123_456_789
        val one =
            SetAnalysis(
                reps = listOf(rep(0).copy(meanConVelMps = raw, peakConVelMps = raw, romM = raw, meanConPowerW = raw)),
                sampleRateHz = 100.0,
                velocityLossPct = null,
                tempoCompliance = null,
                verdicts = emptyList(),
            )
        val summary = setOf(one, actualReps = 1, repsManual = false, includeRepDetail = false).summary
        assertEquals(0.123, summary.meanConVelMps)
        assertEquals(0.123, summary.meanRomM)
        assertEquals(0.1, summary.meanConPowerW)
        assertEquals(raw, summary.peakConVelMps)
    }

    // ---- the coverage flag as it stands ------------------------------------

    /**
     * The detailed export already answers the question correctly in both
     * directions. These two pins are what the summary-only fix must reproduce,
     * not replace.
     */
    @Test
    fun `the detailed export reports incomplete coverage when the counts disagree`() = runTest {
        val detailed = setOf(analysis(3), actualReps = 10, repsManual = true, includeRepDetail = true)
        assertEquals(false, detailed.repMetricsComplete)
        assertEquals(10, detailed.reps)
        assertEquals(3, detailed.repMetrics?.size)
    }

    @Test
    fun `the detailed export reports complete coverage when the counts agree`() = runTest {
        val detailed = setOf(analysis(5), actualReps = 5, repsManual = true, includeRepDetail = true)
        assertEquals(true, detailed.repMetricsComplete)
    }

    /**
     * A set the sensor segmented into nothing -- a plank, or a set recorded
     * with no sensor connected -- has no coverage to report, and that is a
     * different fact from incomplete coverage.
     *
     * The flag stays absent in BOTH modes here, and must go on doing so: with
     * no reps there is nothing for it to qualify. `summary` renders as an empty
     * object and both `velocityLoss_pct` and `tempoCompliance` are dropped, so
     * a reader is not being shown numbers whose provenance is being withheld.
     * This pin is what stops the fix turning "nothing was measured" into
     * "measurement disagrees with the lifter".
     */
    @Test
    fun `a set with no segmented reps reports no coverage flag in either mode`() = runTest {
        val none = analysis(0)
        val summary = setOf(none, actualReps = 12, repsManual = true, includeRepDetail = false)
        val detailed = setOf(none, actualReps = 12, repsManual = true, includeRepDetail = true)
        assertNull(summary.repMetricsComplete)
        assertNull(detailed.repMetricsComplete)
        assertNull(summary.repMetrics)
        assertNull(detailed.repMetrics)
        assertNull(summary.velocityLossPct)
        assertNull(summary.tempoCompliance)
        assertNull(summary.summary.meanConVelMps)
        assertEquals(12, summary.reps)
    }

    /**
     * `explicitNulls = false`, so a null flag is not written at all. The wire
     * form is what a coach reads, and "absent" is the only way this exporter
     * can express "not stated" -- there is no third rendering.
     */
    @Test
    fun `an absent coverage flag is omitted from the JSON rather than written as null`() = runTest {
        val text = exporter(analysis(0), actualReps = 12, repsManual = true).exportJson(1L, false)!!
        assertTrue("repMetricsComplete" !in text, "expected the key to be absent entirely, got:\n$text")
        assertTrue("null" !in text, "the exporter must never write a null literal, got:\n$text")
    }
}
