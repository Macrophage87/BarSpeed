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
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalSerializationApi::class)
private fun soleSerialKeysOf(serializer: KSerializer<*>): Set<String> =
    serializer.descriptor.let { d -> (0 until d.elementsCount).map(d::getElementName).toSet() }

/**
 * What issue #224 moves in the published session export: a set armed with ONE
 * bar sensor can say that unit was silent.
 *
 * #213 gave a silent armed unit a word, keyed by ROLE, and a role exists only
 * where two paired units carry different labels. The owner's habitual
 * configuration is one unit, so the session type they train most had the
 * failure #213 was filed for and none of its remedy: nothing drew, nothing was
 * stored, and the record could not say the unit was silent rather than absent.
 *
 * DIFFERENTIALS. Every assertion here fails at the commit that introduces it:
 * neither [RecordedSensors] nor [SetSensorsExport] can say an UNROLED unit was
 * silent, the published block declares no such key, the version log has no
 * entry for it, the example payload carries no such set, and the published text
 * still states the gap as a permanent absence.
 *
 * A fifth file rather than more cases in [SchemaArmedSilenceContractTest], for
 * the reason that file gives for being the fourth: its cases belong to #213 and
 * are about the ROLE-keyed map, and what is here is the half #213's own gate
 * split out. The two are read together.
 *
 * The version does NOT move. 1.17 is UNRELEASED on `main` -- no tag carries it,
 * v0.1.48 ships 1.16 -- so this is a FURTHER entry under it rather than a mint,
 * the rule #207, #205 and #213 each applied under the same number. The
 * constant, the accepted set and the role vocabulary are asserted UNCHANGED
 * below rather than left inferred.
 *
 * The PLAN contract does not move at all. A plan declares no sensors since
 * #198's 1.10, and whether an armed unit delivered is not something a coach
 * writes down. `DATABASE_VERSION` does not move either: this rides
 * `sensorsJson`, a kotlinx blob on a column that already exists.
 */
class SchemaSoleSilenceContractTest {
    private fun schema(name: String) = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/$name")!!.readBytes().decodeToString(),
    ).jsonObject

    private fun example(name: String) = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/examples/$name")!!.readBytes().decodeToString(),
    ).jsonObject

    private fun setSensors() = schema("session-export.schema.json")["\$defs"]!!.jsonObject["setSensors"]!!.jsonObject

    private fun sensorProperty(name: String) = setSensors()["properties"]!!.jsonObject[name]?.jsonObject

    private fun description(name: String) =
        assertNotNull(sensorProperty(name), "the published sensors block does not declare $name")["description"]!!
            .jsonPrimitive.content

    private fun exportVersionLog() = schema("session-export.schema.json")["properties"]!!
        .jsonObject["schemaVersion"]!!.jsonObject["description"]!!.jsonPrimitive.content

    /** Everything the version log says from its last `1.17:` marker onward. */
    private fun entry117() = exportVersionLog().substringAfterLast("1.17:")

    /**
     * The stored declaration can say the ONE armed unit was silent, with no
     * role to hang it on.
     *
     * On the ROW, not only in the export, for the reason #213's own file gives:
     * the export is rebuilt from the row every time it is written, so a fact the
     * row cannot hold is a fact no re-export can recover.
     *
     * `sensorsJson` is a kotlinx blob on an existing column, so this key costs
     * NO `DATABASE_VERSION` bump, no migration, no exported schema and no
     * emulator test. That is why the shape was chosen over a typed column.
     */
    @Test
    fun `the stored declaration can name a sole armed unit that was silent`() {
        assertTrue(
            "soleSilent" in soleSerialKeysOf(RecordedSensors.serializer()),
            "RecordedSensors cannot say the one armed unit was silent, so no export can publish it",
        )
    }

    /**
     * A one-sensor set whose unit delivered stores nothing at all, and that is
     * what keeps the ordinary set byte-identical to what this app has always
     * written.
     *
     * The whole declaration stays null there -- not an object with an absent
     * key -- so `session.json` publishes no `sensors` block and `meta.json`
     * grows no sensor keys. The object appears only when there is something to
     * say.
     */
    @Test
    fun `an ordinary one-sensor declaration carries no sole silence`() {
        val wire = Json { ignoreUnknownKeys = true }

        val stored = wire.encodeToString(RecordedSensors.serializer(), RecordedSensors(count = 1))

        assertFalse("soleSilent" in stored, "a set whose only unit delivered stored a silence word: $stored")
        assertTrue(
            "soleSilent" in soleSerialKeysOf(RecordedSensors.serializer()),
            "RecordedSensors cannot say the one armed unit was silent, so no export can publish it",
        )
    }

    /**
     * The word and the role-keyed map are never both written for one set.
     *
     * They answer the same question about different shapes of set: `silent` is
     * keyed by role and exists only where roles were armed, this is the answer
     * where the single stream carries none. A set carrying both would be
     * stating one fact twice in two vocabularies, which is the class the
     * published `expected` description already refuses for the missing roles.
     */
    @Test
    fun `a declaration that armed roles never carries the roleless word`() {
        val armed = RecordedSensors(count = 2, expected = listOf(SensorRole.A, SensorRole.B), analysed = SensorRole.A)

        val withSole = SensorCapturePolicy.withSoleSilence(armed, ArmedDelivery.LINKED_SILENT)

        assertNull(withSole?.soleSilent, "a set that armed roles was given a word meant for a roleless stream")
        assertEquals(armed, withSole, "attaching a roleless word to a role-armed set changed the declaration")
    }

    /**
     * The published block declares `soleSilent`, closed over the same words
     * `silent` is closed over, and it is NOT required.
     *
     * A STRING rather than an object, because there is no key to hang it off:
     * the whole point of this case is that the stream carries no role. A
     * one-entry object keyed by something invented -- "sole", "unroled" -- would
     * put a fake role into a document whose readers are told a role is the
     * identity of a physical unit.
     *
     * Not required, because absence must keep meaning "the one unit delivered,
     * or this document was written before the app could tell".
     */
    @Test
    fun `the published sensor block declares a roleless silence word`() {
        val sole = assertNotNull(
            sensorProperty("soleSilent"),
            "the published sensors block does not declare soleSilent",
        )

        assertEquals("string", sole["type"]!!.jsonPrimitive.content, "the roleless word is not published as a word")
        assertEquals(
            ArmedSilencePolicy.PUBLISHED_WIRE,
            sole["enum"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet(),
            "the roleless silence vocabulary and the words the app can say disagree",
        )
        assertTrue(
            sole["description"]?.jsonPrimitive?.content?.isNotBlank() == true,
            "the published soleSilent carries no description, which is the shape of issue #76",
        )
        assertFalse(
            "soleSilent" in setSensors()["required"]!!.jsonArray.map { it.jsonPrimitive.content },
            "the roleless word is required, which invalidates every export already written",
        )
    }

    /**
     * The published description carries the same weakness warning `silent`
     * carries, because it publishes the same words.
     *
     * `notLinked` is a MERGE here for exactly the reason it is a merge there --
     * nothing in this app reads `BluetoothDevice.getBondState()` -- and a reader
     * who meets it on a one-sensor set has no other description to fall back
     * on.
     */
    @Test
    fun `the roleless word warns that its vocabulary is weaker than it looks`() {
        val text = description("soleSilent")

        assertTrue("silent" in text, "the published soleSilent never says which key it shares its words with")
        assertTrue(
            "notLinked" in text,
            "the published soleSilent never names the word most likely to be over-read",
        )
    }

    /**
     * The Kotlin twin and the published block still agree on their key set.
     *
     * [SchemaContractTest] asserts this equality already, which is why it is
     * repeated here: that assertion is what turns "add a field to
     * [SetSensorsExport]" into a contract change that has to move the schema in
     * the same commit.
     */
    @Test
    fun `the export twin carries the roleless word too`() {
        assertTrue(
            "soleSilent" in soleSerialKeysOf(SetSensorsExport.serializer()),
            "SetSensorsExport cannot publish what the row now stores",
        )
        assertEquals(
            soleSerialKeysOf(SetSensorsExport.serializer()),
            setSensors()["properties"]!!.jsonObject.keys,
            "SetSensorsExport and the published sensors block disagree on keys",
        )
    }

    /**
     * The published text that named this gap as a permanent absence is
     * rewritten, rather than left standing beside the key that closes it.
     *
     * #213 wrote into `silent` that a set armed with one bar sensor arms no role
     * and that the whole object is absent there, naming #224. The first half
     * stays true and the second stops being the end of the story: the fact is
     * published, under another key. A description that still says the app cannot
     * state something it now states is a false claim in the one document a
     * reader is pointed at.
     */
    @Test
    fun `the absence text for a one-sensor set is rewritten rather than left standing`() {
        val text = description("silent")

        assertTrue(
            "soleSilent" in text,
            "the published silent ends the one-sensor case at absence and never points at the key that closes it",
        )
        assertFalse(
            "this whole object is absent there (#224)" in text,
            "the published silent still states the one-sensor gap as the end of the story",
        )
    }

    /**
     * A reader can still tell a pre-1.15 row from a one-sensor set that went
     * silent, and the published `shortfall` says how.
     *
     * Its description tells a reader that `count` 1 with an empty `expected` and
     * no shortfall is a row written before 1.15 under a retired `plannedCount`.
     * This change makes a SECOND kind of set match that description, so the
     * discriminator has to be stated or the sentence becomes false the day it
     * ships.
     */
    @Test
    fun `the historical one-stream reading names the key that now separates it`() {
        val text = description("shortfall")

        assertTrue(
            "soleSilent" in text,
            "the pre-1.15 reading of count 1 with an empty expected no longer holds and does not say so",
        )
    }

    /**
     * The published `present` stops claiming an empty list means every armed
     * unit went silent.
     *
     * A correction of text this change makes more reachable rather than a new
     * statement. An empty `present` beside an empty `expected` is a set whose
     * single stream carries NO ROLE -- which is already what a set that met two
     * paired units it could not tell apart publishes today, and is what a
     * one-sensor set publishes the moment this key gives it a reason to publish
     * the block at all. Reading it as "every armed unit went silent" is the
     * wrong-pair class: the figure is read against the wrong question.
     */
    @Test
    fun `an empty present is not published as proof that every unit went silent`() {
        val text = description("present")

        assertTrue(
            "no role" in text || "carries none" in text,
            "the published present still reads an empty list as a silence rather than as an unroled stream",
        )
    }

    /**
     * The version does not move, and the change rides as a further entry under
     * the unreleased 1.17.
     */
    @Test
    fun `the export version stands still and 1_17 gains a further entry`() {
        assertEquals("1.17", SessionExport.SCHEMA_VERSION, "the export version moved; #224 must not mint one")
        assertTrue("1.17" in SessionExport.SUPPORTED_SCHEMA_VERSIONS, "1.17 left the accepted set")
        assertFalse("1.18" in SessionExport.SUPPORTED_SCHEMA_VERSIONS, "a version nobody minted reached the app")
        assertEquals(setOf("a", "b"), SessionExport.VALID_SENSOR_ROLES, "the role vocabulary moved under #224")

        val entry = entry117()
        assertTrue("#224" in entry, "the 1.17 entry does not name the issue this change came from")
        assertTrue("soleSilent" in entry, "the 1.17 entry never names the key it added")
        assertTrue(
            "additive" in entry,
            "the 1.17 entry does not tell a reader whether it has to change anything",
        )
    }

    /**
     * The example payload shows the state, because a description a reader can
     * misread is worth less than a payload they can run a validator over.
     *
     * The set it shows is the one this issue exists for: ONE armed unit, no
     * role, no stream, and a word for what the app could see of the link. The
     * ajv step in CI validates it against the schema this same commit moves.
     */
    @Test
    fun `the published example shows a one-sensor set whose only unit was silent`() {
        val sets =
            example("session-export.example.json")["exercises"]!!.jsonArray
                .flatMap { it.jsonObject["sets"]!!.jsonArray }
                .map { it.jsonObject }
        val block =
            assertNotNull(
                sets.mapNotNull { it["sensors"]?.jsonObject }.firstOrNull { it["soleSilent"] != null },
                "no set in the published example shows a one-sensor set whose only unit went silent",
            )

        assertEquals(1, block.getValue("count").jsonPrimitive.content.toInt(), "the example's set armed more than one")
        assertTrue(
            block.getValue("expected").jsonArray.isEmpty(),
            "the example gave a role to a stream that carries none",
        )
        assertTrue(
            block.getValue("present").jsonArray.isEmpty(),
            "the example says an unroled stream reached the archive under a role",
        )
        assertNull(block["silent"], "the example set carries both the roleless word and the role-keyed map")
        assertTrue(
            block.getValue("soleSilent").jsonPrimitive.content in ArmedSilencePolicy.PUBLISHED_WIRE,
            "the example publishes a word the app cannot say: ${block["soleSilent"]}",
        )
    }
}
