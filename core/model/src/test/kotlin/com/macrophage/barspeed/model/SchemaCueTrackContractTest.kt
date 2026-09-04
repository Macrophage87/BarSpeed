package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the published session export says a set's CUE TRACK contains.
 *
 * A separate class from [SchemaContractTest] and not a preference. Both of
 * these cases were written into that class, and both are still the same kind of
 * pin it holds -- an assertion about `docs/schemas/session-export.schema.json`,
 * the document a reader of an archive is pointed at. What moved them out is
 * that `SchemaContractTest` is at detekt's `LargeClass` ceiling: #161 and this
 * branch each grew it by a block that was under the ceiling on its own, and the
 * two together are over it. Neither side could see that alone, and the file is
 * a shared pin every schema change lands in, so the collision recurs until
 * something stops adding to it.
 *
 * The split is by SUBJECT rather than by size, so it stays legible: everything
 * here is about `voiceCues` and the vocabulary a cue row can carry.
 */
class SchemaCueTrackContractTest {
    private fun schema(name: String) = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/$name")!!.readBytes().decodeToString(),
    ).jsonObject

    /**
     * The published cue vocabulary names the rep call the track now carries.
     *
     * `voiceCues` is the one place a reader is told what a cue row can say,
     * and until #176 it listed a vocabulary the app did not emit: every merged
     * rep call was spoken and no row was written, so a reader counting calls in
     * an archive counted none. A schema that lists `'Rep 4'` as an example while
     * the rows exist only on one of three code paths describes a document
     * nobody has.
     *
     * Narrow, and said so: this checks the vocabulary is NAMED, not that the
     * exporter emits it. What emits it is `CadenceVoice` in `:core:dsp`, pinned
     * there.
     */
    @Test
    fun `the published export documents the rep call among the cues a set records`() {
        val voiceCues = schema("session-export.schema.json")["\$defs"]!!.jsonObject["set"]!!
            .jsonObject["properties"]!!.jsonObject["voiceCues"]!!.jsonObject
        val description = voiceCues["description"]!!.jsonPrimitive.content
        assertTrue("Rep 4" in description, "the cue vocabulary stopped naming the rep count")
        assertTrue(
            "Last rep" in description,
            "the cue vocabulary does not name Last rep, which the guide speaks and now records",
        )
        assertTrue(
            "same instant" in description,
            "nothing tells a reader two rows can share one instant, which a merged call writes",
        )
    }

    /**
     * The 1.13 version-log entry names the cue-track change too.
     *
     * Same reasoning as the rep-marks and sensors entries in
     * [SchemaContractTest]: 1.13 is unreleased, so this extends it rather than
     * minting 1.14 for a boundary no reader has shipped against. What makes
     * this one worth naming separately is that it changes what an EXISTING key
     * contains for a given session rather than adding a new one, which is the
     * half of a version a reader skips at their peril.
     */
    @Test
    fun `the 1_13 version log names the cue-track change as well`() {
        val description =
            schema("session-export.schema.json")["properties"]!!.jsonObject["schemaVersion"]!!
                .jsonObject["description"]!!.jsonPrimitive.content
        assertTrue(
            "voiceCues" in description,
            "the version log never mentions voiceCues, so 1.13 changes a key it does not explain",
        )
        assertTrue(
            "Last rep" in description,
            "the version log does not say the cue track gained the calls the guide merges",
        )
    }

    /**
     * The version log keeps the 1.13 reading rule for the versions that
     * withheld the warning.
     *
     * #176 makes `Last rep` a row for the first time on the plans that merge
     * the call, and #173 then stops the guide speaking it on the subset of
     * those plans whose only slot for it is the beat the rep ends on. Both
     * landed under 1.13, so the two together decide what a reader may conclude
     * from a track that names no `Last rep` -- and without those sentences they
     * cannot conclude anything, because the defect #176 fixed and the
     * suppression #173 added leave the same evidence: no row.
     *
     * #243 speaks the warning again from 1.19, and that does NOT make these
     * sentences deletable: every set recorded under 1.13 through 1.18 was
     * recorded by an app that withheld it, and those archives are the only
     * thing the rule was ever for. What the 1.19 entry must do instead is say
     * that it supersedes them, which the next test asserts. A reading rule for
     * a version that shipped is history, not a claim about the current app.
     */
    @Test
    fun `the version log keeps the 1_13 reading rule for the versions that withheld the warning`() {
        val description =
            schema("session-export.schema.json")["properties"]!!.jsonObject["schemaVersion"]!!
                .jsonObject["description"]!!.jsonPrimitive.content
        assertTrue(
            "not spoken at all" in description,
            "the version log does not say the warning is withheld on the plans that cannot carry it in time",
        )
        assertTrue(
            "absence of a Last rep row" in description,
            "nothing tells a reader whether a track with no Last rep row is by design or is the defect 176 fixed",
        )
    }

    /**
     * The published vocabulary says WHICH rep a call names, and that it moved.
     *
     * `Rep 4` and `Last rep` are the same two strings either side of 1.19 and
     * they name different reps: until 1.19 the guided call counted FINISHED
     * reps, so the last number of a set was `planned - 2` and `Last rep` fell
     * on the rep before the last; from 1.19 the call names the rep now due
     * (#243). A consumer aligning cue rows to reps is off by one across that
     * boundary and nothing in a row says so, which is why the vocabulary has to.
     */
    @Test
    fun `the published vocabulary says which rep a call names, and that it changed at 1_19`() {
        val voiceCues = schema("session-export.schema.json")["\$defs"]!!.jsonObject["set"]!!
            .jsonObject["properties"]!!.jsonObject["voiceCues"]!!.jsonObject
        val description = voiceCues["description"]!!.jsonPrimitive.content
        assertTrue(
            "names the rep the guide is calling for" in description,
            "the cue vocabulary does not say which rep a call names",
        )
        assertTrue(
            "before 1.19" in description,
            "nothing tells a reader the same row named a different rep in older archives",
        )
    }

    /**
     * The version log names the schedule change as well as the vocabulary.
     *
     * Same half a reader skips at their peril as the terminal-cue entry below:
     * this one changes what an EXISTING row means for a given session rather
     * than adding a key, so a reader who skips it silently compares different
     * quantities across the boundary.
     */
    @Test
    fun `the 1_19 version log says the rep call moved onto the rep it calls for`() {
        val description =
            schema("session-export.schema.json")["properties"]!!.jsonObject["schemaVersion"]!!
                .jsonObject["description"]!!.jsonPrimitive.content
        assertTrue(
            "off by one across the boundary" in description,
            "the version log never says a consumer aligning cue rows to reps is off by one across 1.19",
        )
        assertTrue(
            "no longer withheld" in description,
            "the version log does not say the last-rep warning is spoken again from 1.19",
        )
    }

    /**
     * The published documents say WHEN a call is spoken, not only which rep it
     * names.
     *
     * Round 2 of #243 found the two sentences that landed with 1.19 stronger
     * than the schedule under them. Both said a call is heard during the rep it
     * names -- the version log as "each during the rep it names", `voiceCues`
     * as "'Last rep' on the final rep itself" -- and that is true of the two
     * schedules that MERGE the call into a stroke and false of the one that
     * hands it a closing pause, where it rides the PREVIOUS rep's tail. The
     * version log said so itself four sentences later, so the document
     * contradicted itself on the one fact a consumer aligns rows by.
     *
     * `CueTrackOriginTest` in `:core:dsp` pins the behaviour; this pins that
     * the published document states it. A reader who takes the stronger reading
     * puts every call on a 4011 back squat one rep late.
     */
    @Test
    fun `the published documents say a paused schedule speaks the call before the rep`() {
        val schema = schema("session-export.schema.json")
        val versionLog = schema["properties"]!!.jsonObject["schemaVersion"]!!
            .jsonObject["description"]!!.jsonPrimitive.content
        val voiceCues = schema["\$defs"]!!.jsonObject["set"]!!
            .jsonObject["properties"]!!.jsonObject["voiceCues"]!!.jsonObject["description"]!!
            .jsonPrimitive.content
        // The two false sentences, pinned by their absence. The positive pins
        // below are substring checks and would have passed on a document that
        // stated the qualification and kept the false claim beside it -- which
        // is what the 1.19 entry already did, in its own words, four sentences
        // after saying the opposite. A correction that adds is not a
        // correction; these two assert the deletion.
        assertFalse(
            "each during the rep it names" in versionLog,
            "the 1.19 entry still says every call is heard during the rep it names, false on a paused plan",
        )
        assertFalse(
            "on the final rep itself" in voiceCues,
            "voiceCues still puts Last rep on the final rep itself, which is false on a paused plan",
        )
        assertTrue(
            "previous rep's closing pause" in versionLog,
            "the 1.19 entry does not say where a paused schedule speaks the call",
        )
        assertTrue(
            "previous rep's closing pause" in voiceCues,
            "voiceCues does not say where a paused schedule speaks the call",
        )
    }

    /**
     * The published vocabulary says how a consumer tells the two counters
     * apart.
     *
     * Also round 2 of #243, and a gap this change opened rather than found. The
     * guided call and the unguided counter's call are the same two strings, and
     * before 1.19 they named the same rep, so a consumer reading `Rep 4` got
     * the same answer whichever wrote it. From 1.19 they name different reps
     * and nothing in a row says which one is speaking.
     *
     * The discriminator is the stroke words, and the schema has to carry it
     * because it is the only thing in the track that separates them.
     * `CueTrackOriginTest` verifies it holds; this verifies it is published.
     */
    @Test
    fun `the published vocabulary says how to tell the guided track from the unguided one`() {
        val voiceCues = schema("session-export.schema.json")["\$defs"]!!.jsonObject["set"]!!
            .jsonObject["properties"]!!.jsonObject["voiceCues"]!!.jsonObject["description"]!!
            .jsonPrimitive.content
        assertTrue(
            "which counter spoke it" in voiceCues,
            "nothing tells a reader the two counters are indistinguishable row by row",
        )
        assertTrue(
            "no stroke word" in voiceCues,
            "the vocabulary does not name the stroke words as the discriminator",
        )
    }

    /**
     * The published vocabulary names the word an abandoned set now ends on.
     *
     * `voiceCues` is the one place a reader is told what a cue row can say. A
     * terminal word the app speaks and the document does not name is the same
     * defect #176 fixed, in a new word: a reader looking for the end of a set
     * finds `Done` documented, does not find it in the track, and concludes
     * the set was never called over -- which is exactly what was true before
     * this change and exactly what is no longer true after it.
     *
     * Narrow, and said so: this checks the word is NAMED. What emits it is
     * `SetEnd.terminalCall` in `:core:dsp`, pinned there. The word is not
     * evidence of abandonment; `SetEnd.STOPPED` names the two populations it
     * covers.
     */
    @Test
    fun `the published export documents the cue an abandoned set ends on`() {
        val voiceCues = schema("session-export.schema.json")["\$defs"]!!.jsonObject["set"]!!
            .jsonObject["properties"]!!.jsonObject["voiceCues"]!!.jsonObject
        val description = voiceCues["description"]!!.jsonPrimitive.content
        assertTrue(
            "Set ended" in description,
            "the cue vocabulary does not name the word a guided set with no Done now ends on",
        )
        assertTrue(
            "prescription" in description,
            "nothing tells a reader that Done and Set ended mean different endings",
        )
    }

    /**
     * The version log says what an ABSENT boundary means, not only what a
     * present one does.
     *
     * The half a reader skips at their peril, and the half that costs them.
     * Every set recorded before this version that the lifter ended early
     * carries no terminal row at all, and after it every guided set carries
     * one. Without the sentence, a track with no terminal row is
     * uninterpretable: it could be a set from an older release, an unguided
     * set, or the defect. With it, the reader knows which question to ask.
     */
    @Test
    fun `the version log says what a track with no terminal cue means`() {
        val description =
            schema("session-export.schema.json")["properties"]!!.jsonObject["schemaVersion"]!!
                .jsonObject["description"]!!.jsonPrimitive.content
        assertTrue(
            "Set ended" in description,
            "the version log never mentions the terminal cue, so the change to voiceCues is unexplained",
        )
        assertTrue(
            "no terminal cue" in description,
            "nothing tells a reader how to read a guided set whose track ends on a stroke",
        )
    }
}
