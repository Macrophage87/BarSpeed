package com.macrophage.barspeed.ui.components

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * One tap to the system's "turn on Bluetooth?" dialog, UX review 2026-11-06
 * (ble-devices F5).
 *
 * Drawn wherever the app says the phone's Bluetooth is off. Before it the
 * lifter had to leave the app, pull down the shade, find the tile and come
 * back. `AutoConnectManager` hears the adapter come on and reconnects at
 * once, so nothing else has to be tapped afterwards.
 *
 * `ACTION_REQUEST_ENABLE` needs BLUETOOTH_CONNECT from API 31. Without it the
 * button does nothing rather than crash: the permission banner on the same
 * screen is what asks for it. A phone with no activity for the intent does
 * nothing too. What the dialog looks like, and whether every vendor shows
 * it, has not been watched: [Field].
 */
@SuppressLint("MissingPermission")
@Composable
fun TurnOnBluetoothButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // The result is not read: the adapter broadcast, not this dialog's
    // answer, is what moves the links.
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    OutlinedButton(
        onClick = {
            val granted =
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || granted) {
                try {
                    launcher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                } catch (e: ActivityNotFoundException) {
                    // Nothing to open; the line beside this button still says
                    // what to do by hand.
                } catch (e: SecurityException) {
                    // Revoked between the check and the launch.
                }
            }
        },
        contentPadding = ButtonDefaults.TextButtonContentPadding,
        modifier = modifier,
    ) {
        Text("Turn on Bluetooth")
    }
}
