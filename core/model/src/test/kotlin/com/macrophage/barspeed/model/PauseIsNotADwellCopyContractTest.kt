package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A published pause is the segmenter's gap between a rep's two detected
 * phases, not a measured dwell (#92), and every text a coaching reader is
 * pointed at says so.
 *
 * The owner's #92 comment names the harm: "a coaching consumer reading it as
 * 'how long the lifter held the bottom position' is reading something else".
 * `bottomPause_s`'s own schema description was corrected first, while
 * `PLAN_PROMPT` -- the copy the COPY PLAN PROMPT button hands that consumer --
 * and the schema's `scoredPhases` description still called the pauses
 * "measured", so the one document contradicted itself. These tests hold the
 * three texts together.
 *
 * Reads the real `GuideScreen.kt` and the real schema through the test
 * resources, as [PlanPromptWorkingTargetContractTest] does: a copy would drift.
 */
class PauseIsNotADwellCopyContractTest {
    private val prompt: String =
        checkNotNull(
            javaClass.getResourceAsStream("/kotlin/com/macrophage/barspeed/ui/screens/GuideScreen.kt"),
        ) {
            "GuideScreen.kt is not on the test classpath - see the include filter in core/model/build.gradle.kts"
        }.readBytes().decodeToString()

    private val schema: JsonObject = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/session-export.schema.json")!!.readBytes().decodeToString(),
    ).jsonObject

    private fun setProperty(vararg path: String): String {
        var node = schema.getValue("\$defs").jsonObject.getValue("set").jsonObject
        path.forEach { node = node.getValue("properties").jsonObject.getValue(it).jsonObject }
        return node.getValue("description").jsonPrimitive.content
    }

    private fun repMetricDescription(key: String): String =
        schema.getValue("\$defs").jsonObject.getValue("repMetrics").jsonObject
            .getValue("properties").jsonObject.getValue(key).jsonObject
            .getValue("description").jsonPrimitive.content

    @Test
    fun `the plan prompt calls a pause the segmenter's gap and refuses the held-position reading`() {
        assertFalse("Pauses are measured" in prompt, "the plan prompt still calls the pauses measured")
        listOf(
            "A reported pause is the segmenter's gap between the rep's two detected phases, " +
                "not a measured dwell (#92)",
            "do not read either as how long the lifter held the position",
            "under about 0.15 s, does not show the turnaround was dwell-free",
        ).forEach { assertTrue(it in prompt, "the plan prompt does not state: $it") }
    }

    @Test
    fun `the schema's scoredPhases text agrees with bottomPause_s`() {
        val scored = setProperty("tempoCompliance", "scoredPhases")
        assertFalse("Pauses are measured" in scored, "scoredPhases still calls the pauses measured")
        assertTrue("not a measured dwell (#92)" in scored, "scoredPhases does not say a pause is not a measured dwell")
        assertTrue("NOT A MEASURED DWELL" in repMetricDescription("bottomPause_s"))
    }
}
