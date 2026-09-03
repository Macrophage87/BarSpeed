package com.macrophage.barspeed.data

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
     * THE DEFECT, CHARACTERIZED. Issue #113: the rescue reads the main file's
     * header alone, so it reports 16 for a database SQLite reads as 17.
     *
     * This assertion is expected to be INVERTED by the fix. It is here so the
     * wrong answer is a recorded fact with a fixture behind it rather than a
     * paragraph in an issue, and so that the change of behaviour is visible as
     * a diff rather than as a new test appearing beside an old one.
     */
    @Test
    fun `today the stored version ignores the wal and reads the stale header`() {
        assertEquals(16, DatabaseRescue.storedVersion(fixture()))
    }

    /**
     * THE CONSEQUENCE, CHARACTERIZED. A build compiled at 16 meeting a database
     * that is really at 17 takes the Open branch, so Room opens it, reads 17
     * through the WAL, finds it newer, and -- with no destructive fallback in
     * the chain -- throws. The corpus survives and the app does not start.
     *
     * Expected to be inverted by the fix.
     */
    @Test
    fun `today a database that is really newer is not rescued`() {
        val db = fixture()
        assertEquals(RescueOutcome.NotNeeded, DatabaseRescue.rescue(db, File(root, DatabaseRescue.RESCUE_DIR), 16))
        assertTrue(db.isFile, "the database moved on a build that cannot detect the downgrade at all")
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
