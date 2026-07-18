package com.macrophage.accelerometerlifting.model

/**
 * One decoded IMU measurement.
 *
 * Units follow the WitMotion convention: acceleration in g, angular velocity in
 * degrees/second, orientation angles in degrees. [timestampMs] is host arrival
 * time in milliseconds relative to the stream epoch.
 */
data class ImuSample(
    val timestampMs: Long,
    val axG: Double,
    val ayG: Double,
    val azG: Double,
    val wxDps: Double,
    val wyDps: Double,
    val wzDps: Double,
    val rollDeg: Double,
    val pitchDeg: Double,
    val yawDeg: Double,
)

/** One decoded heart-rate measurement. RR intervals are in milliseconds. */
data class HrSample(
    val timestampMs: Long,
    val bpm: Int,
    val rrIntervalsMs: List<Double> = emptyList(),
)

/** Rep phases, in the order they occur for an eccentric-first lift. */
enum class Phase { IDLE, ECCENTRIC, BOTTOM_PAUSE, CONCENTRIC, TOP_PAUSE }

/** Which phase a lift begins with (squat/bench: ECCENTRIC, deadlift: CONCENTRIC). */
enum class StartPhase { ECCENTRIC, CONCENTRIC }
