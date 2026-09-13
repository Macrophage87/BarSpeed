package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Whose count a stored row's rep figure is.
 *
 * ## The table is the contract
 *
 * Twenty-four rows: three live counts (none, zero, three) against
 * `repsManual`, `timed` and `guideCounted`. Written out by hand rather than
 * re-derived from [RepsSourcePolicy.published]'s own branches, for
 * `CountingPolicyTest`'s reason -- a re-derivation agrees by construction.
 *
 * A live count of ZERO is in the table on purpose. It is the row that decides
 * whether the derivation reads absence as a low number, which is the repo's
 * *absence rendered as a value* class: a sensor that resolved no rep must read
 * `sensor`, not `analysis`.
 */
class RepsSourcePolicyTest {
    /** A sensor count nobody argued with is the sensor's. */
    @Test
    fun `a live count with no manual flag is the sensor's`() {
        assertEquals(
            RepsSource.SENSOR,
            RepsSourcePolicy.published(3, repsManual = false, timed = false, guideCounted = false),
        )
    }

    /**
     * A sensor set that resolved nothing still reads `sensor`.
     *
     * Zero is a count. Reading it as no-live-counter would publish `analysis`
     * for the set whose detector saw nothing, which is the one set a coach most
     * needs told apart from a set nothing counted.
     */
    @Test
    fun `a live count of zero is the sensor's, not an absent counter`() {
        assertEquals(
            RepsSource.SENSOR,
            RepsSourcePolicy.published(0, repsManual = false, timed = false, guideCounted = false),
        )
    }

    /** A live count plus the manual flag is the lifter having disagreed with it. */
    @Test
    fun `a live count with the manual flag is a correction`() {
        assertEquals(
            RepsSource.CORRECTED,
            RepsSourcePolicy.published(3, repsManual = true, timed = false, guideCounted = false),
        )
        assertEquals(
            RepsSource.CORRECTED,
            RepsSourcePolicy.published(0, repsManual = true, timed = false, guideCounted = false),
        )
    }

    /** No live count, the manual flag, no tempo: the lifter's own tally. */
    @Test
    fun `no live count with the manual flag and no tempo is the lifter's tally`() {
        assertEquals(
            RepsSource.MANUAL,
            RepsSourcePolicy.published(null, repsManual = true, timed = false, guideCounted = false),
        )
    }

    /**
     * No live count, the manual flag, and A CADENCE HAVING RUN: the guide
     * counted it.
     *
     * The word exists because the alternative is publishing `manual` for a set
     * the lifter did not count. The guide calls each rep on its own schedule
     * whether or not the lifter follows it, so a coach reading `manual` there
     * would credit the lifter with a tally they never made.
     *
     * RENAMED from `no live count with the manual flag and a tempo is the
     * metronome's`, which named the wrong input: a tempo written on an
     * explosive lift plays no cadence, and this branch published `metronome`
     * for exactly that set until the caller stopped passing the raw tempo.
     * `the guide counts only the set a cadence was played on` is where that
     * distinction is now pinned.
     */
    @Test
    fun `no live count with the manual flag and a cadence having run is the metronome's`() {
        assertEquals(
            RepsSource.METRONOME,
            RepsSourcePolicy.published(null, repsManual = true, timed = false, guideCounted = true),
        )
    }

    /**
     * WHICH SETS THE GUIDE COUNTS, over every shape a row can carry.
     *
     * The input above is not "a tempo is written on the row". An explosive lift
     * is judged on peak velocity and is given no cadence whatever tempo it
     * carries, so its count is the lifter's own taps -- and a derivation
     * reading the tempo alone publishes the guide's word for a set no guide
     * paced. Written out by hand for the table's reason: re-deriving it from
     * [RepsSourcePolicy.guideCounted]'s own branches agrees by construction.
     *
     * HOLD AND CARRY ARE IN THE ANSWER at `isTimed = false`, which no row
     * reaches: a hold's row carries a duration, so [RepsSourcePolicy.published]
     * has already returned null for it one branch earlier. They are listed
     * because this pin is over every shape the function can be ASKED, and
     * quietly omitting the pair that cannot occur is how a shape stops being
     * covered the day it can.
     */
    @Test
    fun `the guide counts only the set a cadence was played on`() {
        val counted = mutableListOf<Triple<Boolean, Boolean, ExerciseKind>>()
        for (hasTempo in listOf(false, true)) {
            for (isTimed in listOf(false, true)) {
                for (kind in ExerciseKind.entries) {
                    if (RepsSourcePolicy.guideCounted(hasTempo, isTimed, kind)) {
                        counted += Triple(hasTempo, isTimed, kind)
                    }
                }
            }
        }
        assertEquals(
            listOf(
                Triple(true, false, ExerciseKind.DYNAMIC),
                Triple(true, false, ExerciseKind.HOLD),
                Triple(true, false, ExerciseKind.CARRY),
            ),
            counted,
            "the shapes the guide counts moved",
        )
    }

    /**
     * An explosive lift's tempo is not a cadence, which is the whole reason
     * this input is derived rather than read off the tempo column.
     */
    @Test
    fun `an explosive lift carrying a tempo was counted by nobody but the lifter`() {
        assertEquals(
            false,
            RepsSourcePolicy.guideCounted(hasTempo = true, isTimed = false, kind = ExerciseKind.EXPLOSIVE),
        )
        assertEquals(
            RepsSource.MANUAL,
            RepsSourcePolicy.published(
                null,
                repsManual = true,
                timed = false,
                guideCounted =
                RepsSourcePolicy.guideCounted(hasTempo = true, isTimed = false, kind = ExerciseKind.EXPLOSIVE),
            ),
        )
    }

    /**
     * A row with no stored geometry falls back to the tempo, and that is the
     * one collapse left in this derivation.
     *
     * Such a row does not say what kind of exercise it was -- the column did
     * not exist when it was written -- so a tempo on it reads as guided, which
     * is what a tempo'd row almost always was. Stated rather than hidden, and
     * it is why absence is a parameter here rather than a defaulted `DYNAMIC`.
     */
    @Test
    fun `a row with no stored geometry reads its tempo as the guide`() {
        assertEquals(true, RepsSourcePolicy.guideCounted(hasTempo = true, isTimed = false, kind = null))
        assertEquals(false, RepsSourcePolicy.guideCounted(hasTempo = false, isTimed = false, kind = null))
        assertEquals(
            true,
            RepsSourcePolicy.guideCounted(hasTempo = true, isTimed = true, kind = null),
            "a timed row's fallback stopped reading its tempo",
        )
    }

    /**
     * No live count and no manual flag is the batch segmenter's count.
     *
     * Every sensor-counted set recorded before the live count was stored is in
     * this state: the row's `actualReps` was `manualReps ?: analysis.reps.size`,
     * so with no manual figure it IS the segmenter's.
     */
    @Test
    fun `no live count and no manual flag is the batch segmenter's count`() {
        assertEquals(
            RepsSource.ANALYSIS,
            RepsSourcePolicy.published(null, repsManual = false, timed = false, guideCounted = false),
        )
        assertEquals(
            RepsSource.ANALYSIS,
            RepsSourcePolicy.published(null, repsManual = false, timed = false, guideCounted = true),
        )
    }

    /**
     * A timed set has no word, whatever else the row holds.
     *
     * Null means one thing: nothing counted reps. It is not a sixth word and it
     * is not an omission -- the `reps` figure on such a row is whatever the
     * segmenter made of one long movement.
     */
    @Test
    fun `a timed set publishes no word at all`() {
        for (liveReps in listOf(null, 0, 3)) {
            for (repsManual in listOf(false, true)) {
                for (guideCounted in listOf(false, true)) {
                    assertNull(
                        RepsSourcePolicy.published(liveReps, repsManual, timed = true, guideCounted = guideCounted),
                        "live=$liveReps manual=$repsManual guide=$guideCounted published a counter for a timed set",
                    )
                }
            }
        }
    }

    /** Every row, from a table written out by hand. */
    @Test
    fun `every row answers from a table written out by hand`() {
        TABLE.forEach { (row, expected) ->
            assertEquals(
                expected,
                RepsSourcePolicy.published(row.liveReps, row.repsManual, row.timed, row.guideCounted),
                "$row",
            )
        }
        assertEquals(3 * 2 * 2 * 2, TABLE.size, "the table stopped covering every row")
        assertEquals(TABLE.size, TABLE.map { it.first }.toSet().size, "a row is listed twice")
    }

    /** Every word in the vocabulary is reachable from some row. */
    @Test
    fun `every word is reachable and none is spelt twice`() {
        assertEquals(
            RepsSource.entries.toSet(),
            TABLE.mapNotNull { it.second }.toSet(),
            "a word in the vocabulary is unreachable, or the table reaches one that is not in it",
        )
        assertEquals(
            RepsSource.entries.size,
            RepsSource.entries.map { it.wireName }.toSet().size,
            "two words share a wire spelling",
        )
        assertEquals(
            listOf("sensor", "manual", "metronome", "corrected", "analysis"),
            RepsSource.entries.map { it.wireName },
            "the published spellings moved",
        )
    }

    /** The string form is the same answer. */
    @Test
    fun `the word form agrees with the enum form on every row`() {
        TABLE.forEach { (row, expected) ->
            assertEquals(
                expected?.wireName,
                RepsSourcePolicy.publishedWord(row.liveReps, row.repsManual, row.timed, row.guideCounted),
                "$row",
            )
        }
    }

    private data class Row(
        val liveReps: Int?,
        val repsManual: Boolean,
        val timed: Boolean,
        val guideCounted: Boolean,
    )

    private companion object {
        private val TABLE: List<Pair<Row, RepsSource?>> =
            listOf(
                Row(null, repsManual = false, timed = false, guideCounted = false) to RepsSource.ANALYSIS,
                Row(null, repsManual = false, timed = false, guideCounted = true) to RepsSource.ANALYSIS,
                Row(null, repsManual = false, timed = true, guideCounted = false) to null,
                Row(null, repsManual = false, timed = true, guideCounted = true) to null,
                Row(null, repsManual = true, timed = false, guideCounted = false) to RepsSource.MANUAL,
                Row(null, repsManual = true, timed = false, guideCounted = true) to RepsSource.METRONOME,
                Row(null, repsManual = true, timed = true, guideCounted = false) to null,
                Row(null, repsManual = true, timed = true, guideCounted = true) to null,
                Row(0, repsManual = false, timed = false, guideCounted = false) to RepsSource.SENSOR,
                Row(0, repsManual = false, timed = false, guideCounted = true) to RepsSource.SENSOR,
                Row(0, repsManual = false, timed = true, guideCounted = false) to null,
                Row(0, repsManual = false, timed = true, guideCounted = true) to null,
                Row(0, repsManual = true, timed = false, guideCounted = false) to RepsSource.CORRECTED,
                Row(0, repsManual = true, timed = false, guideCounted = true) to RepsSource.CORRECTED,
                Row(0, repsManual = true, timed = true, guideCounted = false) to null,
                Row(0, repsManual = true, timed = true, guideCounted = true) to null,
                Row(3, repsManual = false, timed = false, guideCounted = false) to RepsSource.SENSOR,
                Row(3, repsManual = false, timed = false, guideCounted = true) to RepsSource.SENSOR,
                Row(3, repsManual = false, timed = true, guideCounted = false) to null,
                Row(3, repsManual = false, timed = true, guideCounted = true) to null,
                Row(3, repsManual = true, timed = false, guideCounted = false) to RepsSource.CORRECTED,
                Row(3, repsManual = true, timed = false, guideCounted = true) to RepsSource.CORRECTED,
                Row(3, repsManual = true, timed = true, guideCounted = false) to null,
                Row(3, repsManual = true, timed = true, guideCounted = true) to null,
            )
    }
}
