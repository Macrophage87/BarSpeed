package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the preview of one upcoming session lists (#202).
 *
 * Two halves. [SessionPreviewPolicy.setLine] is a CHARACTERIZATION of the line
 * `SlotCard` in `:app` has shipped since #160 -- the string moved into
 * `:core:model` unchanged so that the preview and the record flow's "Up next"
 * card cannot phrase the same set two ways -- and every expectation below was
 * read off that composable's source rather than invented here. Nothing on the
 * CI path can render a composable, so before this file the vocabulary had no
 * test at all.
 *
 * [SessionPreviewPolicy.of] is the new decision: how the flat queue becomes
 * the blocks the lifter reads.
 */
class SessionPreviewTest {
    private fun set(
        name: String = "Back squat",
        kind: ExerciseKind = ExerciseKind.DYNAMIC,
        bodyweight: Boolean = false,
        index: Int = 0,
        of: Int = 3,
        reps: Int? = 5,
        durationS: Int? = null,
        loadKg: Double? = 100.0,
        tempo: String? = null,
        side: String? = null,
        warmup: Boolean = false,
    ) = PreviewSet(
        exerciseName = name,
        kind = kind,
        bodyweight = bodyweight,
        setIndexInExercise = index,
        setsInExercise = of,
        reps = reps,
        durationS = durationS,
        loadKg = loadKg,
        tempo = tempo,
        side = side,
        implementCount = null,
        restS = null,
        warmup = warmup,
    )

    @Test
    fun `a rep set states its count then its load`() {
        assertEquals("5 reps · 100 kg", SessionPreviewPolicy.setLine(set(), WeightUnit.KG))
    }

    @Test
    fun `tempo comes last when the plan prescribes one`() {
        assertEquals(
            "5 reps · 100 kg · tempo 3010",
            SessionPreviewPolicy.setLine(set(tempo = "3010"), WeightUnit.KG),
        )
    }

    @Test
    fun `a side leads the line, capitalised`() {
        assertEquals(
            "Left · 5 reps · 100 kg",
            SessionPreviewPolicy.setLine(set(side = "left"), WeightUnit.KG),
        )
    }

    @Test
    fun `a hold is said in seconds and a carry is said as a carry`() {
        assertEquals(
            "60s hold · 100 kg",
            SessionPreviewPolicy.setLine(set(kind = ExerciseKind.HOLD, reps = null, durationS = 60), WeightUnit.KG),
        )
        assertEquals(
            "45s carry · 100 kg",
            SessionPreviewPolicy.setLine(set(kind = ExerciseKind.CARRY, reps = null, durationS = 45), WeightUnit.KG),
        )
    }

    /**
     * The rule `SlotCard` shipped and this inherits: a plank the plan gave no
     * load for says "bodyweight", because a hold has nothing else to say, while
     * a REP set the plan gave no load for says nothing about load -- the lifter
     * is about to state one, and naming it bodyweight would be an invention.
     */
    @Test
    fun `an unloaded hold says bodyweight and an unloaded rep set says nothing`() {
        assertEquals(
            "60s hold · bodyweight",
            SessionPreviewPolicy.setLine(
                set(kind = ExerciseKind.HOLD, reps = null, durationS = 60, loadKg = null),
                WeightUnit.KG,
            ),
        )
        assertEquals("5 reps", SessionPreviewPolicy.setLine(set(loadKg = null), WeightUnit.KG))
    }

    /** Body-weight work says the ADDED load as an addition to the lifter, never as a weight. */
    @Test
    fun `body-weight work is said in the BW notation`() {
        assertEquals(
            "5 reps · BW",
            SessionPreviewPolicy.setLine(set(bodyweight = true, loadKg = null), WeightUnit.KG),
        )
        assertEquals(
            "5 reps · BW + 20 kg",
            SessionPreviewPolicy.setLine(set(bodyweight = true, loadKg = 20.0), WeightUnit.KG),
        )
    }

    /** The unit is the one the lifter is reading in, not the one the plan was written in. */
    @Test
    fun `the load is rendered in the display unit`() {
        assertTrue(SessionPreviewPolicy.setLine(set(), WeightUnit.LB).endsWith(" lb"))
    }

    @Test
    fun `an empty queue previews as an empty session`() {
        val preview = SessionPreviewPolicy.of(emptyList())
        assertTrue(preview.isEmpty)
        assertEquals(0, preview.totalSets)
        assertEquals(0, preview.warmupSets)
        assertEquals(0, preview.blockCount)
    }

    @Test
    fun `sets of one exercise become one block in the queue order`() {
        val preview =
            SessionPreviewPolicy.of(
                listOf(set(index = 0, reps = 5), set(index = 1, reps = 4), set(index = 2, reps = 3)),
            )
        assertEquals(1, preview.blockCount)
        assertEquals("Back squat", preview.blocks[0].exerciseName)
        assertEquals(listOf(5, 4, 3), preview.blocks[0].sets.map { it.reps })
    }

    /**
     * The case a name-based grouping gets wrong, and the plan format allows it
     * on purpose: a ramp block and a working block of the SAME movement, back
     * to back. `flattenPlan` gives each its own `setsInExercise`, and the
     * record flow counts "Set 1/3" then "Set 1/2" through them, so a preview
     * that merged them would tell the lifter five sets of one thing while the
     * screen they land on says set one of three.
     */
    @Test
    fun `two blocks of one exercise, back to back, stay two blocks`() {
        val preview =
            SessionPreviewPolicy.of(
                listOf(
                    set(index = 0, of = 3, warmup = true, loadKg = 40.0),
                    set(index = 1, of = 3, warmup = true, loadKg = 60.0),
                    set(index = 2, of = 3, warmup = true, loadKg = 80.0),
                    set(index = 0, of = 2, loadKg = 100.0),
                    set(index = 1, of = 2, loadKg = 100.0),
                ),
            )
        assertEquals(2, preview.blockCount)
        assertEquals(listOf(3, 2), preview.blocks.map { it.sets.size })
        assertEquals(5, preview.totalSets)
        assertEquals(3, preview.warmupSets)
    }

    /**
     * The property the case above is one instance of, stated so a later
     * grouping rule cannot pass it by accident: a block holds exactly the sets
     * the queue said were in it.
     */
    @Test
    fun `a block holds the number of sets its own slots declare`() {
        val preview =
            SessionPreviewPolicy.of(
                listOf(
                    set(index = 0, of = 2),
                    set(index = 1, of = 2),
                    set(index = 0, of = 1),
                    set(name = "Bench press", index = 0, of = 1),
                ),
            )
        preview.blocks.forEach { block ->
            assertEquals(
                block.sets.first().setsInExercise,
                block.sets.size,
                "block of ${block.exerciseName} holds ${block.sets.size} sets, its slots declare " +
                    "${block.sets.first().setsInExercise}",
            )
        }
    }

    @Test
    fun `two exercises become two blocks, in the queue order`() {
        val preview =
            SessionPreviewPolicy.of(
                listOf(
                    set(name = "Back squat", index = 0, of = 2),
                    set(name = "Back squat", index = 1, of = 2),
                    set(name = "Bench press", index = 0, of = 1),
                ),
            )
        assertEquals(listOf("Back squat", "Bench press"), preview.blocks.map { it.exerciseName })
        assertEquals(listOf(2, 1), preview.blocks.map { it.sets.size })
    }
}
