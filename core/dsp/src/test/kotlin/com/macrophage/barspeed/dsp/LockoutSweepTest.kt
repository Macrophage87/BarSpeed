package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ImuSample
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The sweep behind [LockoutRepCounter]'s constants, re-derived from the tree
 * rather than asserted from the prototype it was first run in.
 *
 * The streams are every untempo'd back squat with a count the owner has
 * entered or bounded, on both units: the 2026-11-06 session
 * (`SquatLockoutFieldTest`; sets 1, 4, 5, 6 and 7 scored against his
 * counts, sets 2 and 3 unscored), field-46's five straight sets (scored
 * against "at least 4, likely 5"), and `field-backsquat-99hz-6rep` against
 * its six cues. A row is the summed miss over those 25 streams with one
 * constant moved and the rest at their defaults.
 *
 * What it says, and what the [DspConfig] KDocs repeat: the defaults miss
 * nothing; [DspConfig.lockoutMinRise] has the narrowest band -- 0.20 to
 * 0.22 miss nothing, 0.18 calls a re-rack and 0.24 already drops a real
 * rep (2026-11-06 set 7, role a).
 */
class LockoutSweepTest {
    private fun load(path: String): List<ImuSample> = ImuCsv.decode(
        LockoutSweepTest::class.java.getResourceAsStream(path)!!.readBytes().decodeToString(),
    )

    private val v022 = (1..7).flatMap {
        listOf("/live-squat-v022/set%02d.csv".format(it), "/live-squat-v022/set%02d-imu-b.csv".format(it))
    }
    private val s46 = (1..5).flatMap {
        listOf(
            "/field-backsquat-straight-s46-set%02d.csv".format(it),
            "/field-backsquat-straight-s46-set%02d-imu-b.csv".format(it),
        )
    }
    private val owner = mapOf(1 to 5, 4 to 2, 5 to 3, 6 to 3, 7 to 3)

    private val streams: List<Pair<List<ImuSample>, (Int) -> Int>> by lazy {
        v022.map { path ->
            val set = path.substringAfter("/set").take(2).toInt()
            load(path) to { n: Int -> owner[set]?.let { abs(n - it) } ?: 0 }
        } + s46.map { load(it) to { n: Int -> if (n in 4..5) 0 else minOf(abs(n - 4), abs(n - 5)) } } +
            listOf(load("/field-backsquat-99hz-6rep.csv") to { n: Int -> abs(n - 6) })
    }

    private fun miss(config: DspConfig): Int = streams.sumOf { (samples, score) ->
        val tracker = StreamingSetTracker.forLift(LiftDirection())
        val counter = LockoutRepCounter(LiftDirection(), config)
        score(samples.count { counter.feed(tracker.feed(it), it.timestampMs) is RepCall.Speak })
    }

    private val base = DspConfig()

    @Test
    fun `the defaults miss nothing`() = assertEquals(0, miss(base))

    @Test
    fun `the rise floor's band is 0_20 to 0_22`() {
        assertEquals(
            listOf(1, 0, 0, 1, 2),
            listOf(0.18, 0.20, 0.22, 0.24, 0.25).map { miss(base.copy(lockoutMinRise = it)) },
        )
    }

    @Test
    fun `the bias time constant's band is 1_0 to 1_2 s`() {
        assertEquals(
            listOf(1, 0, 0, 1, 3),
            listOf(0.7, 1.0, 1.2, 1.5, 2.0).map { miss(base.copy(lockoutBiasTauS = it)) },
        )
    }

    @Test
    fun `the velocity time constant's band is 1_5 to 2_5 s`() {
        assertEquals(
            listOf(1, 0, 0, 0, 1),
            listOf(1.0, 1.5, 2.0, 2.5, 3.0).map { miss(base.copy(lockoutVelocityTauS = it)) },
        )
    }

    @Test
    fun `the three velocity thresholds`() {
        assertEquals(listOf(0, 0, 0, 0), listOf(0.15, 0.2, 0.25, 0.3).map { miss(base.copy(lockoutDescentMps = it)) })
        assertEquals(listOf(3, 0, 0, 1), listOf(0.1, 0.15, 0.2, 0.25).map { miss(base.copy(lockoutAscentMps = it)) })
        assertEquals(listOf(0, 0, 1), listOf(0.02, 0.05, 0.1).map { miss(base.copy(lockoutEndMps = it)) })
    }
}
