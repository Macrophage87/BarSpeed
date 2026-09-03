package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [SqliteWal] and [DatabaseDowngradePolicy.effectiveVersion]. Issue #113.
 *
 * WHAT THIS FILE CAN AND CANNOT SHOW, stated in the same terms
 * [DatabaseDowngradePolicyTest] states its own limit, because the trap is the
 * same one and it already caught issue #101. These headers are built by hand,
 * so what is pinned is that the parser reads the offsets it was told to read
 * and folds the frames the way the KDoc says. It cannot pin that those are the
 * RIGHT offsets, because nothing in this module can produce a real SQLite file.
 *
 * That half is discharged elsewhere and deliberately, in
 * `core/data/src/test/resources/sqlite/`, where a database and a `-wal` written
 * by SQLite 3.50.4 are committed as bytes and read by `WalStaleVersionTest`.
 * The numbers in the fixtures below are the numbers measured off those bytes --
 * magic 377f0682, page size 512, a page-1 frame with dbSizeAfterCommit 0
 * followed by a page-2 frame with dbSizeAfterCommit 2 -- so the hand-built
 * cases are shaped by an observation rather than by what was convenient to
 * assert.
 */
class SqliteWalTest {
    private fun be(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    /** A 32-byte WAL header, matching the layout measured off the committed fixture. */
    private fun walHeaderBytes(
        magic: Int = 0x377F0682,
        pageSize: Int = 512,
        salt1: Int = 0x1DCF5C42,
        salt2: Int = -0x023C700A,
    ): ByteArray = be(magic) + be(3_007_000) + be(pageSize) + be(1) + be(salt1) + be(salt2) + be(0) + be(0)

    private fun frameHeaderBytes(
        pageNumber: Int,
        dbSizeAfterCommit: Int,
        salt1: Int = 0x1DCF5C42,
        salt2: Int = -0x023C700A,
    ): ByteArray = be(pageNumber) + be(dbSizeAfterCommit) + be(salt1) + be(salt2) + be(0) + be(0)

    private fun frame(
        pageNumber: Int,
        dbSizeAfterCommit: Int,
        userVersion: Int? = null,
        salt1: Int = 0x1DCF5C42,
        salt2: Int = -0x023C700A,
    ) = WalFrame(
        header = WalFrameHeader(pageNumber, dbSizeAfterCommit, salt1, salt2),
        page1UserVersion = userVersion,
    )

    private val header = WalHeader(pageSize = 512, salt1 = 0x1DCF5C42, salt2 = -0x023C700A)

    // ---- the file header ---------------------------------------------------

    @Test
    fun `a well-formed wal header yields its page size and salts`() {
        val parsed = SqliteWal.header(walHeaderBytes())
        assertEquals(WalHeader(512, 0x1DCF5C42, -0x023C700A), parsed)
    }

    /**
     * Both magics are real files. The low bit selects the endianness of the
     * frame CHECKSUMS, which this parser does not read, so both are accepted
     * and neither changes anything it does read.
     */
    @Test
    fun `both wal magics are accepted`() {
        assertEquals(512, SqliteWal.header(walHeaderBytes(magic = 0x377F0682))?.pageSize)
        assertEquals(512, SqliteWal.header(walHeaderBytes(magic = 0x377F0683))?.pageSize)
    }

    @Test
    fun `anything that is not a wal says nothing`() {
        assertNull(SqliteWal.header(ByteArray(31)), "a file too short to hold a header")
        assertNull(SqliteWal.header(walHeaderBytes(magic = 0x377F0684)), "a magic one past the real pair")
        assertNull(SqliteWal.header(ByteArray(64)), "a run of zeroes")
    }

    /**
     * A page size this object will not vouch for stops the parse, and the
     * reason is not tidiness. The page size is the frame stride: zero loops
     * forever and a negative runs the scan backwards through the file.
     */
    @Test
    fun `a page size outside SQLite's own bounds says nothing`() {
        assertNull(SqliteWal.header(walHeaderBytes(pageSize = 0)), "zero")
        assertNull(SqliteWal.header(walHeaderBytes(pageSize = -512)), "negative")
        assertNull(SqliteWal.header(walHeaderBytes(pageSize = 256)), "below the 512 floor")
        assertNull(SqliteWal.header(walHeaderBytes(pageSize = 131_072)), "above the 65536 ceiling")
        assertNull(SqliteWal.header(walHeaderBytes(pageSize = 4095)), "not a power of two")
        assertEquals(512, SqliteWal.header(walHeaderBytes(pageSize = 512))?.pageSize, "the floor itself")
        assertEquals(65_536, SqliteWal.header(walHeaderBytes(pageSize = 65_536))?.pageSize, "the ceiling itself")
    }

    // ---- frame headers -----------------------------------------------------

    @Test
    fun `a frame header yields its page, its commit size and its salts`() {
        assertEquals(
            WalFrameHeader(pageNumber = 2, dbSizeAfterCommit = 2, salt1 = 0x1DCF5C42, salt2 = -0x023C700A),
            SqliteWal.frameHeader(frameHeaderBytes(pageNumber = 2, dbSizeAfterCommit = 2)),
        )
    }

    @Test
    fun `a frame header that is not there says nothing`() {
        assertNull(SqliteWal.frameHeader(ByteArray(23)))
        assertNull(SqliteWal.frameHeader(frameHeaderBytes(pageNumber = 0, dbSizeAfterCommit = 0)), "page zero")
        assertNull(SqliteWal.frameHeader(frameHeaderBytes(pageNumber = -1, dbSizeAfterCommit = 0)), "negative page")
    }

    // ---- the fold ----------------------------------------------------------

    /**
     * THE SHAPE THE REAL FIXTURE HAS. The frame carrying the new version is not
     * itself a commit frame; the frame after it commits. This is the case the
     * whole issue turns on and the one a hand-built fixture would not have
     * thought to produce -- it was copied from what SQLite actually wrote.
     */
    @Test
    fun `a page-1 frame committed by a later frame is the version`() {
        val frames = sequenceOf(frame(1, dbSizeAfterCommit = 0, userVersion = 17), frame(2, dbSizeAfterCommit = 2))
        assertEquals(17, SqliteWal.committedUserVersion(header, frames))
    }

    /** A page-1 frame that is itself the commit counts too. */
    @Test
    fun `a page-1 frame that commits on its own is the version`() {
        assertEquals(17, SqliteWal.committedUserVersion(header, sequenceOf(frame(1, 3, userVersion = 17))))
    }

    /**
     * THE CASE THAT DECIDES THE DESIGN. A migration killed before its commit
     * leaves the new version in the log with nothing behind it, and SQLite
     * discards it -- verified against SQLite 3.50.4 by truncating the committed
     * fixture's `-wal` to its page-1 frame, which then reads back as
     * user_version 16, the main header's value, with the newer row gone.
     *
     * Reading that pending 17 as real would move a database aside that the
     * running build could have opened.
     */
    @Test
    fun `a page-1 frame with no commit behind it is not the version`() {
        assertNull(SqliteWal.committedUserVersion(header, sequenceOf(frame(1, 0, userVersion = 17))))
    }

    @Test
    fun `a pending page-1 frame after the last commit is discarded`() {
        val frames = sequenceOf(
            frame(1, dbSizeAfterCommit = 0, userVersion = 17),
            frame(2, dbSizeAfterCommit = 2),
            frame(1, dbSizeAfterCommit = 0, userVersion = 18),
        )
        assertEquals(17, SqliteWal.committedUserVersion(header, frames), "an uncommitted 18 was taken as real")
    }

    @Test
    fun `the last committed page-1 frame wins`() {
        val frames = sequenceOf(
            frame(1, dbSizeAfterCommit = 2, userVersion = 17),
            frame(1, dbSizeAfterCommit = 2, userVersion = 18),
        )
        assertEquals(18, SqliteWal.committedUserVersion(header, frames))
    }

    /**
     * A log whose transactions never touched page 1 says nothing about the
     * version, which is the ordinary case rather than a failure. The main
     * file's header is then the whole story.
     */
    @Test
    fun `a log that never wrote page 1 says nothing`() {
        val frames = sequenceOf(frame(2, 0), frame(3, 0), frame(4, dbSizeAfterCommit = 4))
        assertNull(SqliteWal.committedUserVersion(header, frames))
    }

    /**
     * Frames from before a checkpoint reset carry the OLD salts and are not
     * part of this log. The scan stops rather than skipping, because everything
     * after a foreign frame is older too.
     */
    @Test
    fun `the scan stops at the first frame with foreign salts`() {
        val frames = sequenceOf(
            frame(1, dbSizeAfterCommit = 2, userVersion = 17),
            frame(1, dbSizeAfterCommit = 2, userVersion = 99, salt1 = 0x0BADF00D),
        )
        assertEquals(17, SqliteWal.committedUserVersion(header, frames), "a stale frame's version was taken")
    }

    @Test
    fun `a second salt that does not match is foreign too`() {
        val frames = sequenceOf(frame(1, dbSizeAfterCommit = 2, userVersion = 99, salt2 = 0x0BADF00D))
        assertNull(SqliteWal.committedUserVersion(header, frames))
    }

    @Test
    fun `an empty log says nothing`() {
        assertNull(SqliteWal.committedUserVersion(header, emptySequence()))
    }

    /**
     * The scan is abandoned at the first foreign frame rather than run to the
     * end, so a caller reading a large log stops at the first byte that cannot
     * help. Counted rather than argued: the sequence records how far it was
     * consumed.
     */
    @Test
    fun `the scan is abandoned at the foreign frame, not run to the end`() {
        var consumed = 0
        val frames = sequence {
            consumed++
            yield(frame(1, dbSizeAfterCommit = 2, userVersion = 17))
            consumed++
            yield(frame(1, dbSizeAfterCommit = 2, userVersion = 99, salt1 = 0x0BADF00D))
            consumed++
            yield(frame(1, dbSizeAfterCommit = 2, userVersion = 100))
        }
        assertEquals(17, SqliteWal.committedUserVersion(header, frames))
        assertEquals(2, consumed, "the scan read past the frame it had already rejected")
    }

    // ---- the decision ------------------------------------------------------

    @Test
    fun `the wal's version beats a stale header`() {
        assertEquals(17, DatabaseDowngradePolicy.effectiveVersion(headerVersion = 16, walVersion = 17))
    }

    @Test
    fun `either reading alone is the answer when the other says nothing`() {
        assertEquals(16, DatabaseDowngradePolicy.effectiveVersion(headerVersion = 16, walVersion = null))
        assertEquals(17, DatabaseDowngradePolicy.effectiveVersion(headerVersion = null, walVersion = 17))
        assertNull(DatabaseDowngradePolicy.effectiveVersion(headerVersion = null, walVersion = null))
    }

    /**
     * A disagreement resolves UPWARDS, towards detecting a downgrade, and this
     * is the assertion that pins the direction rather than the arithmetic. A
     * WAL frame cannot be older than the main file for the same page, so this
     * combination should not arise; if a scan ever produced it, failing towards
     * a rescue costs a recoverable empty start, and failing the other way costs
     * an app that will not open -- which invites the uninstall that takes the
     * whole data directory.
     */
    @Test
    fun `a lower wal reading cannot drag the header's version down`() {
        assertEquals(16, DatabaseDowngradePolicy.effectiveVersion(headerVersion = 16, walVersion = 9))
    }

    @Test
    fun `a stale header plus a newer wal is a rescue`() {
        assertEquals(
            DatabaseOpenAction.RESCUE_THEN_OPEN,
            DatabaseDowngradePolicy.decide(headerVersion = 16, walVersion = 17, compiledVersion = 16),
        )
    }

    @Test
    fun `a wal that says nothing leaves the header's decision exactly as it was`() {
        assertEquals(
            DatabaseOpenAction.OPEN,
            DatabaseDowngradePolicy.decide(headerVersion = 16, walVersion = null, compiledVersion = 16),
        )
        assertEquals(
            DatabaseOpenAction.RESCUE_THEN_OPEN,
            DatabaseDowngradePolicy.decide(headerVersion = 17, walVersion = null, compiledVersion = 16),
        )
        assertEquals(
            DatabaseOpenAction.OPEN,
            DatabaseDowngradePolicy.decide(headerVersion = null, walVersion = null, compiledVersion = 16),
        )
    }

    /**
     * An uncommitted bump does not provoke a rescue. This is the whole point of
     * tracking commit frames, expressed at the decision rather than at the
     * parse: a build compiled at 16, meeting a database whose main header says
     * 16 and whose log holds a pending 17, opens normally -- because SQLite
     * will read that database as 16 too.
     */
    @Test
    fun `an uncommitted bump does not provoke a rescue`() {
        val wal = SqliteWal.committedUserVersion(header, sequenceOf(frame(1, 0, userVersion = 17)))
        assertEquals(
            DatabaseOpenAction.OPEN,
            DatabaseDowngradePolicy.decide(headerVersion = 16, walVersion = wal, compiledVersion = 16),
        )
    }
}
