package com.macrophage.barspeed.data

import com.macrophage.barspeed.dsp.SetAnalysis
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
import kotlin.test.assertTrue

/**
 * Differentials for export 1.23 (#157): the row has held a set's working
 * targets, the plan's tempo and its rest instant since database v20, and
 * until export 1.23 the session document published none of them.
 *
 * FOUR OF THE SEVEN FAIL WHEN THEY ARE WRITTEN. `SessionExporter` has no line
 * for `workingReps`, `workingLoad_kg`, `workingDuration_s`, `plannedTempo` or
 * `restMeasured_s`, so field-45's set 8 -- planned 10, lowered to 6, 6 done --
 * still reads "planned 10, did 6, not failed" to a coach.
 *
 * THE THREE THAT PASS HERE guard absence, which the fix must keep: a row
 * written before v20 publishes none of the five (it stored none, and a
 * backfill from the plan would be the #157 defect itself); the LAST set of a
 * session has no next START, so no measured rest; and a next START earlier
 * than the rest instant describes no rest, so none is published rather than a
 * negative one.
 *
 * THE FIXTURE is one session of four sets, each a different exercise so each
 * is found by name: a squat run to a RAISED rep target (6 planned, 8 working)
 * on a raised load (100.0 planned, 102.5 working) later corrected down to
 * 97.5, under an adjusted tempo (3010 planned, 2010 run); a plank run to a
 * LOWERED hold (45 planned, 30 working); a row written before v20; and a
 * deadlift, last. Every working figure differs from its planned sibling and
 * from the actual one, so a mapping that reads the wrong column lands a number
 * the assertion names. The rest instants are neither a set's start nor its
 * end.
 *
 * Nothing here executes Room, SQLite or Android.
 */
class SessionExportWorkingTargetsTest {
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

    // ---- fixtures ----------------------------------------------------------

    private val noReps =
        Json.encodeToString(
            SetAnalysis.serializer(),
            SetAnalysis(
                reps = emptyList(),
                sampleRateHz = 99.4,
                velocityLossPct = null,
                tempoCompliance = null,
                verdicts = emptyList(),
            ),
        )

    private val raisedSquat =
        SetRecordEntity(
            id = 1L,
            sessionId = 1L,
            orderIdx = 0,
            exerciseId = "back_squat",
            exerciseName = "Back Squat",
            loadKg = 97.5,
            plannedLoadKg = 100.0,
            workingLoadKg = 102.5,
            actualReps = 8,
            plannedReps = 6,
            workingReps = 8,
            tempo = "2010",
            plannedTempo = "3010",
            plannedRestS = 120,
            restStartedAtMs = 61_500L,
            startedAtMs = 1_000L,
            endedAtMs = 61_000L,
            analysisJson = noReps,
        )

    private val loweredPlank =
        SetRecordEntity(
            id = 2L,
            sessionId = 1L,
            orderIdx = 1,
            exerciseId = "plank",
            exerciseName = "Plank",
            loadKg = 0.0,
            workingLoadKg = 0.0,
            actualReps = 0,
            actualDurationS = 30,
            plannedDurationS = 45,
            workingDurationS = 30,
            workBegan = true,
            plannedRestS = 90,
            restStartedAtMs = 249_750L,
            startedAtMs = 212_000L,
            endedAtMs = 250_000L,
            analysisJson = noReps,
        )

    private val beforeV20 =
        SetRecordEntity(
            id = 3L,
            sessionId = 1L,
            orderIdx = 2,
            exerciseId = "barbell_row",
            exerciseName = "Barbell Row",
            loadKg = 60.0,
            plannedLoadKg = 60.0,
            actualReps = 10,
            plannedReps = 10,
            tempo = "2011",
            plannedRestS = 90,
            startedAtMs = 400_000L,
            endedAtMs = 440_000L,
            analysisJson = noReps,
        )

    private val lastDeadlift =
        SetRecordEntity(
            id = 4L,
            sessionId = 1L,
            orderIdx = 3,
            exerciseId = "deadlift",
            exerciseName = "Deadlift",
            loadKg = 140.0,
            plannedLoadKg = 140.0,
            workingLoadKg = 140.0,
            actualReps = 5,
            plannedReps = 5,
            workingReps = 5,
            plannedRestS = 180,
            restStartedAtMs = 700_000L,
            startedAtMs = 650_000L,
            endedAtMs = 699_000L,
            analysisJson = noReps,
        )

    private suspend fun setsByExercise(rows: List<SetRecordEntity>): Map<String, JsonObject> {
        val session = SessionEntity(id = 1L, startedAtMs = 1_000L, endedAtMs = 800_000L)
        val repository = SessionRepository(FakeSessionDao(session, rows), FakeExerciseDao())
        val exporter = SessionExporter(repository, dispatcher = Dispatchers.Default)
        val text = exporter.exportJson(1L, includeRepDetail = false)!!
        return Json.parseToJsonElement(text).jsonObject.getValue("exercises").jsonArray.associate {
            val exercise = it.jsonObject
            val set = exercise.getValue("sets").jsonArray.single().jsonObject
            exercise.getValue("exercise").jsonPrimitive.content to set
        }
    }

    private suspend fun session() = setsByExercise(listOf(raisedSquat, loweredPlank, beforeV20, lastDeadlift))

    private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.content

    // ---- the differentials -------------------------------------------------

    /**
     * RED. A raised target that was met reads as met against 8, not as an
     * over-performance against the plan's 6, and the load it ran against is
     * published beside the plan's and the corrected one.
     */
    @Test
    fun `a set's working reps, working load and plan tempo are published beside the plan's`() = runTest {
        val squat = session().getValue("back_squat")
        assertEquals("8", squat.text("workingReps"), "the working rep target is not published: $squat")
        assertEquals("102.5", squat.text("workingLoad_kg"), "the working load is not published: $squat")
        assertEquals("3010", squat.text("plannedTempo"), "the plan's tempo is not published: $squat")
        assertEquals("6", squat.text("plannedReps"), "the plan's count moved")
        assertEquals("2010", squat.text("tempoPrescribed"), "the working tempo moved")
        assertEquals("97.5", squat.text("load_kg"), "the corrected load moved")
        assertTrue("workingDuration_s" !in squat, "a set that is not a hold published a working hold: $squat")
    }

    /**
     * RED. A hold lowered from 45 to 30 and held for 30 publishes the 30 it
     * ran against, so it can read as met; its working load is published too,
     * which is what marks it as a set this build could describe.
     */
    @Test
    fun `a lowered hold publishes the target it ran against`() = runTest {
        val plank = session().getValue("plank")
        assertEquals("30", plank.text("workingDuration_s"), "the working hold is not published: $plank")
        assertEquals("0.0", plank.text("workingLoad_kg"), "the working load of a hold is not published: $plank")
        assertEquals("45", plank.text("plannedDuration_s"), "the plan's hold moved")
        assertTrue("workingReps" !in plank, "a hold published a working rep target: $plank")
    }

    /**
     * RED. The rest after a set is measured from its stored rest instant to
     * the next set's START, in `orderIdx` order across exercises, to a tenth:
     * 212_000 - 61_500 ms is 150.5 s after the squat, and 400_000 - 249_750
     * ms is 150.25 s after the plank, which rounds half up to 150.3.
     */
    @Test
    fun `the measured rest runs from the stored rest instant to the next set's start`() = runTest {
        val sets = session()
        assertEquals("150.5", sets.getValue("back_squat").text("restMeasured_s"), "the squat's rest is not published")
        assertEquals("150.3", sets.getValue("plank").text("restMeasured_s"), "the plank's rest is not published")
    }

    /**
     * RED. The row a v20 build wrote carries the five keys however ordinary
     * the set: every working key is published even where it equals the plan,
     * so an absent key never means "as planned".
     */
    @Test
    fun `a set run exactly to plan still publishes its working figures`() = runTest {
        val deadlift = session().getValue("deadlift")
        assertEquals("5", deadlift.text("workingReps"), "a working count equal to the plan was withheld: $deadlift")
        assertEquals("140.0", deadlift.text("workingLoad_kg"), "a working load equal to the plan was withheld")
    }

    /** A row written before v20 publishes none of the five: it stored none, and nothing may be backfilled. */
    @Test
    fun `a set recorded before v20 publishes none of the five keys`() = runTest {
        val row = session().getValue("barbell_row")
        val minted = listOf("workingReps", "workingLoad_kg", "workingDuration_s", "plannedTempo", "restMeasured_s")
        assertEquals(emptyList(), minted.filter { it in row }, "a pre-v20 row published a 1.23 key: $row")
        assertEquals("10", row.text("plannedReps"), "the pre-v20 row lost its plan count")
    }

    /** The last set of a session had no next START, so it has no measured rest. */
    @Test
    fun `the last set of a session publishes no measured rest`() = runTest {
        val deadlift = session().getValue("deadlift")
        assertTrue("restMeasured_s" !in deadlift, "the last set published a rest nothing ended: $deadlift")
        assertEquals("180", deadlift.text("rest_s"), "the last set lost its planned rest")
    }

    /** A next START before the rest instant describes no rest: nothing is published, never a negative figure. */
    @Test
    fun `a next start earlier than the rest instant publishes no measured rest`() = runTest {
        val sets = setsByExercise(listOf(raisedSquat.copy(restStartedAtMs = 300_000L), loweredPlank))
        val squat = sets.getValue("back_squat")
        assertTrue("restMeasured_s" !in squat, "an inverted pair of instants published a rest: $squat")
    }
}
