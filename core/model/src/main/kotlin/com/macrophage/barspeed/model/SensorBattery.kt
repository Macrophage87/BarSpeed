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

    private const val EMPTY_VOLTS = 3.4
    private const val FULL_VOLTS = 4.1

    /**
     * WT901BLECL battery register (centivolts) to a rough percent.
     *
     * The linear 3.4-4.1 V map `WitmotionClient` has always used, moved here
     * unchanged so a test can hold it. It is NOT a Li-ion discharge curve: a
     * real cell sits near 3.7-3.8 V for most of its charge and falls fast
     * below about 3.6 V, so this reads mid-charge cells low-ish and reaches
     * [LOW_PCT] only at about 3.54 V, where a real cell has little left. The
     * warning therefore comes late rather than early. Not changed here
     * because nothing has measured this unit's curve: [Field].
     */
    fun percentFromCentivolts(centivolts: Int): Int {
        val volts = centivolts / 100.0
        return (((volts - EMPTY_VOLTS) / (FULL_VOLTS - EMPTY_VOLTS)) * 100).toInt().coerceIn(0, 100)
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
