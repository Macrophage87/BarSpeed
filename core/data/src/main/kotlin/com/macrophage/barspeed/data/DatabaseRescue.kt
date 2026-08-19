package com.macrophage.barspeed.data

import com.macrophage.barspeed.model.DatabaseDowngradePolicy
import com.macrophage.barspeed.model.DatabaseOpenAction
import com.macrophage.barspeed.model.SqliteHeader
import java.io.File

/** What [DatabaseRescue] did, so a caller can say it rather than infer it. */
sealed interface RescueOutcome {
    /** The file is absent, current, older, or says nothing this code will act on. */
    data object NotNeeded : RescueOutcome

    /** The database was moved into [directory] and the app will start fresh. */
    data class Rescued(val directory: File) : RescueOutcome

    /**
     * A newer database was found and could NOT be moved.
     *
     * Nothing further is attempted, and in particular nothing destructive: the
     * builder carries no destructive fallback, so Room meets the newer file and
     * throws. A crash with the data intact beats a clean start with it gone.
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
 * stale -wal from the old one. Crash anywhere mid-rescue and the main file is
 * still in place at its newer version, so the next launch simply retries into a
 * new directory. Nothing here depends on SQLite rejecting a mismatched WAL by
 * its salt -- a design that does not need a fact is better than one resting on
 * a verified fact, and this one does not need it.
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
 * NOTHING ON THE NORMAL LAUNCH PATH CHANGES. Every failure and every doubt
 * resolves to [RescueOutcome.NotNeeded], no exception escapes, and the case
 * this release actually meets -- a database at the same version as the code --
 * takes the same branch as a first install.
 */
object DatabaseRescue {
    /** Where rescued databases live, beside the in-flight set journals. */
    const val RESCUE_DIR = "rescued"

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
     * The version [databaseFile] declares, or null if it will not say.
     *
     * Every failure is null: absent, unreadable, too short, not a database,
     * permission denied, a directory where a file was expected. No exception
     * escapes, because this runs before anything else on the launch path and
     * an exception here would stop the app opening at all.
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
        SqliteHeader.userVersion(header)
    }.getOrNull()

    /**
     * The three files of a database, in the order they must be moved.
     *
     * SIDECARS FIRST, MAIN FILE LAST, and this is a returned value rather than
     * the shape of a loop so that a test can hold it. The ordering is the whole
     * safety argument: crash anywhere mid-rescue and the main file is still in
     * place at its newer version, so the next launch retries into a new
     * directory. Move the main file first instead and a crash leaves orphaned
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
     * ONE DIRECTORY IS NOT NECESSARILY ONE DATABASE. A rescue interrupted after
     * a sidecar has moved leaves that sidecar in one db-<ms> directory and the
     * main file, moved by the retry on the next launch, in another. Anything
     * reading these back must not assume a directory holds a complete set.
     */
    fun rescue(databaseFile: File, rescueRoot: File, compiledVersion: Int): RescueOutcome {
        val stored = storedVersion(databaseFile)
        if (DatabaseDowngradePolicy.decide(stored, compiledVersion) == DatabaseOpenAction.OPEN) {
            return RescueOutcome.NotNeeded
        }
        return runCatching {
            val target = File(rescueRoot, "db-${System.currentTimeMillis()}")
            if (!target.mkdirs() && !target.isDirectory) {
                return RescueOutcome.Failed("could not create ${target.path}")
            }
            for (source in moveOrder(databaseFile)) {
                if (source.isFile && !source.renameTo(File(target, source.name))) {
                    return RescueOutcome.Failed("could not move ${source.name}")
                }
            }
            RescueOutcome.Rescued(target)
        }.getOrElse { RescueOutcome.Failed(it::class.simpleName ?: "unknown") }
    }
}
