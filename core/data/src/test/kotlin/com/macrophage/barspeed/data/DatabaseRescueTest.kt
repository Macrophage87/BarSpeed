package com.macrophage.barspeed.data

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [DatabaseRescue] against real files in a real directory, which is as close to
 * the device as anything in this repository gets.
 *
 * THE GATE THIS FILE EXISTS FOR: nothing on the normal launch path may be
 * changed by this code. Its first execution happens on the lifter's phone
 * against the only copy of the corpus, and unlike a wrong number a defect here
 * stops the app opening at all. So every doubt, every failure, and the case
 * this release actually meets all resolve to NotNeeded, and each of those is
 * asserted rather than argued.
 *
 * What this cannot show: that Room reads user_version as the file's stored
 * version, that deleting the destructive fallback makes a downgrade throw, that
 * Room creates a fresh database when the file is absent, or that WAL is on at
 * all. Nothing here executes Room's open path -- no room-testing dependency, no
 * MigrationTestHelper, no committed baseline, no instrumented test. Those four
 * are reasoned, and the design is arranged so that being wrong about any of
 * them fails loudly rather than quietly.
 */
class DatabaseRescueTest {
    private val root: File = Files.createTempDirectory("db-rescue").toFile()

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun db(version: Int, name: String = "accelerometer_lifting.db"): File {
        val bytes = ByteArray(100)
        // 15 chars; byte 15 stays 0 from the zero-filled array, which is
        // the terminating NUL the real magic ends with.
        "SQLite format 3".toByteArray(Charsets.US_ASCII).copyInto(bytes)
        bytes[60] = (version ushr 24).toByte()
        bytes[61] = (version ushr 16).toByte()
        bytes[62] = (version ushr 8).toByte()
        bytes[63] = version.toByte()
        return File(root, name).apply { writeBytes(bytes + ByteArray(900)) }
    }

    private fun rescueRoot() = File(root, DatabaseRescue.RESCUE_DIR)

    private fun assertIsRescued(outcome: RescueOutcome): File {
        assertTrue(outcome is RescueOutcome.Rescued, "expected Rescued, got " + outcome)
        return outcome.directory
    }

    // ---- the four gate properties ------------------------------------------

    /** A first install. The file is absent and nothing is touched. */
    @Test
    fun `a missing database is not a rescue`() {
        val outcome = DatabaseRescue.rescue(File(root, "nothing.db"), rescueRoot(), 9)
        assertEquals(RescueOutcome.NotNeeded, outcome)
        assertTrue(!rescueRoot().exists(), "a rescue directory was created with nothing to put in it")
    }

    /**
     * THE CASE THIS RELEASE ACTUALLY MEETS. The database version does not move
     * in this release, so on every ordinary launch -- and on the rollback a
     * lifter might consider from it -- decide sees 9 against 9 and this is the
     * branch taken. It is the property that makes shipping the rest of this
     * safe, so it is a test rather than an observation.
     */
    @Test
    fun `a database at the compiled version is not a rescue`() {
        val file = db(9)
        val before = file.readBytes()
        assertEquals(RescueOutcome.NotNeeded, DatabaseRescue.rescue(file, rescueRoot(), 9))
        assertTrue(file.isFile, "the database moved when it should not have")
        assertEquals(before.toList(), file.readBytes().toList(), "the database was altered")
    }

    /** An older file is an upgrade and belongs to the migrations. */
    @Test
    fun `an older database is not a rescue`() {
        val file = db(7)
        assertEquals(RescueOutcome.NotNeeded, DatabaseRescue.rescue(file, rescueRoot(), 9))
        assertTrue(file.isFile)
    }

    /**
     * Anything unreadable resolves to NotNeeded and NO EXCEPTION ESCAPES: a
     * directory where a file is expected, a file too short to hold a header,
     * and a file that is not a database at all. Each of these would stop the
     * app launching if it threw.
     */
    @Test
    fun `nothing unreadable throws, and nothing unreadable is a rescue`() {
        val dir = File(root, "a-directory.db").apply { mkdirs() }
        assertNull(DatabaseRescue.storedVersion(dir))
        assertEquals(RescueOutcome.NotNeeded, DatabaseRescue.rescue(dir, rescueRoot(), 9))

        val short = File(root, "short.db").apply { writeBytes(ByteArray(12)) }
        assertNull(DatabaseRescue.storedVersion(short))
        assertEquals(RescueOutcome.NotNeeded, DatabaseRescue.rescue(short, rescueRoot(), 9))

        val notADatabase = File(root, "notes.db").apply { writeText("this is not a database".repeat(20)) }
        assertNull(DatabaseRescue.storedVersion(notADatabase))
        assertEquals(RescueOutcome.NotNeeded, DatabaseRescue.rescue(notADatabase, rescueRoot(), 9))
        assertTrue(notADatabase.isFile, "a file this code cannot read was moved on a guess")
    }

    /**
     * A file that exists, is a file, and cannot be OPENED -- the one shape that
     * reaches the exception guard rather than an early return.
     *
     * The other unreadable cases return null before any stream is opened, so
     * they leave the guard unexercised: verified by mutation, replacing
     * getOrNull with getOrThrow kills nothing without this test. That is the
     * whole reason it exists.
     *
     * LIMIT, MEASURED RATHER THAN ASSUMED. It has teeth only where the platform
     * refuses the open after the permission change, and it establishes that by
     * TRYING rather than by asking -- canRead() was the precondition first and
     * answers a different question. On the Windows box this was written on,
     * probed directly: setReadable returns false, canRead stays true, and the
     * open still succeeds, so this test asserts nothing there and the mutation
     * that should kill it (returning the read's exception instead of null)
     * survives locally. CI is ubuntu-latest, where the permission is honoured.
     * Treat the mutation evidence for this one property as CI's, not the
     * author's.
     */
    @Test
    fun `a file that cannot be opened says nothing rather than throwing`() {
        val file = db(10, name = "unreadable.db")
        file.setReadable(false)
        // Whether the OPEN fails, which is the only thing that reaches the
        // guard. canRead() was the precondition here first and it answers a
        // different question -- it returned true on a file that would not open,
        // so the test skipped on the platform it was written on.
        val stillOpens = runCatching { file.inputStream().use { it.read() } }.isSuccess
        if (stillOpens) return
        assertNull(DatabaseRescue.storedVersion(file), "an unopenable file produced a version")
        assertEquals(RescueOutcome.NotNeeded, DatabaseRescue.rescue(file, rescueRoot(), 9))
        file.setReadable(true)
        assertTrue(file.isFile, "the database was disturbed by a read that failed")
    }

    // ---- the rescue itself -------------------------------------------------

    @Test
    fun `a newer database is moved aside and the original name is free`() {
        val file = db(10)
        val bytes = file.readBytes()
        val rescued = assertIsRescued(DatabaseRescue.rescue(file, rescueRoot(), 9))
        assertTrue(!file.exists(), "the database is still in place, so Room would open it destructively")
        val moved = File(rescued, "accelerometer_lifting.db")
        assertTrue(moved.isFile, "the database is not in the rescue directory either")
        assertEquals(bytes.toList(), moved.readBytes().toList(), "the rescued bytes are not the original bytes")
    }

    /**
     * All three files move. Leaving a -wal behind would strand committed
     * transactions AND leave it beside the fresh database Room is about to
     * create under the same name, which is the one state the move order exists
     * to make unreachable.
     */
    @Test
    fun `the wal and shm move with the database`() {
        val file = db(10)
        val wal = File(file.path + "-wal").apply { writeText("wal") }
        val shm = File(file.path + "-shm").apply { writeText("shm") }
        val rescued = assertIsRescued(DatabaseRescue.rescue(file, rescueRoot(), 9))
        assertEquals(
            listOf("accelerometer_lifting.db", "accelerometer_lifting.db-shm", "accelerometer_lifting.db-wal"),
            rescued.listFiles().orEmpty().map { it.name }.sorted(),
        )
        assertTrue(!wal.exists() && !shm.exists(), "a sidecar was left beside the name Room will recreate")
    }

    /**
     * THE ORDERING, pinned as a value because it is the whole safety argument
     * and because no assertion over the finished directory can see it.
     *
     * Sidecars first, main file last. Reversed, a crash mid-rescue would leave
     * orphaned sidecars beside the name Room is about to recreate -- a fresh
     * database next to a stale -wal, the one state that could corrupt rather
     * than merely lose. With this order a crash leaves the main file in place
     * at its newer version and the next launch simply retries.
     */
    @Test
    fun `the sidecars move before the database, never after`() {
        val file = File(root, "accelerometer_lifting.db")
        assertEquals(
            listOf(
                "accelerometer_lifting.db-wal",
                "accelerometer_lifting.db-shm",
                "accelerometer_lifting.db-journal",
                "accelerometer_lifting.db",
            ),
            DatabaseRescue.moveOrder(file).map { it.name },
        )
        assertEquals(file.path, DatabaseRescue.moveOrder(file).last().path, "the database must move last")
    }

    /**
     * The rollback journal moves too, and before the database.
     *
     * Room asks for AUTOMATIC journal mode: WAL on most devices, TRUNCATE on a
     * low-RAM one, where the sidecar is -journal rather than -wal/-shm. Carried
     * only the WAL pair, a rescue on such a device would leave a stale rollback
     * journal beside the name Room is about to recreate -- the exact state the
     * ordering exists to make unreachable, reintroduced by a missing filename.
     */
    @Test
    fun `a rollback journal moves with the database, as the wal pair does`() {
        val file = db(10)
        val journal = File(file.path + "-journal").apply { writeText("journal") }
        val rescued = assertIsRescued(DatabaseRescue.rescue(file, rescueRoot(), 9))
        assertEquals(
            listOf("accelerometer_lifting.db", "accelerometer_lifting.db-journal"),
            rescued.listFiles().orEmpty().map { it.name }.sorted(),
        )
        assertTrue(!journal.exists(), "a rollback journal was left beside the name Room will recreate")
    }

    /** Sidecars are optional; a database with none rescues alone. */
    @Test
    fun `a database with no sidecars rescues alone`() {
        val rescued = assertIsRescued(DatabaseRescue.rescue(db(10), rescueRoot(), 9))
        assertEquals(listOf("accelerometer_lifting.db"), rescued.listFiles().orEmpty().map { it.name })
    }

    /**
     * A second downgrade does not overwrite the first rescue. Two rollbacks
     * leave two corpora, which is the growth this design accepts and hands to
     * the lifter to bound.
     */
    @Test
    fun `a second rescue does not overwrite the first`() {
        val first = assertIsRescued(DatabaseRescue.rescue(db(10), rescueRoot(), 9))
        Thread.sleep(2)
        val second = assertIsRescued(DatabaseRescue.rescue(db(11), rescueRoot(), 9))
        assertTrue(first.path != second.path, "the second rescue reused the first directory")
        assertTrue(File(first, "accelerometer_lifting.db").isFile, "the first rescue was overwritten")
        assertEquals(2, rescueRoot().listFiles().orEmpty().size)
    }

    /**
     * When the move cannot happen the original is left exactly where it is and
     * the outcome says so. Nothing destructive follows: the builder carries no
     * destructive fallback, so Room meets the newer file and throws. A crash
     * with the data intact beats a clean start with it gone.
     */
    @Test
    fun `a rescue that cannot proceed leaves the database untouched`() {
        val file = db(10)
        // A regular FILE where the rescue directory must go, so mkdirs fails.
        rescueRoot().writeText("in the way")
        val outcome = DatabaseRescue.rescue(file, rescueRoot(), 9)
        assertTrue(outcome is RescueOutcome.Failed, "expected Failed, got " + outcome)
        assertTrue(file.isFile, "the database was disturbed by a rescue that could not complete")
        assertEquals(10, DatabaseRescue.storedVersion(file), "the database was altered")
    }

    // ---- issue #111: a failed move must not be reported as a rescue -------

    /**
     * THE GAP #111'S GATE FOUND. Every `Failed` path above is `mkdirs`
     * failing; nothing before this test made a `renameTo` call itself fail,
     * because POSIX `renameTo` overwrites its destination silently -- no
     * fixture built from real files could provoke it. [move] is the seam
     * that makes the state producible: reporting `Rescued` when a move
     * actually failed was killed by no test until this one.
     */
    @Test
    fun `a move that reports failure is reported as Failed, not as Rescued`() {
        val file = db(10)
        val outcome = DatabaseRescue.rescue(file, rescueRoot(), 9, move = { _, _ -> false })
        assertTrue(outcome is RescueOutcome.Failed, "expected Failed, got " + outcome)
    }

    /**
     * THE SAFETY-ORDERING PROPERTY THE KDOC ARGUES FOR, DISCHARGED RATHER
     * THAN TRUSTED BY READING. Sidecars move first and the main file last so
     * that a crash mid-rescue leaves the main file in place at its newer
     * version; this simulates exactly that failure -- the wal's move fails
     * -- and checks the property the ordering exists to guarantee: the main
     * database file was never touched, still sitting at its original path
     * with its original bytes.
     */
    @Test
    fun `when a sidecar's move fails, the main file is left in place`() {
        val file = db(10)
        val originalBytes = file.readBytes()
        val wal = File(file.path + "-wal").apply { writeText("wal") }
        val failWal: (File, File) -> Boolean = { source, dest -> source.name != wal.name && source.renameTo(dest) }
        val outcome = DatabaseRescue.rescue(file, rescueRoot(), 9, move = failWal)
        assertTrue(outcome is RescueOutcome.Failed, "expected Failed, got " + outcome)
        assertTrue(file.isFile, "the main database moved even though a sidecar's move failed")
        assertEquals(originalBytes.toList(), file.readBytes().toList(), "the main database's bytes changed")
    }

    /**
     * The production default is the real [File.renameTo], not a stub left
     * behind by the seam above -- checked directly rather than inferred from
     * every other test in this file happening to omit the parameter.
     */
    @Test
    fun `the move parameter defaults to a real file rename`() {
        val file = db(10)
        val rescued = assertIsRescued(DatabaseRescue.rescue(file, rescueRoot(), 9))
        assertTrue(
            File(rescued, "accelerometer_lifting.db").isFile,
            "the default move did not actually rename the file",
        )
        assertTrue(!file.exists(), "the default move left the original in place")
    }
}
