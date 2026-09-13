package com.macrophage.barspeed

import com.macrophage.barspeed.model.CrashLogPolicy
import com.macrophage.barspeed.model.CrashRecord
import java.io.File

/**
 * One crash file, as a card can draw it WITHOUT opening it. Issue #272.
 *
 * [label] comes from the file name and [bytes] from the directory entry, so
 * building this costs no read. That is #271's lesson carried into the list:
 * the app died at launch because a directory listing decoded the files it was
 * listing.
 */
data class CrashReport(val file: File, val label: String, val bytes: Long)

/**
 * The crash files on this phone: written by [CrashHandler], listed for the
 * card on Home, pruned to the newest ten, and copied out when the owner
 * shares one. Issue #272.
 *
 * NOT TEST-GATED, and said plainly rather than implied: nothing in this
 * repository can install an uncaught-exception handler, watch a process die,
 * or touch Android's filesystem. What IS test-gated is every decision this
 * class makes -- the file name, the ordering, which files are ours, and which
 * ones the pruner may delete -- because all four live in `CrashLogPolicy` in
 * :core:model and are pinned by `CrashLogPolicyTest`. This class is the
 * filesystem around those decisions and is compile- and lint-gated only.
 *
 * NOTHING HERE LEAVES THE PHONE. There is no network call on this path; the
 * only way a crash file goes anywhere is the owner pressing SEND IT TO ME and
 * choosing a target in the system share sheet.
 */
class CrashLogStore(private val root: File) {
    /**
     * The crash files, newest first, capped at [CrashLogPolicy.KEEP].
     *
     * The cap is applied here as well as in [prune] on purpose. A directory
     * that somehow holds more than ten -- a prune that failed, a crash storm
     * during a write -- must not draw eleven cards, and the card list and the
     * pruner must agree about which ten are the ten. They agree because both
     * ask `CrashLogPolicy`.
     *
     * Never throws: an unreadable directory lists as empty. This is called
     * from the first screen of a cold launch, which is exactly where #271
     * showed what a throwing scan costs.
     */
    fun list(): List<CrashReport> = runCatching {
        val names = root.list()?.toList() ?: emptyList()
        CrashLogPolicy.toKeep(names).mapNotNull { name ->
            val label = CrashLogPolicy.labelFor(name) ?: return@mapNotNull null
            val file = File(root, name)
            CrashReport(file = file, label = label, bytes = file.length())
        }
    }.getOrDefault(emptyList())

    /**
     * Write one crash report and prune to the newest ten. Returns the file, or
     * null if anything at all went wrong.
     *
     * SWALLOWS EVERYTHING. This runs on a thread that is already dying, and
     * the one outcome that must not happen is this code throwing on the way
     * out and displacing the crash the report is about. A crash report that
     * failed to save is a lost diagnostic; a handler that threw would be a
     * lost stack trace, which is worse and is the thing #272 exists to end.
     */
    fun write(record: CrashRecord): File? = runCatching {
        root.mkdirs()
        val file = File(root, CrashLogPolicy.fileName(record.atMs))
        file.writeText(record.render())
        prune()
        file
    }.getOrNull()

    /**
     * Delete everything past the newest ten.
     *
     * The list of what to delete is `CrashLogPolicy.toDelete`'s and nothing
     * else's, so a file this app did not write is never a candidate however
     * full the directory is.
     */
    fun prune() {
        val names = root.list()?.toList() ?: return
        for (name in CrashLogPolicy.toDelete(names)) {
            runCatching { File(root, name).delete() }
        }
    }

    /**
     * Delete one crash report at the owner's word.
     *
     * Guarded twice -- the name must be one this app writes, and the file must
     * be directly under [root] -- because this is a delete reached from a
     * button, and the argument arrives from a list this class did not build a
     * second time.
     */
    fun delete(report: CrashReport): Boolean {
        val name = report.file.name
        if (!CrashLogPolicy.isCrashFile(name)) return false
        if (report.file.parentFile?.absolutePath != root.absolutePath) return false
        return runCatching { report.file.delete() }.getOrDefault(false)
    }

    /**
     * Copy one crash report into the share cache, with a hard ceiling.
     *
     * A crash file is bounded by construction -- `CrashLogRing` bounds the log
     * and `CrashRecord.stackText` bounds the stack -- so this ceiling should
     * never be reached. It is here anyway because #273 is the same shape one
     * step along: `SetJournalStore.zip` reads every stream whole and is
     * expected to run out of heap on an oversize journal. A share path that
     * trusts a file to be small is exactly that defect, and the trust is
     * cheap to remove.
     *
     * Streamed rather than read into a `ByteArray`, through
     * [com.macrophage.barspeed.ui.ShareUtil.shareStreamed], for the same
     * reason issue #111 gave for the rescued-database archive.
     */
    fun copyBounded(source: File, destination: File) {
        destination.outputStream().use { out ->
            source.inputStream().use { input ->
                val buffer = ByteArray(BUFFER_BYTES)
                var written = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    val room = MAX_SHARE_BYTES - written
                    if (room <= 0L) {
                        out.write(TRUNCATION_NOTICE.toByteArray(Charsets.UTF_8))
                        return
                    }
                    val take = minOf(read.toLong(), room).toInt()
                    out.write(buffer, 0, take)
                    written += take
                }
            }
        }
    }

    companion object {
        /** The ceiling on a shared crash file, far above anything the writer can produce. */
        const val MAX_SHARE_BYTES = 1_000_000L

        private const val BUFFER_BYTES = 8 * 1024

        private const val TRUNCATION_NOTICE =
            "\n--- TRUNCATED: this crash file is larger than the share ceiling ---\n"
    }
}
