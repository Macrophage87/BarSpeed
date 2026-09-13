package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the published session-export contract says about which `Done` bounds a
 * set's figures. Issue #285.
 *
 * 1.12 told a reader that a set's per-rep figures cover only the detections
 * whose drive began at or before the set's own `Done` cue, and enumerated the
 * sets nothing bounds: an ad-hoc set recorded with the voice off, every set
 * recorded before cue tracks existed, and a guided set ended early (which 1.16
 * then removed from the list). That enumeration has a fourth member from this
 * version, and it is the common one on straight-reps barbell work: a set the
 * LIFTER counted by tapping, whose `Done` is the rep-count milestone rather
 * than a call to stop lifting.
 *
 * So the 1.12 sentence is now false of a population, and the fix is not
 * additive. It gets an entry, under 1.20 because 1.20 is unreleased --
 * `git show v0.1.52:core/model/.../SessionExport.kt` declares `"1.19"`, read at
 * the tag this round rather than assumed.
 *
 * ## What these pins can and cannot check
 *
 * They read the PUBLISHED document, `docs/schemas/session-export.schema.json`,
 * which is what a reader of an archive is pointed at. The copy of the same log
 * in [SessionExport]'s KDoc is not on this module's test classpath and is not
 * checked here; it is kept in step by hand, and the two drifting is the risk
 * this file reduces on one side only.
 *
 * They cannot check that the entry is RIGHT. They check that it exists, that it
 * names the rule, that it names the key a reader must look at to tell which
 * rule applied, that it carries the measured pair, that it says `Set ended` is
 * exempt, and that it warns about the one figure in the archive that DOES move
 * for a set already on disk.
 *
 * Its own file rather than a method on [SchemaContractTest], which sits on
 * detekt's `LargeClass` limit -- the reason [RollExcursionExportContractTest]
 * and [RestWindowExportContractTest] are separate too.
 */
class SchemaManualSetEndContractTest {
    private companion object {
        /** The opening words of this entry, and the anchor every pin below is scoped by. */
        const val MARKER = "ALSO UNDER 1.20, a FOURTH entry"
    }

    private val versionLog: String
        get() = Json.parseToJsonElement(
            javaClass.getResourceAsStream("/session-export.schema.json")!!.readBytes().decodeToString(),
        ).jsonObject.getValue("properties").jsonObject.getValue("schemaVersion").jsonObject["description"]
            ?.jsonPrimitive?.content.orEmpty()

    /**
     * THIS ENTRY's text, not the whole 82,000-character log.
     *
     * Scoped deliberately, and the scoping is the difference between a pin and
     * a decoration: `tempoPrescribed` and `rollExcursionBasis` are each named
     * several times elsewhere in this document, so a bare substring check for
     * either passes over a log that never gained this entry at all. Two of
     * these pins were written that way first and passed while blind, which is
     * why the marker exists.
     */
    private val entry: String
        get() = versionLog.substringAfter(MARKER, "")

    /**
     * The number the entry rides under, and that it is still the declared one.
     *
     * A pin on the digit rather than on "unreleased", because unreleased is a
     * fact about a tag and goes stale the moment one is cut, while the entry
     * having moved off 1.20 would mean this file is describing another version.
     */
    @Test
    fun `the entry rides under the version this build declares`() {
        assertEquals("1.20", SessionExport.SCHEMA_VERSION, "the declared export version")
        assertTrue(
            entry.isNotEmpty(),
            "the published log carries no entry opening \"$MARKER\"",
        )
    }

    @Test
    fun `the log says a Done cue bounds the figures only where a cadence ran`() {
        assertTrue(
            "ONLY where a CADENCE ran" in entry,
            "the log does not state the narrowed rule, so a reader still applies 1.12's",
        )
        assertTrue(
            "rep-count milestone" in entry,
            "the log never says what the other Done is, which is the whole of why the rule moved",
        )
    }

    /**
     * The reader needs a key, not an argument. `tempoPrescribed` is the one
     * already published per set that says whether anything paced it.
     */
    @Test
    fun `the log names the key a reader tells the two rules apart by`() {
        assertTrue(
            "`tempoPrescribed`" in entry,
            "the log gives a reader no field to apply the rule with",
        )
    }

    /**
     * `Set ended` is the cell that makes this a rule about the word's author
     * rather than about manual sets, and a reader matching only on "was there a
     * tempo" would get it wrong.
     */
    @Test
    fun `the log says Set ended still bounds every set`() {
        assertTrue(
            "`Set ended` still bounds" in entry,
            "the log does not exempt the app's own terminal word",
        )
    }

    /**
     * The measured pair, from the committed capture, so the size of the change
     * is in the document rather than only in a commit body.
     */
    @Test
    fun `the log carries the measured size of the change`() {
        assertTrue(
            "field-ohp-3010-8rep-s38-set05" in entry,
            "the log cites no capture for the figures it moves",
        )
        assertTrue(
            "62.2" in entry && "79.2" in entry,
            "the log states no before-and-after for velocity loss",
        )
    }

    /**
     * The retroactivity warning, which has TWO halves here and the second is
     * the one a reader would otherwise be surprised by: `session.json` is
     * re-derived from the stored rep list and does not move, but the raw
     * archive's `meta.json` recomputes `rollExcursion_deg` from the stored
     * streams at export time and does.
     */
    @Test
    fun `the log says which figure moves on a set already on disk`() {
        assertTrue(
            "rollExcursionBasis" in entry,
            "the log does not say that the recomputed roll window moves, or how a reader sees it",
        )
    }
}
