package com.macrophage.barspeed.data

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
 * MigrationTestHelper, no instrumented test. The four properties above are
 * reasoned, and the design is arranged so that being wrong about any of them
 * fails loudly rather than quietly.
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

    // ---- issue #113: real SQLite bytes, a main file stale under its -wal -----

    /*
     * The same two fixtures SqliteWalTest reads, copied rather than shared
     * because test sources do not cross modules. Made by SQLite 3.53.1, not
     * by hand and not from field data: a database at user_version 10,
     * checkpointed, then set to 21 and copied with the connection still open
     * so nothing checkpointed. SQLite opening the copy reports 21; bytes 60-63
     * of its main file read 10. SqliteWalTest's KDoc has the full recipe.
     */
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

    /** The first 100 bytes of the main file copied beside realWal: user_version 10. */
    private val realMainHeader: ByteArray = Base64.getDecoder().decode(
        "U1FMaXRlIGZvcm1hdCAzAAIAAgIAQCAgAAAAAgAAAAIAAAAAAAAAAAAAAAEAAAAEAAAAAAAAAAAAAAABAAAACgAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAACAC6VyQ==",
    )

    /** The real stale main file -- its 100-byte header, padded -- with [wal] beside it. */
    private fun staleUnderWal(wal: ByteArray = realWal, name: String = "accelerometer_lifting.db"): File {
        val file = File(root, name).apply { writeBytes(realMainHeader + ByteArray(924)) }
        File(file.path + "-wal").writeBytes(wal)
        return file
    }

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
     * TEETH ON BOTH PLATFORMS, matching the fix [RescuedDatabaseStoreTest]'s
     * equivalent test carries: `setReadable(false)` alone has no effect on
     * Windows, where `canRead()` stays true and the open still succeeds, so a
     * version of this test that only tried that mechanism and returned early
     * when it did not bite reported PASS while asserting nothing on the only
     * machine this was written on. [withUnreadable] adds an exclusive
     * [RandomAccessFile] region lock, which Windows enforces on the read
     * itself, and asserts loudly when neither mechanism makes the file
     * unreadable rather than returning quietly.
     */
    @Test
    fun `a file that cannot be opened says nothing rather than throwing`() {
        val file = db(10, name = "unreadable.db")

        val ran =
            withUnreadable(file) {
                assertNull(DatabaseRescue.storedVersion(file), "an unopenable file produced a version")
                assertEquals(RescueOutcome.NotNeeded, DatabaseRescue.rescue(file, rescueRoot(), 9))
                assertTrue(file.isFile, "the database was disturbed by a read that failed")
            }

        assertNotNull(ran, "no mechanism on this platform makes a file unreadable, so this property went unchecked")
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

    // ---- issue #111: what a real rescue reads back as ----------------------

    /*
     * THE COUPLING NOTHING ELSE PINS. RescuedDatabaseStore describes
     * directories this class writes, and every fixture in
     * RescuedDatabaseStoreTest builds one by hand -- so the reader is checked
     * against directories a test invented, never against one
     * DatabaseRescue.rescue actually produced.
     * Drift in the move order, in the mkdirs-before-move step, or in the
     * db-<ms> name would move only the writing side and leave every one of
     * those tests green.
     *
     * The three below drive rescue() for real, one per RescueCompleteness
     * state, and read the result back through the store. Nothing here
     * constructs a directory name: rescue chooses it, and the store has to
     * parse back what it chose.
     */

    @Test
    fun `a real rescue that moves everything reads back as complete`() {
        val file = db(10)
        File(file.path + "-wal").writeText("wal")
        assertIsRescued(DatabaseRescue.rescue(file, rescueRoot(), 9))

        val found = RescuedDatabaseStore(rescueRoot()).rescued().single()
        assertEquals(RescueCompleteness.COMPLETE, found.completeness)
        assertEquals(listOf("accelerometer_lifting.db", "accelerometer_lifting.db-wal"), found.files)
        assertEquals(1_003L, found.totalBytes, "the store did not size what the rescue moved")
        assertNotNull(found.rescuedAtMs, "the store could not date a directory the rescue itself named")
    }

    @Test
    fun `a real rescue whose main file will not move reads back as a fragment`() {
        val file = db(10)
        val wal = File(file.path + "-wal").apply { writeText("wal") }
        val failMain: (File, File) -> Boolean = { source, dest -> source.name != file.name && source.renameTo(dest) }
        val outcome = DatabaseRescue.rescue(file, rescueRoot(), 9, move = failMain)
        assertTrue(outcome is RescueOutcome.Failed, "expected Failed, got " + outcome)

        val found = RescuedDatabaseStore(rescueRoot()).rescued().single()
        assertEquals(RescueCompleteness.FRAGMENT, found.completeness)
        assertEquals(listOf("accelerometer_lifting.db-wal"), found.files)
        assertNotNull(found.rescuedAtMs, "the store could not date a directory the rescue itself named")
        assertTrue(!wal.exists(), "the sidecar the rescue reported moving is still in place")
    }

    @Test
    fun `a real rescue that moves nothing reads back as an empty one`() {
        val file = db(10)
        val outcome = DatabaseRescue.rescue(file, rescueRoot(), 9, move = { _, _ -> false })
        assertTrue(outcome is RescueOutcome.Failed, "expected Failed, got " + outcome)

        val found = RescuedDatabaseStore(rescueRoot()).rescued().single()
        assertEquals(RescueCompleteness.NOTHING, found.completeness)
        assertEquals(emptyList(), found.files)
        assertEquals(0L, found.totalBytes)
        assertNotNull(found.rescuedAtMs, "the store could not date a directory the rescue itself named")
        assertTrue(file.isFile, "the main database moved even though every move was made to fail")
    }

    // ---- issue #113: what the -wal may and may not change -----------------

    /*
     * GUARDS, NOT DIFFERENTIALS: each passes before and after #113's fix.
     * They pin the direction the fix must not move in -- a log SQLite would
     * not use leaves the main file's answer standing, which is the answer
     * every launch got before the log was read at all.
     */

    @Test
    fun `a torn wal leaves the main file's version standing`() {
        val file = staleUnderWal(realWal.copyOf(realWal.size - 1))
        assertEquals(10, DatabaseRescue.storedVersion(file))
        assertEquals(RescueOutcome.NotNeeded, DatabaseRescue.rescue(file, rescueRoot(), 20))
        assertTrue(file.isFile, "a database was moved on the strength of a log SQLite would not read")
    }

    @Test
    fun `a wal that is not a log leaves the main file's version standing`() {
        val file = staleUnderWal("this is not a write-ahead log".toByteArray())
        assertEquals(10, DatabaseRescue.storedVersion(file))
        assertEquals(RescueOutcome.NotNeeded, DatabaseRescue.rescue(file, rescueRoot(), 20))
    }

    /** A directory where the -wal should be: not a file, so not a log. No exception escapes. */
    @Test
    fun `a wal path that is not a file leaves the main file's version standing`() {
        val file = File(root, "accelerometer_lifting.db").apply { writeBytes(realMainHeader + ByteArray(924)) }
        File(file.path + "-wal").mkdirs()
        assertEquals(10, DatabaseRescue.storedVersion(file))
        assertEquals(RescueOutcome.NotNeeded, DatabaseRescue.rescue(file, rescueRoot(), 20))
    }

    /** At the version the log names, the log is the current database, not a newer one. */
    @Test
    fun `a database whose wal holds the compiled version is not a rescue`() {
        val file = staleUnderWal()
        assertEquals(RescueOutcome.NotNeeded, DatabaseRescue.rescue(file, rescueRoot(), 21))
        assertTrue(file.isFile && File(file.path + "-wal").isFile, "a current database was moved")
    }

    // ---- issue #113: the differential -------------------------------------

    /**
     * THE MISSED RESCUE. The main file says 10, the log SQLite will read
     * says 21, and this build knows 20. Room, opening it, would see 21 and
     * throw on every launch; the rescue, reading only the main file, saw 10
     * and did nothing.
     */
    @Test
    fun `storedVersion reads the version SQLite would, through an un-checkpointed wal`() {
        assertEquals(21, DatabaseRescue.storedVersion(staleUnderWal()))
    }

    @Test
    fun `a database newer only in its wal is rescued, wal and all`() {
        val file = staleUnderWal()
        val rescued = assertIsRescued(DatabaseRescue.rescue(file, rescueRoot(), 20))
        assertTrue(!file.exists() && !File(file.path + "-wal").exists(), "part of the newer database was left in place")
        val movedLog = File(rescued, file.name + "-wal").readBytes()
        assertEquals(realWal.toList(), movedLog.toList(), "the log was not moved intact")
    }

    // ---- round 1 review of #113 (A1, B1): a rescue decided by the -wal, cut off --------

    /**
     * THE NEAR NEIGHBOUR #113'S FIX LEFT. The newer version is only in the
     * -wal, and the -wal moves first. When the main file's move then fails
     * -- or the process dies between the two renames -- the version leaves
     * with the log: the next launch reads the stale main header (10),
     * decides Open, and Room migrates a pre-checkpoint snapshot while the
     * committed frames sit in a fragment card. Before #113 both launches
     * read the same main header, so the retry fired. The rescue must finish
     * what it started, into the directory it started, without deciding
     * again on a version the first move took away.
     */
    @Test
    fun `a rescue cut off after the wal moved is finished into the same directory`() {
        val file = staleUnderWal()
        val failMain: (File, File) -> Boolean = { source, dest -> source.name != file.name && source.renameTo(dest) }
        val first = DatabaseRescue.rescue(file, rescueRoot(), 20, move = failMain)
        assertTrue(first is RescueOutcome.Failed, "expected Failed, got " + first)
        val walDir = rescueRoot().listFiles().orEmpty().single { it.isDirectory }
        assertTrue(File(walDir, file.name + "-wal").isFile, "the log did not move first")

        val dir = assertIsRescued(DatabaseRescue.rescue(file, rescueRoot(), 20))
        assertEquals(walDir, dir, "the main file was not put with its own log")
        assertTrue(File(dir, file.name).isFile, "the main file was not rescued")
        assertTrue(!file.exists(), "the stale main file was left for Room to open")
        assertEquals(
            listOf(dir),
            rescueRoot().listFiles().orEmpty().filter { it.isDirectory },
            "the finish split the database across a second directory",
        )
    }

    /**
     * Every file had moved and only the bookkeeping was left: the next
     * launch must not take the database now at the path -- Room's fresh one,
     * or anything else -- for the rest of the old rescue.
     */
    @Test
    fun `a rescue finished but for its marker moves nothing now in place`() {
        val done = File(rescueRoot(), "db-1000").apply { mkdirs() }
        File(done, "accelerometer_lifting.db").writeText("the rescued database")
        File(rescueRoot(), DatabaseRescue.PENDING).writeText("db-1000")
        val file = db(20)
        val bytes = file.readBytes().toList()

        assertEquals(RescueOutcome.NotNeeded, DatabaseRescue.rescue(file, rescueRoot(), 20))
        assertEquals(bytes, file.readBytes().toList(), "the database in place was moved or changed")
        assertEquals(listOf("accelerometer_lifting.db"), done.list().orEmpty().toList())
        assertTrue(!File(rescueRoot(), DatabaseRescue.PENDING).exists(), "the finished rescue's marker was left")
    }

    /**
     * GUARD, added because round 3's review found it unpinned (A's N2): a
     * marker left behind by a finished rescue, or naming a directory that
     * does not exist, must be removed before the launch decides afresh. If
     * it will not go, that launch is Failed and does not open, rather than
     * opening with a marker that a later launch could take for an
     * unfinished rescue of whatever database is then at the path.
     */
    @Test
    fun `a stale marker that will not delete stops the launch`() {
        for (finished in listOf(true, false)) {
            cleanUp()
            val done = File(rescueRoot(), "db-1000").apply { mkdirs() }
            if (finished) File(done, "accelerometer_lifting.db").writeText("the rescued database")
            if (!finished) done.delete()
            File(rescueRoot(), DatabaseRescue.PENDING).writeText("db-1000")
            val file = db(20)
            val bytes = file.readBytes().toList()

            val outcome = DatabaseRescue.rescue(file, rescueRoot(), 20, deleteMarker = { false })
            assertTrue(outcome is RescueOutcome.Failed, "finished=$finished: expected Failed, got $outcome")
            assertEquals(bytes, file.readBytes().toList(), "finished=$finished: the database in place moved")
            assertTrue(File(rescueRoot(), DatabaseRescue.PENDING).isFile, "finished=$finished: marker gone")
        }
    }

    /**
     * GUARD, added with the one above (round 3 review, A's N1 and N2): the
     * other half of the same choice. At the end of the rescue that wrote
     * it, a marker that will not delete does not undo the rescue: every
     * file has moved, so the launch is Rescued, and it is the NEXT launch
     * that finds the marker and retries removing it.
     */
    @Test
    fun `a complete rescue whose marker will not delete is still Rescued`() {
        val file = db(10)
        val dir = assertIsRescued(DatabaseRescue.rescue(file, rescueRoot(), 9, deleteMarker = { false }))
        assertTrue(File(dir, file.name).isFile, "the database did not reach the rescue directory")
        assertTrue(!file.exists(), "the database is still at the path")
        assertEquals(dir.name, File(rescueRoot(), DatabaseRescue.PENDING).readText().trim())
    }

    /**
     * GUARD, added after the fix because a mutation survived it: POSIX
     * rename replaces its destination silently, so a finish that moved a
     * file into a name already taken in the rescue directory would destroy
     * whatever held it. Nothing in a normal rescue puts one there; this
     * plants one.
     */
    @Test
    fun `a finish never moves a file over one already in the rescue directory`() {
        val file = staleUnderWal()
        val target = File(rescueRoot(), "db-1000").apply { mkdirs() }
        File(target, file.name + "-wal").writeText("a log already here")
        File(rescueRoot(), DatabaseRescue.PENDING).writeText("db-1000")

        val outcome = DatabaseRescue.rescue(file, rescueRoot(), 20)
        assertTrue(outcome is RescueOutcome.Failed, "expected Failed, got " + outcome)
        val inTheWay = File(target, file.name + "-wal").readText()
        assertEquals("a log already here", inTheWay, "a file in the way was replaced")
        assertEquals(realWal.toList(), File(file.path + "-wal").readBytes().toList(), "the log at the path was lost")
        assertTrue(file.isFile, "the main file moved without its log")
    }

    /** A marker whose directory is not there names a rescue that moved nothing: decide afresh. */
    @Test
    fun `a marker naming no directory is dropped and the launch decides afresh`() {
        rescueRoot().mkdirs()
        File(rescueRoot(), DatabaseRescue.PENDING).writeText("db-999")
        val file = db(10)
        val dir = assertIsRescued(DatabaseRescue.rescue(file, rescueRoot(), 9))
        assertTrue(dir.name != "db-999", "a rescue went into a directory nothing created")
        assertTrue(File(dir, file.name).isFile)
        assertTrue(!File(rescueRoot(), DatabaseRescue.PENDING).exists(), "the stale marker was left")
    }

    /**
     * GUARD, added because round 2's review found the prefix check unpinned:
     * a marker that is empty -- what power loss can leave of a temp file
     * renamed before its bytes reached the disk -- names [rescueRoot] itself,
     * which is a directory with no database in it. Without the check the
     * launch would take that for an unfinished rescue and move the database
     * in place into the rescue root, where no card lists it.
     */
    @Test
    fun `a marker naming no rescue directory moves nothing and is dropped`() {
        for (text in listOf("", "\n", "keep")) {
            cleanUp()
            File(rescueRoot(), "keep").mkdirs()
            File(rescueRoot(), DatabaseRescue.PENDING).writeText(text)
            val file = db(20)
            val bytes = file.readBytes().toList()

            assertEquals(RescueOutcome.NotNeeded, DatabaseRescue.rescue(file, rescueRoot(), 20), "marker '$text'")
            assertEquals(bytes, file.readBytes().toList(), "marker '$text': the database in place was moved")
            assertTrue(!File(rescueRoot(), file.name).exists(), "marker '$text': the database went into the root")
            assertTrue(!File(rescueRoot(), "keep/${file.name}").exists(), "marker '$text': it went into 'keep'")
            assertTrue(!File(rescueRoot(), DatabaseRescue.PENDING).exists(), "marker '$text' was left")
        }
    }

    // ---- data-layer F2: a corrupt database is set aside, never deleted ------

    /**
     * SQLite's own answer to corruption deletes the files. [DatabaseRescue.setAside]
     * moves them, at the CURRENT version -- no version decides anything here --
     * into a `db-<ms>` directory the rescued card lists as complete.
     * UX review 2026-11-06, data-layer F2.
     */
    @Test
    fun `a corrupt database at the compiled version is set aside whole, not deleted`() {
        val file = db(20)
        val bytes = file.readBytes().toList()
        File(file.path + "-wal").writeText("wal")
        File(file.path + "-shm").writeText("shm")

        val dir = assertIsRescued(DatabaseRescue.setAside(file, rescueRoot(), now = { 7_000L }))

        assertEquals(File(rescueRoot(), "db-7000"), dir)
        assertEquals(bytes, File(dir, file.name).readBytes().toList(), "the database's bytes changed")
        assertEquals("wal", File(dir, file.name + "-wal").readText())
        assertEquals("shm", File(dir, file.name + "-shm").readText())
        assertTrue(!file.exists() && !File(file.path + "-wal").exists(), "a file was left at the path")
        assertTrue(!File(rescueRoot(), DatabaseRescue.PENDING).exists(), "the marker was left")
        val listed = RescuedDatabaseStore(rescueRoot()).rescued().single()
        assertEquals(RescueCompleteness.COMPLETE, listed.completeness)
        assertEquals(7_000L, listed.rescuedAtMs)
    }

    /** A name already taken steps on rather than merging two databases into one directory. */
    @Test
    fun `a set-aside never moves into a rescue that is already there`() {
        val taken = File(rescueRoot(), "db-7000").apply { mkdirs() }
        File(taken, "accelerometer_lifting.db").writeText("an earlier rescue")
        val file = db(20)

        val dir = assertIsRescued(DatabaseRescue.setAside(file, rescueRoot(), now = { 7_000L }))

        assertEquals(File(rescueRoot(), "db-7001"), dir)
        assertEquals("an earlier rescue", File(taken, "accelerometer_lifting.db").readText())
    }

    /**
     * A set-aside cut off after the log moved is finished by the next
     * launch's ordinary rescue, into the same directory, although the main
     * file left behind is at the compiled version and would otherwise open.
     */
    @Test
    fun `a set-aside cut off partway is finished by the next launch, not opened`() {
        val file = db(20)
        File(file.path + "-wal").writeText("wal")
        val failMain: (File, File) -> Boolean = { source, dest -> source.name != file.name && source.renameTo(dest) }

        val first = DatabaseRescue.setAside(file, rescueRoot(), move = failMain, now = { 7_000L })
        assertTrue(first is RescueOutcome.Failed, "expected Failed, got " + first)
        assertTrue(file.exists(), "the main file moved although its move failed")

        val dir = assertIsRescued(DatabaseRescue.rescue(file, rescueRoot(), 20))
        assertEquals(File(rescueRoot(), "db-7000"), dir)
        assertTrue(File(dir, file.name).isFile && File(dir, file.name + "-wal").isFile, "the set was split")
        assertTrue(!file.exists(), "the half-moved database was left for Room to open")
    }

    /** A rescue already under way is the launch's to finish; a set-aside moves nothing over it. */
    @Test
    fun `a set-aside with a rescue under way moves nothing`() {
        File(rescueRoot(), "db-1000").mkdirs()
        File(rescueRoot(), DatabaseRescue.PENDING).writeText("db-1000")
        val file = db(20)

        val outcome = DatabaseRescue.setAside(file, rescueRoot(), now = { 7_000L })

        assertTrue(outcome is RescueOutcome.Failed, "expected Failed, got " + outcome)
        assertTrue(file.exists(), "the database moved")
        assertTrue(!File(rescueRoot(), "db-7000").exists(), "a second rescue directory was started")
    }

    private fun opens(file: File): Boolean = runCatching { file.inputStream().use { it.read() } }.isSuccess

    /**
     * Tries `setReadable(false)` first, then an exclusive [RandomAccessFile]
     * region lock, which Windows enforces on the read itself where the
     * permission flag alone is ignored. Runs [block] only once a mechanism
     * actually makes [file] unreadable, and returns null when neither does --
     * duplicated from [RescuedDatabaseStoreTest], the file this test's own
     * shape was copied from.
     */
    private fun <T> withUnreadable(file: File, block: () -> T): T? {
        file.setReadable(false)
        if (!opens(file)) {
            try {
                return block()
            } finally {
                file.setReadable(true)
            }
        }
        file.setReadable(true)
        RandomAccessFile(file, "rw").use { handle ->
            handle.channel.lock().use {
                if (!opens(file)) return block()
            }
        }
        return null
    }
}
