package com.macrophage.barspeed.data

import com.macrophage.barspeed.model.DatabaseRestorePolicy
import com.macrophage.barspeed.model.RestoreLaunchStep
import com.macrophage.barspeed.model.RestorePhase
import com.macrophage.barspeed.model.RestoreVerdict
import java.io.File

/** What [DatabaseRestore.prepare] did at launch, so a caller can say it rather than infer it. */
sealed interface RestoreOutcome {
    /** No request and no journal: the ordinary launch. */
    data object Idle : RestoreOutcome

    /** A request was found and dropped because its rescue no longer qualifies. */
    data class Dropped(val verdict: RestoreVerdict?) : RestoreOutcome

    /** Every file moved; the restored database is in place, waiting for its first open. */
    data object Swapped : RestoreOutcome

    /** A restore was put back the way it was: the database it displaced is in place again. */
    data object Undone : RestoreOutcome

    /**
     * Neither forward nor back could be completed. Every file is still on
     * disk, in its source or its destination, and the journal says which
     * move was next -- but what sits at the database's own path may be a
     * sidecar with no main file beside it, which is the one state Room must
     * never open. The caller asks [DatabaseRestore.safeToOpen] before it
     * opens anything on this outcome.
     */
    data class Stuck(val reason: String) : RestoreOutcome
}

/**
 * Puts a rescued database back in place, at the lifter's word, before
 * anything opens it. Issue #112.
 *
 * TWO HALVES, AND ONLY ONE IS INTERACTIVE. The card [request]s a restore --
 * one small file naming the rescue -- and the app restarts. The NEXT launch
 * does the work, in [prepare], from `AppDatabase.build`, before Room is asked
 * for anything: the same place, and for the same reason, as
 * [DatabaseRescue.rescue]. Restoring under an open database is not possible
 * (the app's one Room instance holds it for the life of the process), and
 * nothing here is attempted while anything holds it.
 *
 * NOTHING IS OVERWRITTEN AND NOTHING IS DELETED. The swap is two moves per
 * file name: the database in place -- including any sessions recorded since
 * the rollback -- goes into a NEW rescue directory first, then the chosen
 * rescue comes into place. The displaced database is one more rescued card,
 * restorable the same way. The only removals are of directories already
 * empty and of this class's own bookkeeping files.
 *
 * ATOMIC PER FILE, RESUMABLE AS A WHOLE. A rename is atomic for one file, and
 * a database is up to four, so the set is not -- but [JOURNAL] records the
 * phase and the index of the next move, written atomically (temp file then
 * rename) BEFORE each move. Every file is therefore always in exactly one of
 * two known places, and whichever launch comes next can carry the swap
 * forward ([RestorePhase.SWAPPING]) or back ([RestorePhase.UNDOING]) to a
 * state with one whole database in place. Moves out of the database's path go
 * sidecars first, main file last, as [DatabaseRescue.moveOrder] requires;
 * moves into it go in the same order, so the path never holds a main file
 * beside a sidecar from a different database.
 *
 * A RESTORE THAT DOES NOT OPEN IS UNDONE. After the last move the phase is
 * [RestorePhase.UNVERIFIED], and only [markOpened] -- called from Room's own
 * open callback, after migrations and validation -- clears it. A launch that
 * still finds UNVERIFIED knows the previous one never got that far (a
 * migration threw, most likely, or the process died first), and puts both
 * databases back. The cost of a false undo is a second tap; the cost of not
 * undoing is an app that crash-loops before any screen draws.
 *
 * [move] is [File.renameTo] in production and a seam for tests, as in
 * [DatabaseRescue.rescue]. An exception from it is caught and treated as a
 * failed move; an [Error] is not, so a test can stand in for a process death
 * mid-swap.
 */
class DatabaseRestore(
    private val databaseFile: File,
    private val rescueRoot: File,
    private val compiledVersion: Int,
    private val migrationEdges: List<Pair<Int, Int>>,
    private val move: (source: File, dest: File) -> Boolean = File::renameTo,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val requestFile get() = File(rescueRoot, REQUEST)
    private val journalFile get() = File(rescueRoot, JOURNAL)
    private val failedFile get() = File(rescueRoot, FAILED)

    /** Whether [item] can be restored by this build, now. Reads headers only; opens nothing. */
    fun verdict(item: RescuedDatabase, all: List<RescuedDatabase>): RestoreVerdict {
        val stray = all.any { other ->
            other.directory != item.directory &&
                !other.hasDatabaseFile &&
                other.files.any { it.endsWith("-wal") || it.endsWith("-journal") }
        }
        return DatabaseRestorePolicy.verdict(
            hasDatabaseFile = item.hasDatabaseFile,
            version = if (item.hasDatabaseFile) {
                DatabaseRescue.storedVersion(File(item.directory, databaseFile.name))
            } else {
                null
            },
            compiledVersion = compiledVersion,
            migrationEdges = migrationEdges,
            strayLogElsewhere = stray,
            failedBefore = item.directory.name in failedBefore(),
            restoreInProgress = requestFile.exists() || journalFile.exists(),
        )
    }

    /** The rescue directory a request names, if one is waiting for the next launch. */
    fun pendingRequest(): String? = runCatching { requestFile.readText().trim().takeIf { it.isNotEmpty() } }
        .getOrNull()

    /**
     * Ask the next launch to restore [item]. True when the request is on
     * disk. Re-checked at that launch, so a request this build could honour
     * and the next cannot (an older APK installed meanwhile) is dropped, not
     * carried out.
     */
    fun request(item: RescuedDatabase): Boolean = runCatching {
        rescueRoot.mkdirs()
        writeAtomically(requestFile, item.directory.name)
    }.getOrDefault(false)

    /**
     * The launch-time half. Call before anything opens the database. Never
     * throws an [Exception]; see [RestoreOutcome.Stuck] for the one outcome
     * that forbids opening.
     */
    fun prepare(): RestoreOutcome {
        val journal = readJournal()
        if (journal == null && journalFile.exists()) {
            // A journal this code cannot read is never guessed at or removed.
            return RestoreOutcome.Stuck("unreadable restore journal")
        }
        return when (DatabaseRestorePolicy.launchStep(journal?.phase, requestFile.exists())) {
            RestoreLaunchStep.NOTHING -> RestoreOutcome.Idle
            RestoreLaunchStep.BEGIN -> begin()
            RestoreLaunchStep.RESUME -> forward(journal!!)
            RestoreLaunchStep.UNDO -> undo(journal!!)
        }
    }

    /**
     * Whether the database's own path is safe for Room to open after a
     * [RestoreOutcome.Stuck]: see [DatabaseRestorePolicy.safeToOpenAfterStuck].
     */
    fun safeToOpen(): Boolean {
        val live = databaseFile.parentFile ?: File(".")
        val sidecars = DatabaseRescue.moveOrder(databaseFile).filter { it.path != databaseFile.path }
        return DatabaseRestorePolicy.safeToOpenAfterStuck(
            mainPresent = databaseFile.exists(),
            sidecarPresent = sidecars.any { File(live, it.name).exists() },
        )
    }

    /**
     * The restored database opened -- Room's own open callback, after
     * migrations and validation. Clears an [RestorePhase.UNVERIFIED] journal
     * and nothing else; any other phase is a launch's business, not an
     * open's.
     */
    fun markOpened() {
        runCatching {
            if (readJournal()?.phase == RestorePhase.UNVERIFIED) journalFile.delete()
        }
    }

    private fun begin(): RestoreOutcome {
        val name = pendingRequest()
        val source = name?.let { File(rescueRoot, it) }
        val item = source?.takeIf { it.isDirectory }?.let { dir ->
            RescuedDatabaseStore(rescueRoot).rescued().firstOrNull { it.directory == dir }
        }
        if (item == null) {
            requestFile.delete()
            return RestoreOutcome.Dropped(null)
        }
        // The request itself would read as "in progress"; judge the rescue as if it were not there.
        requestFile.delete()
        val verdict = verdict(item, RescuedDatabaseStore(rescueRoot).rescued())
        if (verdict !is RestoreVerdict.Restorable) return RestoreOutcome.Dropped(verdict)
        var stamp = now()
        while (File(rescueRoot, "${DatabaseRescue.DIR_PREFIX}$stamp").exists()) stamp++
        val journal = Journal(RestorePhase.SWAPPING, item.directory.name, "${DatabaseRescue.DIR_PREFIX}$stamp", 0)
        if (!writeJournal(journal)) return RestoreOutcome.Dropped(verdict)
        return forward(journal)
    }

    /** Carry the swap forward from [journal]'s next move; on any failed move, turn round. */
    private fun forward(journal: Journal): RestoreOutcome {
        val moves = swapMoves(journal)
        File(rescueRoot, journal.displaced).mkdirs()
        requestFile.delete()
        for (i in journal.next until moves.size) {
            if (!writeJournal(journal.copy(next = i))) return undo(journal.copy(phase = RestorePhase.UNDOING, next = i))
            val (from, to) = moves[i]
            // A refused or failed move did not happen, so the undo starts one before it.
            if (!step(from, to)) return undo(journal.copy(phase = RestorePhase.UNDOING, next = i - 1))
        }
        if (!writeJournal(journal.copy(phase = RestorePhase.UNVERIFIED, next = moves.size))) {
            return undo(journal.copy(phase = RestorePhase.UNDOING, next = moves.size))
        }
        removeIfEmpty(File(rescueRoot, journal.source))
        removeIfEmpty(File(rescueRoot, journal.displaced))
        return RestoreOutcome.Swapped
    }

    /**
     * Reverse every move up to and including [journal]'s next one -- that
     * one may or may not have happened, and [stepBack] tells the two apart
     * by where the file is. Records the source as [FAILED] so the card stops
     * offering a restore this build has already seen fail.
     */
    private fun undo(journal: Journal): RestoreOutcome {
        val moves = swapMoves(journal)
        File(rescueRoot, journal.source).mkdirs()
        requestFile.delete()
        for (i in minOf(journal.next, moves.size - 1) downTo 0) {
            if (!writeJournal(journal.copy(phase = RestorePhase.UNDOING, next = i))) {
                return RestoreOutcome.Stuck("could not record undo progress")
            }
            val (from, to) = moves[i]
            if (!stepBack(from, to)) return RestoreOutcome.Stuck("could not move ${to.name} back")
        }
        runCatching { failedFile.appendText(journal.source + "\n") }
        journalFile.delete()
        removeIfEmpty(File(rescueRoot, journal.displaced))
        return RestoreOutcome.Undone
    }

    /**
     * Every move of a swap, in order: the database in place into the
     * displaced directory, sidecars first and main file last; then the
     * rescue into place in the same order. Positions in this list are what
     * the journal's index counts, so the list is a function of the journal
     * alone and is the same on every launch.
     */
    internal fun swapMoves(journal: Journal): List<Pair<File, File>> {
        val names = DatabaseRescue.moveOrder(databaseFile).map { it.name }
        val live = databaseFile.parentFile ?: File(".")
        val source = File(rescueRoot, journal.source)
        val displaced = File(rescueRoot, journal.displaced)
        return names.map { File(live, it) to File(displaced, it) } + names.map { File(source, it) to File(live, it) }
    }

    /** One forward move: done if [from] is gone; refused if both exist. */
    private fun step(from: File, to: File): Boolean = when {
        !from.exists() -> true
        to.exists() -> false
        else -> safeMove(from, to)
    }

    /** One move reversed: nothing to do if [to] holds nothing; refused if both exist. */
    private fun stepBack(from: File, to: File): Boolean = when {
        !to.exists() -> true
        from.exists() -> false
        else -> safeMove(to, from)
    }

    private fun safeMove(from: File, to: File): Boolean = try {
        move(from, to)
    } catch (e: Exception) {
        false
    }

    private fun failedBefore(): Set<String> = runCatching {
        if (!failedFile.isFile) return emptySet()
        failedFile.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }.getOrDefault(emptySet())

    private fun removeIfEmpty(dir: File) {
        if (dir.isDirectory && dir.listFiles().isNullOrEmpty()) dir.delete()
    }

    internal data class Journal(val phase: RestorePhase, val source: String, val displaced: String, val next: Int)

    private fun readJournal(): Journal? = runCatching {
        if (!journalFile.isFile) return null
        val fields = journalFile.readLines().mapNotNull { line ->
            line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
        }.toMap()
        Journal(
            phase = RestorePhase.valueOf(fields.getValue("phase")),
            source = fields.getValue("source").also { require(it.startsWith(DatabaseRescue.DIR_PREFIX)) },
            displaced = fields.getValue("displaced").also { require(it.startsWith(DatabaseRescue.DIR_PREFIX)) },
            next = fields.getValue("next").toInt(),
        )
    }.getOrNull()

    private fun writeJournal(journal: Journal): Boolean = writeAtomically(
        journalFile,
        "phase=${journal.phase}\nsource=${journal.source}\ndisplaced=${journal.displaced}\nnext=${journal.next}\n",
    )

    /** Temp file, then rename over the target: a reader sees the old text or the new, never half. */
    private fun writeAtomically(target: File, text: String): Boolean = runCatching {
        val temp = File(target.path + ".tmp")
        temp.writeText(text)
        temp.renameTo(target)
    }.getOrDefault(false)

    companion object {
        /** A restore the lifter asked for, waiting for the next launch: one line, the rescue's directory name. */
        const val REQUEST = "restore.request"

        /** A restore under way: its phase, its two directories, and the next move. */
        const val JOURNAL = "restore.journal"

        /** Rescues whose restore was undone, one directory name per line. */
        const val FAILED = "restore.failed"
    }
}
