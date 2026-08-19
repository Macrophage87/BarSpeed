package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The decision that stands between an older APK and every session the lifter
 * has recorded (issue #101).
 *
 * The headers here are BUILT BY HAND, and the limit of what that can show is
 * worth stating where the assertions are. These pin that the parser reads the
 * offset it was told to read and refuses what it should refuse. They do not
 * pin that offset 60 is where SQLite keeps user_version -- nothing in this
 * repository can write a real SQLite file -- and that half was verified
 * separately, once, by writing a database and reading its bytes. See
 * [SqliteHeader] for the record of it.
 */
class DatabaseDowngradePolicyTest {
    /**
     * LITERAL 60 and LITERAL 100, not the constants the parser reads.
     *
     * Written with SqliteHeader.USER_VERSION_OFFSET this helper was
     * self-consistent: moving the constant moved writer and reader together and
     * every assertion still passed, so the offset -- the one fact the empirical
     * verification exists to establish -- was pinned by nothing at all.
     */
    private fun header(version: Int, magic: String = "SQLite format 3\u0000"): ByteArray {
        val bytes = ByteArray(100)
        magic.toByteArray(Charsets.US_ASCII).copyInto(bytes)
        val at = 60
        bytes[at] = (version ushr 24).toByte()
        bytes[at + 1] = (version ushr 16).toByte()
        bytes[at + 2] = (version ushr 8).toByte()
        bytes[at + 3] = version.toByte()
        return bytes
    }

    @Test
    fun `a well-formed header yields the version it carries`() {
        assertEquals(9, SqliteHeader.userVersion(header(9)))
        assertEquals(1, SqliteHeader.userVersion(header(1)))
        assertEquals(0, SqliteHeader.userVersion(header(0)))
        assertEquals(300, SqliteHeader.userVersion(header(300)), "a version past one byte")
    }

    @Test
    fun `the version is read big-endian, which is how SQLite writes it`() {
        val bytes = SqliteHeader.userVersion(header(0x01020304))
        assertEquals(0x01020304, bytes, "the four bytes were assembled in the wrong order")
    }

    @Test
    fun `a file too short to hold a header says nothing`() {
        assertNull(SqliteHeader.userVersion(ByteArray(0)))
        assertNull(SqliteHeader.userVersion(ByteArray(99)))
    }

    @Test
    fun `a file that is not a SQLite database says nothing`() {
        assertNull(SqliteHeader.userVersion(header(9, magic = "NOT a database\u0000\u0000")))
    }

    /**
     * The top bit set would read as a negative version. It cannot come from
     * Room, so it is refused rather than compared -- a negative number would
     * sort BELOW any compiled version and be read as an upgrade, which is the
     * one wrong answer that leads to the file being opened destructively.
     */
    @Test
    fun `a version with the top bit set says nothing rather than going negative`() {
        assertNull(SqliteHeader.userVersion(header(-1)))
        assertNull(SqliteHeader.userVersion(header(Int.MIN_VALUE)))
    }

    // ---- the decision ------------------------------------------------------

    @Test
    fun `a newer file on disk is rescued`() {
        assertEquals(DatabaseOpenAction.RESCUE_THEN_OPEN, DatabaseDowngradePolicy.decide(10, 9))
        assertEquals(DatabaseOpenAction.RESCUE_THEN_OPEN, DatabaseDowngradePolicy.decide(99, 9))
    }

    @Test
    fun `the same version opens normally`() {
        assertEquals(DatabaseOpenAction.OPEN, DatabaseDowngradePolicy.decide(9, 9))
    }

    /** An older file is an UPGRADE and belongs to the migrations, untouched. */
    @Test
    fun `an older file opens normally and is left for the migrations`() {
        assertEquals(DatabaseOpenAction.OPEN, DatabaseDowngradePolicy.decide(8, 9))
        assertEquals(DatabaseOpenAction.OPEN, DatabaseDowngradePolicy.decide(1, 9))
    }

    /**
     * No file, or a file this code cannot read, opens normally.
     *
     * Deliberately the safe direction: moving a database aside on the strength
     * of a guess would be acting destructively on a file nobody understands.
     * The cost is that a downgrade could slip past, and the builder answers
     * that by having no destructive fallback left to fall into -- an unhandled
     * downgrade throws instead of dropping.
     */
    @Test
    fun `an unreadable or absent version opens normally`() {
        assertEquals(DatabaseOpenAction.OPEN, DatabaseDowngradePolicy.decide(null, 9))
    }

    /** The parser and the decision, composed, on the case that matters. */
    @Test
    fun `a v10 header against a v9 build is rescued end to end`() {
        val stored = SqliteHeader.userVersion(header(10))
        assertEquals(DatabaseOpenAction.RESCUE_THEN_OPEN, DatabaseDowngradePolicy.decide(stored, 9))
    }
}
