package com.macrophage.barspeed.model

/**
 * The write-ahead log's own header, once it has been checked.
 *
 * A value only exists for a `-wal` this code is willing to read: the magic
 * matched and the page size is one SQLite can actually use. Everything else is
 * null at the parse, so no caller downstream has to re-check it.
 */
data class WalHeader(val pageSize: Int, val salt1: Int, val salt2: Int)

/** One frame's 24-byte header, as it sits in the file. */
data class WalFrameHeader(
    val pageNumber: Int,
    /**
     * For a COMMIT frame, the size of the database in pages after that commit;
     * zero for every other frame. This is the only mark of a transaction
     * boundary in the file, and it is what separates a version SQLite would
     * report from one it would roll back.
     */
    val dbSizeAfterCommit: Int,
    val salt1: Int,
    val salt2: Int,
)

/**
 * A frame, plus the one thing worth reading out of its body.
 *
 * [page1UserVersion] is null for every frame that is not page 1, and for a page
 * 1 whose body does not parse as a database header. Carrying the parsed version
 * rather than the page keeps the whole scan bounded: a caller reads 24 bytes
 * per frame and 100 more only for the rare page-1 frame, never a 64 KB page.
 */
data class WalFrame(val header: WalFrameHeader, val page1UserVersion: Int?)

/**
 * The version a database ACTUALLY has, when SQLite has not checkpointed yet.
 *
 * Issue #113. [SqliteHeader] reads bytes 60-63 of the main file, which is the
 * version as of the last checkpoint. In WAL mode that can be arbitrarily stale:
 * every committed change since then lives in the `-wal` and the main file is
 * not rewritten until a checkpoint runs. An installer force-stopping the app
 * for an in-place reinstall never lets it checkpoint on the way out, which is
 * precisely the path a rollback takes.
 *
 * MEASURED, NOT REMEMBERED, and the distinction matters because issue #101's
 * original offset check was correct and still missed this. That check wrote a
 * database, set `PRAGMA user_version`, and read bytes 60-63 -- on a
 * CHECKPOINTED file, which is the one state that hides the staleness.
 * Everything below was re-derived against SQLite 3.50.4, and its output is
 * committed as bytes at
 * `core/data/src/test/resources/sqlite/wal-committed-bump.db` and its `-wal`:
 *
 * ```
 * committed bump, never checkpointed  main header 16   here 17    SQLite 17
 * killed before commit                main header 16   here null  SQLite 16
 * no -wal at all                      main header 16   here n/a   SQLite 16
 * ```
 *
 * The middle row is why [committedUserVersion] tracks commit frames instead of
 * taking the last page-1 frame it sees. A migration killed part-way leaves the
 * new user_version in the log with no commit behind it, and SQLite discards it.
 * Reading it as real would move a database aside that the running build could
 * have opened -- the failure this whole path exists to avoid, reintroduced by
 * the fix for it.
 *
 * WHAT IS NOT CHECKED, said plainly rather than left to be discovered.
 *
 * CHECKSUMS ARE NOT VERIFIED. Every frame carries two, and SQLite uses them to
 * find where a torn write ended. Verifying them means reading every full page
 * of the log on the launch path, which is the cost issue #101 exists to avoid,
 * and implementing an arithmetic this repository cannot check against real
 * SQLite output for anything but the fixtures it happens to hold. Three cheaper
 * rules carry the weight instead: the salts must match the file header, which
 * excludes frames left over from before a checkpoint reset; the frame must lie
 * wholly inside the file, which excludes a truncated final write; and a page-1
 * body must parse as a database header through [SqliteHeader], magic and all.
 * A torn frame whose header survives all three and whose body still parses as a
 * database header could over-report. That residue is stated, not eliminated.
 *
 * THE READING CAN STILL BE WRONG, AND THE DIRECTION IS CHOSEN. Where this and
 * the main header disagree, [DatabaseDowngradePolicy.effectiveVersion] takes
 * the higher, so a doubt resolves towards detecting a downgrade. That is the
 * opposite of the tie-break issue #101 chose, and the reason is in that
 * function's own KDoc.
 */
object SqliteWal {
    /** The WAL magic, one value per frame-checksum endianness. Both are valid files. */
    private const val MAGIC_LITTLE_ENDIAN_CHECKSUMS = 0x377F0682
    private const val MAGIC_BIG_ENDIAN_CHECKSUMS = 0x377F0683

    /** The fixed header at the start of a `-wal`. */
    const val HEADER_BYTES = 32

    /** The fixed header on every frame, ahead of that frame's page image. */
    const val FRAME_HEADER_BYTES = 24

    /** SQLite's own page-size bounds; anything outside marks a file not worth reading. */
    private const val MIN_PAGE_SIZE = 512
    private const val MAX_PAGE_SIZE = 65_536

    private fun intAt(bytes: ByteArray, offset: Int): Int {
        var value = 0
        for (i in 0 until 4) value = (value shl 8) or (bytes[offset + i].toInt() and 0xFF)
        return value
    }

    /**
     * The header [bytes] declare, or null when this is not a `-wal` worth
     * reading: too short, wrong magic, or a page size SQLite would never write.
     *
     * The page size is validated here rather than at the point of use so that
     * no caller can compute a frame stride from a number this object has not
     * vouched for. A zero would loop forever; a negative would run backwards.
     */
    fun header(bytes: ByteArray): WalHeader? {
        if (bytes.size < HEADER_BYTES) return null
        val magic = intAt(bytes, 0)
        if (magic != MAGIC_LITTLE_ENDIAN_CHECKSUMS && magic != MAGIC_BIG_ENDIAN_CHECKSUMS) return null
        val pageSize = intAt(bytes, 8)
        if (pageSize < MIN_PAGE_SIZE || pageSize > MAX_PAGE_SIZE) return null
        if (pageSize and (pageSize - 1) != 0) return null
        return WalHeader(pageSize = pageSize, salt1 = intAt(bytes, 16), salt2 = intAt(bytes, 20))
    }

    /**
     * One frame header, or null if [bytes] is too short to hold one.
     *
     * A page number of zero is not a page, so it is refused here: SQLite
     * numbers pages from 1, and a zero would only arrive from a frame this code
     * should not have been reading in the first place.
     */
    fun frameHeader(bytes: ByteArray): WalFrameHeader? {
        if (bytes.size < FRAME_HEADER_BYTES) return null
        val pageNumber = intAt(bytes, 0)
        if (pageNumber <= 0) return null
        return WalFrameHeader(
            pageNumber = pageNumber,
            dbSizeAfterCommit = intAt(bytes, 4),
            salt1 = intAt(bytes, 8),
            salt2 = intAt(bytes, 12),
        )
    }

    /**
     * The user_version SQLite would report reading through this log, or null
     * when the log says nothing about it.
     *
     * Null is the ordinary answer, not a failure: most transactions never touch
     * page 1, so a healthy `-wal` usually carries no version at all and the
     * main file's header is the whole story.
     *
     * THE SCAN, and each rule is the reason a wrong answer is not produced:
     *
     *  - A frame whose salts differ from [walHeader]'s is not part of this log.
     *    SQLite reuses the file after a checkpoint and rewrites only the
     *    header, so whatever follows is the previous cycle's bytes. The scan
     *    STOPS there rather than skipping, because everything after it is older
     *    too.
     *  - A page-1 frame is held PENDING. It becomes the answer only when a
     *    commit frame -- one whose `dbSizeAfterCommit` is non-zero -- is reached
     *    at or after it. A killed migration leaves the bump pending forever and
     *    this returns null, which is what SQLite does with it.
     *  - A later committed page-1 frame replaces an earlier one. The last
     *    committed writer wins, which is what "the version it has" means.
     *  - Anything still pending when [frames] ends is discarded.
     *
     * [frames] is consumed lazily and abandoned at the first foreign frame, so
     * a caller reading a large log stops at the first byte that cannot help.
     */
    fun committedUserVersion(walHeader: WalHeader, frames: Sequence<WalFrame>): Int? {
        var committed: Int? = null
        var pending: Int? = null
        for (frame in frames) {
            if (frame.header.salt1 != walHeader.salt1 || frame.header.salt2 != walHeader.salt2) break
            if (frame.header.pageNumber == 1 && frame.page1UserVersion != null) {
                pending = frame.page1UserVersion
            }
            if (frame.header.dbSizeAfterCommit != 0) {
                if (pending != null) committed = pending
                pending = null
            }
        }
        return committed
    }
}
