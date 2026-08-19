package com.macrophage.barspeed.model

/**
 * The version an on-disk SQLite file says it is, read without opening it.
 *
 * SQLite keeps the user_version -- what Room stores its schema version in -- at
 * a fixed offset in the 100-byte file header, so it can be read with plain file
 * I/O: no SQLite, no Room, no Android, and no risk of triggering the very open
 * that this exists to get ahead of.
 *
 * The offset was verified rather than remembered. Writing a database, setting
 * PRAGMA user_version to 9, reading bytes 60-63 as a big-endian integer and
 * getting 9; setting it to 10 and reading 10. A remembered offset would have
 * been a claim, and everything downstream of this function depends on it.
 *
 * NOTE what the tests below it can and cannot show. They build a header by
 * hand, so they pin that this parser reads the offset it was told to read.
 * They cannot pin that the offset is the right one -- nothing in this
 * repository can produce a real SQLite file -- and that half rests on the
 * verification recorded above.
 */
object SqliteHeader {
    /** "SQLite format 3" and its terminating NUL, at the start of every database. */
    private val MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    /** The header SQLite reserves; nothing useful lives before it. */
    const val HEADER_BYTES = 100

    /** Byte offset of the four-byte big-endian user_version. */
    const val USER_VERSION_OFFSET = 60

    /**
     * The schema version [header] declares, or null when it does not declare
     * one this function is willing to vouch for.
     *
     * Null for a file too short to hold a header, for anything whose magic does
     * not match, and for a negative version. Null means "say nothing", never
     * "version zero": a caller that cannot read a version must not conclude the
     * file is older than the running app, because that conclusion is what
     * destroys it.
     */
    fun userVersion(header: ByteArray): Int? {
        if (header.size < HEADER_BYTES) return null
        for (i in MAGIC.indices) if (header[i] != MAGIC[i]) return null
        var value = 0
        for (i in 0 until 4) {
            value = (value shl 8) or (header[USER_VERSION_OFFSET + i].toInt() and 0xFF)
        }
        return value.takeIf { it >= 0 }
    }
}

/**
 * What to do about the database file that is already on disk.
 *
 * Issue #101: the app is distributed as APKs the lifter installs themselves,
 * and reinstalling the previous one is the natural way to recover from a bad
 * release. It is also the one action that destroys every session ever recorded
 * -- Room, meeting a file newer than the code, drops every table and recreates
 * them empty, silently and indistinguishably from a first install.
 *
 * This is the decision, kept apart from the doing so it can be tested. The
 * doing is file movement in :core:data; the wiring is one call in a builder
 * that nothing in this repository can execute.
 */
enum class DatabaseOpenAction {
    /**
     * Open normally. The file is missing, matches, is older -- an upgrade,
     * which the migrations handle -- or says nothing this code will act on.
     */
    OPEN,

    /**
     * Move the file aside first, then open, which will create a fresh empty
     * database because nothing is there any more.
     *
     * MOVE rather than copy. A copy doubles peak storage, can fail halfway, and
     * still leaves the original to be destroyed; a rename inside one filesystem
     * costs nothing, preserves the bytes exactly, and leaves the app working.
     */
    RESCUE_THEN_OPEN,
}

object DatabaseDowngradePolicy {
    /**
     * [storedVersion] is what the file says, or null when it says nothing
     * trustworthy. [compiledVersion] is what this build of the app knows.
     *
     * Only a file that is strictly NEWER is rescued. Equal is the ordinary
     * case, older is an upgrade and belongs to the migrations, and null is the
     * case that decides the shape of this function: an unreadable header is
     * treated as ordinary rather than as a downgrade, because rescuing on a
     * file this code cannot read would move a database aside on the strength of
     * a guess. The cost of being wrong in that direction is that a downgrade
     * slips through -- and the builder is arranged so a downgrade that slips
     * through THROWS rather than drops, which is why this is the safe way round.
     */
    fun decide(storedVersion: Int?, compiledVersion: Int): DatabaseOpenAction =
        if (storedVersion != null && storedVersion > compiledVersion) {
            DatabaseOpenAction.RESCUE_THEN_OPEN
        } else {
            DatabaseOpenAction.OPEN
        }
}
