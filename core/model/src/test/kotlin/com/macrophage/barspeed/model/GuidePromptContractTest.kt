package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The plan prompt is a fifth statement of the plan contract, and it is the only
 * one a plan-writing LLM actually reads: `GuideScreen.kt`'s `PLAN_PROMPT` is
 * what the COPY PLAN PROMPT button puts on the clipboard. The published schema
 * is authoritative but nothing sends it anywhere; the README and PROMPTS.md are
 * prose nobody pastes. So a key that exists in the model and is missing from
 * the prompt is a key no generated plan will ever contain.
 *
 * This reads the real source file through the test resources source set — a
 * copy would drift, which is the whole reason `SchemaContractTest` reads the
 * published schemas rather than copies.
 *
 * Assertions are substring `contains` on the QUOTED form of each key, and
 * deliberately nothing else. Quoted, because the bare tokens are ordinary
 * English in 15 KB of coaching prose — "sets" occurs 8 times bare and once
 * quoted, "reps" 7 and 2, "tempo" 13 and 2 — so a bare-token assertion is
 * satisfied by the surrounding sentences and is close to a test that cannot
 * fail. Nothing else, because the file is not byte-identical across machines:
 * `core.autocrlf=true` with no tracked `.gitattributes` means the working copy
 * is 15,366 bytes on Windows and 15,183 on CI. Line COUNTS agree (183 either
 * way); line CONTENT does not, since each carries a trailing carriage return on
 * one of them. A substring test is indifferent to that; a size, an offset or a
 * split-by-line index is not.
 */
class GuidePromptContractTest {
    private val prompt: String =
        checkNotNull(
            javaClass.getResourceAsStream("/kotlin/com/macrophage/barspeed/ui/screens/GuideScreen.kt"),
        ) {
            "GuideScreen.kt is not on the test classpath - see the include filter in core/model/build.gradle.kts"
        }.readBytes().decodeToString()

    private fun assertDocuments(key: String) = assertTrue(
        prompt.contains("\"$key\""),
        "the plan prompt never mentions \"$key\", so no plan generated from it will use that key",
    )

    @Test
    fun `the plan prompt documents every declared exercise key`() {
        listOf(
            "exercise", "notes", "start", "concentric", "sensorInverted",
            "sensorOnStack", "travelRatio", "plane", "bodyweight", "implementCount",
            "optional", "prep_s", "sets",
        ).forEach(::assertDocuments)
    }

    @Test
    fun `the plan prompt documents every declared set key`() {
        listOf(
            "reps", "duration_s", "load_kg", "load_lb", "tempo", "side", "note",
            "targetMeanConcentricVelocity_mps", "velocityLossStop_pct", "rest_s",
        ).forEach(::assertDocuments)
    }

    @Test
    fun `the plan prompt documents every top-level key`() {
        listOf("schemaVersion", "planName", "sessions", "name", "exercises").forEach(::assertDocuments)
    }

    @Test
    fun `the plan prompt documents kind`() {
        // Red until the prompt is rewritten. kind is declarable from the commit
        // before this one, and a key the prompt never mentions is a key no
        // generated plan will carry, so declaring it would stay theoretical.
        assertDocuments("kind")
    }

    @Test
    fun `the plan prompt states the schema version the code writes`() {
        // The prompt names the contract version in prose. There are four other
        // statements of this contract in the repo and they already disagree;
        // this is the one an LLM is actually given, so it is the one pinned.
        assertTrue(
            prompt.contains(PlanFile.SCHEMA_VERSION),
            "the plan prompt does not mention version ${PlanFile.SCHEMA_VERSION}, " +
                "so it is describing a contract the app no longer publishes",
        )
    }
}
