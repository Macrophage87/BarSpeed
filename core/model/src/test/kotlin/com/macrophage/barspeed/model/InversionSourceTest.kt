package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Where a set's `sensorInverted` came from, as [SetGeometryPolicy.describe]
 * records it (#289, export 1.26).
 *
 * ## Why the question exists
 *
 * `sensorInverted` sets `sensorToLifter` to plus or minus one, so it decides
 * which measured phase is called the concentric. Field-41's two stack-clipped
 * cable stations disagree about it -- the pushdown false beside a seeded stack
 * mount, the pulldown true beside a declared one -- and until 1.26 nothing in
 * the export could say whether either came from the plan, from the stack rule
 * or from nowhere.
 *
 * ## The four words, read the way the seven siblings read them
 *
 * - `declared`: the plan wrote the key, either way.
 * - `inferred`: nothing wrote it and [SetGeometryPolicy.stackInversion]'s rule
 *   3 set it from the resolved mount, drive and plane. A rule is reasoning the
 *   app does, which is what `inferred` names; it is not the id-word guess
 *   `inferred` names for `startsWith`, and the published description says so.
 *   It stays `inferred` when [SetGeometryPolicy.analysedUnder] takes the
 *   inversion back on the analysed unit's roll, because that is the rule's
 *   answer completed, not a declaration.
 * - `seeded`: nothing wrote it, no rule set it, and the definition the set ran
 *   under carried it -- which no definition the app ships does
 *   (`SetGeometryPolicyTest`'s `no built-in definition is inverted by
 *   construction`), so only a hand-built definition reaches it.
 * - `default`: nothing wrote it, no rule set it, and it is false.
 *
 * RED until `describe` records the source; green, as the pin that a stored
 * row without one decodes to null, in `SetGeometryPolicyTest`.
 */
class InversionSourceTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val down = ""","concentric":"down""""

    private fun declared(id: String, declarations: String = ""): PlanExerciseDef {
        val text =
            """
            {"schemaVersion":"1.13","planName":"P","sessions":[{"name":"S","exercises":[
              {"exercise":"$id"$declarations,"sets":[{"reps":12,"tempo":"1120"}]}
            ]}]}
            """.trimIndent()
        return json.decodeFromString(PlanFile.serializer(), text).sessions[0].exercises[0]
    }

    private fun described(
        id: String,
        declarations: String = "",
        base: ExerciseDef = ExerciseDef(id, id),
    ): ResolvedGeometry {
        val plan = declared(id, declarations)
        return SetGeometryPolicy.describe(SetGeometryPolicy.resolve(base, plan), plan)
    }

    @Test
    fun `a written false is declared, even where the stack rule would have said true`() {
        val g = described("triceps_pushdown", down + ""","sensorInverted":false""")
        assertEquals(false, g.sensorInverted)
        assertEquals(GeometrySource.DECLARED, g.sources.sensorInverted)
    }

    @Test
    fun `a written true is declared`() {
        val g = described("lat_pulldown", down + ""","sensorOnStack":true,"sensorInverted":true""")
        assertEquals(true, g.sensorInverted)
        assertEquals(GeometrySource.DECLARED, g.sources.sensorInverted)
    }

    /** Field-41's pushdown shape: the stack mount left to the table, the drive declared down, the key omitted. */
    @Test
    fun `an inversion the stack rule set is inferred`() {
        val g = described("triceps_pushdown", down)
        assertEquals(true, g.sensorInverted, "the stack rule did not reach this shape, so the fixture is wrong")
        assertEquals(GeometrySource.INFERRED, g.sources.sensorInverted)
    }

    /** The rule's answer taken back on the analysed unit's roll (#323) is still the rule's, not a declaration. */
    @Test
    fun `an inversion the roll took back stays inferred beside its false`() {
        val plan = declared("triceps_pushdown", down)
        val base = ExerciseDef("triceps_pushdown", "triceps_pushdown")
        val used = SetGeometryPolicy.resolve(base, plan)
        val taken =
            SetGeometryPolicy.analysedUnder(
                used = used,
                geometry = SetGeometryPolicy.describe(used, plan),
                stackRuleApplied = SetGeometryPolicy.stackRuleApplied(base, plan),
                analysedSignal = StackMountSignal.NOT_ON_STACK,
            ).geometry
        assertEquals(false, taken.sensorInverted, "the roll did not take the inversion back, so the fixture is wrong")
        assertEquals(GeometrySource.INFERRED, taken.sources.sensorInverted)
    }

    @Test
    fun `a bar lift nothing inverts is default`() {
        val g = described("back_squat", base = ExerciseDef.seedById("back_squat")!!)
        assertEquals(false, g.sensorInverted)
        assertEquals(GeometrySource.DEFAULT, g.sources.sensorInverted)
    }

    /** No plan at all: the ad-hoc path, which applies no rule. */
    @Test
    fun `an ad-hoc set is default`() {
        val g = SetGeometryPolicy.describe(ExerciseDef.seedById("back_squat")!!, null)
        assertEquals(GeometrySource.DEFAULT, g.sources.sensorInverted)
    }

    /** Only a hand-built definition carries an inversion; no shipped seed does. */
    @Test
    fun `an inversion the definition itself carried is seeded`() {
        val inverted = ExerciseDef("cable_row", "Cable Row", sensorInverted = true)
        assertEquals(GeometrySource.SEEDED, SetGeometryPolicy.describe(inverted, null).sources.sensorInverted)
        val g = described("cable_row", ""","plane":"horizontal"""", base = inverted)
        assertEquals(true, g.sensorInverted)
        assertEquals(GeometrySource.SEEDED, g.sources.sensorInverted)
    }
}
