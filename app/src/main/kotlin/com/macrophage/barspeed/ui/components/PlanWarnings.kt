package com.macrophage.barspeed.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.macrophage.barspeed.model.PlanWarningDisplay
import com.macrophage.barspeed.model.PlanWarningLine
import com.macrophage.barspeed.model.PlanWarningTier
import com.macrophage.barspeed.ui.BarColors

/**
 * A plan's import warnings in the two tiers [PlanWarningDisplay] decides
 * (UX review 2026-11-06, plans F3 and F5), shared by the import gate on Plans
 * and the "Make active" approval on Plan detail so the two cannot drift apart
 * again.
 *
 * What will record wrong is drawn first, in red, every line of it. The notes
 * fold behind one "N notes" control. Nothing is capped: the caller puts this
 * in a scrolling column. "Copy warnings" carries every raw line -- the JSON
 * paths and key names the plan's author needs -- never the lifter wording.
 */
@Composable
fun PlanWarnings(lines: List<PlanWarningLine>, copyHeading: String) {
    if (lines.isEmpty()) return
    val clipboard = LocalClipboardManager.current
    var notesOpen by rememberSaveable { mutableStateOf(false) }
    val (wrong, notes) = lines.partition { it.tier == PlanWarningTier.RECORDS_WRONG }
    Column {
        wrong.forEach {
            Spacer(Modifier.height(4.dp))
            Text("⚠ ${it.text}", style = MaterialTheme.typography.bodySmall, color = BarColors.Red)
        }
        if (notes.isNotEmpty()) {
            // Kept a TextButton (#201): it only shows or hides lines, so it must not read as an answer.
            TextButton(onClick = { notesOpen = !notesOpen }, contentPadding = ButtonDefaults.TextButtonContentPadding) {
                Text(
                    PlanWarningDisplay.notesLabel(notes.size) + if (notesOpen) " ▾" else " ▸",
                    style = MaterialTheme.typography.bodySmall,
                    color = BarColors.Sub,
                )
            }
            if (notesOpen) {
                notes.forEach {
                    Text("• ${it.text}", style = MaterialTheme.typography.bodySmall, color = BarColors.Sub)
                    Spacer(Modifier.height(2.dp))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        OutlinedButton(
            onClick = {
                clipboard.setText(
                    AnnotatedString(PlanWarningDisplay.relayText(copyHeading, lines.map { it.raw })),
                )
            },
            contentPadding = ButtonDefaults.TextButtonContentPadding,
        ) {
            Text("Copy warnings")
        }
    }
}
