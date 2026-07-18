package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ImuSample
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Rotation of device-frame measurements into the gravity-aligned world frame. */
object FrameTransform {
    /**
     * Vertical (world-Z, up-positive) linear acceleration in m/s².
     *
     * Uses the ZYX Euler angles reported by the sensor: world-Z row of the
     * body-to-world rotation is `[-sin(pitch), sin(roll)cos(pitch), cos(roll)cos(pitch)]`.
     * Gravity (1 g) is subtracted after rotation.
     */
    fun verticalLinearAccelMps2(sample: ImuSample, gravityMps2: Double): Double {
        val roll = Math.toRadians(sample.rollDeg)
        val pitch = Math.toRadians(sample.pitchDeg)
        val azWorldG =
            -sin(pitch) * sample.axG +
                sin(roll) * cos(pitch) * sample.ayG +
                cos(roll) * cos(pitch) * sample.azG
        return (azWorldG - 1.0) * gravityMps2
    }

    /** Acceleration magnitude in g, used for quiet detection. */
    fun accMagnitudeG(sample: ImuSample): Double =
        sqrt(sample.axG * sample.axG + sample.ayG * sample.ayG + sample.azG * sample.azG)

    /** Gyro magnitude in deg/s, used for quiet detection. */
    fun gyroMagnitudeDps(sample: ImuSample): Double =
        sqrt(sample.wxDps * sample.wxDps + sample.wyDps * sample.wyDps + sample.wzDps * sample.wzDps)
}
