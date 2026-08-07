package com.macrophage.barspeed.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One spoken voice cue during a set ("Down", "3", "Up", "Rep 4", "Time"…),
 * stamped on the same epoch-ms clock as the IMU and HR samples so cues can be
 * cross-referenced against the accelerometer stream in exports.
 */
@Serializable
data class VoiceCue(
    @SerialName("t_ms") val timestampMs: Long,
    val cue: String,
)
