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

/**
 * The wire names a serializable class declares, read off the class itself.
 *
 * [SchemaContractTest] keeps the same helper and states why it is top-level
 * rather than a method; it is private there, so it is stated again here rather
 * than reached for.
 */
@OptIn(ExperimentalSerializationApi::class)
private fun serialKeysOf(serializer: KSerializer<*>): Set<String> =
    serializer.descriptor.let { d -> (0 until d.elementsCount).map(d::getElementName).toSet() }

/**
 * What the published export contract says about a prescribed set the lifter
 * skipped. Issue #300, export 1.21.
 *
 * ## Why there is a key at all
 *
 * The preference is always to derive rather than mint, and here nothing could
 * be derived. This document publishes no prescribed set count anywhere: the
 * root carries `planRef`, which is a NAME, and `$defs.exercise` carries exactly
 * `exercise` and `sets`. The version log already says why that cannot be worked
 * around after the fact -- the plan is not in the export and may be edited or
 * deleted after the session it drove -- so a reader given a session carrying
 * three squat sets cannot tell a plan that asked for three from a plan that
 * asked for four, and, once one set can be dropped mid-session, cannot tell
 * either of those from a set the app lost.
 *
 * ## What these pins can and cannot check
 *
 * They read the PUBLISHED document, `docs/schemas/session-export.schema.json`,
 * which is what a reader of an archive is pointed at, and the published example
 * `ci.yml` hands to ajv. The Kotlin twin of the same log, on
 * [SessionExport.SCHEMA_VERSION], is not on this module's test classpath and is
 * kept in step by hand -- the limit [SchemaManualSetEndContractTest] states for
 * its own entry.
 *
 * They cannot check that the entry is RIGHT, and they say nothing about what
 * the app writes into the key: that a skip reaches the column and the column
 * reaches the document is `:core:data`'s `SessionExportSkippedSetTest`, and
 * that the right slot is chosen at all is `SkipSetControlTest`.
 *
 * Its own file rather than a method on [SchemaContractTest], which sits on
 * detekt's `LargeClass` limit.
 */
class SchemaSkippedSetContractTest {
    private companion object {
        /** The opening words of this entry, and the anchor every pin below is scoped by. */
        const val MARKER = "1.21: ADDS a session-level skippedSets array"
    }

    private fun schema(name: String) = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/$name")!!.readBytes().decodeToString(),
    ).jsonObject

    private fun export() = schema("session-export.schema.json")

    private val versionLog: String
        get() = export().getValue("properties").jsonObject.getValue("schemaVersion")
            .jsonObject["description"]?.jsonPrimitive?.content.orEmpty()

    /**
     * THIS ENTRY's text, not the whole log.
     *
     * Scoped deliberately: `skippedSets` is named in the root property's own
     * description too, so a bare substring check over the whole document would
     * pass over a log that never gained this entry.
     * [SchemaManualSetEndContractTest] is where that failure was found, twice,
     * and the marker is the fix.
     */
    private val entry: String
        get() = versionLog.substringAfter(MARKER, "")

    // ---- the version the key is filed under ---------------------------------

    /**
     * The key rides under 1.21, which is the version this build writes, and
     * 1.20 -- shipped in v0.1.53 -- is still readable.
     *
     * A mint rather than an eighth entry under 1.20 BECAUSE 1.20 has shipped:
     * `git tag --sort=-creatordate | head -1` is v0.1.53 and `git show
     * v0.1.53:core/model/src/main/kotlin/com/macrophage/barspeed/model/SessionExport.kt`
     * declares `"1.20"`, read at the tag this round rather than relayed.
     * Extending a shipped version changes what a document already in the field
     * means.
     *
     * The literal 1.21 is asserted HERE, in the file that mints it, and nowhere
     * else. Seven other files asserted the tip constant beside a key filed under
     * an older number, and every one of them went false at this mint; they are
     * corrected in the same commit to pin their own filed version instead.
     */
    @Test
    fun `the key rides under 1_21 and 1_20 is still readable`() {
        assertEquals("1.21", SessionExport.SCHEMA_VERSION, "the version the exporter writes")
        assertTrue("1.21" in SessionExport.SUPPORTED_SCHEMA_VERSIONS, "the version written is not accepted")
        assertTrue("1.20" in SessionExport.SUPPORTED_SCHEMA_VERSIONS, "1.20 left the accepted set")
        assertTrue(entry.isNotEmpty(), "the published log carries no entry opening \"$MARKER\"")
    }

    // ---- what the log has to tell a reader ----------------------------------

    /**
     * The entry states the reading rule, which is the whole content of the
     * change for a consumer.
     *
     * Without these sentences a reader meets an array of set numbers and has to
     * guess whether a skipped set also appears under `exercises` as a set of
     * zero reps -- which would double-count it in any adherence figure -- and
     * whether an empty array means "nothing was skipped" or "this build could
     * not tell".
     */
    @Test
    fun `the entry states how adherence is counted and what a skip is not`() {
        assertTrue(
            "RECORDED-AND-NOT-VOIDED OVER PRESCRIBED" in entry,
            "the entry never states the adherence rule the key exists to keep readable",
        )
        assertTrue(
            "NO entry under exercises" in entry,
            "the entry does not say a skipped set is absent from exercises, so a reader may double-count it",
        )
        assertTrue(
            "voided instead" in entry,
            "the entry does not separate a skipped set from a set performed and then marked not-done",
        )
        assertTrue(
            "ABSENCE of entries is not evidence" in entry,
            "the entry lets an empty array be read as proof the whole plan was completed",
        )
    }

    /**
     * The entry says the database version moves and the plan schema does not.
     *
     * Both halves matter to different readers: a phone has to migrate, and a
     * plan-generating LLM must not be told to emit anything new.
     */
    @Test
    fun `the entry says the database moves to 19 and the plan schema does not`() {
        assertTrue("DATABASE_VERSION DOES move, to 19" in entry, "the entry does not say the database moves")
        assertTrue("plan schema is untouched" in entry, "the entry does not say the plan contract is unchanged")
        assertTrue("ADDITIVE" in entry, "the entry does not say whether a 1.20 reader breaks")
    }

    // ---- the declaration, pinned in both directions -------------------------

    /**
     * The root declares `skippedSets`, described, as an array of the block
     * below.
     *
     * The root object is `additionalProperties: false`, so a key the exporter
     * writes and the schema does not declare makes every export carrying it
     * INVALID against the contract its own reader was pointed at. It does not
     * merely go unmentioned.
     */
    @Test
    fun `the published root declares skippedSets as an array of skipped sets, described`() {
        val property = assertNotNull(
            export().getValue("properties").jsonObject["skippedSets"],
            "the published export schema does not declare skippedSets",
        ).jsonObject
        assertEquals("array", property.getValue("type").jsonPrimitive.content, "skippedSets is not an array")
        assertEquals(
            "#/\$defs/skippedSet",
            property.getValue("items").jsonObject.getValue("\$ref").jsonPrimitive.content,
            "the array items do not point at the skippedSet block",
        )
        assertTrue(
            property["description"]?.jsonPrimitive?.content?.isNotBlank() == true,
            "skippedSets is declared with no description, which is the shape of issue #76",
        )
    }

    /**
     * The block is closed, both keys are required, and its keys are exactly the
     * ones [SkippedSet] serialises.
     *
     * PINNED IN BOTH DIRECTIONS, which is the half a key-set assertion typed
     * out by hand cannot do: an equality against the serialiser fails when the
     * Kotlin type gains a field with no schema key AND when the schema gains
     * one the type does not write. #207 added a Kotlin property with no schema
     * key and CI was green, which is the incident this form exists for.
     *
     * Both keys are required because neither is optional in any entry the app
     * can write: a skip names an exercise and a set number or it names nothing.
     */
    @Test
    fun `the published skipped-set block is closed, required and matches the Kotlin type`() {
        val block = assertNotNull(
            export().getValue("\$defs").jsonObject["skippedSet"],
            "the published export schema has no skippedSet block",
        ).jsonObject
        assertEquals(
            false,
            block.getValue("additionalProperties").jsonPrimitive.content.toBoolean(),
            "the skipped-set block accepts undeclared keys, so a typo would validate",
        )
        assertEquals(
            setOf("exercise", "setNumber"),
            block.getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet(),
            "a skipped set may be published without naming the exercise or the set",
        )
        assertEquals(
            serialKeysOf(SkippedSet.serializer()),
            block.getValue("properties").jsonObject.keys,
            "SkippedSet and the published skippedSet block disagree on keys",
        )
        assertTrue(
            "skippedSets" in serialKeysOf(SessionExport.serializer()),
            "SessionExport does not serialise a skippedSets key at all",
        )
    }

    /**
     * Each key is described and the set number is a whole number from one.
     *
     * The set number's description is the one a reader must have: it is the
     * PLAN's number and not a position, because the sets that remain of a
     * shortened block keep the numbers the plan gave them.
     */
    @Test
    fun `both keys of a skipped set are described and the set number starts at one`() {
        val props = export().getValue("\$defs").jsonObject.getValue("skippedSet").jsonObject
            .getValue("properties").jsonObject
        listOf("exercise", "setNumber").forEach { key ->
            assertTrue(
                props.getValue(key).jsonObject["description"]?.jsonPrimitive?.content?.isNotBlank() == true,
                "$key is declared with no description, which is the shape of issue #76",
            )
        }
        val number = props.getValue("setNumber").jsonObject
        assertEquals("integer", number.getValue("type").jsonPrimitive.content, "a set number is not a whole number")
        assertEquals("1", number.getValue("minimum").jsonPrimitive.content, "a set number may be zero or negative")
        assertTrue(
            "PLAN prescribed it" in number.getValue("description").jsonPrimitive.content,
            "the set number's description does not say it is the plan's number rather than a position",
        )
        assertTrue(
            "Never a display name" in props.getValue("exercise").jsonObject
                .getValue("description").jsonPrimitive.content,
            "the exercise key's description does not refuse a display name, so it cannot be joined on",
        )
    }

    // ---- the example ajv actually validates ---------------------------------

    /**
     * The published example carries a skip, so the ajv step validates the block
     * rather than skipping over it.
     *
     * An example carrying none of a new key passes a schema that declares it and
     * a schema that does not, so the declaration could be wrong in every detail
     * while `ci.yml` stayed green. [SchemaContractTest] states the same
     * reasoning for the prep pair and the rep marks.
     *
     * The entry is also checked for being COHERENT with the sets beside it: it
     * names an exercise the example records sets of, at a number past the last
     * set recorded of it, which is the story the key exists to tell.
     */
    @Test
    fun `the published example carries a skip that its own sets do not contradict`() {
        val example = schema("examples/session-export.example.json")
        val skips = assertNotNull(
            example["skippedSets"],
            "no skip in the published example, so ajv never validates the block",
        ).jsonArray.map { it.jsonObject }
        assertTrue(skips.isNotEmpty(), "the example's skippedSets array is empty, which the exporter never writes")
        val byExercise = example.getValue("exercises").jsonArray.map { it.jsonObject }
            .associate { it.getValue("exercise").jsonPrimitive.content to it.getValue("sets").jsonArray.size }
        skips.forEach { skip ->
            val id = skip.getValue("exercise").jsonPrimitive.content
            val number = skip.getValue("setNumber").jsonPrimitive.content.toInt()
            val recorded = assertNotNull(byExercise[id], "the example skips a set of $id and records none of it")
            assertTrue(
                number > recorded,
                "the example skips set $number of $id while recording $recorded sets of it, which cannot both be true",
            )
        }
    }

    // ---- absence is the ordinary state --------------------------------------

    /**
     * A session with nothing skipped publishes no key.
     *
     * The exporter writes JSON with `encodeDefaults = false`, and this is the
     * property that makes "omitted when none was" in the schema true rather
     * than aspirational. An empty array published on every session would be
     * indistinguishable, to a reader, from a build that tracked skips and found
     * none -- which for every session recorded before 1.21 is exactly the claim
     * that must not be made.
     */
    @Test
    fun `a session that skipped nothing publishes no skippedSets key`() {
        val json = Json { encodeDefaults = false }
        val document = json.encodeToString(
            SessionExport.serializer(),
            SessionExport(startedAt = "2026-09-17T12:00:00Z", exercises = emptyList()),
        )
        assertFalse("skippedSets" in document, "an empty skip list reached the wire: $document")
        assertTrue(
            "startedAt" in document,
            "the encoder wrote nothing at all, so the assertion above would pass whatever it did",
        )
    }

    /** A session with one publishes it under both wire names. */
    @Test
    fun `a skipped set reaches the wire under the names the schema declares`() {
        val json = Json { encodeDefaults = false }
        val document = json.encodeToString(
            SessionExport.serializer(),
            SessionExport(
                startedAt = "2026-09-17T12:00:00Z",
                skippedSets = listOf(SkippedSet(exercise = "back_squat", setNumber = 4)),
                exercises = emptyList(),
            ),
        )
        assertTrue("\"skippedSets\"" in document, "the skip list did not reach the wire: $document")
        assertTrue("\"exercise\":\"back_squat\"" in document, "the skip lost its exercise: $document")
        assertTrue("\"setNumber\":4" in document, "the skip lost its set number: $document")
    }
}
