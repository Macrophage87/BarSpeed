package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the app does today with a body-weight set from a lifter who has never
 * entered a body weight (#61), and with an ad-hoc set against a body-weight
 * movement (#229 item 3).
 *
 * Started as characterization; three tests are now DIFFERENTIALS and are
 * marked RED in their own KDoc. They fail at the commit that introduces them
 * and the commit after it is what makes them pass.
 *
 * What they require:
 *
 * 1. [SetLoadPolicy.blocksSetStart] must refuse to start a body-weight set
 *    while nothing usable is stored, and must refuse nothing else. Without a
 *    refusal, [SetLoadPolicy.totalKg] renders the absence as `0.0` and adds
 *    it, so a pull-up with nothing added records `loadKg = 0.0` -- the
 *    "absence rendered as a value" class, and indistinguishable in the row and
 *    the export from a set that genuinely carried no load.
 * 2. [ExerciseDef.resolvedById] must seed body weight the way
 *    [SetGeometryPolicy.bodyweightMount] already does for a plan. #227's seed
 *    default reaches the PLAN path only, so an ad-hoc dead hang -- the one
 *    [ExerciseDef.BODYWEIGHT_IDS] member the exercise picker actually offers
 *    -- is not body weight at all as far as the recorded load is concerned.
 *    #229 item 3.
 *
 * The pins on [SetLoadPolicy.totalKg] itself stay as they are and stay green:
 * this change does not give the export a way to say "unmeasured", it stops the
 * set that would have needed one. Naming that marker is remaining work.
 */
class BodyWeightAbsenceGateTest {
    private val nowMs = 1_700_000_000_000L

    private fun sessionOf(id: String): PlanSessionDef = PlanImport.parse(
        """
        {"schemaVersion":"1.10","planName":"P","sessions":[{"name":"S","exercises":[
          {"exercise":"$id","sets":[{"reps":5}]}
        ]}]}
        """.trimIndent(),
    ).plan!!.sessions[0]

    @Test
    fun `pull-up is body weight by construction and dead hang is too`() {
        assertTrue(ExerciseDef.isBodyweightByConstruction("pull_up"))
        assertTrue(ExerciseDef.isBodyweightByConstruction("dead_hang"))
        assertFalse(ExerciseDef.isBodyweightByConstruction("back_squat"))
    }

    @Test
    fun `the mount policy seeds those ids when a plan declares nothing`() {
        assertTrue(SetGeometryPolicy.bodyweightMount("pull_up", base = false, declared = null))
        assertTrue(SetGeometryPolicy.bodyweightMount("dead_hang", base = false, declared = null))
    }

    /** `pull_up` has no seed entry at all, so an ad-hoc one starts from the bare constructor. */
    @Test
    fun `pull-up carries no seed entry`() {
        assertNull(ExerciseDef.seedById("pull_up"))
    }

    /**
     * GAP, and the one the issue is named for: nothing added and no body
     * weight stored records a load of zero, not an absence.
     */
    @Test
    fun `a body-weight set with no stored body weight records zero for nothing added`() {
        assertEquals(0.0, SetLoadPolicy.totalKg(bodyweight = true, bodyWeightKg = null, addedKg = 0.0))
    }

    /** GAP. With something added it records the added load alone, on the wrong scale. */
    @Test
    fun `a body-weight set with no stored body weight records the added load alone`() {
        assertEquals(10.0, SetLoadPolicy.totalKg(bodyweight = true, bodyWeightKg = null, addedKg = 10.0))
        assertEquals(-20.0, SetLoadPolicy.totalKg(bodyweight = true, bodyWeightKg = null, addedKg = -20.0))
    }

    /** Absence is already classified correctly; nothing consults it before a set runs. */
    @Test
    fun `stored body weight of null zero or negative all classify as absent`() {
        val absent = BodyWeightPromptPolicy.StoredBodyWeight.ABSENT
        assertEquals(absent, BodyWeightPromptPolicy.stateOf(null, null, nowMs))
        assertEquals(absent, BodyWeightPromptPolicy.stateOf(0.0, null, nowMs))
        assertEquals(absent, BodyWeightPromptPolicy.stateOf(-1.0, null, nowMs))
        assertEquals(absent, BodyWeightPromptPolicy.stateOf(Double.NaN, null, nowMs))
    }

    /**
     * GAP, and the reason a prompt alone does not close #61: the ask is
     * skippable, and a skip leaves the session recording body-weight sets
     * against nothing at all.
     */
    @Test
    fun `a skipped prompt silences the ask even when nothing is stored`() {
        assertFalse(
            BodyWeightPromptPolicy.shouldPrompt(
                session = sessionOf("pull_up"),
                kg = null,
                setAtMs = null,
                nowMs = nowMs,
                skippedThisSession = true,
            ),
        )
    }

    /**
     * RED. An ad-hoc set against a body-weight movement must resolve to a
     * body-weight definition, the same answer a planned one already gets
     * through [SetGeometryPolicy.resolve]. #229 item 3, and part of #61's
     * population.
     */
    @Test
    fun `resolvedById seeds body weight for the ids that are body weight by construction`() {
        ExerciseDef.BODYWEIGHT_IDS.forEach { id ->
            assertTrue(ExerciseDef.resolvedById(id).bodyweight, "$id should resolve as body weight")
        }
    }

    /**
     * A body-weight set is the only thing that changes. An ad-hoc back squat
     * is loaded work and stays loaded work.
     */
    @Test
    fun `resolvedById leaves an ordinary lift alone`() {
        assertFalse(ExerciseDef.resolvedById("back_squat").bodyweight)
        assertFalse(ExerciseDef.resolvedById("not_a_real_lift").bodyweight)
    }

    /**
     * The rest of the seed entry survives. A fix that reached for
     * `ExerciseDef(id, id, bodyweight = true)` would set the flag and throw
     * away the display name, the kind and the barbell answer with it.
     */
    @Test
    fun `resolvedById keeps everything else a seed entry declared`() {
        val hang = ExerciseDef.resolvedById("dead_hang")
        assertEquals("Dead Hang", hang.displayName)
        assertEquals(ExerciseKind.HOLD, hang.kind)
        assertFalse(hang.usesBarbell)
        assertEquals("Back Squat", ExerciseDef.resolvedById("back_squat").displayName)
    }

    /** The one new query written correct from birth: it is a reading of [stateOf], not a fifth rule. */
    @Test
    fun `isAbsent agrees with the ABSENT state and accepts a stored figure of any age`() {
        assertTrue(BodyWeightPromptPolicy.isAbsent(null))
        assertTrue(BodyWeightPromptPolicy.isAbsent(0.0))
        assertTrue(BodyWeightPromptPolicy.isAbsent(-1.0))
        assertTrue(BodyWeightPromptPolicy.isAbsent(Double.NaN))
        assertFalse(BodyWeightPromptPolicy.isAbsent(82.0))
    }

    /**
     * RED, and the refusal #61 asks for: a body-weight set with nothing
     * stored may not start, because the only load it could record is a
     * fabricated one.
     */
    @Test
    fun `a body-weight set with no stored body weight may not start`() {
        assertTrue(SetLoadPolicy.blocksSetStart(bodyweight = true, bodyWeightKg = null))
    }

    /**
     * RED. A stored `0.0` is an absence dressed as a number and must refuse
     * the set exactly as a null does; so must a negative and a NaN.
     */
    @Test
    fun `a stored zero negative or NaN body weight refuses a body-weight set too`() {
        assertTrue(SetLoadPolicy.blocksSetStart(bodyweight = true, bodyWeightKg = 0.0))
        assertTrue(SetLoadPolicy.blocksSetStart(bodyweight = true, bodyWeightKg = -5.0))
        assertTrue(SetLoadPolicy.blocksSetStart(bodyweight = true, bodyWeightKg = Double.NaN))
    }

    /**
     * The refusal must be narrow. A lifter who has stated a body weight is
     * never stopped, however old the figure -- staleness is the prompt's
     * question and it stays skippable (#181).
     */
    @Test
    fun `a stated body weight lets the set start whatever its age`() {
        assertFalse(SetLoadPolicy.blocksSetStart(bodyweight = true, bodyWeightKg = 82.0))
    }

    /**
     * And a barbell set is never stopped, because nothing about it depends on
     * the lifter's mass. This is the assertion that keeps the refusal from
     * becoming a wall in front of every session.
     */
    @Test
    fun `a loaded set starts with no body weight stored at all`() {
        assertFalse(SetLoadPolicy.blocksSetStart(bodyweight = false, bodyWeightKg = null))
        assertFalse(SetLoadPolicy.blocksSetStart(bodyweight = false, bodyWeightKg = 0.0))
    }

    @Test
    fun `the ask does fire on a body-weight session that has not been skipped`() {
        assertTrue(
            BodyWeightPromptPolicy.shouldPrompt(
                session = sessionOf("pull_up"),
                kg = null,
                setAtMs = null,
                nowMs = nowMs,
                skippedThisSession = false,
            ),
        )
    }
}
