package com.macrophage.barspeed

import android.Manifest
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
import com.macrophage.barspeed.ui.LiftingTheme
import com.macrophage.barspeed.ui.screens.DevicesScreen
import com.macrophage.barspeed.ui.screens.HomeScreen
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

    private fun requestBlePermissions() {
        val permissions =
            if (Build.VERSION.SDK_INT >= 31) {
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                ) + if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        permissionLauncher.launch(permissions)
    }
}

@Composable
private fun AppNav() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "home") {
        composable("home") { HomeScreen(navController) }
        composable("devices") { DevicesScreen(navController) }
        composable("plans") { PlansScreen(navController) }
        composable("record") { RecordScreen(navController) }
        composable(
            "session/{sessionId}",
            arguments = listOf(navArgument("sessionId") { type = NavType.LongType }),
        ) { backStackEntry ->
            SessionDetailScreen(navController, backStackEntry.arguments?.getLong("sessionId") ?: 0L)
        }
    }
}
