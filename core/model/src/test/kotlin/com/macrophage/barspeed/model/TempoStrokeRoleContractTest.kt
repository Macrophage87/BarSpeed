package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What a tempo digit is ALLOWED to be, read through the digit's ROLE on the
 * lift rather than through its position in the notation (#251).
 *
 * Two rules, and today the code states neither:
 *
 * - `X` -- as fast as possible -- belongs on the CONCENTRIC stroke and nowhere
 *   else. It is a drive instruction. An explosive ECCENTRIC is not a
 *   prescription a lifter can follow, and on a vertical concentric-down lift
 *   -- a lat pulldown, a triceps pushdown, a leg curl -- digit 3 is the
 *   eccentric, so a positional reading offers X on exactly the wrong stroke.
 * - A stroke takes time, so a stroke digit is at least
 *   [TempoAdjustPolicy.MIN_STROKE_S]. Only the two pauses may be 0. The
 *   between-sets wheel already honours that floor; the plan import gate does
 *   not.
 *
 * THESE ARE CHARACTERIZATION PINS, not the rule. Every assertion in this file
 * records what the code does at this commit so that the commit that changes it
 * has to say so in a diff. They are inverted, one by one, by the differentials
 * that follow.
 */
class TempoStrokeRoleContractTest {
    private fun benchPress() = TempoAdjustPolicy.digits(concentricUp = true, horizontal = false)

    private fun pulldown() = TempoAdjustPolicy.digits(concentricUp = false, horizontal = false)

    private fun seatedRow() = TempoAdjustPolicy.digits(concentricUp = true, horizontal = true)

    private fun choicesOf(digits: List<TempoDigit>, position: Int) = digits.first { it.position == position }.choices

    private val oneToNine = (1..9).map { it.toString() }

    /**
     * CHARACTERIZATION. The wheel's alphabet is read off the POSITION alone, so
     * all three lift shapes get the same two lists whichever stroke is the
     * drive.
     */
    @Test
    fun `today every lift shape offers X on digit 3 and on no other digit`() {
        listOf(benchPress(), pulldown(), seatedRow()).forEach { digits ->
            assertEquals(oneToNine + "X", choicesOf(digits, TempoAdjustPolicy.UP_STROKE))
            assertEquals(oneToNine, choicesOf(digits, TempoAdjustPolicy.DOWN_STROKE))
        }
    }

    /**
     * CHARACTERIZATION. On a pulldown digit 3 is the RETURN -- the eccentric --
     * and the wheel offers an explosive one.
     */
    @Test
    fun `today a pulldown's return stroke can be stepped to X`() {
        val digits = pulldown()
        assertEquals("eccentric", digits.first { it.position == TempoAdjustPolicy.UP_STROKE }.caption)
        assertEquals("X", TempoAdjustPolicy.steppedValue("3090", TempoAdjustPolicy.UP_STROKE, 1))
        assertTrue(TempoAdjustPolicy.canStep("3090", TempoAdjustPolicy.UP_STROKE, 1))
    }

    /**
     * CHARACTERIZATION. X sits ABOVE 9, so the fastest stroke there is lives at
     * the slow end of the range and stepping DOWN from 1 offers nothing.
     */
    @Test
    fun `today X sits above nine rather than below one`() {
        assertEquals("X", TempoAdjustPolicy.steppedValue("3090", TempoAdjustPolicy.UP_STROKE, 1))
        assertEquals("1", TempoAdjustPolicy.steppedValue("3010", TempoAdjustPolicy.UP_STROKE, -1))
        assertFalse(TempoAdjustPolicy.canStep("3010", TempoAdjustPolicy.UP_STROKE, -1))
    }

    /**
     * CHARACTERIZATION. [Tempo.parse] takes a 0 stroke, so a plan can declare
     * one and the import gate reports nothing.
     *
     * `CadencePlan.of` then floors the stroke at one second while
     * `SetAnalyzer.complianceFor` goes on grading the lifter against the 0 --
     * the app plays one prescription and scores another.
     */
    @Test
    fun `today the plan gate accepts a stroke of zero`() {
        assertNotNull(Tempo.parseOrNull("0010"), "a zero down stroke")
        assertNotNull(Tempo.parseOrNull("3000"), "a zero up stroke")
        assertNotNull(Tempo.parseOrNull("0-0-1-0"), "and the dash form of the first")
        listOf("0010", "3000", "0-0-1-0").forEach { text ->
            assertEquals(
                emptyList(),
                PlanSetDef(reps = 5, tempo = text).validate("sessions[0].exercises[0].sets[0]"),
                "'$text' passes the import gate",
            )
        }
    }

    /** CHARACTERIZATION. The published pattern accepts a zero stroke too. */
    @Test
    fun `today the published plan schema accepts a stroke of zero`() {
        val pattern = Regex(tempoPattern())
        listOf("0010", "3000", "0-0-1-0").forEach {
            assertTrue(pattern.matches(it), "the published pattern accepts '$it'")
        }
    }

    /** The tempo pattern as published, read from the real document rather than a copy. */
    private fun tempoPattern(): String {
        val schema = Json.parseToJsonElement(
            javaClass.getResourceAsStream("/plan.schema.json")!!.readBytes().decodeToString(),
        ).jsonObject
        return schema["\$defs"]!!.jsonObject["set"]!!.jsonObject["properties"]!!
            .jsonObject["tempo"]!!.jsonObject["pattern"]!!.jsonPrimitive.content
    }
}
