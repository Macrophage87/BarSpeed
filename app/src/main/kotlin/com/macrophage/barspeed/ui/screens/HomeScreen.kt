package com.macrophage.barspeed.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.macrophage.barspeed.CrashReport
import com.macrophage.barspeed.data.DATABASE_VERSION
import com.macrophage.barspeed.data.InterruptedSetSummary
import com.macrophage.barspeed.data.OrphanedSet
import com.macrophage.barspeed.data.RescuedDatabase
import com.macrophage.barspeed.data.SessionEntity
import com.macrophage.barspeed.model.ArmedDelivery
import com.macrophage.barspeed.model.ByteSize
import com.macrophage.barspeed.model.ConnectionState
import com.macrophage.barspeed.model.JournalRecoveryPolicy
import com.macrophage.barspeed.model.RecordingHold
import com.macrophage.barspeed.model.RecoveryRefusal
import com.macrophage.barspeed.model.RestoreVerdict
import com.macrophage.barspeed.model.WeightUnit
import com.macrophage.barspeed.ui.BarColors
import com.macrophage.barspeed.ui.components.PermissionBanner
import com.macrophage.barspeed.ui.components.SectionCaption
import com.macrophage.barspeed.ui.components.SensorDot
import com.macrophage.barspeed.ui.components.Sparkline
import com.macrophage.barspeed.ui.components.StatTile
import com.macrophage.barspeed.ui.components.rememberArmedDelivery
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val KG_PER_TONNE = 1000.0

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(navController: NavController, viewModel: HomeViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val imuState by viewModel.imuState.collectAsState()
    val imuFrameAtMs by viewModel.imuFrameAtMs.collectAsState()
    val imuArmedAtMs by viewModel.imuArmedAtMs.collectAsState()
    val hrmState by viewModel.hrmState.collectAsState()
    val interrupted by viewModel.interrupted.collectAsState()
    val rescued by viewModel.rescued.collectAsState()
    val busyRescues by viewModel.busyRescues.collectAsState()
    val restoreVerdicts by viewModel.restoreVerdicts.collectAsState()
    val recordingHolds by viewModel.recordingHolds.collectAsState()
    val busyInterrupted by viewModel.busyInterrupted.collectAsState()
    val recoveryRefusals by viewModel.recoveryRefusals.collectAsState()
    val busyCrashReports by viewModel.busyCrashReports.collectAsState()
    val crashReports by viewModel.crashReports.collectAsState()
    val exportAllProgress by viewModel.exportAllProgress.collectAsState()
    val exportAllMessage by viewModel.exportAllMessage.collectAsState()
    // One picker for the whole archive (history-export F5): a single zip the
    // lifter names and places once, holding every session's raw zip.
    val exportAllLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            viewModel.exportAllTo(uri)
        }
    // Older sessions are drawn only on request, and the choice survives a
    // rotation; Home's own figures never read them.
    var showOlder by rememberSaveable { mutableStateOf(false) }
    // UX review 2026-11-06, data-layer F3: a database that will not open
    // gets one screen, with the way to send it, and nothing that reads it.
    state.databaseError?.let { error ->
        DatabaseFailureScreen(error)
        return
    }
    // Re-scanned every time this screen is composed, not only on first launch:
    // a set interrupted by a crash that left the process alive would otherwise
    // stay invisible until the app was killed and started again. The same
    // reasoning applies to a rescue: it happens once, at AppDatabase.build,
    // before this screen exists to be told about it.
    LaunchedEffect(Unit) {
        viewModel.refreshInterrupted()
        viewModel.refreshRescued()
        // Re-scanned on every composition of this screen for a reason the
        // other two do not have: a crash report is written by a process that
        // is dying, so the ONLY way it can ever appear is a later launch, or a
        // return to Home after a crash that left the process alive.
        viewModel.refreshCrashReports()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("BarSpeed") },
                actions = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(end = 8.dp),
                    ) {
                        // Volt on the IMU dot means frames are arriving,
                        // not that a link is up (#213). The strap's dot is
                        // unchanged: nothing observes its delivery.
                        val imuDelivery = rememberArmedDelivery(imuState, imuFrameAtMs, imuArmedAtMs)
                        HomeSensorDot("IMU", imuState, imuDelivery) { navController.navigate("devices") }
                        HomeSensorDot("HRM", hrmState, null) { navController.navigate("devices") }
                        // Kept a TextButton (#201): a setting in the app bar's action slot, beside
                        // the sensor dots, not a next step.
                        TextButton(onClick = viewModel::toggleWeightUnit) {
                            Text("${state.weightUnit.suffix} ⇄", color = BarColors.Sub)
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
        ) {
            // Above the hero card because this is the first screen of every
            // cold launch and the one the first permission dialog opens over.
            // Deliberately compact: this Column has no verticalScroll and the
            // history LazyColumn below is unweighted, so anything added here
            // comes out of the history list with no way to scroll it back.
            PermissionBanner()
            // Above InterruptedSetNotice: a rescued database can be an entire
            // training history, where an interrupted capture is one set.
            RescuedDatabaseNotice(
                rescued,
                busyRescues,
                restoreVerdicts,
                recordingHolds,
                viewModel::shareRescued,
                viewModel::discardRescued,
                viewModel::restoreRescued,
            )
            InterruptedSetNotice(
                interrupted,
                busyInterrupted,
                recoveryRefusals,
                viewModel::shareInterrupted,
                viewModel::discardInterrupted,
                viewModel::recoverInterrupted,
            )
            // Below both: a crash report is a diagnostic about the app, where
            // the two cards above it are the lifter's own data.
            CrashReportNotice(crashReports, busyCrashReports, viewModel::shareCrashReport, viewModel::deleteCrashReport)
            HeroCard(state) { navController.navigate("record") }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                StatTile(
                    label = "Week volume",
                    value = volumeValue(state.weekVolumeKg, state.weightUnit),
                    unit = volumeUnit(state.weightUnit),
                    sub = "last 7 days",
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = "Sessions",
                    value = "${state.weekSessions}",
                    sub = "this week",
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { navController.navigate("devices") },
                    modifier = Modifier.weight(1f),
                    contentPadding = ButtonDefaults.TextButtonContentPadding,
                ) {
                    Text("Devices", color = BarColors.Sub)
                }
                OutlinedButton(
                    onClick = { navController.navigate("plans") },
                    modifier = Modifier.weight(1f),
                    contentPadding = ButtonDefaults.TextButtonContentPadding,
                ) {
                    Text("Plans", color = BarColors.Sub)
                }
                OutlinedButton(
                    onClick = { navController.navigate("guide") },
                    modifier = Modifier.weight(1f),
                    contentPadding = ButtonDefaults.TextButtonContentPadding,
                ) {
                    Text("Guide", color = BarColors.Sub)
                }
            }
            Spacer(Modifier.height(6.dp))
            HistoryHeader(
                hasSessions = state.history.isNotEmpty(),
                progress = exportAllProgress,
                message = exportAllMessage,
            ) { exportAllLauncher.launch(viewModel.allSessionsArchiveName()) }
            Spacer(Modifier.height(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.history) { row ->
                    HistoryCard(row) { navController.navigate("session/${row.session.id}") }
                }
                // History beyond the first 20 (history-export F5): opened on
                // request so a session of any age can be opened and exported.
                if (state.olderSessions.isNotEmpty()) {
                    if (showOlder) {
                        items(state.olderSessions) { session ->
                            OlderSessionCard(session) { navController.navigate("session/${session.id}") }
                        }
                    } else {
                        item {
                            TextButton(onClick = { showOlder = true }, modifier = Modifier.fillMaxWidth()) {
                                val count = state.olderSessions.size
                                Text(
                                    if (count == 1) "Show 1 older session" else "Show $count older sessions",
                                    color = BarColors.Sub,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * [SensorDot] with a click, issue #200. Tapping either sensor's status dot
 * opens the Devices screen -- both IMU and HRM go to the same place, since
 * Devices is one screen showing both kinds and there is no per-role route to
 * pick.
 *
 * The click lives HERE, at the Home call site, rather than inside [SensorDot]
 * itself. [SensorDot] is drawn five times in this tree: twice here and three
 * times in `RecordScreen.kt`, mid-set, where navigating away to Devices would
 * abandon a set in progress -- adding the click to the shared component would
 * make it wrong at three of its five call sites. Not every site wants it, so
 * only the site that does carries it.
 *
 * `sizeIn(minWidth/minHeight = 48.dp)` gives the tap target this app's stated
 * 48dp floor (see `rpeOptions`' own KDoc in RecordScreen.kt for where that
 * floor comes from) without drawing the dot any bigger -- the touch target
 * and the visual size are kept deliberately separate, by wrapping rather than
 * resizing. `Modifier.clickable`'s default ripple is the only visual change:
 * no louder affordance, since these dots are a status indicator and #181's
 * whole point was not letting this screen nag.
 */
@Composable
private fun HomeSensorDot(label: String, state: ConnectionState, delivery: ArmedDelivery?, onClick: () -> Unit) {
    Box(
        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        SensorDot(label, state, delivery = delivery)
    }
}

@Composable
private fun HeroCard(state: HomeState, onStart: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.linearGradient(listOf(BarColors.HeroGreen, BarColors.Surface)),
                shape,
            )
            .border(1.dp, BarColors.Volt.copy(alpha = 0.2f), shape)
            .padding(14.dp),
    ) {
        if (state.planName != null) {
            SectionCaption("Active plan", color = BarColors.Volt)
            Text(
                state.planName,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            Text(
                // "distinct exercises", not bare "exercises": this counts
                // DISTINCT movement ids across the whole plan, while the
                // record screen's "Up next" summary and the session picker
                // card both say "exercises" for a BLOCK count scoped to one
                // session -- two different questions sharing one word would
                // read as one number to a lifter comparing them (#227 item
                // 3). See HomeState.planExerciseCount's KDoc.
                "${state.planSessionCount} sessions · ${state.planExerciseCount} distinct exercises · " +
                    "${state.planSetCount} sets",
                style = MaterialTheme.typography.bodySmall,
                color = BarColors.Sub,
            )
        } else {
            SectionCaption("No active plan", color = BarColors.Volt)
            Text(
                "Train ad-hoc or import a plan",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            Text(
                "Import a JSON plan from Claude on the Plans screen.",
                style = MaterialTheme.typography.bodySmall,
                color = BarColors.Sub,
            )
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Text("START SESSION", style = MaterialTheme.typography.titleMedium)
        }
    }
}

/**
 * The History caption with "Export all" beside it (history-export F5), and,
 * under it, the export's progress while it runs and its result line after.
 * Kept to one line in the common case: this Column does not scroll, so every
 * line here comes out of the history list.
 */
@Composable
private fun HistoryHeader(
    hasSessions: Boolean,
    progress: ExportAllProgress?,
    message: String?,
    onExportAll: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth(),
    ) {
        SectionCaption("History")
        if (hasSessions) {
            TextButton(onClick = onExportAll, enabled = progress == null) {
                Text("Export all sessions", color = BarColors.Sub)
            }
        }
    }
    if (progress != null) {
        if (progress.total > 0) {
            val fraction = progress.done.toFloat() / progress.total
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        } else {
            // The session count is not known yet: indeterminate, not a bar at 0.
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Text(
            if (progress.total > 0) "Exporting ${progress.done} of ${progress.total} sessions" else "Exporting",
            style = MaterialTheme.typography.bodySmall,
            color = BarColors.Sub,
        )
    } else if (message != null) {
        Text(message, style = MaterialTheme.typography.bodySmall, color = BarColors.Sub)
    }
}

/**
 * A session past the first 20: name and start only. Nothing about its sets is
 * read, which is what keeps Home's launch cost at the first 20 however long
 * the history is; its detail screen shows the rest and exports it.
 */
@Composable
private fun OlderSessionCard(session: SessionEntity, onClick: () -> Unit) {
    val formatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm")
    val started = Instant.ofEpochMilli(session.startedAtMs).atZone(ZoneId.systemDefault())
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(session.planSessionName ?: "Ad-hoc session", style = MaterialTheme.typography.titleSmall)
            Text(formatter.format(started), style = MaterialTheme.typography.bodySmall, color = BarColors.Sub)
        }
    }
}

@Composable
private fun HistoryCard(row: HistoryRow, onClick: () -> Unit) {
    val formatter = DateTimeFormatter.ofPattern("EEE d MMM")
    val started = Instant.ofEpochMilli(row.session.startedAtMs).atZone(ZoneId.systemDefault())
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(14.dp),
        ) {
            Column {
                Text(
                    row.session.planSessionName ?: "Ad-hoc session",
                    style = MaterialTheme.typography.titleSmall,
                )
                val parts =
                    listOfNotNull(
                        formatter.format(started),
                        "${row.setCount} sets",
                        row.heartRate.avgBpm?.let { "♥ $it avg" },
                        row.heartRate.hrvRmssdMs?.let { "HRV ${it.toInt()}" },
                    )
                Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = BarColors.Sub)
            }
            if (row.sparkline.size >= 2) {
                Sparkline(
                    row.sparkline,
                    color = if (row.session.planSessionName != null) BarColors.Volt else BarColors.Blue,
                )
            }
        }
    }
}

private fun volumeValue(volumeKg: Double, unit: WeightUnit): String = when (unit) {
    WeightUnit.KG -> String.format(Locale.US, "%.1f", volumeKg / KG_PER_TONNE)
    WeightUnit.LB -> String.format(Locale.US, "%.1f", volumeKg * WeightUnit.LB_PER_KG / KG_PER_TONNE)
}

private fun volumeUnit(unit: WeightUnit): String = when (unit) {
    WeightUnit.KG -> "t"
    WeightUnit.LB -> "k lb"
}

/**
 * Sets that were interrupted before they could be stored.
 *
 * Drawn above the hero card, and above everything else on this screen, because
 * a capture the lifter has not ruled on outranks starting a new session. It
 * costs two history rows when it appears: the Column it sits in has no scroll
 * and the history list below is unweighted. That trade is taken deliberately
 * and it is paid only in the rare case, since this draws nothing at all when
 * there is nothing to report.
 *
 * Not a [HistoryCard]. A history row carries a set count and a velocity
 * sparkline; an interrupted set has been through no analysis and has neither,
 * and rendering one as history would put invented figures beside real ones.
 *
 * The numbers shown are the ones that let the lifter check this against their
 * own memory of what happened: how many rows of each captured stream reached
 * the disk, and how much of the phone the capture is holding. A count is not
 * stated when no sensor was connected -- a count of zero would otherwise read
 * as a measurement, when what it means is that there was nothing to measure --
 * and [InterruptedSetSummary] in `:core:data` is where that ruling lives, so
 * it is pinned on every push rather than only drawn.
 *
 * A THIRD ACTION, PUT IT BACK (#100), writes the capture into History marked
 * recovered, behind a confirmation that says what is written and what is not
 * -- unlike DISCARD, it adds a set to the lifter's record, and the words are
 * `JournalRecoveryPolicy`'s so they are pinned rather than only drawn. Where
 * [refusals] says it cannot be put back, the card says why in its place.
 */
@Composable
private fun InterruptedSetNotice(
    interrupted: List<OrphanedSet>,
    busy: Set<File>,
    refusals: Map<File, RecoveryRefusal?>,
    onShare: (OrphanedSet) -> Unit,
    onDiscard: (OrphanedSet) -> Unit,
    onRecover: (OrphanedSet) -> Unit,
) {
    if (interrupted.isEmpty()) return
    var confirmRecover by remember { mutableStateOf<OrphanedSet?>(null) }
    Column(modifier = Modifier.fillMaxWidth()) {
        for (orphan in interrupted) {
            // #304: the same busy key RescuedDatabaseNotice already disables
            // both buttons on, so a second SEND cannot reopen the share-cache
            // file a first copy is still streaming into, and DISCARD cannot
            // remove a directory a send has not finished reading.
            val isBusy = orphan.directory in busy
            Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "INTERRUPTED SET",
                        style = MaterialTheme.typography.titleSmall,
                        color = BarColors.Amber,
                        letterSpacing = 2.sp,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(orphan.header.exerciseName, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        InterruptedSetSummary.lines(orphan).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = BarColors.Sub,
                    )
                    Spacer(Modifier.height(6.dp))
                    SectionCaption("Kept on this phone. Nothing deletes it but you")
                    refusals[orphan.directory]?.let { refusal ->
                        Spacer(Modifier.height(4.dp))
                        Text(
                            JournalRecoveryPolicy.refusalNote(refusal),
                            style = MaterialTheme.typography.bodySmall,
                            color = BarColors.Sub,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { onShare(orphan) },
                            enabled = !isBusy,
                            contentPadding = ButtonDefaults.TextButtonContentPadding,
                        ) {
                            Text("SEND IT TO ME", color = BarColors.Volt)
                        }
                        // Kept a TextButton (#201): destructive, so it stays quieter than the SEND
                        // beside it.
                        TextButton(onClick = { onDiscard(orphan) }, enabled = !isBusy) {
                            Text("DISCARD", color = BarColors.Red)
                        }
                    }
                    if (refusals[orphan.directory] == null) {
                        OutlinedButton(
                            onClick = { confirmRecover = orphan },
                            enabled = !isBusy,
                            contentPadding = ButtonDefaults.TextButtonContentPadding,
                        ) {
                            Text(JournalRecoveryPolicy.ACTION_LABEL, color = BarColors.Volt)
                        }
                    }
                }
            }
        }
    }
    confirmRecover?.let { orphan ->
        AlertDialog(
            onDismissRequest = { confirmRecover = null },
            title = { Text(JournalRecoveryPolicy.CONFIRM_TITLE) },
            text = { Text(JournalRecoveryPolicy.confirmBody(orphan.header.exerciseName)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRecover(orphan)
                        confirmRecover = null
                    },
                ) { Text(JournalRecoveryPolicy.CONFIRM_LABEL, color = BarColors.Volt) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRecover = null }) { Text("Cancel") }
            },
        )
    }
}

/**
 * Crash reports the app wrote about itself, with a way to send one and a way
 * to delete it. Issue #272.
 *
 * The same card shape as [InterruptedSetNotice] and [RescuedDatabaseNotice],
 * because this screen is already where app-private files the owner may want
 * off the phone are listed -- there is no separate export screen to extend.
 * #272 asks for the crash files to sit "beside the session exports"; there is
 * no list of session exports anywhere. Exports are per-session ACTIONS on
 * `SessionDetailScreen`, and these three notice cards are the only list of
 * shareable app-private artifacts this app has.
 *
 * COLLAPSED TO ONE CARD, however many reports there are, and that is a
 * constraint of this screen rather than a preference. [HomeScreen]'s Column
 * has no `verticalScroll` and the history `LazyColumn` below it is
 * unweighted, so a card per report would push the lifter's own history off
 * the bottom with no way to scroll it back -- and ten is the number the
 * pruner keeps. The newest is named, the rest are behind OLDER.
 *
 * THE CAPTION IS NOT [InterruptedSetNotice]'s. That card says "Nothing
 * deletes it but you", which is true of a set journal and FALSE here: the app
 * itself deletes the eleventh oldest crash report without asking. Copying the
 * caption across would have been a false claim on a card three lines long.
 *
 * TIMES ARE UTC, where [rescuedDetail] shows the device's own zone. Not an
 * oversight: the crash file's NAME and its first header line are both UTC, so
 * a card showing local time would give the owner a different string from the
 * one inside the file they are about to send, at the moment they are trying
 * to say which crash they mean. The trailing Z says which it is.
 *
 * Nothing here opens a crash file. `CrashLogStore.list` builds every string
 * on this card from the file name and the directory entry, which is #271's
 * lesson: that crash was a launch-time listing that decoded what it listed.
 */
@Composable
private fun CrashReportNotice(
    reports: List<CrashReport>,
    busy: Set<File>,
    onShare: (CrashReport) -> Unit,
    onDelete: (CrashReport) -> Unit,
) {
    val newest = reports.firstOrNull() ?: return
    var showOlder by remember { mutableStateOf(false) }
    val older = reports.drop(1)
    // #304: the same busy key the other two cards use, on SEND only -- a
    // crash file has a hard ceiling (CrashLogStore.copyBounded), so this is
    // belt-and-braces rather than a fix for an observed corruption.
    val newestBusy = newest.file in busy
    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(
                if (reports.size == 1) "CRASH REPORT" else "CRASH REPORTS (${reports.size})",
                style = MaterialTheme.typography.titleSmall,
                color = BarColors.Amber,
                letterSpacing = 2.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(newest.label, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(2.dp))
            Text(
                "${ByteSize.format(newest.bytes)} · the stack, the app version and the free memory",
                style = MaterialTheme.typography.bodySmall,
                color = BarColors.Sub,
            )
            Spacer(Modifier.height(6.dp))
            SectionCaption("Kept on this phone, newest ten. Nothing sends it but you")
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { onShare(newest) },
                    enabled = !newestBusy,
                    contentPadding = ButtonDefaults.TextButtonContentPadding,
                ) {
                    Text("SEND IT TO ME", color = BarColors.Volt)
                }
                // Kept a TextButton (#201): destructive, so it stays quieter than the SEND beside
                // it.
                TextButton(onClick = { onDelete(newest) }) {
                    Text("DELETE", color = BarColors.Red)
                }
                if (older.isNotEmpty()) {
                    // Kept a TextButton (#201): a disclosure toggle; it reveals the older reports
                    // rather than acting on one.
                    TextButton(onClick = { showOlder = !showOlder }) {
                        Text(if (showOlder) "HIDE OLDER" else "OLDER (${older.size})", color = BarColors.Sub)
                    }
                }
            }
            if (showOlder) {
                for (report in older) {
                    val isBusy = report.file in busy
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "${report.label} · ${ByteSize.format(report.bytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = BarColors.Sub,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(
                            onClick = { onShare(report) },
                            enabled = !isBusy,
                            contentPadding = ButtonDefaults.TextButtonContentPadding,
                        ) {
                            Text("SEND", color = BarColors.Volt)
                        }
                        // Kept a TextButton (#201): destructive, so it stays quieter than the SEND
                        // beside it.
                        TextButton(onClick = { onDelete(report) }) {
                            Text("DELETE", color = BarColors.Red)
                        }
                    }
                }
            }
        }
    }
}

/**
 * A database a downgrade moved aside rather than dropped. Issue #111.
 *
 * Same card shape as [InterruptedSetNotice], reused rather than reinvented
 * per that issue's own instruction -- but not its confirmation posture.
 * InterruptedSetNotice's DISCARD fires on tap alone: a set journal is
 * hundreds of kilobytes, and [SessionDetailScreen]'s own single-session
 * delete already sits behind a titled dialog naming what is lost, which a
 * set journal does not need a second copy of. A rescued database is the
 * opposite case -- potentially an entire training history, three to four
 * orders of magnitude larger, deletable in one tap from the FIRST screen
 * of every cold launch. [SessionDetailScreen]'s delete-one-session dialog
 * is the bar this meets, not [InterruptedSetNotice]'s silence; issue #111
 * asked for that card's SHAPE, not its confirmation posture.
 *
 * DISCARD IS OFFERED ON ALL THREE STATES, INCLUDING THE FRAGMENT, and this
 * is a reversal of the previous round's fix -- recorded rather than quietly
 * re-landed. That fix removed DISCARD from the partial-rescue card on the
 * reasoning that the card's own text says the file may be the only copy of
 * something. The reasoning was right; the remedy was not. With no DISCARD,
 * that card is PERMANENT: it is drawn on the first screen of every cold
 * launch, it holds a sidecar of no bounded size, and the lifter's only route
 * to reclaim the space is Android's "clear app data", which destroys the live
 * database along with it. A well-warned destroy is a better outcome than
 * that. The remedy for "it may be the only copy" is a dialog that says so --
 * see [rescuedDiscardWarning] -- not the removal of the only in-app way out.
 *
 * It also made [SectionCaption]'s "Nothing deletes it but you" FALSE on
 * exactly the state that could not act on it: the caption is drawn
 * unconditionally on all three cards, and the fragment card had no deleter at
 * all. There is one `deleteRecursively` under `rescued/`, in
 * `RescuedDatabaseStore.discard`, reached only from the confirm button below.
 * Restoring DISCARD makes the caption true everywhere it is drawn, which
 * closes that at the root rather than by editing the sentence.
 *
 * Never reads the rescued file itself -- [RescuedDatabase]'s fields are all
 * the filesystem alone can say, and this composable only formats them; see
 * `RescuedDatabaseStore`'s own KDoc for why that constraint holds even for
 * the Share action, which copies bytes through unread rather than parsing
 * them.
 */
@Composable
private fun RescuedDatabaseNotice(
    rescued: List<RescuedDatabase>,
    busy: Set<File>,
    restoreVerdicts: Map<File, RestoreVerdict>,
    held: Set<RecordingHold>,
    onShare: (RescuedDatabase) -> Unit,
    onDiscard: (RescuedDatabase) -> Unit,
    onRestore: (RescuedDatabase) -> Unit,
) {
    if (rescued.isEmpty()) return
    val clock = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    var confirmDiscard by remember { mutableStateOf<RescuedDatabase?>(null) }
    var confirmRestore by remember { mutableStateOf<RescuedDatabase?>(null) }

    // Every tap goes through rescuedTapEffect (RescuedCard.kt), where the
    // routing is pinned; this only carries out what it returns.
    fun onTap(effect: RescuedTapEffect) {
        when (effect) {
            is RescuedTapEffect.Share -> onShare(effect.item)
            is RescuedTapEffect.ConfirmDiscard -> confirmDiscard = effect.item
            is RescuedTapEffect.Discard -> onDiscard(effect.item)
            is RescuedTapEffect.ConfirmRestore -> confirmRestore = effect.item
            is RescuedTapEffect.Restore -> onRestore(effect.item)
        }
    }
    // Re-read at the dialog: a verdict that stopped being Restorable while it
    // was open hides the dialog rather than confirming a stale answer.
    confirmRestore?.let { item -> (restoreVerdicts[item.directory] as? RestoreVerdict.Restorable)?.let { item to it } }
        ?.let { (item, verdict) ->
            AlertDialog(
                onDismissRequest = { confirmRestore = null },
                title = { Text("Restore this rescued database?") },
                text = { Text(rescuedRestoreWarning(item, verdict.version, DATABASE_VERSION)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onTap(rescuedTapEffect(RescuedTap.CONFIRM_RESTORE, item))
                            confirmRestore = null
                        },
                    ) { Text("Restore", color = BarColors.Volt) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmRestore = null }) { Text("Cancel") }
                },
            )
        }
    confirmDiscard?.let { item ->
        AlertDialog(
            onDismissRequest = { confirmDiscard = null },
            title = { Text("Discard this rescued database?") },
            text = { Text(rescuedDiscardWarning(item)) },
            // Kept TextButtons (#201): this dialog's two actions; the dialog's frame already marks
            // them as its answers.
            confirmButton = {
                TextButton(
                    onClick = {
                        onTap(rescuedTapEffect(RescuedTap.CONFIRM_DISCARD, item))
                        confirmDiscard = null
                    },
                ) { Text("Discard", color = BarColors.Red) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = null }) { Text("Cancel") }
            },
        )
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        for (item in rescued) {
            val restore = restoreVerdicts[item.directory]
            val buttons = rescuedCardButtons(item, busy, restore, held)
            Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "RESCUED DATABASE",
                        style = MaterialTheme.typography.titleSmall,
                        color = BarColors.Amber,
                        letterSpacing = 2.sp,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(rescuedTitle(item), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        rescuedDetail(item, clock),
                        style = MaterialTheme.typography.bodySmall,
                        color = BarColors.Sub,
                    )
                    Spacer(Modifier.height(6.dp))
                    SectionCaption("Kept on this phone. Nothing deletes it but you")
                    rescuedRestoreNote(restore, DATABASE_VERSION, held)?.let { note ->
                        Spacer(Modifier.height(4.dp))
                        Text(note, style = MaterialTheme.typography.bodySmall, color = BarColors.Sub)
                    }
                    Spacer(Modifier.height(6.dp))
                    // Its own row: three buttons in one would not fit a narrow
                    // phone, and this is the one that changes the live database.
                    if (buttons.showRestore) {
                        TextButton(
                            onClick = { onTap(rescuedTapEffect(RescuedTap.RESTORE, item)) },
                            enabled = buttons.restoreEnabled,
                        ) {
                            Text("RESTORE IT IN PLACE", color = BarColors.Volt)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (buttons.showSend) {
                            OutlinedButton(
                                onClick = { onTap(rescuedTapEffect(RescuedTap.SEND, item)) },
                                enabled = buttons.sendEnabled,
                                contentPadding = ButtonDefaults.TextButtonContentPadding,
                            ) {
                                Text("SEND IT TO ME", color = BarColors.Volt)
                            }
                        }
                        // Kept a TextButton (#201): destructive (it asks first), so it stays
                        // quieter than the SEND beside it.
                        TextButton(
                            onClick = { onTap(rescuedTapEffect(RescuedTap.DISCARD, item)) },
                            enabled = buttons.discardEnabled,
                        ) {
                            Text("DISCARD", color = BarColors.Red)
                        }
                    }
                }
            }
        }
    }
}
