package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the published session export says about the word the guide speaks for
 * an explosive drive (#264).
 *
 * Its own class rather than a case in `SchemaCueTrackContractTest`, which is
 * about the rep call and the terminal cues: this is one sentence of the
 * `voiceCues` description and one entry of the version log, both moved by one
 * change.
 *
 * WHAT MOVED. `voiceCues` names the stroke words as the only discriminator
 * between the guide's track and the unguided counter's, and said which words
 * belong to which plane -- `'Down' and 'Up' on vertical work, 'Drive' and
 * 'Return' on horizontal`. From #264 the guide says `Drive` on vertical work
 * too, on a drive the tempo writes as `X`, so that pairing stopped being a way
 * to read the plane. The discriminator itself survives -- the unguided counter
 * still says no stroke word at all -- so the description is extended, not
 * replaced.
 *
 * Narrow, and said so: this checks the document STATES it. What the guide says
 * is pinned in `:core:dsp` (`TempoScheduleTest`, `ExplosiveDriveCueTest`,
 * `TwoSecondStrokeCountTest`).
 */
class SchemaExplosiveDriveCueContractTest {
    private val schema = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/session-export.schema.json")!!.readBytes().decodeToString(),
    ).jsonObject

    private val versionLog = schema["properties"]!!.jsonObject["schemaVersion"]!!
        .jsonObject["description"]!!.jsonPrimitive.content

    private val voiceCues = schema["\$defs"]!!.jsonObject["set"]!!
        .jsonObject["properties"]!!.jsonObject["voiceCues"]!!.jsonObject["description"]!!
        .jsonPrimitive.content

    @Test
    fun `voiceCues says Drive is spoken on vertical work too and is not evidence of the plane`() {
        assertTrue(
            "from 1.22 'Drive' on vertical work as well" in voiceCues,
            "voiceCues still says Drive belongs to horizontal work only",
        )
        assertTrue(
            "in place of 'Up' on a drive the tempo writes as 'X'" in voiceCues,
            "voiceCues does not say which vertical stroke is now called Drive",
        )
        assertTrue(
            "'Drive' is NOT evidence of horizontal work" in voiceCues,
            "voiceCues does not stop a reader inferring the plane from the stroke words",
        )
    }

    @Test
    fun `the 1_22 log files the explosive drive's word once, as a further entry that changes no key`() {
        val marker = "1.22 TAKES A FIFTH ENTRY (#264"
        assertEquals(1, versionLog.split(marker).size - 1, "the #264 entry is filed exactly once")
        val entry = versionLog.substringAfter(marker)
        assertTrue("CHANGES NO KEY" in entry, "the entry does not say no key moved")
        assertTrue("ONCE per set" in entry, "the entry does not say where the word appears on a drive-first lift")
        assertTrue(
            "a 10X0 does -- publishes it on every rep" in entry,
            "the entry says ONCE per set for every drive-first lift, false where the rep is named at the drive's end",
        )
        assertTrue("keeps 'Up'" in entry, "the entry does not say an X return keeps its word")
        assertTrue("NOT RETROACTIVE" in entry, "the entry does not say archived tracks keep their rows")
        assertTrue(
            "DATABASE_VERSION does NOT move" in entry,
            "the entry does not say the database is untouched",
        )
    }
}
