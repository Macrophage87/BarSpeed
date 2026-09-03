package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What the published session-export schema says about why a set ended (#189).
 *
 * THESE FAILED WHEN THEY WERE WRITTEN, at b5fdb50 (CI run 33313909287,
 * conclusion failure): the published document declared no such key, and
 * `$defs.set` sets `additionalProperties: false` -- so the moment the exporter
 * wrote one, every export carrying it was INVALID against the contract its own
 * reader was pointed at. That is why the schema half was red-gated beside the
 * exporter half rather than treated as documentation.
 *
 * A file of its own rather than more assertions in [SchemaContractTest], for
 * [SchemaAddedSetContractTest]'s reason: that class is at detekt's `LargeClass`
 * threshold and adding to it reds `:core:model:detekt`, which is CI's first
 * step.
 */
class SchemaLimiterContractTest {
    private fun schema(name: String) = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/$name")!!.readBytes().decodeToString(),
    ).jsonObject

    private fun setProperties(): JsonObject {
        val defs = schema("session-export.schema.json")["\$defs"]!!.jsonObject
        return defs["set"]!!.jsonObject["properties"]!!.jsonObject
    }

    private fun versionLog(): String {
        val properties = schema("session-export.schema.json")["properties"]!!.jsonObject
        return properties["schemaVersion"]!!.jsonObject["description"]!!.jsonPrimitive.content
    }

    /**
     * The ONE "1.19 carries a ... change" paragraph that names [issue],
     * bounded at the next such marker rather than run to the end of the log.
     *
     * Anchored on the ISSUE and not on the ordinal, which is the second thing
     * that went wrong here. Bounding came first: unbounded,
     * `"setup" in log.substring(start)` is satisfied by any LATER paragraph
     * too, so two paragraphs claiming one ordinal passed silently as long as
     * the word appeared somewhere downstream. But an ordinal is a POSITION in
     * a merged log, and a position is exactly what a rebase moves: #64's
     * rebase onto `main` inserted #60's `voided` entry as the THIRD paragraph
     * and pushed both of this branch's entries down one, so tests naming
     * FOURTH and FIFTH asserted about whichever paragraph happened to sit
     * there. An issue number is a fact about the change and no rebase moves
     * it.
     *
     * The single-match assertion is load-bearing: it fails on a log that
     * names the issue twice as well as on one that names it not at all, so
     * the duplication a merge produces cannot pass by leaving one correct
     * copy somewhere in the text.
     */
    private fun entryNaming(log: String, issue: String): String {
        val marker = "1.19 carries a"
        val starts = generateSequence(log.indexOf(marker)) { prev ->
            log.indexOf(marker, prev + marker.length).takeIf { it >= 0 }
        }.takeWhile { it >= 0 }.toList()
        val entries =
            starts.mapIndexed { i, start ->
                if (i + 1 < starts.size) log.substring(start, starts[i + 1]) else log.substring(start)
            }
        val naming = entries.filter { issue in it }
        assertEquals(
            1,
            naming.size,
            "the 1.19 version log should carry exactly one entry naming $issue, it carries " +
                "${naming.size} of ${entries.size}",
        )
        return naming.single()
    }

    /**
     * The published export declares the reason, and declares it CLOSED to
     * exactly the answers the app can write.
     *
     * An equality and not a subset, the way [SchemaContractTest] pins every
     * other vocabulary: a schema listing an answer the app cannot produce
     * promises a reader a grouping that will always be empty, and a schema
     * missing one the app does produce rejects a valid export.
     */
    @Test
    fun `the published export declares the reason, closed to the answers the app can write`() {
        val limiter = assertNotNull(
            setProperties()["limiter"],
            "the published export schema does not declare why a set ended",
        ).jsonObject
        assertEquals(
            SetLimiter.entries.map { it.stored }.toSet(),
            limiter["enum"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet(),
            "the published reason vocabulary drifted from SetLimiter",
        )
    }

    /**
     * The reason's description names the two things a reader cannot recover
     * from the value alone.
     *
     * That an absent key is a question skipped or never asked, and NOT a set
     * that ended for an unknown reason -- without it a reader counts silence
     * as a category. And that the outside-reason answer marks a set analysis
     * should discard rather than read as capacity, which is the half that
     * keeps "every unfinished set is a fail" honest.
     */
    @Test
    fun `the published reason says what its absence means and what the outside answer is for`() {
        val description = setProperties()["limiter"]!!.jsonObject["description"]!!.jsonPrimitive.content
        assertTrue(
            "OMITTED" in description,
            "the published reason never says that an unanswered set carries no key: $description",
        )
        assertTrue(
            "discard" in description,
            "the published reason never says the outside answer marks a set to discard: $description",
        )
        assertTrue(
            "pain" in description.lowercase(),
            "the published reason never singles out the answer a coach most needs to see: $description",
        )
    }

    /**
     * The note is declared beside the reason, as its own key, with the cap the
     * app enforces.
     *
     * `maxLength` is not decoration: a reader that allocates on it, or a
     * consumer that round-trips through a store with its own limit, is being
     * promised something, and the promise has to be the same number the app
     * truncates at.
     */
    @Test
    fun `the published export declares the free-text note beside the reason, capped`() {
        val note = assertNotNull(
            setProperties()["limiterNote"],
            "the published export schema does not declare the free-text note",
        ).jsonObject
        assertEquals("string", note["type"]!!.jsonPrimitive.content, "the note is not published as a string")
        assertEquals(
            SetLimiter.NOTE_MAX_CHARS,
            note["maxLength"]!!.jsonPrimitive.content.toInt(),
            "the published note cap drifted from SetLimiter.NOTE_MAX_CHARS",
        )
        val description = note["description"]!!.jsonPrimitive.content
        assertTrue(
            "other" in description,
            "the published note never says which answer it belongs to: $description",
        )
    }

    /**
     * The version log names the reason under the OPEN 1.14 entry.
     *
     * 1.14 is unreleased -- v0.1.44 shipped 1.13, read at the tag -- so this
     * extends that entry rather than minting 1.15, exactly as the seven
     * changes under 1.13 did while that number was open. Minting a boundary no
     * reader has shipped against publishes a version that never existed.
     */
    @Test
    fun `the 1_14 version log names the reason as an additive change under the open number`() {
        val log = versionLog()
        assertTrue(
            "`limiter`" in log,
            "the version log never mentions the reason, so 1.14 publishes an unexplained key",
        )
        assertTrue(
            "THIRD change, additive" in log,
            "the version log does not say the reason is additive: a 1.13 reader is told to re-check it",
        )
    }

    /**
     * The published example carries a failed set with a reason on it.
     *
     * `ci.yml` validates this example against the schema with ajv, and that is
     * the schema half's only automated coverage -- an example carrying none of
     * the new key passes a schema that declares it and a schema that does not.
     */
    @Test
    fun `the published export example carries a failed set with a reason`() {
        val sets =
            schema("examples/session-export.example.json")["exercises"]!!.jsonArray
                .flatMap { it.jsonObject["sets"]!!.jsonArray }
                .map { it.jsonObject }
        val withReason = sets.filter { "limiter" in it }
        assertTrue(withReason.isNotEmpty(), "the published example shows no set carrying a reason")
        for (set in withReason) {
            assertNotNull(
                SetLimiter.ofStored(set.getValue("limiter").jsonPrimitive.content),
                "the published example carries a reason the app cannot write",
            )
        }
    }

    /**
     * Every `limiterNote` the published example carries is a note the app's
     * own field can hold.
     *
     * THE DOCUMENT, NOT A COPY OF IT. This reads the real file off the test
     * resource path, which `core/model/build.gradle.kts` puts there so a
     * published document can be pinned rather than transcribed.
     * [SetLimiterTest] folds the same loop over a hard-coded literal, and a
     * literal cannot notice the example changing: 522356c's body claimed that
     * pin stopped the document and the build drifting apart, and that claim
     * was false.
     *
     * A published example carrying a note the field cannot produce is a
     * contract nothing holds.
     */
    @Test
    fun `every limiterNote in the published example is a note the field can hold`() {
        val notes =
            schema("examples/session-export.example.json")["exercises"]!!.jsonArray
                .flatMap { it.jsonObject["sets"]!!.jsonArray }
                .mapNotNull { it.jsonObject["limiterNote"]?.jsonPrimitive?.content }
        assertTrue(notes.isNotEmpty(), "the published example shows no set carrying a limiterNote")
        for (note in notes) {
            val typed = note.fold("") { held, ch -> SetLimiter.sanitizeForTyping(held + ch) }
            assertEquals(
                note,
                SetLimiter.normalizeNote(typed),
                "the published example carries a note the app's own field cannot hold",
            )
        }
    }

    /**
     * The published vocabulary carries the set-up answer, and its own entry
     * says how a coach reads it (#146).
     *
     * The equality above pins the two vocabularies to each other and would
     * stay green if BOTH lost this answer, so it cannot stand for #146. This
     * names the answer, and names the reading that is the whole reason for
     * adding it: the set tested the set-up and not the muscle, so the numbers
     * are not a capacity reading and the load stands next session. The word is
     * looked for INSIDE that answer's own entry, because "capacity" already
     * appears in the outside answer's.
     */
    @Test
    fun `the published reason carries the set-up answer and says it is not a capacity reading`() {
        val limiter = setProperties()["limiter"]!!.jsonObject
        assertTrue(
            "setup" in limiter["enum"]!!.jsonArray.map { it.jsonPrimitive.content },
            "the published vocabulary cannot say the set was set up wrong",
        )
        val description = limiter["description"]!!.jsonPrimitive.content
        val start = description.indexOf("setup:")
        assertTrue(start >= 0, "the published description never explains the set-up answer: $description")
        val end = description.indexOf("pain:", start)
        assertTrue(end > start, "the set-up answer is not described before the pain answer")
        val entry = description.substring(start, end)
        assertTrue(
            "capacity" in entry,
            "the published set-up answer never says its numbers are not a capacity reading: $entry",
        )
    }

    /**
     * The version log names it under 1.19, the open number nothing has
     * shipped.
     *
     * v0.1.50 shipped 1.18, so 1.19 takes further entries rather than minting
     * 1.20 -- the rule the 1.13, 1.15 and 1.17 entries each state. A closed
     * enum gaining a value is a change to the number even so: a reader
     * validating against 1.18 rejects a document carrying the ninth answer.
     *
     * Selected by the issue it names, never by its ordinal. The paragraph has
     * been THIRD and FOURTH under 1.18 and is FIFTH under 1.19: one rebase
     * onto `main` merged #60's `voided`/`voidReason` entry ahead of it, and
     * the next found 1.18 shipped in v0.1.50 and four 1.19 entries already
     * landed. An ordinal is a position in a merged log and the next merge
     * moves it again; `(#146)` is a fact about the change.
     */
    @Test
    fun `the 1_19 version log names the set-up answer under the open number`() {
        val entry = entryNaming(versionLog(), "(#146)")
        assertTrue("`setup`" in entry, "the 1.19 entry for #146 does not name the set-up answer: $entry")
    }

    /**
     * The version log names #191's widening in its own 1.19 entry, so a
     * reader of the published document (not just the Kotlin KDoc it is
     * copied from) is told that a non-null `limiter` no longer implies
     * `failed`.
     *
     * The Kotlin KDoc on [SessionExport.SCHEMA_VERSION] already carries this
     * paragraph; nothing pinned the PUBLISHED schema to it, which is why the
     * omission there passed CI. Round 1 of #191's review found the drift.
     *
     * Selected by `#191`, never by its ordinal, for the reason the entry
     * above states: this paragraph has been FOURTH and FIFTH under 1.18 and
     * is SIXTH under 1.19, moved by two rebases. The separate
     * assertion that the entry names #191, which this test used to carry, is
     * DELETED rather than kept: once the entry is selected by containing
     * `#191` that assertion cannot fail, and an assertion that cannot fail
     * reads as coverage.
     */
    @Test
    fun `the 1_19 version log entry for 191 says limiter may appear on a set that did not fail`() {
        val entry = entryNaming(versionLog(), "#191")
        assertTrue(
            "did NOT fail" in entry || "did not fail" in entry,
            "the 1.19 entry for #191 does not say limiter may appear on a set that did not fail: $entry",
        )
    }

    /**
     * The reason's own description says the same widening the version log
     * names, not just the log.
     *
     * The version log entry above is a change note; a reader who never reads
     * the changelog and jumps straight to `$defs.set.properties.limiter`
     * still has to be told that a non-null `limiter` no longer implies
     * `failed`. Round 2 of #191's review found this paragraph unpinned:
     * deleting it left the suite green.
     */
    @Test
    fun `the published reason says it is asked of completed sets rated at the counted end`() {
        val description = setProperties()["limiter"]!!.jsonObject["description"]!!.jsonPrimitive.content
        assertTrue(
            "COMPLETED SETS RATED AT THE COUNTED END" in description,
            "the published reason never says it is asked of a completed set rated at the counted end: " +
                description,
        )
        assertTrue(
            "#191" in description,
            "the published reason never names the issue that widened the question: $description",
        )
    }
}
