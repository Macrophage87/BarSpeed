package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertFalse
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
            "exercise", "notes", "description", "additional_notes", "start", "concentric",
            "sensorInverted", "sensorOnStack", "travelRatio", "plane", "bodyweight",
            "implementCount", "optional", "prep_s", "sensors", "sets",
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
        listOf(
            "schemaVersion",
            "planName",
            "sessions",
            "name",
            "exercises",
            "bodyweight_kg",
            "bodyweight_lb",
        ).forEach(::assertDocuments)
    }

    /**
     * The prompt tells the model what a guessed bodyweight costs, and gives it
     * the spelling of "not known".
     *
     * This is the one key in the contract whose WRONG value is silent: it
     * becomes the base load of every `bodyweight: true` set, so a guess does
     * not fail validation, does not warn, and shows up only as recorded loads
     * and powers that are wrong by a constant nobody can recover afterwards.
     * The model has to be told to omit it, which means being told why.
     *
     * Narrow, and said so: this cannot check that the prompt teaches the rule
     * well, only that the omit-when-unknown instruction and the consequence
     * that motivates it are both in the text an LLM is actually handed.
     */
    @Test
    fun `the plan prompt tells the model to omit an unknown bodyweight, and why`() {
        assertTrue(
            "do not guess" in prompt,
            "the plan prompt never tells the model not to guess a bodyweight it was not given",
        )
        assertTrue(
            "base load" in prompt,
            "the plan prompt never names what a wrong bodyweight costs, so omitting it reads as stylistic",
        )
    }

    @Test
    fun `the plan prompt documents kind`() {
        // Red until the prompt is rewritten. kind is declarable from the commit
        // before this one, and a key the prompt never mentions is a key no
        // generated plan will carry, so declaring it would stay theoretical.
        assertDocuments("kind")
    }

    /**
     * The prompt is the only statement of the contract anything actually sends
     * anywhere, so a claim left standing here outlives the same claim corrected
     * in the schema. It told the model a prep applies only to sets with a
     * tempo, which this change makes false.
     *
     * Narrow, and said so: this cannot check the prompt is right, only that the
     * sentence this change falsified is gone and the case it was wrong about is
     * named.
     */
    @Test
    fun `the plan prompt does not tell the model a prep needs a tempo`() {
        assertFalse(
            "ONLY APPLIES TO SETS WITH A tempo" in prompt,
            "the plan prompt still tells the model a prep needs a tempo",
        )
        assertTrue(
            "a hold or a carry" in prompt,
            "the plan prompt never tells the model a prep applies to a hold or a carry",
        )
    }

    /**
     * The prompt states the character cap, in figures, and stops telling the
     * model to put form cues in `notes`.
     *
     * A cap the generating model is never told about is a cap that only ever
     * surfaces as a refusal at the import gate, after the plan is written --
     * and the owner's requirement on this change is stronger than that: the
     * model has to know the limit exists so it FRONT-LOADS the cue that
     * decides how the set is performed, rather than writing 220 characters of
     * preamble and putting the safety line in the overflow.
     *
     * Narrow, and said so: this cannot check the prompt teaches the split well,
     * only that the number is in it and that the sentence pointing form cues at
     * the old key is gone.
     */
    @Test
    fun `the plan prompt states the description cap in figures`() {
        assertTrue(
            "${PlanFile.DESCRIPTION_MAX_CHARS} characters" in prompt,
            "the plan prompt never states the ${PlanFile.DESCRIPTION_MAX_CHARS}-character description cap, " +
                "so a generated plan will hit it as a refusal instead of writing to it",
        )
        assertFalse(
            "put form cues in exercise notes" in prompt,
            "the plan prompt still sends form cues to \"notes\", which is now the slot behind the tap",
        )
    }

    /**
     * The prompt's reading key names the void mark and says what to do with
     * it (#60).
     *
     * DIFFERENTIAL. Fails at the commit that introduces it: the prompt
     * documents `warmup`, `failed`, `limiter`, `limiterNote`,
     * `repMetricsComplete`, `velocityLossBasis` and `sensors`, and has never
     * named `voided`. This is the copy the COPY PLAN PROMPT button puts on
     * the clipboard, so a key absent from it is a key the reading model is
     * never told about -- and the failure mode is not a missing feature, it
     * is a wrong number: a coach handed a document with a voided set and no
     * instruction counts a set nobody performed into volume and into every
     * trend, which is the read the mark exists to stop.
     *
     * The absence rule is asserted with the rest, because it is the half a
     * reader cannot recover: no mark on a session recorded before database
     * v16 means the app could not ask, not that the set was performed.
     */
    @Test
    fun `the plan prompt tells the reader what a voided set is and to drop it`() {
        assertDocuments("voided")
        assertDocuments("voidReason")
        assertTrue(
            "did not perform" in prompt,
            "the plan prompt never says what the void mark asserts, so the key reads as unexplained",
        )
        assertTrue(
            "database v16" in prompt,
            "the plan prompt never says an absent mark can mean the app could not ask",
        )
    }

    /**
     * The prompt no longer tells the reader that a session ending early
     * simply has fewer sets in it (#60).
     *
     * DIFFERENTIAL, and a correction rather than an addition. The sentence
     * was true when it was written and this change falsifies half of it. The
     * plan-side clause still holds -- a plan cannot mark prescribed work as
     * skipped -- but the export-side clause now mis-instructs, because a set
     * the lifter DID record and did not perform stays in the document
     * carrying its mark instead of vanishing from it. A reader following the
     * old sentence reads a voided set as work that happened.
     *
     * Asserted within one line and never across a line boundary, for the
     * reason this class's KDoc gives: the source is not byte-identical across
     * machines, since `core.autocrlf=true` leaves the working copy a trailing
     * carriage return CI's copy does not carry.
     */
    @Test
    fun `the plan prompt does not tell the reader an unperformed set is simply absent`() {
        assertFalse(
            "simply has fewer sets in its export." in prompt,
            "the plan prompt still tells the reader an unperformed set is simply absent from the export",
        )
        assertTrue(
            "recorded and not performed" in prompt,
            "the plan prompt never separates a set never started from one recorded and not performed",
        )
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
