package com.macrophage.barspeed.data

import com.macrophage.barspeed.model.SqliteWal
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The version an on-disk database ACTUALLY has, against a `-wal` that SQLite
 * itself wrote. Issue #113.
 *
 * WHY THIS FILE CARRIES A BINARY FIXTURE. Every other test of this path builds
 * its header by hand, so it pins that the parser reads the offset it was told
 * to read and nothing more. `SqliteHeader`'s own KDoc says the limit out loud:
 * "nothing in this repository can produce a real SQLite file". These two
 * resources are one -- produced by SQLite 3.50.4 and committed as bytes -- so
 * what is pinned here is that this code reads a file SQLite wrote, not a file
 * this repository invented in the same shape it parses.
 *
 * HOW THE FIXTURE WAS MADE, and it can be remade the same way:
 *
 * ```
 * c = sqlite3.connect("a.db", isolation_level=None)
 * c.execute("PRAGMA page_size=512"); c.execute("PRAGMA journal_mode=WAL")
 * c.execute("PRAGMA user_version=16")
 * c.execute("CREATE TABLE set_records(id INTEGER PRIMARY KEY, note TEXT)")
 * c.execute("INSERT INTO set_records(note) VALUES ('recorded on the older build')")
 * c.execute("PRAGMA wal_checkpoint(TRUNCATE)")     # a clean state at 16
 * c.execute("BEGIN"); c.execute("PRAGMA user_version=17")
 * c.execute("INSERT INTO set_records(note) VALUES ('recorded on the newer build')")
 * c.execute("COMMIT")
 * # copy a.db and a.db-wal NOW -- never close, which would checkpoint
 * ```
 *
 * COPYING IS THE POINT, and it is how the first attempt at this fixture was
 * lost. Opening the pair with any SQLite -- even read-only, even just to check
 * it -- checkpoints on close and DELETES the `-wal`, leaving a main file that
 * says 17 and no evidence of anything. The generator's own verification step
 * destroyed the fixture it had just written; it now verifies a copy. The same
 * hazard applies to a reader of these resources: never point sqlite3 at the
 * files in `src/test/resources`.
 *
 * WHAT THE FIXTURE IS, measured off the bytes as committed:
 *
 * ```
 * main:  1024 B, header bytes 60-63 = 16          <- STALE
 * wal:   1104 B, magic 377f0682, page size 512, salt1 1dcf5c42 salt2 fdc38ff6
 *   frame 0: page=1  dbSizeAfterCommit=0  page-1 user_version=17
 *   frame 1: page=2  dbSizeAfterCommit=2
 * SQLite reading through the WAL: user_version=17, 2 rows
 * ```
 *
 * Note frame 0: the frame carrying the new version is NOT itself a commit
 * frame. It is made real by frame 1, which commits. Anything looking for "the
 * last commit frame that is page 1" finds nothing here.
 */
class WalStaleVersionTest {
    private val root: File = Files.createTempDirectory("wal-version").toFile()

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    /**
     * The fixture, copied out of the jar into a real directory.
     *
     * A test resource is not a file on disk once it is packaged, and every
     * function under test here takes a [File]. Copying is also what the rescue
     * path itself would do to these bytes, so nothing is read in a shape the
     * production code never meets.
     */
    private fun fixture(stem: String = "wal-committed-bump.db"): File {
        val db = File(root, "accelerometer_lifting.db")
        for ((resource, dest) in listOf(stem to db, "$stem-wal" to File(db.path + "-wal"))) {
            val bytes = checkNotNull(javaClass.getResourceAsStream("/sqlite/$resource")) {
                "missing test resource /sqlite/$resource"
            }.use { it.readBytes() }
            dest.writeBytes(bytes)
        }
        return db
    }

    /**
     * The fixture is the shape the rest of this file argues from.
     *
     * Asserted rather than assumed, because a resource that failed to copy, or
     * one silently checkpointed by a curious reader, would make every test
     * below pass for the wrong reason: a missing `-wal` collapses the whole
     * question into the ordinary header read.
     */
    @Test
    fun `the fixture is a stale main header beside a live wal`() {
        val db = fixture()
        val wal = File(db.path + "-wal")
        assertEquals(1024L, db.length(), "the main file is not the committed fixture")
        assertEquals(1104L, wal.length(), "the wal is not the committed fixture")
        assertEquals(16, SqliteHeaderProbe.mainHeaderVersion(db), "the main header is not stale at 16")
    }

    /**
     * THE DIFFERENTIAL. Issue #113: the version this reports must be the
     * version the database HAS, which for the committed fixture is 17 -- what
     * SQLite reports reading through the same two files.
     *
     * c0 pinned the wrong answer here, 16, read from the main header alone.
     * That assertion is DELETED rather than left beside this one: it was a
     * record of a defect, not a property, and keeping both would leave the file
     * asserting two different answers to one question.
     */
    @Test
    fun `the stored version reads through the wal, not the stale header`() {
        assertEquals(17, DatabaseRescue.storedVersion(fixture()))
    }

    /**
     * THE CONSEQUENCE. A build compiled at 16 meeting a database that is really
     * at 17 must rescue it.
     *
     * Before this, the Open branch was taken, Room opened the file, read 17
     * through the WAL, found it newer, and -- with no destructive fallback in
     * the chain -- threw. The corpus survived and the app did not start. c0
     * pinned that as `today a database that is really newer is not rescued`;
     * that assertion is deleted here, not reworded.
     */
    @Test
    fun `a database that is really newer is rescued`() {
        val db = fixture()
        val outcome = DatabaseRescue.rescue(db, File(root, DatabaseRescue.RESCUE_DIR), 16)
        assertTrue(outcome is RescueOutcome.Rescued, "expected Rescued, got " + outcome)
        assertTrue(!db.exists(), "the database is still in place, so Room would meet it and throw")
    }

    /**
     * THE WAL MOVES WITH IT, and for this defect that is not the same
     * assertion as `the wal and shm move with the database` already makes in
     * DatabaseRescueTest. There the `-wal` is three bytes of the word "wal";
     * here it is the only place the newer schema exists at all. Leaving it
     * behind would strand the newer half beside the database Room is about to
     * recreate under that name, and the rescued directory would hold a corpus
     * missing everything committed since the last checkpoint.
     */
    @Test
    fun `the wal carrying the newer version is rescued with the database`() {
        val db = fixture()
        val walBytes = File(db.path + "-wal").readBytes()
        val outcome = DatabaseRescue.rescue(db, File(root, DatabaseRescue.RESCUE_DIR), 16)
        assertTrue(outcome is RescueOutcome.Rescued, "expected Rescued, got " + outcome)
        assertEquals(
            listOf("accelerometer_lifting.db", "accelerometer_lifting.db-wal"),
            outcome.directory.listFiles().orEmpty().map { it.name }.sorted(),
        )
        assertEquals(
            walBytes.toList(),
            File(outcome.directory, "accelerometer_lifting.db-wal").readBytes().toList(),
            "the rescued wal is not the wal that was on disk",
        )
        assertTrue(!File(db.path + "-wal").exists(), "the wal was left beside the name Room will recreate")
    }

    /**
     * A MIGRATION KILLED BEFORE ITS COMMIT MUST NOT PROVOKE A RESCUE, and this
     * is the one test in this commit that is GREEN before the fix as well as
     * after. It is here rather than in c0 because it cannot be red: before the
     * fix nothing reads the log at all, so the answer is the header's 16 for
     * the trivial reason. It guards the fix from over-reaching in the other
     * direction, which is a failure c0 could not have expressed.
     *
     * The fixture is the committed `-wal` truncated to its first frame, which
     * is exactly what a kill between the page-1 write and the commit leaves.
     * Verified against SQLite 3.50.4: reading that pair back gives
     * user_version 16 and one row, the newer row gone.
     */
    @Test
    fun `a version bumped but never committed is not a rescue`() {
        val db = fixture()
        val wal = File(db.path + "-wal")
        wal.writeBytes(wal.readBytes().copyOfRange(0, SqliteWal.HEADER_BYTES + SqliteWal.FRAME_HEADER_BYTES + 512))
        assertEquals(16, DatabaseRescue.storedVersion(db), "an uncommitted bump was read as the version")
        assertEquals(RescueOutcome.NotNeeded, DatabaseRescue.rescue(db, File(root, DatabaseRescue.RESCUE_DIR), 16))
        assertTrue(db.isFile, "a database the running build could open was moved aside")
    }

    /**
     * A `-wal` that is not a write-ahead log changes nothing. Anything this
     * code cannot read must leave the header's reading exactly as it was, on
     * the same rule that governs an unreadable main file: never conclude
     * anything from bytes you could not parse.
     */
    @Test
    fun `a wal that is not a wal leaves the header's reading alone`() {
        val db = fixture()
        File(db.path + "-wal").writeText("this is not a write-ahead log".repeat(40))
        assertEquals(16, DatabaseRescue.storedVersion(db))
        assertEquals(RescueOutcome.NotNeeded, DatabaseRescue.rescue(db, File(root, DatabaseRescue.RESCUE_DIR), 16))

        File(db.path + "-wal").writeBytes(ByteArray(12))
        assertEquals(16, DatabaseRescue.storedVersion(db), "a wal too short to hold a header changed the answer")

        File(db.path + "-wal").writeBytes(ByteArray(0))
        assertEquals(16, DatabaseRescue.storedVersion(db), "an empty wal changed the answer")
    }

    /**
     * A TORN FINAL FRAME COSTS ONLY ITSELF, not the frames before it.
     *
     * FOUND BY MUTATION. Relaxing the whole-frame bound in `walVersion` from
     * `offset + stride <= length` to `offset < length` survived the entire
     * suite; nothing pinned it, so this is the pin.
     *
     * THE FIRST ATTEMPT AT THIS TEST DID NOT KILL THAT MUTATION EITHER, and
     * the reason is worth keeping. It appended 100 bytes of junk, whose first
     * 24 parse as a frame header with garbage salts -- so the salt check
     * stopped the scan and the answer came out right whether the bound was
     * there or not. A test that passes for a reason other than the one it
     * names is the thing a mutation table exists to catch, and it caught this
     * one before it was committed.
     *
     * WHAT A TORN WRITE ACTUALLY LEAVES is a REAL frame cut short: its 24-byte
     * header is intact and its salts MATCH, and only the page image is
     * truncated. That is what this appends -- a page-1 frame header with the
     * fixture's own salts, followed by 50 bytes where 512 belong.
     *
     * SQLite stops at the damaged frame and honours everything before it,
     * verified at 3.50.4 against exactly these bytes: user_version 17 and two
     * rows. With the bound, the short frame is never reached and the answer is
     * 17. Without it, the page read runs off the end, the exception guard
     * discards the WHOLE scan, and the answer falls back to the main header's
     * stale 16 -- a missed rescue, which is the direction that ends in a
     * crash-loop and an uninstall. One damaged frame must not cost the version
     * the frames before it committed.
     */
    @Test
    fun `a torn final frame does not discard the frames before it`() {
        val db = fixture()
        val wal = File(db.path + "-wal")
        val salts = wal.readBytes().copyOfRange(16, 24)
        val tornHeader = byteArrayOf(0, 0, 0, 1) + ByteArray(4) + salts + ByteArray(8)
        wal.writeBytes(wal.readBytes() + tornHeader + ByteArray(50) { it.toByte() })
        assertEquals(17, DatabaseRescue.storedVersion(db), "a torn tail discarded the committed frames")
        val outcome = DatabaseRescue.rescue(db, File(root, DatabaseRescue.RESCUE_DIR), 16)
        assertTrue(outcome is RescueOutcome.Rescued, "expected Rescued, got " + outcome)
    }

    /**
     * A MAIN FILE THIS CODE CANNOT READ STAYS UNREADABLE, whatever the `-wal`
     * beside it says.
     *
     * Issue #101 set the rule and this does not reopen it: a database whose own
     * header will not parse is never acted on, because moving one aside on the
     * strength of a guess is the destructive direction. The `-wal` raises a
     * version the main file declared; it does not supply one in place of a main
     * file that declared nothing.
     *
     * The shape is reachable -- a torn in-place reinstall can leave a
     * half-written main file beside an intact log. Green before the fix, for
     * the trivial reason that nothing reads the log yet; it is here so that
     * wiring the log in cannot quietly widen what gets rescued.
     */
    @Test
    fun `an unreadable main file is not given a version by its wal`() {
        val db = fixture()
        val walBytes = File(db.path + "-wal").readBytes()

        db.writeText("this is not a database".repeat(20))
        File(db.path + "-wal").writeBytes(walBytes)
        assertNull(DatabaseRescue.storedVersion(db), "a wal supplied a version for a file that has none")
        assertEquals(RescueOutcome.NotNeeded, DatabaseRescue.rescue(db, File(root, DatabaseRescue.RESCUE_DIR), 16))
        assertTrue(db.isFile, "a file this code cannot read was moved on the strength of its wal")

        db.delete()
        File(db.path + "-wal").writeBytes(walBytes)
        assertNull(DatabaseRescue.storedVersion(db), "an absent database was given a version by an orphan wal")
        assertEquals(RescueOutcome.NotNeeded, DatabaseRescue.rescue(db, File(root, DatabaseRescue.RESCUE_DIR), 16))
    }

    /**
     * The case the fix must not disturb: no `-wal` at all, which is what a
     * cleanly closed database and a TRUNCATE/DELETE-mode device both leave.
     * The answer is the main header and stays the main header.
     */
    @Test
    fun `a database with no wal reads its own header, before and after`() {
        val db = fixture()
        File(db.path + "-wal").delete()
        assertEquals(16, DatabaseRescue.storedVersion(db))
        assertEquals(RescueOutcome.NotNeeded, DatabaseRescue.rescue(db, File(root, DatabaseRescue.RESCUE_DIR), 16))
    }
}

/**
 * The main file's own header, read here rather than through the code under
 * test, so a test that asserts "the header is stale" cannot be satisfied by the
 * very function whose reading of the header is in question.
 */
private object SqliteHeaderProbe {
    fun mainHeaderVersion(file: File): Int {
        val b = file.inputStream().use { it.readNBytes(100) }
        var v = 0
        for (i in 60 until 64) v = (v shl 8) or (b[i].toInt() and 0xFF)
        return v
    }
}
