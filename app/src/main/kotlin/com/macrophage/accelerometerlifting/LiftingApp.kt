package com.macrophage.accelerometerlifting

import android.app.Application
import com.macrophage.accelerometerlifting.ble.AutoConnectManager
import com.macrophage.accelerometerlifting.ble.BleScanner
import com.macrophage.accelerometerlifting.ble.DeviceRegistry
import com.macrophage.accelerometerlifting.data.AppDatabase
import com.macrophage.accelerometerlifting.data.PlanRepository
import com.macrophage.accelerometerlifting.data.RawExporter
import com.macrophage.accelerometerlifting.data.SessionExporter
import com.macrophage.accelerometerlifting.data.SessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Manual dependency container; one instance per process. */
class AppContainer(app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val database = AppDatabase.build(app)
    val planRepository = PlanRepository(database.planDao())
    val sessionRepository = SessionRepository(database.sessionDao(), database.exerciseDao())
    val sessionExporter = SessionExporter(sessionRepository)
    val rawExporter = RawExporter(sessionRepository, appVersion = BuildConfig.VERSION_NAME)

    val deviceRegistry = DeviceRegistry(app)
    val bleScanner = BleScanner()
    val autoConnect = AutoConnectManager(app, deviceRegistry, appScope)
    val settings = SettingsStore(app)
}

class LiftingApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Auto-connect to both preferred sensors from app launch (spec 4.1).
        container.autoConnect.start()
    }
}
