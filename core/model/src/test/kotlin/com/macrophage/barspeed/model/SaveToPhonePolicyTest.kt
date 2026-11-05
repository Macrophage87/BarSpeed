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
}
