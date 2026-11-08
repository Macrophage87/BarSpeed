package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Export 1.28, minted here: from v0.2.3 a squat the sensor counts is counted
 * live by the lockout detector (`LockoutRepCounter`, `LiveCounterPolicy`'s
 * squat row) and every other lift by the full-cycle detector. No key moves;
 * the descriptions a reader weighs `sensor` counts by do, in the published
 * schema and in `PLAN_PROMPT`.
 *
 * Holds the tip literal, which moves at every mint, and the example's
 * version. WHERE THE FIGURES COME FROM, because `:core:model` cannot see
 * `:core:dsp`'s corpus: 5, 5, 4, 2, 3, 3 and 3 and 4, 6, 5, 1, 4, 3 and 4 are
 * `SquatLockoutFieldTest`'s.
 */
class SchemaSquatLockoutContractTest {
    private fun document(name: String): JsonObject = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/$name")!!.readBytes().decodeToString(),
    ).jsonObject

    private val schema = document("session-export.schema.json")

    private fun properties() = schema.getValue("properties").jsonObject

    private fun setKey(key: String) = schema.getValue("\$defs").jsonObject.getValue("set").jsonObject
        .getValue("properties").jsonObject.getValue(key).jsonObject
        .getValue("description").jsonPrimitive.content

    private val prompt: String =
        checkNotNull(
            javaClass.getResourceAsStream("/kotlin/com/macrophage/barspeed/ui/screens/GuideScreen.kt"),
        ) { "GuideScreen.kt is not on the test classpath - see the include filter in core/model/build.gradle.kts" }
            .readBytes().decodeToString()

    @Test
    fun `the exporter writes 1_28, which the schema and the example carry, and 1_27 is still readable`() {
        val enum = properties().getValue("schemaVersion").jsonObject.getValue("enum").jsonArray
            .map { it.jsonPrimitive.content }
        assertEquals("1.28", SessionExport.SCHEMA_VERSION, "the version the exporter writes")
        assertTrue("1.28" in enum, "the published schema rejects the version the exporter writes")
        assertTrue("1.27" in SessionExport.SUPPORTED_SCHEMA_VERSIONS, "1.27, shipped in v0.2.2, left the accepted set")
        assertEquals(SessionExport.SUPPORTED_SCHEMA_VERSIONS, enum.toSet(), "the accepted versions drifted")
        assertEquals(
            "1.28",
            document("examples/session-export.example.json").getValue("schemaVersion").jsonPrimitive.content,
            "the example declares a version other than the tip",
        )
    }

    @Test
    fun `the version log files 1_28 once and states what moved and what did not`() {
        val log = properties().getValue("schemaVersion").jsonObject.getValue("description").jsonPrimitive.content
        val marker = "1.28 (squat lockout count)"
        assertEquals(1, Regex(Regex.escape(marker)).findAll(log).count(), "the 1.28 entry is not filed once")
        val entry = log.substringAfter(marker)
        listOf(
            "MINTS NO KEY",
            "LOCKOUT detector",
            "never at the floor",
            "the unrack is never a rep",
            "can be called",
            "5, 5, 4, 2, 3, 3 and 3",
            "4, 6, 5, 1, 4, 3 and 4",
            "A MINT AND NOT A FURTHER 1.27 ENTRY",
            "NOT RETROACTIVE",
            "DATABASE_VERSION does NOT move",
        ).forEach { assertTrue(it in entry, "the 1.28 entry does not state: $it") }
    }

    /** repsSource and the prompt's copy both say a squat is counted at lockout, and since when. */
    @Test
    fun `both copies of the reading key say a squat is counted at lockout from v0_2_3`() {
        mapOf("the published schema" to setKey("repsSource"), "the plan prompt" to prompt).forEach { (name, text) ->
            listOf(
                "v0.2.3",
                "lockout detector",
                "at the top and never at the floor",
                "the unrack is never a rep",
                "onto the safeties or a sit that stays on a box",
                "v0.1.55 to v0.2.2",
                "explosive",
                "keeps the full-cycle detector",
                "at the bottom where it stopped",
                "5, 5, 4, 2, 3, 3 and 3",
            ).forEach { assertTrue(it.lowercase() in text.lowercase(), "$name does not state: $it") }
        }
    }

    /** liveReps and countTrusted name the lockout detector beside the full-cycle one. */
    @Test
    fun `liveReps and countTrusted name the squat's detector`() {
        assertTrue("LOCKOUT detector" in setKey("liveReps"), "liveReps does not name the lockout detector")
        assertTrue("on a squat a lockout detector from v0.2.3" in setKey("countTrusted"), "countTrusted")
        assertTrue("on my squats a lockout detector from v0.2.3" in prompt, "the prompt's countTrusted line")
    }
}
