package com.macrophage.barspeed.data

import com.macrophage.barspeed.dsp.PhaseComplianceResult
import com.macrophage.barspeed.dsp.RepAnalysis
import com.macrophage.barspeed.dsp.SetAnalysis
import com.macrophage.barspeed.dsp.TempoComplianceResult
import com.macrophage.barspeed.model.Tempo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `tempoCompliance.scoredPhases` is in the bytes `SessionExporter` writes,
 * empty or not (#66 (b)).
 *
 * The exporter's `Json` drops defaults (`encodeDefaults = false`), so the one
 * `@EncodeDefault(ALWAYS)` on `TempoComplianceExport.scoredPhases` is all that
 * keeps an empty list in the document -- and the shipped coach prompt reads an
 * absent key as "not stated" where an empty one means "nothing was graded".
 * Deleting that annotation line changed nothing any test could see.
 *
 * Byte-compared on the real exporter's text, line by line with the pretty
 * printer's indentation stripped, so the block's key order and every value's
 * spelling are pinned and the nesting depth is not. `ScoredPhasesEncodingTest`
 * in `:core:model` pins the same encoding upstream. A pin, green when written.
 *
 * Nothing here executes Room, SQLite or Android.
 */
class SessionExportScoredPhasesBytesTest {
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

        override suspend fun setsForSession(sessionId: Long): List<SetRecordEntity> = rows

        override fun observeSetsForSession(sessionId: Long): Flow<List<SetRecordEntity>> = flowOf(rows)

        override suspend fun rawStreamsForSet(setId: Long): List<RawStreamEntity> = emptyList()

        override suspend fun updateRpe(
            setId: Long,
            rpe: Int?,
            failed: Boolean,
            failedByLifter: Boolean?,
            warmup: Boolean,
        ) = Unit

        override suspend fun updateVoided(setId: Long, voided: Boolean, reason: String?) = Unit

        override suspend fun updateLimiter(setId: Long, limiter: String?, limiterNote: String?) = Unit

        override suspend fun updateWarmupMark(setId: Long, warmupMark: Boolean?) = Unit

        override suspend fun overrideReps(setId: Long, reps: Int) = Unit

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

    private fun rep(index: Int, eccS: Double?) = RepAnalysis(
        index = index,
        eccS = eccS,
        bottomPauseS = null,
        conS = 1.0,
        topPauseS = null,
        meanConVelMps = 0.4,
        peakConVelMps = 0.6,
        meanEccVelMps = eccS?.let { -0.2 },
        peakEccVelMps = eccS?.let { -0.3 },
        romM = 0.5,
        peakPowerW = null,
    )

    /** Eight reps, the even four of which resolved an eccentric. */
    private val reps = (1..8).map { rep(it, if (it % 2 == 0) 3.0 else null) }

    private fun row(compliance: TempoComplianceResult) = SetRecordEntity(
        id = 6L,
        sessionId = 1L,
        orderIdx = 0,
        exerciseId = "overhead_press",
        exerciseName = "Overhead Press",
        loadKg = 40.0,
        actualReps = 8,
        repsManual = true,
        plannedReps = 8,
        tempo = "3010",
        workBegan = true,
        startedAtMs = 1_788_342_174_823L,
        endedAtMs = 1_788_342_220_675L,
        analysisJson =
        json.encodeToString(
            SetAnalysis.serializer(),
            SetAnalysis(
                reps = reps,
                sampleRateHz = 99.4,
                velocityLossPct = null,
                tempoCompliance = compliance,
                verdicts = emptyList(),
            ),
        ),
    )

    private fun phase(name: String, prescribedS: Double, within: Int, of: Int, scored: Boolean) =
        PhaseComplianceResult(
            phase = name,
            prescribedS = prescribedS,
            actualMeanS = if (of == 0) null else prescribedS,
            worstDeviationS = 0.0,
            repsWithinTolerance = within,
            repsEvaluated = of,
            scored = scored,
        )

    private fun compliance(eccentricOf: Int, concentricOf: Int) =
        TempoComplianceResult(
            prescribed = Tempo.parse("3010"),
            toleranceS = 0.5,
            phases =
            listOf(
                phase("eccentric", 3.0, eccentricOf, eccentricOf, scored = eccentricOf > 0),
                phase("concentric", 1.0, concentricOf, concentricOf, scored = concentricOf > 0),
            ),
            repsFullyCompliant = maxOf(eccentricOf, concentricOf),
            repsEvaluated = maxOf(eccentricOf, concentricOf),
            prescribedEccConRatio = 3.0,
        )

    /** The exporter's text for the one set's `tempoCompliance`, one trimmed line per element. */
    private suspend fun tempoLines(compliance: TempoComplianceResult): List<String> {
        val session = SessionEntity(id = 1L, startedAtMs = 1_788_342_000_000L, endedAtMs = 1_788_343_100_000L)
        val rows = listOf(row(compliance))
        val repository = SessionRepository(FakeSessionDao(session = session, rows = rows), FakeExerciseDao())
        val exporter = SessionExporter(repository, dispatcher = Dispatchers.Default)
        val text = exporter.exportJson(1L, includeRepDetail = false)!!
        val lines = text.lines().map { it.trim() }
        val start = lines.indexOf("\"tempoCompliance\": {")
        return lines.subList(start, lines.subList(start, lines.size).indexOfFirst { it.startsWith("}") } + start + 1)
    }

    @Test
    fun `a set that graded nothing writes an empty scoredPhases`() = runTest {
        assertEquals(
            listOf(
                "\"tempoCompliance\": {",
                "\"prescribed\": \"3010\",",
                "\"tolerance_s\": 0.5,",
                "\"withinTolerance\": 0,",
                "\"of\": 0,",
                "\"scoredPhases\": [],",
                "\"prescribedEccConRatio\": 3.0",
                "},",
            ),
            tempoLines(compliance(eccentricOf = 0, concentricOf = 0)),
        )
    }

    @Test
    fun `a set graded on the drive alone writes the drive and nothing else`() = runTest {
        assertEquals(
            listOf(
                "\"tempoCompliance\": {",
                "\"prescribed\": \"3010\",",
                "\"tolerance_s\": 0.5,",
                "\"withinTolerance\": 8,",
                "\"of\": 8,",
                "\"scoredPhases\": [",
                "\"concentric\"",
                "],",
                "\"prescribedEccConRatio\": 3.0",
                "},",
            ),
            tempoLines(compliance(eccentricOf = 0, concentricOf = 8)),
        )
    }
}
