package com.macrophage.barspeed.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/** Shares exports via the system share sheet using a cache-backed FileProvider URI. */
object ShareUtil {
    fun shareFile(context: Context, fileName: String, bytes: ByteArray, mimeType: String) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, fileName)
        file.writeBytes(bytes)
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

    fun shareJson(context: Context, fileName: String, json: String) =
        shareFile(context, fileName, json.toByteArray(Charsets.UTF_8), "application/json")
}
