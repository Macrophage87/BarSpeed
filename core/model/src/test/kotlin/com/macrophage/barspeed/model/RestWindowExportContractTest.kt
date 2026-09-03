package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * What the export says about WHEN a rest started. Issue #178.
 *
 * Its own file rather than a method on [SchemaContractTest], which sits on
 * detekt's `LargeClass` limit and goes over it with one more test -- measured,
 * not guessed: adding this there red-ed `:core:model:detekt` with
 * `Class SchemaContractTest is too large`.
 *
 * The app held two answers to when a rest started -- the countdown ran from
 * the set-over cue and the rest-HR capture from the write instant, 53.06 s
 * apart on field-37 set 7 -- and `rest_s` shipped with no description at all,
 * so the published document said nothing that could tell a reader which. The
 * pins are narrow and say so: they cannot check the description is RIGHT, only
 * that it states the instant and names the other reader of it.
 */
class RestWindowExportContractTest {
    private fun schema(name: String) = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/$name")!!.readBytes().decodeToString(),
    ).jsonObject

    private val restS
        get() = schema("session-export.schema.json")["\$defs"]!!.jsonObject["set"]!!
            .jsonObject["properties"]!!.jsonObject["rest_s"]!!.jsonObject

    @Test
    fun `the published rest_s carries a description at all`() {
        val description = restS["description"]?.jsonPrimitive?.content.orEmpty()
        assertTrue(
            description.isNotBlank(),
            "rest_s is published with no description, which is the shape of issue #76",
        )
    }

    @Test
    fun `the published rest_s description says which instant the rest runs from`() {
        val description = restS["description"]?.jsonPrimitive?.content.orEmpty()
        assertTrue(
            "called over" in description,
            "the published rest_s description never says which instant the rest runs from: $description",
        )
    }

    /** The stream that reads the same instant, so the two can be joined. */
    @Test
    fun `the published rest_s description names the rest-HR stream sharing that instant`() {
        val description = restS["description"]?.jsonPrimitive?.content.orEmpty()
        assertTrue(
            "rest_before_hrm" in description,
            "the published rest_s description does not name the stream sharing that instant: $description",
        )
    }
}
