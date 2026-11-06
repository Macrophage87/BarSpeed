package com.macrophage.barspeed.data

import com.macrophage.barspeed.model.RestorePhase
import com.macrophage.barspeed.model.RestoreRefusal
import com.macrophage.barspeed.model.RestoreVerdict
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [DatabaseRestore] against real files in a real directory, the way
 * [DatabaseRescueTest] drives the rescue. Issue #112.
 *
 * THE PROPERTY EVERY TEST HERE COMES BACK TO: nothing is lost. Whatever
 * happens -- a completed swap, a refusal, a move that fails, a process that
 * dies between any two moves, an undo that dies too -- every byte of both
 * databases is still on disk afterwards, and the next launch leaves exactly
 * one whole database at the database's own path. [contents] reads the whole
 * tree so a test can say that rather than check the files it thought of.
 *
 * What this cannot show: that Room opens what is put back, runs its
 * migrations on it, or calls the open callback that clears the journal.
 * Nothing here executes Room or SQLite. The emulator bench -- restore a
 * rescued database into a newer build and watch History fill -- is owed.
 */
class DatabaseRestoreTest {
    private val root: File = Files.createTempDirectory("db-restore").toFile()
    private val liveDir = File(root, "databases").apply { mkdirs() }
    private val live = File(liveDir, DATABASE_NAME)
    private val rescueRoot = File(root, "files/${DatabaseRescue.RESCUE_DIR}")
    private val edges = (10 until 20).map { it to it + 1 }

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun header(version: Int): ByteArray {
        val bytes = ByteArray(100)
        "SQLite format 3".toByteArray(Charsets.US_ASCII).copyInto(bytes)
        bytes[60] = (version ushr 24).toByte()
        bytes[61] = (version ushr 16).toByte()
        bytes[62] = (version ushr 8).toByte()
        bytes[63] = version.toByte()
        return bytes
    }

    /** A database at [version] in [dir], its main file tagged with [tag] so its bytes are its own. */
    private fun database(dir: File, version: Int, tag: String, wal: Boolean = true) {
        dir.mkdirs()
        File(dir, DATABASE_NAME).writeBytes(header(version) + tag.toByteArray())
        if (wal) File(dir, "$DATABASE_NAME-wal").writeText("wal of $tag")
    }

    private fun rescue(name: String, version: Int, tag: String, wal: Boolean = true): File =
        File(rescueRoot, name).also { database(it, version, tag, wal) }

    /** Every file under [root] that is not this class's bookkeeping, path relative to root -> text. */
    private fun contents(): Map<String, String> = root.walkTopDown().filter { it.isFile }
        .filterNot { it.parentFile == rescueRoot && it.name.startsWith("restore.") }
        .associate { it.relativeTo(root).path to it.readBytes().toString(Charsets.ISO_8859_1) }

    private fun mainTag(file: File) = file.readBytes().drop(100).toByteArray().toString(Charsets.US_ASCII)

    private fun restore(move: (File, File) -> Boolean = File::renameTo, now: Long = 5_000L) =
        DatabaseRestore(live, rescueRoot, 20, edges, move, { now })

    private fun store() = RescuedDatabaseStore(rescueRoot)

    private fun item(name: String) = store().rescued().single { it.directory.name == name }

    /** Simulates the process dying: not an Exception, so nothing in the restore catches it. */
    private class ProcessDeath : Error()

    private fun dyingAfter(successes: Int): (File, File) -> Boolean {
        var left = successes
        return { from, to -> if (left-- <= 0) throw ProcessDeath() else from.renameTo(to) }
    }

    private fun prepareDying(restore: DatabaseRestore): Boolean = try {
        restore.prepare()
        false
    } catch (e: ProcessDeath) {
        true
    }

    /** The arrangement before any restore: live at 20 tagged "live", rescue db-1000 at 19 tagged "old". */
    private fun standardSetup(): Map<String, String> {
        database(liveDir, 20, "live")
        rescue("db-1000", 19, "old")
        return contents()
    }

    private fun assertOriginal(before: Map<String, String>) {
        assertEquals(before, contents(), "the arrangement before the restore did not come back")
        assertEquals("live", mainTag(live))
    }

    private fun assertSwapped(before: Map<String, String>) {
        assertEquals("old", mainTag(live), "the rescued database is not in place")
        assertEquals("wal of old", File(liveDir, "$DATABASE_NAME-wal").readText())
        val displaced = File(rescueRoot, "db-5000")
        assertEquals("live", mainTag(File(displaced, DATABASE_NAME)), "the database in place was not set aside")
        assertEquals("wal of live", File(displaced, "$DATABASE_NAME-wal").readText())
        assertFalse(File(rescueRoot, "db-1000").exists(), "the restored rescue's directory was left behind")
        assertEquals(before.values.sorted(), contents().values.sorted(), "bytes were lost or invented")
    }

    // ---- the swap ------------------------------------------------------------

    @Test
    fun `a restore puts the rescue in place and sets the database in place aside, losing nothing`() {
        val before = standardSetup()
        assertTrue(restore().request(item("db-1000")))
        assertEquals(RestoreOutcome.Swapped, restore().prepare())
        assertSwapped(before)
    }

    @Test
    fun `the set-aside database is itself a restorable rescue`() {
        standardSetup()
        restore().request(item("db-1000"))
        restore().prepare()
        restore().markOpened()
        val displaced = item("db-5000")
        assertEquals(RescueCompleteness.COMPLETE, displaced.completeness)
        assertEquals(RestoreVerdict.Restorable(20), restore().verdict(displaced, store().rescued()))
    }

    @Test
    fun `an open clears the journal and the next launch does nothing`() {
        val before = standardSetup()
        restore().request(item("db-1000"))
        restore().prepare()
        restore().markOpened()
        assertFalse(File(rescueRoot, DatabaseRestore.JOURNAL).exists())
        assertEquals(RestoreOutcome.Idle, restore().prepare())
        assertSwapped(before)
    }

    /** A migration that throws never reaches the open callback; the next launch puts things back. */
    @Test
    fun `a restore that never opened is undone at the next launch`() {
        val before = standardSetup()
        restore().request(item("db-1000"))
        restore().prepare()
        assertEquals(RestoreOutcome.Undone, restore().prepare())
        assertOriginal(before)
        assertEquals(
            RestoreVerdict.Refused(RestoreRefusal.FAILED_BEFORE, 19),
            restore().verdict(item("db-1000"), store().rescued()),
        )
    }

    /**
     * The hazard behind the guard, pinned: a corruption set-aside cut off
     * after the log moved, while the restore is still UNVERIFIED. The next
     * launch's undo puts the database from before the restore back, with the
     * restored database's log stranded in the set-aside's folder; the rescue
     * that launch then tries to finish from its marker meets the database
     * from before the restore's own log, finds that name taken, and is
     * Failed -- on which `AppDatabase.build` refuses to open, on every
     * launch. This is why `SetAsideOnCorruption` moves nothing while
     * [DatabaseRestore.journalPresent] is true. Review of 95acd6a,
     * data-layer F2.
     */
    @Test
    fun `a set-aside cut off during an unverified restore would stop every launch, so a restore blocks it`() {
        standardSetup()
        restore().request(item("db-1000"))
        assertEquals(RestoreOutcome.Swapped, restore().prepare())
        assertTrue(restore().journalPresent(), "the guard does not see the unconfirmed restore")

        val failMain: (File, File) -> Boolean = { source, dest ->
            source.name != DATABASE_NAME && source.renameTo(dest)
        }
        assertIs<RescueOutcome.Failed>(DatabaseRescue.setAside(live, rescueRoot, move = failMain, now = { 7_000L }))
        assertEquals(RestoreOutcome.Undone, restore().prepare())

        assertIs<RescueOutcome.Failed>(DatabaseRescue.rescue(live, rescueRoot, 20), "the hazard did not reproduce")
        assertIs<RescueOutcome.Failed>(DatabaseRescue.rescue(live, rescueRoot, 20), "a second launch got past it")
    }

    /**
     * What the guard leaves instead: the corrupt restored database stays at
     * the path, the open throws, and the next launch's undo restores both
     * databases whole -- the one from before the restore at the path, the
     * restored one back in its rescue -- and the confirmed open after it
     * leaves no journal for the guard to see.
     */
    @Test
    fun `a corrupt restore left in place is undone whole and the guard then stands down`() {
        val before = standardSetup()
        restore().request(item("db-1000"))
        assertEquals(RestoreOutcome.Swapped, restore().prepare())
        assertTrue(restore().journalPresent())

        assertEquals(RestoreOutcome.Undone, restore().prepare())
        assertOriginal(before)
        assertFalse(File(rescueRoot, DatabaseRescue.PENDING).exists(), "a rescue was started over the undo")
        assertFalse(restore().journalPresent(), "the guard would refuse every later corruption set-aside")
    }

    /** Room creates a -shm (and may a -wal) when it opens; an undo carries those with the restored set. */
    @Test
    fun `an undo carries files the open created back with the restored database`() {
        database(liveDir, 20, "live", wal = false)
        rescue("db-1000", 19, "old", wal = false)
        restore().request(item("db-1000"))
        restore().prepare()
        File(liveDir, "$DATABASE_NAME-shm").writeText("shm made by the open")
        assertEquals(RestoreOutcome.Undone, restore().prepare())
        assertEquals("live", mainTag(live))
        assertFalse(File(liveDir, "$DATABASE_NAME-shm").exists(), "a sidecar of the restored set stayed in place")
        assertEquals("shm made by the open", File(rescueRoot, "db-1000/$DATABASE_NAME-shm").readText())
    }

    @Test
    fun `with no database in place, nothing is set aside and no empty rescue is left`() {
        rescue("db-1000", 19, "old")
        restore().request(item("db-1000"))
        assertEquals(RestoreOutcome.Swapped, restore().prepare())
        assertEquals("old", mainTag(live))
        assertFalse(File(rescueRoot, "db-5000").exists(), "an empty rescue directory would draw an empty card")
    }

    // ---- what is checked again at launch ------------------------------------

    @Test
    fun `a request the launching build cannot honour is dropped and nothing moves`() {
        database(liveDir, 20, "live")
        rescue("db-1000", 21, "newer")
        val before = contents()
        restore().request(item("db-1000"))
        val outcome = restore().prepare()
        assertEquals(RestoreOutcome.Dropped(RestoreVerdict.Refused(RestoreRefusal.NEWER_THAN_THIS_BUILD, 21)), outcome)
        assertEquals(before, contents())
        assertNull(restore().pendingRequest(), "a refused request would be retried on every launch")
    }

    @Test
    fun `a request for a rescue that has gone is dropped`() {
        standardSetup()
        restore().request(item("db-1000"))
        File(rescueRoot, "db-1000").deleteRecursively()
        assertEquals(RestoreOutcome.Dropped(null), restore().prepare())
        assertEquals("live", mainTag(live))
    }

    @Test
    fun `a rescue whose own log is unusable is judged by its main header`() {
        database(liveDir, 20, "live")
        rescue("db-1000", 19, "old", wal = false)
        File(rescueRoot, "db-1000/$DATABASE_NAME-wal").writeText("not a log")
        assertEquals(RestoreVerdict.Restorable(19), restore().verdict(item("db-1000"), store().rescued()))
    }

    @Test
    fun `a stray log in another rescue refuses, and a stray shm does not`() {
        standardSetup()
        File(rescueRoot, "db-900").mkdirs()
        File(rescueRoot, "db-900/$DATABASE_NAME-shm").writeText("shm")
        assertIs<RestoreVerdict.Restorable>(restore().verdict(item("db-1000"), store().rescued()))
        File(rescueRoot, "db-900/$DATABASE_NAME-wal").writeText("wal")
        assertEquals(
            RestoreVerdict.Refused(RestoreRefusal.SPLIT_RESCUE, 19),
            restore().verdict(item("db-1000"), store().rescued()),
        )
    }

    @Test
    fun `a fragment is not restorable and a pending request blocks a second`() {
        standardSetup()
        File(rescueRoot, "db-900").mkdirs()
        File(rescueRoot, "db-900/$DATABASE_NAME-shm").writeText("shm")
        assertEquals(
            RestoreVerdict.Refused(RestoreRefusal.NO_DATABASE_FILE, null),
            restore().verdict(item("db-900"), store().rescued()),
        )
        restore().request(item("db-1000"))
        assertEquals("db-1000", restore().pendingRequest())
        assertEquals(
            RestoreVerdict.Refused(RestoreRefusal.RESTORE_IN_PROGRESS, 19),
            restore().verdict(item("db-1000"), store().rescued()),
        )
    }

    // ---- interruption -----------------------------------------------------

    /**
     * THE ATOMICITY PROPERTY. The process dies after every possible number
     * of completed moves; the next launch, with nothing failing, finishes the
     * swap, and no byte of either database is lost on the way.
     */
    @Test
    fun `a swap the process dies in at any move is finished by the next launch`() {
        var deaths = 0
        for (k in 0 until 8) {
            cleanUp()
            liveDir.mkdirs()
            val before = standardSetup()
            restore().request(item("db-1000"))
            if (!prepareDying(restore(dyingAfter(k)))) continue
            deaths++
            assertEquals(before.values.sorted(), contents().values.sorted(), "death after $k moves lost bytes")
            assertEquals(RestoreOutcome.Swapped, restore().prepare(), "after death at move $k")
            assertSwapped(before)
        }
        assertEquals(4, deaths, "the swap of a two-file database is four real moves; each must be a death point")
    }

    @Test
    fun `an undo the process dies in at any move is finished by the next launch`() {
        var deaths = 0
        for (k in 0 until 8) {
            cleanUp()
            liveDir.mkdirs()
            val before = standardSetup()
            restore().request(item("db-1000"))
            restore().prepare()
            if (!prepareDying(restore(dyingAfter(k)))) continue
            deaths++
            assertEquals(before.values.sorted(), contents().values.sorted(), "death after $k undo moves lost bytes")
            assertEquals(RestoreOutcome.Undone, restore().prepare(), "after death at undo move $k")
            assertOriginal(before)
        }
        assertEquals(4, deaths)
    }

    @Test
    fun `a move that fails mid-swap turns the swap round and the database in place comes back`() {
        for (k in 0 until 4) {
            cleanUp()
            liveDir.mkdirs()
            val before = standardSetup()
            restore().request(item("db-1000"))
            var calls = 0
            val failingAt: (File, File) -> Boolean = { from, to -> if (calls++ == k) false else from.renameTo(to) }
            assertEquals(RestoreOutcome.Undone, restore(failingAt).prepare(), "move $k failing")
            assertOriginal(before)
        }
    }

    /**
     * GUARD, added because round 2's review found it unpinned: the undo's
     * KDoc says every undo is recorded, whether the swap reached an open or
     * turned round on a failed move. A swap turned round at its very first
     * move (the undo then starts at index -1 and moves nothing) is recorded
     * too, so this build does not offer the same restore again.
     */
    @Test
    fun `a swap turned round by a failed move is recorded as undone, at every move`() {
        for (k in 0 until 4) {
            cleanUp()
            liveDir.mkdirs()
            standardSetup()
            restore().request(item("db-1000"))
            var calls = 0
            val failingAt: (File, File) -> Boolean = { from, to -> if (calls++ == k) false else from.renameTo(to) }
            assertEquals(RestoreOutcome.Undone, restore(failingAt).prepare(), "move $k failing")
            assertEquals(
                RestoreVerdict.Refused(RestoreRefusal.FAILED_BEFORE, 19),
                restore().verdict(item("db-1000"), store().rescued()),
                "move $k failing: the undone restore is offered again by the build that undid it",
            )
        }
    }

    @Test
    fun `a move that throws is a failed move, not a crash`() {
        val before = standardSetup()
        restore().request(item("db-1000"))
        var calls = 0
        val throwing: (File, File) -> Boolean = { from, to ->
            if (calls++ == 2) throw SecurityException("denied") else from.renameTo(to)
        }
        assertEquals(RestoreOutcome.Undone, restore(throwing).prepare())
        assertOriginal(before)
    }

    /**
     * Forward and back both fail with the rescue's -wal already in place and
     * its main file not: the one arrangement Room must not open.
     */
    @Test
    fun `a restore that can go neither way is stuck, and a lone sidecar in place is not opened`() {
        val before = standardSetup()
        restore().request(item("db-1000"))
        var calls = 0
        // Moves 0 and 1 set the live pair aside; 2 brings the rescue's wal in; 3 (its main file) and all after fail.
        val failing: (File, File) -> Boolean = { from, to -> if (calls++ >= 3) false else from.renameTo(to) }
        assertIs<RestoreOutcome.Stuck>(restore(failing).prepare())
        assertFalse(restore().safeToOpen(), "Room would create a fresh database beside another database's log")
        assertEquals(before.values.sorted(), contents().values.sorted(), "a stuck restore lost bytes")
        assertEquals(RestoreOutcome.Undone, restore().prepare(), "the next launch, with moves working, recovers")
        assertOriginal(before)
        assertTrue(restore().safeToOpen())
    }

    // ---- round 1 review (A3, B2): a main file at the path is not a whole database ----

    /**
     * B2's state. The live -wal is set aside, the live main file's move
     * fails, and the -wal's way back fails too. The main file at the path
     * is the database in place, but its committed log is in the displaced
     * directory: opening it shows a pre-checkpoint snapshot and writes a
     * fresh -wal that blocks the log's return for good.
     */
    @Test
    fun `a main file whose log was set aside is not opened`() {
        val before = standardSetup()
        restore().request(item("db-1000"))
        var calls = 0
        val failing: (File, File) -> Boolean = { from, to -> if (calls++ >= 1) false else from.renameTo(to) }
        assertIs<RestoreOutcome.Stuck>(restore(failing).prepare())
        assertTrue(live.isFile && !File(liveDir, "$DATABASE_NAME-wal").exists(), "not the state under test")
        assertFalse(restore().safeToOpen(), "Room would open a main file split from its own log")
        assertEquals(before.values.sorted(), contents().values.sorted(), "a stuck restore lost bytes")
        assertEquals(RestoreOutcome.Undone, restore().prepare(), "the next launch, with moves working, recovers")
        assertOriginal(before)
    }

    /**
     * A3's state. The swap completed and never opened, so the next launch
     * undoes it: the restored database goes home, the displaced main file
     * comes back, and its -wal's return fails.
     */
    @Test
    fun `an undo stuck before the displaced log came back is not opened`() {
        val before = standardSetup()
        restore().request(item("db-1000"))
        assertEquals(RestoreOutcome.Swapped, restore().prepare())
        val failWalHome: (File, File) -> Boolean = { from, to ->
            if (from.parentFile.name == "db-5000" && from.name.endsWith("-wal")) false else from.renameTo(to)
        }
        assertIs<RestoreOutcome.Stuck>(restore(failWalHome).prepare())
        assertEquals("live", mainTag(live), "not the state under test")
        assertFalse(File(liveDir, "$DATABASE_NAME-wal").exists(), "not the state under test")
        assertFalse(restore().safeToOpen(), "Room would open a main file split from its own log")
        assertEquals(before.values.sorted(), contents().values.sorted(), "a stuck undo lost bytes")
        assertEquals(RestoreOutcome.Undone, restore().prepare())
        assertOriginal(before)
    }

    /**
     * Nothing at the path: the live database is set aside, the rescue's
     * first move in fails, and the live main file's return fails. Room would
     * create an empty database there, and the next launch would meet it in
     * the way of the move it has to finish.
     */
    @Test
    fun `nothing at the path after a stuck restore is not opened`() {
        val before = standardSetup()
        restore().request(item("db-1000"))
        var calls = 0
        val failing: (File, File) -> Boolean = { from, to -> if (calls++ >= 2) false else from.renameTo(to) }
        assertIs<RestoreOutcome.Stuck>(restore(failing).prepare())
        assertTrue(liveDir.list().orEmpty().isEmpty(), "not the state under test: ${liveDir.list().orEmpty().toList()}")
        assertFalse(restore().safeToOpen(), "Room would create an empty database in the way of the undo")
        assertEquals(RestoreOutcome.Undone, restore().prepare())
        assertOriginal(before)
    }

    /**
     * GUARD, added after the fix because a mutation survived it (reading
     * the displaced directory's contents from the rescue's directory, the
     * wrong pair): every stuck test above is a refusal, and the wrong pair
     * refuses too. Here the undo's first move -- the restored main file
     * going home -- fails, so the restored database is still whole at the
     * path, and opening it is right.
     */
    @Test
    fun `an undo stuck with the restored database still whole in place may open it`() {
        val before = standardSetup()
        restore().request(item("db-1000"))
        assertEquals(RestoreOutcome.Swapped, restore().prepare())
        var calls = 0
        val failingFirst: (File, File) -> Boolean = { from, to -> if (calls++ == 0) false else from.renameTo(to) }
        assertIs<RestoreOutcome.Stuck>(restore(failingFirst).prepare())
        assertEquals("old", mainTag(live), "not the state under test")
        assertTrue(File(liveDir, "$DATABASE_NAME-wal").exists(), "not the state under test")
        assertTrue(restore().safeToOpen(), "a whole database at the path was refused")
        assertEquals(before.values.sorted(), contents().values.sorted(), "a stuck undo lost bytes")
    }

    // ---- round 1 review (A4, B3): an undone restore is refused by this build, not every build ----

    @Test
    fun `an undone restore is offered again by a build with a newer database version`() {
        standardSetup()
        restore().request(item("db-1000"))
        assertEquals(RestoreOutcome.Swapped, restore().prepare())
        // No open was recorded, so the next launch undoes it.
        assertEquals(RestoreOutcome.Undone, restore().prepare())
        assertEquals(
            RestoreVerdict.Refused(RestoreRefusal.FAILED_BEFORE, 19),
            restore().verdict(item("db-1000"), store().rescued()),
            "the build that saw it fail offered it again",
        )
        val newer = DatabaseRestore(live, rescueRoot, 21, edges + (20 to 21), File::renameTo, { 6_000L })
        assertEquals(
            RestoreVerdict.Restorable(19),
            newer.verdict(item("db-1000"), store().rescued()),
            "a build with a newer database version still refuses a restore an older one undid",
        )
    }

    /**
     * GUARD, added when the record of undone restores began to be written
     * through a synced write (round 3 review, A's N6), because a mutation
     * that made that write replace the record rather than add to it
     * survived: a second undone restore must not make the first one
     * restorable again in the build that undid it.
     */
    @Test
    fun `a second undone restore does not forget the first`() {
        standardSetup()
        rescue("db-2000", 19, "older")
        restore().request(item("db-1000"))
        assertEquals(RestoreOutcome.Swapped, restore().prepare())
        assertEquals(RestoreOutcome.Undone, restore().prepare())
        restore().request(item("db-2000"))
        assertEquals(RestoreOutcome.Swapped, restore().prepare())
        assertEquals(RestoreOutcome.Undone, restore().prepare())
        for (name in listOf("db-1000", "db-2000")) {
            assertEquals(
                RestoreVerdict.Refused(RestoreRefusal.FAILED_BEFORE, 19),
                restore().verdict(item(name), store().rescued()),
                "$name was undone and is offered again by the build that undid it",
            )
        }
    }

    /**
     * POSIX rename replaces its destination silently, so a move into a taken
     * name would destroy whatever held it. Nothing in a normal swap puts a
     * file there; this one is planted between launches to make sure that if
     * anything ever does, it is kept rather than overwritten.
     */
    @Test
    fun `a destination that is already taken is never overwritten`() {
        val before = standardSetup()
        restore().request(item("db-1000"))
        assertTrue(prepareDying(restore(dyingAfter(2))), "the process was meant to die mid-swap")
        live.writeText("intruder")
        val outcome = restore().prepare()
        assertTrue(outcome is RestoreOutcome.Stuck || outcome is RestoreOutcome.Undone, "got $outcome")
        val after = contents().values
        assertTrue("intruder" in after, "a file in the way was overwritten")
        assertTrue(after.containsAll(before.values), "bytes of either database were lost")
    }

    @Test
    fun `a journal that cannot be read moves nothing`() {
        val before = standardSetup()
        rescueRoot.mkdirs()
        File(rescueRoot, DatabaseRestore.JOURNAL).writeText("phase=SIDEWAYS\n")
        assertIs<RestoreOutcome.Stuck>(restore().prepare())
        assertEquals(before, contents())
        assertTrue(restore().safeToOpen(), "a whole database is in place and may open")
    }

    @Test
    fun `the move order carries sidecars before main files, out of place and into it`() {
        val moves = restore().swapMoves(DatabaseRestore.Journal(RestorePhase.SWAPPING, "db-1", "db-2", 0))
        val names = moves.map { (from, to) -> from.parentFile.name + "/" + from.name + " -> " + to.parentFile.name }
        assertEquals(
            listOf(
                "databases/$DATABASE_NAME-wal -> db-2",
                "databases/$DATABASE_NAME-shm -> db-2",
                "databases/$DATABASE_NAME-journal -> db-2",
                "databases/$DATABASE_NAME -> db-2",
                "db-1/$DATABASE_NAME-wal -> databases",
                "db-1/$DATABASE_NAME-shm -> databases",
                "db-1/$DATABASE_NAME-journal -> databases",
                "db-1/$DATABASE_NAME -> databases",
            ),
            names,
        )
    }
}
