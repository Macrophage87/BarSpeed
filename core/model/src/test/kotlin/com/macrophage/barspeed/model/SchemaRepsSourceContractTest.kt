package com.macrophage.barspeed.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The `repsSource` and `liveReps` keys in every place the export contract is
 * stated (#286).
 *
 * ## Why the keys exist
 *
 * `reps` is an integer with no statement of whose count it is. Four counters
 * can produce it -- the sensor live, the lifter's taps, the cadence guide, and
 * the batch segmenter at the end of the set -- and until now only one bit
 * separated them: `repsManual`, which is true both for a tally the lifter kept
 * and for a correction of a count something else made. A coach reading a
 * straight-reps deadlift set could not tell a sensor count from a hand count,
 * which is the first question to ask of it (#284).
 *
 * `liveReps` is the sensor's own figure, kept beside the corrected one. Without
 * it a correction destroys what the sensor said on exactly the sets where the
 * sensor was wrong.
 *
 * ## A FURTHER ENTRY UNDER 1.20, not a mint
 *
 * v0.1.52 ships `SCHEMA_VERSION = "1.19"`, read by
 * `git show v0.1.52:core/model/src/main/kotlin/com/macrophage/barspeed/model/SessionExport.kt`
 * rather than assumed, and the constant on `main` is already 1.20 for the
 * `ecc_s` omission entry. So 1.20 is UNRELEASED and this key rides under it.
 * `the keys ride under an unreleased 1_20` is the pin that says so, and it is
 * green on both sides of this change on purpose.
 *
 * ## What is NOT purely additive
 *
 * `repMetricsComplete`. Its published description said *"when repsManual is
 * false the recorded count IS the segmenter's count, so the two agree by
 * construction and this says nothing independent."* That stops being true here:
 * on a sensor-counted set `repsManual` is false and the recorded count is the
 * LIVE count, so a false value on such a set is the live and batch detectors
 * disagreeing -- real information, and the figure the first deadlift session is
 * read for. The sentence is DELETED rather than reworded, in the schema and in
 * the Kotlin KDoc, and `the completeness caveat no longer claims the counts
 * agree by construction` pins its removal.
 */
class SchemaRepsSourceContractTest {
    private fun schema() = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/session-export.schema.json")!!.readBytes().decodeToString(),
    ).jsonObject

    private fun setProperty(name: String) =
        schema()["\$defs"]!!.jsonObject["set"]!!.jsonObject["properties"]!!.jsonObject[name]

    private fun versionLog() =
        schema()["properties"]!!.jsonObject["schemaVersion"]!!.jsonObject["description"]!!.jsonPrimitive.content

    private fun description(name: String) = setProperty(name)!!.jsonObject["description"]!!.jsonPrimitive.content

    private val exampleText: String =
        javaClass.getResourceAsStream("/examples/session-export.example.json")!!.readBytes().decodeToString()

    // ---- 1. the published keys and their Kotlin twins ----

    @Test
    fun `the published set declares repsSource with exactly the app's five counter words`() {
        val declared = setProperty("repsSource")!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(SessionExport.VALID_REPS_SOURCES, declared.toSet(), "the published counter words drifted")
        assertEquals(declared.size, declared.toSet().size, "the published enum repeats a word")
    }

    /**
     * The vocabulary is the enum, in both directions.
     *
     * `VALID_REPS_SOURCES` is derived from [RepsSource] rather than written out,
     * so this asserts the derivation has not been replaced by a copy.
     */
    @Test
    fun `the published vocabulary is exactly the counter enum's wire words`() {
        assertEquals(RepsSource.entries.map { it.wireName }.toSet(), SessionExport.VALID_REPS_SOURCES)
        assertEquals(RepsSource.entries.size, SessionExport.VALID_REPS_SOURCES.size, "a word collided")
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `the set serialiser carries both keys`() {
        val names = SetExport.serializer().descriptor.elementNames.toList()
        assertContains(names, "repsSource")
        assertContains(names, "liveReps")
    }

    /** A rep count is a count: an integer that cannot be negative. */
    @Test
    fun `the published live count is a non-negative integer`() {
        val live = setProperty("liveReps")!!.jsonObject
        assertEquals("integer", live["type"]!!.jsonPrimitive.content)
        assertEquals(0, live["minimum"]!!.jsonPrimitive.content.toInt(), "a live rep count may be negative")
    }

    // ---- 2. the reading key ----

    /**
     * The description says what each word means and what absence means.
     *
     * Absence is ONE state -- nothing counted reps -- and a reader who takes it
     * for a sixth word, or for `manual`, reads every plank and every set
     * recorded before this version backwards.
     */
    @Test
    fun `the published description names every word and says absence means no counter`() {
        val d = description("repsSource")
        SessionExport.VALID_REPS_SOURCES.forEach {
            assertTrue("`$it`" in d, "the description does not say what `$it` means")
        }
        assertTrue("ABSENT" in d || "Absent" in d, "the description does not say what absence means")
        assertTrue("timed" in d, "the description does not say a timed set has no counter")
    }

    /**
     * The reading key states the hand count as the ground truth for the first
     * straight-reps captures.
     *
     * Not a hedge for its own sake. The live detector has never been scored
     * against a real straight-reps set: issue #145's F1 capture is still owed,
     * every one of the thirteen mark-carrying captures is a guided set whose
     * marks are the GUIDE's calls, and the batch detector over-counts all six
     * committed concentric-first captures that carry a hand count. A coach must
     * not read `sensor` as a verified count.
     */
    @Test
    fun `the reading key says the hand count is the ground truth on the first captures`() {
        val d = description("repsSource")
        assertTrue("hand count" in d.lowercase(), "the reading key does not name the hand count")
        assertTrue(
            "#286" in d || "#284" in d,
            "the reading key does not point at the issue that produced the first evidence",
        )
    }

    /** The live count's description says where it comes from and when it is absent. */
    @Test
    fun `the published live count description says whose figure it is and when it is absent`() {
        val d = description("liveReps")
        assertTrue("live" in d.lowercase(), "the description does not say the figure is the live one")
        assertTrue("ABSENT" in d || "Absent" in d, "the description does not say what absence means")
        assertTrue("corrected" in d, "the description does not pair the figure with a correction")
    }

    /**
     * The completeness caveat no longer claims the two counts agree by
     * construction.
     *
     * DELETED rather than reworded: the sentence was true while every
     * `repsManual`-false row carried the segmenter's own count, and a sensor
     * count in `reps` makes it false. The same deletion is owed in
     * [SetExport.repMetricsComplete]'s KDoc and `the Kotlin twin carries the
     * same correction` asserts it there.
     */
    @Test
    fun `the completeness caveat no longer claims the counts agree by construction`() {
        val d = description("repMetricsComplete")
        assertFalse(
            "agree by construction" in d,
            "the published caveat still says the recorded and segmented counts agree by construction",
        )
        assertTrue("repsSource" in d, "the caveat does not point at the key that now says whose count it is")
    }

    // ---- 3. the version log ----

    @Test
    fun `the keys ride under an unreleased 1_20`() {
        assertEquals("1.20", SessionExport.SCHEMA_VERSION)
        assertContains(SessionExport.SUPPORTED_SCHEMA_VERSIONS, "1.20")
        val versions =
            schema()["properties"]!!.jsonObject["schemaVersion"]!!.jsonObject["enum"]!!.jsonArray
                .map { it.jsonPrimitive.content }
        assertEquals(SessionExport.SUPPORTED_SCHEMA_VERSIONS, versions.toSet(), "the accepted versions drifted")
    }

    /**
     * The 1.20 entry names both keys and flags the one meaning that moved.
     *
     * The log is the only place a reader comparing two exports learns that
     * `repMetricsComplete` is measured against a different count on a
     * sensor-counted set, and that `reps` on such a set is no longer the
     * segmenter's figure.
     */
    @Test
    fun `the 1_20 entry names both keys and says which meaning moved`() {
        val log = versionLog()
        val entry = log.substringAfter("1.20:")
        assertTrue("repsSource" in entry, "the 1.20 entry does not name repsSource")
        assertTrue("liveReps" in entry, "the 1.20 entry does not name liveReps")
        assertTrue("repMetricsComplete" in entry, "the 1.20 entry does not flag the caveat whose meaning moved")
        assertTrue(
            "NOT PURELY ADDITIVE" in entry || "not purely additive" in entry,
            "the 1.20 entry does not say the change is other than additive",
        )
    }

    // ---- 4. the published example ----

    /**
     * The example carries both keys, because the ajv step validates the example
     * and a key no example exercises is a key nothing checks.
     */
    @Test
    fun `the published export example carries a set's counter and live count`() {
        assertTrue("\"repsSource\"" in exampleText, "the example declares no repsSource")
        assertTrue("\"liveReps\"" in exampleText, "the example declares no liveReps")
        val parsed = Json.parseToJsonElement(exampleText).jsonObject
        val words =
            parsed.getValue("exercises").jsonArray
                .flatMap { it.jsonObject.getValue("sets").jsonArray }
                .mapNotNull { it.jsonObject["repsSource"]?.jsonPrimitive?.content }
        assertTrue(words.isNotEmpty(), "no set in the example carries a counter word")
        assertTrue(
            words.all { it in SessionExport.VALID_REPS_SOURCES },
            "the example publishes a counter word the schema does not allow: $words",
        )
    }
}
