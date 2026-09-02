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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalSerializationApi::class)
private fun serialKeysOf(serializer: KSerializer<*>): Set<String> =
    serializer.descriptor.let { d -> (0 until d.elementsCount).map(d::getElementName).toSet() }

/**
 * What issue #215 moves in the published session export: `side` stops being a
 * copy of the prescription, and `plannedSide` arrives beside it to carry what
 * the plan asked for.
 *
 * DIFFERENTIALS. Every assertion here fails at the commit that introduces it:
 * [SetExport] has no such field, the published set object declares no such
 * key, the version log has no entry for it, the published description of
 * `side` still says only "which side was trained", and no set in the example
 * payload shows a lifter working the other arm.
 *
 * A file of its own rather than more cases in [SchemaContractTest], for
 * [SchemaArmedSilenceContractTest]'s reason: that file is already over a
 * thousand lines and its cases belong to the contract as a whole, while these
 * belong to one issue and say what that issue changes about what the document
 * MEANS.
 *
 * The version does NOT move. 1.17 is unreleased -- v0.1.48 ships 1.16, which
 * #207's own log entry records reading at that tag -- so this is a FURTHER
 * entry under it and not a mint, the rule the 1.13, 1.14 and 1.16 entries each
 * applied while they were open. The constant, the accepted set and the absence
 * of a 1.18 are asserted UNCHANGED rather than left inferred.
 *
 * The PLAN contract does not move either, and that is worth an assertion of
 * its own: a plan has declared `side` per set since long before this, and what
 * this change records is the side the LIFTER worked. A `plannedSide` in the
 * plan document would be the plan telling itself what it already said.
 */
class SchemaWorkedSideContractTest {
    private fun schema(name: String) = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/$name")!!.readBytes().decodeToString(),
    ).jsonObject

    private fun example(name: String) = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/examples/$name")!!.readBytes().decodeToString(),
    ).jsonObject

    private fun setObject() = schema("session-export.schema.json").getValue("\$defs").jsonObject
        .getValue("set").jsonObject

    private fun setProperty(name: String) = setObject().getValue("properties").jsonObject[name]?.jsonObject

    private fun enumOf(property: JsonObject) =
        property.getValue("enum").jsonArray.map { it.jsonPrimitive.content }.toSet()

    private fun exportVersionLog() = schema("session-export.schema.json").getValue("properties")
        .jsonObject.getValue("schemaVersion").jsonObject.getValue("description").jsonPrimitive.content

    /** Everything the version log says from its LAST `1.17:` marker onward. */
    private fun entry117() = exportVersionLog().substringAfterLast("1.17:")

    /**
     * The Kotlin twin can publish the pair.
     *
     * The load-bearing half is that this is a PAIR. `side` alone could not say
     * a lifter had deviated -- it was a copy of the plan's own declaration, so
     * it agreed with the prescription by construction -- and a `side` that now
     * carries the worked arm WITHOUT a prescription beside it would lose the
     * prescription instead, which is the same defect pointing the other way.
     */
    @Test
    fun `the export twin carries the prescribed side beside the worked one`() {
        val keys = serialKeysOf(SetExport.serializer())
        assertTrue("side" in keys, "SetExport stopped publishing the side the set worked")
        assertTrue("plannedSide" in keys, "SetExport cannot publish the side the plan asked for")
    }

    /**
     * The published set declares `plannedSide`, closed over the same two words
     * `side` is closed over, and it is NOT required.
     *
     * Not required because absence has to keep meaning what it means: a
     * bilateral set, an ad-hoc set, an appended set -- none of which was
     * prescribed a side -- and every set recorded before database v14. A
     * required key would invalidate every export already in a coach's hands.
     */
    @Test
    fun `the published set declares the prescribed side, closed and optional`() {
        val planned = assertNotNull(setProperty("plannedSide"), "the published set declares no plannedSide")

        assertEquals(PlanFile.VALID_SIDES, enumOf(planned), "plannedSide accepts words the plan cannot write")
        assertEquals(
            SideChoicePolicy.CHOICES.toSet(),
            enumOf(planned),
            "the published prescription and the words the control offers disagree",
        )
        assertTrue(
            planned["description"]?.jsonPrimitive?.content?.isNotBlank() == true,
            "the published plannedSide carries no description, which is the shape of issue #76",
        )
        assertFalse(
            "plannedSide" in setObject().getValue("required").jsonArray.map { it.jsonPrimitive.content },
            "plannedSide is required, which invalidates every export already written",
        )
    }

    /**
     * The published `side` says which arm was WORKED, and says plainly that it
     * could not before.
     *
     * This is the half a reader gets wrong silently. The key did not change
     * type and did not stop being written, so nothing in a validator will tell
     * a coach that the same key now answers a different question on newer
     * documents -- it has to be written where they will read it.
     */
    @Test
    fun `the published side tells a reader it is the arm worked and was not always`() {
        val side = assertNotNull(setProperty("side"))
        val description = side.getValue("description").jsonPrimitive.content

        assertEquals(PlanFile.VALID_SIDES, enumOf(side), "side accepts words the plan cannot write")
        assertTrue("worked" in description, "the published side never says it is the arm the lifter worked")
        assertTrue("plannedSide" in description, "the published side does not point at its own pair")
        assertTrue("#215" in description, "the published side does not say which change moved it")
    }

    /**
     * The version stands still and 1.17 gains a further entry.
     *
     * Entries already ride under 1.17 for #207, #205, #213 and #223, and this
     * is another. The number may still take entries because no tag carries it;
     * that is the same rule, and the 1.15 and 1.17 entries each record getting
     * it wrong once, in opposite directions.
     */
    @Test
    fun `the export version stands still and 1_17 gains a further entry`() {
        assertEquals("1.17", SessionExport.SCHEMA_VERSION, "the export version moved; #215 must not mint one")
        assertTrue("1.17" in SessionExport.SUPPORTED_SCHEMA_VERSIONS, "1.17 left the accepted set")
        assertFalse("1.18" in SessionExport.SUPPORTED_SCHEMA_VERSIONS, "a version nobody minted reached the app")

        val entry = entry117()
        assertTrue("#215" in entry, "the 1.17 entry does not name the issue this change came from")
        assertTrue("plannedSide" in entry, "the 1.17 entry never names the key it added")
        assertTrue(
            "additive" in entry,
            "the 1.17 entry does not tell a reader whether it has to change anything",
        )
    }

    /**
     * The plan contract does not move.
     *
     * #215 changes what is RECORDED, not what a coach may write. Pinned
     * because the two vocabularies are one word apart and a change made in the
     * wrong document would mint a plan version for nothing -- 1.11 is this
     * branch's mint already, for #214's `progression` key.
     */
    @Test
    fun `the plan contract is untouched by a recorded side`() {
        assertEquals("1.11", PlanFile.SCHEMA_VERSION, "the plan version moved for a change to the record")
        val planSet = schema("plan.schema.json").getValue("\$defs").jsonObject.getValue("set").jsonObject
        assertFalse(
            "plannedSide" in planSet.getValue("properties").jsonObject.keys,
            "the plan document gained a key that belongs to the record",
        )
    }

    /**
     * The example payload shows a set worked on the other arm.
     *
     * A description a reader can misread is worth less than a payload they can
     * run a validator over, and the ajv step in CI validates this file against
     * the schema this same commit moves -- `additionalProperties` is false on
     * a set, so an example carrying the key without the schema declaring it
     * fails there rather than here.
     *
     * The pair shown is the one this change exists for: one set on the arm the
     * plan asked for, and one where the lifter swapped -- indistinguishable
     * from each other in every export written before this version.
     */
    @Test
    fun `the published example shows a set worked on the arm the plan did not ask for`() {
        val sets =
            example("session-export.example.json").getValue("exercises").jsonArray
                .flatMap { it.jsonObject.getValue("sets").jsonArray }
                .map { it.jsonObject }
        val unilateral = sets.filter { it["side"] != null }

        assertTrue(unilateral.size >= 2, "the example shows fewer than two unilateral sets")
        assertTrue(
            unilateral.all { it["plannedSide"] != null },
            "a unilateral set in the example publishes no prescription to read its side against",
        )
        assertEquals(
            1,
            unilateral.count { it.getValue("side") != it.getValue("plannedSide") },
            "the example shows no set worked on the arm the plan did not ask for, which is the whole change",
        )
        assertTrue(
            unilateral.any { it.getValue("side") == it.getValue("plannedSide") },
            "the example shows only the deviation, so a reader cannot see what agreement looks like",
        )
        assertTrue(
            unilateral.all { it.getValue("side").jsonPrimitive.content in SideChoicePolicy.CHOICES },
            "the example publishes a side the app cannot record",
        )
    }
}
