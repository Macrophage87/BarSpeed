package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #203. A coaching LLM given the shipped prompt declined to supply a
 * body weight it genuinely had, because the paragraph opened with
 * prohibition, never stated what the number is FOR, never said what
 * precision is fit for purpose, and drew the permitted-source line in the
 * wrong place -- "if I told you in this conversation or it is in a session
 * export" excludes the source that actually exists, a connected scale or
 * health platform the coach can read without the lifter ever typing a
 * number.
 *
 * `PLAN_PROMPT` (`GuideScreen.kt`) is what a plan-writing LLM actually reads;
 * `plan.schema.json`'s `properties.bodyweight_kg.description` is the
 * published contract. They are two independent documents and #198's own
 * history is the proof they drift if only one is edited -- so this pins that
 * both state the same four things, rather than trusting a single file's
 * wording: the load-arithmetic purpose, the permitted-source (not
 * permitted-channel) provenance rule, the fourteen-day tolerance that
 * matches [BodyWeightPromptPolicy.STALE_AFTER_DAYS], and the instruction to
 * omit rather than supply anything staler than that. The anti-fabrication
 * rule -- never invent a figure from lifts, a typical lifter, or an old plan
 * -- is pinned as surviving in both, unweakened, because the owner's own
 * correction on the issue said explicitly that softening it is the worse
 * trade.
 *
 * No vendor or product is named in either copy, on the owner's instruction:
 * the rule is about what makes a number trustworthy, not which integration
 * produced it, or it goes stale every time the owner changes tools.
 */
class BodyWeightPromptCopyContractTest {
    private val prompt: String =
        checkNotNull(
            javaClass.getResourceAsStream("/kotlin/com/macrophage/barspeed/ui/screens/GuideScreen.kt"),
        ) {
            "GuideScreen.kt is not on the test classpath - see the include filter in core/model/build.gradle.kts"
        }.readBytes().decodeToString()

    private val schemaDescription: String =
        Json.parseToJsonElement(
            javaClass.getResourceAsStream("/plan.schema.json")!!.readBytes().decodeToString(),
        ).jsonObject["properties"]!!.jsonObject["bodyweight_kg"]!!
            .jsonObject["description"]!!.jsonPrimitive.content

    private val bannedVendorWords =
        listOf(
            "Apple Health",
            "Google Fit",
            "Fitbit",
            "Withings",
            "Garmin",
            "Oura",
            "Samsung Health",
            "MyFitnessPal",
        )

    @Test
    fun `both copies state the load-arithmetic purpose before any prohibition`() {
        listOf("prompt" to prompt, "schema" to schemaDescription).forEach { (where, text) ->
            assertTrue(
                "load arithmetic" in text,
                "$where never states that bodyweight_kg is a load-arithmetic input, not a health datum",
            )
        }
    }

    @Test
    fun `both copies permit a measurement from any genuine source, not only a typed one`() {
        listOf("prompt" to prompt, "schema" to schemaDescription).forEach { (where, text) ->
            assertTrue(
                "connected scale" in text,
                "$where still restricts a permitted bodyweight to what the lifter typed or exported",
            )
            assertTrue(
                "health platform" in text,
                "$where never names a health platform as a permitted source",
            )
        }
    }

    @Test
    fun `both copies name fourteen days as the tolerance, matching the app's own threshold`() {
        listOf("prompt" to prompt, "schema" to schemaDescription).forEach { (where, text) ->
            assertTrue(
                "FOURTEEN DAYS" in text,
                "$where does not say fourteen days is fit for purpose, so it can disagree with " +
                    "BodyWeightPromptPolicy.STALE_AFTER_DAYS",
            )
        }
    }

    @Test
    fun `both copies say to omit rather than supply a stale measurement`() {
        listOf("prompt" to prompt, "schema" to schemaDescription).forEach { (where, text) ->
            assertTrue(
                "OMIT" in text && "older than that" in text,
                "$where never tells the model to omit a measurement older than the tolerance rather than send it",
            )
        }
    }

    @Test
    fun `the anti-fabrication rule survives, unweakened, in both copies`() {
        listOf("prompt" to prompt, "schema" to schemaDescription).forEach { (where, text) ->
            assertTrue(
                "not from a typical lifter" in text,
                "$where dropped the anti-fabrication rule's typical-lifter case",
            )
            assertTrue(
                "not from an old plan" in text,
                "$where dropped the anti-fabrication rule's old-plan case",
            )
            assertTrue(
                "base load" in text,
                "$where never says what a guessed figure corrupts",
            )
        }
    }

    @Test
    fun `neither copy names a vendor or product as the permitted source`() {
        listOf("prompt" to prompt, "schema" to schemaDescription).forEach { (where, text) ->
            bannedVendorWords.forEach { vendor ->
                assertFalse(
                    vendor in text,
                    "$where names \"$vendor\", which goes stale the moment the owner changes tools",
                )
            }
        }
    }
}
