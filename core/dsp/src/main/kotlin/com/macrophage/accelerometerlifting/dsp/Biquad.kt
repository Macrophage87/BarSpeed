package com.macrophage.accelerometerlifting.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Second-order Butterworth low-pass filter (single biquad section, causal). */
class Biquad private constructor(
    private val b0: Double,
    private val b1: Double,
    private val b2: Double,
    private val a1: Double,
    private val a2: Double,
) {
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0
    private var primed = false

    fun process(x: Double): Double {
        if (!primed) {
            // Prime with the first sample to avoid a startup step transient.
            x1 = x
            x2 = x
            y1 = x
            y2 = x
            primed = true
        }
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1
        x1 = x
        y2 = y1
        y1 = y
        return y
    }

    fun reset() {
        primed = false
    }

    companion object {
        fun lowPass(cutoffHz: Double, sampleRateHz: Double): Biquad {
            val effectiveCutoff = minOf(cutoffHz, sampleRateHz * 0.45)
            val omega = 2.0 * PI * effectiveCutoff / sampleRateHz
            val cosOmega = cos(omega)
            val alpha = sin(omega) / (2.0 * (1.0 / sqrt(2.0)))
            val a0 = 1.0 + alpha
            return Biquad(
                b0 = ((1.0 - cosOmega) / 2.0) / a0,
                b1 = (1.0 - cosOmega) / a0,
                b2 = ((1.0 - cosOmega) / 2.0) / a0,
                a1 = (-2.0 * cosOmega) / a0,
                a2 = (1.0 - alpha) / a0,
            )
        }
    }
}
