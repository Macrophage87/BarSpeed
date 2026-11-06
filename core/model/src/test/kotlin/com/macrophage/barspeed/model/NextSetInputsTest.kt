package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [NextSetInputs.of], asked with the call shape each of its callers uses
 * (code-health review 2026-11-06, F2):
 *
 *  - REST: last = the set just finished, next = the slot after it, the
 *    statements as they stand, and [LastRanSet] for the boxes.
 *  - SKIP: last = the skipped slot, next = the slot that moves up, the
 *    statements as they stand, no [LastRanSet] -- the skipped slot never ran.
 *  - SWITCH / APPEND / REMOVE: the statements cleared ([NextSetStatements.NONE]),
 *    no [LastRanSet].
 *  - CAPTION: last = the set being set up, next = the one after it, the
 *    statements as they stand, no [LastRanSet].
 *
 * The rows are pinned to what the five `:app` reducers computed by hand before
 * they were routed through this function, so a change here is a change to all
 * of them at once.
 */
class NextSetInputsTest {
    private fun squat(index: Int, kg: Double?, reps: Int? = 5, tempo: String? = "3010", warmup: Boolean = false) =
        NextSetSlot(
            exerciseId = "back_squat",
            setIndexInExercise = index,
            bodyweight = false,
            warmup = warmup,
            plannedLoadKg = kg,
            plannedReps = reps,
            plannedDurationS = null,
            plannedTempo = tempo,
            loadKg = kg,
            reps = reps,
            durationS = null,
            tempo = tempo,
        )

    private fun plank(index: Int, seconds: Int) = NextSetSlot(
        exerciseId = "plank",
        setIndexInExercise = index,
        bodyweight = true,
        warmup = false,
        plannedLoadKg = null,
        plannedReps = null,
        plannedDurationS = seconds,
        plannedTempo = null,
        loadKg = null,
        reps = null,
        durationS = seconds,
        tempo = null,
    )

    private val deadlift = NextSetSlot(
        exerciseId = "deadlift",
        setIndexInExercise = 0,
        bodyweight = false,
        warmup = false,
        plannedLoadKg = 140.0,
        plannedReps = 8,
        plannedDurationS = null,
        plannedTempo = null,
        loadKg = 140.0,
        reps = 8,
        durationS = null,
        tempo = null,
    )

    private val everything = NextSetStatements(loadKg = 115.0, reps = 6, durationS = 30, tempo = "4010")
    private val ran = LastRanSet(addedKg = 112.5, reps = 6, durationS = null, tempo = "4010")

    // ---- REST ---------------------------------------------------------------

    @Test
    fun `rest inside a flat block keeps every statement and the boxes show them`() {
        val out = NextSetInputs.of(squat(0, 110.0), squat(1, 110.0), everything, ran)

        assertEquals(115.0, out.statedKg)
        assertEquals(6, out.statedReps)
        assertEquals("4010", out.statedTempo)
        assertEquals(115.0, out.loadKg)
        assertEquals(6, out.reps)
    }

    @Test
    fun `rest on a stepping block carries the load as a distance and drops a changed rep count`() {
        val out = NextSetInputs.of(squat(0, 110.0, reps = 5), squat(1, 120.0, reps = 3), everything, ran)

        assertEquals(125.0, out.statedKg)
        assertEquals(125.0, out.loadKg)
        assertNull(out.statedReps)
        assertEquals(3, out.reps, "the plan's own next count, not the statement")
    }

    @Test
    fun `rest across a block boundary drops every statement and seeds from the next exercise`() {
        val out = NextSetInputs.of(squat(2, 120.0), deadlift, everything, ran)

        assertNull(out.statedKg)
        assertNull(out.statedReps)
        assertNull(out.statedDurationS)
        assertNull(out.statedTempo)
        assertEquals(140.0, out.loadKg)
        assertEquals(8, out.reps)
        assertNull(out.durationS)
        assertEquals("", out.adHocTempo, "the deadlift declares no tempo and inherits none")
    }

    @Test
    fun `rest from a warm-up onto a working set with a different load drops the correction`() {
        val out = NextSetInputs.of(squat(0, 60.0, warmup = true), squat(1, 110.0), everything, ran)

        assertNull(out.statedKg)
        assertEquals(110.0, out.loadKg)
    }

    @Test
    fun `rest at the end of the plan seeds every box from the set that ran`() {
        val out = NextSetInputs.of(squat(2, 120.0), null, everything, ran)

        assertNull(out.statedKg)
        assertEquals(112.5, out.loadKg, "the ADDED load that ran")
        assertEquals(6, out.reps)
        assertNull(out.durationS)
        assertEquals("4010", out.adHocTempo)
    }

    @Test
    fun `rest after a set with no count falls back to five reps`() {
        val out = NextSetInputs.of(null, null, NextSetStatements.NONE, ran.copy(reps = null, durationS = 40))

        assertEquals(NextSetInputs.FALLBACK_REPS, out.reps)
        assertEquals(40, out.durationS)
    }

    @Test
    fun `rest onto a timed slot puts the last count in the hidden reps box`() {
        val out = NextSetInputs.of(squat(2, 120.0), plank(0, 45), NextSetStatements.NONE, ran)

        assertEquals(6, out.reps)
        assertEquals(45, out.durationS)
        assertEquals(0.0, out.loadKg, "a planned slot declaring no load is offered none, not the last one")
    }

    @Test
    fun `rest inside a hold block keeps the stated hold`() {
        val out = NextSetInputs.of(plank(0, 45), plank(1, 45), everything, ran)

        assertEquals(30, out.statedDurationS)
        assertEquals(30, out.durationS)
    }

    // ---- SKIP -----------------------------------------------------------------

    @Test
    fun `skip re-decides the statements against the skipped slot`() {
        val out = NextSetInputs.of(squat(1, 110.0), squat(2, 120.0), everything)

        assertEquals(125.0, out.statedKg)
        assertEquals(125.0, out.loadKg)
        assertEquals(6, out.statedReps)
    }

    @Test
    fun `skip onto a slot with no count of a kind leaves that box alone`() {
        val out = NextSetInputs.of(squat(2, 120.0), plank(0, 45), everything)

        assertNull(out.reps, "nothing ran, so there is no last count to fall back to")
        assertEquals(45, out.durationS)
    }

    // ---- SWITCH / APPEND / REMOVE -------------------------------------------

    @Test
    fun `cleared statements seed every box from the new slot even inside a block`() {
        val out = NextSetInputs.of(squat(0, 110.0), squat(1, 110.0), NextSetStatements.NONE)

        assertNull(out.statedKg)
        assertNull(out.statedReps)
        assertNull(out.statedDurationS)
        assertNull(out.statedTempo)
        assertEquals(110.0, out.loadKg)
        assertEquals(5, out.reps)
        assertEquals("3010", out.adHocTempo)
    }

    @Test
    fun `an appended slot declaring no load seeds the box from what it carries`() {
        val appended = squat(3, null, reps = null, tempo = null).copy(loadKg = 115.0, reps = 6, tempo = "4010")

        val out = NextSetInputs.of(null, appended, NextSetStatements.NONE)

        assertEquals(115.0, out.loadKg)
        assertEquals(6, out.reps)
        assertEquals("4010", out.adHocTempo)
    }

    // ---- the caption's promise is what the next set gets -------------------

    private data class Case(val name: String, val upcoming: NextSetSlot, val after: NextSetSlot?)

    private val cases = listOf(
        Case("flat block", squat(0, 110.0), squat(1, 110.0)),
        Case("stepping block", squat(0, 110.0, reps = 5), squat(1, 120.0, reps = 3)),
        Case("tempo changes", squat(0, 110.0), squat(1, 110.0, tempo = "4010")),
        Case("warm-up onto working", squat(0, 60.0, warmup = true), squat(1, 110.0)),
        Case("warm-up onto equal working", squat(0, 110.0, warmup = true), squat(1, 110.0)),
        Case("next declares no load", squat(0, 110.0), squat(1, null)),
        Case("last of block", squat(2, 120.0), deadlift),
        Case("last of plan", squat(2, 120.0), null),
        Case("hold block", plank(0, 45), plank(1, 45)),
        Case("ramping hold", plank(0, 30), plank(1, 45)),
    )

    private val statements = listOf(
        everything,
        NextSetStatements.NONE,
        NextSetStatements(loadKg = 100.0, reps = null, durationS = null, tempo = null),
        NextSetStatements(loadKg = null, reps = 4, durationS = 60, tempo = "2011"),
    )

    /**
     * The change dialog's caption asks [NextSetInputs.of] about the set being
     * set up and the one after it, with no set that ran. The rest transition
     * that follows that set asks about the same pair WITH the set that ran. The
     * caption's "stands for later sets" must be the rest transition's answer,
     * and where it says the load stands the box must open on the standing load.
     */
    @Test
    fun `the caption's promise is what the rest transition gives the next set`() {
        for (case in cases) {
            for (stated in statements) {
                val promise = NextSetInputs.of(case.upcoming, case.after, stated)
                val actual = NextSetInputs.of(case.upcoming, case.after, stated, ran)
                val at = "${case.name} / $stated"

                assertEquals(promise.statedKg != null, actual.statedKg != null, "load reach, $at")
                assertEquals(promise.statedReps != null, actual.statedReps != null, "reps reach, $at")
                assertEquals(promise.statedDurationS != null, actual.statedDurationS != null, "hold reach, $at")
                assertEquals(promise.statedKg, actual.statedKg, "load, $at")
                assertEquals(promise.statedReps, actual.statedReps, "reps, $at")
                assertEquals(promise.statedDurationS, actual.statedDurationS, "hold, $at")
                assertEquals(promise.statedTempo, actual.statedTempo, "tempo, $at")
                if (actual.statedKg != null) assertEquals(actual.statedKg, actual.loadKg, "load box, $at")
                if (actual.statedReps != null) assertEquals(actual.statedReps, actual.reps, "reps box, $at")
                if (actual.statedDurationS != null) {
                    assertEquals(actual.statedDurationS, actual.durationS, "hold box, $at")
                }
            }
        }
    }
}
