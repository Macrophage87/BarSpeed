package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the published export says about WHICH detector counts a sensor set,
 * once issue #305 arms the full-cycle counter.
 *
 * #302 rewrote four published descriptions -- `repsSource`'s reading key,
 * `liveReps`, `countTrusted` and `repMetricsComplete` -- and their copies in
 * `PLAN_PROMPT` around the drive-impulse detector v0.1.54 armed. #305 arms a
 * different detector, so each of those sentences goes false for every set a
 * build carrying #305 records. These pins say what each copy must state now,
 * and that the sentence that went false is gone.
 *
 * DIFFERENTIALS, RED at the commit that writes this file: the documents still
 * describe the drive-impulse detector as the current one, and none of them
 * names the full-cycle detector. The commit after it corrects the published
 * schema, its version log, the Kotlin twin's KDoc and `PLAN_PROMPT` together.
 *
 * WHERE THE FIGURES COME FROM, because `:core:model` cannot see `:core:dsp`'s
 * corpus: 5, 5, 5, 4 and 2 on field-44 and the 7, 8, 6, 10, 9 and 2 on the
 * overhead presses are `CycleLiveCountFieldTest`'s, replaying the captures
 * through the counter the app arms; the median 1.16 s behind "about 1.2 s" is
 * `ClosingRuleCandidateTest`'s per-rep table. Nothing mechanical compares the
 * two modules; what this file enforces is that the schema and the prompt the
 * coach receives say the same thing.
 */
class SchemaCycleCounterContractTest {
    private fun schema() = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/session-export.schema.json")!!.readBytes().decodeToString(),
    ).jsonObject

    private fun description(name: String) = schema()["\$defs"]!!.jsonObject["set"]!!.jsonObject["properties"]!!
        .jsonObject[name]!!.jsonObject["description"]!!.jsonPrimitive.content

    private fun versionLog() =
        schema()["properties"]!!.jsonObject["schemaVersion"]!!.jsonObject["description"]!!.jsonPrimitive.content

    private val prompt: String =
        checkNotNull(
            javaClass.getResourceAsStream("/kotlin/com/macrophage/barspeed/ui/screens/GuideScreen.kt"),
        ) { "GuideScreen.kt is not on the test classpath - see the include filter in core/model/build.gradle.kts" }
            .readBytes().decodeToString()

    private fun assertStates(name: String, text: String, phrases: List<String>) = phrases.forEach {
        assertTrue(it.lowercase() in text.lowercase(), "$name does not state: $it")
    }

    private fun assertDropped(name: String, text: String, phrases: List<String>) = phrases.forEach {
        assertFalse(it.lowercase() in text.lowercase(), "$name still states: $it")
    }

    /**
     * The reading key, in both copies: the detector that counts from v0.1.55,
     * when it speaks, what it has been scored on, and that the drive-impulse
     * figures now describe a v0.1.54 recording.
     */
    @Test
    fun `both copies of the reading key name the full-cycle detector and its score`() {
        mapOf("the published schema" to description("repsSource"), "the plan prompt" to prompt).forEach { (n, t) ->
            assertStates(
                n,
                t,
                listOf(
                    "from v0.1.55",
                    "full-cycle detector",
                    "about 1.2 s after the pull ends",
                    "a rep late",
                    "5, 5, 5, 4 and 2 of 5, 5, 5, 4 and 2",
                    "failed third pull",
                    "set-up pull",
                    "7, 8, 6, 10, 9 and 2 against hand counts of 6, 7, 5, 8, 8 and 2",
                    "recorded by v0.1.54",
                ),
            )
        }
    }

    /**
     * NEITHER COPY STILL CALLS THE DRIVE-IMPULSE DETECTOR THE CURRENT ONE, and
     * neither states its heavy-set warning without saying which recordings it
     * is about. Pinned as absences as well as presences, because a rewording
     * that added the new detector and kept "from v0.1.54 it is" would satisfy
     * the test above and still tell a coach the wrong detector counted.
     */
    @Test
    fun `neither copy of the reading key still calls the impulse detector the current one`() {
        mapOf("the published schema" to description("repsSource"), "the plan prompt" to prompt).forEach { (n, t) ->
            assertDropped(
                n,
                t,
                listOf(
                    "From v0.1.54 it is a drive-impulse detector's count",
                    "from v0.1.54 the live count is a drive-impulse detector",
                    "So a low or zero count on a heavy set is more likely a miss",
                ),
            )
        }
    }

    /** `liveReps` names the detector a v0.1.55 recording was counted by, and scopes the impulse one to v0.1.54. */
    @Test
    fun `the published live count names the full-cycle detector`() {
        val d = description("liveReps")
        assertStates("liveReps", d, listOf("full-cycle detector", "v0.1.55", "recorded by v0.1.54"))
        assertDropped("liveReps", d, listOf("A set recorded by v0.1.54 or later was counted live by a drive-impulse"))
    }

    /** `countTrusted`'s NOT ABOUT THE COUNT names the detector that counts, in both copies. */
    @Test
    fun `countTrusted names the full-cycle detector in both copies`() {
        val d = description("countTrusted")
        assertStates("countTrusted", d, listOf("full-cycle detector"))
        assertDropped(
            "countTrusted",
            d,
            listOf("from v0.1.54 a sensor-counted set is counted by a drive-impulse detector with no velocity in it"),
        )
        assertStates("the plan prompt", prompt, listOf("\"countTrusted\""))
        assertDropped("the plan prompt", prompt, listOf("the count comes from a separate drive-impulse detector"))
    }

    /** `repMetricsComplete`'s caveat names what the live detector reads from v0.1.55. */
    @Test
    fun `the completeness caveat names the full cycle`() {
        val d = description("repMetricsComplete")
        assertStates("repMetricsComplete", d, listOf("two different detectors", "a full cycle from v0.1.55"))
        assertDropped("repMetricsComplete", d, listOf("the live one reads a drive impulse, not a velocity"))
    }

    /**
     * The version log files the correction under the unreleased 1.22, once,
     * says no key moves and the database stays put.
     *
     * The marker is matched without its ordinal, because another lane may file
     * a further 1.22 entry first; the issue tag and the subject identify it.
     * No `1.22:` marker is used -- `SchemaUnitIdentityContractTest` reads
     * everything after the LAST one.
     */
    @Test
    fun `the version log files the correction once under 1_22`() {
        val log = versionLog()
        val marker = Regex("1\\.22 TAKES A \\w+ ENTRY \\(#305, the live count's descriptions\\)")
        assertEquals(1, marker.findAll(log).count(), "the entry is not filed exactly once")
        val entry = log.substring(marker.find(log)!!.range.first)
        val facts = listOf("CHANGES NO KEY", "full-cycle detector", "DATABASE_VERSION does NOT move")
        assertStates("the entry", entry, facts)
        assertEquals("1.22", SessionExport.SCHEMA_VERSION, "the version this entry extends is not the one written")
    }
}
