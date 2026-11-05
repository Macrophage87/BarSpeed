package com.macrophage.barspeed.ui.screens

import com.macrophage.barspeed.data.DATABASE_NAME
import com.macrophage.barspeed.data.RescueCompleteness
import com.macrophage.barspeed.data.RescuedDatabase
import com.macrophage.barspeed.model.ByteSize
import com.macrophage.barspeed.model.RecordingHold
import com.macrophage.barspeed.model.RestoreRefusal
import com.macrophage.barspeed.model.RestoreVerdict
import java.io.File
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rescued-database card's string-to-arm, SEND/DISCARD and busy-gate
 * mappings. Issue #122.
 *
 * #111's gate permuted each of these inside `RescuedDatabaseNotice` -- titles
 * swapped between arms, SEND's conditional inverted, DISCARD wired straight to
 * the delete, the busy gate forced open -- and the whole suite stayed green
 * every time. They now live as pure functions in `RescuedCard.kt` and every
 * one of those permutations lands on a named test here.
 *
 * What this cannot reach: whether the composable CALLS these helpers. A card
 * that ignored [rescuedCardButtons] or [rescuedTapEffect] would compile and
 * pass; that step is compile- and lint-gated only. Nothing here renders.
 */
class RescuedCardTest {
    private val wal = "$DATABASE_NAME-wal"

    private fun item(
        files: List<String>,
        totalBytes: Long = 1_003L,
        rescuedAtMs: Long? = 1_700_000_000_000L,
        dir: String = "db-1700000000000",
    ) = RescuedDatabase(File("/rescued", dir), rescuedAtMs, files, totalBytes)

    private val complete = item(listOf(DATABASE_NAME, wal))
    private val fragment = item(listOf(wal), totalBytes = 3L)
    private val nothing = item(emptyList(), totalBytes = 0L)

    @Test
    fun `the fixtures are the three states they claim to be`() {
        assertEquals(RescueCompleteness.COMPLETE, complete.completeness)
        assertEquals(RescueCompleteness.FRAGMENT, fragment.completeness)
        assertEquals(RescueCompleteness.NOTHING, nothing.completeness)
    }

    // ---- the string each arm carries ---------------------------------------

    @Test
    fun `each state carries its own title, and no two share one`() {
        assertEquals("Your training history -- the main database file", rescuedTitle(complete))
        assertEquals("Partial rescue -- may hold data your database is missing", rescuedTitle(fragment))
        assertEquals("Nothing survived this rescue attempt", rescuedTitle(nothing))
    }

    @Test
    fun `the complete discard warning names the size, the files and that nothing else has them`() {
        val text = rescuedDiscardWarning(complete)
        assertTrue(ByteSize.format(1_003L) in text, text)
        assertTrue("($DATABASE_NAME, $wal)" in text, text)
        assertTrue("MOVED these files rather than copying them" in text, text)
        assertTrue(text.endsWith("This cannot be undone."), text)
    }

    @Test
    fun `the fragment discard warning says no database file was saved and that the sidecar may matter`() {
        val text = rescuedDiscardWarning(fragment)
        assertTrue(text.startsWith("This rescue saved no database file -- only $wal, "), text)
        assertTrue("can hold sets your current database does not have" in text, text)
        assertTrue(text.endsWith("This cannot be undone."), text)
    }

    /** The "0 B of rescued data ()" dialog #111 shipped once, pinned shut. */
    @Test
    fun `the empty discard warning says there is nothing to lose and opens no empty brackets`() {
        val text = rescuedDiscardWarning(nothing)
        assertEquals(
            "This rescue attempt saved no files at all, so there is nothing here to lose. Only the card goes.",
            text,
        )
        assertFalse("()" in text)
    }

    @Test
    fun `the detail line names when, how large, and which files`() {
        val clock = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        assertEquals(
            "rescued 2023-11-14 22:13 · ${ByteSize.format(1_003L)} · $DATABASE_NAME, $wal",
            rescuedDetail(complete, clock, ZoneOffset.UTC),
        )
    }

    @Test
    fun `an empty rescue's detail line has no dangling separator, and an undatable one says so`() {
        val clock = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        assertEquals(
            "rescued at an unknown time · ${ByteSize.format(0L)}",
            rescuedDetail(item(emptyList(), totalBytes = 0L, rescuedAtMs = null), clock, ZoneOffset.UTC),
        )
    }

    // ---- which buttons, and when they are live -----------------------------

    @Test
    fun `send is offered on every state that has files, and not on the empty one`() {
        assertTrue(rescuedCardButtons(complete, emptySet()).showSend)
        assertTrue(rescuedCardButtons(fragment, emptySet()).showSend)
        assertFalse(rescuedCardButtons(nothing, emptySet()).showSend, "an empty rescue offered SEND: a 22-byte zip")
    }

    @Test
    fun `an idle card has both buttons live`() {
        for (card in listOf(complete, fragment, nothing)) {
            val buttons = rescuedCardButtons(card, emptySet())
            assertTrue(
                buttons.sendEnabled && buttons.discardEnabled,
                "idle ${card.completeness} card disabled: $buttons",
            )
        }
    }

    /** #111's race: DISCARD live while SEND's archive streams deletes the corpus mid-copy. */
    @Test
    fun `a busy card has both buttons dead`() {
        val buttons = rescuedCardButtons(complete, setOf(complete.directory))
        assertFalse(buttons.sendEnabled, "SEND live while this rescue is busy")
        assertFalse(buttons.discardEnabled, "DISCARD live while this rescue is busy")
    }

    @Test
    fun `one busy rescue does not disable another`() {
        val other = item(listOf(DATABASE_NAME), dir = "db-1")
        val buttons = rescuedCardButtons(complete, setOf(other.directory))
        assertTrue(buttons.sendEnabled && buttons.discardEnabled, "an unrelated busy key disabled this card")
    }

    @Test
    fun `the view model's guard and the card read the same busy predicate`() {
        assertTrue(rescuedActionAllowed(complete, emptySet()))
        assertFalse(rescuedActionAllowed(complete, setOf(complete.directory)))
        assertTrue(rescuedActionAllowed(complete, setOf(File("/rescued", "db-1"))))
    }

    // ---- where a tap goes ---------------------------------------------------

    @Test
    fun `discard on the card opens the dialog and deletes nothing`() {
        val effect = rescuedTapEffect(RescuedTap.DISCARD, complete)
        assertIs<RescuedTapEffect.ConfirmDiscard>(effect)
        assertEquals(complete, effect.item)
    }

    @Test
    fun `only the dialog's confirm deletes`() {
        val effect = rescuedTapEffect(RescuedTap.CONFIRM_DISCARD, complete)
        assertIs<RescuedTapEffect.Discard>(effect)
        assertEquals(complete, effect.item)
        for (tap in RescuedTap.entries - RescuedTap.CONFIRM_DISCARD) {
            assertFalse(rescuedTapEffect(tap, complete) is RescuedTapEffect.Discard, "$tap deletes without a dialog")
        }
    }

    @Test
    fun `send shares the item it was tapped on`() {
        val effect = rescuedTapEffect(RescuedTap.SEND, fragment)
        assertIs<RescuedTapEffect.Share>(effect)
        assertEquals(fragment, effect.item)
    }

    // ---- restore (issue #112) -----------------------------------------------

    @Test
    fun `restore on the card opens its dialog and moves nothing`() {
        val effect = rescuedTapEffect(RescuedTap.RESTORE, complete)
        assertIs<RescuedTapEffect.ConfirmRestore>(effect)
        assertEquals(complete, effect.item)
    }

    @Test
    fun `only the restore dialog's confirm restores`() {
        assertIs<RescuedTapEffect.Restore>(rescuedTapEffect(RescuedTap.CONFIRM_RESTORE, complete))
        for (tap in RescuedTap.entries - RescuedTap.CONFIRM_RESTORE) {
            assertFalse(rescuedTapEffect(tap, complete) is RescuedTapEffect.Restore, "$tap restores without a dialog")
        }
    }

    @Test
    fun `a restorable, fragment or uncomputed card carries no restore note`() {
        assertNull(rescuedRestoreNote(RestoreVerdict.Restorable(19), 20, emptySet()))
        assertNull(rescuedRestoreNote(RestoreVerdict.Refused(RestoreRefusal.NO_DATABASE_FILE, null), 20, emptySet()))
        assertNull(rescuedRestoreNote(null, 20, emptySet()))
    }

    @Test
    fun `every other refusal says what stands in the way`() {
        val notes = (RestoreRefusal.entries - RestoreRefusal.NO_DATABASE_FILE).associateWith {
            rescuedRestoreNote(RestoreVerdict.Refused(it, 21), 20, emptySet())
        }
        for ((reason, note) in notes) assertTrue(!note.isNullOrBlank(), "$reason has no note")
        assertEquals(notes.size, notes.values.toSet().size, "two refusals share one note")
    }

    @Test
    fun `a rescue too new for this build names both versions`() {
        assertEquals(
            "Can't restore into this build: this is database version 21 and this build reads up to 20. " +
                "Install a build at database version 21 or newer to restore it.",
            rescuedRestoreNote(RestoreVerdict.Refused(RestoreRefusal.NEWER_THAN_THIS_BUILD, 21), 20, emptySet()),
        )
    }

    @Test
    fun `the restore warning says the database in use is set aside, not deleted`() {
        val text = rescuedRestoreWarning(complete, version = 20, compiledVersion = 20)
        assertTrue("set aside as a rescued database of its own -- not deleted" in text, text)
        assertTrue("with any sessions recorded since the rescue" in text, text)
        assertTrue(ByteSize.format(1_003L) in text, text)
        assertFalse("upgraded" in text, "a same-version restore claimed an upgrade: $text")
    }

    @Test
    fun `an older rescue's warning says it will be upgraded and what happens if it does not open`() {
        val text = rescuedRestoreWarning(complete, version = 18, compiledVersion = 20)
        assertTrue("database version 18 and will be upgraded to 20 when it opens" in text, text)
        assertTrue("the next start puts both databases back" in text, text)
    }

    @Test
    fun `a refused or uncomputed rescue offers no restore`() {
        for (verdict in listOf(null, RestoreVerdict.Refused(RestoreRefusal.NEWER_THAN_THIS_BUILD, 21))) {
            val buttons = rescuedCardButtons(complete, emptySet(), verdict)
            assertFalse(buttons.showRestore || buttons.restoreEnabled, "restore offered on $verdict")
        }
    }

    // ---- issue #112: the differential ---------------------------------------

    @Test
    fun `a restorable rescue offers restore`() {
        val buttons = rescuedCardButtons(complete, emptySet(), RestoreVerdict.Restorable(19))
        assertTrue(buttons.showRestore, "a rescue this build can open is not offered back")
        assertTrue(buttons.restoreEnabled, "an idle restorable card's RESTORE is dead")
    }

    @Test
    fun `a busy restorable rescue draws restore but cannot start it`() {
        val buttons = rescuedCardButtons(complete, setOf(complete.directory), RestoreVerdict.Restorable(19))
        assertTrue(buttons.showRestore, "busy hid the button rather than disabling it")
        assertFalse(buttons.restoreEnabled, "RESTORE live while a share or discard is in flight")
    }

    // ---- round 1 review of #112 (A2, B4): RESTORE ends the process ----------

    @Test
    fun `a restorable rescue cannot be started while any recording hold is held`() {
        for (hold in RecordingHold.entries) {
            val buttons = rescuedCardButtons(complete, emptySet(), RestoreVerdict.Restorable(19), setOf(hold))
            assertTrue(buttons.showRestore, "$hold hid the button rather than disabling it")
            assertFalse(buttons.restoreEnabled, "RESTORE live while $hold is held: the restart would cut it short")
            assertTrue(buttons.sendEnabled && buttons.discardEnabled, "$hold disabled actions that end nothing")
        }
    }

    // ---- round 2 review of #112 (A's N3): a dead RESTORE says why -----------

    /**
     * GUARD, added because round 3's review found the order unpinned (A's
     * N3): a refused card draws no RESTORE, so a recording hold has nothing
     * to grey out there, and its refusal -- what would let it be restored --
     * must not be replaced by a note about the holds.
     */
    @Test
    fun `a refused rescue's note is the same whatever is held`() {
        val holdSets = listOf(emptySet<RecordingHold>()) + RecordingHold.entries.map { setOf(it) } +
            listOf(RecordingHold.entries.toSet())
        for (reason in RestoreRefusal.entries) {
            val verdict = RestoreVerdict.Refused(reason, 21)
            val idle = rescuedRestoreNote(verdict, 20, emptySet())
            for (held in holdSets) {
                assertEquals(idle, rescuedRestoreNote(verdict, 20, held), "$reason's note changed with $held held")
            }
        }
    }

    @Test
    fun `a restorable rescue held by a recording says why RESTORE is dead`() {
        for (hold in RecordingHold.entries) {
            assertEquals(
                "Can't restore yet: a session is open, or a set or session is still saving. " +
                    "RESTORE works again once it has finished.",
                rescuedRestoreNote(RestoreVerdict.Restorable(19), 20, setOf(hold)),
                "RESTORE dead while $hold is held, with nothing on the card to say why",
            )
        }
    }
}
