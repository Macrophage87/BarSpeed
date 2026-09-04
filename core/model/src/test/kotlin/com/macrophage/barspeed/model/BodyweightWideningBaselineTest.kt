package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What already holds for `muscle_up`, `inverted_row` and `rope_dead_hang`
 * before [ExerciseDef.BODYWEIGHT_IDS] is widened to name them (#239), and what
 * must go on holding afterwards.
 *
 * CHARACTERIZATION, not differentials. Every assertion here is true at the
 * commit that introduces it and true at the commit that widens the table; the
 * reds live in `BodyweightThreeIdDifferentialTest`.
 *
 * The first test is the load-bearing one, and it is why this change widens the
 * id table instead of re-keying the refusal off the resolved `bodyweight`
 * flag. #239 offers those as alternatives. They are not: the refusal ALREADY
 * reads the resolved flag -- `RecordViewModel.beginSet` asks
 * [SetLoadPolicy.blocksSetStart] about `RecordState.currentExercise.bodyweight`,
 * and a plan slot's definition is [SetGeometryPolicy.resolve]'s output, which
 * takes a declared `true` first. So a plan-declared `"bodyweight": true` on any
 * id, in or out of the table, is refused today with nothing stored. Re-keying
 * would change nothing and would leave the case #239 is actually about open:
 * the plan that OMITS the key. Field-38 is the same reading from the other
 * side -- two `rope_dead_hang` sets recorded body-weight-inclusive loads
 * because that session's plan declared the flag.
 */
class BodyweightWideningBaselineTest {
    private val three = listOf("muscle_up", "inverted_row", "rope_dead_hang")

    private fun exerciseOf(id: String, bodyweight: String): PlanExerciseDef = PlanImport.parse(
        """
        {"schemaVersion":"1.12","planName":"P","sessions":[{"name":"S","exercises":[
          {"exercise":"$id","bodyweight":$bodyweight,"sets":[{"reps":3}]}
        ]}]}
        """.trimIndent(),
    ).plan!!.sessions[0].exercises[0]

    /**
     * The refusal is already keyed off the resolved flag, so a plan that says
     * `"bodyweight": true` is covered on any id at all -- including one the app
     * has never heard of.
     */
    @Test
    fun `a plan-declared body weight already refuses a set with nothing stored`() {
        (three + "some_custom_hold").forEach { id ->
            val used = SetGeometryPolicy.resolve(ExerciseDef(id, id), exerciseOf(id, "true"))
            assertTrue(used.bodyweight, "$id declared true must resolve as body weight")
            assertTrue(
                SetLoadPolicy.blocksSetStart(used.bodyweight, bodyWeightKg = null),
                "$id declared true must be refused with nothing stored",
            )
        }
    }

    /**
     * And a declared `false` must go on winning after the widening. A plan may
     * genuinely mean an inverted row on a machine whose load is external, and
     * the table is a default for an omitted key, never an override.
     */
    @Test
    fun `a declared false still wins on all three ids`() {
        three.forEach { id ->
            assertFalse(
                SetGeometryPolicy.bodyweightMount(id, base = false, declared = false),
                "$id declared false must stay loaded work",
            )
            val used = SetGeometryPolicy.resolve(ExerciseDef(id, id), exerciseOf(id, "false"))
            assertFalse(used.bodyweight, "$id declared false must resolve as loaded work")
        }
    }

    /**
     * The import gate speaks only when the plan said nothing. A line on a plan
     * that spelled the key out is how a gate becomes something the eye skips,
     * and widening the table must not start one.
     */
    @Test
    fun `the import gate stays silent on all three when the plan declares the flag`() {
        three.forEach { id ->
            listOf("true", "false").forEach { declared ->
                val result = PlanImport.parse(
                    """
                    {"schemaVersion":"1.12","planName":"P","sessions":[{"name":"S","exercises":[
                      {"exercise":"$id","bodyweight":$declared,"sets":[{"reps":3}]}
                    ]}]}
                    """.trimIndent(),
                )
                assertTrue(
                    result.warnings.none { it.contains("built in as body-weight work") },
                    "$id declared $declared must draw no seed line",
                )
            }
        }
    }

    /**
     * What the three do on the AD-HOC path: nothing, because the picker cannot
     * reach them. `RecordState.exerciseOptions` is [ExerciseDef.SEED] and none
     * of the three is a seed entry, so an ad-hoc set against one of them is not
     * reachable from the exercise picker today and the widening changes no
     * ad-hoc behaviour that can be exercised. [ExerciseDef.resolvedById]
     * already routes every id through [SetGeometryPolicy.bodyweightMount], so
     * the day the picker does reach one, the seed follows with no further
     * change -- which is the near-neighbour trap avoided rather than a claim
     * that the path is live.
     */
    @Test
    fun `the ad-hoc picker offers seed entries only and none of the three is one`() {
        three.forEach { id ->
            assertNull(ExerciseDef.seedById(id), "$id must not be a seed entry")
            assertTrue(ExerciseDef.SEED.none { it.id == id }, "$id must not be in the picker's list")
        }
    }

    /**
     * The widening must stay narrow. An assist machine takes load OFF the
     * lifter rather than making the lifter's mass the load, and those three ids
     * are seeded stack-mounted instead -- a different fact about a different
     * id.
     */
    @Test
    fun `the assist machine ids stay outside the body-weight table`() {
        listOf("assisted_pull_up", "assisted_chin_up", "assisted_dip").forEach { id ->
            assertFalse(ExerciseDef.isBodyweightByConstruction(id), "$id must not be body weight by construction")
        }
        assertFalse(ExerciseDef.isBodyweightByConstruction("back_squat"))
        assertFalse(ExerciseDef.isBodyweightByConstruction("barbell_row"))
    }
}
