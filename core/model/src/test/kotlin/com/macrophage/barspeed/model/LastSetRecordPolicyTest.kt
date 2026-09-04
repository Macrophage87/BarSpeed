package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the rest screen's "Last set" box states (#237).
 *
 * Two halves. [LastSetRecordPolicy.values] is pinned as a CONTRACT: the strike
 * pairs a rest-screen correction against the figure the row was written with,
 * and every case below is a set the lifter can actually produce. The GAP-marked
 * cases are CHARACTERIZATIONS of the seam [LastSetRecordPolicy.status] is at
 * this commit -- it says the effort and nothing else -- and they are deleted by
 * the differential rather than reworded, because #237 asks for the limiter word
 * and the warm-up mark beside it.
 */
class LastSetRecordPolicyTest {
    private fun repValues(
        recordedAddedKg: Double = 100.0,
        correctedAddedKg: Double? = null,
        countedReps: Int? = 5,
        correctedReps: Int? = null,
        bodyweight: Boolean = false,
        side: String? = null,
    ) = LastSetRecordPolicy.values(
        kind = ExerciseKind.DYNAMIC,
        bodyweight = bodyweight,
        unit = WeightUnit.KG,
        side = side,
        recordedAddedKg = recordedAddedKg,
        correctedAddedKg = correctedAddedKg,
        countedReps = countedReps,
        correctedReps = correctedReps,
        recordedDurationS = null,
        correctedDurationS = null,
    )

    private fun holdValues(
        recordedDurationS: Int? = 30,
        correctedDurationS: Int? = null,
        recordedAddedKg: Double = 0.0,
        bodyweight: Boolean = false,
    ) = LastSetRecordPolicy.values(
        kind = ExerciseKind.HOLD,
        bodyweight = bodyweight,
        unit = WeightUnit.KG,
        side = null,
        recordedAddedKg = recordedAddedKg,
        correctedAddedKg = null,
        countedReps = 0,
        correctedReps = null,
        recordedDurationS = recordedDurationS,
        correctedDurationS = correctedDurationS,
    )

    @Test
    fun `an uncorrected rep set states what was recorded and strikes nothing`() {
        val values = repValues()
        assertEquals("5 reps · 100 kg", SetCardValues.plain(values))
        assertTrue(values.all { it.planned == null }, "nothing to strike on a set nobody corrected")
    }

    @Test
    fun `a corrected rep count strikes the count the row was written with`() {
        val reps = repValues(correctedReps = 6).first { it.suffix == "reps" }
        assertEquals("6", reps.stated)
        assertEquals("5", reps.planned)
    }

    @Test
    fun `a corrected load strikes the load the row was written with`() {
        val load = repValues(correctedAddedKg = 90.0).last()
        assertEquals("90 kg", load.stated)
        assertEquals("100 kg", load.planned)
    }

    @Test
    fun `a set corrected down to an empty bar says the zero rather than going blank`() {
        val load = repValues(correctedAddedKg = 0.0).last()
        assertEquals("0 kg", load.stated)
        assertEquals("100 kg", load.planned)
    }

    @Test
    fun `body-weight work keeps its notation on both sides of the strike`() {
        val load = repValues(recordedAddedKg = 10.0, correctedAddedKg = 20.0, bodyweight = true).last()
        assertEquals("BW + 20 kg", load.stated)
        assertEquals("BW + 10 kg", load.planned)
    }

    @Test
    fun `a timed set states its hold and never a rep count`() {
        assertEquals("30s hold · bodyweight", SetCardValues.plain(holdValues()))
    }

    @Test
    fun `a corrected hold strikes the seconds the row was written with`() {
        val hold = holdValues(correctedDurationS = 45).first { it.suffix == "hold" }
        assertEquals("45s", hold.stated)
        assertEquals("30s", hold.planned)
    }

    @Test
    fun `the arm the set was worked is stated and never struck`() {
        val side = repValues(side = "left").first()
        assertEquals("Left", side.stated)
        assertNull(side.planned, "no rest-screen control corrects the side, so there is no pair")
    }

    @Test
    fun `no figure states a tempo`() {
        assertTrue(repValues().none { it.prefix == "tempo" })
        assertTrue(holdValues().none { it.prefix == "tempo" })
    }

    @Test
    fun `a rated set reads the wording of the tile the lifter lit`() {
        assertEquals("Hard · RPE 8", status(rated = "Hard · RPE 8"))
    }

    @Test
    fun `an unrated set names the absence rather than going blank`() {
        assertEquals(EffortCorrectionPolicy.NOT_RATED, status())
    }

    @Test
    fun `a failed set says so`() {
        assertEquals(EffortCorrectionPolicy.FAILED, status(failed = true))
    }

    @Test
    fun `GAP a limiter answer is nowhere in the status`() {
        assertEquals(
            EffortCorrectionPolicy.FAILED,
            status(failed = true),
            "the box cannot yet say why the set ended; #237's differential removes this",
        )
    }

    @Test
    fun `GAP a warm-up mark is nowhere in the status`() {
        assertEquals(
            "Hard · RPE 8",
            status(rated = "Hard · RPE 8"),
            "the box cannot yet say the set was preparatory; #237's differential removes this",
        )
    }

    private fun status(
        rated: String? = null,
        rpe: Int? = null,
        failed: Boolean = false,
        limiter: SetLimiter? = null,
        note: String? = null,
        timed: Boolean = false,
        declared: Boolean = false,
        mark: Boolean? = null,
    ) = LastSetRecordPolicy.status(
        ratedDescription = rated,
        rpe = rpe,
        failed = failed,
        limiter = limiter,
        limiterNote = note,
        timed = timed,
        warmupDeclared = declared,
        warmupMark = mark,
    )
}
