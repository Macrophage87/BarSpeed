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
 * Export 1.23, a further entry (#132): `repsManual` names the metronome's
 * count as the metronome's, never as a count the lifter entered.
 *
 * WHAT WAS FALSE. The key was described as true "when reps were entered or
 * corrected manually rather than sensor-counted". On every guided set it is
 * true, and nobody entered anything: the cadence guide wrote the count on its
 * own schedule (the runner's `onRepCounted` and the lifter's tap call the same
 * writer), and the in-set screen offers no rep control at all. A coaching
 * analysis that read the flag by that description labelled guided counts
 * "hand-entered".
 *
 * THE VALUE IS RIGHT; ONLY ITS DESCRIPTION WAS WRONG. On a set that is not
 * timed the flag is true exactly where `repsSource` reads `manual`,
 * `corrected` or `metronome`, and false where it reads `sensor` or `analysis`
 * -- `RepsSourcePolicy.published`'s own branches -- so it is described as
 * that, and pointed at the key that says which. The name is historical and is
 * corrected by its description, never by a rename.
 *
 * RED WHEN WRITTEN.
 */
class SchemaRepsManualProvenanceContractTest {
    private val schema: JsonObject = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/session-export.schema.json")!!.readBytes().decodeToString(),
    ).jsonObject

    private fun description(name: String): String = schema.getValue("\$defs").jsonObject.getValue("set")
        .jsonObject.getValue("properties").jsonObject.getValue(name)
        .jsonObject.getValue("description").jsonPrimitive.content

    private fun versionLog(): String = schema.getValue("properties").jsonObject.getValue("schemaVersion").jsonObject
        .getValue("description").jsonPrimitive.content

    private val marker = "1.23 FURTHER ENTRY (#132"

    /** The metronome's count is named as the metronome's, and nobody is said to have entered it. */
    @Test
    fun `repsManual names the cadence guide's count and says no one entered it`() {
        val d = description("repsManual")
        assertFalse(
            "entered or corrected manually rather than sensor-counted" in d,
            "repsManual still calls a guided count one the lifter entered",
        )
        assertTrue("the cadence guide's count" in d, "repsManual does not name the metronome's count: $d")
        assertTrue("no one entered" in d, "repsManual does not say the guide's count was entered by no one")
        assertTrue("never says a person counted" in d, "repsManual still reads as a person's count")
    }

    /** It points at the key that says whose count it is, and maps its two values onto that key's words. */
    @Test
    fun `repsManual points at repsSource for whose count it is`() {
        val d = description("repsManual")
        assertTrue("`repsSource`" in d, "repsManual does not point at repsSource")
        for (word in listOf("`manual`", "`corrected`", "`metronome`", "`sensor`", "`analysis`")) {
            assertTrue(word in d, "repsManual does not say where $word falls")
        }
        assertTrue("historical" in d, "repsManual does not say its name is historical")
    }

    /** The log files the change once, and says nothing but a description moved. */
    @Test
    fun `the 1_23 log files the repsManual correction once`() {
        val log = versionLog()
        assertEquals(1, log.split(marker).size - 1, "the #132 entry is not filed exactly once")
        val entry = log.substringAfter(marker).substringBefore("1.23 FURTHER ENTRY (")
        for (fact in listOf("NO KEY OR VALUE CHANGES", "DATABASE_VERSION does NOT move")) {
            assertTrue(fact in entry, "the #132 entry does not state: $fact")
        }
    }
}
