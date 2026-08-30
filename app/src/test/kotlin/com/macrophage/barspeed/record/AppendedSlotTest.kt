package com.macrophage.barspeed.record

import com.macrophage.barspeed.model.ExerciseDef
import com.macrophage.barspeed.model.SetGeometryPolicy
import com.macrophage.barspeed.model.Stage
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
 * A field added to [PlannedSlot] with no entry here reds
 * `:app:testDebugUnitTest`, so the next field cannot be inherited by accident
 * the way `warmup` was.
 *
 * THE KEY SET IS HALF THE PIN AND THE VALUES ARE THE OTHER HALF. Asserting
 * only that these keys equal [PlannedSlot]'s fields catches a field with NO
 * stated decision and passes a field whose stated decision is WRONG -- which
 * is the same shape of hole `warmup` fell through, one level up. The three
 * sweeps below assert each recorded decision against what `appendedState`
 * actually does with that field, over two fixtures, so an entry moved to the
 * wrong group reds on the entry itself and not on some behaviour test that
 * happens to cover it. Round 2 of #188 raised this.
 *
 * SHOWN RED, NOT ASSERTED TO WORK. At `ab5a9c87265f4e2a11b1db0e69b6a864e3f294d7`
 * three entries of this table were deliberately misfiled, one per group --
 * `side` INHERITED -> RESET, `loadKg` CARRIED -> INHERITED, `warmup` RESET ->
 * CARRIED -- and `:app:testDebugUnitTest` reported 17 tests completed, 3
 * failed: exactly the three sweeps, no more and no fewer, with the key-set
 * assertion passing throughout. That commit's CI run is the durable artifact.
 * This commit restores the three entries and changes nothing else.
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

/** On READY for the slot at [queueIndex]: `upcomingIndex` is that same slot. */
private fun ready(queue: List<PlannedSlot>, queueIndex: Int) =
    RecordState(stage = Stage.READY, queue = queue, queueIndex = queueIndex, adHoc = false)

private fun appendedSlot(state: RecordState, at: Int): PlannedSlot {
    val next = assertNotNull(appendedState(state), "appendedState returned null")
    return next.queue[at]
}

private val plank = ExerciseDef("plank", "Plank")

/**
 * A slot measured on the clock rather than in reps.
 *
 * The sweeps need it for two fields and nothing else does. `durationS` and
 * `plannedDurationS` are null on every rep-based fixture in this file, on the
 * anchor AND on the appended slot, and no comparison can tell "cleared" from
 * "copied unchanged" across two nulls. [PlannedSlot.isTimed] is
 * `durationS != null`, so this is also the only fixture in which
 * `carriedValues` takes its duration branch at all.
 */
private fun timedSlot(setIndexInExercise: Int, setsInExercise: Int) = PlannedSlot(
    exercise = plank,
    geometry = SetGeometryPolicy.describe(plank, null),
    setIndexInExercise = setIndexInExercise,
    setsInExercise = setsInExercise,
    reps = null,
    durationS = 45,
    plannedReps = null,
    plannedDurationS = 45,
    loadKg = 0.0,
    plannedLoadKg = 0.0,
    tempo = null,
    plannedTempo = null,
    side = "right",
    implementCount = 1,
    exerciseNotes = "Ribs down",
    exerciseNotesBehindTap = null,
    targetMeanConVelMps = null,
    velocityLossStopPct = null,
    restS = 90,
    prepS = 8,
    sensors = 1,
    isExerciseChange = setIndexInExercise == 0,
    warmup = false,
    isAddedSet = false,
)

/**
 * The lat pulldown block `appendedState`'s own KDoc names: a 60 lb opener the
 * plan marks warm-up, and a working weight found only after it.
 */
private fun rampedPulldown() = listOf(
    slot(pulldown, 0, 3, warmup = true, loadKg = 27.2),
    slot(pulldown, 1, 3, loadKg = 34.0),
    slot(pulldown, 2, 3, loadKg = 34.0),
)

/**
 * One anchor, the slot [appendedState] builds after it, and the statements the
 * lifter had standing at that moment.
 *
 * [standing] is what makes CARRIED distinguishable from INHERITED at all: a
 * carried field with nothing stated returns the anchor's own value, which is
 * byte-identical to inheriting it. Every fixture here therefore states
 * something for each of the four carried fields it can -- a value DIFFERENT
 * from the anchor's, or the comparison proves nothing.
 */
private class Sweep(
    val what: String,
    val anchor: PlannedSlot,
    val appended: PlannedSlot,
    val standing: Map<String, Any?>,
)

/** Reflected because the sweeps walk field NAMES; [APPEND_DECISIONS] is keyed by them. */
private fun fieldOf(slot: PlannedSlot, name: String): Any? =
    PlannedSlot::class.java.getDeclaredField(name).also { it.isAccessible = true }.get(slot)

/**
 * The warm-up pulldown block, appended from mid-rest after its opener, with a
 * corrected load, a corrected count and a turned tempo all standing.
 *
 * The anchor is deliberately the slot with the most distinctive values in the
 * file: `warmup` true, `isExerciseChange` true, `setIndexInExercise` 0 and all
 * four frozen declarations non-null, so every RESET field it covers has an
 * anchor value that a copy would be visible in.
 */
private fun repSweep(): Sweep {
    val queue = rampedPulldown()
    val state = RecordState(
        stage = Stage.RESTING,
        queue = queue,
        queueIndex = 0,
        adHoc = false,
        statedLoadKg = 34.0,
        statedReps = 5,
        statedTempo = "2011",
    )
    return Sweep(
        what = "a rep-based warm-up anchor, appended from rest",
        anchor = queue[0],
        appended = appendedSlot(state, at = 3),
        standing = mapOf("loadKg" to 34.0, "reps" to 5, "tempo" to "2011"),
    )
}

/** A timed block, for `durationS` and `plannedDurationS`. */
private fun timedSweep(): Sweep {
    val queue = listOf(timedSlot(0, 2), timedSlot(1, 2))
    val state = RecordState(
        stage = Stage.RESTING,
        queue = queue,
        queueIndex = 0,
        adHoc = false,
        statedDurationS = 60,
    )
    return Sweep(
        what = "a timed anchor, appended from rest",
        anchor = queue[0],
        appended = appendedSlot(state, at = 2),
        standing = mapOf("durationS" to 60),
    )
}

private fun sweeps() = listOf(repSweep(), timedSweep())

private fun fieldsDecided(decision: Append) = APPEND_DECISIONS.filterValues { it == decision }.keys

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

    @Test
    fun `a set appended after a plan-declared warm-up is not a warm-up`() {
        val added = appendedSlot(resting(rampedPulldown(), 0, statedLoadKg = 34.0), at = 3)
        assertEquals(false, added.warmup)
    }

    @Test
    fun `a set appended from READY on a warm-up slot is not a warm-up`() {
        val added = appendedSlot(ready(rampedPulldown(), 0), at = 3)
        assertEquals(false, added.warmup)
    }

    @Test
    fun `appending twice after a warm-up marks neither set a warm-up`() {
        val once = assertNotNull(appendedState(resting(rampedPulldown(), 0)))
        val twice = assertNotNull(appendedState(once))
        assertEquals(false, twice.queue[3].warmup)
        assertEquals(false, twice.queue[4].warmup)
    }

    @Test
    fun `every field recorded INHERITED is copied from the anchor unchanged`() {
        for (sweep in sweeps()) {
            for (name in fieldsDecided(Append.INHERITED)) {
                assertEquals(
                    fieldOf(sweep.anchor, name),
                    fieldOf(sweep.appended, name),
                    "APPEND_DECISIONS records `$name` as INHERITED, but appendedState does not " +
                        "copy it from the anchor over ${sweep.what}. Either the code changed or " +
                        "the recorded decision is wrong -- fix whichever is, do not relax this.",
                )
            }
        }
    }

    @Test
    fun `every field recorded RESET differs from the anchor's value`() {
        // "Differs in at least one fixture", not in both: a field the anchor
        // has no value for cannot be seen being cleared, which is why the
        // timed fixture exists at all. The anchor's value is required non-null
        // in the fixture that counts, so a null-to-null pair can never be
        // mistaken for evidence.
        for (name in fieldsDecided(Append.RESET)) {
            val shown = sweeps().filter { sweep ->
                val was = fieldOf(sweep.anchor, name)
                was != null && was != fieldOf(sweep.appended, name)
            }
            assertTrue(
                shown.isNotEmpty(),
                "APPEND_DECISIONS records `$name` as RESET, but no fixture shows appendedState " +
                    "changing it from a non-null anchor value. Either it is not reset at all, or " +
                    "no fixture here gives the anchor a value distinctive enough to prove it is.",
            )
        }
    }

    @Test
    fun `every field recorded CARRIED takes the value standing for the exercise`() {
        // The strict one. A carried field with nothing stated returns the
        // anchor's own value, so a fixture that states nothing would let
        // CARRIED and INHERITED read alike; a field with no standing value in
        // any fixture therefore FAILS rather than being skipped, which is what
        // forces the next carried field to arrive with a fixture.
        for (name in fieldsDecided(Append.CARRIED)) {
            val shown = sweeps().filter { sweep ->
                val stated = sweep.standing[name]
                stated != null &&
                    stated != fieldOf(sweep.anchor, name) &&
                    stated == fieldOf(sweep.appended, name)
            }
            assertTrue(
                shown.isNotEmpty(),
                "APPEND_DECISIONS records `$name` as CARRIED, but no fixture shows the appended " +
                    "slot taking a standing statement that differs from the anchor's own value. " +
                    "A carried field needs a fixture that states one; a field no statement " +
                    "reaches is not CARRIED.",
            )
        }
    }
}
