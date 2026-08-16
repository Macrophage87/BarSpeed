package com.macrophage.barspeed

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.macrophage.barspeed.model.BlePermissionPolicy
import com.macrophage.barspeed.ui.LiftingTheme
import com.macrophage.barspeed.ui.screens.DevicesScreen
import com.macrophage.barspeed.ui.screens.GuideScreen
import com.macrophage.barspeed.ui.screens.HomeScreen
import com.macrophage.barspeed.ui.screens.PlanDetailScreen
import com.macrophage.barspeed.ui.screens.PlansScreen
import com.macrophage.barspeed.ui.screens.RecordScreen
import com.macrophage.barspeed.ui.screens.SessionDetailScreen

class MainActivity : ComponentActivity() {
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestBlePermissions()
        setContent {
            LiftingTheme {
                AppNav()
            }
        }
    }

    /**
     * The set asked for is [BlePermissionPolicy.runtimePermissions], which is
     * pinned against the manifest that has to declare it. The two bare SDK
     * literals this used to carry could not be tested from anywhere: `:app` has
     * no test source set.
     */
    private fun requestBlePermissions() {
        permissionLauncher.launch(
            BlePermissionPolicy.runtimePermissions(Build.VERSION.SDK_INT).toTypedArray(),
        )
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
