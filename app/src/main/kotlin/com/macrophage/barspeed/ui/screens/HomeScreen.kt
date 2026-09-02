package com.macrophage.barspeed.ui.screens

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
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.macrophage.barspeed.data.OrphanedSet
import com.macrophage.barspeed.data.RescueCompleteness
import com.macrophage.barspeed.data.RescuedDatabase
import com.macrophage.barspeed.model.ByteSize
import com.macrophage.barspeed.model.ConnectionState
import com.macrophage.barspeed.model.WeightUnit
import com.macrophage.barspeed.ui.BarColors
import com.macrophage.barspeed.ui.components.PermissionBanner
import com.macrophage.barspeed.ui.components.SectionCaption
import com.macrophage.barspeed.ui.components.SensorDot
import com.macrophage.barspeed.ui.components.Sparkline
import com.macrophage.barspeed.ui.components.StatTile
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
    val hrmState by viewModel.hrmState.collectAsState()
    val interrupted by viewModel.interrupted.collectAsState()
    val rescued by viewModel.rescued.collectAsState()
    val busyRescues by viewModel.busyRescues.collectAsState()
    // Re-scanned every time this screen is composed, not only on first launch:
    // a set interrupted by a crash that left the process alive would otherwise
    // stay invisible until the app was killed and started again. The same
    // reasoning applies to a rescue: it happens once, at AppDatabase.build,
    // before this screen exists to be told about it.
    LaunchedEffect(Unit) {
        viewModel.refreshInterrupted()
        viewModel.refreshRescued()
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
                        HomeSensorDot("IMU", imuState) { navController.navigate("devices") }
                        HomeSensorDot("HRM", hrmState) { navController.navigate("devices") }
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
            RescuedDatabaseNotice(rescued, busyRescues, viewModel::shareRescued, viewModel::discardRescued)
            InterruptedSetNotice(interrupted, viewModel::shareInterrupted, viewModel::discardInterrupted)
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
                TextButton(onClick = { navController.navigate("devices") }, modifier = Modifier.weight(1f)) {
                    Text("Devices", color = BarColors.Sub)
                }
                TextButton(onClick = { navController.navigate("plans") }, modifier = Modifier.weight(1f)) {
                    Text("Plans", color = BarColors.Sub)
                }
                TextButton(onClick = { navController.navigate("guide") }, modifier = Modifier.weight(1f)) {
                    Text("Guide", color = BarColors.Sub)
                }
            }
            Spacer(Modifier.height(6.dp))
            SectionCaption("History")
            Spacer(Modifier.height(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.history) { row ->
                    HistoryCard(row) { navController.navigate("session/${row.session.id}") }
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
private fun HomeSensorDot(label: String, state: ConnectionState, onClick: () -> Unit) {
    Box(
        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        SensorDot(label, state)
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
                "${state.planSessionCount} sessions · ${state.planExerciseCount} exercises · " +
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
                        row.session.hrAvgBpm?.let { "♥ $it avg" },
                        row.session.hrvRmssdMs?.let { "HRV ${it.toInt()}" },
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
 * The two numbers shown are the ones that let the lifter check this against
 * their own memory of what happened: how many samples of each captured stream
 * reached the disk, and when the last of them did. Neither is stated when no
 * sensor was connected -- a count of zero would otherwise read as a
 * measurement, when what it means is that there was nothing to measure.
 */
@Composable
private fun InterruptedSetNotice(
    interrupted: List<OrphanedSet>,
    onShare: (OrphanedSet) -> Unit,
    onDiscard: (OrphanedSet) -> Unit,
) {
    if (interrupted.isEmpty()) return
    val clock = DateTimeFormatter.ofPattern("HH:mm:ss")
    Column(modifier = Modifier.fillMaxWidth()) {
        for (orphan in interrupted) {
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
                        interruptedDetail(orphan, clock),
                        style = MaterialTheme.typography.bodySmall,
                        color = BarColors.Sub,
                    )
                    Spacer(Modifier.height(6.dp))
                    SectionCaption("Kept on this phone. Nothing deletes it but you")
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { onShare(orphan) }) {
                            Text("SEND IT TO ME", color = BarColors.Volt)
                        }
                        TextButton(onClick = { onDiscard(orphan) }) {
                            Text("DISCARD", color = BarColors.Red)
                        }
                    }
                }
            }
        }
    }
}

/**
 * What survived, in the terms the field check reads.
 *
 * The sample counts and the last sample's wall clock are what distinguish a
 * capture that genuinely reached the filesystem from one the app merely
 * remembered. With neither sensor connected there is no count to give and the
 * card says so in words rather than printing a zero.
 *
 * BOTH recovered streams are counted, issue #156. The analysed unit is the one
 * that can be flat while the second one captured the whole set, and this card
 * is the only thing standing between that capture and the DISCARD button
 * beside it.
 */
private fun interruptedDetail(orphan: OrphanedSet, clock: DateTimeFormatter): String {
    val reps = orphan.repMarks.size
    val second =
        when {
            orphan.secondaryImuSamples.isNotEmpty() ->
                ", ${orphan.secondaryImuSamples.size} from the second sensor"
            orphan.header.secondaryImuConnected -> ", none from the second sensor"
            else -> ""
        }
    val lastMs =
        listOfNotNull(
            orphan.imuSamples.lastOrNull()?.timestampMs,
            orphan.secondaryImuSamples.lastOrNull()?.timestampMs,
        ).maxOrNull()
    val parts =
        listOfNotNull(
            when {
                orphan.header.imuConnected -> "${orphan.imuSamples.size} analysed samples$second"
                second.isNotEmpty() -> "no analysed sensor connected$second"
                else -> "no sensor connected"
            },
            lastMs?.let {
                "last at ${clock.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))}"
            },
            if (reps > 0) "$reps reps counted" else null,
        )
    return parts.joinToString(" · ")
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
    onShare: (RescuedDatabase) -> Unit,
    onDiscard: (RescuedDatabase) -> Unit,
) {
    if (rescued.isEmpty()) return
    val clock = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    var confirmDiscard by remember { mutableStateOf<RescuedDatabase?>(null) }
    confirmDiscard?.let { item ->
        AlertDialog(
            onDismissRequest = { confirmDiscard = null },
            title = { Text("Discard this rescued database?") },
            text = { Text(rescuedDiscardWarning(item)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDiscard(item)
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
            val isBusy = item.directory in busy
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
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Nothing to send from a rescue that saved no files.
                        // The old version offered SEND here and produced a
                        // valid, empty 22-byte zip -- an attachment that
                        // arrives and looks like a backup.
                        if (item.completeness != RescueCompleteness.NOTHING) {
                            TextButton(onClick = { onShare(item) }, enabled = !isBusy) {
                                Text("SEND IT TO ME", color = BarColors.Volt)
                            }
                        }
                        TextButton(onClick = { confirmDiscard = item }, enabled = !isBusy) {
                            Text("DISCARD", color = BarColors.Red)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Three states, not two, and every string on this card switches on the same
 * one: [RescuedDatabase.completeness], computed in :core:data and pinned by
 * `RescuedDatabaseStoreTest`. Nothing here re-derives it.
 *
 * NO COMPLETENESS CLAIM ON THE COMPLETE CASE. The title used to read "Your
 * training history" flat, which is the claim `RescuedDatabaseStore.zipTo`'s
 * own KDoc contradicts: a `.db` alone can look complete and be stale,
 * because its `-wal` can hold committed transactions the main file does not
 * yet have -- and in a split rescue that `-wal` is a SEPARATE card. A lifter
 * who sent "Your training history" and got a zip that opened cleanly had
 * every reason to believe they had everything, and the file list was
 * suppressed on exactly that card. Both halves are fixed: the title names
 * what the card actually holds, and [rescuedDetail] lists the files on every
 * card that has any.
 */
private fun rescuedTitle(item: RescuedDatabase): String = when (item.completeness) {
    RescueCompleteness.COMPLETE -> "Your training history -- the main database file"
    RescueCompleteness.FRAGMENT -> "Partial rescue -- may hold data your database is missing"
    RescueCompleteness.NOTHING -> "Nothing survived this rescue attempt"
}

/**
 * What the discard dialog says, and it cannot be one sentence, because one
 * sentence is not true of all three states.
 *
 * THE EMPTY CASE RENDERED, VERBATIM, "This permanently removes 0 B of
 * rescued data (). This cannot be undone." -- reachable, because DISCARD is
 * offered there. `joinToString` on an empty list is the empty string and the
 * dialog interpolated it inside brackets it had already opened. That is the
 * identical defect the commit which added this dialog found and fixed six
 * lines away in [rescuedDetail], whose own KDoc states the fact that makes
 * this one wrong; tier enumeration had been applied to two of this card's
 * four strings and not to the other two.
 *
 * THE COMPLETE CASE CARRIES THE ONLY-COPY WARNING TOO. `DatabaseRescue`
 * moves rather than copies -- "MOVE, NOT COPY" is its own KDoc's heading
 * -- and `DatabaseRescue.moveOrder` moves the sidecars the same way, so
 * only-copy is a fact on both arms. What separates them is what is lost:
 * on FRAGMENT, whether losing it loses anything is uncertain; on COMPLETE,
 * losing it loses the whole history, because `AppDatabase.build`'s KDoc
 * says what happens next -- Room finds nothing and creates an empty
 * database. The FRAGMENT arm below carried the only-copy clause and this
 * one did not.
 *
 * THE FRAGMENT CASE IS WHY THE DIALOG EXISTS AT ALL. DISCARD is offered
 * there now -- see [RescuedDatabaseNotice]'s own KDoc for why the button came
 * back -- so this text is where the lifter finds out what they are about to
 * destroy. It HEDGES: a sidecar CAN hold sets the live database does not
 * have, which is `RescuedDatabaseStore.zipTo`'s own wording, but nothing on
 * this screen has opened the file and nothing here knows whether this
 * particular one does. `DatabaseRescue.moveOrder` carries -wal, -shm and
 * -journal, and a -shm alone holds nothing worth keeping, so a flat "this
 * holds your missing sets" would be a claim stronger than anything this
 * screen can support.
 */
private fun rescuedDiscardWarning(item: RescuedDatabase): String = when (item.completeness) {
    RescueCompleteness.COMPLETE ->
        "This permanently removes ${ByteSize.format(item.totalBytes)} of rescued data " +
            "(${item.files.joinToString(", ")}). The rescue MOVED these files rather than " +
            "copying them, so the app's own database does not have what is in them and nothing " +
            "here can put them back. Send it to yourself first if you are not sure. " +
            "This cannot be undone."
    RescueCompleteness.FRAGMENT ->
        "This rescue saved no database file -- only ${item.files.joinToString(", ")}, " +
            "${ByteSize.format(item.totalBytes)} in all. A sidecar like that can hold sets your " +
            "current database does not have, and nothing else on this phone has a copy. Send it to " +
            "yourself first if you are not sure. This cannot be undone."
    RescueCompleteness.NOTHING ->
        "This rescue attempt saved no files at all, so there is nothing here to lose. " +
            "Only the card goes."
}

/**
 * When it was rescued (or that this code cannot say, rather than a made-up
 * time), how large it is, and what is actually in it.
 *
 * THE FILE LIST IS SHOWN ON EVERY CARD THAT HAS ONE, including the complete
 * case, which used to suppress it. That suppression was the near neighbour of
 * the title's completeness claim: in a split rescue the `.db`-only card said
 * "Your training history", sorted to the top, and showed nothing that would
 * let a lifter notice its `-wal` was on a different card. The empty case
 * still contributes null rather than an empty string -- `joinToString` on an
 * empty list is `""`, which `listOfNotNull` would still include, rendering a
 * dangling " · " with nothing after it, and an empty rescue directory is
 * reachable (see `RescuedDatabase.isEmpty`'s own KDoc). Null on emptiness
 * rather than on a state test, so the guard cannot drift from the thing it
 * guards.
 */
private fun rescuedDetail(item: RescuedDatabase, clock: DateTimeFormatter): String {
    val whenText =
        item.rescuedAtMs?.let { "rescued ${clock.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))}" }
            ?: "rescued at an unknown time"
    val sizeText = ByteSize.format(item.totalBytes)
    val filesText = item.files.joinToString(", ").takeIf { item.files.isNotEmpty() }
    return listOfNotNull(whenText, sizeText, filesText).joinToString(" · ")
}
