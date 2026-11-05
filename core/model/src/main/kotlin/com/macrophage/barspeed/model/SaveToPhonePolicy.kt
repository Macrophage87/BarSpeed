package com.macrophage.barspeed.model

/** Which of the session detail screen's three "Save to phone" exports is pending. */
enum class SaveKind {
    SUMMARY_JSON,
    DETAILED_JSON,
    RAW_ZIP,
}

/** What to do with the document the system picker has just returned. */
enum class SaveOutcome {
    /** Nothing: the picker was cancelled, so no document exists to fill. */
    NOTHING,

    /** Write the bytes built before the picker opened. */
    WRITE_HELD,

    /** The bytes are gone; build the export again from its kind, then write it. */
    REGENERATE,

    /** The bytes are gone and so is the kind: say so, and do not leave an empty file. */
    REFUSE,
}

/**
 * The session detail screen's "Save to phone" flow, when the picker returns
 * (#37).
 *
 * The flow is two steps with an Activity in between. The export is built and
 * held in the ViewModel's heap, then the system's `CreateDocument` picker
 * opens, and the picker CREATES the file before it returns. So by the time
 * the bytes are written the document already exists, empty. If the bytes are
 * gone by then, an answer that writes nothing leaves a 0-byte file under the
 * name the lifter chose, with no word that anything went wrong. The bytes can
 * be gone because the Activity was destroyed behind the picker, which is
 * ordinary on a low-memory phone.
 *
 * Here rather than in `:app` for the reason [RecordExitPolicy] is: no test on
 * the CI path reaches a ViewModel.
 */
object SaveToPhonePolicy {
    /**
     * The outcome for a picker result.
     *
     * [documentCreated] is whether the picker returned a document at all,
     * [bytesHeld] whether the export built before it opened is still in
     * memory, and [kind] the export the lifter asked for, kept where it
     * survives the Activity, or null where nothing kept it.
     *
     * Where a document came back and the bytes did not survive, the
     * document already exists, empty, so doing nothing is never the answer:
     * the export is built again from [kind], or, with no kind either, the
     * caller is told to refuse, which removes the empty file and says so.
     */
    fun onPicked(documentCreated: Boolean, bytesHeld: Boolean, kind: SaveKind?): SaveOutcome = when {
        !documentCreated -> SaveOutcome.NOTHING
        bytesHeld -> SaveOutcome.WRITE_HELD
        kind != null -> SaveOutcome.REGENERATE
        else -> SaveOutcome.REFUSE
    }

    /** What the lifter is told when an export could not be written at all. */
    const val REFUSED_MESSAGE = "Nothing was saved. Tap Save to phone again."
}
