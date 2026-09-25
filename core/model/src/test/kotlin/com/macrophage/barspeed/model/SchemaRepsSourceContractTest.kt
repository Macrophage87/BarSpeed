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
import kotlin.test.assertNotEquals
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

    /**
     * The copy of the plan contract the COPY PLAN PROMPT button puts on a
     * lifter's clipboard, read off the test classpath the way
     * [SchemaRpeScaleContractTest] reads it.
     */
    private val prompt: String =
        checkNotNull(
            javaClass.getResourceAsStream("/kotlin/com/macrophage/barspeed/ui/screens/GuideScreen.kt"),
        ) { "GuideScreen.kt is not on the test classpath - see the include filter in core/model/build.gradle.kts" }
            .readBytes().decodeToString()

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
     * Not a hedge for its own sake. The live detector has been scored on two
     * deadlift sessions and on nothing else, it called nothing on the two
     * heaviest sets of the second (#305), and the batch detector over-counts
     * all six committed concentric-first captures that carry a hand count. A
     * coach must not read `sensor` as a verified count.
     *
     * This KDoc said the live detector "has never been scored against a real
     * straight-reps set". False since #301 in v0.1.54, and DELETED rather than
     * reworded (#302).
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

    /**
     * THE FIGURES, in both published copies of the reading key.
     *
     * WHAT CHANGED (#302). Since #301 in v0.1.54 a sensor-counted set is
     * counted by `DriveImpulseCounter`, and every figure this test used to pin
     * -- 35 calls against 103 marks over thirteen tempo'd captures, and the six
     * per-capture overhead-press rows -- was `LiveRepCaller`'s, the retired
     * detector. Quoting them under `sensor` attributed one detector's failures
     * to another's word, so they are replaced rather than kept beside the new
     * ones.
     *
     * WHERE EACH NEW FIGURE COMES FROM, because `:core:model` cannot see
     * `:core:dsp`'s corpus and nothing mechanical compares them with the table
     * that produced them. Field-43's 5, 5 and 3, and the overhead-press 8, 8,
     * 7, 10, 11 and 2 against 6, 7, 5, 8, 8 and 2, are computed by
     * `LiveCountDifferentialTest`, replaying the capture through the counter
     * the app arms. Field-44's 5, 5, 5, 0 and 0 is what the app exported as
     * `liveReps` on the day, the truth of 5, 5, 5, 4 and 2 is the owner's as
     * settled on #305, and no committed test computes either: field-44 is not
     * a fixture here. The retired detector's 3, 1 and 2 is
     * `DeadliftLiveCountFieldTest`'s.
     *
     * BOTH COPIES, checked against the SAME strings, because that is the only
     * drift this file can catch: what is enforced is that the schema and the
     * prompt the coach receives say the same thing.
     */
    @Test
    fun `the reading key states what the live detector has been scored on`() {
        val documents = mapOf("the published schema" to description("repsSource"), "the plan prompt" to prompt)
        val figures =
            listOf(
                "an upward acceleration impulse followed by braking, with no velocity in it",
                "5, 5 and 3 of 5, 5 and 5 at 61.2, 83.9 and 102.1 kg",
                "5, 5, 5, 0 and 0 of 5, 5, 5, 4 and 2 at 61.2, 83.9, 102.1, 111.1 and 120.2 kg",
                "nothing at 111.1 or 120.2 kg",
                "#305",
                "8, 8, 7, 10, 11 and 2 against hand counts of 6, 7, 5, 8, 8 and 2",
                "recorded by v0.1.53",
                "3, 1 and 2",
            )
        documents.forEach { (name, text) ->
            figures.forEach {
                assertTrue(it.lowercase() in text.lowercase(), "$name does not state: $it")
            }
        }
    }

    /**
     * NEITHER COPY STILL CARRIES A CLAIM #301 MADE FALSE (#302).
     *
     * Pinned as absences as well as presences, because the defect this issue
     * found was a sentence left standing beside a correct one: a rewording that
     * added the new figures and kept "has never been scored" would satisfy the
     * test above and still tell a coach the opposite.
     */
    @Test
    fun `neither copy of the reading key still carries a claim the impulse counter made false`() {
        val documents = mapOf("the published schema" to description("repsSource"), "the plan prompt" to prompt)
        val deleted =
            listOf(
                "never been scored against a real straight-reps set",
                "35 calls against 103 marks",
                "four of the thirteen say nothing",
                "the live caller makes 1 call",
                "the measured bottleneck is the velocity estimate rather than the pairing rule",
            )
        documents.forEach { (name, text) ->
            deleted.forEach {
                assertFalse(it.lowercase() in text.lowercase(), "$name still states: $it")
            }
        }
    }

    /**
     * THE SET NO GUIDE PACED, named in both copies.
     *
     * An explosive lift carrying a tempo is given no cadence -- it is judged on
     * peak velocity -- so the lifter taps its count, and the derivation read
     * the tempo alone and published `metronome` for it. That is fixed rather
     * than documented, and what the reading key must still say is the one case
     * left: a row with no stored geometry cannot say what kind of exercise it
     * was, so its tempo is read as the guide.
     */
    @Test
    fun `the reading key names the explosive set no guide paced`() {
        val documents = mapOf("the published schema" to description("repsSource"), "the plan prompt" to prompt)
        documents.forEach { (name, text) ->
            assertTrue(
                "explosive lift carrying a tempo" in text.lowercase(),
                "$name does not name the lift a tempo does not pace",
            )
            assertTrue("no stored geometry" in text.lowercase(), "$name does not name the collapse that is left")
        }
    }

    /**
     * A GEOMETRY-LESS TEMPO'D ROW READS AS THE GUIDE, in both copies.
     *
     * `RepsSourcePolicy.guideCounted` falls back to the tempo when `kind` is
     * null, so such a row publishes `metronome`. The published schema says
     * exactly that. `PLAN_PROMPT`, the copy the coach actually receives, said
     * the opposite -- that "metronome" on such a row can be the lifter's own
     * tap and should be read as "manual" -- which is a reinterpretation of
     * almost every geometry-less tempo'd row in the archive, and almost all of
     * those really were guided. The collapse is wrong for ONE shape, the
     * explosive lift carrying a tempo, and the prompt generalised that one
     * shape over the whole class.
     *
     * Pinned as a positive statement rather than as the absence of the wrong
     * one, so a future rewording cannot satisfy it by deleting the sentence:
     * both copies must SAY the tempo is read as the guide. The negative is
     * kept beside it because the specific instruction that shipped is the one
     * a coach would have followed.
     */
    @Test
    fun `the reading key says a geometry-less tempo reads as the guide and not as a tap`() {
        val documents = mapOf("the published schema" to description("repsSource"), "the plan prompt" to prompt)
        documents.forEach { (name, text) ->
            assertTrue(
                "read as the guide" in text.lowercase(),
                "$name does not say a geometry-less tempo'd row is read as the guide",
            )
            assertFalse(
                "read it as \"manual\"" in text.lowercase(),
                "$name tells a coach to read a geometry-less tempo'd row as a tap",
            )
        }
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
     * THE LIVE COUNT NAMES THE DETECTOR THAT PRODUCES IT (#302).
     *
     * The published description said "The live detector applies the same
     * pairing rule over a CAUSAL velocity estimate". Since #301 in v0.1.54 a
     * sensor-counted set -- the only set this key is published on -- is
     * counted from a drive impulse with no velocity in it, so the sentence was
     * false for every set v0.1.54 recorded. It is DELETED, and the retired
     * detector is named only as what a v0.1.53 recording carries.
     */
    @Test
    fun `the published live count names the impulse detector and not a velocity pairing rule`() {
        val d = description("liveReps")
        assertTrue("drive-impulse detector" in d, "the description does not name the detector that counts")
        assertTrue("no velocity" in d, "the description does not say the live count reads no velocity")
        assertTrue("v0.1.53" in d, "the description does not say which recordings carry the retired detector")
        assertFalse(
            "The live detector applies the same pairing rule over a CAUSAL velocity estimate" in d,
            "the description still says the live count is a velocity pairing rule",
        )
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

    /**
     * The completeness caveat no longer calls the live and batch counts one
     * rule over two estimates (#302).
     *
     * On a `sensor` set `repMetricsComplete` compares the segmenter with the
     * live count, and since #301 those are two different detectors. The clause
     * "one pairing rule over two velocity estimates" read a false as two
     * estimates of one thing disagreeing; it is DELETED, and the caveat says
     * two detectors disagree and either may be wrong.
     */
    @Test
    fun `the completeness caveat calls the live and batch counts two detectors`() {
        val d = description("repMetricsComplete")
        assertFalse(
            "one pairing rule over two velocity estimates" in d,
            "the caveat still calls the live and batch counts one rule over two estimates",
        )
        assertTrue("two different detectors" in d, "the caveat does not say the two counts are different detectors")
    }

    /**
     * THE COPY THE COACH ACTUALLY RECEIVES says whose count `reps` is.
     *
     * `PLAN_PROMPT` is the canonical statement of this contract -- it is what
     * the button copies to the clipboard -- and it told the coach that
     * *"`reps` is authoritative -- I counted it, or the voice guide did. The
     * accelerometer is RECORD-ONLY on standard lifts."* Both halves are false
     * from #286: the accelerometer counts a straight-reps set, and `reps` may
     * be its figure rather than a person's. A schema key the shipped prompt
     * contradicts is the *duplicate documentation drifts* class, and it has
     * shipped a real defect here before -- the prompt told the model to emit
     * `start` values the app's own schema rejected.
     */
    @Test
    fun `the shipped plan prompt tells the coach to read the counter word`() {
        assertTrue("repsSource" in prompt, "the prompt the coach receives does not mention repsSource")
        SessionExport.VALID_REPS_SOURCES.forEach {
            assertTrue(
                '"' + it + '"' in prompt,
                "the prompt does not say what the counter word $it means",
            )
        }
        assertFalse(
            "The accelerometer is RECORD-ONLY on standard lifts" in prompt,
            "the prompt still tells the coach the accelerometer never counts",
        )
        assertTrue(
            "hand count" in prompt.lowercase(),
            "the prompt does not tell the coach the hand count is the ground truth on the first captures",
        )
    }

    // ---- 3. the version log ----

    /**
     * RENAMED from `the keys ride under an unreleased 1_20`, whose premise
     * v0.1.53 made false by shipping 1.20.
     */
    @Test
    fun `the keys ride under 1_20, which v0_1_53 shipped`() {
        // CORRECTED FORWARD, and this comment is the one copy of the reasoning
        // that seven files share. This line read
        // `assertEquals("<the tip>", SessionExport.SCHEMA_VERSION)`: it asserted
        // the version THIS KEY IS FILED UNDER by reading the version the
        // exporter currently WRITES. Those are the same number only while the
        // mint is the newest one, so the line goes false at the next mint --
        // twice here already, each time "corrected" by re-pointing it at the new
        // tip, which is the same defect again. #300 mints 1.21, so it is DELETED
        // rather than re-pointed. What replaces it cannot go stale: the filed
        // version is still ACCEPTED, and it is not the version the exporter writes. That
        // the tip constant, the accepted set, the published enum and the example
        // all agree is SchemaContractTest's `session export schema allows the
        // version the exporter writes` and `the published example declares the
        // version the exporter writes`, both of which read the constant instead
        // of a literal.
        assertContains(SessionExport.SUPPORTED_SCHEMA_VERSIONS, "1.20")
        assertNotEquals("1.20", SessionExport.SCHEMA_VERSION, "the exporter is still writing 1.20")
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
