package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Export 1.25, minted here (#335): the full-cycle detector's rule that an
 * attempt the bar FALLS from within 1.2 s of its drive is not counted applies
 * to a concentric-first lift only. No key moves; the reading key of
 * `repsSource` does, in the published schema and in `PLAN_PROMPT`.
 *
 * The contract lands in the same commit as the red, as a deliberate contract
 * change must, so these assertions pass at the commit that writes them. The
 * behaviour they describe is red in `:core:dsp` -- `SquatLiveCountFieldTest`,
 * `FallRejectionCorpusTest`, `CycleFallRejectionTest` -- until the fix.
 *
 * THE TIP LITERAL LIVES HERE, on the rule `SchemaSkippedSetContractTest` wrote
 * when it minted 1.21. `SchemaSessionHrvContractTest`, which held it for 1.24,
 * now asserts only that 1.24 is still accepted.
 *
 * WHERE THE FIGURES COME FROM, because `:core:model` cannot see `:core:dsp`'s
 * corpus: 6, 4, 5, 5 and 5 and 5, 1, 2, 1 and 5 are `SquatLiveCountFieldTest`'s.
 * Nothing mechanical compares the two modules; what this file enforces is
 * that the schema and the prompt the coach receives say the same thing.
 */
class SchemaFallRejectionContractTest {
    private fun document(name: String): JsonObject = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/$name")!!.readBytes().decodeToString(),
    ).jsonObject

    private val schema = document("session-export.schema.json")

    private fun properties() = schema.getValue("properties").jsonObject

    private fun versionLog() =
        properties().getValue("schemaVersion").jsonObject.getValue("description").jsonPrimitive.content

    private fun readingKey() = schema.getValue("\$defs").jsonObject.getValue("set").jsonObject
        .getValue("properties").jsonObject.getValue("repsSource").jsonObject
        .getValue("description").jsonPrimitive.content

    private val prompt: String =
        checkNotNull(
            javaClass.getResourceAsStream("/kotlin/com/macrophage/barspeed/ui/screens/GuideScreen.kt"),
        ) { "GuideScreen.kt is not on the test classpath - see the include filter in core/model/build.gradle.kts" }
            .readBytes().decodeToString()

    @Test
    fun `the exporter writes 1_25, which the schema accepts, and 1_24 is still readable`() {
        val enum = properties().getValue("schemaVersion").jsonObject.getValue("enum").jsonArray
            .map { it.jsonPrimitive.content }
        assertEquals("1.25", SessionExport.SCHEMA_VERSION, "the version the exporter writes")
        assertTrue("1.25" in SessionExport.SUPPORTED_SCHEMA_VERSIONS, "the version written is not accepted")
        assertTrue("1.25" in enum, "the published schema rejects the version the exporter writes")
        assertTrue("1.24" in SessionExport.SUPPORTED_SCHEMA_VERSIONS, "1.24, shipped in v0.1.57, left the accepted set")
    }

    @Test
    fun `the version log files 1_25 once and states what moved and what did not`() {
        val log = versionLog()
        assertEquals(1, Regex("""1\.25 \(#335""").findAll(log).count(), "the 1.25 entry is not filed exactly once")
        val entry = log.substringAfter("1.25 (#335")
        listOf(
            "MINTS NO KEY",
            "STARTS WITH ITS CONCENTRIC",
            "A floor contact within 1.2 s of the drive still rejects the attempt on every lift",
            "RETRACTS",
            "v0.1.55 to v0.1.57",
            "6, 4, 5, 5 and 5",
            "5, 1, 2, 1 and 5",
            "5, 5, 5, 5, 5, 5, 4 and 2",
            "A MINT and not a further 1.24 entry",
            "NOT RETROACTIVE",
            "REJECTS a 1.25 document",
            "DATABASE_VERSION does NOT move",
        ).forEach { assertTrue(it in entry, "the 1.25 entry does not state: $it") }
    }

    /**
     * Both copies of the reading key say the fall rule is a concentric-first
     * lift's, that a floor contact still rejects on any lift, what the squats
     * read, and that a v0.1.55-v0.1.57 set had the rule on every lift.
     */
    @Test
    fun `both copies of the reading key hold the fall rule to concentric-first lifts`() {
        mapOf("the published schema" to readingKey(), "the plan prompt" to prompt).forEach { (name, text) ->
            listOf(
                "within 1.2 s of its drive is not counted, on any lift",
                "starts with its concentric",
                "starts with its eccentric",
                "1.25 (#335)",
                "v0.1.55 to v0.1.57",
                "at least 4 on each",
                "read 6, 4, 5, 5 and 5",
                "5, 1, 2, 1 and 5 live",
                "a low count may be a miss",
            ).forEach { assertTrue(it.lowercase() in text.lowercase(), "$name does not state: $it") }
        }
    }

    /** The sentence 1.25 retracts is gone from both copies. */
    @Test
    fun `neither copy of the reading key still applies the fall rule to every lift`() {
        listOf(
            "the published schema" to "back on the floor from, or falls from, within 1.2 s",
            "the plan prompt" to "back on the floor, or falls, within 1.2 s",
        ).forEach { (name, retracted) ->
            val text = if (name == "the plan prompt") prompt else readingKey()
            assertFalse(retracted in text, "$name still states: $retracted")
        }
    }

    @Test
    fun `the published example declares 1_25`() {
        val example = document("examples/session-export.example.json")
        assertEquals("1.25", example.getValue("schemaVersion").jsonPrimitive.content)
    }
}
