package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * RED. `muscle_up`, `inverted_row` and `rope_dead_hang` are body-weight
 * movements the corpus and the owner's plans use, and they are outside
 * [ExerciseDef.BODYWEIGHT_IDS] (#239). Every test in this class fails at the
 * commit that introduces it; the commit after it widens the table and is what
 * makes them pass.
 *
 * The failure they describe, in the lifter's terms: a plan that OMITS
 * `"bodyweight"` on one of the three records the set's `load_kg` as the ADDED
 * load alone, so a hang with nothing added is written as `0.0` -- a number
 * where the truth is that the load is the lifter, and one nothing downstream
 * can tell from a genuinely unloaded set. That is the "absence rendered as a
 * value" class, and it is what #61's refusal exists to stop; the refusal never
 * fires here because the resolved `bodyweight` flag is false.
 *
 * WHY THE OMITTED KEY AND NOT THE DECLARED ONE. A plan that declares
 * `"bodyweight": true` is already covered, on any id at all, and
 * `BodyweightWideningBaselineTest` pins that. So these tests are all written
 * with the key absent, which is the only case left.
 *
 * The tests trace the widened table to its LAST consumers, not its first: the
 * set refusal, the session-level ask, the plan validator's negative-load
 * allowance and the import gate line. A table widened and consulted in one
 * place only would read as fixed while three of those four still answered the
 * old way.
 */
class BodyweightThreeIdDifferentialTest {
    private val three = listOf("muscle_up", "inverted_row", "rope_dead_hang")

    private fun planOf(id: String, set: String): PlanImport.Result = PlanImport.parse(
        """
        {"schemaVersion":"1.12","planName":"P","sessions":[{"name":"S","exercises":[
          {"exercise":"$id","sets":[$set]}
        ]}]}
        """.trimIndent(),
    )

    /** RED. The table itself. */
    @Test
    fun `muscle up inverted row and rope dead hang are body weight by construction`() {
        three.forEach { id ->
            assertTrue(ExerciseDef.isBodyweightByConstruction(id), "$id must be body weight by construction")
        }
    }

    /** RED. The seed default an omitted key falls back to. */
    @Test
    fun `the mount policy seeds all three when a plan declares nothing`() {
        three.forEach { id ->
            assertTrue(
                SetGeometryPolicy.bodyweightMount(id, base = false, declared = null),
                "$id with no declaration must resolve as body weight",
            )
        }
    }

    /**
     * RED, and the defect as the lifter meets it: a plan slot with the key
     * omitted must resolve to a body-weight definition, so
     * [SetLoadPolicy.blocksSetStart] refuses the set instead of letting
     * [SetLoadPolicy.totalKg] render the absent body weight as `0.0`.
     */
    @Test
    fun `a plan that omits the flag refuses the set when no body weight is stored`() {
        three.forEach { id ->
            val declared = planOf(id, """{"reps":3}""").plan!!.sessions[0].exercises[0]
            val used = SetGeometryPolicy.resolve(ExerciseDef(id, id), declared)
            assertTrue(used.bodyweight, "$id with no declaration must resolve as body weight")
            assertTrue(
                SetLoadPolicy.blocksSetStart(used.bodyweight, bodyWeightKg = null),
                "$id must be refused with nothing stored",
            )
            assertEquals(
                82.0,
                SetLoadPolicy.totalKg(used.bodyweight, bodyWeightKg = 82.0, addedKg = 0.0),
                "$id with a stored body weight must record it as the load",
            )
        }
    }

    /**
     * RED. A session made of nothing but one of the three needs the stored
     * figure exactly as a session of dips does, so the ask must fire on it.
     */
    @Test
    fun `a session of any of the three asks for a body weight`() {
        three.forEach { id ->
            val session = planOf(id, """{"reps":3}""").plan!!.sessions[0]
            assertTrue(
                BodyWeightPromptPolicy.sessionNeedsBodyWeight(session),
                "$id must make the session need a body weight",
            )
        }
    }

    /**
     * RED. Assistance is signed: a band or an assist machine takes weight off
     * the lifter, so a negative `load_kg` is meaningful on body-weight work and
     * `PlanFile.validate` allows it on exactly that population. With the key
     * omitted on the three, the validator rejects the plan outright today.
     */
    @Test
    fun `a plan may declare an assist load on the three with the flag omitted`() {
        three.forEach { id ->
            val errors = planOf(id, """{"reps":3,"load_kg":-20}""").errors
            assertTrue(errors.isEmpty(), "$id assisted plan must validate, got $errors")
        }
    }

    /**
     * RED. The import gate has to say when the app decided for a plan that said
     * nothing -- the decision changes whether the stored body weight is added
     * to the set's load at all, and nothing else before the set is recorded
     * says so.
     */
    @Test
    fun `the import gate names all three when the plan omits the flag`() {
        three.forEach { id ->
            val warnings = planOf(id, """{"reps":3}""").warnings
            assertTrue(
                warnings.any { it.contains(id) && it.contains("built in as body-weight work") },
                "$id must draw a seed line at the gate, got $warnings",
            )
        }
    }
}
