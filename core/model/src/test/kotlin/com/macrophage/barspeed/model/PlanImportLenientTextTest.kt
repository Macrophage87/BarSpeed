package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Differentials for the wrapping a plan picks up on its way to the phone
 * (UX review 2026-11-06, plans F4): a UTF-8 byte-order mark at the start of a
 * saved file, and the ```json fence a chat reply puts round a code block.
 * Before [PlanImport.parse] stripped them, each of these was refused with
 * "Not valid plan JSON: Unexpected JSON token at offset 0", a parser message
 * about a document that was fine.
 *
 * Only the SURROUNDING wrapping is removed. Prose before the fence is still a
 * refusal: guessing where a plan starts inside free text is a different and
 * riskier rule than peeling off a known envelope.
 */
class PlanImportLenientTextTest {
    private val bare =
        """{"schemaVersion":"1.4","planName":"P","sessions":[""" +
            """{"name":"S","exercises":[{"exercise":"back_squat","sets":[{"reps":5,"load_kg":100}]}]}]}"""

    private fun assertAccepted(text: String) {
        val result = PlanImport.parse(text)
        assertNotNull(result.plan, "refused: ${result.errors}")
        assertEquals(emptyList(), result.errors)
        assertEquals("P", result.plan.planName)
    }

    @Test
    fun `the bare document is accepted, as before`() {
        assertAccepted(bare)
    }

    @Test
    fun `a leading byte-order mark is ignored`() {
        assertAccepted("\uFEFF" + bare)
    }

    @Test
    fun `a json-tagged markdown fence round the document is ignored`() {
        assertAccepted("```json\n$bare\n```")
    }

    @Test
    fun `an untagged markdown fence round the document is ignored`() {
        assertAccepted("```\n$bare\n```")
    }

    @Test
    fun `whitespace, CRLF line ends and a BOM round a fence are all ignored`() {
        assertAccepted("\uFEFF\r\n  ```json\r\n$bare\r\n```  \r\n")
    }

    @Test
    fun `unknown-key warnings are read from the unwrapped document`() {
        // The key check parses the text a second time; if it saw the fenced
        // text it would find no object and report nothing, so a fenced plan
        // would silently lose the misspelt-key warning a bare one gets.
        val withExtra = bare.replace("\"planName\":\"P\"", "\"planName\":\"P\",\"coachNote\":\"x\"")
        val bareWarnings = PlanImport.parse(withExtra).warnings
        assertTrue(bareWarnings.isNotEmpty())
        assertEquals(bareWarnings, PlanImport.parse("\uFEFF```json\n$withExtra\n```").warnings)
    }

    @Test
    fun `prose before the fence is still refused`() {
        val result = PlanImport.parse("Here is your plan:\n```json\n$bare\n```")
        assertNull(result.plan)
        assertTrue(result.errors.single().startsWith("Not valid plan JSON"))
    }
}
