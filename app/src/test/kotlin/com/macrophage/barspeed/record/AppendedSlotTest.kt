package com.macrophage.barspeed.record

import com.macrophage.barspeed.model.ExerciseDef
import com.macrophage.barspeed.model.SetGeometryPolicy
import com.macrophage.barspeed.model.Stage
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Where each field of an appended [PlannedSlot] comes from.
 *
 * `AddSetControl.placement` in `:core:model` decides WHERE the appended slot
 * goes in the queue and is pinned there. It cannot see the slot itself:
 * [PlannedSlot] carries an `ExerciseDef` and a `ResolvedGeometry` and lives in
 * `:app`. So the second half of the rule -- which of the anchor's twenty-four
 * fields the appended set keeps, and which are cleared -- had nothing running
 * against it at all, and that is how #188 shipped an appended set inheriting
 * the finished set's `warmup`.
 *
 * REACHABLE ONLY BECAUSE `app/build.gradle.kts` NOW PINS THE TEST JVM TO 21.
 * `:app` is `jvmToolchain(17)` and `:core:model` emits class file 65, so
 * before that block every test in this file would have died on
 * `UnsupportedClassVersionError` at the first `ExerciseDef` load, without
 * asserting anything. `PlanQueueTest` avoided it by never touching such a
 * type; nothing that describes a queued SET can.
 */
private enum class Append {
    /** Cleared or recomputed: nothing prescribed this set, or the index moved. */
    RESET,

    /**
     * Taken from what is STANDING for the anchor's exercise -- the lifter's
     * typed correction where there is one, the anchor's own value otherwise.
     * `carriedValues`, the same function the set bake uses.
     */
    CARRIED,

    /**
     * Copied from the anchor unchanged, because it describes the EXERCISE or
     * how it is performed and the appended set is one more set of it.
     */
    INHERITED,
}

/**
 * One stated decision per [PlannedSlot] field, checked against the class.
 *
 * The point is the check below, not the table: a field added to
 * [PlannedSlot] with no entry here reds `:app:testDebugUnitTest`, so the
 * next field cannot be inherited by accident the way `warmup` was. What the
 * table cannot do is verify a WRONG entry -- that is what the assertions
 * further down are for, and they cover one field per group.
 */
private val APPEND_DECISIONS: Map<String, Append> = mapOf(
    // Identity and how the movement is performed: the appended set is one
    // more set of the anchor's exercise, so all of this follows it.
    "exercise" to Append.INHERITED,
    "geometry" to Append.INHERITED,
    "side" to Append.INHERITED,
    "implementCount" to Append.INHERITED,
    "exerciseNotes" to Append.INHERITED,
    "exerciseNotesBehindTap" to Append.INHERITED,
    "targetMeanConVelMps" to Append.INHERITED,
    "velocityLossStopPct" to Append.INHERITED,
    "restS" to Append.INHERITED,
    "prepS" to Append.INHERITED,
    "sensors" to Append.INHERITED,
    // What the lifter is standing on for that exercise.
    "loadKg" to Append.CARRIED,
    "reps" to Append.CARRIED,
    "durationS" to Append.CARRIED,
    "tempo" to Append.CARRIED,
    // Frozen plan declarations: nothing prescribed this set, and the absence
    // is a statement rather than a gap.
    "plannedLoadKg" to Append.RESET,
    "plannedReps" to Append.RESET,
    "plannedDurationS" to Append.RESET,
    "plannedTempo" to Append.RESET,
    // Facts about this slot's place and purpose, not the anchor's.
    "setIndexInExercise" to Append.RESET,
    "setsInExercise" to Append.RESET,
    "isExerciseChange" to Append.RESET,
    "isAddedSet" to Append.RESET,
    "warmup" to Append.RESET,
)

private val press = ExerciseDef("overhead_press", "Overhead Press")
private val pulldown = ExerciseDef("lat_pulldown", "Lat pulldown")

private fun slot(
    exercise: ExerciseDef,
    setIndexInExercise: Int,
    setsInExercise: Int,
    warmup: Boolean = false,
    loadKg: Double = 20.0,
    side: String? = "left",
) = PlannedSlot(
    exercise = exercise,
    geometry = SetGeometryPolicy.describe(exercise, null),
    setIndexInExercise = setIndexInExercise,
    setsInExercise = setsInExercise,
    reps = 8,
    durationS = null,
    plannedReps = 8,
    plannedDurationS = null,
    loadKg = loadKg,
    plannedLoadKg = loadKg,
    tempo = "3010",
    plannedTempo = "3010",
    side = side,
    implementCount = 2,
    exerciseNotes = "Brace before the first rep",
    exerciseNotesBehindTap = "Elbows under the bar",
    targetMeanConVelMps = 0.45,
    velocityLossStopPct = 20.0,
    restS = 150,
    prepS = 12,
    sensors = 2,
    isExerciseChange = setIndexInExercise == 0,
    warmup = warmup,
    isAddedSet = false,
)

/** Mid-rest after the slot at [queueIndex]: `upcomingIndex` is one further on. */
private fun resting(queue: List<PlannedSlot>, queueIndex: Int, statedLoadKg: Double? = null) = RecordState(
    stage = Stage.RESTING,
    queue = queue,
    queueIndex = queueIndex,
    adHoc = false,
    statedLoadKg = statedLoadKg,
)

private fun appendedSlot(state: RecordState, at: Int): PlannedSlot {
    val next = assertNotNull(appendedState(state), "appendedState returned null")
    return next.queue[at]
}

class AppendedSlotTest {
    @Test
    fun `every PlannedSlot field has a stated append decision`() {
        // Instance fields only. The Compose compiler adds a public static
        // `$stable` to every class it sees, and it is NOT flagged synthetic --
        // measured, not guessed: this assertion failed on `$stable` the first
        // time it ran. A property with no backing field, such as `isTimed`,
        // never appears here at all, which is right: it is derived and there
        // is nothing to inherit.
        val declared =
            PlannedSlot::class.java.declaredFields
                .filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }
                .map { it.name }
                .toSet()
        assertEquals(
            declared,
            APPEND_DECISIONS.keys,
            "PlannedSlot's fields and APPEND_DECISIONS have diverged. A new field is " +
                "inherited from the anchor unless appendedState resets it, which after " +
                "#188 means inherited from the set just FINISHED. Decide which, state it " +
                "in APPEND_DECISIONS, and assert it below.",
        )
    }

    @Test
    fun `an appended set is a set of the exercise just finished`() {
        val queue = listOf(slot(press, 0, 2), slot(press, 1, 2), slot(pulldown, 0, 1))
        val added = appendedSlot(resting(queue, 1), at = 2)
        assertEquals(press, added.exercise)
        assertEquals(SetGeometryPolicy.describe(press, null), added.geometry)
    }

    @Test
    fun `an appended set inherits how the anchor's exercise is performed`() {
        val queue = listOf(slot(press, 0, 2), slot(press, 1, 2))
        val added = appendedSlot(resting(queue, 0), at = 2)
        assertEquals("left", added.side)
        assertEquals(2, added.implementCount)
        assertEquals(2, added.sensors)
        assertEquals(150, added.restS)
        assertEquals(12, added.prepS)
        assertEquals(0.45, added.targetMeanConVelMps)
        assertEquals(20.0, added.velocityLossStopPct)
        assertEquals("Brace before the first rep", added.exerciseNotes)
        assertEquals("Elbows under the bar", added.exerciseNotesBehindTap)
    }

    @Test
    fun `an appended set carries the load standing for that exercise`() {
        val queue = listOf(slot(press, 0, 2), slot(press, 1, 2))
        val added = appendedSlot(resting(queue, 0, statedLoadKg = 13.6), at = 2)
        assertEquals(13.6, added.loadKg)
        assertEquals(8, added.reps)
        assertEquals("3010", added.tempo)
    }

    @Test
    fun `an appended set carries no prescription of its own`() {
        val queue = listOf(slot(press, 0, 2), slot(press, 1, 2))
        val added = appendedSlot(resting(queue, 0, statedLoadKg = 13.6), at = 2)
        assertNull(added.plannedLoadKg)
        assertNull(added.plannedReps)
        assertNull(added.plannedDurationS)
        assertNull(added.plannedTempo)
        assertEquals(true, added.isAddedSet)
        assertEquals(false, added.isExerciseChange)
        assertEquals(2, added.setIndexInExercise)
        assertEquals(3, added.setsInExercise)
    }

    @Test
    fun `across a block boundary a load typed for the next exercise is not carried`() {
        val queue = listOf(slot(press, 0, 1), slot(pulldown, 0, 1))
        val added = appendedSlot(resting(queue, 0, statedLoadKg = 30.0), at = 1)
        assertEquals(press, added.exercise)
        assertEquals(20.0, added.loadKg)
    }
}
