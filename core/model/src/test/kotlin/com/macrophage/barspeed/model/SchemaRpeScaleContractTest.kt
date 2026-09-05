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
 * The `rpeScale` key in every place the export contract is stated (#244).
 *
 * ELEVEN OF THE TWELVE METHODS ARE RED AT THIS COMMIT, measured by running
 * the suite here rather than asserted. The twelfth is the guard that the
 * schema NUMBER does not move, which is green on both sides and is the point:
 * this key is a further entry under an unreleased 1.19, not a mint.
 *
 * The eleven fail because the published schema has no such property,
 * `SetExport` has no such field, the version log's last entry is the TENTH,
 * the shipped example carries no scale word, and `PLAN_PROMPT` -- the copy the
 * COPY PLAN PROMPT button actually puts on a lifter's clipboard -- tells a
 * coach that a 6 means one equipment increment on every dynamic set.
 *
 * ## Why the key exists
 *
 * Without it a `6` is unreadable. On a `weight` exercise it means the lifter
 * could have added a plate pair; on a `reps` exercise, from this version, it
 * means three or four reps were left; on a `"none"` exercise it means the set
 * felt comfortable and names no quantity at all. Those are three different
 * claims sharing one integer, and no other key in the document separates them:
 * the plan is not in the export, `progression` is not published per set, and a
 * plan can be edited or deleted after the session it drove.
 *
 * ## Why the stored word is the RESOLVED scale and not the declaration
 *
 * `rpeScale` is a CAPTURE-TIME fact about which question the lifter was shown,
 * frozen when the set is written -- `SetExport.bodyWeightKg`'s own reason,
 * with a sharper edge. If the raw `progression` were stored instead, a later
 * change to how a declaration maps onto a question would restate which
 * question a past lifter saw. It cannot: they saw what they saw.
 *
 * ## What ABSENCE means, and what it deliberately does not
 *
 * A set recorded before this version carries no key, and the document cannot
 * say what its exercise progressed on -- nothing stored it. The reading rule
 * is therefore today's behaviour rather than a guess: absent means `load` on a
 * dynamic set and `time` on a timed one, which is exactly what the app asked
 * before this key existed. That is stated in the schema, in the version log
 * and in the prompt, because a reader who assumes `load` on a timed set reads
 * every plank backwards.
 */
class SchemaRpeScaleContractTest {
    private fun schema() = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/session-export.schema.json")!!.readBytes().decodeToString(),
    ).jsonObject

    private fun setProperty(name: String) =
        schema()["\$defs"]!!.jsonObject["set"]!!.jsonObject["properties"]!!.jsonObject[name]

    private fun versionLog() =
        schema()["properties"]!!.jsonObject["schemaVersion"]!!.jsonObject["description"]!!.jsonPrimitive.content

    private fun rpeDescription() = setProperty("rpe")!!.jsonObject["description"]!!.jsonPrimitive.content

    private fun scaleDescription() = setProperty("rpeScale")!!.jsonObject["description"]!!.jsonPrimitive.content

    private val exampleText: String =
        javaClass.getResourceAsStream("/examples/session-export.example.json")!!.readBytes().decodeToString()

    private val prompt: String =
        checkNotNull(
            javaClass.getResourceAsStream("/kotlin/com/macrophage/barspeed/ui/screens/GuideScreen.kt"),
        ) { "GuideScreen.kt is not on the test classpath - see the include filter in core/model/build.gradle.kts" }
            .readBytes().decodeToString()

    // ---- 1. the published key and its Kotlin twin ----

    @Test
    fun `the published set declares rpeScale with exactly the app's four scale words`() {
        val declared = setProperty("rpeScale")!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(SessionExport.VALID_RPE_SCALES, declared.toSet(), "the published scale words drifted")
        assertEquals(declared.size, declared.toSet().size, "the published enum repeats a word")
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `the set serialiser carries the rpeScale key`() {
        assertContains(SetExport.serializer().descriptor.elementNames.toList(), "rpeScale")
    }

    /**
     * The number does not move. 1.19 is UNRELEASED -- v0.1.50 shipped 1.18,
     * read at the tag rather than assumed -- so this is a further entry under
     * it and not a mint, the rule every entry under 1.19 already states.
     */
    @Test
    fun `the schema version is still 1_19 and the key rides under it`() {
        assertEquals("1.19", SessionExport.SCHEMA_VERSION)
        assertContains(SessionExport.SUPPORTED_SCHEMA_VERSIONS, "1.19")
    }

    // ---- 2. the two version logs, which must carry the same entry ----

    @Test
    fun `the published version log has an rpeScale entry naming the key and the absence rule`() {
        val log = versionLog()
        assertTrue("rpeScale" in log, "the published version log never names the key")
        assertTrue("ELEVENTH" in log, "the entry does not say where it sits in 1.19's log")
        assertTrue("#244" in log, "the entry is not anchored on the issue that asked for it")
        assertTrue(
            "load" in log && "reps" in log && "time" in log && "feel" in log,
            "the entry does not list the four scale words: $log",
        )
    }

    // The KOTLIN version log on `SessionExport.SCHEMA_VERSION` carries the
    // same entry and is NOT asserted here: `SessionExport.kt` is not on this
    // module's test classpath, which admits the published schemas, their
    // examples and `GuideScreen.kt` and nothing else. The coupling that IS
    // machine-checked is the one that matters to a reader -- the published
    // enum against `VALID_RPE_SCALES`, above -- and the Kotlin entry is
    // reviewed rather than pinned.

    // ---- 3. the reading key on `rpe` and on `rpeScale` ----

    /**
     * The sentence that went false is DELETED rather than reworded.
     *
     * Before this version the published `rpe` told a reader that the document
     * does not record "whether the set drew the load or the time rungs". It
     * does now, and leaving that standing beside the key that records it is
     * the drift class this repository keeps shipping.
     */
    @Test
    fun `the published rpe no longer claims the drawn rungs are unrecoverable`() {
        assertFalse(
            "nor whether the set drew the load or the time rungs" in rpeDescription(),
            "the rpe description still says the scale is unrecoverable: ${rpeDescription()}",
        )
        assertTrue("rpeScale" in rpeDescription(), "the rpe description does not point at the key that says which")
    }

    /**
     * The `rpe` description names all four rungsets, not just load and time.
     *
     * Coupled to the caption table rather than paraphrased, the arrangement
     * `EffortContractTest` already uses: revising a row after a field session
     * reds this until the document moves with it.
     */
    @Test
    fun `the published rpe names the headroom figures of every scale it can be given on`() {
        val description = rpeDescription()
        listOf(EffortAsk.LOAD, EffortAsk.REPS, EffortAsk.TIME, EffortAsk.FEEL).forEach { ask ->
            HeadroomTier.entries.forEach { tier ->
                val figure =
                    EffortScale.headroomCaption(tier, ask, WeightUnit.LB)
                        .removePrefix("Could have added ")
                        .removePrefix("Could have gone ")
                assertTrue(
                    figure in description,
                    "the published rpe never names \"$figure\", which a $ask tile offers: $description",
                )
            }
        }
    }

    @Test
    fun `the published rpeScale states the reading rule for each word and for absence`() {
        val text = scaleDescription()
        SessionExport.VALID_RPE_SCALES.forEach {
            assertTrue("`$it`" in text || "'$it'" in text, "the scale key never explains the word $it: $text")
        }
        assertTrue("ABSENT" in text || "absent" in text, "the scale key never says what absence means: $text")
        assertTrue(
            "frozen" in text || "recorded" in text,
            "the scale key never says the word is a capture-time fact: $text",
        )
    }

    // ---- 4. the shipped example, which ajv validates in CI ----

    @Test
    fun `the shipped example exercises the key it now publishes`() {
        assertTrue("\"rpeScale\"" in exampleText, "the published example never uses the key this version adds")
        val sets =
            Json.parseToJsonElement(exampleText).jsonObject["exercises"]!!.jsonArray
                .flatMap { it.jsonObject["sets"]!!.jsonArray }
                .map { it.jsonObject }
        val words = sets.mapNotNull { it["rpeScale"]?.jsonPrimitive?.content }.toSet()
        assertTrue(words.isNotEmpty(), "no set in the example carries a scale word")
        assertTrue(
            words.all { it in SessionExport.VALID_RPE_SCALES },
            "the example carries a scale word the schema refuses: $words",
        )
        sets.filter { "rpeScale" in it }.forEach {
            assertTrue("rpe" in it, "the example publishes a scale word on a set carrying no rating")
        }
    }

    // ---- 5. the prompt, which is the copy that actually reaches a model ----

    @Test
    fun `the plan prompt reading key names rpeScale and all four of its words`() {
        assertTrue("\"rpeScale\"" in prompt, "the prompt never names the key, so no coach will read it")
        SessionExport.VALID_RPE_SCALES.forEach {
            assertTrue("\"$it\"" in prompt, "the prompt's reading key never offers the scale \"$it\"")
        }
    }

    @Test
    fun `the plan prompt says what an absent scale word means, on both kinds of set`() {
        // The reading-key BULLET, not merely the first line mentioning the
        // key: the `progression` paragraph names it too, several lines
        // earlier, and `first { }` picked that one -- so this test read a
        // sentence that was never meant to carry the absence rule.
        val line = prompt.lineSequence().first { it.trimStart().startsWith("- \"rpeScale\"") }
        assertTrue("ABSENT" in line || "absent" in line, "the prompt does not say what absence means: $line")
        assertTrue("load" in line && "time" in line, "the prompt does not give the absence rule per kind: $line")
    }

    /**
     * The prompt's `progression` paragraph and the reading key have to agree:
     * the key the plan declares is the key that decides which question the
     * lifter is asked afterwards, and a coach writing a plan needs to know
     * that before choosing it.
     */
    @Test
    fun `the plan prompt's progression paragraph says the rating asks in that dimension`() {
        val sentence = prompt.lineSequence().first { "\"progression\"" in it }
        // The exact clause, not the two words apart. The paragraph already
        // said "which dimension of THIS exercise steps up" and already said
        // "I rate how it felt", so an assertion for "dimension" and "rate"
        // separately passes against the UNCHANGED prompt and is a check that
        // cannot fail. What is new is that the RATING is asked in that
        // dimension too, and a coach choosing the key needs to know it.
        assertTrue(
            "asks in that dimension" in sentence,
            "the progression paragraph does not say the rating asks in that dimension: $sentence",
        )
    }

    /**
     * The prompt's old line said a headroom rung is LOAD on every dynamic set.
     * It is not, from this version, and a coach following it reads a pull-up's
     * 6 as a plate claim.
     *
     * IT ASSERTED THAT THE PHRASE "These are LOAD, not reps" WAS GONE, and
     * that was too strong: the phrase is TRUE and worth keeping on the weight
     * row, where the rungs really are a load claim and the reason -- a rep
     * count below three left is a guess -- is exactly what a coach needs. What
     * had to go is the phrase standing UNQUALIFIED over every dynamic set. So
     * this asserts the qualification instead of the absence.
     */
    @Test
    fun `the plan prompt no longer says the headroom end is load on every dynamic set`() {
        val claims = prompt.lineSequence().filter { "These are LOAD, not reps" in it }.toList()
        claims.forEach {
            assertTrue(
                "progresses by WEIGHT" in it,
                "the prompt calls a headroom rung a load claim without saying on which exercises: $it",
            )
        }
        assertFalse(
            "Dynamic sets, headroom end" in prompt,
            "the prompt still keys the headroom end off the set's kind rather than the exercise's progression",
        )
    }
}
