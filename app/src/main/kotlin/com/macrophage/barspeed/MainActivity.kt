package com.macrophage.barspeed

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.macrophage.barspeed.model.BlePermissionPolicy
import com.macrophage.barspeed.ui.LiftingTheme
import com.macrophage.barspeed.ui.components.LocalBlePermissionUi
import com.macrophage.barspeed.ui.components.PermissionUi
import com.macrophage.barspeed.ui.screens.DevicesScreen
import com.macrophage.barspeed.ui.screens.GuideScreen
import com.macrophage.barspeed.ui.screens.HomeScreen
import com.macrophage.barspeed.ui.screens.PlanDetailScreen
import com.macrophage.barspeed.ui.screens.PlansScreen
import com.macrophage.barspeed.ui.screens.RecordScreen
import com.macrophage.barspeed.ui.screens.SessionDetailScreen

class MainActivity : ComponentActivity() {
    private val gate by lazy { (application as LiftingApp).container.blePermissionGate }

    /**
     * The result is read now, where it used to be discarded by an empty lambda.
     *
     * Discarding it was issue #41: two denials, or an auto-reset the lifter
     * never chose, and every later launch returned denied with no dialog while
     * Home and Record drew a grey dot identical to "the sensor is off".
     */
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            gate.onResult(result, hasBlePermissions(), shouldShowBleRationale())
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gate.refresh(hasBlePermissions(), shouldShowBleRationale())
        ensureAsked()
        setContent {
            LiftingTheme {
                CompositionLocalProvider(
                    LocalBlePermissionUi provides
                        PermissionUi(
                            step = gate.step,
                            request = ::ensureAskedAgain,
                            openSettings = ::openAppSettings,
                        ),
                ) {
                    AppNav()
                }
            }
        }
    }

    /**
     * Granting from Settings does not restart the process, so without this the
     * banner would still be up after the lifter did exactly what it asked --
     * while `AutoConnectManager`'s backoff turns the sensor dot green behind
     * it. Revoking from Settings does kill the process, so that direction
     * arrives through `onCreate` instead.
     */
    override fun onResume() {
        super.onResume()
        gate.refresh(hasBlePermissions(), shouldShowBleRationale())
    }

    /** The first ask of the process. Silent once anything has been asked. */
    private fun ensureAsked() {
        if (gate.needsAsk()) ensureAskedAgain()
    }

    private fun ensureAskedAgain() {
        val permissions = BlePermissionPolicy.runtimePermissions(Build.VERSION.SDK_INT).toTypedArray()
        try {
            permissionLauncher.launch(permissions)
        } catch (e: IllegalStateException) {
            // launch throws on an unregistered or destroyed launcher. The ask
            // state is deliberately left untouched: marking it in flight here
            // would leave the banner drawn with no button on it for the life of
            // the process, which is the dead end this change closes.
            return
        }
        gate.markInFlight()
    }

    /**
     * The only route back once the platform has stopped showing the dialog.
     *
     * Both fallbacks exist because a button offered to fix a permission must
     * not be the thing that kills the app. The banner names the path in words
     * as well, so it still reads if neither Intent resolves.
     */
    private fun openAppSettings() {
        val details =
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        try {
            startActivity(details)
        } catch (e: ActivityNotFoundException) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (e2: ActivityNotFoundException) {
                return
            }
        }
    }

    private fun hasBlePermissions(): Boolean = BlePermissionPolicy.blePermissions(Build.VERSION.SDK_INT).all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * True while the platform will still show the dialog for at least one of
     * the permissions. Any of them, not all: if even one can still be asked
     * for, asking is the better offer than a trip to Settings.
     */
    private fun shouldShowBleRationale(): Boolean = BlePermissionPolicy.blePermissions(Build.VERSION.SDK_INT).any {
        ActivityCompat.shouldShowRequestPermissionRationale(this, it)
    }
}

@Composable
private fun AppNav() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "home") {
        composable("home") { HomeScreen(navController) }
        composable("devices") { DevicesScreen(navController) }
        composable("plans") { PlansScreen(navController) }
        composable("guide") { GuideScreen(navController) }
        composable(
            "plan/{planId}",
            arguments = listOf(navArgument("planId") { type = NavType.LongType }),
        ) { backStackEntry ->
            PlanDetailScreen(navController, backStackEntry.arguments?.getLong("planId") ?: 0L)
        }
        composable("record") { RecordScreen(navController) }
        composable(
            "session/{sessionId}",
            arguments = listOf(navArgument("sessionId") { type = NavType.LongType }),
        ) { backStackEntry ->
            SessionDetailScreen(navController, backStackEntry.arguments?.getLong("sessionId") ?: 0L)
        }
    }
}
