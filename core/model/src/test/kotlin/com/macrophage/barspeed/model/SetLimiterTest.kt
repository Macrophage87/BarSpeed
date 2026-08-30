package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The closed vocabulary a failed set's reason is recorded in, the page it is
 * offered on, and the note that rides beside it (#189).
 */
class SetLimiterTest {
    @Test
    fun `every answer stores its own lowercased name and reads back as itself`() {
        for (limiter in SetLimiter.entries) {
            assertEquals(limiter.name.lowercase(), limiter.stored)
            assertEquals(limiter, SetLimiter.ofStored(limiter.stored))
        }
    }

    /**
     * A value this build has never heard of reads as no answer.
     *
     * The column is TEXT, so a row written by a LATER build can carry one. A
     * reader that threw there would fail while the lifter is mid-session, over
     * a set that is already safely recorded.
     */
    @Test
    fun `an unrecognised stored value reads as no answer rather than throwing`() {
        assertNull(SetLimiter.ofStored("tempo"))
        assertNull(SetLimiter.ofStored("MUSCLE"))
        assertNull(SetLimiter.ofStored(""))
        assertNull(SetLimiter.ofStored(null))
    }

    /**
     * No member stands for absence.
     *
     * Absence is a null column. A member for it would be counted as an answer
     * by the grouping the enum exists for, which is the failure this pin
     * exists to catch when someone adds a convenient "unknown" rung.
     */
    @Test
    fun `no answer stands for the absence of an answer`() {
        val absenceWords = setOf("unknown", "none", "unspecified", "na", "notgiven", "no_answer")
        assertEquals(
            emptyList(),
            SetLimiter.entries.filter { it.stored in absenceWords },
            "a member stands for absence, which a null column already says",
        )
    }

    @Test
    fun `the rep page offers every answer exactly once, in the enum's order`() {
        assertEquals(SetLimiter.entries.toList(), SetLimiterScale.tiles(timed = false).map { it.limiter })
    }

    /**
     * A hold or a carry drops the pace answer and keeps the rest.
     *
     * `EffortScale.tiles` already branches on `timed`; this follows that
     * precedent rather than inventing a second one. There is no pace to lose
     * in a plank.
     */
    @Test
    fun `a hold drops the pace answer and nothing else`() {
        val timed = SetLimiterScale.tiles(timed = true).map { it.limiter }
        assertEquals(SetLimiter.entries - SetLimiter.PACE, timed)
    }

    /**
     * The hold branch rewords exactly the two answers whose noun changes and
     * leaves every other word alone.
     *
     * The reword is the point of the branch and the untouched answers are the
     * point of pinning it: a second full caption table would let the two
     * ladders drift apart silently.
     */
    @Test
    fun `a hold rewords only the two answers whose noun changes`() {
        val rep = SetLimiterScale.tiles(timed = false).associate { it.limiter to it.label }
        val timed = SetLimiterScale.tiles(timed = true).associate { it.limiter to it.label }
        assertEquals(
            setOf(SetLimiter.MUSCLE, SetLimiter.FORM),
            timed.filter { (limiter, label) -> rep.getValue(limiter) != label }.keys,
        )
        assertEquals("Could not hold it any longer", timed.getValue(SetLimiter.MUSCLE))
        assertEquals("Position broke down", timed.getValue(SetLimiter.FORM))
        assertEquals("Grip gave out", timed.getValue(SetLimiter.GRIP))
    }

    /**
     * Pain is its own group, and it sits after every performance answer.
     *
     * #189 requires the visual separation in as many words. The page draws a
     * boundary between groups, so the boundary has to be a boundary in this
     * list rather than an index the drawing code counts to.
     */
    @Test
    fun `pain is the only welfare answer and it follows every performance answer`() {
        val tiles = SetLimiterScale.tiles(timed = false)
        assertEquals(
            listOf(SetLimiter.PAIN),
            tiles.filter { it.group == SetLimiterGroup.WELFARE }.map { it.limiter },
        )
        val lastPerformance = tiles.indexOfLast { it.group == SetLimiterGroup.PERFORMANCE }
        val pain = tiles.indexOfFirst { it.limiter == SetLimiter.PAIN }
        assertTrue(pain > lastPerformance, "pain is drawn among the performance answers")
    }

    /**
     * The outside-reason answer is its own group too.
     *
     * It is what keeps "every unfinished set is a fail" honest: it lets
     * analysis discard a set rather than read it as capacity, and grouping it
     * with the performance answers would lose exactly that.
     */
    @Test
    fun `the outside reason is neither a performance answer nor free text`() {
        val tiles = SetLimiterScale.tiles(timed = false).associate { it.limiter to it.group }
        assertEquals(SetLimiterGroup.CONTEXT, tiles.getValue(SetLimiter.OUTSIDE))
        assertEquals(
            listOf(SetLimiter.OTHER),
            SetLimiterScale.tiles(timed = false).filter { it.group == SetLimiterGroup.FREE }.map { it.limiter },
        )
    }

    // ---- the free-text note --------------------------------------------------

    @Test
    fun `an ordinary note is stored exactly as it was typed`() {
        assertEquals("rack was taken", SetLimiter.normalizeNote("rack was taken"))
    }

    @Test
    fun `newlines, tabs and runs of spaces collapse to one space`() {
        assertEquals("left elbow twinged", SetLimiter.normalizeNote("  left\nelbow\t\t twinged  "))
    }

    /**
     * A quote and a backslash are removed, and the reason is not cosmetic.
     *
     * The raw archive's set manifest is assembled as text and escapes nothing,
     * so either character in a note makes the WHOLE manifest unparseable --
     * every set in that session, not just this one.
     */
    @Test
    fun `a double quote and a backslash are removed`() {
        assertEquals("it went ping", SetLimiter.normalizeNote("it went \"ping\\\""))
    }

    /**
     * The characters that survive normalization survive a hand-built JSON
     * string byte for byte.
     *
     * This is the pin that makes "exported verbatim" a fact. It builds the
     * manifest's exact construction -- a key and a value pasted between quotes
     * with no escaping -- and parses it back.
     */
    @Test
    fun `a normalized note survives the manifest's unescaped string construction`() {
        val typed = "felt \"wrong\" — right hip \\ pinched; stopped\tearly"
        val stored = assertNotNull(SetLimiter.normalizeNote(typed))
        val manifest = """{"limiterNote": "$stored"}"""
        assertEquals(
            stored,
            Json.parseToJsonElement(manifest).jsonObject.getValue("limiterNote").jsonPrimitive.content,
        )
    }

    @Test
    fun `a note longer than the cap is truncated to the cap`() {
        val long = "a".repeat(SetLimiter.NOTE_MAX_CHARS + 40)
        assertEquals(SetLimiter.NOTE_MAX_CHARS, SetLimiter.normalizeNote(long)!!.length)
    }

    /**
     * Blank is no note.
     *
     * An empty string stored beside an `other` answer would be absence
     * rendered as a value: a reader cannot tell it from a note the lifter
     * typed and then deleted.
     */
    @Test
    fun `a blank or whitespace-only note reads as no note`() {
        assertNull(SetLimiter.normalizeNote(""))
        assertNull(SetLimiter.normalizeNote("   \n\t "))
        assertNull(SetLimiter.normalizeNote("\"\\"))
        assertNull(SetLimiter.normalizeNote(null))
    }

    /**
     * Normalizing twice changes nothing.
     *
     * It runs at the write AND again at the publish boundary, over a note this
     * build may not have written, so a second pass that trimmed or shortened a
     * little more would publish something other than what the row holds. It no
     * longer runs on a keystroke -- [SetLimiter.sanitizeForTyping] is what the
     * field applies.
     */
    @Test
    fun `normalizing an already normalized note changes nothing`() {
        val samples =
            listOf(
                "rack taken",
                "  cramp  in\tcalf ",
                "z".repeat(SetLimiter.NOTE_MAX_CHARS + 5),
                "quote \" and slash \\ gone",
            )
        for (sample in samples) {
            val once = SetLimiter.normalizeNote(sample)
            assertEquals(once, SetLimiter.normalizeNote(once))
        }
    }

    /**
     * The characters the manifest cannot carry are refused AS THEY ARE TYPED.
     *
     * This is the half of the field's job that must not move: a double quote
     * or a backslash reaching the raw archive's manifest does not corrupt the
     * note, it makes the whole manifest unparseable for every set in the
     * session. Dropping them in front of the lifter is what makes "published
     * verbatim" true of what they watched themselves type.
     */
    @Test
    fun `the field drops a double quote and a backslash as they are typed`() {
        assertEquals("it went ping", SetLimiter.sanitizeForTyping("it went \"ping\\\""))
    }

    /** An interior newline is a space in the field, as it is at the write. */
    @Test
    fun `a newline typed inside the note reads as a space`() {
        assertEquals("cramp in calf", SetLimiter.sanitizeForTyping("cramp\nin\tcalf"))
    }

    /** The cap is the field's, not only the write's. */
    @Test
    fun `the field holds no more than the note cap`() {
        val long = "z".repeat(SetLimiter.NOTE_MAX_CHARS + 40)
        assertEquals(SetLimiter.NOTE_MAX_CHARS, SetLimiter.sanitizeForTyping(long).length)
    }

    /**
     * An empty field is an empty string and never a null.
     *
     * Absence is [SetLimiter.normalizeNote]'s answer to give, once, at the
     * write. A field cannot hold absence; it holds "".
     */
    @Test
    fun `an empty field stays an empty string`() {
        assertEquals("", SetLimiter.sanitizeForTyping(""))
    }

    /**
     * A SPACE ON ITS OWN IS HELD. Second differential of the same defect.
     *
     * This assertion was the second half of `an empty field stays an empty
     * string` until here, where it is corrected and moved out: that pin was
     * mine, it was written to characterize the delegating stub, and it pinned
     * the TRIM -- the one transform that is not safe on a prefix and the one
     * this round exists to move to the write.
     *
     * A lone space is the first keystroke of "rack was taken" reaching a word
     * boundary, and a field that answers it with "" is a field that cannot be
     * typed a phrase into. What is still collapsed is the RUN: three spaces
     * are one, because a lifter cannot have meant three.
     *
     * The note stored from a field holding only a space is still nothing --
     * normalizeNote returns null for blank -- so this widens what the field
     * may hold and not what the row may carry.
     */
    @Test
    fun `a space typed on its own is held as the word boundary it is`() {
        assertEquals(" ", SetLimiter.sanitizeForTyping("   "))
        assertNull(SetLimiter.normalizeNote(SetLimiter.sanitizeForTyping("   ")))
    }

    /**
     * A SPACE CAN BE TYPED. This is the differential.
     *
     * The note field is a value-driven OutlinedTextField: it re-applies its
     * transform to the WHOLE accumulated value on every keystroke and shows
     * the result back. So the transform never sees the finished note -- it is
     * applied to every PREFIX of it, in turn, and the field can hold only what
     * that loop converges to.
     *
     * `normalizeNote` ends by collapsing and trimming, so while the FIELD
     * applied it -- at 6b8d7f2 (CI run 33320046206, conclusion failure) and
     * every commit before it -- the keystroke that added a trailing space
     * produced a value identical to the one already on screen and the space
     * was deleted the instant it was typed. "rack was taken" became
     * "rackwastaken", and #189's one free-text answer could hold only a single
     * run-on token. The field now applies [SetLimiter.sanitizeForTyping].
     *
     * The fold here is the field's own contract rather than an approximation
     * of it, and the expected value is `normalizeNote` of the finished phrase
     * rather than a literal, because what the lifter watches themselves type
     * has to be what the write stores.
     */
    @Test
    fun `a phrase typed one character at a time keeps its spaces`() {
        val phrase = "rack was taken"
        val typed = phrase.fold("") { held, ch -> SetLimiter.sanitizeForTyping(held + ch) }
        assertEquals(SetLimiter.normalizeNote(phrase), typed)
    }

    /**
     * The same loop over the string the published example happens to carry
     * today, kept here as a unit case beside its siblings.
     *
     * The DOCUMENT is pinned in [SchemaLimiterContractTest], which reads the
     * real file; this test would not notice the example changing.
     */
    @Test
    fun `the published example's note is a note the field can hold`() {
        val phrase = "lost count and stopped, not sure how many I had done"
        val typed = phrase.fold("") { held, ch -> SetLimiter.sanitizeForTyping(held + ch) }
        assertEquals(phrase, SetLimiter.normalizeNote(typed))
    }
}
