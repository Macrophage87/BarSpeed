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
import kotlin.test.assertTrue

/**
 * The `velocityLossRegime` key in every place the export contract is stated
 * (#250).
 *
 * DIFFERENTIAL. Every method here except the version guard is RED at the
 * commit that introduces it, measured by running the suite rather than
 * asserted: the published schema has no such property, `SetExport` has no such
 * field, the version log's last entry under 1.19 is the ELEVENTH, no set in
 * the shipped example carries a regime word, and `PLAN_PROMPT` -- the copy the
 * COPY PLAN PROMPT button puts on a coach's clipboard -- tells the reader that
 * `velocityLoss_pct` is fatigue with no qualification at all.
 *
 * The version guard is green on both sides and is the point: this is a further
 * entry under an unreleased 1.19, not a mint. v0.1.50 ships 1.18, read at the
 * tag rather than assumed.
 *
 * ## Why the key exists
 *
 * `velocityLoss_pct` is best rep to last rep and it answers two different
 * questions depending on the set. On a free-tempo barbell lift it is fatigue.
 * On a tempo-prescribed controlled movement the concentric speed IS the
 * prescription, so the same figure measures how well the lifter held the count
 * -- and nothing in the document says which of the two a reader is looking at.
 * On the two sets of field-38 that bolted both accelerometers to ONE
 * rail-guided stack, one travel with rep counts agreeing exactly, the published
 * figure still moved 5.6 and 7.3 points with the choice of unit; a coach reading
 * those as fatigue reads the mount as autoregulation.
 *
 * ## Why it is DERIVED rather than stored
 *
 * All three inputs -- the set's prescription and the drive direction and kind
 * of its frozen geometry -- are already on the row, so the word is computed at
 * export time and `DATABASE_VERSION` does not move. That is the opposite of
 * `rpeScale`, which records which question a lifter was SHOWN and therefore
 * cannot be re-derived from anything.
 *
 * ## What ABSENCE means
 *
 * Absent on a set recorded before the geometry column existed, on a hold or a
 * carry, and on a tempo string the app cannot parse. The reading rule for all
 * three is today's behaviour rather than a guess: read velocity loss as this
 * document has always told a reader to read it, which is `maxIntent`.
 */
class SchemaVelocityLossRegimeContractTest {
    private fun schema() = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/session-export.schema.json")!!.readBytes().decodeToString(),
    ).jsonObject

    private fun setProperty(name: String) =
        schema()["\$defs"]!!.jsonObject["set"]!!.jsonObject["properties"]!!.jsonObject[name]

    private fun versionLog() =
        schema()["properties"]!!.jsonObject["schemaVersion"]!!.jsonObject["description"]!!.jsonPrimitive.content

    private fun regimeDescription() =
        setProperty("velocityLossRegime")!!.jsonObject["description"]!!.jsonPrimitive.content

    private val exampleText: String =
        javaClass.getResourceAsStream("/examples/session-export.example.json")!!.readBytes().decodeToString()

    private val prompt: String =
        checkNotNull(
            javaClass.getResourceAsStream("/kotlin/com/macrophage/barspeed/ui/screens/GuideScreen.kt"),
        ) { "GuideScreen.kt is not on the test classpath - see the include filter in core/model/build.gradle.kts" }
            .readBytes().decodeToString()

    // ---- 1. the published key and its Kotlin twin ----

    @Test
    fun `the published set declares velocityLossRegime with exactly the app's two words`() {
        val declared =
            setProperty("velocityLossRegime")!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(SessionExport.VALID_VELOCITY_LOSS_REGIMES, declared.toSet(), "the published regime words drifted")
        assertEquals(declared.size, declared.toSet().size, "the published enum repeats a word")
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `the set serialiser carries the velocityLossRegime key`() {
        assertContains(SetExport.serializer().descriptor.elementNames.toList(), "velocityLossRegime")
    }

    @Test
    fun `the schema version is still 1_19 and the key rides under it`() {
        assertEquals("1.19", SessionExport.SCHEMA_VERSION)
        assertContains(SessionExport.SUPPORTED_SCHEMA_VERSIONS, "1.19")
    }

    // ---- 2. the version log ----

    @Test
    fun `the published version log has a regime entry naming the key, both words and the absence rule`() {
        val log = versionLog()
        assertTrue("velocityLossRegime" in log, "the published version log never names the key")
        assertTrue("TWELFTH" in log, "the entry does not say where it sits in 1.19's log")
        assertTrue("#250" in log, "the entry is not anchored on the issue that asked for it")
        SessionExport.VALID_VELOCITY_LOSS_REGIMES.forEach {
            assertTrue("`$it`" in log, "the entry never names the word $it")
        }
        assertTrue(
            "DATABASE_VERSION" in log && "does not move" in log,
            "the entry does not say the word is derived rather than stored: $log",
        )
    }

    // ---- 3. the reading rule on the key and on velocityLoss_pct ----

    @Test
    fun `the published regime key states the rule for each word and for absence`() {
        val text = regimeDescription()
        SessionExport.VALID_VELOCITY_LOSS_REGIMES.forEach {
            assertTrue("`$it`" in text, "the regime key never explains the word $it: $text")
        }
        assertTrue("ABSENT" in text || "absent" in text, "the regime key never says what absence means: $text")
        assertTrue(
            "tempoCompliance" in text && "romSpread_pct" in text,
            "the regime key does not say what a controlled set IS read on: $text",
        )
    }

    /**
     * `velocityLoss_pct` stays published on both regimes, and its own
     * description has to stop presenting itself as fatigue unqualified.
     */
    @Test
    fun `the published velocityLoss_pct points at the regime that says how to read it`() {
        val text = setProperty("velocityLoss_pct")!!.jsonObject["description"]!!.jsonPrimitive.content
        assertTrue("velocityLossRegime" in text, "velocityLoss_pct does not point at the key qualifying it: $text")
    }

    // ---- 4. the shipped example, which ajv validates in CI ----

    @Test
    fun `the shipped example carries both regime words`() {
        assertTrue("\"velocityLossRegime\"" in exampleText, "the published example never uses the key")
        val words = exampleSets().mapNotNull { it["velocityLossRegime"]?.jsonPrimitive?.content }.toSet()
        assertEquals(
            SessionExport.VALID_VELOCITY_LOSS_REGIMES,
            words,
            "the example does not exercise both regimes, so a reader sees only one shape",
        )
        exampleSets().filter { "velocityLossRegime" in it }.forEach {
            assertTrue("geometry" in it, "the example publishes a regime on a set with no geometry to derive it from")
        }
    }

    /**
     * The example's own arithmetic, checked rather than trusted: every set
     * that carries geometry and is not a hold or a carry must carry the word
     * the decision gives for it, and no other set may carry one.
     */
    @Test
    fun `every regime word in the example is the one the decision derives for that set`() {
        exampleSets().forEachIndexed { index, set ->
            val geometry = set["geometry"]?.jsonObject
            val expected =
                VelocityLossRegime.of(
                    tempoPrescribed = set["tempoPrescribed"]?.jsonPrimitive?.content,
                    concentricUp = geometry?.get("concentric")?.jsonPrimitive?.content?.let { it == "up" },
                    horizontal = geometry?.get("plane")?.jsonPrimitive?.content?.let { it == "horizontal" },
                    kind =
                    geometry?.get("kind")?.jsonPrimitive?.content
                        ?.let { word -> ExerciseKind.entries.first { it.name.lowercase() == word } },
                )
            assertEquals(
                expected?.wireName,
                set["velocityLossRegime"]?.jsonPrimitive?.content,
                "example set $index carries a regime word the rule does not derive for it",
            )
        }
    }

    private fun exampleSets() = Json.parseToJsonElement(exampleText).jsonObject["exercises"]!!.jsonArray
        .flatMap { it.jsonObject["sets"]!!.jsonArray }
        .map { it.jsonObject }

    // ---- 5. the prompt, which is the copy that actually reaches a model ----

    @Test
    fun `the plan prompt reading key names the regime and both of its words`() {
        assertTrue("\"velocityLossRegime\"" in prompt, "the prompt never names the key, so no coach will read it")
        SessionExport.VALID_VELOCITY_LOSS_REGIMES.forEach {
            assertTrue("\"$it\"" in prompt, "the prompt's reading key never offers the regime \"$it\"")
        }
    }

    @Test
    fun `the plan prompt says how to read velocity loss in each regime`() {
        val line = prompt.lineSequence().first { it.trimStart().startsWith("- \"velocityLoss_pct\"") }
        assertTrue("velocityLossRegime" in line, "the velocity-loss bullet does not point at the regime: $line")
        assertTrue(
            "tempoCompliance" in line && "romSpread_pct" in line,
            "the bullet does not say what a controlled set is judged on instead: $line",
        )
        assertTrue(
            "compliance" in line,
            "the bullet does not say what velocity loss MEASURES on a controlled set: $line",
        )
    }

    /**
     * The plan-writing half. The prompt already tells a coach to use tempo and
     * velocity targets deliberately on primary barbell lifts; #250 asks that
     * the paragraph say the consequence out loud, because the choice of a
     * tempo is now the choice of which figure the lifter and the coach read
     * after the set.
     */
    @Test
    fun `the plan prompt's tempo paragraph says which figure a prescribed tempo moves the reading to`() {
        val line = prompt.lineSequence().first { "Use tempo and velocity targets deliberately" in it }
        assertTrue(
            "velocityLossRegime" in line || "controlled" in line,
            "the tempo paragraph does not say a prescribed tempo changes the headline figure: $line",
        )
        assertTrue(
            "tempoCompliance" in line,
            "the tempo paragraph does not name what a tempo set is read on instead: $line",
        )
    }
}
