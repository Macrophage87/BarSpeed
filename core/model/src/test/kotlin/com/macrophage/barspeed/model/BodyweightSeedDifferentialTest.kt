package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Differentials for #61's population, filed as #227 item 2: a plan running
 * pull-ups, dips, push-ups, chin-ups or a dead hang without declaring
 * `"bodyweight"` records `loadKg` as the ADDED load alone, because no
 * built-in [ExerciseDef] sets `bodyweight = true` and
 * [SetGeometryPolicy.resolve] takes the flag only from the plan.
 *
 * Four assertions were RED at the commit that introduced them (d9e4a0d6).
 * Before ab12bbbc's fix, `resolve` assigned `declared.bodyweight ?: false`
 * unconditionally over whatever the built-in definition said,
 * [BodyWeightPromptPolicy.sessionNeedsBodyWeight] read the same raw,
 * un-seeded flag, and the import gate said nothing. The green ones are
 * marked as such: a declared value, either way, and an id nothing seeds were
 * both already correct before the fix and stay so after it.
 *
 * Shaped after [StackSeedDifferentialTest] (#223), the same population shape
 * one field earlier.
 */
class BodyweightSeedDifferentialTest {
    private fun exercise(id: String, declarations: String = ""): PlanExerciseDef = PlanImport.parse(
        """
        {"schemaVersion":"1.10","planName":"P","sessions":[{"name":"S","exercises":[
          {"exercise":"$id"$declarations,"sets":[{"reps":5}]}
        ]}]}
        """.trimIndent(),
    ).plan!!.sessions[0].exercises[0]

    private fun adHoc(id: String) = ExerciseDef(id, id.replace('_', ' '))

    private fun warnings(id: String, declarations: String = ""): List<String> = PlanImport.parse(
        """
        {"schemaVersion":"1.10","planName":"P","sessions":[{"name":"S","exercises":[
          {"exercise":"$id"$declarations,"sets":[{"reps":5}]}
        ]}]}
        """.trimIndent(),
    ).warnings

    private fun sessionOf(id: String, declarations: String = ""): PlanSessionDef = PlanImport.parse(
        """
        {"schemaVersion":"1.10","planName":"P","sessions":[{"name":"S","exercises":[
          {"exercise":"$id"$declarations,"sets":[{"reps":5}]}
        ]}]}
        """.trimIndent(),
    ).plan!!.sessions[0]

    /** RED. The #61 shape: the key is absent, and the id is body weight by construction. */
    @Test
    fun `an absent bodyweight key on a pull-up resolves to bodyweight work`() {
        val plan = exercise("pull_up")
        assertEquals(true, SetGeometryPolicy.resolve(adHoc("pull_up"), plan).bodyweight)
    }

    /**
     * GREEN, both before and after ab12bbbc's fix: a declared false wins over
     * the seed either way, since [SetGeometryPolicy.bodyweightMount] returns
     * [declared] unconditionally whenever it is non-null and never reaches
     * the seed at all in that case. Kept beside the red cases as the
     * differential's other half -- the fix must not make this one fail.
     */
    @Test
    fun `a declared false on a body-weight id wins`() {
        val plan = exercise("dip", ""","bodyweight":false""")
        assertEquals(false, SetGeometryPolicy.resolve(adHoc("dip"), plan).bodyweight)
    }

    /** GREEN already, for the same reason: an id nothing seeds has nothing to resolve to. */
    @Test
    fun `an id nothing seeds still resolves to false`() {
        val plan = exercise("back_squat")
        assertEquals(false, SetGeometryPolicy.resolve(ExerciseDef.seedById("back_squat")!!, plan).bodyweight)
    }

    /**
     * RED. The prompt gate: a session of nothing but an unflagged pull-up
     * must ask for a body weight, the way it already does for a declared
     * `"bodyweight": true` -- #181's whole rule is that this is the only
     * thing deciding whether the session needs the figure at all.
     */
    @Test
    fun `a session of an unflagged built-in body-weight id still needs a body weight`() {
        assertTrue(BodyWeightPromptPolicy.sessionNeedsBodyWeight(sessionOf("chin_up")))
    }

    /**
     * RED. The import gate names the inference once per exercise, mirroring
     * `stackSeeded`'s line for `sensorOnStack` (#223).
     */
    @Test
    fun `the gate names an inferred bodyweight seed once for the exercise`() {
        val lines = warnings("push_up").filter { "bodyweight" in it }
        assertEquals(1, lines.size, "expected exactly one bodyweight line: ${warnings("push_up")}")
        assertTrue("push_up" in lines.single(), lines.single())
    }

    /** GREEN, and must stay so: a plan that declares the key is not lectured. */
    @Test
    fun `a plan that declares the bodyweight key draws no bodyweight warning`() {
        for (value in listOf("true", "false")) {
            val lines = warnings("push_up", ""","bodyweight":$value""")
            assertTrue(lines.none { "bodyweight" in it }, "warned about a declared key: $lines")
        }
        assertTrue(
            warnings("back_squat").none { "bodyweight" in it },
            "warned about an id nothing seeds",
        )
    }

    /**
     * RED. The validation consequence: an assisted dead hang recorded with a
     * negative load (an assist band taking weight off) is refused today,
     * because `allowNegativeLoad` reads the same un-seeded flag `resolve`
     * does. Once the seed applies, the omitted key is body-weight work and
     * the negative load is a legitimate assist figure.
     */
    @Test
    fun `an assisted dead hang with a negative load and no declared key validates`() {
        val errors = PlanImport.parse(
            """
            {"schemaVersion":"1.10","planName":"P","sessions":[{"name":"S","exercises":[
              {"exercise":"dead_hang","sets":[{"reps":5,"load_kg":-10.0}]}
            ]}]}
            """.trimIndent(),
        ).errors
        assertTrue(errors.none { "load must be >= 0" in it }, errors.toString())
    }
}
