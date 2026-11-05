package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What #242 and #287 change about what the published export SAYS: the
 * analysis can now move off an armed unit that delivered enough frames, when
 * it stopped early beside its partner or never moved while its partner did.
 *
 * Pinned against the real document in `docs/schemas/`, as
 * [SchemaShortDeliveryContractTest] pins #209's widening of the same three
 * keys. That file's absences still hold and are not restated here; this one
 * asserts the two new causes are said where a reader looks for why a set's
 * analysis moved, and that the version log files them once under 1.26.
 */
class SchemaSensorCoverageContractTest {
    private fun schema() = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/session-export.schema.json")!!.readBytes().decodeToString(),
    ).jsonObject

    private fun sensorDescription(name: String) = schema()["\$defs"]!!.jsonObject["setSensors"]!!
        .jsonObject["properties"]!!.jsonObject[name]!!.jsonObject["description"]!!.jsonPrimitive.content

    private fun exportVersionLog() = schema()["properties"]!!
        .jsonObject["schemaVersion"]!!.jsonObject["description"]!!.jsonPrimitive.content

    private val shippedPrompt: String =
        checkNotNull(
            javaClass.getResourceAsStream("/kotlin/com/macrophage/barspeed/ui/screens/GuideScreen.kt"),
        ) {
            "GuideScreen.kt is not on the test classpath - see the include filter in core/model/build.gradle.kts"
        }.readBytes().decodeToString()

    private val marker = "1.26 (#242, #287, a unit that cannot stand for the set beside its partner)"

    @Test
    fun `analysedFellBack names all three causes`() {
        val d = sensorDescription("analysedFellBack")
        for (fact in listOf("THREE CAUSES", "STOPPED EARLY", "NEVER MOVED", "0.01 g", "one second")) {
            assertTrue(fact in d, "analysedFellBack does not state: $fact")
        }
    }

    @Test
    fun `the fallback basis and silent say what they now cover`() {
        val basis = sensorDescription("analysedRoleBasis")
        assertTrue("stopped early" in basis && "never moved" in basis, "fallback does not cover the two causes")
        assertTrue("never the one a `stackSignature` names" in basis, "a still unit's signature is not ruled out")
        assertTrue("stopped early beside its partner" in sensorDescription("silent"), "silent: the early stop")
    }

    @Test
    fun `the 1_26 log files the coverage change once`() {
        val log = exportVersionLog()
        assertEquals(1, log.split(marker).size - 1, "the #242/#287 entry is not filed exactly once")
        val entry = log.substringAfter(marker).substringBefore(" 1.26 (")
        for (fact in listOf("NOT RETROACTIVE", "DATABASE_VERSION does NOT move", "94 ms", "0.0374 g")) {
            assertTrue(fact in entry, "the #242/#287 entry does not state: $fact")
        }
    }

    @Test
    fun `the shipped prompt says the analysis also moves off an early stop or a still unit`() {
        assertTrue(
            "also off a unit that stopped early beside its partner, or never moved while its partner did" in
                shippedPrompt,
            "the coach is not told the two new causes",
        )
    }
}
