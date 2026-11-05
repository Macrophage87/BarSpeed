package com.macrophage.barspeed.model

/**
 * Why a rescued database cannot be put back. Issue #112.
 *
 * Each is a distinct fact a card can say, never a generic "not available":
 * the lifter deciding whether to send a rescue off the phone, discard it, or
 * install another build needs to know which of these stands in the way.
 */
enum class RestoreRefusal {
    /** The rescue holds no main database file -- a fragment or an empty one. */
    NO_DATABASE_FILE,

    /**
     * Another rescue holds a `-wal` or `-journal` with no main file beside it.
     * An interrupted rescue splits one database across two directories
     * (sidecars move first), and that log may hold committed sets this main
     * file lacks. Nothing here can tell which main file a stray log belongs
     * to, so restoring would quietly drop whatever is in it.
     */
    SPLIT_RESCUE,

    /** A restore of this rescue was tried, did not open in this build, and was undone. */
    FAILED_BEFORE,

    /** Its version could not be read, so nothing can say whether this build can open it. */
    VERSION_UNREADABLE,

    /**
     * Newer than this build. Restoring it would recreate the downgrade it was
     * rescued from, and the next launch would rescue it again -- a loop, not
     * a restore. It needs a build at or above its version.
     */
    NEWER_THAN_THIS_BUILD,

    /** Older than any version this build's migration chain starts from: Room would throw at open. */
    NO_MIGRATION_PATH,

    /** A restore is already scheduled or under way. One at a time. */
    RESTORE_IN_PROGRESS,
}

/** Whether a rescued database can be put back in place. */
sealed interface RestoreVerdict {
    /** Its version, which this build can open (equal) or migrate (older). */
    data class Restorable(val version: Int) : RestoreVerdict

    data class Refused(val reason: RestoreRefusal, val version: Int?) : RestoreVerdict
}

/**
 * Where a restore stands, as written to its journal before every file move.
 * A launch that finds one of these knows exactly what to do next.
 */
enum class RestorePhase {
    /** Moving files: the database in place into a new rescue, then the chosen rescue into place. */
    SWAPPING,

    /** Every file moved; waiting for the restored database's first successful open. */
    UNVERIFIED,

    /** Putting everything back where it was, in reverse. */
    UNDOING,
}

/** What a launch does about restoring, before anything opens the database. */
enum class RestoreLaunchStep {
    /** No journal and no request: the ordinary launch. */
    NOTHING,

    /** A request and no journal: check it again, and start the swap if it still holds. */
    BEGIN,

    /** An interrupted swap: carry it forward from the move it reached. */
    RESUME,

    /**
     * Undo: either an interrupted undo, or a swap that completed on an
     * earlier launch and never reached an open -- the restored database
     * failed to open (a migration threw, most likely) or the process died
     * first. Either way the database in place is not known to work, and the
     * one it displaced is.
     */
    UNDO,
}

/**
 * Every decision restoring a rescued database makes, kept apart from the
 * file moves so it can be tested. Issue #112.
 *
 * WHICH RESCUE: the one the lifter tapped, and only one with its main file
 * ([RestoreRefusal.NO_DATABASE_FILE]) and no stray log elsewhere that may be
 * its other half ([RestoreRefusal.SPLIT_RESCUE]).
 *
 * WHAT HAPPENS TO THE DATABASE IN PLACE: it is never overwritten. The swap
 * moves it into a rescue directory of its own first, so it becomes one more
 * rescued card that can be sent, discarded, or restored the same way. Sessions
 * recorded since the rollback are in it, and stay there.
 *
 * THE VERSION RELATIONSHIP: only into a build at or above the rescue's own
 * version ([RestoreRefusal.NEWER_THAN_THIS_BUILD]), and only from a version the
 * build's migration chain reaches ([RestoreRefusal.NO_MIGRATION_PATH]).
 *
 * ATOMICITY: per file, which is all a rename gives. The journal records which
 * move comes next before each one, so a launch can always carry a swap
 * forward or back to a state where one complete database is in place;
 * [launchStep] decides which.
 */
object DatabaseRestorePolicy {
    /**
     * [version] is what the rescue's own header and `-wal` say (null when
     * they say nothing); [hasDatabaseFile] whether its main file is there;
     * [strayLogElsewhere] whether any OTHER rescue holds a `-wal` or `-journal`
     * with no main file; [failedBefore] whether this rescue's restore was
     * undone before; [restoreInProgress] whether a request or journal exists.
     *
     * Checked in that order of what the lifter can do about it: a fragment
     * cannot be restored at all; a stray log or a running restore can be
     * dealt with on this screen; a version mismatch needs another build.
     */
    fun verdict(
        hasDatabaseFile: Boolean,
        version: Int?,
        compiledVersion: Int,
        migrationEdges: List<Pair<Int, Int>>,
        strayLogElsewhere: Boolean,
        failedBefore: Boolean,
        restoreInProgress: Boolean,
    ): RestoreVerdict {
        fun refuse(reason: RestoreRefusal) = RestoreVerdict.Refused(reason, version)
        return when {
            !hasDatabaseFile -> refuse(RestoreRefusal.NO_DATABASE_FILE)
            restoreInProgress -> refuse(RestoreRefusal.RESTORE_IN_PROGRESS)
            strayLogElsewhere -> refuse(RestoreRefusal.SPLIT_RESCUE)
            failedBefore -> refuse(RestoreRefusal.FAILED_BEFORE)
            version == null -> refuse(RestoreRefusal.VERSION_UNREADABLE)
            version > compiledVersion -> refuse(RestoreRefusal.NEWER_THAN_THIS_BUILD)
            !canMigrate(version, compiledVersion, migrationEdges) -> refuse(RestoreRefusal.NO_MIGRATION_PATH)
            else -> RestoreVerdict.Restorable(version)
        }
    }

    /**
     * Whether [edges] (each a migration's start and end version) take a
     * database from [from] to [to]. Equal is trivially yes: no migration runs.
     * Only upward edges are followed, which is all Room is given here; an
     * edge past [to] can therefore never lead back to it, and needs no bound.
     */
    fun canMigrate(from: Int, to: Int, edges: List<Pair<Int, Int>>): Boolean {
        if (from == to) return true
        val seen = mutableSetOf(from)
        var frontier = listOf(from)
        while (frontier.isNotEmpty()) {
            val next = edges.filter { (start, end) -> start in frontier && end > start && end !in seen }
                .map { it.second }
            if (to in next) return true
            seen += next
            frontier = next
        }
        return false
    }

    /**
     * After a restore that could go neither forward nor back, whether Room may
     * open what is at the database's path. Yes when a main file is there (a
     * whole database, the displaced one or the restored one), and yes when
     * nothing is (Room creates an empty one, and every file of both databases
     * is still on disk where the journal says). NO when a sidecar sits there
     * with no main file: Room would create a fresh database beside a log from
     * a different one, the one state `DatabaseRescue`'s move order exists to
     * make unreachable. Refusing means the launch throws -- a crash with every
     * byte on disk, which beats an open that can corrupt.
     */
    fun safeToOpenAfterStuck(mainPresent: Boolean, sidecarPresent: Boolean): Boolean = mainPresent || !sidecarPresent

    /**
     * After a restore that could go neither forward nor back, whether the
     * database's path holds exactly one database's complete set of files, so
     * that Room may open it. Issue #112, round 1 review (A3, B2).
     *
     * A MAIN FILE AT THE PATH IS NOT A WHOLE DATABASE. Moving a database
     * either way passes through states where its main file is in one place
     * and its `-wal` in another -- out of the path the sidecars go first, so
     * the main file sits there for a moment without its log -- and a stuck
     * restore can stop in any of them. Opening such a main file shows a
     * snapshot from before its last checkpoint, and the open writes a fresh
     * `-wal` beside it, which makes every later attempt to put the old one
     * back refuse: the split becomes permanent. Nothing at the path is no
     * better: Room would create an empty database there, and the next launch
     * meets it in the way of the move it has to finish.
     *
     * WHAT THIS ASKS INSTEAD. Every file of either database is at the path or
     * in that database's own directory -- the displaced one's new rescue
     * directory, or the restored one's source directory -- because a move is
     * a rename and never leaves a copy. So, with [displacedFiles] and
     * [restoredFiles] the names each database had when the swap began, and
     * [inDisplacedDir] and [inSourceDir] the names in each directory now, a
     * database is whole at the path when its directory holds none of its
     * files and the other database has none at the path. Anything in a
     * database's directory is that database's, including a file an earlier
     * open created, so a non-empty directory is never whole whatever the
     * recorded sets say. [mainAtPath] is checked as well, so a vanished main
     * file is never taken for a whole one.
     *
     * Refusing means the launch throws -- a crash with every byte on disk,
     * retried by every launch after it, which beats an open that splits a
     * database from its log for good. [mainName] is the database file's name;
     * the other names are its sidecars.
     */
    fun oneWholeDatabaseInPlace(
        mainName: String,
        displacedFiles: Set<String>,
        restoredFiles: Set<String>,
        inDisplacedDir: Set<String>,
        inSourceDir: Set<String>,
        mainAtPath: Boolean,
    ): Boolean {
        val displacedAtPath = displacedFiles - inDisplacedDir
        val restoredAtPath = restoredFiles - inSourceDir
        val displacedWhole = mainName in displacedFiles && inDisplacedDir.isEmpty() && restoredAtPath.isEmpty()
        val restoredWhole = mainName in restoredFiles && inSourceDir.isEmpty() && displacedAtPath.isEmpty()
        return mainAtPath && (displacedWhole || restoredWhole)
    }

    /**
     * The journal outranks the request: a journal means files have started
     * moving, and until it is resolved a request must not start a second swap
     * over the first. A swap left [RestorePhase.UNVERIFIED] by an earlier
     * launch is undone: had its database opened, the open would have cleared
     * the journal.
     */
    fun launchStep(phase: RestorePhase?, requested: Boolean): RestoreLaunchStep = when (phase) {
        RestorePhase.SWAPPING -> RestoreLaunchStep.RESUME
        RestorePhase.UNVERIFIED, RestorePhase.UNDOING -> RestoreLaunchStep.UNDO
        null -> if (requested) RestoreLaunchStep.BEGIN else RestoreLaunchStep.NOTHING
    }
}
