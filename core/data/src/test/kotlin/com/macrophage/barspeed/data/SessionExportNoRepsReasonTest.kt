package com.macrophage.barspeed.data

import com.macrophage.barspeed.dsp.NoRepsReason
import com.macrophage.barspeed.dsp.RepAnalysis
import com.macrophage.barspeed.dsp.SetAnalysis
import com.macrophage.barspeed.model.SessionExport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A blank set says why, on the wire. Schema 1.18, issue #138.
 *
 * Until 1.18 a set whose segmentation resolved nothing published `reps: []`,
 * `summary: {}` and no `velocityLossBasis` -- byte-identical to a manual set
 * recorded with no sensor at all. Three sets of the field-37 session are in
 * exactly that state in the archive, and the lifter meets three blanks on a
 * 36-minute session with nothing saying which kind of blank they are.
 *
 * A separate file from [SessionExporterTest] because that class is already at
 * detekt's `LargeClass` limit, the same reason [SessionExportRepMarksTest] and
 * [SessionExportVelocityLossTest] are separate. The fakes are this file's own,
 * as every test file in this module keeps its own.
 *
 * Nothing here executes Room, SQLite or Android. What is verified is the
 * exporter's own mapping and nothing about what the database did with it. In
 * particular nothing here says the DSP's reason is the RIGHT reason -- that is
 * `BlankAnalysisReasonTest`'s question, in the module that can measure it.
 *
 * ## What the exporter is and is not allowed to decide
 *
 * The reason is READ off the stored analysis, never recomputed. `Exporters`
 * does not re-run the segmenter -- it has the raw stream and deliberately does
 * not inflate it for this -- so a set recorded before 1.18 has no reason to
 * read and publishes none, permanently. That is asserted below as an ABSENCE,
 * because it is the state every set already on disk is in.
 */
class SessionExportNoRepsReasonTest {
    // ---- fakes -------------------------------------------------------------

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

        override suspend fun updateRpe(setId: Long, rpe: Int?, failed: Boolean, warmup: Boolean) = Unit

        override suspend fun updateLimiter(setId: Long, limiter: String?, limiterNote: String?) = Unit

        override suspend fun updateWarmupMark(setId: Long, warmupMark: Boolean?) = Unit

        override suspend fun overrideReps(setId: Long, reps: Int) = Unit

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

    // ---- fixtures ----------------------------------------------------------

    private val json = Json { ignoreUnknownKeys = true }

    /** A set the segmenter emptied, carrying the reason the DSP reached. */
    private fun blank(reason: NoRepsReason?) = SetAnalysis(
        reps = emptyList(),
        sampleRateHz = 99.35,
        velocityLossPct = null,
        tempoCompliance = null,
        verdicts = emptyList(),
        noRepsReason = reason,
    )

    /**
     * A set that resolved one rep, which is the state `field-rdl-3010-10rep-s36-set04`
     * is in -- 123.64 m of runaway and one surviving rep. It carries no reason
     * and must publish none.
     */
    private val oneRep = SetAnalysis(
        reps = listOf(
            RepAnalysis(
                index = 0,
                eccS = 2.0,
                bottomPauseS = null,
                conS = 1.0,
                topPauseS = null,
                meanConVelMps = 0.4,
                peakConVelMps = 0.8,
                meanEccVelMps = -0.3,
                peakEccVelMps = -0.6,
                romM = 0.5,
                peakPowerW = null,
            ),
        ),
        sampleRateHz = 99.36,
        velocityLossPct = null,
        tempoCompliance = null,
        verdicts = emptyList(),
    )

    private fun row(analysis: SetAnalysis?) = SetRecordEntity(
        id = 5L,
        sessionId = 1L,
        orderIdx = 0,
        exerciseId = "romanian_deadlift",
        exerciseName = "Romanian Deadlift",
        loadKg = 52.163122551154075,
        actualReps = 10,
        repsManual = true,
        plannedReps = 10,
        startedAtMs = 1_000L,
        endedAtMs = 69_000L,
        // Empty rather than null: the column is non-null, and an empty string
        // is what SessionRepository.decodeAnalysis returns null for. That is
        // the "no analysis this exporter can read" state.
        analysisJson = analysis?.let { json.encodeToString(SetAnalysis.serializer(), it) } ?: "",
    )

    private suspend fun setObject(analysis: SetAnalysis?, includeRepDetail: Boolean = true): JsonObject {
        val dao =
            FakeSessionDao(
                session = SessionEntity(id = 1L, startedAtMs = 1_000L, endedAtMs = 69_000L),
                rows = listOf(row(analysis)),
            )
        val exporter = SessionExporter(SessionRepository(dao, FakeExerciseDao()), dispatcher = Dispatchers.Default)
        return Json.parseToJsonElement(exporter.exportJson(1L, includeRepDetail)!!)
            .jsonObject.getValue("exercises").jsonArray.single()
            .jsonObject.getValue("sets").jsonArray.single().jsonObject
    }

    private fun JsonObject.summary(): JsonObject = getValue("summary").jsonObject

    // ---- the key --------------------------------------------------------

    @Test
    fun `a blank set publishes the reason its analysis reached`() = runTest {
        // The whole of #138 on the wire. Before this the object below was
        // `{}` and a reader had no way to tell it from a set nobody measured.
        val summary = setObject(blank(NoRepsReason.RUNS_EXCEED_DISPLACEMENT_CAP)).summary()
        assertEquals(
            "runsExceedDisplacementCap",
            summary["noRepsReason"]?.jsonPrimitive?.content,
            "the blank set's summary does not say why it is blank",
        )
        assertEquals(setOf("noRepsReason"), summary.keys, "the blank summary carries something else as well")
    }

    @Test
    fun `the word published is the wire name, not the Kotlin one`() = runTest {
        // A document is read by a coach's tooling, not by Kotlin. Every value
        // must be one the published schema accepts, and the enum's own
        // constant name is not.
        NoRepsReason.entries.forEach { reason ->
            val word = setObject(blank(reason)).summary()["noRepsReason"]?.jsonPrimitive?.content
            assertEquals(reason.wireName, word, "${reason.name} reached the wire under the wrong word")
            assertTrue(
                word in SessionExport.VALID_NO_REPS_REASONS,
                "$word is not a value the published schema accepts",
            )
        }
    }

    @Test
    fun `it is written in both export modes`() = runTest {
        // Gated on the reps, never on the export mode, for the reason
        // repMetricsComplete gives at its own call site: the summary export is
        // where a reader most needs to be told the figures are absent, so a
        // caveat that appears only in the detailed export leaves the
        // summary-only reader holding a blank object with the warning removed.
        val reason = NoRepsReason.NO_MOVEMENT
        assertEquals(
            "noMovement",
            setObject(blank(reason), includeRepDetail = false).summary()["noRepsReason"]?.jsonPrimitive?.content,
            "the summary-only export drops the reason",
        )
        assertEquals(
            "noMovement",
            setObject(blank(reason), includeRepDetail = true).summary()["noRepsReason"]?.jsonPrimitive?.content,
            "the detailed export drops the reason",
        )
    }

    // ---- and where it must NOT appear -------------------------------------

    @Test
    fun `a set that resolved a rep publishes no reason`() = runTest {
        // Absence stays absence. This is the limit of the key, asserted rather
        // than described: a set resolving 1 of 10 through a runaway integrator
        // publishes a full summary and no reason, so the key's absence is not
        // a statement that the reps are right.
        val summary = setObject(oneRep).summary()
        assertNull(summary["noRepsReason"], "a set with a rep published a reason for having none")
        assertTrue("meanRom_m" in summary.keys, "the one-rep set stopped publishing its summary figures")
    }

    @Test
    fun `a set recorded before 1_18 still publishes an empty summary`() = runTest {
        // Every set already on disk. Its stored analysis has no such key, so
        // it deserializes to null and nothing invents one -- and the exporter
        // must not reach for the raw stream to fill it in, which would be a
        // figure computed under today's rule presented as what the set
        // recorded.
        assertEquals(
            emptyMap(),
            setObject(blank(null)).summary(),
            "a pre-1.18 blank set gained a key from somewhere",
        )
    }

    @Test
    fun `the two sets that legitimately explain nothing publish an empty summary`() = runTest {
        // A row whose analysis will not decode at all -- the rescue path's
        // state, where the exporter has no analysis object to read a reason
        // off and must not manufacture one.
        assertEquals(
            emptyMap(),
            setObject(null).summary(),
            "a row with no readable analysis was given a segmentation reason",
        )
        // And the genuinely sensorless set as RecordViewModel writes it: an
        // empty analysis carrying a verdict and no reason, because the
        // segmenter never ran. It is NOT the same state as a set the segmenter
        // emptied, and giving both a word would collapse the two again in the
        // other direction -- which is the collapse #138 is about, inverted.
        val sensorless = SetAnalysis(
            reps = emptyList(),
            sampleRateHz = 0.0,
            velocityLossPct = null,
            tempoCompliance = null,
            verdicts = listOf("No sensor data recorded."),
        )
        assertEquals(
            emptyMap(),
            setObject(sensorless).summary(),
            "a set nothing analysed was given a segmentation reason",
        )
    }

    @Test
    fun `a stored analysis that carries both reps and a reason publishes only the reps`() = runTest {
        // FOUND BY MUTATION TESTING, not by reading. Deleting
        // `.takeIf { reps.isEmpty() }` from the exporter left the whole suite
        // green, because every fixture with reps happened to carry a null
        // reason -- so the gate that keeps the two facts from contradicting
        // each other was decoration.
        //
        // SetAnalyzer cannot produce this row today: NoRepsReason.of returns
        // null the moment a span survives the cue bound. A STORED row can,
        // and that is the point -- analysisJson is frozen text written by
        // whatever version recorded the set, and a later change to the
        // analyzer that set the field before checking the reps would put a
        // reason on a set with figures in it. The reps win: a document saying
        // both "here are your reps" and "there were no reps" is worse than one
        // saying either.
        val contradiction = oneRep.copy(noRepsReason = NoRepsReason.NO_MOVEMENT)
        val summary = setObject(contradiction).summary()
        assertNull(summary["noRepsReason"], "a set with a rep published a reason for having none")
        assertTrue("meanRom_m" in summary.keys, "the set stopped publishing the figures it does have")
    }
}
