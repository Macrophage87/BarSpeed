package com.macrophage.barspeed.ui.components

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.macrophage.barspeed.model.BlePermissionPolicy
import com.macrophage.barspeed.model.BlePermissionStep
import com.macrophage.barspeed.ui.BarColors

/**
 * The Nearby-devices permission, when it is standing between the lifter and
 * the bar sensor.
 *
 * Draws nothing at all when the permission is held, so every call site can add
 * it unconditionally. It is on Home, and on the three record stages that lead
 * into a set -- SETUP, READY and RESTING -- and on Devices. It is deliberately
 * absent from IN_SET, where the samples for the set in progress are already
 * missing and a system dialog would be raised over a lifter under a loaded bar,
 * and from FINISHED, which is after the loss.
 *
 * [demoMode] changes what may be claimed rather than whether this draws. Demo
 * streaming does not touch the sensor and works with every BLE permission
 * denied, so "sets record with no bar-speed data" would be false next to a chip
 * reading "Demo mode ON".
 */
@Composable
fun PermissionBanner(demoMode: Boolean = false, modifier: Modifier = Modifier) {
    val step by LocalBlePermissionUi.current.step.collectAsState()
    if (step == BlePermissionStep.GRANTED) return
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            PermissionBannerBody(demoMode, headline = true)
        }
    }
}

/**
 * The banner without its own frame, for the one call site that already draws a
 * card around it: the record screen's "Bar sensor not connected" card, whose
 * advice this replaces. [headline] is dropped there because that card carries
 * its own title.
 */
@Composable
fun PermissionBannerBody(demoMode: Boolean = false, headline: Boolean = false) {
    val ui = LocalBlePermissionUi.current
    val step by ui.step.collectAsState()
    if (step == BlePermissionStep.GRANTED) return

    Column {
        if (headline) {
            Text(
                "Bar sensor blocked",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = BarColors.Amber,
            )
        }
        Text(consequence(demoMode), style = MaterialTheme.typography.bodySmall, color = BarColors.Sub)
        if (step == BlePermissionStep.SETTINGS_ONLY || step == BlePermissionStep.ASK_AGAIN_OR_SETTINGS) {
            Text(
                "If no dialog appears, turn it on in Settings → Permissions → ${permissionLabel()}.",
                style = MaterialTheme.typography.bodySmall,
                color = BarColors.Sub,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // AWAITING_ANSWER offers nothing on purpose: the system dialog is up
            // or going up, and a second way to ask underneath it is a double
            // prompt. It still draws the lines above, so a request that never
            // happens leaves something on screen rather than the silence this
            // defect hid behind.
            if (step == BlePermissionStep.ASK_AGAIN || step == BlePermissionStep.ASK_AGAIN_OR_SETTINGS) {
                TextButton(onClick = ui.request) { Text("Grant") }
            }
            if (step == BlePermissionStep.SETTINGS_ONLY || step == BlePermissionStep.ASK_AGAIN_OR_SETTINGS) {
                TextButton(onClick = ui.openSettings) { Text("Open settings") }
            }
        }
    }
}

/**
 * What the lifter will see the permission called in Settings. "Nearby devices"
 * is the Android 12 group name; below 31 the permission requested is
 * `ACCESS_FINE_LOCATION` and the toggle reads Location. `minSdk` is 26, so both
 * ship.
 */
private fun permissionLabel(): String =
    if (Build.VERSION.SDK_INT >= BlePermissionPolicy.BLUETOOTH_RUNTIME_PERMISSIONS_SDK) {
        "Nearby devices"
    } else {
        "Location"
    }

private fun consequence(demoMode: Boolean): String {
    val name = permissionLabel()
    return when {
        // Never a recommendation to use demo instead: what demo writes into a
        // set is a separate and unaddressed question.
        demoMode -> "$name is denied, so the bar sensor cannot be reached. Demo mode does not use it."
        BlePermissionPolicy.denialBlocksRecording(Build.VERSION.SDK_INT) ->
            "Without $name, BarSpeed cannot reach the bar sensor and sets record with no bar-speed data."
        else -> "Without $name, BarSpeed cannot find new sensors. A sensor already paired still connects."
    }
}
