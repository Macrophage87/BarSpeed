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
 * CHARACTERIZATION ONLY. Every assertion here states the answer at the commit
 * that introduced this file; several of them state a defect. The ones that do
 * are marked GAP, and the differential commit repoints exactly those.
 *
 * The two facts this file exists to hold still are:
 *
 * 1. [SetLoadPolicy.totalKg] renders an absent body weight as `0.0` and adds
 *    it, so a pull-up with nothing added records `loadKg = 0.0` -- the "absence
 *    rendered as a value" class, and indistinguishable in the row and the
 *    export from a set that genuinely carried no load.
 * 2. The seed default #227 landed reaches the PLAN path only.
 *    `RecordState.currentExercise` resolves an ad-hoc set as
 *    `seedById(id) ?: ExerciseDef(id, id)`, which never consults
 *    [SetGeometryPolicy.bodyweightMount], so an ad-hoc dead hang -- the one
 *    [ExerciseDef.BODYWEIGHT_IDS] member the picker actually offers -- is not
 *    body weight at all as far as the recorded load is concerned.
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

    /**
     * The exact expression `RecordState.currentExercise` falls back to for an
     * ad-hoc set, restated here so a differential has something to point at.
     */
    private fun adHocToday(id: String): ExerciseDef = ExerciseDef.seedById(id) ?: ExerciseDef(id, id)

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

    /** GAP. The picker offers `dead_hang`; its seed entry says the body is not the load. */
    @Test
    fun `an ad-hoc dead hang resolves today to a definition that is not body weight`() {
        assertFalse(adHocToday("dead_hang").bodyweight)
    }

    /** GAP. `pull_up` has no seed entry at all, so an ad-hoc one is a bare constructor call. */
    @Test
    fun `an ad-hoc pull-up resolves today to a bare definition that is not body weight`() {
        assertNull(ExerciseDef.seedById("pull_up"))
        assertFalse(adHocToday("pull_up").bodyweight)
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
     * The lift of `RecordState.currentExercise`'s ad-hoc fallback, pinned as
     * the identity it is: same answer as the expression it replaced, for a
     * seeded id, a body-weight seeded id and an unseeded one alike.
     */
    @Test
    fun `resolvedById answers exactly what the ad-hoc fallback answered`() {
        listOf("back_squat", "dead_hang", "pull_up", "not_a_real_lift").forEach { id ->
            assertEquals(adHocToday(id), ExerciseDef.resolvedById(id))
        }
    }

    /** GAP, carried by the lifted function now: an ad-hoc dead hang is still not body weight. */
    @Test
    fun `resolvedById does not seed body weight today`() {
        assertFalse(ExerciseDef.resolvedById("dead_hang").bodyweight)
        assertFalse(ExerciseDef.resolvedById("pull_up").bodyweight)
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
     * GAP. Nothing consults the stored body weight before a set starts, and
     * this bug-preserving identity says so out loud.
     */
    @Test
    fun `nothing blocks the start of a body-weight set today`() {
        assertFalse(SetLoadPolicy.blocksSetStart(bodyweight = true, bodyWeightKg = null))
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
