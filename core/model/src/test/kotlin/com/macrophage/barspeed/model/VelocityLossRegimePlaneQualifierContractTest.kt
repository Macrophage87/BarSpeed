package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The LAST sentence of every published statement of the regime rule (#250).
 *
 * DIFFERENTIAL, and a second pass over ground the first pass covered in part.
 * Round 1 finding 2 said the rule reads the PLANE before the drive direction,
 * and the fix rewrote the middle of each description and left its closing
 * sentence standing. Three of those closing sentences still said, in the
 * document a coach reads, that a concentric-down lift carrying a tempo is
 * always `controlled` -- with no plane on it, which is false for horizontal
 * work, whose concentric IS digit 3 and whose `30X0` is `maxIntent`. Every
 * method here is RED at the commit that introduces it, measured by running the
 * suite rather than asserted.
 *
 * Each method pins BOTH directions: the qualified sentence is present AND the
 * unqualified one is absent. The absence half is what makes a revert red rather
 * than merely un-improved -- the qualified string does not contain the
 * unqualified one, so restoring the old sentence fails the assertion that the
 * old sentence is gone.
 *
 * WHAT THIS PIN DOES NOT COVER, stated because a green pin reads as
 * coverage. It asserts the PLAN_PROMPT source string; it says nothing
 * about how that string DRAWS. PLAN_PROMPT is rendered on the Guide screen
 * as well as copied by the COPY PLAN PROMPT button, and nothing in this
 * repository can run a Compose screen. The corrected passage was read off a
 * device once, on the headless AVD barspeed-api35, and that was a bench run
 * rather than a check CI repeats -- see the commit that added this
 * paragraph. What a coach's model then DOES with the copy stays [Field].
 *
 * The Kotlin twin is pinned by [VelocityLossRegimeTest] and
 * `VelocityLossRegimeTempoScheduleContractTest` for its BEHAVIOUR; what cannot
 * be reached from a test is a KDoc, so `SessionExport`'s and
 * `VelocityLossRegime`'s prose are corrected by review rather than by a pin,
 * and this file says so instead of implying otherwise.
 */
class VelocityLossRegimePlaneQualifierContractTest {
    private fun schema() = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/session-export.schema.json")!!.readBytes().decodeToString(),
    ).jsonObject

    private fun regimeDescription() =
        schema()["\$defs"]!!.jsonObject["set"]!!.jsonObject["properties"]!!.jsonObject["velocityLossRegime"]!!
            .jsonObject["description"]!!.jsonPrimitive.content

    private fun versionLog() =
        schema()["properties"]!!.jsonObject["schemaVersion"]!!.jsonObject["description"]!!.jsonPrimitive.content

    private val prompt: String =
        checkNotNull(
            javaClass.getResourceAsStream("/kotlin/com/macrophage/barspeed/ui/screens/GuideScreen.kt"),
        ) { "GuideScreen.kt is not on the test classpath - see the include filter in core/model/build.gradle.kts" }
            .readBytes().decodeToString()

    private fun velocityLossBullet() =
        prompt.lineSequence().first { it.trimStart().startsWith("- \"velocityLoss_pct\"") }

    @Test
    fun `the published regime key qualifies its concentric-down sentence to vertical work`() {
        val text = regimeDescription()
        assertFalse(
            "A concentric-DOWN lift carrying a tempo is always" in text,
            "the regime key still states the concentric-down rule with no plane on it: $text",
        )
        assertTrue(
            "A VERTICAL concentric-DOWN lift carrying a tempo is always `controlled`" in text,
            "the regime key does not say WHICH plane the concentric-down rule holds for: $text",
        )
    }

    @Test
    fun `the published regime key names the horizontal case the concentric-down sentence excludes`() {
        val text = regimeDescription()
        assertTrue(
            "HORIZONTAL work is not in that position" in text && "its concentric IS digit 3" in text,
            "the regime key does not say why horizontal work escapes the concentric-down rule: $text",
        )
        assertTrue(
            "`maxIntent` under today's contract however `geometry.concentric` was declared" in text,
            "the regime key does not say a horizontal 30X0 is maxIntent whatever concentric says: $text",
        )
    }

    @Test
    fun `the twelfth log entry qualifies its concentric-down sentence to vertical work`() {
        val log = versionLog()
        assertFalse(
            "A concentric-down lift carrying a tempo cannot be" in log,
            "the 1.19 log still states the concentric-down rule with no plane on it",
        )
        assertTrue(
            "A VERTICAL concentric-down lift carrying a tempo cannot be `maxIntent` today" in log,
            "the 1.19 log does not say WHICH plane the concentric-down rule holds for",
        )
        assertTrue(
            "HORIZONTAL work is not in that position" in log && "its concentric IS digit 3" in log,
            "the 1.19 log does not name the horizontal case the rule excludes",
        )
    }

    /**
     * [VelocityLossRegime.of] takes four inputs and its own KDoc counts four.
     * The log's derivation clause counted three, which drops the PLANE -- the
     * input round 1 added and the one this whole correction is about.
     */
    @Test
    fun `the twelfth log entry counts the four inputs the decision actually takes`() {
        val log = versionLog()
        assertFalse("all three inputs" in log, "the 1.19 log still enumerates three inputs, omitting the plane")
        assertTrue(
            "all four inputs are already frozen on the set's row" in log,
            "the 1.19 log does not say the four stored inputs are what make the word derivable",
        )
    }

    @Test
    fun `the plan prompt reading key puts the plane before the drive direction`() {
        val line = velocityLossBullet()
        assertFalse(
            "The concentric digit is positional" in line,
            "PLAN_PROMPT still tells a coach the concentric digit is positional, full stop: $line",
        )
        assertTrue(
            "HORIZONTAL" in line && "VERTICAL" in line,
            "PLAN_PROMPT's reading key never distinguishes the two planes: $line",
        )
        assertTrue(
            "always digit 3" in line,
            "PLAN_PROMPT's reading key does not say digit 3 is the concentric on horizontal work: $line",
        )
    }

    @Test
    fun `the plan prompt reading key qualifies its concentric-down sentence to vertical work`() {
        val line = velocityLossBullet()
        assertFalse(
            "A concentric-down lift carrying a tempo is always \"controlled\" for now" in line,
            "PLAN_PROMPT still states the concentric-down rule with no plane on it: $line",
        )
        assertTrue(
            "A VERTICAL concentric-down lift carrying a tempo is always \"controlled\" for now" in line,
            "PLAN_PROMPT does not say WHICH plane the concentric-down rule holds for: $line",
        )
        assertTrue(
            "a HORIZONTAL one can be \"maxIntent\"" in line,
            "PLAN_PROMPT does not tell a coach a horizontal tempo set can still be maxIntent: $line",
        )
    }
}
