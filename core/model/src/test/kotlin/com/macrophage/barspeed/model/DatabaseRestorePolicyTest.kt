package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [DatabaseRestorePolicy]: which rescue may be put back, into which build,
 * and what a launch does about a restore in flight. Issue #112.
 *
 * Every decision is here so it is pinned on every push; the file moves that
 * carry it out are `DatabaseRestore` in :core:data, pinned against real files
 * by `DatabaseRestoreTest`. Neither runs Room: whether a restored database's
 * migrations succeed on a phone is the emulator bench's to show, and is owed.
 */
class DatabaseRestorePolicyTest {
    private val chain = (10 until 20).map { it to it + 1 }

    private fun verdict(
        hasDatabaseFile: Boolean = true,
        version: Int? = 19,
        compiled: Int = 20,
        edges: List<Pair<Int, Int>> = chain,
        stray: Boolean = false,
        failedBefore: Boolean = false,
        inProgress: Boolean = false,
    ) = DatabaseRestorePolicy.verdict(hasDatabaseFile, version, compiled, edges, stray, failedBefore, inProgress)

    private fun reasonOf(verdict: RestoreVerdict) = (verdict as RestoreVerdict.Refused).reason

    private fun refused(reason: RestoreRefusal, version: Int?) = RestoreVerdict.Refused(reason, version)

    @Test
    fun `a rescue at this build's version is restorable`() {
        assertEquals(RestoreVerdict.Restorable(20), verdict(version = 20))
    }

    @Test
    fun `an older rescue the migrations reach is restorable`() {
        assertEquals(RestoreVerdict.Restorable(19), verdict(version = 19))
        assertEquals(RestoreVerdict.Restorable(10), verdict(version = 10))
    }

    /** Restoring a 21 into a 20 recreates the downgrade it was rescued from. */
    @Test
    fun `a rescue newer than this build is refused, not looped`() {
        assertEquals(refused(RestoreRefusal.NEWER_THAN_THIS_BUILD, 21), verdict(version = 21))
    }

    @Test
    fun `a rescue older than the migration chain starts is refused`() {
        assertEquals(refused(RestoreRefusal.NO_MIGRATION_PATH, 9), verdict(version = 9))
    }

    @Test
    fun `a gap in the chain refuses everything below it`() {
        val gapped = chain - (14 to 15)
        assertEquals(refused(RestoreRefusal.NO_MIGRATION_PATH, 14), verdict(version = 14, edges = gapped))
        assertEquals(RestoreVerdict.Restorable(15), verdict(version = 15, edges = gapped))
    }

    @Test
    fun `an unreadable version is refused rather than guessed`() {
        assertEquals(refused(RestoreRefusal.VERSION_UNREADABLE, null), verdict(version = null))
    }

    @Test
    fun `a rescue without its main file is refused first`() {
        assertEquals(
            refused(RestoreRefusal.NO_DATABASE_FILE, null),
            verdict(hasDatabaseFile = false, version = null, stray = true, inProgress = true),
        )
    }

    @Test
    fun `a stray log in another rescue refuses the restore`() {
        assertEquals(refused(RestoreRefusal.SPLIT_RESCUE, 19), verdict(stray = true))
    }

    @Test
    fun `a rescue whose restore was undone before is refused`() {
        assertEquals(refused(RestoreRefusal.FAILED_BEFORE, 19), verdict(failedBefore = true))
    }

    @Test
    fun `one restore at a time`() {
        assertEquals(refused(RestoreRefusal.RESTORE_IN_PROGRESS, 19), verdict(inProgress = true))
    }

    /** What the lifter can fix on this screen is named before what needs another build. */
    @Test
    fun `refusals are ordered by what the lifter can do about them`() {
        assertEquals(
            RestoreRefusal.RESTORE_IN_PROGRESS,
            reasonOf(verdict(version = 21, stray = true, failedBefore = true, inProgress = true)),
        )
        assertEquals(
            RestoreRefusal.SPLIT_RESCUE,
            reasonOf(verdict(version = 21, stray = true, failedBefore = true)),
        )
        assertEquals(
            RestoreRefusal.FAILED_BEFORE,
            reasonOf(verdict(version = 21, failedBefore = true)),
        )
    }

    @Test
    fun `migration reach follows only upward edges that land at or below the target`() {
        assertTrue(DatabaseRestorePolicy.canMigrate(20, 20, emptyList()))
        assertTrue(DatabaseRestorePolicy.canMigrate(18, 20, listOf(18 to 19, 19 to 20)))
        assertTrue(DatabaseRestorePolicy.canMigrate(18, 20, listOf(18 to 20)))
        assertFalse(DatabaseRestorePolicy.canMigrate(18, 20, listOf(18 to 19)))
        assertFalse(DatabaseRestorePolicy.canMigrate(18, 20, listOf(18 to 21, 21 to 20)))
        assertFalse(DatabaseRestorePolicy.canMigrate(21, 20, listOf(21 to 20)))
    }

    // ---- what a launch does -------------------------------------------------

    @Test
    fun `a launch with nothing pending does nothing`() {
        assertEquals(RestoreLaunchStep.NOTHING, DatabaseRestorePolicy.launchStep(null, requested = false))
    }

    @Test
    fun `a request with no journal begins`() {
        assertEquals(RestoreLaunchStep.BEGIN, DatabaseRestorePolicy.launchStep(null, requested = true))
    }

    /** A second swap must never start over an unfinished first. */
    @Test
    fun `a journal outranks a request`() {
        for (requested in listOf(true, false)) {
            assertEquals(RestoreLaunchStep.RESUME, DatabaseRestorePolicy.launchStep(RestorePhase.SWAPPING, requested))
            assertEquals(RestoreLaunchStep.UNDO, DatabaseRestorePolicy.launchStep(RestorePhase.UNDOING, requested))
            assertEquals(RestoreLaunchStep.UNDO, DatabaseRestorePolicy.launchStep(RestorePhase.UNVERIFIED, requested))
        }
    }

    @Test
    fun `after a stuck restore, a sidecar with no main file is the one state not opened`() {
        assertTrue(DatabaseRestorePolicy.safeToOpenAfterStuck(mainPresent = true, sidecarPresent = true))
        assertTrue(DatabaseRestorePolicy.safeToOpenAfterStuck(mainPresent = true, sidecarPresent = false))
        assertTrue(DatabaseRestorePolicy.safeToOpenAfterStuck(mainPresent = false, sidecarPresent = false))
        assertFalse(DatabaseRestorePolicy.safeToOpenAfterStuck(mainPresent = false, sidecarPresent = true))
    }

    // ---- round 1 review (A3, B2): what a stuck restore may open ---------------

    private val db = "a.db"
    private val wal = "a.db-wal"
    private val pair = setOf(db, wal)

    private fun whole(
        displaced: Set<String> = pair,
        restored: Set<String> = pair,
        inDisplaced: Set<String>,
        inSource: Set<String>,
        mainAtPath: Boolean = true,
    ) = DatabaseRestorePolicy.oneWholeDatabaseInPlace(db, displaced, restored, inDisplaced, inSource, mainAtPath)

    @Test
    fun `before any move and after the last, one whole database is in place`() {
        assertTrue(whole(inDisplaced = emptySet(), inSource = pair), "nothing moved yet")
        assertTrue(whole(inDisplaced = pair, inSource = emptySet()), "every move done")
    }

    @Test
    fun `a main file whose log is in its directory is not a whole database`() {
        // The database in place lost its -wal to the displaced directory and kept its main file.
        assertFalse(whole(inDisplaced = setOf(wal), inSource = pair))
        // The restored main file came in; its -wal is still in the rescue's directory.
        assertFalse(whole(inDisplaced = pair, inSource = setOf(wal)))
    }

    @Test
    fun `a file of the other database at the path makes it not whole`() {
        // Each database's own directory is empty, but a file of the other one is at the path.
        assertFalse(whole(displaced = setOf(db), inDisplaced = emptySet(), inSource = setOf(db)))
        assertFalse(whole(restored = setOf(db), inDisplaced = setOf(db), inSource = emptySet()))
    }

    @Test
    fun `nothing at the path is not opened`() {
        assertFalse(whole(inDisplaced = pair, inSource = pair, mainAtPath = false))
    }

    @Test
    fun `a database with no main file of its own is never the whole one`() {
        // The database in place had only a stray -wal: with nothing moved, the path holds no database.
        assertFalse(whole(displaced = setOf(wal), inDisplaced = emptySet(), inSource = pair))
    }

    @Test
    fun `a file an open created and an undo carried home still counts against the database`() {
        // The restored database had no -wal when the swap began; an open made one and an undo
        // carried it into the rescue's directory. The main file at the path is not whole.
        assertFalse(whole(restored = setOf(db), inDisplaced = pair, inSource = setOf(wal)))
    }

    @Test
    fun `a main file that vanished is not taken for a whole database`() {
        assertFalse(whole(inDisplaced = emptySet(), inSource = pair, mainAtPath = false))
    }
}
