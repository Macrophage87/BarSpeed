package com.macrophage.barspeed.record

import com.macrophage.barspeed.model.ExerciseDef
import com.macrophage.barspeed.model.Implement
import com.macrophage.barspeed.model.ProgressionKind
import com.macrophage.barspeed.model.SetGeometryPolicy
import com.macrophage.barspeed.model.Stage
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
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
 * is the same shape of hole `warmup` fell through, one level up. The four
 * sweeps below assert each recorded decision against what `appendedState`
 * actually does with that field, over two fixtures. Round 2 of #188 raised
 * this and round 3 measured how far it reaches.
 *
 * WHICH MISFILINGS RED, ENUMERATED BECAUSE THIS WAS CLAIMED WRONG ONCE. Three
 * groups give six ordered directions an entry can be moved in, and an earlier
 * version of this header said flatly that a wrongly-grouped entry reds -- with
 * three of the six actually run. The fourth sweep was added because one of the
 * other three did not: CARRIED mislabelled RESET passed, since the RESET sweep
 * asked only that the value DIFFER from the anchor and a carried value differs
 * from the anchor in every fixture here by construction. All six are now run
 * against THIS commit's tree, one build each, one mutation each,
 * `./gradlew :app:testDebugUnitTest --rerun-tasks`:
 *
 *  - `side` INHERITED -> RESET reds `every field recorded RESET differs...`
 *  - `side` INHERITED -> CARRIED reds `every field recorded CARRIED takes...`
 *  - `loadKg` CARRIED -> INHERITED reds `every field recorded INHERITED...`
 *  - `loadKg` CARRIED -> RESET reds `no field recorded RESET takes...`
 *  - `warmup` RESET -> CARRIED reds `every field recorded CARRIED takes...`
 *  - `warmup` RESET -> INHERITED reds `every field recorded INHERITED...`
 *
 * Each is 13 tests in this class, 1 failure, exactly the sweep named and no
 * other test, read from the class's JUnit XML rather than the console. The
 * fourth sweep was checked against all four CARRIED fields, not just the one:
 * `reps`, `tempo` and `durationS` mislabelled RESET each red it too. What is
 * claimed is those ten runs and nothing wider; a field whose value no fixture
 * makes distinctive is still outside every sweep here.
 *
 * SHOWN RED, NOT ASSERTED TO WORK, TWICE. At
 * `ab5a9c87265f4e2a11b1db0e69b6a864e3f294d7` three entries were deliberately
 * misfiled, one per group, and `:app:testDebugUnitTest` reported 17 tests, 3
 * failed. The fourth sweep has its own red at
 * `7ae82a766c3af6e81fca95ed3ed22dd2e67c6726`, where `loadKg` is misfiled
 * CARRIED -> RESET alone: CI run 33310983019, 18 tests completed, 1 failed.
 * Both CI runs are the durable artifacts; this commit restores the entry and
 * changes nothing else in the table.
 */
private val APPEND_DECISIONS: Map<String, Append> = mapOf(
    // Identity and how the movement is performed: the appended set is one
    // more set of the anchor's exercise, so all of this follows it.
    "exercise" to Append.INHERITED,
    "geometry" to Append.INHERITED,
    "side" to Append.INHERITED,
    "implementCount" to Append.INHERITED,
    // What the load sits on, and the bar it sits on where the plan named one
    // (#253). One more set of the anchor's exercise is done with the anchor's
    // implement -- there is no other answer -- so both follow it.
    "implement" to Append.INHERITED,
    "barKg" to Append.INHERITED,
    "exerciseNotes" to Append.INHERITED,
    "exerciseNotesBehindTap" to Append.INHERITED,
    "targetMeanConVelMps" to Append.INHERITED,
    "velocityLossStopPct" to Append.INHERITED,
    "restS" to Append.INHERITED,
    "prepS" to Append.INHERITED,
    "sensors" to Append.INHERITED,
    // Which dimension the post-set grid raises (#214). One more set of an
    // exercise progresses the way that exercise progresses, so it follows
    // the anchor like everything else about how the movement is performed.
    "progression" to Append.INHERITED,
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
    // #215's frozen side, in the same group and for the same reason: an
    // appended set has no prescription, so it has no prescribed side. `side`
    // one group up stays INHERITED -- the appended set is one more set of the
    // anchor's exercise, on the arm that exercise was being worked with.
    "plannedSide" to Append.RESET,
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
    // Non-null in this fixture on purpose: the RESET sweep can only see a
    // field being cleared where the anchor had something to clear.
    plannedSide = side,
    implementCount = 2,
    // Neither is the field's default: an inheritance pin comparing two copies
    // of a default value cannot fail, the reason `progression` below is not
    // WEIGHT here either.
    implement = Implement.DUMBBELL,
    barKg = 25.0,
    exerciseNotes = "Brace before the first rep",
    exerciseNotesBehindTap = "Elbows under the bar",
    targetMeanConVelMps = 0.45,
    velocityLossStopPct = 20.0,
    restS = 150,
    prepS = 12,
    sensors = 2,
    // Deliberately NOT the ProgressionKind default: an inheritance pin
    // comparing two copies of a default value cannot fail.
    progression = ProgressionKind.REPS,
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
    implement = Implement.BARBELL,
    barKg = 15.0,
    exerciseNotes = "Ribs down",
    exerciseNotesBehindTap = null,
    targetMeanConVelMps = null,
    velocityLossStopPct = null,
    restS = 90,
    prepS = 8,
    sensors = 1,
    progression = ProgressionKind.TIME,
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
 * A one-set lat pulldown block followed by a two-set press block (#206).
 *
 * The shape in which an appended set BECOMES the coming one: the pulldown
 * block has nothing left to run, so `AddSetControl.placement` puts the
 * appended set immediately next and the rest screen's boxes are re-seeded
 * from it. Taking it back has to move them again, and no other fixture in
 * this file reaches that branch.
 */
private fun pulldownThenPress() = listOf(
    slot(pulldown, 0, 1, loadKg = 27.2),
    slot(press, 0, 2, loadKg = 20.0),
    slot(press, 1, 2, loadKg = 20.0),
)

/**
 * The three-set pulldown block with a press block behind it (#206).
 *
 * The shape in which an appended set is NOT the coming one AND has a slot
 * after it. `rampedPulldown` has neither -- its appended set lands at the very
 * end of the queue, so "the boxes were left alone" and "there was nothing to
 * re-seed from" are the same observation there, and a pin built on it cannot
 * tell them apart. Found by mutation M8; see this file's removal section.
 */
private fun pulldownThenPressBlock() = listOf(
    slot(pulldown, 0, 3, loadKg = 27.2),
    slot(pulldown, 1, 3, loadKg = 34.0),
    slot(pulldown, 2, 3, loadKg = 34.0),
    slot(press, 0, 2, loadKg = 20.0),
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

/**
 * What an appended set is made of, and -- since #206 -- what taking one back
 * out leaves behind.
 *
 * The removal cases live here rather than in a file of their own because they
 * need these fixtures and this queue: a slot removed has to be checked against
 * the slot that was appended, field for field, and duplicating `slot`,
 * `resting` and `ready` beside a second class is how two fixtures start
 * disagreeing about what a plan looks like. `APPEND_DECISIONS` is untouched by
 * #206 -- removal adds no field to [PlannedSlot] and changes no field's
 * source, so the table still names every one of them.
 */
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

    /**
     * Characterization (#206). Two appends put two slots at the END of the
     * block, in the order they were added, each carrying the next index.
     *
     * Pinned because removal is about to have to name ONE of these two, and
     * "the last appended set of the exercise" is only a well-defined phrase
     * while this holds. Green before #206 and after it; nothing here is a
     * differential.
     */
    @Test
    fun `two appends leave two added slots at the end of the block in order`() {
        val once = assertNotNull(appendedState(resting(rampedPulldown(), 0)))
        val twice = assertNotNull(appendedState(once))
        assertEquals(5, twice.queue.size)
        assertEquals(listOf(false, false, false, true, true), twice.queue.map { it.isAddedSet })
        assertEquals(listOf(0, 1, 2, 3, 4), twice.queue.map { it.setIndexInExercise })
    }

    // ---- #206: taking an appended set back out --------------------------

    /**
     * RED before c3. The plain undo: append one set, remove it, and the queue
     * is the plan's again.
     */
    @Test
    fun `an appended set can be taken back out again`() {
        val once = assertNotNull(appendedState(resting(rampedPulldown(), 0)))
        val back = assertNotNull(removedState(once), "removedState returned null")
        assertEquals(3, back.queue.size)
        assertEquals(listOf(false, false, false), back.queue.map { it.isAddedSet })
        assertEquals(listOf(0, 1, 2), back.queue.map { it.setIndexInExercise })
    }

    /**
     * RED before c3. #206 requirement 2: removing one appended set disturbs no
     * other. The survivor keeps the place AND the carried load it was built
     * with, which is what "the others are undisturbed" has to mean for a set
     * that is going to be recorded.
     */
    @Test
    fun `removing one appended set leaves the other standing unchanged`() {
        val once = assertNotNull(appendedState(resting(rampedPulldown(), 0, statedLoadKg = 40.0)))
        val twice = assertNotNull(appendedState(once))
        val survivor = twice.queue[3]
        val back = assertNotNull(removedState(twice))
        assertEquals(4, back.queue.size)
        assertEquals(listOf(false, false, false, true), back.queue.map { it.isAddedSet })
        assertEquals(survivor, back.queue[3])
    }

    /**
     * RED before c3. Repeatable to exhaustion: two appends and two removals
     * leave the plan's own queue, and a third removal has nothing to take.
     */
    @Test
    fun `two appends and two removals leave the plan's queue`() {
        val twice = assertNotNull(appendedState(assertNotNull(appendedState(resting(rampedPulldown(), 0)))))
        val back = assertNotNull(removedState(assertNotNull(removedState(twice))))
        assertEquals(3, back.queue.size)
        assertEquals(listOf(false, false, false), back.queue.map { it.isAddedSet })
        assertNull(removedState(back))
    }

    /**
     * RED before c3. #206 requirement 4, the case it is actually about: the
     * removed set was NOT the coming one, so the load and reps the lifter is
     * standing on for that exercise are left exactly where they are. Reverting
     * them would silently undo a correction made for the EXERCISE rather than
     * for the set.
     */
    @Test
    fun `removing a set that was not the coming one leaves the standing load alone`() {
        val once = assertNotNull(appendedState(resting(rampedPulldown(), 0, statedLoadKg = 40.0)))
        val back = assertNotNull(removedState(once))
        assertEquals(40.0, back.statedLoadKg)
        assertEquals(once.loadInput, back.loadInput)
        assertEquals(once.repsInput, back.repsInput)
    }

    /**
     * NOT a differential -- green when it was written, and added because
     * mutation M8 SURVIVED the pin above it.
     *
     * M8 deletes `removedState`'s `if (!target.wasUpcoming)` guard, so every
     * removal re-seeds. `removing a set that was not the coming one leaves the
     * standing load alone` did not notice, because on `rampedPulldown` the
     * appended set is the LAST slot in the queue: there is nothing after it to
     * re-seed from, the null guard returns the same state either way, and the
     * pin was reading a coincidence of the fixture as evidence of the rule.
     *
     * This one puts a press block behind the pulldowns, so the removal has a
     * slot after it and re-seeding would be visible. The standing 40 kg is a
     * statement about the PULLDOWN and must survive a removal that leaves
     * pulldowns still to run.
     */
    @Test
    fun `removing a set with sets still to come leaves the boxes alone`() {
        val once = assertNotNull(appendedState(resting(pulldownThenPressBlock(), 0, statedLoadKg = 40.0)))
        assertEquals(5, once.queue.size)
        assertTrue(once.queue[3].isAddedSet)
        val back = assertNotNull(removedState(once))
        assertEquals(4, back.queue.size)
        assertEquals(40.0, back.statedLoadKg)
        assertEquals(once.loadInput, back.loadInput)
        assertEquals(once.repsInput, back.repsInput)
    }

    /**
     * RED before c3. The one case where the boxes MUST move: the removed set
     * had displaced the set the next START would run, and every editable box
     * was seeded from it. Leaving them would show the pulldown's load on a
     * press set -- the box disagreeing with what the set would record, which
     * is what #45 and #124 are about.
     *
     * This is not a reversion of requirement 4. Nothing about the pulldown's
     * standing values is rolled back; the boxes follow the set that is now
     * coming up, exactly as `jumpedState` and `appendedState` already make
     * them.
     */
    @Test
    fun `removing the set that had become the coming one re-seeds the boxes`() {
        val once = assertNotNull(appendedState(resting(pulldownThenPress(), 0)))
        assertEquals(4, once.queue.size)
        assertTrue(once.queue[1].isAddedSet)
        val back = assertNotNull(removedState(once))
        assertEquals(3, back.queue.size)
        assertEquals(press.id, back.upcomingSlot?.exercise?.id)
        assertEquals(back.weightUnit.inputValue(20.0), back.loadInput)
        assertNotEquals(back.weightUnit.inputValue(27.2), back.loadInput)
        assertNull(back.statedLoadKg)
        assertNull(back.statedReps)
    }

    /**
     * RED before the fix. THE FIFTH FIELD. `removedState` cleared the four
     * stated values beside it and left [RecordState.statedSide] standing, so
     * a side stated for the set that is then REMOVED survives into the slot
     * that moves up. That slot is outside the block -- the removal takes the
     * last appended set, so nothing of that exercise follows it -- and
     * `carriedValues` bakes the standing side into it, which `set_records`
     * then publishes as `side` beside an untouched `plannedSide` (#215).
     *
     * Stated on the state the append left, which is where the control lives:
     * the lifter appends a set, says "left" for it, then takes it back out.
     */
    @Test
    fun `removing the set that had become the coming one clears a stated side`() {
        val once = assertNotNull(appendedState(resting(pulldownThenPress(), 0)))
        val back = assertNotNull(removedState(once.copy(statedSide = "left")))
        assertEquals(press.id, back.upcomingSlot?.exercise?.id)
        assertNull(back.statedSide)
    }

    /**
     * RED before c3. THE BOUNDARY, in :app. Once the appended set has RUN it
     * is a recorded set, and no state this function produces removes one.
     *
     * Mid-rest after the appended set itself: `queueIndex` is that slot and
     * `upcomingIndex` is one past it.
     */
    @Test
    fun `an appended set that has already run is not removable`() {
        val once = assertNotNull(appendedState(resting(rampedPulldown(), 0)))
        assertNull(removedState(resting(once.queue, 3)))
    }

    /** RED before c3. Nothing appended, nothing removable. */
    @Test
    fun `a plan queue with no appended set has nothing to remove`() {
        assertNull(removedState(resting(rampedPulldown(), 0)))
        assertNull(removedState(ready(rampedPulldown(), 0)))
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

    @Test
    fun `no field recorded RESET takes the value standing for the exercise`() {
        // The fourth sweep, and it exists for one direction the other three
        // leave open: CARRIED mislabelled RESET. The sweep above asserts only
        // that a RESET field DIFFERS from the anchor in some fixture, and a
        // genuinely carried field satisfies that too, because every fixture
        // here deliberately states a carried value different from the
        // anchor's. Measured, not argued: at
        // `becaca430b6cb1de65f9bebf98378959fba5b345`, before this sweep
        // existed, relabelling `loadKg` CARRIED -> RESET left
        // `:app:testDebugUnitTest` green at 12 tests, 0 failures. With this
        // sweep it reds -- CI run 33310983019 on
        // `7ae82a766c3af6e81fca95ed3ed22dd2e67c6726`, 18 tests completed, 1
        // failed, this one.
        //
        // Vacuous for every field genuinely RESET today -- none of the nine
        // has a `standing` entry, because `standing` is authored per CARRIED
        // field. That is the point: it fires only on the mislabel it was
        // built for, and a fixture stating a value for a RESET field would be
        // a deliberate act by whoever wrote it.
        for (name in fieldsDecided(Append.RESET)) {
            for (sweep in sweeps()) {
                val stated = sweep.standing[name] ?: continue
                if (stated == fieldOf(sweep.anchor, name)) continue
                assertNotEquals(
                    stated,
                    fieldOf(sweep.appended, name),
                    "APPEND_DECISIONS records `$name` as RESET, but over ${sweep.what} the " +
                        "appended slot took the value STANDING for the exercise rather than a " +
                        "cleared or recomputed one. That is what CARRIED means. The recorded " +
                        "decision is in the wrong group.",
                )
            }
        }
    }
}
