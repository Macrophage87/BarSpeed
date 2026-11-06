package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Export 1.27's further entry for the off-plan set (UX review 2026-11-06,
 * record-setup F2): "Different exercise..." inside a plan session records a
 * set published with `added` true, and the published schema says so.
 *
 * Pins the contract text only: the version-log entry is filed once, under
 * 1.27, with no key change, not retroactive and no database move; and
 * `added`'s description covers where such a set lands in `exercises[]`, its
 * null `rest_s`, where `plannedPrep_s` comes from and what its values were
 * filled from. Where the set lands in the queue is `OffPlanSetTest`'s, in
 * `:app`.
 */
class SchemaOffPlanSetContractTest {
    private val schema: JsonObject = Json.parseToJsonElement(
        checkNotNull(javaClass.getResourceAsStream("/session-export.schema.json")) {
            "session-export.schema.json is not on the test classpath"
        }.readBytes().decodeToString(),
    ).jsonObject

    private fun versionLog(): String = schema.getValue("properties").jsonObject.getValue("schemaVersion").jsonObject
        .getValue("description").jsonPrimitive.content

    private fun added(): String = schema.getValue("\$defs").jsonObject.getValue("set").jsonObject
        .getValue("properties").jsonObject.getValue("added").jsonObject
        .getValue("description").jsonPrimitive.content

    @Test
    fun `the version log files the off-plan set once under 1_27 as no key change`() {
        val marker = "FURTHER 1.27 ENTRY (off-plan set)"
        assertEquals(1, Regex(Regex.escape(marker)).findAll(versionLog()).count(), "the entry is not filed once")
        val entry = versionLog().substringAfter(marker)
        assertTrue(entry.startsWith(" NO KEY CHANGES"), entry.take(80))
        assertTrue("NOT RETROACTIVE" in entry, "the entry does not say it is not retroactive")
        assertTrue("DATABASE_VERSION does NOT move" in entry, "the entry does not say the database stays")
    }

    @Test
    fun `added says how an off-plan set is published`() {
        val text = added()
        assertTrue("AN OFF-PLAN SET is also added (1.27)" in text, "added does not name the off-plan set")
        assertTrue("forms a group of its own holding only added sets" in text, "its own exercises[] group")
        assertTrue("BEFORE that lift's prescribed sets" in text, "merging into a lift's group at its orderIdx")
        assertTrue("Its rest_s is null" in text, "its rest_s")
        assertTrue("first declaration of that lift's prep" in text, "where its plannedPrep_s comes from")
        assertTrue("last recorded set of that lift" in text, "what its values were filled from")
    }
}
