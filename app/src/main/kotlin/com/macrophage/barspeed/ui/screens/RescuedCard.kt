package com.macrophage.barspeed.ui.screens

import com.macrophage.barspeed.data.RescueCompleteness
import com.macrophage.barspeed.data.RescuedDatabase
import com.macrophage.barspeed.model.ByteSize
import com.macrophage.barspeed.model.RecordingHold
import com.macrophage.barspeed.model.RecordingServicePolicy
import com.macrophage.barspeed.model.RestoreRefusal
import com.macrophage.barspeed.model.RestoreVerdict
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/*
 * The rescued-database card's decisions, out of the composable and out of
 * HomeScreen.kt. Issue #122.
 *
 * Every function here is a pure function of a [RescuedDatabase] (and, for the
 * buttons, the busy set), so `app/src/test` reaches it with no Android
 * runtime: `RescuedCardTest` pins which string goes on which arm, whether SEND
 * is drawn, whether DISCARD goes through the confirmation, and whether the
 * busy gate is consulted. Before this file, #111's gate permuted each of
 * those inside `RescuedDatabaseNotice` and the whole suite stayed green.
 *
 * WHAT THIS DOES NOT REACH, stated rather than implied: the composable still
 * decides whether it CALLS these. A `RescuedDatabaseNotice` that ignored
 * [rescuedCardButtons] and hard-coded `enabled = true`, or called `onDiscard`
 * without asking [rescuedTapEffect], would compile and pass. That last step
 * is compile- and lint-gated only, not test-gated; what moved under test is
 * every decision the composable delegates.
 */

/**
 * Whether an action may start on [item] now: false while a share, a discard
 * or a restore request is already in flight on the same directory (the
 * restore request joined this set with issue #112). Issue #111's race -- SEND, then
 * DISCARD while the archive streams -- is closed by this one predicate, read
 * by the card for `enabled` and by `HomeViewModel` as the guard at the top of
 * each action, so the two cannot disagree about what "busy" means.
 */
internal fun rescuedActionAllowed(item: RescuedDatabase, busy: Set<File>): Boolean = item.directory !in busy

/** What the card draws under the text, decided here so a test can hold it. */
internal data class RescuedCardButtons(
    /**
     * SEND IT TO ME is drawn at all. Not on [RescueCompleteness.NOTHING]: the
     * old card offered SEND there and produced a valid, empty 22-byte zip --
     * an attachment that arrives and looks like a backup.
     */
    val showSend: Boolean,
    /** SEND is tappable. False while [rescuedActionAllowed] says busy. */
    val sendEnabled: Boolean,
    /**
     * DISCARD is tappable. It is drawn on all three states -- see
     * `RescuedDatabaseNotice`'s own KDoc for why the fragment card keeps it --
     * and disabled only while busy.
     */
    val discardEnabled: Boolean,
    /**
     * RESTORE is drawn (issue #112): only when `DatabaseRestore.verdict` says
     * [RestoreVerdict.Restorable]. Every other verdict draws no button and,
     * through [rescuedRestoreNote], says why.
     */
    val showRestore: Boolean,
    /** RESTORE is tappable. False while busy, while a recording hold is held, and whenever it is not drawn. */
    val restoreEnabled: Boolean,
)

/**
 * [restore] is `DatabaseRestore.verdict` for [item], or null when none was
 * computed. [held] is what the recording holds hold now: RESTORE ends the
 * process, so it is dead while [RecordingServicePolicy.mayEndProcess] says
 * no -- a set-end write or a session close would be cut short (round 1
 * review of #112, A2/B4). SEND and DISCARD end nothing and ignore it.
 */
internal fun rescuedCardButtons(
    item: RescuedDatabase,
    busy: Set<File>,
    restore: RestoreVerdict? = null,
    held: Set<RecordingHold> = emptySet(),
): RescuedCardButtons {
    val allowed = rescuedActionAllowed(item, busy)
    return RescuedCardButtons(
        showSend = item.completeness != RescueCompleteness.NOTHING,
        sendEnabled = allowed,
        discardEnabled = allowed,
        showRestore = restore is RestoreVerdict.Restorable,
        restoreEnabled = restore is RestoreVerdict.Restorable && allowed && RecordingServicePolicy.mayEndProcess(held),
    )
}

/** A button on the card, or the confirm button of one of its dialogs. */
internal enum class RescuedTap { SEND, DISCARD, CONFIRM_DISCARD, RESTORE, CONFIRM_RESTORE }

/** What a [RescuedTap] does. */
internal sealed interface RescuedTapEffect {
    val item: RescuedDatabase

    /** Stream it off the phone. Destroys nothing. */
    data class Share(override val item: RescuedDatabase) : RescuedTapEffect

    /** Open the confirmation dialog for [item]. Destroys nothing yet. */
    data class ConfirmDiscard(override val item: RescuedDatabase) : RescuedTapEffect

    /** Delete it. Reached ONLY from the dialog's own confirm button. */
    data class Discard(override val item: RescuedDatabase) : RescuedTapEffect

    /** Open the restore confirmation for [item]. Moves nothing yet. */
    data class ConfirmRestore(override val item: RescuedDatabase) : RescuedTapEffect

    /** Ask the next launch to restore it, and restart. Reached ONLY from the restore dialog's confirm. */
    data class Restore(override val item: RescuedDatabase) : RescuedTapEffect
}

/**
 * The routing, as a value. DISCARD ON THE CARD NEVER DELETES: it opens the
 * dialog, and only the dialog's confirm is a [RescuedTapEffect.Discard]. A
 * rescued database can be an entire training history, deletable from the
 * first screen of every cold launch, so the one-tap path is the defect this
 * mapping exists to make visible to a test.
 */
internal fun rescuedTapEffect(tap: RescuedTap, item: RescuedDatabase): RescuedTapEffect = when (tap) {
    RescuedTap.SEND -> RescuedTapEffect.Share(item)
    RescuedTap.DISCARD -> RescuedTapEffect.ConfirmDiscard(item)
    RescuedTap.CONFIRM_DISCARD -> RescuedTapEffect.Discard(item)
    RescuedTap.RESTORE -> RescuedTapEffect.ConfirmRestore(item)
    RescuedTap.CONFIRM_RESTORE -> RescuedTapEffect.Restore(item)
}

/**
 * Why RESTORE is not on this card, or is on it but cannot be tapped, in
 * words the lifter can act on; or null when there is nothing to say: the
 * card is restorable and nothing is held, it is a fragment or
 * an empty rescue (whose own title already says it holds no database), or no
 * verdict was computed. Issue #112.
 *
 * Every refusal names what would change it. A version the build cannot take
 * names both numbers, because "install a newer build" is only actionable with
 * the number it has to reach.
 *
 * A RESTORE GREYED OUT BY A RECORDING HOLD SAYS SO. [held] is what the
 * recording holds hold now, as for [rescuedCardButtons], which disables
 * RESTORE while [RecordingServicePolicy.mayEndProcess] says no. Without a
 * note here that button would be dead with no reason given. Round 2 review
 * of #112, A's N3.
 */
internal fun rescuedRestoreNote(
    restore: RestoreVerdict?,
    compiledVersion: Int,
    held: Set<RecordingHold> = emptySet(),
): String? {
    if (restore is RestoreVerdict.Restorable && !RecordingServicePolicy.mayEndProcess(held)) {
        return "Can't restore yet: a session is open, or a set or session is still saving. " +
            "RESTORE works again once it has finished."
    }
    if (restore !is RestoreVerdict.Refused) return null
    val version = restore.version
    return when (restore.reason) {
        RestoreRefusal.NO_DATABASE_FILE -> null
        RestoreRefusal.RESTORE_IN_PROGRESS ->
            "Can't restore yet: an earlier restore is waiting for BarSpeed's next start or has not " +
                "finished being checked."
        RestoreRefusal.SPLIT_RESCUE ->
            "Can't restore yet: a partial rescue holds a log that may belong to this database. " +
                "Send the partial rescue to yourself, then discard it, to restore this one."
        RestoreRefusal.FAILED_BEFORE ->
            "A restore of this database was tried by this build or a newer one and undone before it was " +
                "confirmed to open; both databases were put back. A build with a newer database version " +
                "can try it again."
        RestoreRefusal.VERSION_UNREADABLE -> "Can't restore: this database's version can't be read."
        RestoreRefusal.NEWER_THAN_THIS_BUILD ->
            "Can't restore into this build: this is database version $version and this build reads up to " +
                "$compiledVersion. Install a build at database version $version or newer to restore it."
        RestoreRefusal.NO_MIGRATION_PATH ->
            "Can't restore into this build: it has no upgrade from database version $version."
    }
}

/**
 * What the restore dialog says. Issue #112.
 *
 * IT SAYS WHAT HAPPENS TO THE DATABASE IN USE NOW, because that is the
 * question a restore after a rollback raises: sessions may have been recorded
 * since. They are not overwritten -- `DatabaseRestore` moves that database
 * into a rescue of its own before anything comes into its place -- and the
 * dialog says so rather than leaving the lifter to assume the worst or the
 * best. It names no session count: nothing on this screen counts the
 * database in use, and a number from the history list would be a claim
 * stronger than what that list is.
 *
 * AN OLDER RESCUE IS UPGRADED WHEN IT OPENS, and the migrations' first run on
 * this data is on this phone. The dialog says that too, and what happens if
 * the open fails.
 */
internal fun rescuedRestoreWarning(item: RescuedDatabase, version: Int, compiledVersion: Int): String {
    val upgrade = if (version < compiledVersion) {
        " It is database version $version and will be upgraded to $compiledVersion when it opens. " +
            "If it does not open, the next start puts both databases back as they were."
    } else {
        ""
    }
    return "BarSpeed will close and reopen to put this rescued database " +
        "(${ByteSize.format(item.totalBytes)}) back in place.$upgrade " +
        "The database in use now, with any sessions recorded since the rescue, is set aside as a " +
        "rescued database of its own -- not deleted -- and can be sent, discarded or restored the same way."
}

/**
 * Three states, not two, and every string on this card switches on the same
 * one: [RescuedDatabase.completeness], computed in :core:data and pinned by
 * `RescuedDatabaseStoreTest`. Nothing here re-derives it.
 *
 * NO COMPLETENESS CLAIM ON THE COMPLETE CASE. The title used to read "Your
 * training history" flat, which is the claim `RescuedDatabaseStore.zipTo`'s
 * own KDoc contradicts: a `.db` alone can look complete and be stale,
 * because its `-wal` can hold committed transactions the main file does not
 * yet have -- and in a split rescue that `-wal` is a SEPARATE card. A lifter
 * who sent "Your training history" and got a zip that opened cleanly had
 * every reason to believe they had everything, and the file list was
 * suppressed on exactly that card. Both halves are fixed: the title names
 * what the card actually holds, and [rescuedDetail] lists the files on every
 * card that has any.
 */
internal fun rescuedTitle(item: RescuedDatabase): String = when (item.completeness) {
    RescueCompleteness.COMPLETE -> "Your training history -- the main database file"
    RescueCompleteness.FRAGMENT -> "Partial rescue -- may hold data your database is missing"
    RescueCompleteness.NOTHING -> "Nothing survived this rescue attempt"
}

/**
 * What the discard dialog says, and it cannot be one sentence, because one
 * sentence is not true of all three states.
 *
 * THE EMPTY CASE RENDERED, VERBATIM, "This permanently removes 0 B of
 * rescued data (). This cannot be undone." -- reachable, because DISCARD is
 * offered there. `joinToString` on an empty list is the empty string and the
 * dialog interpolated it inside brackets it had already opened. That is the
 * identical defect the commit which added this dialog found and fixed six
 * lines away in [rescuedDetail], whose own KDoc states the fact that makes
 * this one wrong; tier enumeration had been applied to two of this card's
 * four strings and not to the other two.
 *
 * THE COMPLETE CASE CARRIES THE ONLY-COPY WARNING TOO. `DatabaseRescue`
 * moves rather than copies -- "MOVE, NOT COPY" is its own KDoc's heading
 * -- and `DatabaseRescue.moveOrder` moves the sidecars the same way, so
 * only-copy is a fact on both arms. What separates them is what is lost:
 * on FRAGMENT, whether losing it loses anything is uncertain; on COMPLETE,
 * losing it loses the whole history, because `AppDatabase.build`'s KDoc
 * says what happens next -- Room finds nothing and creates an empty
 * database. The FRAGMENT arm below carried the only-copy clause and this
 * one did not.
 *
 * THE FRAGMENT CASE IS WHY THE DIALOG EXISTS AT ALL. DISCARD is offered
 * there now -- see [RescuedDatabaseNotice]'s own KDoc for why the button came
 * back -- so this text is where the lifter finds out what they are about to
 * destroy. It HEDGES: a sidecar CAN hold sets the live database does not
 * have, which is `RescuedDatabaseStore.zipTo`'s own wording, but nothing on
 * this screen has opened the file and nothing here knows whether this
 * particular one does. `DatabaseRescue.moveOrder` carries -wal, -shm and
 * -journal, and a -shm alone holds nothing worth keeping, so a flat "this
 * holds your missing sets" would be a claim stronger than anything this
 * screen can support.
 */
internal fun rescuedDiscardWarning(item: RescuedDatabase): String = when (item.completeness) {
    RescueCompleteness.COMPLETE ->
        "This permanently removes ${ByteSize.format(item.totalBytes)} of rescued data " +
            "(${item.files.joinToString(", ")}). The rescue MOVED these files rather than " +
            "copying them, so the app's own database does not have what is in them and nothing " +
            "here can put them back. Send it to yourself first if you are not sure. " +
            "This cannot be undone."
    RescueCompleteness.FRAGMENT ->
        "This rescue saved no database file -- only ${item.files.joinToString(", ")}, " +
            "${ByteSize.format(item.totalBytes)} in all. A sidecar like that can hold sets your " +
            "current database does not have, and nothing else on this phone has a copy. Send it to " +
            "yourself first if you are not sure. This cannot be undone."
    RescueCompleteness.NOTHING ->
        "This rescue attempt saved no files at all, so there is nothing here to lose. " +
            "Only the card goes."
}

/**
 * When it was rescued (or that this code cannot say, rather than a made-up
 * time), how large it is, and what is actually in it.
 *
 * THE FILE LIST IS SHOWN ON EVERY CARD THAT HAS ONE, including the complete
 * case, which used to suppress it. That suppression was the near neighbour of
 * the title's completeness claim: in a split rescue the `.db`-only card said
 * "Your training history", sorted to the top, and showed nothing that would
 * let a lifter notice its `-wal` was on a different card. The empty case
 * still contributes null rather than an empty string -- `joinToString` on an
 * empty list is `""`, which `listOfNotNull` would still include, rendering a
 * dangling " · " with nothing after it, and an empty rescue directory is
 * reachable (see `RescuedDatabase.isEmpty`'s own KDoc). Null on emptiness
 * rather than on a state test, so the guard cannot drift from the thing it
 * guards.
 */
internal fun rescuedDetail(
    item: RescuedDatabase,
    clock: DateTimeFormatter,
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val whenText =
        item.rescuedAtMs?.let { "rescued ${clock.format(Instant.ofEpochMilli(it).atZone(zone))}" }
            ?: "rescued at an unknown time"
    val sizeText = ByteSize.format(item.totalBytes)
    val filesText = item.files.joinToString(", ").takeIf { item.files.isNotEmpty() }
    return listOfNotNull(whenText, sizeText, filesText).joinToString(" · ")
}
