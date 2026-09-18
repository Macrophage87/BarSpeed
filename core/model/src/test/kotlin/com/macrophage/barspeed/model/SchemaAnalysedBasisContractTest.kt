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
 * What the published export says about WHY a set's figures came from the role
 * they came from, issue #278.
 *
 * DIFFERENTIALS. `the Kotlin twin carries the same key` and `the published
 * vocabulary is the enum the app decides with` both FAIL at the commit that
 * writes this file: the document declares the key and `SetSensorsExport` does
 * not. The two that read only the document pass here, and they are in this
 * commit rather than the next so that a later change cannot quietly reword the
 * published description without a test naming what it said. The commit after
 * this one adds the Kotlin field and the wiring.
 *
 * WHY THE LIFTER NEEDS IT. On a set that declares `sensorOnStack` and records
 * two units, nothing declares where the SECOND unit was mounted, so the
 * declaration was applied to whichever unit the set armed. Field-42's three
 * seated cable rows armed the unit on the rotating handle and published 3, 4
 * and 2 reps of the 8 performed. Once the app chooses by each unit's measured
 * roll, a reader has to be able to tell "the rule chose this unit" from "the
 * rule declined" from "the armed unit went quiet" -- three different statements
 * about how far a set's figures can be trusted, and no other key separates
 * them.
 *
 * A separate file rather than cases in `SchemaContractTest`, which sits on
 * detekt's `LargeClass` limit, and with its own private `serialKeysOf` as
 * `SchemaUnitIdentityContractTest` and `SchemaArmedSilenceContractTest` each
 * keep theirs.
 */
class SchemaAnalysedBasisContractTest {
    private fun schema(name: String) = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/$name")!!.readBytes().decodeToString(),
    ).jsonObject

    private fun setSensors() = schema("session-export.schema.json")["\$defs"]!!.jsonObject["setSensors"]!!.jsonObject

    private fun sensorProperties(): JsonObject = setSensors()["properties"]!!.jsonObject

    private fun exportVersionLog() = schema("session-export.schema.json")["properties"]!!
        .jsonObject["schemaVersion"]!!.jsonObject["description"]!!.jsonPrimitive.content

    /** The published key exists, is a string, and is closed to the three words the app decides with. */
    @Test
    fun `the published sensors block says why the analysed role is what it is`() {
        val basis = assertNotNull(sensorProperties()["analysedRoleBasis"], "no analysedRoleBasis in the schema")
            .jsonObject

        assertEquals("string", basis["type"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("declared", "stackSignature", "fallback"),
            basis["enum"]!!.jsonArray.map { it.jsonPrimitive.content },
            "the published vocabulary",
        )
        assertTrue(
            "analysedRoleBasis" !in setSensors()["required"]!!.jsonArray.map { it.jsonPrimitive.content },
            "a key no earlier set can carry was made required",
        )
    }

    /**
     * THE DIFFERENTIAL. The Kotlin type the exporter serialises carries the key
     * the document declares. `SchemaContractTest` asserts the two key sets are
     * equal and reds from the other side at this same commit; this names the one
     * key, so a failure says which.
     */
    @Test
    fun `the Kotlin twin carries the same key`() {
        assertTrue(
            "analysedRoleBasis" in serialKeysOf(SetSensorsExport.serializer()),
            "SetSensorsExport does not publish the basis the schema declares",
        )
    }

    /**
     * THE SECOND DIFFERENTIAL, and it is not the first one twice: the published
     * enum and the enum the app DECIDES with have to be the same three words in
     * the same order, or the document describes a vocabulary nothing emits.
     */
    @Test
    fun `the published vocabulary is the enum the app decides with`() {
        val published = sensorProperties()["analysedRoleBasis"]!!.jsonObject["enum"]!!
            .jsonArray.map { it.jsonPrimitive.content }

        assertEquals(AnalysedRoleBasis.entries.map { it.published }, published)
        assertTrue(
            "analysedRoleBasis" in serialKeysOf(SetSensorsExport.serializer()),
            "the vocabulary agrees but nothing serialises it",
        )
    }

    /**
     * The version log files the change under 1.22 as a SECOND entry, and says
     * the database does not move with it.
     *
     * A SECOND 1.22 entry and not a third 1.21 one: v0.1.54 ships 1.21 -- `git
     * show v0.1.54:core/model/.../SessionExport.kt` reads `SCHEMA_VERSION =
     * "1.21"`, read at the tag this round -- so 1.21 takes no further entries
     * and #260's key minted 1.22 ahead of this one. Everything here that said
     * 1.21 and THIRD is corrected rather than reworded.
     *
     * The `1.22:` marker is not repeated -- `SchemaUnitIdentityContractTest`
     * reads everything after the LAST `1.22:` marker, so a new marker would
     * silently narrow what that file inspects. That is why this entry opens
     * `1.22 TAKES A SECOND ENTRY` with no colon after the number.
     *
     * The tip literal appears in two files and they guard different things:
     * `SchemaUnitIdentityContractTest` pins it because that file MINTS 1.22,
     * and the equality below pins that this entry extends the number the app
     * actually writes, which is what fires if a later mint leaves this entry
     * behind.
     */
    @Test
    fun `the version log files this as a second entry under an unreleased number`() {
        val log = exportVersionLog()
        val entry = log.substringAfterLast("1.22:")

        assertTrue("1.22 TAKES A SECOND ENTRY (#278)" in entry, "the log does not file this change")
        assertTrue("analysedRoleBasis" in entry, "the log names no key")
        assertTrue("DATABASE_VERSION does NOT move" in entry, "the log does not say the database stays put")
        assertEquals(1, Regex("1\\.22 TAKES A SECOND ENTRY").findAll(log).count(), "the entry is filed twice")
        assertEquals(
            "1.22",
            SessionExport.SCHEMA_VERSION,
            "the version this entry extends is not the one the app writes",
        )
    }

    /**
     * The published example carries the key, so `ci.yml`'s ajv step validates a
     * document that actually has one.
     *
     * `declared` rather than `stackSignature`, because the example's dual set is
     * an overhead press and declares no stack mount: an example carrying a
     * signature verdict on a barbell set would be a document the app cannot
     * produce.
     */
    @Test
    fun `the published example carries the basis on its dual set`() {
        val sets = schema("examples/session-export.example.json")["exercises"]!!
            .jsonArray.flatMap { it.jsonObject["sets"]!!.jsonArray }
        val bases = sets.mapNotNull {
            it.jsonObject["sensors"]?.jsonObject?.get("analysedRoleBasis")?.jsonPrimitive?.content
        }

        assertEquals(listOf("declared"), bases, "the example publishes no basis, so ajv validates none")
    }
}
