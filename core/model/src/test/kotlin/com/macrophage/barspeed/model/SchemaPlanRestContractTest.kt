package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The plan's set-level `rest_s` says which rest it is (#218).
 *
 * The session export has described its copy of the key since 1.23 (#157),
 * and publishes the measured rest beside it as `restMeasured_s`; the plan
 * schema, which is where a plan author meets the key, described nothing. The
 * trap the issue measured on field-37 is direction: the key prescribes the
 * rest AFTER the set that declares it, while the raw archive's
 * `rest_before_hrm` for that set is the window BEFORE it.
 *
 * A description edit only, so the plan schema's version does not move; the
 * pin is that the description states the direction, the minimum, the default
 * and where the measured rest is published.
 */
class SchemaPlanRestContractTest {
    private val plan: JsonObject = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/plan.schema.json")!!.readBytes().decodeToString(),
    ).jsonObject

    @Test
    fun `the plan's rest_s says it is the rest after its own set and never a measurement`() {
        val description = plan.getValue("\$defs").jsonObject.getValue("set").jsonObject
            .getValue("properties").jsonObject.getValue("rest_s").jsonObject
            .getValue("description").jsonPrimitive.content
        listOf(
            "AFTER this set",
            "never to the set after it",
            "MINIMUM",
            "the app's default",
            "never a measurement",
            "`restMeasured_s`",
            "`rest_before_hrm`",
            "#218",
        ).forEach { assertTrue(it in description, "the plan's rest_s does not state: $it") }
    }
}
