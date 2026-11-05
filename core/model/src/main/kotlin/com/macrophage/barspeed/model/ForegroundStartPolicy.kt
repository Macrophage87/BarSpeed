package com.macrophage.barspeed.model

/**
 * Why the recording foreground service is not running for the set the lifter
 * just started (#53).
 *
 * Named by the java.lang family the platform throws, not by the Android
 * subclass, because this module cannot see `android.app` and because the
 * families are what `RecordingService` can actually tell apart. What each
 * family covers is AOSP's documentation of `Service.startForeground`, quoted
 * in `RecordingService.onStartCommand`'s KDoc, not a measurement.
 */
enum class ForegroundStartFailure {
    /**
     * `SecurityException`: from API 34 the platform refused the type for want
     * of the permission it requires.
     */
    PERMISSION_REFUSED,

    /**
     * `IllegalStateException` and its subclasses: the background-start
     * restriction (`ForegroundServiceStartNotAllowedException`, at either
     * `startForegroundService` or `startForeground`), and the two
     * missing/invalid-type exceptions.
     */
    START_REFUSED,

    /**
     * `IllegalArgumentException`: the type is not a subset of the manifest's
     * `android:foregroundServiceType`. `ForegroundServiceContractTest` makes
     * that unreachable as shipped; the family is broader than that one case,
     * which `RecordingService`'s KDoc names and accepts.
     */
    TYPE_REJECTED,
}

/**
 * Which [ForegroundStartFailure] a throw out of the foreground-service start
 * is, if it is one at all.
 *
 * This is the decision `RecordingService.onStartCommand`'s three catch clauses
 * used to make by their mere presence, where nothing could pin it: the
 * `SecurityException` clause is a sibling of the `IllegalStateException` one,
 * not a subclass, so deleting it compiled cleanly and reinstated the crash at
 * START SET (#52's third hole). Here each family is an arm a test can red.
 *
 * DECLARED, NOT YET READ: [classify] answers null for everything, and the
 * service still catches with its own three clauses and reports nothing. The
 * commit after this one pins the three families.
 */
object ForegroundStartPolicy {
    /**
     * The failure [thrown] stands for, or null where it is none of the three
     * families -- which the caller rethrows rather than swallows, so a bug of
     * an unknown shape still crashes visibly.
     */
    @Suppress("UNUSED_PARAMETER", "FunctionOnlyReturningConstant")
    fun classify(thrown: Throwable): ForegroundStartFailure? = null
}
