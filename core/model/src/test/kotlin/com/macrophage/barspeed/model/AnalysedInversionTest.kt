package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Whether the stack inversion rule reaches the unit a set is actually analysed
 * from. Issue #323.
 *
 * ## The defect
 *
 * Since #317, [SetGeometryPolicy.stackInversion]'s rule 3 reads a set inverted
 * wherever it resolves onto the stack with its drive going down in the vertical
 * plane and the plan names no inversion. The stack mount it reads is the
 * RESOLVED one, so a plan leaving `sensorOnStack` to the app's stack table
 * gets the inversion too -- and the table says which machine, never where the
 * unit was clipped. A unit on the handle or the rope moves WITH the drive, so
 * reading it inverted swaps the drive and the return.
 *
 * ## What this file pins, and in which commit
 *
 * The seam first, green before the fix and after: which slots the rule built
 * an inversion into, what the analysed stream's signature is on each shape of
 * capture, and the three cases the fix must not move -- a declared inversion,
 * a rule inversion on a unit whose roll says stack, a set the rule never
 * reached. The case #323 exists for is a separate, red differential.
 */
class AnalysedInversionTest {
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

    private fun base(id: String) = ExerciseDef(id, id)

    private fun ruleApplied(id: String, declarations: String = ""): Boolean =
        SetGeometryPolicy.stackRuleApplied(base(id), declared(id, declarations))

    private fun analysed(
        declarations: String,
        signal: StackMountSignal,
        id: String = "triceps_pushdown",
    ): AnalysedGeometry {
        val plan = declared(id, declarations)
        val used = SetGeometryPolicy.resolve(base(id), plan)
        return SetGeometryPolicy.analysedUnder(
            used = used,
            geometry = SetGeometryPolicy.describe(used, plan),
            stackRuleApplied = SetGeometryPolicy.stackRuleApplied(base(id), plan),
            analysedSignal = signal,
        )
    }

    private fun samples(n: Int, firstMs: Long = 0L): List<ImuSample> = List(n) { i ->
        ImuSample(
            timestampMs = firstMs + i * 10L,
            axG = 0.0,
            ayG = 0.0,
            azG = 1.0,
            wxDps = 0.0,
            wyDps = 0.0,
            wzDps = 0.0,
            rollDeg = 0.0,
            pitchDeg = 0.0,
            yawDeg = 0.0,
        )
    }

    private fun armed() =
        RecordedSensors(count = 2, expected = listOf(SensorRole.A, SensorRole.B), analysed = SensorRole.A)

    // ---- the seam: green before the fix and after ------------------------------

    /**
     * Only an inversion the rule alone decided is one the analysed unit can
     * take back. A declaration either way is somebody's word, horizontal and
     * drive-up work never reach the rule, a declared handle mount takes the
     * set out of it, and with no plan at all [SetGeometryPolicy.resolve]
     * applies no rule.
     */
    @Test
    fun `the rule is recorded as applied exactly where it alone set the inversion`() {
        assertTrue(ruleApplied("triceps_pushdown", down), "field-41's shape: stack from the table, drive down")
        assertTrue(ruleApplied("rope_pushdown", down + ""","sensorOnStack":true"""), "a declared stack")
        assertFalse(ruleApplied("triceps_pushdown", down + ""","sensorInverted":true"""), "declared true")
        assertFalse(ruleApplied("triceps_pushdown", down + ""","sensorInverted":false"""), "declared false")
        assertFalse(ruleApplied("triceps_pushdown", down + ""","sensorOnStack":false"""), "declared handle")
        assertFalse(ruleApplied("cable_face_pull", ""","plane":"horizontal","sensorOnStack":true"""), "horizontal")
        assertFalse(ruleApplied("leg_extension", ""","concentric":"up""""), "drive up")
        assertFalse(SetGeometryPolicy.stackRuleApplied(base("triceps_pushdown"), null), "no plan")
    }

    /** A declaration wins, whatever the analysed stream's roll says. */
    @Test
    fun `a declared inversion stands on a unit whose roll moved`() {
        val result = analysed(down + ""","sensorInverted":true""", StackMountSignal.NOT_ON_STACK)
        assertTrue(result.exercise.sensorInverted)
        assertTrue(result.geometry.sensorInverted)
    }

    /** The case #317 was for: the analysed unit sat on the stack, so the rule stands. */
    @Test
    fun `a rule inversion stands on a unit whose roll says stack`() {
        val result = analysed(down, StackMountSignal.ON_STACK)
        assertTrue(result.exercise.sensorInverted)
        assertTrue(result.geometry.sensorInverted)
    }

    @Test
    fun `a set the rule never reached is analysed as resolved`() {
        val result = analysed(down + ""","sensorOnStack":false""", StackMountSignal.NOT_ON_STACK)
        assertFalse(result.exercise.sensorInverted)
        assertFalse(result.geometry.sensorInverted)
    }

    /**
     * The signature is asked of the stream the figures come from, on a
     * one-sensor set too -- the set #323 names has no role to key a signal by.
     * The supplier answers by buffer identity, so a signal measured on the
     * wrong buffer cannot pass.
     */
    @Test
    fun `a one-sensor set's analysed signal is its one buffer's`() {
        val only = samples(12)
        val capture = armedCaptureOf(
            armed = null,
            secondaryRole = null,
            analysedBuffer = only,
            secondaryBuffer = emptyList(),
            declaresStackMount = true,
            stackSignalOf = { if (it === only) StackMountSignal.NOT_ON_STACK else StackMountSignal.ON_STACK },
        )
        assertSame(only, capture.samples)
        assertEquals(StackMountSignal.NOT_ON_STACK, capture.analysedSignal)
    }

    /** Nothing asks on a set declaring no stack: a still bench unit reads stack as readily. */
    @Test
    fun `a set declaring no stack mount measures nothing`() {
        val capture = armedCaptureOf(
            armed = null,
            secondaryRole = null,
            analysedBuffer = samples(12),
            secondaryBuffer = emptyList(),
            declaresStackMount = false,
            stackSignalOf = { StackMountSignal.ON_STACK },
        )
        assertEquals(StackMountSignal.UNMEASURED, capture.analysedSignal)
    }

    /**
     * On a two-unit set the signal follows the analysis: where #278 moved it
     * onto the partner because the partner's roll said stack, the analysed
     * signal is the partner's, and where it stayed on the armed unit it is the
     * armed unit's.
     */
    @Test
    fun `a two-unit set's analysed signal is the analysed role's`() {
        val a = samples(12)
        val b = samples(12, firstMs = 1L)
        val moved = armedCaptureOf(
            armed(),
            SensorRole.B,
            a,
            b,
            declaresStackMount = true,
            stackSignalOf = { if (it === b) StackMountSignal.ON_STACK else StackMountSignal.NOT_ON_STACK },
        )
        assertSame(b, moved.samples)
        assertEquals(StackMountSignal.ON_STACK, moved.analysedSignal, "the partner's roll, which moved it")

        val stayed = armedCaptureOf(
            armed(),
            SensorRole.B,
            a,
            b,
            declaresStackMount = true,
            stackSignalOf = { StackMountSignal.NOT_ON_STACK },
        )
        assertSame(a, stayed.samples)
        assertEquals(StackMountSignal.NOT_ON_STACK, stayed.analysedSignal, "neither qualified: the armed unit's")
    }

    // ---- the case #323 exists for: red before the fix -------------------------

    /**
     * RED before #323's fix. The rule alone inverted this set, and the unit it
     * is analysed from moved with the drive -- a handle or a rope -- so the
     * inversion is taken back from the definition AND from the description
     * the row stores, and nothing else in the description moves.
     */
    @Test
    fun `a rule inversion is taken back on a unit whose roll moved`() {
        val plan = declared("triceps_pushdown", down)
        val used = SetGeometryPolicy.resolve(base("triceps_pushdown"), plan)
        val described = SetGeometryPolicy.describe(used, plan)
        val result = analysed(down, StackMountSignal.NOT_ON_STACK)
        assertFalse(result.exercise.sensorInverted, "the rope unit is still read with drive and return swapped")
        assertFalse(result.geometry.sensorInverted, "the row still publishes the inversion the analysis dropped")
        assertEquals(used.copy(sensorInverted = false), result.exercise)
        assertEquals(described.copy(sensorInverted = false), result.geometry)
    }

    /**
     * RED before #323's fix. Nothing measured the unit, which is absence and
     * not a verdict -- so it is not evidence the unit rode the stack either,
     * and the rule, which needs that evidence, does not apply.
     */
    @Test
    fun `a rule inversion is taken back on a unit nothing measured`() {
        val result = analysed(down, StackMountSignal.UNMEASURED)
        assertFalse(result.exercise.sensorInverted)
        assertFalse(result.geometry.sensorInverted)
    }
}
