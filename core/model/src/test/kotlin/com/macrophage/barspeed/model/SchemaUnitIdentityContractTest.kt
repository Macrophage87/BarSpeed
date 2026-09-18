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
 * What the published export says about WHICH PHYSICAL UNIT carried each sensor
 * role, issue #260.
 *
 * DIFFERENTIALS. Every assertion here fails at the commit that writes it: the
 * document declares no such key, the Kotlin twin carries none, the version log
 * files no such change, and the published example names no unit. The commit
 * after this one is what makes them pass. The commit BEFORE it pinned the same
 * four facts the other way round, which is what makes these four a change
 * rather than four new claims.
 *
 * WHY THE LIFTER NEEDS IT, in the owner's own words, asked which physical unit
 * was role a on field-38: *"I'm not really sure. Will check each time, they're
 * likely to get mixed up a lot."* (2026-09-05). Roles are not positional --
 * `SensorCapturePolicy.roster` reads `roleByAddress`, the label the lifter gave
 * each paired unit -- so role a IS one fixed unit for as long as the pairing
 * stands, and the lifter holding two identical magnet-mounted WT901 units
 * cannot tell which. Every dual-unit mount inference in the corpus rests on
 * that memory.
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

    /** Everything the version log says from its LAST `1.22:` marker onward. */
    private fun entry122() = exportVersionLog().substringAfterLast("1.22:")

    /**
     * The published sensors block names the unit behind each role, keyed by the
     * role, with the addresses as free-form strings.
     *
     * ROLE-KEYED and not an array of objects, which is `silent`'s shape in this
     * same block and is what makes "role a is this unit" one lookup rather than
     * a scan with a match. `propertyNames` closes the key set to the two roles
     * the app can label, so a third key cannot appear without this pin moving.
     *
     * The VALUE is an unconstrained string on purpose. A MAC pattern here would
     * be a claim about what a Bluetooth stack hands the app -- these addresses
     * come from `BluetoothDevice.getAddress()` and nothing in this repository
     * has ever validated one -- and a document rejected for a shape a real
     * phone produced would lose the whole export over a regex.
     */
    @Test
    fun `the published sensors block names the unit behind each role`() {
        val units = assertNotNull(
            sensorProperties()["unitAddresses"],
            "the published sensors block still names no physical unit",
        ).jsonObject

        assertEquals("object", units["type"]!!.jsonPrimitive.content, "a role-keyed object, as silent is")
        assertEquals(
            setOf("a", "b"),
            units["propertyNames"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet(),
            "the key set is not closed to the roles the app can label",
        )
        assertEquals(
            "string",
            units["additionalProperties"]!!.jsonObject["type"]!!.jsonPrimitive.content,
            "an address is published as a string",
        )
        assertTrue(
            units["description"]!!.jsonPrimitive.content.isNotBlank(),
            "the published unitAddresses carries no description, which is the shape of issue #76",
        )
        assertTrue(
            "unitAddresses" !in setSensors()["required"]!!.jsonArray.map { it.jsonPrimitive.content },
            "a key no one-sensor set can publish must not be required",
        )
    }

    /**
     * The Kotlin twin carries the same key, because nothing can publish what it
     * cannot hold.
     *
     * [SchemaContractTest] asserts these two key sets are EQUAL, so neither
     * side can move alone: that assertion is what turns "add a field to
     * `SetSensorsExport`" into a contract change that has to move the document
     * in the same commit, and this is where the reason is written down.
     */
    @Test
    fun `the export twin carries the unit key too`() {
        assertTrue(
            "unitAddresses" in serialKeysOf(SetSensorsExport.serializer()),
            "SetSensorsExport cannot publish which unit carried a role",
        )
        assertEquals(
            serialKeysOf(SetSensorsExport.serializer()),
            sensorProperties().keys,
            "SetSensorsExport and the published sensors block disagree on keys",
        )
    }

    /**
     * The version log files the change as the 1.22 MINT, states it is additive,
     * states which direction a validator breaks in, and states the one thing a
     * reader must not assume: the addresses are the pairing read AT EXPORT
     * TIME, not a fact recorded with the set.
     *
     * A MINT and not a further 1.21 entry, which reverses what this test
     * asserted while it was drafted: `git tag --sort=-creatordate | head -1` is
     * v0.1.54 and `git show v0.1.54:core/model/.../SessionExport.kt` reads
     * `SCHEMA_VERSION = "1.21"`, both read at the tag this round rather than
     * relayed. 1.21 has SHIPPED, so it takes no further entries, and the
     * marker this file reads moved with it.
     *
     * The REJECTION is asserted because it is what a mint costs and a further
     * entry does not: `schemaVersion` is a closed enum, so the 1.21 schema
     * already in the field refuses a 1.22 document on the version string before
     * it reaches a key.
     *
     * THE TIP LITERAL LIVES HERE NOW, on the rule
     * `SchemaSkippedSetContractTest` wrote when it minted 1.21: the literal the
     * exporter writes is asserted in the file that MINTS it and nowhere else,
     * so that file now pins its own filed version and this one pins the tip.
     *
     * The export-time caveat is asserted and not merely written because it is
     * the whole difference between this key and a column: nothing in
     * `RawStreamEntity` or `SetRecordEntity` stores the address a capture came
     * from, so a reader who takes this for a recorded fact would attribute a
     * capture to whichever unit happens to hold the label today.
     */
    @Test
    fun `the version log files the unit identity as the additive 1_22 mint`() {
        val entry = entry122()
        assertTrue("unitAddresses" in entry, "the version log does not file the unit-identity change")
        assertTrue(
            "ADDITIVE" in entry.uppercase(),
            "the log does not say whether a 1.21 reader written before this key is affected",
        )
        assertTrue(
            "REJECTS" in entry.uppercase(),
            "the log does not say the 1.21 schema in the field refuses a 1.22 document",
        )
        assertEquals("1.22", SessionExport.SCHEMA_VERSION, "the version the exporter writes")
        assertTrue("1.22" in SessionExport.SUPPORTED_SCHEMA_VERSIONS, "the version written is not accepted")
        assertTrue("1.21" in SessionExport.SUPPORTED_SCHEMA_VERSIONS, "1.21 left the accepted set")
        assertTrue(
            "export time" in entry,
            "the log does not say the addresses are the pairing as it stands at export time",
        )
    }

    /**
     * The published example carries the key, so `ci.yml`'s ajv step actually
     * validates it.
     *
     * A declared key no example exercises is a key ajv never sees: the schema
     * step would pass on a document shape nothing has ever been checked
     * against. Asserted over the example's own dual set -- the one whose
     * `sensors` block declares two roles -- because that is the only set an
     * `unitAddresses` may appear on.
     */
    @Test
    fun `the published example names both units on its dual set`() {
        val sets =
            schema("examples/session-export.example.json")["exercises"]!!.jsonArray
                .flatMap { it.jsonObject["sets"]!!.jsonArray }
        val dual =
            assertNotNull(
                sets.map { it.jsonObject }.firstOrNull { set ->
                    set["sensors"]?.jsonObject?.get("expected")?.jsonArray?.size == 2
                },
                "the published example has no dual set, so nothing validates this key",
            )
        val units =
            assertNotNull(
                dual.getValue("sensors").jsonObject["unitAddresses"],
                "the example's dual set names no unit, so ajv never sees this key",
            ).jsonObject

        assertEquals(setOf("a", "b"), units.keys, "the example names one unit, not the pair")
        units.forEach { (role, address) ->
            assertTrue(
                address.jsonPrimitive.content.isNotBlank(),
                "the example publishes a blank address for role $role",
            )
        }
    }
}
