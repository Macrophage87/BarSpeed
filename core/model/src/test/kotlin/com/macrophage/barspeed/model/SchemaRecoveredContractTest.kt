package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Export 1.26, a set's `recovered` (#100): true on a set written back into
 * History from an interrupted set's capture rather than by the set ending.
 *
 * Pins the contract: the log entry, the declaration in both published
 * schemas -- session.json's set and the raw archive's `meta.json` set, two
 * writers of one fact -- and that neither makes it required, since it is
 * omitted when false. The set's key list is pinned to [SetExport] in
 * `SchemaExportKeySetContractTest`; what the exporters write is
 * `SessionExportRecoveredTest`'s, in `:core:data`.
 */
class SchemaRecoveredContractTest {
    private fun document(name: String): JsonObject = Json.parseToJsonElement(
        checkNotNull(javaClass.getResourceAsStream("/$name")) { "$name is not on the test classpath" }
            .readBytes().decodeToString(),
    ).jsonObject

    private val schema = document("session-export.schema.json")

    private val raw = document("raw-meta.schema.json")

    private fun setDef(document: JsonObject): JsonObject =
        document.getValue("\$defs").jsonObject.getValue("set").jsonObject

    private fun declaration(document: JsonObject): JsonObject =
        setDef(document).getValue("properties").jsonObject.getValue(KEY).jsonObject

    @Test
    fun `the version log files the key under 1_26 once, as an added key`() {
        val log = schema.getValue("properties").jsonObject.getValue("schemaVersion").jsonObject
            .getValue("description").jsonPrimitive.content
        assertEquals(1, Regex("""1\.26 \(#100, recovered set\)""").findAll(log).count())
        val entry = log.substringAfter("1.26 (#100, recovered set)")
        assertTrue(entry.startsWith(": ADDS ONE KEY."), entry.take(80))
        assertTrue("OMITTED WHEN FALSE" in entry, "the entry does not say the key is omitted when false")
        assertTrue("1.26" in SessionExport.SUPPORTED_SCHEMA_VERSIONS, "the entry files the key under no version")
    }

    /**
     * session.json declares a boolean; meta.json declares `const: true`, the
     * shape its other omitted-when-false flags (`added`, `voided`) take.
     * Neither requires it, and both say what a recovered set lacks.
     */
    @Test
    fun `session json and meta json both declare it as an optional flag`() {
        assertEquals("boolean", declaration(schema).getValue("type").jsonPrimitive.content)
        assertEquals("true", declaration(raw).getValue("const").jsonPrimitive.content)
        for ((name, document) in listOf("session-export" to schema, "raw-meta" to raw)) {
            val required = setDef(document)["required"]?.toString().orEmpty()
            assertFalse("\"$KEY\"" in required, "$name requires a key that is omitted when false")
            val description = declaration(document).getValue("description").jsonPrimitive.content
            assertTrue("database v21" in description, "$name does not say why older sets lack it")
            assertTrue("rating" in description, "$name does not say what a recovered set lacks")
        }
    }

    private fun logEntry(): String = schema.getValue("properties").jsonObject.getValue("schemaVersion").jsonObject
        .getValue("description").jsonPrimitive.content
        .substringAfter("1.26 (#100, recovered set)")
        .substringBefore("Under 1.26 and not a mint")

    private val guide: String =
        checkNotNull(
            javaClass.getResourceAsStream("/kotlin/com/macrophage/barspeed/ui/screens/GuideScreen.kt"),
        ) { "GuideScreen.kt is not on the test classpath - see the include filter in core/model/build.gradle.kts" }
            .readBytes().decodeToString()

    /**
     * Whose count a recovered set's reps are (#100 review round 1, Lens A B1
     * and Lens B B2). The declarations said "the segmenter's" on every
     * recovered set, which is false wherever the lifter or the guide counted
     * it: those reps are the journaled tally, stated.
     */
    @Test
    fun `both declarations and the log say whose count a recovered set's reps are`() {
        val texts =
            listOf(
                "session-export" to declaration(schema).getValue("description").jsonPrimitive.content,
                "raw-meta" to declaration(raw).getValue("description").jsonPrimitive.content,
                "the 1.26 log entry" to logEntry(),
            )
        for ((name, text) in texts) {
            assertTrue("the lifter's or the guide's journaled count" in text, "$name does not name the tally")
            assertTrue("segmenter's" in text, "$name does not name the sensor-counted case")
        }
    }

    /**
     * A recovered set carries no silence word, and both declarations say so
     * (#100 review round 1, Lens A B2): the published `sensors` contract
     * reads an absent block as one sensor whose stream the analysis could
     * run on, which a recovered marks-only set is not.
     */
    @Test
    fun `both declarations say a recovered set carries no silence word`() {
        for ((name, document) in listOf("session-export" to schema, "raw-meta" to raw)) {
            val description = declaration(document).getValue("description").jsonPrimitive.content
            assertTrue("no silence word" in description, "$name does not say a recovered set has no silence word")
            assertTrue("does not mean the stream was analysable" in description, "$name leaves the absence unread")
        }
    }

    /**
     * The reader the app hands its export is told what a recovered set is
     * (#100 review round 1, Lens B B3), and the two sentences a recovered
     * set would make false are qualified: an absent `workingReps` and an
     * absent `sensors` block.
     */
    @Test
    fun `the analysis prompt explains a recovered set`() {
        assertTrue("- \"recovered\": true" in guide, "the prompt has no recovered bullet")
        assertEquals(2, Regex("""except on a set carrying "recovered": true""").findAll(guide).count())
    }

    private companion object {
        const val KEY = "recovered"
    }
}
