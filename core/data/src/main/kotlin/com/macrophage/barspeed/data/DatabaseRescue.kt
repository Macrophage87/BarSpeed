package com.macrophage.barspeed.data

import com.macrophage.barspeed.model.DatabaseDowngradePolicy
import com.macrophage.barspeed.model.DatabaseOpenAction
import com.macrophage.barspeed.model.SqliteHeader
import com.macrophage.barspeed.model.SqliteWal
import com.macrophage.barspeed.model.WalFrame
import java.io.File
import java.io.RandomAccessFile

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
 * THE LAUNCH PATH READS TWO FILES NOW, NOT ONE. That sentence used to read
 * "nothing on the normal launch path changes", and issue #113 made it false:
 * the version is no longer the main file's header alone, because in WAL mode
 * that header is only as current as the last checkpoint. The `-wal` is scanned
 * too, at 24 bytes per frame plus 100 for a frame carrying page 1 -- never a
 * whole page, and never through SQLite, which is still the thing this code
 * exists to get ahead of. What has NOT changed is everything the old sentence
 * was really promising: every failure and every doubt still resolves to
 * [RescueOutcome.NotNeeded], no exception escapes, and the case this release
 * actually meets -- a database at the same version as the code, whose log
 * mentions no other -- still takes the same branch as a first install.
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
     * The version [databaseFile] ACTUALLY has, or null if it will not say.
     *
     * TWO PLACES CAN SAY, AND THE MAIN FILE ALONE IS NOT ENOUGH. Issue #113:
     * Room journals in WAL mode, and in WAL mode the main file's header is the
     * version as of the last CHECKPOINT. Every committed change since then
     * lives in the `-wal` and the header is not rewritten until a checkpoint
     * runs. An installer force-stopping the app for an in-place reinstall never
     * lets it checkpoint on the way out, which is exactly the path a rollback
     * takes -- so the header can read older than the database is, and this
     * function used to return that older number.
     *
     * Reproduced against SQLite 3.50.4 rather than reasoned about, and the pair
     * of files is committed at `core/data/src/test/resources/sqlite/`: a
     * database checkpointed at 16, then bumped to 17 and committed without a
     * checkpoint, reads 16 from the header and 17 through the log.
     *
     * THE LOG ONLY RAISES A VERSION THE MAIN FILE DECLARED. When
     * [headerVersion] says nothing the answer is nothing, and the log is not
     * consulted at all -- issue #101's rule, unchanged: a database whose own
     * header will not parse is never acted on, because moving one aside on a
     * guess is the destructive direction. A torn reinstall can leave a
     * half-written main file beside an intact log, and that is not a rescue.
     *
     * Every failure is still null: absent, unreadable, too short, not a
     * database, permission denied, a directory where a file was expected, and
     * now also a `-wal` that will not parse. No exception escapes, because this
     * runs before anything else on the launch path and an exception here would
     * stop the app opening at all.
     */
    fun storedVersion(databaseFile: File): Int? {
        val header = headerVersion(databaseFile) ?: return null
        return DatabaseDowngradePolicy.effectiveVersion(
            headerVersion = header,
            walVersion = walVersion(File(databaseFile.path + WAL_SUFFIX)),
        )
    }

    /**
     * The version [databaseFile]'s own 100-byte header declares.
     *
     * This was the whole of [storedVersion] until issue #113; it is now the
     * first of two readings and its limit has a name. In WAL mode this is the
     * version as of the last checkpoint, not the version the database has.
     */
    private fun headerVersion(databaseFile: File): Int? = runCatching {
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
     * The version committed into [walFile], or null when it says nothing.
     *
     * Null is the ordinary answer and not a failure. Most transactions never
     * touch page 1, so a healthy log carries no version at all; so does a
     * device in TRUNCATE or DELETE mode, which has no `-wal` in the first
     * place. In every one of those cases the main file's header is the whole
     * story and this changes nothing about the launch.
     *
     * BOUNDED BY CONSTRUCTION, because this runs before the app has drawn
     * anything. Per frame it reads the 24-byte frame header, and 100 bytes more
     * only for a frame that carries page 1 -- never the page itself, which can
     * be 64 KB. The frame count is the file length over the stride, so a log
     * that has grown large costs two small reads per frame and no allocation
     * that scales with it.
     *
     * A FRAME MUST LIE WHOLLY INSIDE THE FILE to be read at all, which is the
     * cheap stand-in for the checksums this does not verify: a torn final write
     * leaves a short frame, and a short frame is never reached. What that does
     * NOT exclude is a torn frame that happens to be complete, whose salts
     * match and whose page-1 body still parses as a database header;
     * [SqliteWal]'s KDoc states that residue and this does not narrow it.
     *
     * No exception escapes. A read that fails part-way discards the scan and
     * returns null, leaving the header's reading to stand.
     */
    internal fun walVersion(walFile: File): Int? = runCatching {
        if (!walFile.isFile) return null
        val length = walFile.length()
        if (length < SqliteWal.HEADER_BYTES) return null
        RandomAccessFile(walFile, "r").use { file ->
            val headerBytes = ByteArray(SqliteWal.HEADER_BYTES)
            file.readFully(headerBytes)
            val walHeader = SqliteWal.header(headerBytes) ?: return null
            val stride = SqliteWal.FRAME_HEADER_BYTES.toLong() + walHeader.pageSize
            val frames = sequence {
                var offset = SqliteWal.HEADER_BYTES.toLong()
                while (offset + stride <= length) {
                    yield(readFrame(file, offset) ?: break)
                    offset += stride
                }
            }
            SqliteWal.committedUserVersion(walHeader, frames)
        }
    }.getOrNull()

    /**
     * One frame at [offset], with page 1's declared version when it carries
     * page 1.
     *
     * The body is read through [SqliteHeader.userVersion], which checks the
     * database magic before it reads an offset, so a frame claiming page 1
     * whose body is not a database header contributes nothing rather than a
     * number. The caller has already established that the whole frame lies
     * inside the file, so the 100 bytes are always there: the minimum page size
     * this parser accepts is 512.
     */
    private fun readFrame(file: RandomAccessFile, offset: Long): WalFrame? {
        file.seek(offset)
        val frameHeaderBytes = ByteArray(SqliteWal.FRAME_HEADER_BYTES)
        file.readFully(frameHeaderBytes)
        val frameHeader = SqliteWal.frameHeader(frameHeaderBytes) ?: return null
        if (frameHeader.pageNumber != 1) return WalFrame(frameHeader, null)
        val page = ByteArray(SqliteHeader.HEADER_BYTES)
        file.readFully(page)
        return WalFrame(frameHeader, SqliteHeader.userVersion(page))
    }

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
     * ONE DIRECTORY IS NOT NECESSARILY ONE DATABASE. A rescue interrupted after
     * a sidecar has moved leaves that sidecar in one db-<ms> directory and the
     * main file, moved by the retry on the next launch, in another. Anything
     * reading these back must not assume a directory holds a complete set.
     */
    fun rescue(
        databaseFile: File,
        rescueRoot: File,
        compiledVersion: Int,
        move: (source: File, dest: File) -> Boolean = File::renameTo,
    ): RescueOutcome {
        val stored = storedVersion(databaseFile)
        if (DatabaseDowngradePolicy.decide(stored, compiledVersion) == DatabaseOpenAction.OPEN) {
            return RescueOutcome.NotNeeded
        }
        return runCatching {
            val target = File(rescueRoot, "$DIR_PREFIX${System.currentTimeMillis()}")
            if (!target.mkdirs() && !target.isDirectory) {
                return RescueOutcome.Failed("could not create ${target.path}")
            }
            for (source in moveOrder(databaseFile)) {
                if (source.isFile && !move(source, File(target, source.name))) {
                    return RescueOutcome.Failed("could not move ${source.name}")
                }
            }
            RescueOutcome.Rescued(target)
        }.getOrElse { RescueOutcome.Failed(it::class.simpleName ?: "unknown") }
    }
}
