package com.macrophage.barspeed

import android.util.Log
import com.macrophage.barspeed.data.DATABASE_VERSION
import com.macrophage.barspeed.model.CrashLogRing
import com.macrophage.barspeed.model.CrashRecord

/**
 * The app's own log, and the last fifty lines of it. Issue #272.
 *
 * THIS APP BARELY LOGS, AND THAT IS A MEASUREMENT, NOT A GUESS. At the SHA
 * this landed on there was exactly ONE logging call in the whole tree --
 * `HomeViewModel`'s warning when the interrupted-set scan fails -- no Timber,
 * no logger of its own, and `android.util.Log` imported in that one file.
 * #272 asks for "the last N app log lines"; the honest description of what
 * this delivers today is that a crash report will usually carry NO log lines
 * at all, which is why `CrashRecord.render` prints an empty log as a stated
 * absence rather than as an empty section. The buffer is here so that every
 * line the app logs from now on is in the next crash report, not because
 * there is a body of logging to collect.
 *
 * A thin façade over `android.util.Log`, deliberately: routing through this
 * object is the only way a line reaches a crash report, and a call that goes
 * straight to `Log` is invisible to it.
 *
 * THE STACK OF [error] IS NOT PUT IN THE RING. One line names the throwable;
 * the frames go to logcat only. A ring that held stacks would be bounded by
 * `CrashLogRing`'s character cap and would spend the whole buffer on one
 * entry.
 */
object AppLog {
    /** The buffer a crash report reads. Allocated once, at class load, never at crash time. */
    val recent = CrashLogRing()

    /** A warning: logcat, plus one line in the crash buffer. */
    fun w(tag: String, message: String, error: Throwable? = null) {
        if (error == null) Log.w(tag, message) else Log.w(tag, message, error)
        recent.add(line("W", tag, message, error))
    }

    /** Information worth having in a crash report. */
    fun i(tag: String, message: String) {
        Log.i(tag, message)
        recent.add(line("I", tag, message, null))
    }

    private fun line(level: String, tag: String, message: String, error: Throwable?): String {
        val suffix = error?.let { " -- $it" } ?: ""
        return "$level/$tag: $message$suffix"
    }
}

/**
 * The route being drawn, for a crash report to name. Issue #272.
 *
 * A process-scoped object rather than a field on `AppContainer`, and the
 * reason is ordering: [CrashReporting.install] runs BEFORE the container is
 * built, so that a throw inside `AppDatabase.build` -- the one place this app
 * already knows a launch can die -- is still recorded. Anything the handler
 * reads has to exist before the container does.
 *
 * Holds one nullable String and nothing else, which is the same bar
 * `BlePermissionGate`'s KDoc sets for what may be parked at process scope:
 * no Activity, no launcher, nothing with a lifecycle.
 *
 * Null is a real value here and is rendered as words, never as a route.
 * Nothing sets this until the first navigation destination is drawn.
 */
object CurrentScreen {
    @Volatile
    var route: String? = null
}

/**
 * Writes the crash report, then hands the throwable to whoever was the
 * default handler before us. Issue #272.
 *
 * THE RETHROW IS BY CONSTRUCTION, and that is the point of the shape rather
 * than a comment about it. [previous] is captured at install time and called
 * in a `finally`, so there is no path through this method that writes a
 * report and does not pass the crash on: a throw from [store] lands in the
 * catch, and the `finally` runs either way. Android must still show the crash
 * and still kill the process; a handler that swallowed one would turn a
 * visible failure into a phone that quietly stops working, which this
 * repository ranks as the worse outcome.
 *
 * NOTHING HERE IS TEST-GATED. No test in this repository can install an
 * uncaught-exception handler or observe a process dying, so this class is
 * compile- and lint-gated only. What IS pinned, in :core:model, is every
 * decision it makes about content: `CrashRecordRenderTest` for the text and
 * `CrashLogPolicyTest` for the file name and the pruning.
 *
 * NULL [previous] IS NOT INVENTED AROUND. If nothing was installed before us
 * there is no handler to hand to, and this deliberately does not construct
 * one: the platform's own behaviour for an uncaught exception with no default
 * handler is not something this code can improve on, and guessing at it would
 * be a claim about a runtime nothing here has observed.
 */
class CrashHandler(
    private val previous: Thread.UncaughtExceptionHandler?,
    private val store: CrashLogStore,
    private val record: (Thread, Throwable) -> CrashRecord,
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, error: Throwable) {
        try {
            store.write(record(thread, error))
        } catch (writeFailure: Throwable) {
            // Deliberately swallowed and deliberately not logged: this runs on
            // a dying thread, and anything that throws on the way out of here
            // would displace the crash the report is about.
        } finally {
            previous?.uncaughtException(thread, error)
        }
    }
}

/** Installs [CrashHandler] and builds the record it writes. Issue #272. */
object CrashReporting {
    /**
     * Called from `LiftingApp.onCreate` before anything else, so that a throw
     * inside the dependency container is recorded rather than lost.
     *
     * Idempotent by inspection: installing twice would chain a second handler
     * to the first and write two files for one crash.
     */
    fun install(store: CrashLogStore) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is CrashHandler) return
        Thread.setDefaultUncaughtExceptionHandler(
            CrashHandler(previous, store) { thread, error -> recordOf(thread, error) },
        )
    }

    /**
     * The record for one crash.
     *
     * Every value here is either a constant compiled into the APK or one
     * cheap call. Nothing opens a file, reads the database or walks the
     * journal directory -- #271 died because a diagnostic path read what it
     * was given, and the moment of least available heap is the worst possible
     * time to repeat it.
     *
     * The three memory figures are read AFTER the crash, so they describe the
     * heap as the handler found it, not as it was when the throwable was
     * constructed. That is the useful reading for an `OutOfMemoryError` and it
     * is what the file's own wording says.
     */
    fun recordOf(thread: Thread, error: Throwable): CrashRecord {
        val runtime = Runtime.getRuntime()
        return CrashRecord(
            atMs = System.currentTimeMillis(),
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            databaseVersion = DATABASE_VERSION,
            screen = CurrentScreen.route,
            threadName = thread.name,
            freeMemoryBytes = runtime.freeMemory(),
            totalMemoryBytes = runtime.totalMemory(),
            maxMemoryBytes = runtime.maxMemory(),
            logLines = AppLog.recent.lines(),
            stack = CrashRecord.stackText(error),
        )
    }
}
