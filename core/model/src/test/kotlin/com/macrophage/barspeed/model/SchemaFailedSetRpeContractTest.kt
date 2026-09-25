package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the published export says about an rpe beside a failure (#313).
 *
 * From the build carrying #313 no write stores an rpe on a set that failed,
 * whichever of the two failure facts says so; before it a derived failure
 * could carry the rating that stood when the failure arrived, and before
 * #310 a stated one could too. A reader weighing `rpe` needs to know that a
 * failed set's absent rating is the owner's rule, not a skipped question.
 *
 * Narrow, and said so: this checks the published schema STATES it. The Kotlin
 * twin on `SetExport.rpe` and the log on [SessionExport] are kept in step by
 * hand; nothing here can see a KDoc.
 */
class SchemaFailedSetRpeContractTest {
    private val schema = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/session-export.schema.json")!!.readBytes().decodeToString(),
    ).jsonObject

    private val versionLog = schema["properties"]!!.jsonObject["schemaVersion"]!!
        .jsonObject["description"]!!.jsonPrimitive.content

    private val rpe = schema["\$defs"]!!.jsonObject["set"]!!
        .jsonObject["properties"]!!.jsonObject["rpe"]!!.jsonObject["description"]!!
        .jsonPrimitive.content

    private val rule = "From 1.22 (#313) a set that FAILED carries no rpe"

    @Test
    fun `rpe says a failed set carries none, by either failure fact`() {
        assertTrue(rule in rpe, "rpe does not say a failed set carries no rating")
        assertTrue("whichever of the two failure facts says so" in rpe, "rpe does not name the derived failure")
        assertTrue("`failedByLifter` is unchanged by it" in rpe, "rpe does not say the lifter's half is untouched")
        assertTrue("may carry both" in rpe, "rpe does not say older sets can carry a rating beside a failure")
    }

    @Test
    fun `the 1_22 log files the rule once, as a further entry that changes no key`() {
        val marker = "1.22 TAKES A NINTH ENTRY (#313"
        assertEquals(1, versionLog.split(marker).size - 1, "the #313 entry is filed exactly once")
        val entry = versionLog.substringAfter(marker)
        assertTrue("CHANGES NO KEY" in entry, "the entry does not say no key moved")
        assertTrue("NOT RETROACTIVE" in entry, "the entry does not say stored rows keep their rating")
        assertTrue("DATABASE_VERSION does NOT move" in entry, "the entry does not say the database is untouched")
    }
}
