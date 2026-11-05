package com.macrophage.barspeed.model

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * [SqliteWal]: which page 1 a reader of a `-wal` sees. Issue #113.
 *
 * TWO KINDS OF FIXTURE, AND WHY BOTH. [WalWriter] below writes logs to the
 * format from first principles, which lets every edge -- an uncommitted tail,
 * a broken checksum, a stale salt, a torn last frame -- be built on purpose.
 * But a writer and a reader made from the same reading of the format agree
 * with each other whether or not that reading is right. So the first test
 * reads a log REAL SQLite wrote, embedded byte for byte, and it is the one
 * that would catch a misread checksum or byte order shared by both halves.
 * It is synthetic, not field data: no lifter's database is in it.
 *
 * How it was made (SQLite 3.53.1 through Python's `sqlite3`): a fresh
 * database, `page_size=512`, `journal_mode=wal`, `wal_autocheckpoint=0`;
 * `create table t(x)`; `user_version=10`; `wal_checkpoint(TRUNCATE)`, so the
 * main file holds 10 and the log is empty; then `user_version=21`, and the
 * main file and its `-wal` copied while the connection was still open, so
 * nothing checkpointed. SQLite opening the copy reports `user_version` 21;
 * bytes 60-63 of the copied main file read 10. That is #113's shape exactly.
 *
 * [WalWriter] was itself checked against SQLite when this file was written,
 * not only against this reader: a log it wrote, carrying a page 1 at 21 beside
 * a real main file at 10, read back as 21 through SQLite. That check ran
 * outside this repository -- nothing here executes SQLite -- and is recorded in
 * the commit body, not re-run by this file.
 */
class SqliteWalTest {
    /** A real `-wal`, 568 bytes: one committed frame, page 1, user_version 21. */
    private val realWal: ByteArray = Base64.getDecoder().decode(
        "N38GggAt4hgAAAIAAAAAAeeSABXUGSwM6suV+q/Rh3MAAAABAAAAAueSABXUGSwMLSJR/JIpPXZTUUxpdGUgZm9ybWF0IDMAAgAC" +
            "AgBAICAAAAACAAAAAgAAAAAAAAAAAAAAAQAAAAQAAAAAAAAAAAAAAAEAAAAVAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAIALpXJDQAAAAEB3wAB3wAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAB8BBhcPDwEvdGFibGV0dAJDUkVBVEUgVEFCTEUgdCh4KQ==",
    )

    /** The first 100 bytes of the main file copied beside [realWal]: user_version 10. */
    private val realMainHeader: ByteArray = Base64.getDecoder().decode(
        "U1FMaXRlIGZvcm1hdCAzAAIAAgIAQCAgAAAAAgAAAAIAAAAAAAAAAAAAAAEAAAAEAAAAAAAAAAAAAAABAAAACgAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAACAC6VyQ==",
    )

    /**
     * A big-endian-checksum log, 568 bytes: [WalWriter]'s output for one
     * committed page 1 at user_version 21 -- a real page 1 taken from a
     * SQLite-made database at 10, with offset 60 set to 21 -- which SQLite
     * 3.53.1 then opened beside that main file and read as 21 (and read as
     * 10 with this frame's checksum broken, its salt stale, or its commit
     * field zero). Fixed here as bytes because SQLite on a little-endian
     * machine only ever WRITES the little-endian magic, so without it the
     * big-endian branch would be checked only by the writer that shares its
     * reading of the format: forcing little-endian words in both halves
     * passed every other test in this file.
     */
    private val sqliteVerifiedBigEndianWal: ByteArray = Base64.getDecoder().decode(
        "N38GgwAt4hgAAAIAAAAAABI0VngLrfANKDkhT9q/6xUAAAABAAAAAhI0VngLrfANSARVjx1zkqpTUUxpdGUgZm9ybWF0IDMAAgAC" +
            "AgBAICAAAAACAAAAAgAAAAAAAAAAAAAAAQAAAAQAAAAAAAAAAAAAAAEAAAAVAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAIALpXJDQAAAAEB3wAB3wAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAB8BBhcPDwEvdGFibGV0dAJDUkVBVEUgVEFCTEUgdCh4KQ==",
    )

    private fun read(bytes: ByteArray): ByteArray? = SqliteWal.committedPageOne(ByteArrayInputStream(bytes))

    private fun versionIn(bytes: ByteArray): Int? = read(bytes)?.let { SqliteHeader.userVersion(it) }

    // ---- real SQLite output -------------------------------------------------

    @Test
    fun `a log real SQLite wrote reads as the version SQLite reports, not the stale main file's`() {
        assertEquals(10, SqliteHeader.userVersion(realMainHeader), "the fixture's main file is not the stale one")
        assertEquals(21, versionIn(realWal))
        assertEquals(21, SqliteWal.effectiveUserVersion(realMainHeader, read(realWal)))
    }

    @Test
    fun `a big-endian log SQLite read as 21 reads as 21`() {
        val magic = sqliteVerifiedBigEndianWal.take(4).fold(0) { acc, b -> acc shl 8 or (b.toInt() and 0xFF) }
        assertEquals(0x377f0683, magic, "the fixture is not the big-endian form")
        assertEquals(21, versionIn(sqliteVerifiedBigEndianWal))
    }

    @Test
    fun `the real log with its last byte torn off holds no complete frame`() {
        assertNull(read(realWal.copyOf(realWal.size - 1)))
    }

    @Test
    fun `the real log with one page byte flipped fails its checksum`() {
        val corrupt = realWal.copyOf().also { it[SqliteWal.HEADER_BYTES + SqliteWal.FRAME_HEADER_BYTES + 200] = 1 }
        assertNull(read(corrupt))
    }

    // ---- which frames count -------------------------------------------------

    @Test
    fun `a committed page 1 is read, little-endian sums`() {
        assertEquals(21, versionIn(WalWriter().frame(1, commit = 2, version = 21).bytes()))
    }

    @Test
    fun `a committed page 1 is read, big-endian sums`() {
        assertEquals(21, versionIn(WalWriter(bigEndian = true).frame(1, commit = 2, version = 21).bytes()))
    }

    @Test
    fun `a page 1 with no commit after it is not seen`() {
        assertNull(read(WalWriter().frame(1, commit = 0, version = 21).bytes()))
    }

    @Test
    fun `a page 1 is committed by a later commit frame for another page`() {
        val log = WalWriter().frame(1, commit = 0, version = 21).frame(3, commit = 3).bytes()
        assertEquals(21, versionIn(log))
    }

    @Test
    fun `the newest committed page 1 wins`() {
        val log = WalWriter().frame(1, commit = 2, version = 20).frame(1, commit = 2, version = 21).bytes()
        assertEquals(21, versionIn(log))
    }

    @Test
    fun `an uncommitted page 1 after a committed one is not seen`() {
        val log = WalWriter()
            .frame(1, commit = 2, version = 20)
            .frame(1, commit = 0, version = 21)
            .frame(3, commit = 0)
            .bytes()
        assertEquals(20, versionIn(log))
    }

    @Test
    fun `a frame whose checksum fails ends the log there`() {
        val log = WalWriter().frame(1, commit = 2, version = 20).frame(1, commit = 2, version = 21, breakSum = true)
            .frame(3, commit = 3).bytes()
        assertEquals(20, versionIn(log))
    }

    /**
     * A frame from before the log was restarted carries the old salt. SQLite
     * rewrites the header with a new salt when it restarts a log, and leaves
     * the old frames' bytes in place beyond the new ones.
     */
    @Test
    fun `a frame with a stale salt ends the log there`() {
        val log = WalWriter().frame(1, commit = 2, version = 20).frame(1, commit = 2, version = 21, staleSalt = true)
            .bytes()
        assertEquals(20, versionIn(log))
    }

    @Test
    fun `a torn last frame is ignored and the frames before it stand`() {
        val log = WalWriter().frame(1, commit = 2, version = 20).frame(1, commit = 2, version = 21).bytes()
        assertEquals(20, versionIn(log.copyOf(log.size - 7)))
    }

    @Test
    fun `a frame for page 0 ends the log there`() {
        val log = WalWriter().frame(1, commit = 2, version = 20).frame(0, commit = 2).frame(1, commit = 2, version = 21)
            .bytes()
        assertEquals(20, versionIn(log))
    }

    @Test
    fun `a log whose committed frames never touch page 1 says nothing`() {
        assertNull(read(WalWriter().frame(2, commit = 2).frame(3, commit = 3).bytes()))
    }

    @Test
    fun `a header with no frames says nothing`() {
        assertNull(read(WalWriter().bytes()))
    }

    // ---- headers SQLite would not use ---------------------------------------

    @Test
    fun `a stream shorter than a header says nothing`() {
        assertNull(read(ByteArray(SqliteWal.HEADER_BYTES - 1)))
        assertNull(read(ByteArray(0)))
    }

    @Test
    fun `a wrong magic says nothing`() {
        assertNull(read(WalWriter(magic = 0x377f0684).frame(1, commit = 2, version = 21).bytes()))
    }

    @Test
    fun `a page size that is not a power of two in range says nothing`() {
        for (size in listOf(256, 1000, 131_072)) {
            assertNull(read(WalWriter(pageSize = size).frame(1, commit = 2, version = 21).bytes()), "page size $size")
        }
    }

    @Test
    fun `the largest and smallest page sizes are read`() {
        for (size in listOf(512, 65_536)) {
            assertEquals(21, versionIn(WalWriter(pageSize = size).frame(1, commit = 2, version = 21).bytes()))
        }
    }

    @Test
    fun `a header whose checksum fails says nothing`() {
        assertNull(read(WalWriter(breakHeaderSum = true).frame(1, commit = 2, version = 21).bytes()))
    }

    @Test
    fun `a format version other than SQLite's says nothing`() {
        assertNull(read(WalWriter(formatVersion = 3_007_001).frame(1, commit = 2, version = 21).bytes()))
    }

    // ---- which header stands ------------------------------------------------

    @Test
    fun `the log's page 1 stands over the main file's, and the main file's stands without one`() {
        val main = SqliteHeaderBytes.of(20)
        assertEquals(21, SqliteWal.effectiveUserVersion(main, SqliteHeaderBytes.of(21)))
        assertEquals(20, SqliteWal.effectiveUserVersion(main, null))
        assertNull(SqliteWal.effectiveUserVersion(null, null))
        assertEquals(21, SqliteWal.effectiveUserVersion(null, SqliteHeaderBytes.of(21)))
    }

    /** SQLite reads page 1 from the log when the log has one, garbage or not. */
    @Test
    fun `a log page 1 that is not a database header says nothing rather than falling back`() {
        assertNull(SqliteWal.effectiveUserVersion(SqliteHeaderBytes.of(20), ByteArray(SqliteHeader.HEADER_BYTES)))
    }

    @Test
    fun `the checksum refuses a length that is not whole word pairs`() {
        assertFailsWith<IllegalArgumentException> {
            SqliteWal.checksum(ByteArray(12), 0, 12, false, IntArray(2))
        }
    }
}

/** A database header page's first 100 bytes with [version] at offset 60. */
internal object SqliteHeaderBytes {
    fun of(version: Int, size: Int = SqliteHeader.HEADER_BYTES): ByteArray {
        val bytes = ByteArray(size)
        "SQLite format 3".toByteArray(Charsets.US_ASCII).copyInto(bytes)
        putInt(bytes, SqliteHeader.USER_VERSION_OFFSET, version)
        return bytes
    }

    fun putInt(bytes: ByteArray, at: Int, value: Int) {
        bytes[at] = (value ushr 24).toByte()
        bytes[at + 1] = (value ushr 16).toByte()
        bytes[at + 2] = (value ushr 8).toByte()
        bytes[at + 3] = value.toByte()
    }
}

/**
 * Writes a `-wal` to the format SQLite documents (`fileformat2.html`, "The
 * Write-Ahead Log"), with its running checksum, so a test can build the exact
 * log it means. Every field big-endian; checksum words in the order the
 * magic's low bit names. The `break*`/`stale*` switches produce one specific
 * defect each, so a test names the defect it is about.
 */
internal class WalWriter(
    private val pageSize: Int = 512,
    private val bigEndian: Boolean = false,
    private val magic: Int = if (bigEndian) 0x377f0683 else 0x377f0682,
    private val formatVersion: Int = SqliteWal.FORMAT_VERSION,
    private val breakHeaderSum: Boolean = false,
) {
    private val salt1 = 0x1234_5678
    private val salt2 = 0x0BAD_F00D
    private val out = ByteArrayOutputStream()
    private val sum = IntArray(2)

    init {
        val header = ByteArray(SqliteWal.HEADER_BYTES)
        SqliteHeaderBytes.putInt(header, 0, magic)
        SqliteHeaderBytes.putInt(header, 4, formatVersion)
        SqliteHeaderBytes.putInt(header, 8, pageSize)
        SqliteHeaderBytes.putInt(header, 12, 0)
        SqliteHeaderBytes.putInt(header, 16, salt1)
        SqliteHeaderBytes.putInt(header, 20, salt2)
        SqliteWal.checksum(header, 0, 24, bigEndian, sum)
        SqliteHeaderBytes.putInt(header, 24, if (breakHeaderSum) sum[0] + 1 else sum[0])
        SqliteHeaderBytes.putInt(header, 28, sum[1])
        out.write(header)
    }

    /**
     * One frame for [page]. [commit] is the frame's "database size after
     * commit" field: non-zero makes it a commit frame. A page-1 frame carries a
     * header at [version]; any other page is zeros. [data], when given, is
     * the whole page instead, as it is when this writer's output is checked
     * against SQLite beside a real main file.
     */
    fun frame(
        page: Int,
        commit: Int,
        version: Int = 0,
        breakSum: Boolean = false,
        staleSalt: Boolean = false,
        data: ByteArray? = null,
    ): WalWriter {
        val frame = ByteArray(SqliteWal.FRAME_HEADER_BYTES + pageSize)
        SqliteHeaderBytes.putInt(frame, 0, page)
        SqliteHeaderBytes.putInt(frame, 4, commit)
        SqliteHeaderBytes.putInt(frame, 8, if (staleSalt) salt1 - 1 else salt1)
        SqliteHeaderBytes.putInt(frame, 12, salt2)
        when {
            data != null -> data.copyInto(frame, SqliteWal.FRAME_HEADER_BYTES)
            page == 1 -> SqliteHeaderBytes.of(version).copyInto(frame, SqliteWal.FRAME_HEADER_BYTES)
        }
        val running = sum.copyOf()
        SqliteWal.checksum(frame, 0, 8, bigEndian, running)
        SqliteWal.checksum(frame, SqliteWal.FRAME_HEADER_BYTES, pageSize, bigEndian, running)
        SqliteHeaderBytes.putInt(frame, 16, if (breakSum) running[0] xor 1 else running[0])
        SqliteHeaderBytes.putInt(frame, 20, running[1])
        if (!breakSum) running.copyInto(sum)
        out.write(frame)
        return this
    }

    fun bytes(): ByteArray = out.toByteArray()
}
