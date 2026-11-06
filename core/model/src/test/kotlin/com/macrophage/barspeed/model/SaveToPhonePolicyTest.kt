package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What the session detail screen's "Save to phone" does with the document the
 * system picker returns (#37).
 *
 * Nothing here runs the picker, a ContentResolver or a ViewModel. What is
 * pinned is the decision `SessionDetailViewModel.savePendingTo` carries out;
 * that it carries it out was checked by reading it, and by nothing else.
 */
class SaveToPhonePolicyTest {
    /** A cancelled picker created no document, so there is nothing to fill or to clean up. */
    @Test
    fun `a cancelled picker does nothing`() {
        SaveKind.entries.forEach { kind ->
            assertEquals(SaveOutcome.NOTHING, SaveToPhonePolicy.onPicked(false, bytesHeld = true, kind = kind))
            assertEquals(SaveOutcome.NOTHING, SaveToPhonePolicy.onPicked(false, bytesHeld = false, kind = kind))
        }
        assertEquals(SaveOutcome.NOTHING, SaveToPhonePolicy.onPicked(false, bytesHeld = false, kind = null))
    }

    /** The ordinary case: the bytes built before the picker opened are still here, and they are written. */
    @Test
    fun `held bytes are written`() {
        assertEquals(
            SaveOutcome.WRITE_HELD,
            SaveToPhonePolicy.onPicked(true, bytesHeld = true, kind = SaveKind.RAW_ZIP),
        )
        assertEquals(SaveOutcome.WRITE_HELD, SaveToPhonePolicy.onPicked(true, bytesHeld = true, kind = null))
    }

    /**
     * THE DEFECT (#37). The Activity was destroyed behind the picker, so the
     * held bytes are gone, but the screen's saved instance state kept which
     * export was asked for. The picker has already created the document;
     * answering NOTHING leaves it empty. The export is built again instead.
     */
    @Test
    fun `lost bytes with a known kind are built again`() {
        SaveKind.entries.forEach { kind ->
            assertEquals(SaveOutcome.REGENERATE, SaveToPhonePolicy.onPicked(true, bytesHeld = false, kind = kind))
        }
    }

    /** With neither the bytes nor the kind there is nothing to build, and the empty file must not be left silent. */
    @Test
    fun `lost bytes with no kind are refused rather than left as an empty file`() {
        assertEquals(SaveOutcome.REFUSE, SaveToPhonePolicy.onPicked(true, bytesHeld = false, kind = null))
    }

    /**
     * THE DEFECT (#37's second path). Two pickers stacked by a quick second
     * tap: JSON, then Raw zip. Each build replaced the one set of held bytes,
     * so the first picker to return wrote the zip into the .json document,
     * and the second found nothing held and refused, deleting its file. Bytes
     * held for another export are not this picker's: it builds its own.
     */
    @Test
    fun `bytes held for another export are not written into this one`() {
        assertEquals(
            SaveOutcome.REGENERATE,
            SaveToPhonePolicy.onPickedFor(true, heldKind = SaveKind.RAW_ZIP, pickedKind = SaveKind.SUMMARY_JSON),
        )
        assertEquals(
            SaveOutcome.REGENERATE,
            SaveToPhonePolicy.onPickedFor(true, heldKind = SaveKind.DETAILED_JSON, pickedKind = SaveKind.SUMMARY_JSON),
        )
    }

    /** The other side: bytes held for this export are written, and a cancelled picker still does nothing. */
    @Test
    fun `bytes held for this export are written`() {
        SaveKind.entries.forEach { kind ->
            assertEquals(
                SaveOutcome.WRITE_HELD,
                SaveToPhonePolicy.onPickedFor(true, heldKind = kind, pickedKind = kind),
            )
            assertEquals(
                SaveOutcome.REGENERATE,
                SaveToPhonePolicy.onPickedFor(true, heldKind = null, pickedKind = kind),
            )
            assertEquals(SaveOutcome.NOTHING, SaveToPhonePolicy.onPickedFor(false, heldKind = kind, pickedKind = kind))
        }
    }

    /**
     * Nothing held and no kind for the picker: the empty file is refused, as
     * [SaveToPhonePolicy.onPicked] refuses it. Two nulls are not a match, so
     * this is never answered by writing bytes that do not exist.
     */
    @Test
    fun `nothing held for a picker of no kind is refused`() {
        assertEquals(SaveOutcome.REFUSE, SaveToPhonePolicy.onPickedFor(true, heldKind = null, pickedKind = null))
    }
}
