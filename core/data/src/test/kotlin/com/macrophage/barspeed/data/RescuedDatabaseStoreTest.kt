package com.macrophage.barspeed.data

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.zip.Deflater
import java.util.zip.ZipFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [RescuedDatabaseStore] against real files in a real directory. Issue #111.
 *
 * NOTHING HERE OPENS A RESCUED FILE AS A DATABASE. That is the constraint this
 * class exists to hold, and this test file holds it too: every fixture is
 * bytes this test wrote itself, never anything decoded through SQLite, Room,
 * or [DatabaseRescue.storedVersion]. [zipTo] is the one function that reads a
 * rescued file's bytes at all, and only to copy them through unread -- proven
 * by round-tripping arbitrary bytes and getting the same bytes back, not by
 * inspecting them as a database.
 */
class RescuedDatabaseStoreTest {
    private val root: File = Files.createTempDirectory("rescued-store").toFile()
    private val store = RescuedDatabaseStore(root)

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun rescueDir(ms: Long): File = File(root, "${DatabaseRescue.DIR_PREFIX}$ms").apply { mkdirs() }

    @Test
    fun `an empty root has nothing rescued`() {
        assertEquals(emptyList(), store.rescued())
    }

    @Test
    fun `a directory that is not a rescue is not counted`() {
        File(root, "not-a-rescue.txt").writeText("stray file")
        assertEquals(emptyList(), store.rescued())
    }

    /**
     * The ordinary case: a complete rescue, dated and sized correctly, and
     * reporting the main database file is present.
     */
    @Test
    fun `a complete rescue reports its time, size and files`() {
        val dir = rescueDir(1_000L)
        File(dir, DATABASE_NAME).writeBytes(ByteArray(500))
        File(dir, "$DATABASE_NAME-wal").writeBytes(ByteArray(300))

        val found = store.rescued().single()
        assertEquals(1_000L, found.rescuedAtMs)
        assertEquals(listOf(DATABASE_NAME, "$DATABASE_NAME-wal"), found.files)
        assertEquals(800L, found.totalBytes)
        assertTrue(found.hasDatabaseFile, "the main database file was present but not reported as such")
        assertFalse(found.isEmpty, "a directory holding two files reported itself as empty")
        assertEquals(RescueCompleteness.COMPLETE, found.completeness)
    }

    /**
     * A rescue interrupted after a sidecar moved but before the main file
     * did -- [DatabaseRescue.rescue]'s own KDoc names this exact shape.
     * hasDatabaseFile must say so; nothing here may describe this directory
     * as a complete database.
     */
    @Test
    fun `a directory holding only a sidecar reports no database file`() {
        val dir = rescueDir(2_000L)
        File(dir, "$DATABASE_NAME-wal").writeBytes(ByteArray(300))

        val found = store.rescued().single()
        assertEquals(listOf("$DATABASE_NAME-wal"), found.files)
        assertFalse(found.hasDatabaseFile, "a directory with only a sidecar reported having the database file")
        assertFalse(found.isEmpty, "a directory holding a sidecar reported itself as empty")
        assertEquals(RescueCompleteness.FRAGMENT, found.completeness)
    }

    /**
     * The other reachable partial shape: a rescue whose only source file is
     * the main database (no un-checkpointed WAL, the common case) leaves
     * nothing behind at all if that one move fails, since
     * DatabaseRescue.rescue's loop never reaches a second file to attempt.
     * mkdirs() still succeeded, so the directory exists and is found; it
     * just holds nothing.
     */
    @Test
    fun `a directory with no files at all reports itself empty`() {
        rescueDir(3_000L)

        val found = store.rescued().single()
        assertEquals(emptyList(), found.files)
        assertEquals(0L, found.totalBytes)
        assertFalse(found.hasDatabaseFile)
        assertTrue(found.isEmpty, "a directory with no files did not report itself as empty")
        assertEquals(RescueCompleteness.NOTHING, found.completeness)
    }

    /**
     * THE SEAM THIS ISSUE'S OWN GATE ASKED FOR, and the reason it is on this
     * side of the module boundary. The card in :app makes four decisions off
     * this one split -- the title, whether to list the files, what the discard
     * dialog warns about, and whether SEND is worth offering -- and the gate
     * MEASURED that permuting those branches inside HomeScreen.kt reds
     * nothing, because :app has no test source set at all. The split is
     * computed in [RescuedDatabase.completeness] now, and this is the test
     * that makes a permutation of it cost something.
     *
     * All three states in ONE fixture, deliberately: three separate tests
     * would each pin one arm, and a mutation that swaps two arms would red
     * two of them -- which is fine -- but a mutation that collapses all three
     * to a single value would red every one and read as a broken fixture
     * rather than a broken decision. One fixture asserting all three keeps
     * the failure message naming which state was misread.
     */
    @Test
    fun `completeness names which of the three states a directory is in`() {
        rescueDir(1_000L).also { File(it, DATABASE_NAME).writeBytes(ByteArray(1)) }
        rescueDir(2_000L).also { File(it, "$DATABASE_NAME-wal").writeBytes(ByteArray(1)) }
        rescueDir(3_000L)

        val byTime = store.rescued().associateBy { it.rescuedAtMs }
        assertEquals(
            RescueCompleteness.COMPLETE,
            byTime[1_000L]?.completeness,
            "a directory holding the main database file",
        )
        assertEquals(
            RescueCompleteness.FRAGMENT,
            byTime[2_000L]?.completeness,
            "a directory holding only a sidecar",
        )
        assertEquals(
            RescueCompleteness.NOTHING,
            byTime[3_000L]?.completeness,
            "a directory holding nothing at all",
        )
    }

    /**
     * A directory name that does not parse as `db-<ms>` is not hidden -- a
     * dropped directory here is the exact invisibility issue #111 exists to
     * fix, even if this code cannot date it. Nothing but [DatabaseRescue]
     * creates directories under this root today, so this is a defensive
     * property rather than an expected shape, and it is tested as one:
     * absence of a parseable timestamp reads as null, not as the directory
     * vanishing from the list.
     */
    @Test
    fun `a directory whose name does not parse still appears, with no timestamp`() {
        val dir = File(root, "not-timestamped").apply { mkdirs() }
        File(dir, DATABASE_NAME).writeBytes(ByteArray(10))

        val found = store.rescued().single()
        assertNull(found.rescuedAtMs, "a directory whose name is not db-<ms> produced a timestamp anyway")
        assertEquals(dir, found.directory)
    }

    /**
     * A directory named `db-<digits>` where the digits are not actually a
     * timestamp -- e.g. `db-` alone -- must not crash or invent a value.
     */
    @Test
    fun `a name with the prefix but no digits reports no timestamp`() {
        val dir = File(root, "${DatabaseRescue.DIR_PREFIX}not-a-number").apply { mkdirs() }
        File(dir, DATABASE_NAME).writeBytes(ByteArray(10))

        assertNull(store.rescued().single().rescuedAtMs)
    }

    /**
     * A directory that happens to be purely numeric but never carried the
     * `db-` prefix at all must not be read as a timestamp either.
     * `removePrefix` is a no-op when the prefix does not match, so a parse
     * written as `name.removePrefix(PREFIX).toLongOrNull()` without first
     * checking `startsWith` would parse this directory's whole name as a
     * millisecond value -- this is the fixture that catches exactly that.
     */
    @Test
    fun `a purely numeric name with no db- prefix reports no timestamp`() {
        val dir = File(root, "1000").apply { mkdirs() }
        File(dir, DATABASE_NAME).writeBytes(ByteArray(10))

        assertNull(store.rescued().single().rescuedAtMs, "a name with no db- prefix was parsed as a timestamp anyway")
    }

    /**
     * THE GATE #101 ALREADY CAUGHT FOR THE SQLITE HEADER OFFSET, one commit
     * earlier in this same feature, reproduced here: every fixture above
     * builds its `db-` names from [DatabaseRescue.DIR_PREFIX] -- the SAME
     * constant [read] parses against. A change to that constant would move
     * both sides together and every test above would still pass. This test
     * hardcodes the literal `"db-"` independently, the way
     * [DatabaseRescueTest]'s own `db()` helper hardcodes byte offset 60
     * rather than referencing `SqliteHeader.USER_VERSION_OFFSET` -- pinning
     * the actual on-disk format [DatabaseRescue.rescue] writes, not merely
     * whatever the shared constant currently says.
     */
    @Test
    fun `the literal db- prefix is what a timestamp is parsed against, not only the shared constant`() {
        val dir = File(root, "db-5000").apply { mkdirs() }
        File(dir, DATABASE_NAME).writeBytes(ByteArray(1))

        assertEquals(5_000L, store.rescued().single().rescuedAtMs)
    }

    @Test
    fun `complete rescues are ordered oldest first within their own tier`() {
        rescueDir(3_000L).also { File(it, DATABASE_NAME).writeBytes(ByteArray(1)) }
        rescueDir(1_000L).also { File(it, DATABASE_NAME).writeBytes(ByteArray(1)) }
        rescueDir(2_000L).also { File(it, DATABASE_NAME).writeBytes(ByteArray(1)) }

        assertEquals(listOf(1_000L, 2_000L, 3_000L), store.rescued().map { it.rescuedAtMs })
    }

    /**
     * THE ORDERING FIX ITSELF. Sidecars move before the main file, so a
     * rescue interrupted partway is always dated EARLIER than the complete
     * rescue a retry eventually produces -- sorting on time alone would put
     * the ambiguous card above the safe one, which is the shape a wrong tap
     * is likeliest in. This fixture makes the partial rescue OLDER (1_000)
     * than the complete one (2_000) and asserts the complete one still
     * sorts first, which a pure time sort would get backwards.
     */
    @Test
    fun `a complete rescue sorts before an older partial one`() {
        rescueDir(1_000L).also { File(it, "$DATABASE_NAME-wal").writeBytes(ByteArray(1)) }
        rescueDir(2_000L).also { File(it, DATABASE_NAME).writeBytes(ByteArray(1)) }

        val order = store.rescued()
        assertEquals(listOf(2_000L, 1_000L), order.map { it.rescuedAtMs })
        assertTrue(order.first().hasDatabaseFile, "the complete rescue did not sort first")
    }

    /** A partial rescue, holding a real file, sorts before an empty one -- both incomplete, but not equally so. */
    @Test
    fun `a partial rescue sorts before an empty one`() {
        rescueDir(1_000L) // empty
        rescueDir(2_000L).also { File(it, "$DATABASE_NAME-wal").writeBytes(ByteArray(1)) } // partial

        val order = store.rescued()
        assertEquals(listOf(2_000L, 1_000L), order.map { it.rescuedAtMs })
        assertFalse(order.first().isEmpty, "the partial rescue did not sort before the empty one")
    }

    /**
     * An unparseable name sorts to the END of its own tier, not the front on
     * a `0L` fallback -- being unable to date something is not a reason to
     * make it look oldest and most disposable.
     */
    @Test
    fun `an unparseable name sorts last within its tier, not first`() {
        rescueDir(5_000L).also { File(it, DATABASE_NAME).writeBytes(ByteArray(1)) }
        File(root, "not-timestamped").apply { mkdirs() }.also { File(it, DATABASE_NAME).writeBytes(ByteArray(1)) }

        val order = store.rescued()
        assertEquals(listOf(5_000L, null), order.map { it.rescuedAtMs })
    }

    @Test
    fun `discard removes the directory and it no longer appears`() {
        val dir = rescueDir(1_000L)
        File(dir, DATABASE_NAME).writeBytes(ByteArray(10))
        val found = store.rescued().single()

        assertTrue(store.discard(found), "discard removed the directory but reported failure")

        assertTrue(!dir.exists(), "discard left the directory on disk")
        assertEquals(emptyList(), store.rescued())
    }

    /**
     * A second rescue is untouched by discarding the first -- two directories
     * in [store.rescued] must not become one operation.
     */
    @Test
    fun `discarding one rescue leaves the other`() {
        val first = rescueDir(1_000L).also { File(it, DATABASE_NAME).writeBytes(ByteArray(1)) }
        rescueDir(2_000L).also { File(it, DATABASE_NAME).writeBytes(ByteArray(1)) }
        val toDiscard = store.rescued().first { it.directory == first }

        store.discard(toDiscard)

        assertEquals(listOf(2_000L), store.rescued().map { it.rescuedAtMs })
    }

    /**
     * DISCARD REPORTS A PARTIAL DELETE RATHER THAN SWALLOWING IT.
     * `deleteRecursively` removes what it can and returns false if any single
     * entry survived; dropping that Boolean meant a discard that deleted the
     * main file and left a sidecar reported success, and the directory then
     * re-read as a [RescueCompleteness.FRAGMENT] whose card says it may hold
     * data the live database is missing -- false, after a deliberate discard,
     * and invisible to a caller that was never told.
     *
     * LIMIT, AND IT IS THE WHOLE VALUE OF THIS TEST: the mechanisms below are
     * this JVM's, not Android's. Windows blocks an unlink on an open file;
     * POSIX blocks it by taking the write bit off the containing directory.
     * Neither is how Android would produce a partial delete -- there an
     * unlink succeeds on an open file, and the reachable route is the process
     * being killed partway through the bottom-up walk, which cannot be staged
     * from here. This pins that the Boolean is returned and not that Android
     * ever makes it false.
     *
     * Asserts rather than skips when neither mechanism engages. The version
     * of the unreadable-file test this file shipped before returned early on
     * such a platform and reported as a pass while asserting nothing, which
     * is a check that cannot fail reading as coverage.
     */
    @Test
    fun `discard reports failure when something in the directory survives`() {
        val dir = rescueDir(1_000L)
        File(dir, DATABASE_NAME).writeBytes(ByteArray(10))
        val stubborn = File(dir, "$DATABASE_NAME-wal").apply { writeBytes(ByteArray(10)) }
        val found = store.rescued().single()

        val ran =
            withUndeletable(dir, stubborn) {
                assertFalse(store.discard(found), "discard reported success while a file survived")
                assertTrue(stubborn.exists(), "the fixture did not actually block the delete")
                assertTrue(dir.exists(), "the rescue directory went even though a file inside it survived")
            }

        assertNotNull(ran, "no mechanism on this platform blocks a delete, so this property went unchecked")
    }

    // ---- zipTo: the streamed archive --------------------------------------

    /**
     * Every file the directory holds round-trips through the zip byte for
     * byte -- proof this copies rather than parses. Arbitrary bytes, not a
     * real SQLite header, is the point: this function must not care what it
     * is moving.
     */
    @Test
    fun `zipTo archives every file with its bytes intact, unread`() {
        val dir = rescueDir(1_000L)
        val dbBytes = byteArrayOf(1, 2, 3, 4, 5, 0, -1, 127, -128)
        val walBytes = byteArrayOf(9, 8, 7)
        File(dir, DATABASE_NAME).writeBytes(dbBytes)
        File(dir, "$DATABASE_NAME-wal").writeBytes(walBytes)
        val found = store.rescued().single()
        val zipFile = File(root, "out.zip")

        store.zipTo(found, zipFile)

        ZipFile(zipFile).use { zip ->
            val entries = zip.entries().asSequence().map { it.name }.toSet()
            assertEquals(setOf(DATABASE_NAME, "$DATABASE_NAME-wal"), entries)
            assertEquals(dbBytes.toList(), zip.getInputStream(zip.getEntry(DATABASE_NAME)).readBytes().toList())
            assertEquals(
                walBytes.toList(),
                zip.getInputStream(zip.getEntry("$DATABASE_NAME-wal")).readBytes().toList(),
            )
        }
    }

    /** A directory holding only a sidecar still zips -- exactly what is there, no more. */
    @Test
    fun `zipTo archives a partial rescue as exactly what it has`() {
        val dir = rescueDir(1_000L)
        File(dir, "$DATABASE_NAME-wal").writeBytes(byteArrayOf(1, 2, 3))
        val found = store.rescued().single()
        val zipFile = File(root, "out.zip")

        store.zipTo(found, zipFile)

        ZipFile(zipFile).use { zip ->
            val entries = zip.entries().asSequence().map { it.name }.toSet()
            assertEquals(setOf("$DATABASE_NAME-wal"), entries)
        }
    }

    /** An empty directory still produces a valid, empty archive rather than throwing. */
    @Test
    fun `zipTo on an empty directory produces a readable empty archive`() {
        rescueDir(1_000L)
        val found = store.rescued().single()
        val zipFile = File(root, "out.zip")

        store.zipTo(found, zipFile)

        ZipFile(zipFile).use { zip -> assertEquals(0, zip.entries().asSequence().count()) }
    }

    /**
     * THE FIX FOR THIS ISSUE'S OWN WORST FINDING. `putNextEntry` writes the
     * zip entry's header before a single byte of the file is read, so an
     * earlier version that caught a read failure per file and moved on --
     * matching SetJournalStore.zip's own shape -- left a STRUCTURALLY VALID
     * zip whose entry for the unreadable file was truncated or zero bytes:
     * an archive that opens cleanly and looks complete while the lifter's
     * actual backup is gone. Now the whole call throws, and the partial
     * destination is deleted rather than left for a caller to share by
     * mistake.
     *
     * TEETH ON BOTH PLATFORMS, WHICH THIS TEST DID NOT HAVE. It used to
     * probe setReadable(false) alone and `return` when the platform ignored
     * it -- which is every Windows box, where this was written -- reporting
     * as a PASS while asserting nothing at all. Reverting zipTo to the old
     * runCatching-and-skip shape red nothing here, and neither did deleting
     * the partial-output cleanup: both halves of the fix this test exists for
     * were unpinned on the only machine that runs it before CI. It now tries
     * an exclusive [RandomAccessFile] region lock as well, which Windows
     * enforces on the read itself, and it ASSERTS when neither mechanism
     * engages instead of returning quietly. A check that cannot fail reads as
     * coverage; this one now fails loudly if it has stopped checking.
     */
    @Test
    fun `a file that will not read fails the whole archive and deletes the partial output`() {
        val dir = rescueDir(1_000L)
        File(dir, DATABASE_NAME).writeBytes(ByteArray(10))
        val unreadable = File(dir, "$DATABASE_NAME-wal").apply { writeBytes(ByteArray(10)) }
        val found = store.rescued().single()
        val zipFile = File(root, "out.zip")

        val ran =
            withUnreadable(unreadable) {
                assertFailsWith<IOException> { store.zipTo(found, zipFile) }
                assertFalse(zipFile.exists(), "a failed zipTo left a partial archive on disk")
            }

        assertNotNull(ran, "no mechanism on this platform makes a file unreadable, so this property went unchecked")
    }

    /**
     * BEST_SPEED, matching RawExporter.buildZip's own reasoning from issue
     * #29 on the only other zip this app streams at comparable scale: this
     * is the largest one it ever builds. Two real archives, identical
     * directory, differing only in the injected level -- not a hand-built
     * reference, which is the confound RawExporterTest's own equivalent
     * test found the hard way (see that class's history).
     */
    @Test
    fun `the rescue zip deflates at BEST_SPEED, not the library default`() {
        val dir = rescueDir(1_000L)
        // Repeated content, large enough for a compression level to show up
        // as a real byte-count difference.
        File(dir, DATABASE_NAME).writeBytes("the quick brown fox jumps over the lazy dog ".repeat(2_000).toByteArray())
        val found = store.rescued().single()

        val defaultZip = File(root, "default.zip")
        RescuedDatabaseStore(root, zipCompressionLevel = Deflater.DEFAULT_COMPRESSION).zipTo(found, defaultZip)
        val bestSpeedZip = File(root, "best.zip")
        RescuedDatabaseStore(root, zipCompressionLevel = Deflater.BEST_SPEED).zipTo(found, bestSpeedZip)
        val productionZip = File(root, "production.zip")
        store.zipTo(found, productionZip)

        assertTrue(
            bestSpeedZip.length() > defaultZip.length(),
            "expected BEST_SPEED (${bestSpeedZip.length()} B) to exceed DEFAULT_COMPRESSION " +
                "(${defaultZip.length()} B) on this compressible content",
        )
        assertEquals(bestSpeedZip.length(), productionZip.length(), "the production default should be BEST_SPEED")
    }

    // ---- platform fixtures -------------------------------------------------

    /**
     * Runs [block] with [file] genuinely unreadable, or returns null when no
     * mechanism here can make it so.
     *
     * TWO MECHANISMS BECAUSE NEITHER IS PORTABLE, and each is PROBED rather
     * than assumed to have worked. POSIX honours the permission bit, which is
     * what CI runs on; Windows returns false from setReadable and opens the
     * file anyway, but enforces an exclusive region lock on the read. Probing
     * is the point -- an unprobed fixture that silently stops biting is how
     * this test spent two review rounds passing without checking anything.
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

    /**
     * Runs [block] with [file] undeletable, or returns null when no mechanism
     * here can make it so. Probed the same way and for the same reason as
     * [withUnreadable].
     *
     * The POSIX probe uses a throwaway file rather than [file] itself: a
     * probe that deletes what it is probing destroys the fixture it was
     * checking. The Windows probe has no such option -- an open handle blocks
     * a delete per file, not per directory -- so it probes [file] directly,
     * and if the delete succeeds the mechanism had no teeth and this returns
     * null anyway.
     */
    private fun <T> withUndeletable(parent: File, file: File, block: () -> T): T? {
        if (removesWriteAccess(parent)) {
            try {
                return block()
            } finally {
                parent.setWritable(true)
            }
        }
        return file.inputStream().use { if (file.delete()) null else block() }
    }

    /** True when taking the write bit off [dir] actually stops an unlink inside it. */
    private fun removesWriteAccess(dir: File): Boolean {
        val probe = File(dir, "delete-probe.tmp").apply { writeBytes(ByteArray(1)) }
        dir.setWritable(false)
        val blocked = !probe.delete() && probe.exists()
        if (!blocked) {
            dir.setWritable(true)
            probe.delete()
        }
        return blocked
    }

    private fun opens(file: File): Boolean = runCatching { file.inputStream().use { it.read() } }.isSuccess
}
