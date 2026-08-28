package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
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
}
