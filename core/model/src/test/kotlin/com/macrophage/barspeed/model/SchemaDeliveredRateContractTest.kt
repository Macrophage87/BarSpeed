package com.macrophage.barspeed.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalSerializationApi::class)
private fun serialKeysOf(serializer: KSerializer<*>): Set<String> =
    serializer.descriptor.let { d -> (0 until d.elementsCount).map(d::getElementName).toSet() }

/**
 * What the published export says about each unit's DELIVERED frame rate, issue
 * #321.
 *
 * The contract lands in the same commit as the reds, as a deliberate contract
 * change must: [SchemaContractTest] asserts that [SetSensorsExport] and the
 * published sensors block carry EQUAL key sets, so neither side can move
 * alone. These assertions therefore pass at the commit that writes them. The
 * behaviour they describe is red in `SessionExportDeliveredRateTest` in
 * `:core:data` until the fix.
 *
 * A separate file rather than cases in [SchemaContractTest], which sits on
 * detekt's LargeClass limit, with its own private `serialKeysOf` for that
 * reason, as [SchemaUnitIdentityContractTest] keeps its own.
 */
class SchemaDeliveredRateContractTest {
    private fun schema(name: String) = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/$name")!!.readBytes().decodeToString(),
    ).jsonObject

    private fun setSensors() = schema("session-export.schema.json")["\$defs"]!!.jsonObject["setSensors"]!!.jsonObject

    private fun sensorProperties(): JsonObject = setSensors()["properties"]!!.jsonObject

    private fun exportVersionLog() = schema("session-export.schema.json")["properties"]!!
        .jsonObject["schemaVersion"]!!.jsonObject["description"]!!.jsonPrimitive.content

    private fun description(key: String) = sensorProperties()[key]!!.jsonObject["description"]!!.jsonPrimitive.content

    /** Everything the version log says from this entry's marker onward. */
    private fun entry() = exportVersionLog().substringAfter("1.23 FURTHER ENTRY (#321", missingDelimiterValue = "")

    private fun assertRoleKeyed(key: String, valueType: String) {
        val property = assertNotNull(sensorProperties()[key], "the published sensors block has no $key").jsonObject
        assertEquals("object", property["type"]!!.jsonPrimitive.content, "$key is not a role-keyed object")
        assertEquals(
            SessionExport.VALID_SENSOR_ROLES,
            property["propertyNames"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet(),
            "$key's keys are not closed to the roles the app can label",
        )
        assertEquals(
            valueType,
            property["additionalProperties"]!!.jsonObject["type"]!!.jsonPrimitive.content,
            "$key's values are the wrong type",
        )
        assertTrue(
            key !in setSensors()["required"]!!.jsonArray.map { it.jsonPrimitive.content },
            "$key cannot be required: an unroled set can never publish it",
        )
    }

    /**
     * Both keys are role-keyed objects closed to the two roles, a number and an
     * integer, and neither is required.
     *
     * ROLE-KEYED for `unitAddresses`' reason: "what did role a's link deliver"
     * is then one lookup. The value types are asserted because a rate
     * published as a string would validate against nothing a reader divides.
     */
    @Test
    fun `the published sensors block carries both delivery keys, role-keyed`() {
        assertRoleKeyed("deliveredRate_hz", "number")
        assertRoleKeyed("burstSpacing_ms", "integer")
    }

    /**
     * The Kotlin twin carries both keys under the published names, and the two
     * key sets are equal.
     */
    @Test
    fun `the export twin carries both delivery keys`() {
        val keys = serialKeysOf(SetSensorsExport.serializer())
        assertTrue("deliveredRate_hz" in keys, "SetSensorsExport cannot publish a delivered rate")
        assertTrue("burstSpacing_ms" in keys, "SetSensorsExport cannot publish a burst spacing")
        assertEquals(keys, sensorProperties().keys, "SetSensorsExport and the published sensors block disagree")
    }

    /**
     * The rate's description says DELIVERED and not sampled, names its window,
     * says no threshold is published, and points at the raw archive's
     * `sampleRate_hz` rather than restating it.
     *
     * Each is asserted because each is the sentence a reader needs to not
     * misread the number. "Delivered" is the whole difference between this key
     * and a sensor rate: a dropped frame and a slower sensor read the same
     * here. The window matters because the raw archive's rate covers the whole
     * capture. The threshold sentence stops a reader inferring that the app
     * judged the figure.
     */
    @Test
    fun `the rate's description says delivered, the window, and no threshold`() {
        val rate = description("deliveredRate_hz")
        assertTrue("DELIVERED, NOT SAMPLED" in rate, "the rate does not say it is delivered, not sampled")
        assertTrue("working window" in rate, "the rate does not name the interval it covers")
        assertTrue("rollExcursionBasis" in rate, "the rate does not point at the window the archive names")
        assertTrue("NO THRESHOLD" in rate, "the rate does not say that no threshold judges it")
        assertTrue("sampleRate_hz" in rate, "the rate does not say how it relates to the archive's rate")
        val spacing = description("burstSpacing_ms")
        assertTrue("not how often the sensor sampled" in spacing, "the spacing reads as a sample interval")
        assertTrue("median" in spacing, "the spacing does not say it is a median")
    }

    /**
     * The version log files the change as a further 1.23 entry, additive,
     * computed at export and not stored, with no database move.
     *
     * A FURTHER ENTRY and not a mint because 1.23 is unreleased: the latest
     * tag is v0.1.55, whose own SessionExport.kt reads 1.22, and no v0.1.56 tag
     * exists, all read this round.
     */
    @Test
    fun `the version log files the delivery keys as a further 1_23 entry`() {
        val entry = entry()
        assertTrue(entry.isNotEmpty(), "the version log has no 1.23 entry for #321")
        assertTrue("deliveredRate_hz" in entry, "the entry does not name the rate key")
        assertTrue("burstSpacing_ms" in entry, "the entry does not name the spacing key")
        assertTrue("ADDITIVE" in entry, "the entry does not say whether an older reader is affected")
        assertTrue("COMPUTED AT EXPORT" in entry, "the entry does not say the keys are not stored")
        assertTrue("DATABASE_VERSION does NOT move" in entry, "the entry does not say the database stays")
        assertTrue("1.23" in SessionExport.SUPPORTED_SCHEMA_VERSIONS, "the version filed under is not accepted")
    }

    /**
     * The published example carries both keys on its dual set, so `ci.yml`'s
     * ajv step validates them.
     */
    @Test
    fun `the published example carries both delivery keys on its dual set`() {
        val sets =
            schema("examples/session-export.example.json")["exercises"]!!.jsonArray
                .flatMap { it.jsonObject["sets"]!!.jsonArray }
        val dual =
            assertNotNull(
                sets.map { it.jsonObject }.firstOrNull { set ->
                    set["sensors"]?.jsonObject?.get("expected")?.jsonArray?.size == 2
                },
                "the published example has no dual set",
            ).getValue("sensors").jsonObject
        for (key in listOf("deliveredRate_hz", "burstSpacing_ms")) {
            val byRole = assertNotNull(dual[key], "the example's dual set carries no $key").jsonObject
            assertEquals(setOf("a", "b"), byRole.keys, "the example's $key does not name both roles")
        }
    }
}
