package com.macrophage.barspeed.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
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
 * What issue #213 moves in the published session export, and in the two Kotlin
 * shapes that have to move with it.
 *
 * DIFFERENTIALS. Every assertion here fails at the commit that introduces it:
 * neither `RecordedSensors` nor `SetSensorsExport` can say a role was armed and
 * silent, the published block declares no such key, the version log has no
 * entry for it, and the example payload carries no set that shows it.
 *
 * A fourth file rather than more cases in [SchemaContractTest],
 * [SchemaSensorContractTest] or [SchemaAnalysedFallbackContractTest], for the
 * reason the second gives for being the second and the third gives for being
 * the third: those files' cases belong to #198 and #207, and
 * [SchemaContractTest] is already over a thousand lines. What stays there is
 * the sensor block's keys, closure and role vocabulary; what is here is what
 * #213 changes about what the document SAYS.
 *
 * The version does NOT move. 1.17 is UNRELEASED on `main` -- no tag carries it,
 * and this is a FURTHER entry under it rather than a mint, which is the same
 * rule the 1.13, 1.14 and 1.16 entries each applied while they were unreleased.
 * The constant, the accepted set and the enum are asserted UNCHANGED below
 * rather than left inferred, because "do not touch the constant" is exactly the
 * kind of instruction a diff can violate silently.
 *
 * The PLAN contract does not move at all. A plan declares no sensors since
 * #198's 1.10, and whether an armed unit delivered is not something a coach
 * writes down.
 */
class SchemaArmedSilenceContractTest {
    private fun schema(name: String) = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/$name")!!.readBytes().decodeToString(),
    ).jsonObject

    private fun example(name: String) = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/examples/$name")!!.readBytes().decodeToString(),
    ).jsonObject

    private fun setSensors() = schema("session-export.schema.json")["\$defs"]!!.jsonObject["setSensors"]!!.jsonObject

    private fun sensorProperty(name: String) = setSensors()["properties"]!!.jsonObject[name]?.jsonObject

    private fun exportVersionLog() = schema("session-export.schema.json")["properties"]!!
        .jsonObject["schemaVersion"]!!.jsonObject["description"]!!.jsonPrimitive.content

    /** Everything the version log says from its LAST `1.17:` marker onward. */
    private fun entry117() = exportVersionLog().substringAfterLast("1.17:")

    /**
     * The stored declaration can say a role was armed and silent.
     *
     * On the ROW, not only in the export, and that is the load-bearing half.
     * The export is rebuilt from the row every time it is written, so a fact
     * the row cannot hold is a fact no re-export can recover -- which is
     * exactly what happened to field-37's thirteen sets, whose archives say
     * `present: ["a"]` and nothing whatever about what the app could see of
     * `b`.
     *
     * `sensorsJson` is a kotlinx blob on an existing column, so this key costs
     * NO `DATABASE_VERSION` bump, no migration, no exported schema and no
     * emulator test. That is the reason the shape was chosen; a typed column
     * would have cost all four.
     */
    @Test
    fun `the stored declaration can name a role that was armed and silent`() {
        assertTrue(
            "silent" in serialKeysOf(RecordedSensors.serializer()),
            "RecordedSensors cannot say a role was armed and silent, so no export can publish it",
        )
    }

    /**
     * A declaration that named no silent role does not carry the key at all.
     *
     * The repository encodes `sensorsJson` with kotlinx's default
     * `encodeDefaults = false`, so an empty map never reaches the column, and
     * absence has to keep meaning what it means on every row written before
     * this key existed. It does: no build before this one could observe an
     * armed unit's delivery, so a row that says nothing here is a row that was
     * never asked. The same rule `analysedFellBack` follows.
     */
    @Test
    fun `an ordinary declaration stores no silence at all`() {
        val wire = Json { ignoreUnknownKeys = true }
        val stored =
            wire.encodeToString(
                RecordedSensors.serializer(),
                RecordedSensors(count = 2, expected = listOf(SensorRole.A, SensorRole.B), analysed = SensorRole.A),
            )

        assertFalse("silent" in stored, "a set whose units both delivered stored a silence key: $stored")
        assertTrue(
            "silent" in serialKeysOf(RecordedSensors.serializer()),
            "RecordedSensors cannot say a role was armed and silent, so no export can publish it",
        )
    }

    /**
     * The published block declares `silent`, closed over the roles and over
     * the words the app can actually say, and it is NOT required.
     *
     * Not required because absence must keep meaning "nothing was silent, or
     * this document was written before the app could tell". A required key
     * would make every export already in a coach's hands invalid against the
     * contract they are pointed at.
     *
     * `additionalProperties` rather than a list of objects, and the ROLE is the
     * key rather than a field: the roles that were silent are already
     * `expected` minus `present`, and a second list of them would be a
     * duplicate statement that can disagree with its own inputs -- the rule the
     * published `expected` description already states for exactly this reason.
     * What is NEW here is the word, and the role is the handle it hangs off.
     */
    @Test
    fun `the published sensor block declares silence, keyed by role and closed over the words`() {
        val silent = assertNotNull(
            sensorProperty("silent"),
            "the published sensors block does not declare silent",
        )

        assertEquals("object", silent["type"]!!.jsonPrimitive.content, "silence is not an object keyed by role")
        assertEquals(
            SessionExport.VALID_SENSOR_ROLES,
            silent["propertyNames"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet(),
            "the published silence is keyed by something other than the app's own roles",
        )
        assertEquals(
            ArmedSilencePolicy.PUBLISHED_WIRE,
            silent["additionalProperties"]!!.jsonObject["enum"]!!.jsonArray
                .map { it.jsonPrimitive.content }.toSet(),
            "the published silence vocabulary and the words the app can say disagree",
        )
        assertTrue(
            silent["description"]?.jsonPrimitive?.content?.isNotBlank() == true,
            "the published silent carries no description, which is the shape of issue #76",
        )
        assertFalse(
            "silent" in setSensors()["required"]!!.jsonArray.map { it.jsonPrimitive.content },
            "silence is required, which invalidates every export already written",
        )
    }

    /**
     * The published description says what the app could NOT distinguish, not
     * only what it could.
     *
     * The claim-stronger-than-its-evidence guard, on the one key most likely to
     * be over-read. `notLinked` merges powered-off, out-of-range, refused and
     * an OS bond removed behind the app's back, because nothing in this
     * repository reads `BluetoothDevice.getBondState()`; a reader who takes it
     * for "the unit was switched off" is being told something the app never
     * observed.
     */
    @Test
    fun `the published silence warns that notLinked is a merge rather than a diagnosis`() {
        val description = assertNotNull(sensorProperty("silent"))["description"]!!.jsonPrimitive.content

        assertTrue(
            "bond" in description,
            "the published silence never tells a reader the app cannot see an OS bond",
        )
        assertTrue(
            "out of range" in description,
            "the published silence never tells a reader notLinked merges several causes",
        )
    }

    /**
     * The Kotlin twin and the published block still agree on their key set.
     *
     * [SchemaContractTest] asserts this equality already, which is exactly why
     * it is repeated here: that assertion is what turns "add a field to
     * `SetSensorsExport`" into a contract change that has to move the schema in
     * the same commit, and this file is where the reason is written down.
     */
    @Test
    fun `the export twin carries the silence key too`() {
        assertTrue(
            "silent" in serialKeysOf(SetSensorsExport.serializer()),
            "SetSensorsExport cannot publish what the row now stores",
        )
        assertEquals(
            serialKeysOf(SetSensorsExport.serializer()),
            setSensors()["properties"]!!.jsonObject.keys,
            "SetSensorsExport and the published sensors block disagree on keys",
        )
    }

    /**
     * The version does not move, and the change rides as a further entry under
     * the unreleased 1.17.
     *
     * The rule this applies is the one the 1.13, 1.14 and 1.16 entries each
     * applied and the 1.15 entry got wrong in one direction and the 1.17 entry
     * got wrong in the other: a number nobody has been handed may still grow;
     * a number that shipped may not. No tag carries 1.17 -- v0.1.48 ships 1.16,
     * which is what #207's own entry records reading at that tag -- so this is
     * an extension rather than a mint.
     */
    @Test
    fun `the export version stands still and 1_17 gains a further entry`() {
        assertEquals("1.17", SessionExport.SCHEMA_VERSION, "the export version moved; #213 must not mint one")
        assertTrue("1.17" in SessionExport.SUPPORTED_SCHEMA_VERSIONS, "1.17 left the accepted set")
        assertFalse("1.18" in SessionExport.SUPPORTED_SCHEMA_VERSIONS, "a version nobody minted reached the app")

        val entry = entry117()
        assertTrue("#213" in entry, "the 1.17 entry does not name the issue its second change came from")
        assertTrue("silent" in entry, "the 1.17 entry never names the key it added")
        assertTrue(
            "additive" in entry,
            "the 1.17 entry does not tell a reader whether it has to change anything",
        )
    }

    /**
     * The example payload shows the state, because a description a reader can
     * misread is worth less than a payload they can run a validator over.
     *
     * The set it shows is field-37's, which is the population this key exists
     * for: two units armed, one present, and the absent one carrying a word
     * for what the app could see of it. That set is ALREADY in the example --
     * it is the one carrying `analysedFellBack` -- so what moves is one key on
     * one object, and the ajv step in CI validates it against the schema this
     * same commit moves.
     */
    @Test
    fun `the published example shows a set whose armed partner was silent`() {
        val sets =
            example("session-export.example.json")["exercises"]!!.jsonArray
                .flatMap { it.jsonObject["sets"]!!.jsonArray }
                .map { it.jsonObject }
        val silent =
            sets.mapNotNull { it["sensors"]?.jsonObject }.firstOrNull { it["silent"] != null }

        val block = assertNotNull(silent, "no set in the published example shows an armed unit that went silent")
        val expected = block.getValue("expected").jsonArray.map { it.jsonPrimitive.content }
        val present = block.getValue("present").jsonArray.map { it.jsonPrimitive.content }
        val words = block.getValue("silent").jsonObject

        assertEquals(
            (expected - present.toSet()).toSet(),
            words.keys,
            "the example's silence names roles the same set says were present, or misses one it says were not",
        )
        assertTrue(
            words.values.all { it.jsonPrimitive.content in ArmedSilencePolicy.PUBLISHED_WIRE },
            "the example publishes a word the app cannot say: $words",
        )
    }
}
