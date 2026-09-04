package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the rest screen's "Last set" box states (#237).
 *
 * Two halves. [LastSetRecordPolicy.values] is pinned as a CONTRACT: the strike
 * pairs a rest-screen correction against the figure the row was written with,
 * and every case below is a set the lifter can actually produce.
 *
 * [LastSetRecordPolicy.status] is where this commit's DIFFERENTIAL is. The seam
 * says the effort and nothing else, so the ten cases from "a failed set says
 * why it ended" down all fail here, and the commit after this one is what makes
 * them pass. 60ac18d5's two GAP characterizations are DELETED rather than
 * reworded: they stated the silence as today's answer, and the differential is
 * what ends it.
 *
 * The clauses are joined by " · " and the limiter one carries a COLON
 * after its label. Both are deliberate. The separator is the one every row on
 * this screen already uses; the colon is what stops "Failed · Ended
 * · Not given" reading as three peers when the middle two are one
 * statement.
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
    fun `a failed set says why it ended beside how it was rated`() {
        assertEquals("Failed · Ended: Muscle failure", status(failed = true, limiter = SetLimiter.MUSCLE))
    }

    @Test
    fun `an unanswered failure names the gap rather than dropping it`() {
        assertEquals(
            "Failed · Ended: ${SetLimiterPolicy.NOT_GIVEN}",
            status(failed = true),
            "the row this box replaces draws SAY WHY in amber; the gap has to survive the move",
        )
    }

    @Test
    fun `a set nobody would be asked about carries no limiter clause at all`() {
        assertEquals("Hard · RPE 8", status(rated = "Hard · RPE 8", rpe = 5))
    }

    @Test
    fun `a completed set rated at the counted end says what limited it`() {
        assertEquals(
            "Hard · RPE 8 · Limited by: Grip was the limit",
            status(rated = "Hard · RPE 8", rpe = 8, limiter = SetLimiter.GRIP),
        )
    }

    @Test
    fun `the lifter's own words are the limiter clause wherever they typed any`() {
        assertEquals(
            "Failed · Ended: rack was taken",
            status(failed = true, limiter = SetLimiter.OTHER, note = "rack was taken"),
        )
    }

    @Test
    fun `a hold reads the hold wording for the answer, never the rep wording`() {
        assertEquals(
            "Failed · Ended: Could not hold it any longer",
            status(failed = true, limiter = SetLimiter.MUSCLE, timed = true),
        )
    }

    @Test
    fun `a warm-up says so`() {
        assertEquals("Hard · RPE 8 · Warm-up", status(rated = "Hard · RPE 8", declared = true))
    }

    @Test
    fun `the lifter's mark beats the plan in both directions`() {
        assertEquals("Hard · RPE 8 · Warm-up", status(rated = "Hard · RPE 8", mark = true))
        assertEquals("Hard · RPE 8", status(rated = "Hard · RPE 8", declared = true, mark = false))
    }

    @Test
    fun `a working set says nothing about warming up`() {
        assertFalse(status(rated = "Hard · RPE 8").contains("Warm-up"))
    }

    @Test
    fun `every clause a set has to say is said at once, in one order`() {
        assertEquals(
            "Failed · Ended: Pain, or something felt wrong · Warm-up",
            status(failed = true, limiter = SetLimiter.PAIN, declared = true),
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
