package com.macrophage.barspeed

import android.app.Application
import com.macrophage.barspeed.ble.AutoConnectManager
import com.macrophage.barspeed.ble.BleScanner
import com.macrophage.barspeed.ble.DeviceRegistry
import com.macrophage.barspeed.data.AppDatabase
import com.macrophage.barspeed.data.DatabaseRescue
import com.macrophage.barspeed.data.PlanRepository
import com.macrophage.barspeed.data.RawExporter
import com.macrophage.barspeed.data.RescuedDatabaseStore
import com.macrophage.barspeed.data.SessionExporter
import com.macrophage.barspeed.data.SessionRepository
import com.macrophage.barspeed.data.SetJournalStore
import com.macrophage.barspeed.model.CrashLogPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/** Manual dependency container; one instance per process. */
class AppContainer(app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val database = AppDatabase.build(app)
    val planRepository = PlanRepository(database.planDao())
    val sessionRepository = SessionRepository(database.sessionDao(), database.exerciseDao())
    val sessionExporter = SessionExporter(sessionRepository)
    val rawExporter = RawExporter(sessionRepository, sessionExporter, appVersion = BuildConfig.VERSION_NAME)

    /**
     * Where a set that is still being performed is kept, so that losing the
     * process does not lose the capture.
     *
     * A directory in the app's own private storage, deliberately not a table
     * in [AppDatabase]. A tenth database version is the worst available place
     * for a crash-recovery mechanism: if the migration is what fails, the
     * lifter loses their WHOLE history rather than one in-progress set, which
     * is strictly worse than the defect it would be fixing. A file append
     * needs no migration at all.
     *
     * Its own scope on the IO dispatcher rather than [appScope], which is
     * Dispatchers.Default: this scope exists to block on a file descriptor,
     * which is the one thing Default's thread pool is sized not to do.
     */
    val setJournals =
        SetJournalStore(File(app.filesDir, "inflight"), CoroutineScope(SupervisorJob() + Dispatchers.IO))

    /**
     * Where [DatabaseRescue.rescue] leaves a database it moved aside, read
     * back for issue #111's card. The same directory [AppDatabase.build]
     * passes as rescueRoot, recomputed from the same public constant rather
     * than threaded through as a return value -- [DatabaseRescue.rescue]'s
     * own result is discarded there today, and #111's own gate found why
     * that discard is not yet safe to build on. This store never reads it;
     * it reads the filesystem [DatabaseRescue] already left in place.
     */
    val rescuedDatabases = RescuedDatabaseStore(File(app.filesDir, DatabaseRescue.RESCUE_DIR))

    /**
     * The crash reports [CrashHandler] has written, for Home's card to list
     * and share. Issue #272.
     *
     * The same directory `LiftingApp.onCreate` hands the handler, recomputed
     * from the same public constant rather than threaded through as a return
     * value -- the pattern [rescuedDatabases] above already uses, and for a
     * sharper reason here: the handler is installed BEFORE this container is
     * constructed, so it cannot be given anything this container owns.
     */
    val crashLogs = CrashLogStore(File(app.filesDir, CrashLogPolicy.DIR))

    val deviceRegistry = DeviceRegistry(app)
    val bleScanner = BleScanner()
    val autoConnect = AutoConnectManager(app, deviceRegistry, appScope)
    val settings = SettingsStore(app)

    /**
     * Process-scoped, and holding enums only. See [BlePermissionGate] for why
     * neither the Activity nor the launcher may be parked here.
     */
    val blePermissionGate = BlePermissionGate()

    /**
     * Process-scoped for the same reason [appScope] is: it answers a question
     * asked while the record screen's ViewModel is being destroyed, about work
     * that outlives it. See [RecordingHolds].
     */
    val recordingHolds = RecordingHolds(app)
}

class LiftingApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // BEFORE the container, deliberately. #272: AppDatabase.build is the
        // one place this app already knows a launch can die -- a migration or
        // a rescue -- and a crash handler installed after it would miss
        // exactly that crash. Nothing the handler reads comes from the
        // container for the same reason.
        CrashReporting.install(CrashLogStore(File(filesDir, CrashLogPolicy.DIR)))
        container = AppContainer(this)
        // Auto-connect to both preferred sensors from app launch (spec 4.1).
        container.autoConnect.start()
    }
}
