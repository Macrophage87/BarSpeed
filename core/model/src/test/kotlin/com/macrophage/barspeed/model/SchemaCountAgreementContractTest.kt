package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Export 1.23, a further entry (#246): `repMetricsComplete` is described as
 * what it computes -- two COUNTS matching -- and not as a statement that the
 * per-rep array is the lifter's reps.
 *
 * THE SHAPE. #246 measured two field-38 sets (triceps pushdown, 1120, v0.1.50)
 * that published `true` at 12 of 12 and 14 of 14 while, against the set's own
 * cadence grid, each carried one detection before the work began, three
 * cycles with no detection and two extra detections inside a cycle: the
 * errors cancelled to equal counts. Those figures are the issue's, relayed
 * and not re-measured here -- neither set is a committed capture.
 *
 * WHY THE VALUE IS KEPT AND ONLY THE DESCRIPTION NARROWED. A stronger test
 * would match each detection to a counted rep IN TIME, and none is computed:
 * the stored per-rep rows carry durations and an ordinal and no clock, and
 * the live count is stored as one integer, so the match would need the
 * segmenter re-run over the stored raw stream, which the export does not do.
 * #246 built its alignment from that stream and the marks, so a stronger
 * test is possible and is not implemented; as published, `true` means the
 * counts agree, and the description now says so. The name is historical and
 * is corrected by its description, never by a rename.
 *
 * RED WHEN WRITTEN: the description says what false means and never says what
 * true does not.
 */
class SchemaCountAgreementContractTest {
    private fun document(name: String): JsonObject = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/$name")!!.readBytes().decodeToString(),
    ).jsonObject

    private val schema = document("session-export.schema.json")

    private val prompt: String =
        checkNotNull(
            javaClass.getResourceAsStream("/kotlin/com/macrophage/barspeed/ui/screens/GuideScreen.kt"),
        ) {
            "GuideScreen.kt is not on the test classpath - see the include filter in core/model/build.gradle.kts"
        }.readBytes().decodeToString()

    private fun description(name: String): String = schema.getValue("\$defs").jsonObject.getValue("set")
        .jsonObject.getValue("properties").jsonObject.getValue(name)
        .jsonObject.getValue("description").jsonPrimitive.content

    private fun versionLog(): String = schema.getValue("properties").jsonObject.getValue("schemaVersion").jsonObject
        .getValue("description").jsonPrimitive.content

    private val marker = "1.23 FURTHER ENTRY (#246"

    /** True is the counts matching, and the description says what that does not certify. */
    @Test
    fun `repMetricsComplete says true is only the two counts matching`() {
        val d = description("repMetricsComplete")
        assertTrue("TRUE IS THE TWO COUNTS MATCHING AND NOTHING MORE" in d, "no statement of what true is: $d")
        assertTrue("misses reps and finds as many" in d, "the cancelling-errors shape is not stated")
        assertTrue("NOT evidence that the per-rep array" in d, "true still reads as the lifter's reps")
        assertTrue("historical" in d, "repMetricsComplete does not say its name is historical")
    }

    /** It says why nothing stronger is published: no stored figure can match a detection to a rep in time. */
    @Test
    fun `repMetricsComplete says why no stronger test exists`() {
        val d = description("repMetricsComplete")
        assertTrue("carry durations and no clock" in d, "the per-rep rows' missing clock is not stated")
        assertTrue("one integer" in d, "the live count's shape is not stated")
    }

    /** The log files the change once, and says nothing but a description moved. */
    @Test
    fun `the 1_23 log files the completeness correction once`() {
        val log = versionLog()
        assertEquals(1, log.split(marker).size - 1, "the #246 entry is not filed exactly once")
        val entry = log.substringAfter(marker).substringBefore("1.23 FURTHER ENTRY (")
        for (fact in listOf("NO KEY OR VALUE CHANGES", "DATABASE_VERSION does NOT move")) {
            assertTrue(fact in entry, "the #246 entry does not state: $fact")
        }
    }

    /** The copy the coach receives: the per-rep line said what false means and nothing about true. */
    @Test
    fun `the plan prompt says true is only the counts matching`() {
        assertTrue(
            "\"repMetricsComplete\": true says only that the two counts match" in prompt,
            "the plan prompt lets a coach read true as the lifter's reps",
        )
    }
}
