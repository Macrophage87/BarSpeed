package com.macrophage.barspeed.data

import java.io.File
import java.io.FileOutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * What a rescued directory actually holds -- the ONE three-way split every
 * reader of [RescuedDatabase] makes, named once here instead of re-derived at
 * each site from [RescuedDatabase.hasDatabaseFile] and
 * [RescuedDatabase.isEmpty].
 *
 * WHY IT IS A VALUE IN :core:data RATHER THAN A `when` IN :app. The card for
 * issue #111 makes four decisions off this split -- the title, whether to
 * list the files, what the discard dialog warns about, and whether SEND is
 * worth offering -- and made all four in a module with no test source set.
 * That was measured, not assumed: this issue's own gate permuted those
 * branches in :app and the whole suite stayed green. Computed here, the split
 * is pinned by `RescuedDatabaseStoreTest` on every push, and :app is left
 * with a `when` over a value it does not compute.
 *
 * [COMPLETE] IS NOT A COMPLETENESS GUARANTEE, and its name is the trap this
 * doc exists to disarm. It means the main database file is present, nothing
 * more. [RescuedDatabaseStore.zipTo]'s own KDoc states why that is weaker
 * than it sounds: a `.db` alone can look complete and be stale, because its
 * `-wal` can hold committed transactions the main file does not yet have --
 * and in a split rescue that `-wal` is in a DIFFERENT directory, surfacing as
 * its own [FRAGMENT]. Nothing may render [COMPLETE] as "you have everything".
 *
 * [riskOrder] is declared per constant rather than read off `ordinal`, so
 * that reordering these constants -- a change that reads as cosmetic --
 * cannot silently reorder the cards a lifter taps on. What the order means
 * is [RescuedDatabaseStore.rescued]'s concern and documented there.
 *
 * WHAT MOVING IT HERE DID NOT BUY: only the CLASSIFICATION is pinned, not
 * the mapping from a state to a string and to a button. Which title, which
 * warning, whether SEND is drawn and whether DISCARD goes through a
 * confirmation are still decided in :app, where nothing can fail, and
 * permuting them leaves the whole suite green.
 */
enum class RescueCompleteness(val riskOrder: Int) {
    /** The main database file is present. Not a guarantee it is current; see this enum's own KDoc. */
    COMPLETE(0),

    /**
     * No main database file, but at least one sidecar survived. The state
     * that may hold data nothing else has, and the one a reader must not
     * describe as junk -- see [RescuedDatabase.hasDatabaseFile]'s own KDoc.
     */
    FRAGMENT(1),

    /** Nothing at all. See [RescuedDatabase.isEmpty] for why this is reachable on its own. */
    NOTHING(2),
}

/**
 * One `rescued/db-<ms>/` directory found on disk, and what the filesystem
 * alone can say about it. Issue #111.
 *
 * NEVER OPENED. [DatabaseRescue] exists because this file's schema may be
 * newer than anything the running app can read; parsing it here would be the
 * same defect one layer up. Every field below comes from a directory listing
 * and a file length, never a byte decoded from inside a database file. [zipTo]
 * is the one exception, and it is not one: it copies bytes through unread and
 * uninterpreted, the same way [SetJournalStore.zip] already copies a capture's
 * raw CSVs -- the constraint this class exists to honour is against parsing
 * the file AS a database, not against moving its bytes.
 *
 * [files] names exactly what is present, because a rescue interrupted partway
 * -- see [DatabaseRescue.rescue]'s own KDoc -- can leave a directory holding
 * only a sidecar, or only the main file. Nothing here may assume a directory
 * is a complete database; [completeness] is how a caller checks rather than
 * assumes.
 */
data class RescuedDatabase(
    val directory: File,
    val rescuedAtMs: Long?,
    val files: List<String>,
    val totalBytes: Long,
) {
    /**
     * Whether the main database file itself is among [files].
     *
     * False is a real, reachable state, not a hypothetical: a rescue
     * interrupted after a sidecar moved but before the main file's move
     * leaves that sidecar here alone, with the main file moved by the retry
     * on the next launch into a DIFFERENT `db-<ms>` directory. A reader must
     * not describe a directory without the main file as a complete database.
     */
    val hasDatabaseFile: Boolean get() = DATABASE_NAME in files

    /**
     * True when [files] is empty -- reachable on its own, not only alongside
     * [hasDatabaseFile]: a rescue whose only source file is the main database
     * (the common case, no un-checkpointed WAL) leaves nothing behind at all
     * if that one move itself fails, since [DatabaseRescue.rescue]'s loop
     * attempts nothing else. `mkdirs()` still succeeded, so the directory
     * exists and this store still finds it -- an orphan with nothing worth
     * reading and nothing worth protecting, which is exactly the distinction
     * a caller needs to treat it differently from a genuine partial rescue.
     */
    val isEmpty: Boolean get() = files.isEmpty()

    /**
     * Which of the three states this directory is in, as one named value --
     * the only thing a reader should switch on. See [RescueCompleteness]'s
     * own KDoc for why the split is computed here and not at each call site.
     */
    val completeness: RescueCompleteness get() = when {
        hasDatabaseFile -> RescueCompleteness.COMPLETE
        isEmpty -> RescueCompleteness.NOTHING
        else -> RescueCompleteness.FRAGMENT
    }
}

/**
 * Where rescued databases are found, sized, sent off the phone, and
 * discarded -- the read half of [DatabaseRescue], which only ever writes.
 *
 * Issue #111. Reuses [SetJournalStore]'s shape for the interrupted-capture
 * card: a directory scan, an archive of exactly the files present, and a
 * discard at the lifter's word and never on a timer.
 *
 * [zipTo] WRITES STRAIGHT TO A FILE RATHER THAN RETURNING BYTES, and this is
 * not a style choice. [SetJournalStore.zip] returns a ByteArray because a set
 * journal is on the order of hundreds of kilobytes; a rescued corpus is
 * explicitly unbounded by design (N downgrades, N corpora, [DatabaseRescue]'s
 * own KDoc), already stated there to reach "tens of megabytes" for a single
 * mature history. Measured on the JDK this project builds with, scanning
 * heap size against corpus size through the identical ByteArrayOutputStream
 * + toByteArray() code path [SetJournalStore.zip] and `ShareUtil.shareFile`
 * use: a 192 MB heap holds a 60 MB corpus but exhausts outright on an 80 MB
 * one, well inside what this design already expects a mature history to
 * reach. Streaming through [ZipOutputStream] over a [FileOutputStream] costs
 * only its own internal buffers, a few tens of kilobytes, regardless of
 * corpus size: the same 80 MB corpus, and a 200 MB one beyond it, both
 * streamed clean on a 32 MB heap in the same measurement, with no observable
 * heap growth either time. The corpus has no ceiling by design, so the code
 * that moves it must not have one either. (A single "peak multiplier" figure
 * was measured here too and removed: it did not reproduce under a second,
 * independent measurement approach, and the two threshold facts above do not
 * depend on it.)
 */
class RescuedDatabaseStore(
    private val root: File,
    private val zipCompressionLevel: Int = Deflater.BEST_SPEED,
) {
    /**
     * Every rescued database on disk. Never opens one.
     *
     * ORDERED BY RISK, NOT ONLY BY TIME: a complete rescue first, then a
     * partial one that may hold data nothing else has, then an empty one
     * that holds nothing at all -- oldest first within each group. Sidecars
     * move before the main file ([DatabaseRescue.rescue]'s own KDoc states
     * why), so a partial rescue's directory is always dated EARLIER than the
     * complete directory the retry eventually produces; sorting on time
     * alone would put the ambiguous, harder-to-read card above the safe one,
     * which is the shape a wrong tap is likeliest to happen in. A directory
     * whose name will not parse sorts to the END of its group rather than to
     * the front on a `0L` fallback -- unable to date something is not a
     * reason to make it look oldest and most disposable.
     */
    fun rescued(): List<RescuedDatabase> = root.listFiles().orEmpty()
        .filter { it.isDirectory }
        .map { dir -> read(dir) }
        .sortedWith(compareBy({ it.completeness.riskOrder }, { it.rescuedAtMs ?: Long.MAX_VALUE }))

    /**
     * The rescued database is off the phone or discarded; this directory can
     * go. True when nothing of it is left -- which includes a directory that
     * was already gone, since that is the question a caller has ("is it
     * gone?") and not "did this call do the removing?".
     *
     * RETURNS WHETHER IT IS ACTUALLY GONE rather than dropping
     * `deleteRecursively`'s own Boolean on the floor. That function removes
     * what it can and reports false if any single entry survived, so a caller
     * ignoring it cannot tell a clean removal from a partial one -- and a
     * partial one is not benign here. Deleting the main file while a sidecar
     * survives turns a [RescueCompleteness.COMPLETE] directory into a
     * [RescueCompleteness.FRAGMENT], which this store then re-reads and a
     * card re-titles as possibly holding data the live database is missing.
     * After a deliberate discard that reading is false, and a caller never
     * told the discard failed has no way to know it.
     *
     * THE MECHANISM IS NOT PROVEN ON THE PLATFORM THIS SHIPS TO. A partial
     * delete was produced on Windows by holding an open handle to a sidecar;
     * that route does not exist on Android, where an unlink succeeds on an
     * open file. The Android-reachable route -- the process being killed
     * partway through `deleteRecursively`'s own bottom-up walk -- was not
     * tested and cannot be from here. What is claimed is only that this
     * function now reports what the filesystem told it; whether Android ever
     * makes it report false is a field question, not a tested one.
     */
    fun discard(item: RescuedDatabase): Boolean = item.directory.deleteRecursively()

    /**
     * Every file [item] holds, written to [destination] as a zip -- the whole
     * directory, not only the main database. A `.db` alone can look complete
     * and be stale: its `-wal` can hold committed transactions the main file
     * does not yet have. Streamed straight to disk; see this class's own
     * KDoc for why that is load-bearing rather than incidental. Uncompressed
     * intent, unconverted content: each file's bytes are copied through
     * exactly as they lie, the same as [SetJournalStore.zip], because
     * nothing here can verify what it would be converting. BEST_SPEED,
     * matching [RawExporter.buildZip]'s own reasoning from issue #29 on the
     * only other zip this app streams at comparable scale -- this is the
     * largest one it ever builds, 30 to 150 times the size that call was
     * measured against, so leaving the level unset here would have been the
     * one place that reasoning was not applied.
     *
     * A FAILED READ FAILS THE WHOLE ARCHIVE, LOUDLY -- an earlier version of
     * this function caught a read failure per file and moved on, matching
     * [SetJournalStore.zip]'s own shape, and that shape does not transfer
     * here. `putNextEntry` writes the zip entry's header before a single
     * byte of the file is read, so catching a mid-copy failure and
     * continuing left a STRUCTURALLY VALID zip holding a truncated or
     * zero-byte database: an archive that opens cleanly and looks complete
     * while the lifter's actual backup is gone. A partial CSV capture is
     * still independently useful, which is why [SetJournalStore.zip] can
     * afford to skip one and keep going; a partial database file is not, so
     * this throws instead.
     *
     * THE PARTIAL OUTPUT IS REMOVED ON ANY FAILURE, NOT ONLY AN IOException.
     * That cleanup used to live in `catch (e: IOException)`, which left the
     * half-written archive on disk for anything thrown that was not one -- a
     * SecurityException from opening a file, or any Error -- restoring, by
     * the shape of a catch clause, the exact outcome the paragraph above
     * exists to make unreachable. A `finally` guarded by a completion flag
     * covers every exit instead.
     *
     * NOTHING PINS THAT WIDENING. Reverting to the exact IOException-only
     * shape leaves this module's suite green; only the completion flag and
     * the cleanup itself are covered. The measurements are in this commit's
     * own body.
     */
    fun zipTo(item: RescuedDatabase, destination: File) {
        var complete = false
        try {
            FileOutputStream(destination).use { fileOut ->
                ZipOutputStream(fileOut).apply { setLevel(zipCompressionLevel) }.use { zip ->
                    item.directory.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }.forEach { file ->
                        zip.putNextEntry(ZipEntry(file.name))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            }
            complete = true
        } finally {
            if (!complete) destination.delete()
        }
    }

    /**
     * One rescued directory, described from what the filesystem alone says.
     *
     * [RescuedDatabase.rescuedAtMs] is null rather than the directory being
     * dropped when its name does not parse as `db-<ms>` -- absence rendered
     * as absence, in a store whose whole purpose is telling the lifter
     * something exists. A directory this code cannot date is still a
     * directory taking up space; hiding it would recreate the exact
     * invisibility issue #111 exists to fix.
     */
    private fun read(dir: File): RescuedDatabase {
        val files = dir.listFiles().orEmpty().filter { it.isFile }
        return RescuedDatabase(
            directory = dir,
            rescuedAtMs = dir.name.takeIf { it.startsWith(DatabaseRescue.DIR_PREFIX) }
                ?.removePrefix(DatabaseRescue.DIR_PREFIX)?.toLongOrNull(),
            files = files.map { it.name }.sorted(),
            totalBytes = files.sumOf { it.length() },
        )
    }
}
