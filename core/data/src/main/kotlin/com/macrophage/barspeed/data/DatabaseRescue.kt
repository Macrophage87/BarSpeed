package com.macrophage.barspeed.data

import com.macrophage.barspeed.model.DatabaseDowngradePolicy
import com.macrophage.barspeed.model.DatabaseOpenAction
import com.macrophage.barspeed.model.SqliteHeader
import com.macrophage.barspeed.model.SqliteWal
import java.io.File
import java.io.FileOutputStream

/** What [DatabaseRescue] did, so a caller can say it rather than infer it. */
sealed interface RescueOutcome {
    /** The file is absent, current, older, or says nothing this code will act on. */
    data object NotNeeded : RescueOutcome

    /** The database was moved into [directory] and the app will start fresh. */
    data class Rescued(val directory: File) : RescueOutcome

    /**
     * A newer database was found and could NOT be moved, or a rescue an
     * earlier launch started could not be finished.
     *
     * Nothing further is attempted, and in particular nothing destructive.
     * `AppDatabase.build` refuses to open on this outcome: when the newer
     * version was only in the `-wal` and the `-wal` has already moved, the
     * main file left at the path reads OLDER, and Room would open it rather
     * than throw. The sentence that stood here, "Room meets the newer file and
     * throws", was false in that case (round 1 review of #113, A1/B1). A
     * crash with the data on disk beats an open of half of it.
     *
     * SAYS NOTHING ABOUT WHAT IS STILL WHERE IT WAS. An earlier version of this
     * KDoc claimed the original was untouched; that is false when a sidecar has
     * already moved and the main file's move is what failed, which separates
     * the database from its write-ahead log. Both halves survive on disk, in
     * different places.
     *
     * [reason] is a diagnostic string, not a contract, and nothing pins WHICH
     * outcome a mid-move failure produces -- the call site discards it today.
     * Anything built on this value (issue #111) must establish that first.
     */
    data class Failed(val reason: String) : RescueOutcome
}

/**
 * Moves a database aside when it is newer than the app that is about to open
 * it, so that opening it cannot destroy it. Issue #101.
 *
 * MOVE, NOT COPY. A copy doubles peak storage, can fail halfway, and still
 * leaves the original to be dropped. A rename inside one filesystem costs
 * nothing, preserves the bytes exactly, and leaves the app working -- Room
 * finds no file, creates an empty one, and the destructive path is never
 * reached. That is what allows fallbackToDestructiveMigrationOnDowngrade to be
 * deleted, which turns any downgrade this code fails to detect from a silent
 * drop into a loud throw.
 *
 * THREE FILES MOVE, NOT ONE, AND THE ORDER IS LOAD-BEARING. Room journals in
 * WAL mode, so the set is the database, its -wal and its -shm. The sidecars go
 * FIRST and the main file LAST, which makes the one genuinely dangerous state
 * unreachable by construction: a freshly created database sitting beside a
 * stale -wal from the old one. Nothing here depends on SQLite rejecting a
 * mismatched WAL by its salt -- a design that does not need a fact is better
 * than one resting on a verified fact, and this one does not need it.
 *
 * A RESCUE ONCE DECIDED IS FINISHED, NOT DECIDED AGAIN. Crash mid-rescue and
 * the main file is still in place -- but since issue #113 it need not read
 * newer: when the newer version was only in the `-wal`, the `-wal` has gone
 * and the main file left behind reads older. Deciding again would open it.
 * So the directory is recorded in [PENDING] before the first move, and a
 * launch that finds it moves the rest into that same directory without
 * reading any version. An earlier version of this paragraph said the main
 * file is "still in place at its newer version, so the next launch simply
 * retries into a new directory"; that stopped being true at #113's fix and
 * was found by round 1's review.
 *
 * GROWTH IS UNBOUNDED BY DESIGN, and it is the lifter's to bound. N downgrades
 * leave N rescued corpora, each the size of the database at that moment -- a
 * 100 Hz set gzips to roughly 116 KB, so a ten-set session is around 1.2 MB and
 * a mature history is tens of megabytes. Nothing here expires or caps them,
 * following the rule the interrupted-capture path already states: rescued data
 * goes at the lifter's word and never on a timer. A card that offers to send
 * one off the phone or throw it away, showing its size so the decision is made
 * with the number in front of them, is filed separately.
 *
 * NOTHING ON THE NORMAL LAUNCH PATH CHANGES: with no [PENDING] marker and a
 * database at or below the compiled version, this returns
 * [RescueOutcome.NotNeeded] and moves nothing. Every failure to move or
 * record a rescue is [RescueOutcome.Failed], on which `AppDatabase.build`
 * throws; so is a later launch's failure to clear the marker of a finished
 * one. A marker that will not delete at the end of the rescue that wrote it
 * leaves that launch [RescueOutcome.Rescued] (see `moveInto`). No exception
 * escapes this function; the launch's throw is the caller's. An earlier
 * version of this paragraph said every failure and every doubt resolves to
 * NotNeeded; that stopped being true when Failed began to stop the launch,
 * and was found by round 2's review. The version after it said every
 * failure to clear a rescue is Failed, which the end of `moveInto` never
 * was; found by round 3's review.
 */
object DatabaseRescue {
    /** Where rescued databases live, beside the in-flight set journals. */
    const val RESCUE_DIR = "rescued"

    /**
     * Every rescue directory's name starts with this, followed by the epoch
     * millisecond it was created at. Public so a reader -- issue #111's
     * card -- can parse the timestamp back out without a magic string
     * duplicated between this file and that one.
     */
    const val DIR_PREFIX = "db-"

    /**
     * A rescue under way: one line, the directory it is moving into, written
     * in [RESCUE_DIR] before the first file moves and removed after the last.
     * Round 1 review of issue #113 (A1, B1); see [rescue].
     */
    const val PENDING = "rescue.pending"

    private const val WAL_SUFFIX = "-wal"
    private const val SHM_SUFFIX = "-shm"

    /**
     * The rollback journal, which is the sidecar in TRUNCATE and DELETE mode.
     *
     * Room asks for AUTOMATIC journal mode, which resolves to WAL on most
     * devices and to TRUNCATE on a low-RAM one. Carrying only the WAL pair
     * would leave a stale rollback journal beside the name Room is about to
     * recreate on exactly those devices -- the one state the move order exists
     * to make unreachable, reintroduced by a filename.
     */
    private const val JOURNAL_SUFFIX = "-journal"

    /**
     * The version [databaseFile] declares, or null if it will not say --
     * read the way SQLite will read it, through its `-wal`. Issue #113.
     *
     * THE MAIN FILE'S HEADER ALONE WAS STALE, and that was measured, not
     * supposed: a migration's `user_version` bump sits in the `-wal` until a
     * checkpoint, and a force-stop is expected not to wait for one (reasoned;
     * a `[Field]` item). Against real SQLite files copied with the writer
     * still open, the main header read 0 (and in a second copy 10) while
     * SQLite reported 21 through the log; [SqliteWal]'s KDoc has the
     * measurement. Read that way, a rollback past such an upgrade
     * decided Open, and Room -- seeing the real version -- threw on every
     * launch. So when `<name>-wal` holds a committed page 1, its header is the
     * one read ([SqliteWal.effectiveUserVersion]); otherwise the main file's
     * is, exactly as before.
     *
     * THE LOG CAN ONLY ADD A RESCUE SQLITE ITSELF WOULD FORCE. Every way the
     * `-wal` can fail to say something -- absent, not a file, unreadable,
     * torn, not a log, nothing committed to page 1 -- leaves the main file's
     * answer standing, which is the answer every launch got before the log
     * was read at all. A log page 1 that is not a database header is the one
     * exception and goes the safe way: null, so Open, so Room throws rather
     * than anything moving on a guess.
     *
     * A HOT ROLLBACK JOURNAL IS NOT CONSULTED, and that is a known false
     * rescue, not fixed here. In TRUNCATE or DELETE mode (a low-RAM device's
     * AUTOMATIC) a migration writes page 1 into the main file in place and
     * keeps the old page in `<name>-journal` until it commits. A process
     * killed mid-migration can leave the main header at the NEWER version while
     * SQLite, opening it, would roll the journal back to the older one. This
     * reads the newer header, so the database is moved aside although this
     * build could have opened it. Nothing is lost -- the journal moves with
     * it -- but the rescue reads the same newer header, so the card refuses
     * to restore it here (newer than this build) and it can only be sent off
     * the phone. Reasoned from SQLite's rollback-journal design, not
     * reproduced.
     *
     * Every failure of the MAIN file is still null: absent, unreadable, too
     * short, not a database, permission denied, a directory where a file was
     * expected. No exception escapes, because this runs before anything else
     * on the launch path and an exception here would stop the app opening at
     * all.
     */
    fun storedVersion(databaseFile: File): Int? = runCatching {
        if (!databaseFile.isFile) return null
        val header = ByteArray(SqliteHeader.HEADER_BYTES)
        databaseFile.inputStream().use { stream ->
            var read = 0
            while (read < header.size) {
                val n = stream.read(header, read, header.size - read)
                if (n < 0) break
                read += n
            }
            if (read < header.size) return null
        }
        SqliteWal.effectiveUserVersion(header, committedPageOne(File(databaseFile.path + WAL_SUFFIX)))
    }.getOrNull()

    /**
     * The newest committed page 1 in [wal], or null -- for an absent or
     * unreadable log as much as for one with nothing in it, because each of
     * those means the main file's header stands. Its own catch, separate from
     * [storedVersion]'s, so that a log that cannot be read falls back to the
     * main file rather than making the whole answer null.
     */
    private fun committedPageOne(wal: File): ByteArray? = runCatching {
        if (!wal.isFile) return null
        wal.inputStream().buffered().use { SqliteWal.committedPageOne(it) }
    }.getOrNull()

    /**
     * The three files of a database, in the order they must be moved.
     *
     * SIDECARS FIRST, MAIN FILE LAST, and this is a returned value rather than
     * the shape of a loop so that a test can hold it. Crash anywhere
     * mid-rescue and the main file is still in place, and [PENDING] sends the
     * next launch's moves into the same directory (see [rescue] for why the
     * version is not read again). Move the main file first instead and a crash leaves orphaned
     * sidecars beside the name Room is about to recreate -- a fresh database
     * next to a stale -wal, which is the one state that could corrupt rather
     * than merely lose. Nothing here relies on SQLite rejecting a mismatched
     * WAL by its salt; the order means it never has to.
     *
     * THE SET IS JOURNAL-MODE DEPENDENT and covers all of Room's. AUTOMATIC
     * resolves to WAL on most devices, whose sidecars are -wal and -shm, and to
     * TRUNCATE on a low-RAM one, whose sidecar is -journal; DELETE mode uses
     * -journal too. All three suffixes are carried, and each is moved only if
     * it is actually there, so the ones that do not apply cost nothing.
     */
    internal fun moveOrder(databaseFile: File): List<File> = listOf(
        File(databaseFile.path + WAL_SUFFIX),
        File(databaseFile.path + SHM_SUFFIX),
        File(databaseFile.path + JOURNAL_SUFFIX),
        databaseFile,
    )

    /**
     * Move [databaseFile] aside if it is newer than [compiledVersion].
     *
     * [rescueRoot] is the directory rescued databases are kept in; it is
     * created on demand and only when there is something to put in it.
     *
     * [move] defaults to [File.renameTo] and production never overrides it --
     * this parameter exists so a test can make one specific file's move fail
     * without depending on real filesystem permissions to provoke it. POSIX
     * renameTo overwrites its destination silently, so no fixture built from
     * real files could make it return false; the seam is what makes "a move
     * fails" a state a test can produce at all. Issue #111 found that gap:
     * before this parameter existed, nothing pinned that a failed move is
     * reported as [RescueOutcome.Failed] rather than silently taken as
     * [RescueOutcome.Rescued].
     *
     * FINISHED, NOT DECIDED AGAIN. The target directory is written to
     * [PENDING] before anything moves, and the file is removed only after the
     * last move. A launch that finds it does not read the version: it moves
     * whatever of [moveOrder] is still at the path into that directory, so a
     * database whose newer version left with its `-wal` is still rescued
     * whole, into one directory. Two exceptions, each read off the disk: a
     * main file already in the directory means every move was done and only
     * the bookkeeping was left, so nothing at the path is touched and the
     * launch decides afresh; and a named directory that does not exist means
     * nothing had moved, so the same. In both of these, and for a marker that
     * names no `db-` directory at all, the marker is removed and the launch
     * decides afresh; if it cannot be removed, that launch is
     * [RescueOutcome.Failed] and does not open, and every later launch tries
     * again. [deleteMarker] is that removal, a parameter so a test can make
     * it fail, as [move] is. A move into a name that is already
     * taken is refused, never made. Any failure once [PENDING] is written
     * leaves it in place and returns [RescueOutcome.Failed], on which the
     * launch does not open; a failure before it is written has moved nothing.
     *
     * Before this, ONE DIRECTORY WAS NOT NECESSARILY ONE DATABASE: an
     * interrupted rescue left a sidecar in one db-<ms> directory and the main
     * file, moved by a retry, in another. A rescue started by a build without
     * [PENDING] can still have left that shape, so anything reading these back
     * must still not assume a directory holds a complete set.
     */
    fun rescue(
        databaseFile: File,
        rescueRoot: File,
        compiledVersion: Int,
        move: (source: File, dest: File) -> Boolean = File::renameTo,
        deleteMarker: (File) -> Boolean = File::delete,
    ): RescueOutcome = runCatching {
        val marker = File(rescueRoot, PENDING)
        if (marker.exists()) {
            val name = marker.readText().trim()
            val target = File(rescueRoot, name)
            val unfinished = name.startsWith(DIR_PREFIX) && target.isDirectory &&
                !File(target, databaseFile.name).exists()
            if (unfinished) return moveInto(databaseFile, target, marker, move, deleteMarker)
            if (!deleteMarker(marker)) return RescueOutcome.Failed("could not clear ${marker.name}")
        }
        val stored = storedVersion(databaseFile)
        if (DatabaseDowngradePolicy.decide(stored, compiledVersion) == DatabaseOpenAction.OPEN) {
            return RescueOutcome.NotNeeded
        }
        val target = File(rescueRoot, "$DIR_PREFIX${System.currentTimeMillis()}")
        if (!target.mkdirs() && !target.isDirectory) {
            return RescueOutcome.Failed("could not create ${target.path}")
        }
        val temp = File(marker.path + ".tmp")
        writeSynced(temp, target.name)
        if (!temp.renameTo(marker)) return RescueOutcome.Failed("could not record the rescue")
        moveInto(databaseFile, target, marker, move, deleteMarker)
    }.getOrElse { RescueOutcome.Failed(it::class.simpleName ?: "unknown") }

    /**
     * [text] into [file], forced to the storage device before this returns.
     * A temp file renamed over a marker or a journal must have its bytes on
     * disk before the rename does: on power loss, delayed allocation can
     * otherwise commit the rename -- and the next file move's rename --
     * while the renamed file is still empty. An empty [PENDING] is dropped
     * and the launch decides afresh, which is round 1's A1 split again; an
     * empty restore journal falls back to the narrower open check. That is
     * reasoned from delayed-allocation semantics, not observed on a device,
     * and no test here can tell a synced write from an unsynced one. Round 2
     * review, A's N1. Throws on failure; every caller catches.
     *
     * The file is synced; the directory that holds it is not. On a file
     * system that does not commit renames in the order they were made, the
     * rename of the marker can still be lost after power loss while a later
     * rename -- a `-wal` into the rescue directory -- survives. That residual
     * is not closed here; it is a `[Field]` question. Round 3 review, B's N3.
     *
     * [append] adds [text] to the end of [file] rather than replacing it,
     * for the restore's record of undone restores, which is appended to and
     * never renamed. Round 3 review, A's N6.
     */
    internal fun writeSynced(file: File, text: String, append: Boolean = false) {
        FileOutputStream(file, append).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
    }

    /**
     * Every file of [moveOrder] still at the path, into [target], in that
     * order; then [marker] goes. A taken destination is refused rather than
     * overwritten -- POSIX rename would replace it silently.
     */
    private fun moveInto(
        databaseFile: File,
        target: File,
        marker: File,
        move: (source: File, dest: File) -> Boolean,
        deleteMarker: (File) -> Boolean,
    ): RescueOutcome {
        for (source in moveOrder(databaseFile)) {
            if (!source.isFile) continue
            val dest = File(target, source.name)
            if (dest.exists()) return RescueOutcome.Failed("${dest.name} is already in ${target.name}")
            if (!move(source, dest)) return RescueOutcome.Failed("could not move ${source.name}")
        }
        // Every file has moved. A marker that will not go does not undo the
        // rescue. The next launch finds the main file already in the target
        // and only retries removing it; if that retry fails too, that launch
        // is Failed and does not open. Nothing is lost either way.
        deleteMarker(marker)
        return RescueOutcome.Rescued(target)
    }
}
