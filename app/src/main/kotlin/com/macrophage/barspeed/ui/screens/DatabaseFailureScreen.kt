package com.macrophage.barspeed.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.macrophage.barspeed.data.DATABASE_NAME
import com.macrophage.barspeed.data.DatabaseRescue
import com.macrophage.barspeed.ui.ShareUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * What the app draws instead of crashing when its database will not open.
 * UX review 2026-11-06, data-layer F3.
 *
 * Reached two ways: the dependency container failing in
 * `LiftingApp.onCreate` (a rescue or a restore that could not finish), and
 * Home's history flow failing on Room's lazy first open (a migration that
 * throws). Before this both were a crash on every launch, and the CRASH
 * REPORT card on Home could never be tapped because Home died as it drew.
 *
 * ONE BUTTON, AND IT NEVER OPENS THE DATABASE: it zips the files at the
 * database's path and everything in `files/rescued/`, byte for byte, and
 * hands them to the share sheet.
 *
 * Compile- and lint-gated only; nothing here is test-gated.
 */
@Composable
fun DatabaseFailureScreen(error: String) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var sending by remember { mutableStateOf(false) }
    var sendError by remember { mutableStateOf<String?>(null) }
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("BarSpeed can't open its database", style = MaterialTheme.typography.headlineSmall)
            Text("Your training history is still on the phone. Send the database to yourself to keep a copy.")
            Button(
                enabled = !sending,
                onClick = {
                    scope.launch {
                        sending = true
                        sendError = null
                        try {
                            ShareUtil.shareStreamed(context, "barspeed-database.zip", "application/zip") {
                                zipDatabaseFiles(context, it)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            sendError = "Couldn't send it: ${e.message ?: e::class.simpleName}"
                        } finally {
                            sending = false
                        }
                    }
                },
            ) { Text("SEND DATABASE") }
            sendError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text(error, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
}

/**
 * The database's main file and whichever of its sidecars exist, plus
 * everything under `files/rescued/` under a `rescued/` prefix, zipped into
 * [destination] as they lie. A partial archive is deleted rather than left
 * looking whole, as `RescuedDatabaseStore.zipTo` does for the same reason.
 *
 * WHY THE RESCUE DIRECTORY TOO: the launch-time route here is a rescue cut
 * off or a restore stuck partway, and in both the history can be SPLIT --
 * a `-wal` in a `db-<ms>` folder, the main file at the path, the journal
 * saying which is which. Sending only the path would send half of it.
 * Review of 14a1713.
 */
private fun zipDatabaseFiles(context: Context, destination: File) {
    val main = context.getDatabasePath(DATABASE_NAME)
    val files = listOf("", "-wal", "-shm", "-journal").map { File(main.path + it) }.filter { it.isFile }
    val rescueRoot = File(context.filesDir, DatabaseRescue.RESCUE_DIR)
    val rescued = rescueRoot.walkTopDown().filter { it.isFile }.sortedBy { it.path }.toList()
    if (files.isEmpty() && rescued.isEmpty()) {
        throw IOException("no database file at ${main.path} and nothing in ${rescueRoot.path}")
    }
    var complete = false
    try {
        ZipOutputStream(FileOutputStream(destination)).use { zip ->
            for (file in files) {
                zip.putNextEntry(ZipEntry(file.name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            for (file in rescued) {
                val name = file.relativeTo(rescueRoot).invariantSeparatorsPath
                zip.putNextEntry(ZipEntry("${DatabaseRescue.RESCUE_DIR}/$name"))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        complete = true
    } finally {
        if (!complete) destination.delete()
    }
}
