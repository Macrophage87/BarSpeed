package com.macrophage.barspeed.model

/**
 * What a sensor's battery reading is worth on screen, UX review 2026-11-06
 * (ble-devices F2).
 *
 * The percent used to be drawn only inside the Devices chip, from a value
 * read once at connect time. It is now drawn beside the top-bar sensor dots
 * on Home and Record too, and a low reading is called out there rather than
 * left for the lifter to notice from a sensor that has gone flat mid-set.
 *
 * Lives here, not in `:core:ble` or `:app`, because neither of those has a
 * test that reaches it.
 */
object SensorBattery {
    /** Below this percent the reading is drawn as a warning. */
    const val LOW_PCT = 20

    /**
     * How often `AutoConnectManager` asks a connected unit for its battery
     * again, in milliseconds. Every connect reads it once as well.
     */
    const val REFRESH_INTERVAL_MS = 5 * 60_000L

    /**
     * A generic single-cell Li-ion discharge curve, as (centivolts, percent)
     * points in rising order. A typical rest-voltage table, not a measurement
     * of the WT901BLECL's own cell: [Field].
     */
    private val liIonCurve =
        listOf(
            340 to 0,
            350 to 5,
            360 to 10,
            365 to 20,
            370 to 30,
            375 to 40,
            380 to 50,
            390 to 65,
            400 to 80,
            410 to 90,
            420 to 100,
        )

    /**
     * WT901BLECL battery register (centivolts) to a rough percent.
     *
     * Interpolated along [liIonCurve], truncated to a whole percent, and
     * clamped at both ends. It replaced the linear 3.4-4.1 V map
     * `WitmotionClient` used to have: a real cell sits near 3.7-3.8 V for most
     * of its charge and falls fast below about 3.6 V, so the linear map read
     * 3.60 V as 28% and reached [LOW_PCT] only at about 3.54 V, where the cell
     * has little left. On this curve "low" starts below 3.65 V, so it warns
     * early rather than late. The register is read while the unit streams, so
     * the voltage is under some load and may read a little low; that also
     * errs towards warning early.
     */
    fun percentFromCentivolts(centivolts: Int): Int {
        val (firstCv, firstPct) = liIonCurve.first()
        val (lastCv, lastPct) = liIonCurve.last()
        if (centivolts <= firstCv) return firstPct
        if (centivolts >= lastCv) return lastPct
        val upper = liIonCurve.indexOfFirst { it.first >= centivolts }
        val (hiCv, hiPct) = liIonCurve[upper]
        val (loCv, loPct) = liIonCurve[upper - 1]
        return loPct + (hiPct - loPct) * (centivolts - loCv) / (hiCv - loCv)
    }

    /** True only for a reading under [LOW_PCT]. An unknown reading is not low. */
    fun isLow(pct: Int?): Boolean = pct != null && pct < LOW_PCT

    /**
     * The short text drawn beside a sensor's dot, or null when there is no
     * reading. Absence is drawn as nothing, never as 0%.
     */
    fun badge(pct: Int?): String? = when {
        pct == null -> null
        isLow(pct) -> "$pct% low"
        else -> "$pct%"
    }

    /** [badge] for a link, which has a reading only while it is connected. */
    fun badgeOf(state: ConnectionState): String? = (state as? ConnectionState.Connected)?.let { badge(it.batteryPct) }

    /** [isLow] for a link, which has a reading only while it is connected. */
    fun isLowOf(state: ConnectionState): Boolean = isLow((state as? ConnectionState.Connected)?.batteryPct)
}
