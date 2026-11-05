package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [PlanExerciseDef.approvalBodyweight]: whether the plan approval screen draws
 * an exercise's loads as body-weight work (#229 item 1).
 *
 * Characterization of the three cases that do not depend on the seed: a
 * declaration either way is read as written, and an ordinary lift that says
 * nothing is loaded work.
 */
class PlanApprovalBodyweightTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun exercise(id: String, declarations: String = ""): PlanExerciseDef = json.decodeFromString(
        PlanFile.serializer(),
        """
        {"schemaVersion":"1.4","planName":"P","sessions":[{"name":"S","exercises":[
          {"exercise":"$id"$declarations,"sets":[{"reps":5}]}
        ]}]}
        """.trimIndent(),
    ).sessions[0].exercises[0]

    @Test
    fun `a declaration is drawn as written, either way`() {
        assertTrue(exercise("weighted_step_up", ""","bodyweight":true""").approvalBodyweight)
        assertFalse(exercise("pull_up", ""","bodyweight":false""").approvalBodyweight)
    }

    @Test
    fun `an ordinary lift that says nothing is drawn as loaded work`() {
        assertFalse(exercise("back_squat").approvalBodyweight)
        assertFalse(exercise("cable_fly").approvalBodyweight)
    }
}
