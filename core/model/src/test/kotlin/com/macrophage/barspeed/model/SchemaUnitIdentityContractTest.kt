package com.macrophage.barspeed.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull

@OptIn(ExperimentalSerializationApi::class)
private fun serialKeysOf(serializer: KSerializer<*>): Set<String> =
    serializer.descriptor.let { d -> (0 until d.elementsCount).map(d::getElementName).toSet() }

/**
 * What the published export says about WHICH PHYSICAL UNIT carried each sensor
 * role, issue #260.
 *
 * CHARACTERIZATION, at this commit: it says nothing. The block names roles `a`
 * and `b` and the lifter, holding two identical WT901 units, cannot tell which
 * one either name refers to -- *"I'm not really sure. Will check each time,
 * they're likely to get mixed up a lot."* (owner, 2026-09-05, on which unit is
 * role a). Every dual-unit mount inference in the corpus therefore rests on
 * the owner's memory of which unit went where.
 *
 * These assertions are the BEFORE side. The commit that flips them is the
 * differential, and the one after that is what makes them pass.
 *
 * A separate file rather than cases in [SchemaContractTest], which sits on
 * detekt's LargeClass limit, and rather than in [SchemaSensorContractTest],
 * whose subject is what #198 moved. Its own private `serialKeysOf`, as
 * [SchemaArmedSilenceContractTest] keeps its own, for that same reason.
 */
class SchemaUnitIdentityContractTest {
    private fun schema(name: String) = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/$name")!!.readBytes().decodeToString(),
    ).jsonObject

    private fun setSensors() = schema("session-export.schema.json")["\$defs"]!!.jsonObject["setSensors"]!!.jsonObject

    private fun sensorProperties(): JsonObject = setSensors()["properties"]!!.jsonObject

    private fun exportVersionLog() = schema("session-export.schema.json")["properties"]!!
        .jsonObject["schemaVersion"]!!.jsonObject["description"]!!.jsonPrimitive.content

    /**
     * The published sensors block declares no key naming a unit.
     *
     * `$defs.setSensors` is `additionalProperties: false`, so this is not a
     * gap a writer could fill unilaterally: until the document declares the
     * key, an export carrying it is INVALID against the contract its consumer
     * was pointed at.
     */
    @Test
    fun `the published sensors block names no physical unit`() {
        assertNull(
            sensorProperties()["unitAddresses"],
            "the published sensors block already names the units, so this pin is not the before side",
        )
    }

    /**
     * The Kotlin twin carries no such key either, which is why the export
     * cannot publish one.
     *
     * [SchemaContractTest] asserts these two key sets are EQUAL, so neither
     * side can move alone: that is what makes adding a field here a contract
     * change rather than an addition.
     */
    @Test
    fun `the export twin carries no unit key`() {
        assertFalse(
            "unitAddresses" in serialKeysOf(SetSensorsExport.serializer()),
            "SetSensorsExport already carries a unit key, so this pin is not the before side",
        )
    }

    /**
     * And the version log records no such change.
     *
     * The log is the only place a reader learns what a version number means,
     * and a key published without an entry is a key nobody downstream can
     * date.
     */
    @Test
    fun `the version log records no unit identity yet`() {
        assertFalse(
            "unitAddresses" in exportVersionLog(),
            "the version log already files a unit-identity change, so this pin is not the before side",
        )
    }
}
