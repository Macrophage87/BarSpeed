package com.macrophage.barspeed

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.macrophage.barspeed.model.BlePermissionPolicy
import com.macrophage.barspeed.model.FgsTypeChoice
import com.macrophage.barspeed.model.ForegroundStartFailure
import com.macrophage.barspeed.model.ForegroundStartPolicy

/**
 * Foreground service that keeps the process and BLE connections alive while a
 * session is being recorded (screen off, app backgrounded).
 */
class RecordingService : Service() {
    override fun onCreate() {
        super.onCreate()
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_recording),
                NotificationManager.IMPORTANCE_LOW,
            )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /**
     * ServiceCompat owns the API fork. It masks the type with `0x40000FFF`
     * from 34 and with `0xFF` on 29-33, and on 26-28 it drops the argument
     * entirely and calls the two-argument `startForeground`, which is why the
     * type policy has no "no type" answer. The `0xFF` mask is also why
     * [BlePermissionPolicy] must return `CONNECTED_DEVICE` (`0x10`) rather
     * than `SPECIAL_USE` (`0x40000000`) below 34: `SPECIAL_USE` would be
     * masked away to `FOREGROUND_SERVICE_TYPE_NONE` on that band.
     *
     * Three families are caught, none of them optional. AOSP's
     * `Service.java` javadoc for `startForeground(int, Notification, int)`
     * documents five throws and scopes them mutually exclusively:
     *
     *  - `IllegalArgumentException` — the param type is not a subset of the
     *    manifest's `android:foregroundServiceType`.
     *  - `MissingForegroundServiceTypeException` — the manifest attribute is
     *    not set and the param is `FOREGROUND_SERVICE_TYPE_MANIFEST`.
     *  - `InvalidForegroundServiceTypeException` — the manifest attribute or
     *    the param is `FOREGROUND_SERVICE_TYPE_NONE`.
     *  - `SecurityException` — targeting UDC+ without the permission the
     *    specified type requires. This is the API 34 check refusing
     *    `connectedDevice` when Nearby devices has been denied, and it is the
     *    throw this whole commit exists for.
     *  - `ForegroundServiceStartNotAllowedException` — the background-start
     *    restriction.
     *
     * Only Missing-, Invalid- and `ForegroundServiceStartNotAllowedException`
     * descend from `IllegalStateException`; `IllegalArgumentException` and
     * `SecurityException` are both its *siblings* under `RuntimeException`.
     * That is why each of those two needs its own arm and why none of the
     * three is unreachable. They were three catch clauses here, and deleting
     * the `SecurityException` one as redundant with the `IllegalStateException`
     * one compiled cleanly and reinstated the crash at START SET, with
     * nothing to red it (#52). The decision is now
     * `ForegroundStartPolicy.classify`'s, in `:core:model`, where
     * `ForegroundStartPolicyTest` pins each family as an arm; this catches
     * `RuntimeException` and rethrows whatever that answers null for, so a
     * throw of any other shape still crashes rather than becoming a banner.
     * The catch's own type is not pinned: narrowing it below
     * `RuntimeException` compiles and no test reds, so #52's third hole has
     * moved here rather than closed; nothing under `app/src/test` reaches
     * `onStartCommand`.
     * Two earlier versions of this comment got the hierarchy
     * wrong in two different ways: the first named the not-a-subset case
     * `MissingForegroundServiceTypeException`; the second said "only the
     * last three", which reads `SecurityException` as an
     * `IllegalStateException` and leaves `MissingForegroundServiceTypeException`
     * out. Review found both. The classes are named rather than positioned
     * here because that is what makes the claim checkable, by `javap` on
     * `android-35/android.jar`.
     *
     * `ForegroundStartPolicy.classify`'s TYPE_REJECTED arm is broader than
     * the family that justifies it. The five throws above are what AOSP
     * *documents*, not a proven bound on what `startForeground` raises. If
     * the platform also rejects a malformed notification with an
     * `IllegalArgumentException`, that bug now shows the Record banner's
     * "rejected the kind of recording service" wording instead of crashing,
     * which cuts against the reason `buildNotification()` sits outside the
     * try. An earlier version of this paragraph said such a bug "degrades
     * silently"; that stopped being true when #53 made every classified
     * refusal report itself. Nobody read
     * `ActiveServices.setServiceForegroundInnerLocked` to settle it. Not
     * reachable on this tree — fixed channel, fixed drawable,
     * `assembleDebug` green — so the risk is named and accepted, not closed.
     *
     * `ForegroundServiceContractTest` makes the not-a-subset case unreachable
     * as shipped, since it reds if the manifest is narrowed, but the catch is
     * cheap and this is a commit whose entire subject is not crashing at
     * START SET.
     *
     * Either way the set still records in-process with the screen on; what is
     * lost is surviving the screen going off, which beats losing the set to a
     * crash the moment the lifter taps START SET.
     *
     * WHAT WAS LOST WAS ALSO SILENT, until #53. Every refusal ended here with
     * no foreground service and nothing on screen, so a lifter who turned the
     * screen off mid-set had no warning that the process was now as killable
     * as any background app. Each refusal is now reported to
     * [RecordingHolds.reportForeground], which `RecordViewModel` mirrors into
     * `RecordState.foregroundFailure` and `RecordScreen` draws as a banner
     * above every stage; a start that succeeds reports null and clears it.
     * Whether the platform actually kills the process at screen-off on any
     * given device is [Field] and unmeasured here.
     *
     * `stopSelf(startId)` rather than `stopSelf()`: `start()` fires on every
     * `beginSet()`, so a bare `stopSelf()` would tear down a service a later
     * start had already re-armed. START_NOT_STICKY stops the system
     * relaunching a service that will fail again the same way.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        val holds = (application as LiftingApp).container.recordingHolds
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, foregroundServiceType())
            holds.reportForeground(null)
            START_STICKY
        } catch (e: RuntimeException) {
            val failure = ForegroundStartPolicy.classify(e) ?: throw e
            holds.reportForeground(failure)
            stopSelf(startId)
            START_NOT_STICKY
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * The type the platform is handed. Three steps decide it: the policy's
     * [FgsTypeChoice], the enum-to-manifest contract, and the enum-to-int
     * mapping. The first two are pinned by `BlePermissionPolicyTest` and
     * `ForegroundServiceContractTest`. The third used to be a `when` here that
     * nothing pinned -- swapping its two right-hand sides was issue #21
     * verbatim, the policy correctly picking `SPECIAL_USE` and the platform
     * handed `connectedDevice`, and review ran the full CI sequence green on
     * that swap (#52). It is now [FgsTypeChoice.platformType], pinned as
     * literals in `ForegroundServiceContractTest` and against `ServiceInfo`'s
     * own constants in `FgsTypePlatformTest`, and this reads nothing else.
     */
    private fun foregroundServiceType(): Int =
        BlePermissionPolicy.foregroundServiceType(Build.VERSION.SDK_INT, hasBluetoothConnect()).platformType

    private fun hasBluetoothConnect(): Boolean =
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    private fun buildNotification(): Notification {
        val contentIntent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.recording_notification_title))
            .setContentText(getString(R.string.recording_notification_text))
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1

        /**
         * `ForegroundServiceStartNotAllowedException` is where issue #21's
         * clause is honoured. `startForegroundService()` is where the refusal
         * lands *for this caller*: `RecordViewModel.beginSet` starts the service
         * this way, on the lifter's tap, so a throw here would kill the app
         * between "START SET" and the first sample.
         *
         * An earlier version of this comment said the exception "structurally
         * cannot land" around `startForeground`. That is false, and review
         * caught it: AOSP documents the throw on both `startForeground`
         * overloads. It is why `ForegroundStartPolicy.classify` maps
         * `IllegalStateException` and its subclasses to START_REFUSED for
         * `onStartCommand` too — the exception extends
         * `ServiceStartNotAllowedException` extends `IllegalStateException`,
         * not `SecurityException`, so a permission-shaped catch would see it
         * at neither site.
         *
         * Returns the refusal rather than swallowing it (#53): null when the
         * platform accepted the start request, which says nothing yet about
         * whether `onStartCommand` will manage to go foreground -- that half
         * reports itself. The catch is not widened: only the family this
         * call was already known to raise is turned into a value.
         */
        fun start(context: Context): ForegroundStartFailure? = try {
            context.startForegroundService(Intent(context, RecordingService::class.java))
            null
        } catch (e: IllegalStateException) {
            // Background-start refusal on API 31+. Recording continues in
            // process; only screen-off survival is lost, and the caller is
            // told so.
            ForegroundStartPolicy.classify(e)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RecordingService::class.java))
        }
    }
}
