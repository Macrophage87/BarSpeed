package com.macrophage.barspeed.ui

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
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        context.startActivity(Intent.createChooser(intent, fileName))
    }

    fun shareJson(context: Context, fileName: String, json: String) =
        shareFile(context, fileName, json.toByteArray(Charsets.UTF_8), "application/json")
}
