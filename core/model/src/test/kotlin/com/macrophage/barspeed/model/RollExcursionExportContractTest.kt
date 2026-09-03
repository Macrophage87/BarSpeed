package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the archive's two documents say about `rollExcursion_deg`. Issue #133.
 *
 * The key lives in the raw zip's `meta.json`, which has no published schema of
 * its own, so the two places a reader is told what it means are the
 * `schemaVersion` changelog in `session-export.schema.json` -- the archive's
 * only versioned document -- and the analysis prompt in `GuideScreen.kt`, which
 * is what the READ SESSION PROMPT button puts on the clipboard and therefore
 * the copy an analysis actually receives.
 *
 * Both statements are wrong once the figure is windowed and unwrapped, and the
 * prompt's is wrong in a way that matters: it tells the reader that "hundreds"
 * of degrees is the top of the scale, which was true only because the old
 * arithmetic could not produce anything else.
 *
 * Its own file rather than a method on [SchemaContractTest], which sits on
 * detekt's `LargeClass` limit -- the reason [RestWindowExportContractTest] is
 * separate too.
 *
 * These pins cannot check that either statement is RIGHT. They check that each
 * says which interval the figure covers, names the key that publishes the
 * answer, and no longer carries the ceiling claim.
 */
class RollExcursionExportContractTest {
    private val schemaVersionDescription: String
        get() = Json.parseToJsonElement(
            javaClass.getResourceAsStream("/session-export.schema.json")!!.readBytes().decodeToString(),
        ).jsonObject.getValue("properties").jsonObject.getValue("schemaVersion").jsonObject["description"]
            ?.jsonPrimitive?.content.orEmpty()

    private val analysisPrompt: String
        get() = checkNotNull(
            javaClass.getResourceAsStream("/kotlin/com/macrophage/barspeed/ui/screens/GuideScreen.kt"),
        ) {
            "GuideScreen.kt is not on the test classpath - see the include filter in core/model/build.gradle.kts"
        }.readBytes().decodeToString()

    // ---- the versioned document -------------------------------------------

    /**
     * The whole rule sentence, not a token from it.
     *
     * A substring check for `rollExcursion_deg` alone would pass on a changelog
     * that merely mentioned the key, and the entry exists because the figure's
     * MEANING moved.
     */
    @Test
    fun `the changelog states the interval the excursion is measured over`() {
        assertTrue(
            "measured over the set's WORKING WINDOW" in schemaVersionDescription,
            "the changelog never says which interval rollExcursion_deg covers",
        )
        assertTrue(
            "workStartedAt_ms" in schemaVersionDescription && "terminal cue" in schemaVersionDescription,
            "the changelog names neither bound of the window it claims to use",
        )
    }

    @Test
    fun `the changelog states that the signal is unwrapped`() {
        assertTrue(
            "unwrapped" in schemaVersionDescription,
            "the changelog does not say the roll signal is unwrapped, which is half the change",
        )
    }

    @Test
    fun `the changelog names the key that says which interval was used`() {
        assertTrue(
            "rollExcursionBasis" in schemaVersionDescription,
            "a new key is written into the archive and the changelog does not name it",
        )
    }

    // ---- the copy an analysis actually receives ---------------------------

    /**
     * The prompt told the reader that hundreds of degrees was the top of the
     * scale. That was a property of the arithmetic, not of the mount, and it is
     * deleted rather than reworded: on the same captures the sweeps are 909.0
     * and 515.2, and a reader holding the old sentence will treat both as the
     * same "it tumbled".
     */
    @Test
    fun `the analysis prompt no longer claims hundreds is the top of the scale`() {
        assertFalse(
            "hundreds means gravity leaked into every sample" in analysisPrompt,
            "the prompt still states a ceiling the figure no longer has",
        )
    }

    @Test
    fun `the analysis prompt says which interval the excursion covers`() {
        assertTrue(
            "\"rollExcursionBasis\"" in analysisPrompt,
            "the prompt never mentions rollExcursionBasis, so no analysis reading it will use that key",
        )
        assertTrue(
            "workingWindow" in analysisPrompt,
            "the prompt does not give the reader the spelling of the basis it must match on",
        )
    }

    @Test
    fun `the analysis prompt warns that older archives carry the unwindowed figure`() {
        assertTrue(
            "wholeCapture" in analysisPrompt,
            "the prompt does not tell the reader how an unwindowed set identifies itself",
        )
    }
}
