package com.macrophage.barspeed.model

import java.io.InputStream

/**
 * The database header a reader of a write-ahead log would actually see, read
 * from the `-wal` with plain I/O. Issue #113.
 *
 * WHY THE MAIN FILE'S HEADER IS NOT ENOUGH. In WAL mode a commit appends the
 * changed pages to the `-wal` and leaves the main file alone until a
 * checkpoint copies them back. `PRAGMA user_version` lives in page 1, so a
 * migration that bumps it is, until that checkpoint, in the `-wal` only, and
 * bytes 60-63 of the main file still carry the version before it. Measured
 * while #101 was gated (main file 0, SQLite through the `-wal` 10) and again
 * for this change with SQLite 3.53.1 through Python's `sqlite3`, WAL mode,
 * autocheckpoint off, the three files copied while the writer still held
 * them open (main file 0, and in a second copy 10; SQLite through the `-wal`
 * 21 in both). A package installer force-stopping the app for an in-place
 * reinstall is expected to leave the same shape, because a force-stop gives
 * nothing a chance to checkpoint -- reasoned, not observed on a phone, and a
 * `[Field]` item.
 *
 * WHAT THIS DOES, stated as what SQLite does, because it has to be the same
 * thing. When the first connection opens a database whose `-wal` is not
 * empty, SQLite rebuilds its view of the log from the `-wal` alone ("WAL
 * recovery", `walIndexRecover` in SQLite's `wal.c`): it reads the 32-byte
 * header and walks the frames in order, accepting each only while its salt
 * matches the header's and the running checksum matches the one stored in the
 * frame, and stopping at the first that fails. Only frames up to the last
 * COMMIT frame (a non-zero "database size" field) are visible. A reader then
 * takes each page from the newest visible frame that holds it, or from the main
 * file when none does. [committedPageOne] is that walk, kept to page 1 -- the
 * page the header is in.
 *
 * WHY RECOVERY IS WHAT THE APP'S OPEN SEES, reasoned and not measured on a
 * phone. The wal-index in `-shm` could in principle carry a different
 * frame count from the one recovery derives. On the unix VFS Android uses, the
 * first connection to a database that no other connection holds truncates
 * the `-shm` before reading it (the "dead man switch" lock in `os_unix.c`), so
 * the index is rebuilt from the `-wal` by the walk above. At launch, before
 * Room opens, there is no other connection. That is an argument from SQLite's
 * source; that the framework SQLite on a given phone behaves that way is a
 * `[Field]` item.
 *
 * THE FAILURE DIRECTION. Everything that is not a well-formed, checksummed,
 * committed page 1 is null, and null means "the main file's header stands",
 * which is the answer every launch got before this existed. So the only way
 * this changes a launch is by finding a committed page 1 SQLite itself would
 * read -- and the tests below are built from real SQLite output as well as
 * from bytes written to the format, so a misreading of the format shared by
 * the reader and a hand-built fixture is not the only thing standing between
 * this and a wrong answer.
 *
 * NOT BOUNDED IN SIZE. The checksum is a running one, so reaching the last
 * commit means reading every frame before it. SQLite's default
 * autocheckpoint is 1,000 pages; the value the framework sets on the owner's
 * phone is unverified, and nothing bounds a log a checkpoint never reached.
 * The read is streamed one frame at a time, so its memory is one page
 * whatever the length; its time on a phone for a long log is unmeasured.
 */
object SqliteWal {
    /** The `-wal` file header. */
    const val HEADER_BYTES = 32

    /** Each frame's header, before its page. */
    const val FRAME_HEADER_BYTES = 24

    /** The only WAL format version SQLite has written or accepts. */
    const val FORMAT_VERSION = 3_007_000

    /** Magic with its low bit clear: checksum words are little-endian; set: big-endian. */
    private const val MAGIC = 0x377f0682

    private const val MIN_PAGE_SIZE = 512
    private const val MAX_PAGE_SIZE = 65_536

    /**
     * The first [SqliteHeader.HEADER_BYTES] of the newest COMMITTED page 1 in
     * [wal], or null when there is none a reader would see.
     *
     * Null for: a stream shorter than a header; a wrong magic; a page size
     * that is not a power of two in 512..65536; a header checksum that does
     * not match; a format version other than [FORMAT_VERSION]; and a log
     * whose valid, committed frames never touch page 1. In every one of those
     * SQLite either ignores the log or refuses to open it, and in none of
     * them does the log change what page 1 says.
     *
     * Reads [wal] to the end of its last valid frame. Does not close it.
     */
    fun committedPageOne(wal: InputStream): ByteArray? {
        val header = ByteArray(HEADER_BYTES)
        if (!readFully(wal, header)) return null
        val magic = int32(header, 0)
        if (magic and 1.inv() != MAGIC) return null
        val bigEndianSums = magic and 1 == 1
        val pageSize = int32(header, 8)
        if (pageSize < MIN_PAGE_SIZE || pageSize > MAX_PAGE_SIZE || pageSize and (pageSize - 1) != 0) return null
        val sum = IntArray(2)
        checksum(header, 0, HEADER_BYTES - 8, bigEndianSums, sum)
        if (sum[0] != int32(header, 24) || sum[1] != int32(header, 28)) return null
        if (int32(header, 4) != FORMAT_VERSION) return null

        val frame = ByteArray(FRAME_HEADER_BYTES + pageSize)
        var latestPageOne: ByteArray? = null
        var committedPageOne: ByteArray? = null
        while (readFully(wal, frame)) {
            if (!saltsMatch(header, frame)) break
            val page = int32(frame, 0)
            if (page == 0) break
            checksum(frame, 0, 8, bigEndianSums, sum)
            checksum(frame, FRAME_HEADER_BYTES, pageSize, bigEndianSums, sum)
            if (sum[0] != int32(frame, 16) || sum[1] != int32(frame, 20)) break
            if (page == 1) {
                latestPageOne = frame.copyOfRange(FRAME_HEADER_BYTES, FRAME_HEADER_BYTES + SqliteHeader.HEADER_BYTES)
            }
            if (int32(frame, 4) != 0) committedPageOne = latestPageOne
        }
        return committedPageOne
    }

    /**
     * The user_version a reader would see: page 1 from the log when the log
     * has a committed one, the main file's header otherwise.
     *
     * NO FALLBACK FROM A LOG PAGE THAT WILL NOT PARSE. SQLite reads page 1
     * from the log whenever the log holds a committed one, whatever the main
     * file says, so a log page 1 that is not a database header makes the
     * answer null ("say nothing"), never the main file's number -- that number
     * is the one SQLite will not read.
     */
    fun effectiveUserVersion(mainHeader: ByteArray?, committedPageOne: ByteArray?): Int? =
        if (committedPageOne != null) {
            SqliteHeader.userVersion(committedPageOne)
        } else {
            mainHeader?.let(SqliteHeader::userVersion)
        }

    /**
     * SQLite's own checksum (`walChecksumBytes`): pairs of 32-bit words, in
     * the byte order the magic names, folded into two running sums. Updates
     * [sum] in place, so one call's result is the next call's seed -- the log's
     * checksum runs from its header through every frame.
     */
    internal fun checksum(bytes: ByteArray, offset: Int, length: Int, bigEndian: Boolean, sum: IntArray) {
        require(length % 8 == 0) { "checksummed length $length is not a multiple of 8" }
        var s0 = sum[0]
        var s1 = sum[1]
        var i = offset
        val end = offset + length
        while (i < end) {
            s0 += word(bytes, i, bigEndian) + s1
            s1 += word(bytes, i + 4, bigEndian) + s0
            i += 8
        }
        sum[0] = s0
        sum[1] = s1
    }

    private fun saltsMatch(header: ByteArray, frame: ByteArray): Boolean {
        for (i in 0 until 8) if (header[16 + i] != frame[8 + i]) return false
        return true
    }

    private fun word(bytes: ByteArray, at: Int, bigEndian: Boolean): Int =
        if (bigEndian) int32(bytes, at) else Integer.reverseBytes(int32(bytes, at))

    /** Big-endian, which every stored field in the format is. */
    private fun int32(bytes: ByteArray, at: Int): Int = (bytes[at].toInt() and 0xFF shl 24) or
        (bytes[at + 1].toInt() and 0xFF shl 16) or
        (bytes[at + 2].toInt() and 0xFF shl 8) or
        (bytes[at + 3].toInt() and 0xFF)

    /** True when [buffer] was filled; false at an end of stream that left it short. */
    private fun readFully(stream: InputStream, buffer: ByteArray): Boolean {
        var read = 0
        while (read < buffer.size) {
            val n = stream.read(buffer, read, buffer.size - read)
            if (n < 0) return false
            read += n
        }
        return true
    }
}
