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
 * A vertical concentric-DOWN lift's drive is digit 1, and since #258 (plan
 * schema 1.15) it can be stepped to X: [Tempo] holds a null `downS` and
 * [Tempo.parse] reads an X there. The role rule needed no change for it --
 * the concentric digit is offered X exactly where
 * [TempoAdjustPolicy.spellable] says X can be spelled, and #258 moved that one
 * statement. The eccentric is still never offered X on any lift shape.
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
    fun `X is offered on the concentric stroke where the notation can spell one, and on no other digit`() {
        listOf(benchPress(), seatedRow()).forEach { digits ->
            assertEquals(listOf("X") + oneToNine, at(digits, TempoAdjustPolicy.UP_STROKE).choices)
            assertEquals(oneToNine, at(digits, TempoAdjustPolicy.DOWN_STROKE).choices)
            assertEquals(zeroToNine, at(digits, TempoAdjustPolicy.BOTTOM_PAUSE).choices)
            assertEquals(zeroToNine, at(digits, TempoAdjustPolicy.TOP_PAUSE).choices)
        }
        val pulldown = pulldown()
        assertEquals(oneToNine, at(pulldown, TempoAdjustPolicy.UP_STROKE).choices, "the return is never explosive")
        assertEquals(listOf("X") + oneToNine, at(pulldown, TempoAdjustPolicy.DOWN_STROKE).choices, "the drive")
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
        // A tap of more than one place, which the screen cannot produce -- its
        // STEPPER_STEP is 1 -- and which the public function must still answer
        // for. X is BELOW one second, so it sits one place below the head of a
        // range that starts at 1, and a two-place tap up from it lands on 2.
        // This is the pin the mutation table asked for: without it, an
        // explicit "any tap off the alphabet lands on the first value" branch
        // is unkillable, which makes it decoration in a function whose whole
        // job is deciding which value a tap produces.
        assertEquals("2", TempoAdjustPolicy.steppedValue("30X0", returnStroke, 2))
        assertEquals("1", TempoAdjustPolicy.steppedValue("30X0", returnStroke, -2))
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
     * INVERTS `a pulldown's drive cannot reach X because the notation cannot
     * spell one` (#258).
     *
     * That pin was written to fail the day the notation widened, and this is
     * it failing on purpose: digit 1 now spells X, so a pulldown's drive steps
     * below one into X and stops there, exactly as a bench press's does on
     * digit 3, and the written tempo says X in the position the lift drives.
     */
    @Test
    fun `a pulldown's drive steps below one into X and stops there`() {
        assertEquals(Tempo(null, 0.0, 1.0, 0.0), Tempo.parseOrNull("X010"), "digit 1 takes an X")
        assertTrue(TempoAdjustPolicy.EXPLOSIVE in TempoAdjustPolicy.spellable(TempoAdjustPolicy.DOWN_STROKE))
        val drive = at(pulldown(), TempoAdjustPolicy.DOWN_STROKE)
        assertEquals("concentric", drive.caption)
        assertEquals("X", TempoAdjustPolicy.steppedValue("1010", drive, -1))
        assertTrue(TempoAdjustPolicy.canStep("1010", drive, -1))
        assertEquals("X010", TempoAdjustPolicy.withDigit("1010", drive, "X"))
        assertFalse(TempoAdjustPolicy.canStep("X010", drive, -1), "nothing faster than X")
        assertEquals("1", TempoAdjustPolicy.steppedValue("X010", drive, 1))
        assertEquals(listOf("X", "0", "1", "0"), TempoAdjustPolicy.wheelValues("X010"))
    }

    /**
     * The other half of the same rule: on a lift whose digit 1 is the
     * ECCENTRIC -- a bench press, a seated row -- digit 1 still never offers X,
     * though the notation can now spell one there. A plan may declare `X0X0`
     * on a bench press; the control draws it and steps out of it, as it does a
     * plan-authored explosive return on a pulldown.
     */
    @Test
    fun `a bench press's lowering is never offered X, and a plan-authored one steps out`() {
        val lowering = at(benchPress(), TempoAdjustPolicy.DOWN_STROKE)
        assertEquals("eccentric", lowering.caption)
        assertNull(TempoAdjustPolicy.withDigit("3010", lowering, "X"))
        assertEquals("1", TempoAdjustPolicy.steppedValue("X0X0", lowering, -1))
        assertEquals("1", TempoAdjustPolicy.steppedValue("X0X0", lowering, 1))
        assertEquals("10X0", TempoAdjustPolicy.withDigit("X0X0", lowering, "1"))
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

    /**
     * CHARACTERIZATION, not a differential. The THIRD door
     * [TempoAdjustPolicy.MIN_STROKE_S]'s KDoc names rather than claims shut.
     *
     * The gate's test is [Tempo.hasZeroStroke], an equality against 0.0 and
     * not a floor, so a plan may still author a half-second stroke and be
     * accepted -- and the published pattern admits it too, which
     * `the published plan schema refuses a stroke of zero` pins from the
     * other side by listing `0.5-0-1-0` among the strings it must still take.
     * `CadencePlan.strokeSeconds` then truncates that 0.5 to a one-second
     * beat while `SetAnalyzer.complianceFor` grades the 0.5, which is exactly
     * the mismatch this branch closed for a 0 and did not close here.
     * `CadencePlanTest` pins the playing half, in the module that plays it.
     *
     * Pinned green rather than fixed: closing it moves the published pattern
     * and the gate together and is its own piece of work. What it buys is
     * that the day the floor becomes a floor, this assertion inverts instead
     * of a sentence in a KDoc going quietly stale.
     */
    @Test
    fun `today the plan gate accepts a fractional stroke below one second`() {
        val path = "sessions[0].exercises[0].sets[0]"
        listOf("0.5-0-1-0", "1-0-0.5-0").forEach { text ->
            assertFalse(Tempo.parse(text).hasZeroStroke, "'$text' is not a zero stroke; the gate's test misses it")
            assertEquals(emptyList(), PlanSetDef(reps = 5, tempo = text).validate(path), "'$text' at the gate")
            assertNull(TempoAdjustPolicy.wheelValues(text), "'$text' must draw no wheel")
        }
    }

    /** A pause of 0 is a real pause -- the one where the lifter does not stop -- and stays valid. */
    @Test
    fun `a zero pause is still a tempo the plan gate accepts`() {
        listOf("1010", "3010", "1-0-1-0", "30X0", "3-0-X-0", "X010", "X-0-X-0").forEach { text ->
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
     * INVERTS the published pattern's `X in digit 3 only` (#258, plan schema
     * 1.15): digit 1 takes an X in both the compact and the dash form, the two
     * pauses still take none, and the schema says so among its versions.
     *
     * The pattern and [Tempo.parse] move in one change because the import gate
     * is the parser: a pattern that refused `X010` while the app took it, or
     * took it while the app refused it, is a plan that validates in an ajv run
     * and is then treated differently on the phone.
     */
    @Test
    fun `the published plan schema takes an X on either stroke and on no pause`() {
        val pattern = Regex(tempoPattern())
        listOf("X010", "x010", "X0X0", "X-0-1-0", "x-2-X-1").forEach {
            assertTrue(pattern.matches(it), "the published pattern refuses '$it'")
            assertTrue(Tempo.parseOrNull(it) != null, "the app refuses '$it'")
        }
        listOf("3X10", "301X", "3-X-1-0", "3-0-1-X", "XX10").forEach {
            assertFalse(pattern.matches(it), "the published pattern accepts '$it'")
            assertNull(Tempo.parseOrNull(it), "the app accepts '$it'")
        }
        val versions = planSchema()["properties"]!!.jsonObject["schemaVersion"]!!.jsonObject
        assertTrue(
            versions["description"]!!.jsonPrimitive.content.contains("1.15: "),
            "plan schema 1.15 does not record the widened digit 1 among its changes",
        )
        assertTrue(tempoNode()["description"]!!.jsonPrimitive.content.contains("digit 1 or digit 3"))
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
