package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the published export and the plan prompt say about which build an
 * export came from (#312's lane, owner correction 2026-09-25: "The filename
 * states which version made it").
 *
 * WHAT WAS WRONG. `PLAN_PROMPT` told the coach that nothing in the export says
 * which build recorded a set. False: `SessionDetailViewModel.exportName` names
 * every export `BarSpeed-v<BuildConfig.VERSION_NAME>-<session start>-<suffix>`,
 * and `RawExporter` writes `appVersion` into the raw zip's `meta.json`. Both
 * name the build that WROTE the export, which is the recording build unless
 * the session was exported after an update. `liveReps`'s description says,
 * truly of `session.json`, that nothing in THIS document records the build --
 * true, and it sends a reader the wrong way, so it gains the pointer.
 *
 * Narrow, and said so: this checks the documents STATE it. The Kotlin twin of
 * the `liveReps` description and of the log, on [SessionExport], is kept in
 * step by hand; nothing here can see a KDoc.
 */
class SchemaExportBuildContractTest {
    private val schema = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/session-export.schema.json")!!.readBytes().decodeToString(),
    ).jsonObject

    private val versionLog = schema["properties"]!!.jsonObject["schemaVersion"]!!
        .jsonObject["description"]!!.jsonPrimitive.content

    private val liveReps = schema["\$defs"]!!.jsonObject["set"]!!
        .jsonObject["properties"]!!.jsonObject["liveReps"]!!.jsonObject["description"]!!
        .jsonPrimitive.content

    private val prompt: String =
        checkNotNull(
            javaClass.getResourceAsStream("/kotlin/com/macrophage/barspeed/ui/screens/GuideScreen.kt"),
        ) { "GuideScreen.kt is not on the test classpath - see the include filter in core/model/build.gradle.kts" }
            .readBytes().decodeToString()

    private val names = "name the build that WROTE the export"
    private val unlessUpdated = "the recording build unless the session was exported after an update"

    @Test
    fun `the plan prompt names the filename and appVersion instead of saying nothing does`() {
        assertFalse(
            "nothing in the export says which build recorded a set" in prompt,
            "PLAN_PROMPT still says nothing in the export names the build",
        )
        assertTrue(names in prompt, "PLAN_PROMPT does not say the filename and appVersion name the writing build")
        assertTrue(unlessUpdated in prompt, "PLAN_PROMPT does not say when the writing build is not the recorder")
        assertTrue("filename" in prompt && "\"appVersion\"" in prompt, "PLAN_PROMPT does not name both places")
        assertTrue(
            "started before 2026-09-18 cannot have been counted by the impulse detector" in prompt,
            "PLAN_PROMPT lost the date rule",
        )
    }

    @Test
    fun `liveReps keeps its true sentence and points at the filename and appVersion`() {
        assertTrue(
            "Nothing in this document records which build recorded a set" in liveReps,
            "liveReps dropped the sentence that is true of session.json",
        )
        assertTrue(names in liveReps, "liveReps does not say the filename and appVersion name the writing build")
        assertTrue(unlessUpdated in liveReps, "liveReps does not say when the writing build is not the recorder")
        assertTrue("`meta.json` `appVersion`" in liveReps, "liveReps does not name the raw zip's appVersion")
    }

    @Test
    fun `the 1_22 log files the pointer once, as a further entry that changes no key`() {
        val marker = "1.22 TAKES AN EIGHTH ENTRY (#312"
        assertEquals(1, versionLog.split(marker).size - 1, "the #312 entry is filed exactly once")
        val entry = versionLog.substringAfter(marker)
        assertTrue("CHANGES NO KEY" in entry, "the entry does not say no key moved")
        assertTrue(names in entry, "the entry does not say the filename and appVersion name the writing build")
        assertTrue("every 5 s" in entry, "the entry does not say what the hold voice now records")
        assertTrue("DATABASE_VERSION does NOT move" in entry, "the entry does not say the database is untouched")
    }
}
