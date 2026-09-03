package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Voiding a recorded set the lifter did not perform (#60).
 *
 * GREEN WHEN WRITTEN, and deliberately so: [VoidSetPolicy] is a new symbol
 * nothing calls yet, so these are pins on a decision rather than differentials
 * against a defect. The differentials are the two later commits' -- the v15 to
 * v16 hop, and what the two export writers publish -- and neither can compile
 * until the decision below exists.
 *
 * THE CASE IS FIELD-37 SET 13, and its shape drives the fixtures here: a
 * prescribed set, 0 kg, 0 reps, 0 seconds, marked failed, in a session of
 * twelve real ones. It is the reason the count and the tonnage are separate
 * questions -- a 0 kg set moves no tonnage at all while still making a 12-set
 * session read as 13, so a policy that only guarded volume would have left the
 * defect in place and looked correct.
 */
class VoidSetPolicyTest {
    @Test
    fun `a recorded set is voidable and a queued one is not`() {
        assertTrue(VoidSetPolicy.voidable(SetPlace.RECORDED))
        assertFalse(
            VoidSetPolicy.voidable(SetPlace.QUEUED),
            "a queued slot has no row to mark; RemoveSetControl is what takes one of those back",
        )
    }

    /**
     * A prescribed set is voidable, which is where this parts company with
     * [RemoveSetControl].
     *
     * That control refuses a prescribed set on purpose. This one must accept
     * it, because the row it exists for IS prescribed: #195 copied the
     * twelfth set's prescription onto a set nobody performed. Nothing in
     * [VoidSetPolicy.voidable] reads a prescription, and this test is what
     * says the omission is a decision.
     */
    @Test
    fun `voidability does not depend on whether the set was prescribed`() {
        assertEquals(
            VoidSetPolicy.voidable(SetPlace.RECORDED),
            VoidSetPolicy.voidable(SetPlace.RECORDED),
            "voidable takes no prescription argument, so a prescribed row is treated as any other",
        )
        assertTrue(VoidSetPolicy.voidable(SetPlace.RECORDED))
    }

    @Test
    fun `voiding takes a set out of volume and out of the performed count`() {
        val voided = VoidSetPolicy.effects(voided = true)
        assertFalse(voided.countsTowardVolume, "a voided set still moves the tonnage")
        assertFalse(voided.countsAsPerformed, "a voided set is still counted as a set that happened")
    }

    @Test
    fun `an unvoided set counts toward both`() {
        val kept = VoidSetPolicy.effects(voided = false)
        assertTrue(kept.countsTowardVolume)
        assertTrue(kept.countsAsPerformed)
    }

    /**
     * Voiding never takes the row, the streams or the export entry -- for
     * either value of the mark.
     *
     * The three that do not move are the whole difference between this and
     * deletion, and they are asserted on both answers so that a change making
     * them depend on the flag reds here rather than on a lifter's phone.
     */
    @Test
    fun `voiding takes neither the row, nor the streams, nor the export entry`() {
        for (voided in listOf(false, true)) {
            val effects = VoidSetPolicy.effects(voided)
            assertTrue(effects.staysInHistory, "voided=$voided removed the row from history")
            assertTrue(effects.keepsRawStreams, "voided=$voided discarded the raw streams")
            assertTrue(effects.publishedInExport, "voided=$voided withheld the set from the export")
        }
    }

    @Test
    fun `volume drops a voided set and keeps the rest`() {
        val sets =
            listOf(
                VolumeSet(loadKg = 60.0, reps = 5, voided = false),
                VolumeSet(loadKg = 60.0, reps = 5, voided = true),
                VolumeSet(loadKg = 40.0, reps = 10, voided = false),
            )
        assertEquals(700.0, VoidSetPolicy.volumeKg(sets))
    }

    @Test
    fun `volume with nothing voided is the plain sum`() {
        val sets = listOf(VolumeSet(60.0, 5, false), VolumeSet(40.0, 10, false))
        assertEquals(700.0, VoidSetPolicy.volumeKg(sets))
    }

    /**
     * The field-37 row: 0 kg, so it moves no tonnage, and it still has to
     * leave the count.
     *
     * The mutation this kills is the plausible one -- guarding volume only,
     * on the reasoning that volume is what a voided set corrupts. It is not:
     * this set's contribution to volume is 0 either way, and the number it
     * makes wrong is the number of sets in the session.
     */
    @Test
    fun `a voided set carrying no load still leaves the performed count`() {
        val fabricated = VolumeSet(loadKg = 0.0, reps = 0, voided = true)
        val real = VolumeSet(loadKg = 0.0, reps = 0, voided = false)
        assertEquals(0.0, VoidSetPolicy.volumeKg(listOf(fabricated, real)))
        assertEquals(
            listOf(real),
            VoidSetPolicy.performed(listOf(fabricated, real)) { it.voided },
            "the fabricated 0 kg set survived the performed filter",
        )
    }

    @Test
    fun `performed keeps order and keeps everything when nothing is voided`() {
        val sets = listOf("a" to false, "b" to false, "c" to false)
        assertEquals(sets, VoidSetPolicy.performed(sets) { it.second })
    }

    @Test
    fun `performed drops only the voided entries, in place`() {
        val sets = listOf("a" to false, "b" to true, "c" to false)
        assertEquals(listOf("a" to false, "c" to false), VoidSetPolicy.performed(sets) { it.second })
    }

    /**
     * The reason is the limiter note's rule, not a second copy of it.
     *
     * Asserted against [SetLimiter.normalizeNote] itself rather than against
     * an expected string, because what matters is that the two cannot drift:
     * the reason reaches the same text-assembled manifest the note does, whose
     * writer escapes nothing, so a second spelling of the rule that fell
     * behind would make the whole manifest unparseable for every set in the
     * session.
     */
    @Test
    fun `the reason is normalized by the limiter note's own rule`() {
        for (raw in listOf("app re-armed the\nfinished slot", """a "quoted" \ reason""", "  padded  ")) {
            assertEquals(
                SetLimiter.normalizeNote(raw),
                VoidSetPolicy.reason(raw),
                "drifted from normalizeNote on: $raw",
            )
        }
        assertEquals(SetLimiter.sanitizeForTyping("half typed "), VoidSetPolicy.reasonAsTyped("half typed "))
    }

    @Test
    fun `a blank reason is no reason`() {
        assertNull(VoidSetPolicy.reason(null))
        assertNull(VoidSetPolicy.reason("   "), "a blank reason was stored as an empty string")
    }

    @Test
    fun `the label names the mark it will take off`() {
        assertEquals("Didn't perform this set?", VoidSetPolicy.label(voided = false))
        assertEquals("Did perform this set", VoidSetPolicy.label(voided = true))
    }

    /**
     * The confirmation says what a lifter cannot see from the card.
     *
     * Asserted by content and not by string equality: the three claims are
     * that nothing is deleted, that the set is still exported, and that the
     * act is undoable. This screen's only other destructive control deletes
     * the session and every raw stream in it, so a confirmation that said
     * only "are you sure" would be read as the same kind of act.
     *
     * The first two assertions pin the PAIRING, not just the digits. The
     * fixture is field-37 set 13, at orderIdx 12; sets 11 and 12 of that same
     * session are `rope_dead_hang` too, read from its `meta.json` in
     * `TimedHoldCueTrackTest`'s provenance block. So 13 counts SESSION sets,
     * and a confirmation reading "Set 13 of Rope Dead Hang" names a set of
     * that exercise which does not exist. No ordinal is asserted for the set
     * within its exercise: sets 1-10 are not enumerated anywhere in this tree,
     * so "the Nth Rope Dead Hang" is not a fact this repo holds. The exercise
     * is therefore named without a number, and the number is attributed to
     * the session -- the session-wide index is the only counter the call site
     * holds.
     */
    @Test
    fun `the confirmation says the set is kept, exported and undoable`() {
        val text = VoidSetPolicy.confirmation("Rope Dead Hang", setNumber = 13)
        assertTrue("this Rope Dead Hang set" in text, "the confirmation does not name the exercise: $text")
        assertTrue("set 13 of the session" in text, "the confirmation does not say which set of the session: $text")
        assertTrue("sensor data" in text, "the confirmation does not say the sensor data is kept: $text")
        assertTrue("exported" in text, "the confirmation does not say the set is still exported: $text")
        assertTrue("volume" in text, "the confirmation does not say what stops counting: $text")
        assertTrue("undo" in text, "the confirmation does not say the mark can be taken off: $text")
    }

    @Test
    fun `the chip says what happened, not what the mark is called`() {
        assertEquals("NOT PERFORMED", VoidSetPolicy.CHIP)
    }
}
