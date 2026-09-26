package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Plan schema 1.14: what the PUBLISHED plan schema says a WRITTEN
 * `sensorOnStack` true does to the stack inversion (#327).
 *
 * 1.13 shipped in v0.1.56 saying the analysed unit's roll decides the stack
 * inversion under a written `sensorOnStack` true as well as under the stack
 * default. The owner's answer on #327 -- "It still rolls on the stack." -- is
 * that a unit clipped to the weight stack still shows roll, so a roll check
 * must not overrule a plan that wrote the stack mount. Redefining a released
 * version is not available, so the change is 1.14 and the 1.13 entry stays as
 * what 1.13 said; `PlanStackInversionContractTest` still pins it.
 *
 * A class of its own for the reason `PlanStackInversionContractTest` gives:
 * [SchemaContractTest] sits on detekt's `LargeClass` limit. That class's
 * relational pins -- the published enum equals
 * `PlanFile.SUPPORTED_SCHEMA_VERSIONS`, the published example declares
 * `PlanFile.SCHEMA_VERSION` -- move with the constants and need no edit.
 */
class PlanWrittenStackContractTest {
    private fun plan(): JsonObject = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/plan.schema.json")!!.readBytes().decodeToString(),
    ).jsonObject

    private fun versionLog(): String = plan()["properties"]!!.jsonObject["schemaVersion"]!!
        .jsonObject["description"]!!.jsonPrimitive.content

    private fun property(key: String): String = plan()["\$defs"]!!.jsonObject["exercise"]!!
        .jsonObject["properties"]!!.jsonObject[key]!!.jsonObject["description"]!!.jsonPrimitive.content

    private fun document(version: String): String {
        val text =
            """
            {"schemaVersion":"$version","planName":"P","sessions":[{"name":"S","exercises":[
              {"exercise":"rope_pushdown","concentric":"down","sensorOnStack":true,"sets":[{"reps":12,"tempo":"1120"}]}
            ]}]}
            """.trimIndent()
        return text
    }

    /**
     * The version the app writes, pinned beside the mint that moved it.
     *
     * Moved here from `PlanStackInversionContractTest`, whose copy naming
     * 1.13 is deleted rather than carried forward, for the reason that class
     * gave: a bump left half-done reds here rather than shipping a prompt
     * asking for a version the app does not write.
     */
    @Test
    fun `the app writes plan schema 1_14`() {
        assertEquals("1.14", PlanFile.SCHEMA_VERSION)
    }

    /**
     * 1.14 is accepted, and so are the two versions before it: 1.12 is the
     * newest plan version at tags v0.1.51 and v0.1.55 and 1.13 at v0.1.56,
     * read at the tags, so plans written against either can be in a lifter's
     * hands. Each document here writes the stack mount and omits
     * the inversion, the shape 1.14 is about, so the gate is asked about the
     * document the change reaches.
     */
    @Test
    fun `the import gate accepts 1_12, 1_13 and 1_14`() {
        for (version in listOf("1.12", "1.13", "1.14")) {
            assertTrue(version in PlanFile.SUPPORTED_SCHEMA_VERSIONS, "$version is not an accepted plan version")
            val result = PlanImport.parse(document(version))
            assertEquals(emptyList(), result.errors, "a $version document is refused")
        }
    }

    /**
     * The 1.14 entry states the rule, keeps the roll check for the stack
     * default, names its cost, and says why 1.13 was not edited.
     */
    @Test
    fun `the plan's 1_14 entry states that a written stack mount is not roll-checked`() {
        val entry = versionLog().substringAfter("1.14:", missingDelimiterValue = "")
        assertTrue(entry.isNotEmpty(), "the plan version log has no 1.14 entry")
        assertTrue(
            "Where a plan WRITES `sensorOnStack` true, the stack inversion the 1.13 entry states stands on every " +
                "set of that exercise without consulting the analysed unit's roll" in entry,
            "the 1.14 entry never says a written stack mount keeps the inversion whatever the roll",
        )
        assertTrue(
            "now applies only where `sensorOnStack` is absent or null and the stack mount is the stack " +
                "default" in entry,
            "the 1.14 entry never says the roll check still applies to a stack mount left to the default",
        )
        assertTrue(
            "has that unit read inverted, with drive and return swapped, and nothing at the end of the set " +
                "takes it back" in entry,
            "the 1.14 entry never states what a written true costs on a unit clipped to the handle or the rope",
        )
        assertTrue(
            "1.13 SHIPPED in v0.1.56" in entry && "the 1.13 entry above stays as what 1.13 said" in entry,
            "the 1.14 entry never says why 1.13 was not edited in place",
        )
    }

    /**
     * Both geometry properties send a plan writer to the 1.14 entry, and
     * `sensorInverted` no longer says an omitted key resolves true only on a
     * unit whose roll says stack, which 1.14 makes false under a written
     * stack mount.
     */
    @Test
    fun `sensorInverted and sensorOnStack point at the 1_14 entry`() {
        val inverted = property("sensorInverted")
        assertTrue(
            "From 1.14 that holds on every set where `sensorOnStack` is written true, whatever the analysed " +
                "unit's roll" in inverted,
            "sensorInverted never says a written stack mount keeps the inversion whatever the roll",
        )
        assertTrue(
            "what a written `sensorOnStack` true changes about it in the 1.14 entry" in inverted,
            "sensorInverted does not point at the 1.14 entry",
        )
        assertTrue(
            "the stack inversion then stands without the analysed unit's roll being consulted, as the 1.14 " +
                "entry states" in property("sensorOnStack"),
            "sensorOnStack does not say what writing it true now means for the inversion",
        )
    }
}
