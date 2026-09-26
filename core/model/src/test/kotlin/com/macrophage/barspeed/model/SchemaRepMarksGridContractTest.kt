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
 * Export 1.23, a further entry (#294): `repMarks` -- and the raw archive's
 * `_reps.csv`, which holds the same instants -- are described as what they
 * hold, and the content is kept.
 *
 * WHAT THEY HOLD, MEASURED. `RepMarkTrackTest` in `:core:dsp` reads the
 * thirteen committed captures that carry a rep-mark stream, all guided sets:
 * every one of their 103 marks lands within 1 ms of a row of the same set's
 * cue track, 94 on the same millisecond. Read against those rows the mark
 * sits on the NEXT cycle's opening stroke word, or on `Done`; on eight of
 * the thirteen sets that stroke word carries no rep word at all.
 * So on a guided set a mark is the instant the cadence guide finished one
 * prescribed cycle: the prescribed grid, kept on the guide's own schedule.
 * #294 measured the consequence on field-42: a failed set published more
 * marks than reps.
 *
 * WHY THE CONTENT IS KEPT. No true rep instant exists in what a guided set
 * stores. The per-rep rows carry durations and an ordinal and no clock, the
 * cue track is what the app said, and the live count is one integer. Publishing
 * "rep instants" would mean inventing them. The name is historical and is
 * corrected by its description, never by a rename: a renamed key or file
 * breaks every reader and adds no fact.
 *
 * RED WHEN WRITTEN: the description opens "The instants a rep was COUNTED",
 * says a mark is written "when the voice guide calls a rep", and `voiceCues`
 * says "repMarks is what was counted".
 */
class SchemaRepMarksGridContractTest {
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

    private val marker = "1.23 FURTHER ENTRY (#294"

    /** On a guided set a mark is the guide's cycle, not a rep anyone observed. */
    @Test
    fun `repMarks on a metronome set is described as the cadence guide's cycle grid`() {
        val d = description("repMarks")
        assertTrue("the instant the cadence guide finished one prescribed cycle" in d, "no cycle: $d")
        assertTrue("the prescribed grid" in d, "repMarks does not name the grid")
        assertTrue("NOT instants anyone observed a rep" in d, "repMarks does not say a mark is not an observed rep")
        assertTrue("`metronome`" in d && "`manual`" in d, "repMarks does not say which counter wrote which marks")
    }

    /**
     * The field-42 set 12 shape, stated: a set the lifter failed or ended
     * early can carry more marks than `reps`, and a row count of the archive's
     * file is a count of marks.
     */
    @Test
    fun `repMarks says marks can outnumber reps and the archive file counts marks`() {
        val d = description("repMarks")
        assertTrue("more marks than `reps`" in d, "repMarks does not say marks can outnumber reps")
        assertTrue("`_reps.csv`" in d, "repMarks does not name the archive file that holds the same instants")
        assertTrue("row count is the number of marks, not of reps" in d, "repMarks lets a row count read as reps")
        assertTrue("historical" in d, "repMarks does not say its name is historical")
    }

    /**
     * DELETED, not reworded: the opening "The instants a rep was COUNTED", and
     * "when the voice guide calls a rep" -- on the committed corpus the guide's
     * mark sits on an opening stroke word, which on eight of thirteen sets
     * carries no rep call.
     * `voiceCues`' "repMarks is what was counted" goes with them.
     */
    @Test
    fun `the sentences that read the marks as reps are gone`() {
        val d = description("repMarks")
        assertFalse(d.startsWith("The instants a rep was COUNTED"), "repMarks still opens by calling them reps")
        assertFalse("when the voice guide calls a rep" in d, "repMarks still says the guide marks on its rep call")
        assertFalse("repMarks is what was counted" in description("voiceCues"), "voiceCues still calls marks reps")
    }

    /** The log files the change once, and says nothing but descriptions moved. */
    @Test
    fun `the 1_23 log files the repMarks correction once`() {
        val log = versionLog()
        assertEquals(1, log.split(marker).size - 1, "the #294 entry is not filed exactly once")
        val entry = log.substringAfter(marker).substringBefore("1.23 FURTHER ENTRY (")
        for (fact in listOf("NO KEY, VALUE OR FILE CHANGES", "103", "DATABASE_VERSION does NOT move")) {
            assertTrue(fact in entry, "the #294 entry does not state: $fact")
        }
    }

    /** The copy the coach receives says not to count the marks as reps. */
    @Test
    fun `the plan prompt says the marks are not reps on a guided set`() {
        assertTrue("never count their rows as reps" in prompt, "the plan prompt lets a coach count marks as reps")
    }
}
