package com.macrophage.barspeed.data

import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import java.io.File

/**
 * Room's open-helper callback with ONE change: a database SQLite reports as
 * corrupt is moved into `rescued/` by [DatabaseRescue.setAside] instead of
 * deleted. UX review 2026-11-06, data-layer F2.
 *
 * `SupportSQLiteOpenHelper.Callback.onCorruption`'s default closes the
 * database and deletes its file and sidecars; the framework then opens the
 * path again and Room creates an empty database. Everything the rescue and
 * restore paths built to "move, never delete" was skipped on that path. Every
 * other member forwards to [delegate] unchanged, so migrations, validation
 * and Room's own open callback run exactly as before.
 *
 * WHEN THE MOVE FAILS, THIS THROWS rather than falling back to the delete.
 * The open then fails, the app shows that it cannot open its database, and
 * the files are still on disk; an unfinished move is completed by the next
 * launch's [DatabaseRescue.rescue] from its marker.
 *
 * NOTHING MOVES WHILE A RESTORE IS UNDER WAY. When [restoreInProgress] says
 * a restore journal is on disk, this closes the database and THROWS without
 * moving anything: the journal records where every file of both databases
 * belongs, and the next launch's undo puts them back by those names --
 * the corrupt restored database into its own rescue, the one from before it
 * back at the path. A set-aside in between would move the same files into a
 * third directory, and one cut off partway would leave the undo refusing a
 * taken destination on every launch. Review of 95acd6a. This replaces a flag
 * that stopped the empty replacement's open from confirming the restore;
 * with nothing moved there is no empty replacement to open.
 *
 * Compile- and lint-gated only: nothing in this repository can make SQLite
 * report corruption, and whether the framework calls this on every route a
 * damaged page can surface by is the platform's, not observed here.
 */
internal class SetAsideOnCorruption(
    private val delegate: SupportSQLiteOpenHelper.Callback,
    private val rescueRoot: File,
    private val restoreInProgress: () -> Boolean,
) : SupportSQLiteOpenHelper.Callback(delegate.version) {
    override fun onConfigure(db: SupportSQLiteDatabase) = delegate.onConfigure(db)

    override fun onCreate(db: SupportSQLiteDatabase) = delegate.onCreate(db)

    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
        delegate.onUpgrade(db, oldVersion, newVersion)

    override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
        delegate.onDowngrade(db, oldVersion, newVersion)

    override fun onOpen(db: SupportSQLiteDatabase) = delegate.onOpen(db)

    override fun onCorruption(db: SupportSQLiteDatabase) {
        val path = db.path
        Log.e(TAG, "SQLite reported corruption in $path; moving it aside rather than deleting it")
        // The default closes before it deletes; the move needs the same, so
        // nothing writes to a file that has left its path.
        try {
            if (db.isOpen) db.close()
        } catch (e: Exception) {
            Log.w(TAG, "closing the corrupt database failed; moving it anyway", e)
        }
        // An in-memory database has no file to keep.
        if (path == null || path == ":memory:") return
        check(!restoreInProgress()) {
            "corrupt while a restore is under way; left in place for the next launch to undo the restore"
        }
        val outcome = DatabaseRescue.setAside(File(path), rescueRoot)
        check(outcome is RescueOutcome.Rescued) {
            "a corrupt database could not be moved aside, and was not deleted: $outcome"
        }
    }

    private companion object {
        const val TAG = "SetAsideOnCorruption"
    }
}
