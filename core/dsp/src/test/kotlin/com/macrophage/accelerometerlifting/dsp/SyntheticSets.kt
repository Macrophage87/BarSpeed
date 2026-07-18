package com.macrophage.accelerometerlifting.dsp

import com.macrophage.accelerometerlifting.model.ImuSample
import kotlin.math.PI
import kotlin.math.cos
import kotlin.random.Random

/**
 * Generates synthetic accelerometer streams from ideal velocity waveforms.
 *
 * Each movement phase is a half-sine velocity profile: v(t) = Vp sin(pi t / T),
 * so a(t) = Vp (pi/T) cos(pi t / T). Peak velocity is derived from the desired
 * ROM: Vp = rom * pi / (2 T). The device is modeled as level (angles zero) with
 * its Z axis up, so the accelerometer reads 1 g + a/g on Z.
 */
object SyntheticSets {
    data class RepSpec(
        val eccS: Double,
        val bottomPauseS: Double,
        val conS: Double,
        val topPauseS: Double,
        val romM: Double,
    )

    fun generate(
        reps: List<RepSpec>,
        sampleRateHz: Double = 100.0,
        leadInS: Double = 1.5,
        leadOutS: Double = 1.5,
        accNoiseG: Double = 0.004,
        gyroNoiseDps: Double = 1.0,
        seed: Int = 1234,
        eccentricFirst: Boolean = true,
    ): List<ImuSample> {
        val rng = Random(seed)
        val dt = 1.0 / sampleRateHz
        val accel = mutableListOf<Double>()

        fun still(durationS: Double) = repeat((durationS / dt).toInt()) { accel += 0.0 }

        fun phase(durationS: Double, romM: Double, sign: Double) {
            val n = (durationS / dt).toInt()
            val vp = romM * PI / (2.0 * durationS)
            for (i in 0 until n) {
                val t = i * dt
                accel += sign * vp * (PI / durationS) * cos(PI * t / durationS)
            }
        }

        still(leadInS)
        for (rep in reps) {
            if (eccentricFirst) {
                phase(rep.eccS, rep.romM, -1.0)
                still(rep.bottomPauseS)
                phase(rep.conS, rep.romM, +1.0)
                still(rep.topPauseS)
            } else {
                phase(rep.conS, rep.romM, +1.0)
                still(rep.topPauseS)
                phase(rep.eccS, rep.romM, -1.0)
                still(rep.bottomPauseS)
            }
        }
        still(leadOutS)

        val g = 9.80665
        return accel.mapIndexed { i, a ->
            ImuSample(
                timestampMs = (i * dt * 1000).toLong(),
                axG = rng.nextGaussian() * accNoiseG,
                ayG = rng.nextGaussian() * accNoiseG,
                azG = 1.0 + a / g + rng.nextGaussian() * accNoiseG,
                wxDps = rng.nextGaussian() * gyroNoiseDps,
                wyDps = rng.nextGaussian() * gyroNoiseDps,
                wzDps = rng.nextGaussian() * gyroNoiseDps,
                rollDeg = 0.0,
                pitchDeg = 0.0,
                yawDeg = 0.0,
            )
        }
    }

    private fun Random.nextGaussian(): Double {
        // Box-Muller; adequate for test noise.
        val u1 = nextDouble().coerceAtLeast(1e-12)
        val u2 = nextDouble()
        return kotlin.math.sqrt(-2.0 * kotlin.math.ln(u1)) * cos(2.0 * PI * u2)
    }
}
