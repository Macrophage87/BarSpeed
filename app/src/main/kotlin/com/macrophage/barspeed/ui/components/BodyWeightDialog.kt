package com.macrophage.barspeed.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.macrophage.barspeed.model.WeightUnit
import com.macrophage.barspeed.ui.BarColors

/**
 * Body weight is the base load for pull-ups, dips and other bodyweight work,
 * where the plan prescribes only what was ADDED (or assisted away).
 *
 * Shared rather than owned by one screen (issue #199): it used to be private
 * to `HomeScreen.kt`, whose own control read the fourth column of a
 * three-way navigation row and dialed this in. That column left Home
 * entirely for the Plans screen, and the control there needs the identical
 * dialog -- prefilled from the stored value, in the lifter's own unit -- so
 * this moved out to `ui/components` rather than being copied a second time.
 *
 * [current] still prefills the field. The button that opens this dialog
 * deliberately shows no value at all (issue #199's owner ruling), so the
 * prefilled text here is the ONLY place in the app outside RecordScreen's
 * own prompt where a lifter can see what is actually stored.
 */
@Composable
fun BodyWeightDialog(current: Double?, unit: WeightUnit, onDismiss: () -> Unit, onSet: (Double) -> Unit) {
    var text by remember { mutableStateOf(current?.let { unit.inputValue(it) } ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Body weight") },
        text = {
            Column {
                Text(
                    "Used as the base load for pull-ups, dips and other bodyweight work — " +
                        "those plans prescribe only the weight you add or take off.",
                    style = MaterialTheme.typography.bodySmall,
                    color = BarColors.Sub,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Weight (${unit.suffix})") },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { unit.parseToKg(text)?.takeIf { it > 0 }?.let(onSet) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
