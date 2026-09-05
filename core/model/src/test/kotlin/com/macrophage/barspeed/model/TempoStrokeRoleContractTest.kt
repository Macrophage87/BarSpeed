package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a tempo digit is ALLOWED to be, read through the digit's ROLE on the
 * lift rather than through its position in the notation (#251).
 *
 * THE DIFFERENTIALS, replacing the five characterization pins that stood in
 * their place one commit back. Each names what it inverted, and each reds
 * against that parent, which is the point of them.
 *
 * Two rules:
 *
 * - `X` -- as fast as possible -- belongs on the CONCENTRIC stroke and nowhere
 *   else, and it is the value BELOW one second, not above nine. It is a drive
 *   instruction: there is no such thing as an explosive eccentric a lifter can
 *   follow, and on a vertical concentric-down lift -- a lat pulldown, a triceps
 *   pushdown, a leg curl -- digit 3 is the eccentric, so a positional reading
 *   offers X on exactly the wrong stroke.
 * - A stroke takes time, so a stroke digit is at least
 *   [TempoAdjustPolicy.MIN_STROKE_S]. Only the two pauses may be 0. The
 *   between-sets wheel already honoured that floor; the plan import gate did
 *   not, and neither did the published pattern.
 *
 * WHAT THIS FILE DOES NOT CLAIM. A vertical concentric-DOWN lift's drive is
 * digit 1, and it still cannot be stepped to X -- not because the role rule
 * says so, but because [Tempo] has no way to hold one: `downS` is a
 * non-nullable Double and [Tempo.parse] refuses a non-numeric digit 1. So on a
 * pulldown NEITHER stroke offers X, and the owner's ask -- step the down
 * stroke on a pulldown to X -- is not discharged here. Widening the notation
 * is #258, which changes [Tempo]'s shape, the plan schema, the voice guide and
 * the tempo scorer together. The role rule is written so that #258 turns the
 * offer on by itself: the concentric digit is offered X exactly where
 * [TempoAdjustPolicy.spellable] says X can be spelled, and #258 moves that one
 * statement.
 */
class TempoStrokeRoleContractTest {
    private fun benchPress() = TempoAdjustPolicy.digits(concentricUp = true, horizontal = false)

    private fun pulldown() = TempoAdjustPolicy.digits(concentricUp = false, horizontal = false)

    private fun seatedRow() = TempoAdjustPolicy.digits(concentricUp = true, horizontal = true)

    private fun at(digits: List<TempoDigit>, position: Int) = digits.first { it.position == position }

    private val oneToNine = (1..9).map { it.toString() }

    private val zeroToNine = (0..9).map { it.toString() }

    /**
     * INVERTS `today every lift shape offers X on digit 3 and on no other
     * digit`.
     *
     * All four digits of all three lift shapes, because the rule is about the
     * whole row: a per-stroke pin would let the pauses drift unnoticed while
     * the strokes were being argued about.
     */
    @Test
    fun `X is offered on the concentric stroke and on no other digit`() {
        listOf(benchPress(), seatedRow()).forEach { digits ->
            assertEquals(listOf("X") + oneToNine, at(digits, TempoAdjustPolicy.UP_STROKE).choices)
            assertEquals(oneToNine, at(digits, TempoAdjustPolicy.DOWN_STROKE).choices)
            assertEquals(zeroToNine, at(digits, TempoAdjustPolicy.BOTTOM_PAUSE).choices)
            assertEquals(zeroToNine, at(digits, TempoAdjustPolicy.TOP_PAUSE).choices)
        }
        val pulldown = pulldown()
        assertEquals(oneToNine, at(pulldown, TempoAdjustPolicy.UP_STROKE).choices, "the return is never explosive")
        assertEquals(oneToNine, at(pulldown, TempoAdjustPolicy.DOWN_STROKE).choices, "the drive cannot spell one")
        assertEquals(zeroToNine, at(pulldown, TempoAdjustPolicy.BOTTOM_PAUSE).choices)
        assertEquals(zeroToNine, at(pulldown, TempoAdjustPolicy.TOP_PAUSE).choices)
    }

    /**
     * INVERTS `today a pulldown's return stroke can be stepped to X`.
     *
     * The caption is asserted beside the alphabet so the two facts sit in one
     * test: this digit is the eccentric AND it is not offered X.
     */
    @Test
    fun `a pulldown's return stroke is never offered X`() {
        val returnStroke = at(pulldown(), TempoAdjustPolicy.UP_STROKE)
        assertEquals("eccentric", returnStroke.caption)
        assertEquals("9", TempoAdjustPolicy.steppedValue("3090", returnStroke, 1))
        assertFalse(TempoAdjustPolicy.canStep("3090", returnStroke, 1), "nine is the end of the return's range")
        assertNull(TempoAdjustPolicy.withDigit("3010", returnStroke, "X"), "and it cannot be written one either")
    }

    /**
     * A plan may still DECLARE `30X0` on a pulldown, and the control draws it
     * rather than refusing to draw at all.
     *
     * The softer of the two available answers, chosen deliberately. Refusing to
     * draw would take the whole tempo control away from that set -- the lifter
     * could not adjust the drive, the pauses or anything else -- over a digit
     * the plan wrote and they did not. Instead the digit shows what the plan
     * said and both buttons move it to the nearest value the wheel does offer,
     * so the state is escapable and not re-enterable.
     */
    @Test
    fun `a plan-authored explosive return is drawn, and steps out of X and not back into it`() {
        val returnStroke = at(pulldown(), TempoAdjustPolicy.UP_STROKE)
        assertEquals(listOf("3", "0", "X", "0"), TempoAdjustPolicy.wheelValues("30X0"), "the control still draws")
        assertEquals("1", TempoAdjustPolicy.steppedValue("30X0", returnStroke, -1))
        assertEquals("1", TempoAdjustPolicy.steppedValue("30X0", returnStroke, 1))
        assertTrue(TempoAdjustPolicy.canStep("30X0", returnStroke, 1), "there is a way out of it")
        assertEquals("3010", TempoAdjustPolicy.withDigit("30X0", returnStroke, "1"))
    }

    /**
     * INVERTS `today X sits above nine rather than below one`.
     *
     * X is the FASTEST stroke there is, so it belongs at the fast end of the
     * range. Above nine it sat at the slow end, where one mis-tap past a
     * nine-second stroke produced an explosive one.
     */
    @Test
    fun `the drive steps below one into X and stops there`() {
        val drive = at(benchPress(), TempoAdjustPolicy.UP_STROKE)
        assertEquals("X", TempoAdjustPolicy.steppedValue("3010", drive, -1))
        assertEquals("X", TempoAdjustPolicy.steppedValue("30X0", drive, -1))
        assertFalse(TempoAdjustPolicy.canStep("30X0", drive, -1), "nothing faster than X")
        assertEquals("1", TempoAdjustPolicy.steppedValue("30X0", drive, 1))
        assertEquals("9", TempoAdjustPolicy.steppedValue("3090", drive, 1))
        assertFalse(TempoAdjustPolicy.canStep("3090", drive, 1), "nine is the slowest, and X is no longer past it")
    }

    /**
     * A vertical concentric-DOWN lift's drive is digit 1 and cannot be stepped
     * to X, because the notation cannot hold one. #258, not this change.
     *
     * Green at its own parent and green here: it pins the BOUNDARY of what
     * landed rather than a behaviour that moved, and it is written down so that
     * the gap between the owner's ask and what shipped is a failing assertion
     * the day #258 lands rather than a sentence in a commit body.
     */
    @Test
    fun `a pulldown's drive cannot reach X because the notation cannot spell one`() {
        assertNull(Tempo.parseOrNull("X010"), "digit 1 takes no X")
        assertFalse(TempoAdjustPolicy.EXPLOSIVE in TempoAdjustPolicy.spellable(TempoAdjustPolicy.DOWN_STROKE))
        val drive = at(pulldown(), TempoAdjustPolicy.DOWN_STROKE)
        assertEquals("concentric", drive.caption)
        assertEquals("1", TempoAdjustPolicy.steppedValue("1010", drive, -1))
        assertFalse(TempoAdjustPolicy.canStep("1010", drive, -1))
    }

    /**
     * INVERTS `today the plan gate accepts a stroke of zero`.
     *
     * The path is named, as every other error this gate raises names one: a
     * plan is a document the lifter did not write, and "somewhere in here a
     * stroke is zero" is not something they can act on.
     */
    @Test
    fun `the plan gate refuses a stroke of zero and names the path`() {
        val path = "sessions[0].exercises[0].sets[0]"
        listOf("0010", "3000", "0-0-1-0", "3-0-0-0").forEach { text ->
            val errors = PlanSetDef(reps = 5, tempo = text).validate(path)
            assertEquals(1, errors.size, "'$text' should raise exactly one error, got $errors")
            assertTrue(errors.single().startsWith("$path.tempo"), "'$text' names no path: ${errors.single()}")
            assertTrue(errors.single().contains(text), "'$text' is not quoted back: ${errors.single()}")
        }
    }

    /** A pause of 0 is a real pause -- the one where the lifter does not stop -- and stays valid. */
    @Test
    fun `a zero pause is still a tempo the plan gate accepts`() {
        listOf("1010", "3010", "1-0-1-0", "30X0", "3-0-X-0").forEach { text ->
            assertEquals(
                emptyList(),
                PlanSetDef(reps = 5, tempo = text).validate("sessions[0].exercises[0].sets[0]"),
                "'$text' should pass the import gate",
            )
        }
    }

    /**
     * INVERTS `today the published plan schema accepts a stroke of zero`.
     *
     * The pattern and not only the prose, because the pattern is the half an
     * ajv run and a plan-writing model both act on. A schema that accepts what
     * the app then refuses is the disagreement plan schema 1.12 exists to
     * close.
     */
    @Test
    fun `the published plan schema refuses a stroke of zero`() {
        val pattern = Regex(tempoPattern())
        listOf("0010", "3000", "0-0-1-0", "3-0-0-0", "0.0-0-1-0", "3-0-0.0-0").forEach {
            assertFalse(pattern.matches(it), "the published pattern still accepts '$it'")
        }
        listOf("1010", "3010", "30X0", "30x0", "4-0-1-0", "4-0-X-0", "0.5-0-1-0", "10-0-1-0").forEach {
            assertTrue(pattern.matches(it), "the published pattern no longer accepts '$it'")
        }
    }

    /**
     * The floor is stated in the document a plan author reads, not only
     * enforced in the code that refuses them.
     *
     * A refusal with no published rule behind it is a plan rejected for a
     * reason its author cannot look up.
     */
    @Test
    fun `the published plan schema says a stroke may not be zero`() {
        val description = tempoNode()["description"]!!.jsonPrimitive.content
        assertTrue(description.contains("only the two pauses"), "the tempo description states no stroke floor")
        val versions = planSchema()["properties"]!!.jsonObject["schemaVersion"]!!.jsonObject
        assertTrue(
            versions["description"]!!.jsonPrimitive.content.contains("1.12: a stroke digit"),
            "plan schema 1.12 does not record the stroke floor among its changes",
        )
    }

    /** The one fact every alphabet pin above rests on. */
    @Test
    fun `the concentric digit follows the plane first and the drive direction second`() {
        assertEquals(TempoAdjustPolicy.UP_STROKE, TempoAdjustPolicy.concentricDigit(true, horizontal = false))
        assertEquals(TempoAdjustPolicy.DOWN_STROKE, TempoAdjustPolicy.concentricDigit(false, horizontal = false))
        assertEquals(TempoAdjustPolicy.UP_STROKE, TempoAdjustPolicy.concentricDigit(true, horizontal = true))
        assertEquals(
            TempoAdjustPolicy.UP_STROKE,
            TempoAdjustPolicy.concentricDigit(false, horizontal = true),
            "a horizontal machine has no down for a positional reading to attach to",
        )
    }

    private fun planSchema() = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/plan.schema.json")!!.readBytes().decodeToString(),
    ).jsonObject

    /** The tempo property as published, read from the real document rather than a copy. */
    private fun tempoNode() =
        planSchema()["\$defs"]!!.jsonObject["set"]!!.jsonObject["properties"]!!.jsonObject["tempo"]!!.jsonObject

    private fun tempoPattern(): String = tempoNode()["pattern"]!!.jsonPrimitive.content
}
