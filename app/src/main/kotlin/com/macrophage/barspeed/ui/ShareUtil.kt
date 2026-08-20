package com.macrophage.barspeed.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Shares exports via the system share sheet using a cache-backed FileProvider URI. */
object ShareUtil {
    /**
     * suspend, not a plain function, so the blocking write below cannot land
     * back on Main by a caller forgetting to wrap it -- the same shape issue
     * #29 fixed for SessionExporter/RawExporter, applied to the one file
     * write this issue's own filed body also named. Only the write is
     * wrapped in Dispatchers.IO; the Intent/chooser building and
     * startActivity resume on whatever dispatcher called this, unchanged
     * from before, matching SessionDetailViewModel.savePendingTo's existing
     * shape of wrapping only the blocking part.
     *
     * Every existing caller's payload is bounded -- a session export, a
     * single set's raw zip -- so holding it as a ByteArray before this
     * write is inexpensive. [shareStreamed] is the variant for a payload
     * with no such bound; see its own KDoc.
     */
    suspend fun shareFile(context: Context, fileName: String, bytes: ByteArray, mimeType: String) {
        val file =
            withContext(Dispatchers.IO) {
                val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                File(dir, fileName).apply { writeBytes(bytes) }
            }
        shareCachedFile(context, fileName, file, mimeType)
    }

    suspend fun shareJson(context: Context, fileName: String, json: String) =
        shareFile(context, fileName, json.toByteArray(Charsets.UTF_8), "application/json")

    /**
     * Shares a payload [write] produces directly into the cache file, rather
     * than a ByteArray this function writes out itself.
     *
     * Issue #111: a rescued database's zip has no bound by design -- N
     * downgrades leave N corpora, each potentially tens of megabytes,
     * nothing expires them -- unlike every existing caller of [shareFile],
     * whose payload this codebase already knows the size of. Holding an
     * unbounded payload as a ByteArray is what `RescuedDatabaseStore.zipTo`
     * exists to avoid on the :core:data side; this is that avoidance
     * carried through to the share step, so the two halves are not undone
     * by meeting back at a single in-memory buffer here. [write] runs on
     * Dispatchers.IO, same as [shareFile]'s own blocking write.
     *
     * THE CACHE FILE IS A SECOND FULL-SIZE COPY AND NOTHING HERE REMOVES IT,
     * which inverts `DatabaseRescue`'s own "MOVE, NOT COPY. A copy doubles
     * peak storage" on the one payload this codebase says is unbounded. Said
     * out loud rather than left as an unstated exception. It is not silent
     * data loss -- the copy is in cacheDir, which Android reclaims under
     * storage pressure, and a write that runs out of room fails loudly
     * through the caller's own handler. The fix is declined for one reason,
     * and only this one: bounding it properly needs the share result, which
     * this function does not have.
     *
     * THE SECOND REASON THIS DECLINE USED TO GIVE IS DELETED, BECAUSE THE
     * CODE ON THIS PATH ALREADY DOES THE THING IT CALLED IMPOSSIBLE. It said
     * every cleanup available here would delete a file the share sheet may
     * still be streaming from. [fileName] is stable across taps, so a second
     * send reopens the same cache path: `RescuedDatabaseStore.zipTo` opens
     * it with a `FileOutputStream`, which truncates on open, and its own
     * `finally` deletes it if that second build fails. Measured against the
     * real compiled class: a complete 15,394-byte archive at that path was
     * gone after a second `zipTo` on the same destination was made to fail.
     * That is the same shape [shareFile]'s truncating `writeBytes` has had
     * for every other export in this app since long before issue #111, so it
     * is named here and left alone rather than fixed inside this change --
     * but it is not a reason to decline a cleanup that does not exist yet.
     */
    suspend fun shareStreamed(context: Context, fileName: String, mimeType: String, write: (File) -> Unit) {
        val file =
            withContext(Dispatchers.IO) {
                val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                File(dir, fileName).also(write)
            }
        shareCachedFile(context, fileName, file, mimeType)
    }

    /** The share-sheet Intent, common to [shareFile] and [shareStreamed] once each has its cache file. */
    private fun shareCachedFile(context: Context, fileName: String, file: File, mimeType: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent =
            Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                // ClipData is what actually propagates the URI grant to the chosen app.
                clipData = ClipData.newRawUri(fileName, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        val chooser =
            Intent.createChooser(intent, fileName).apply {
                // Callers pass the Application context (ViewModels); launching an
                // activity from a non-Activity context requires NEW_TASK.
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        context.startActivity(chooser)
    }
}
