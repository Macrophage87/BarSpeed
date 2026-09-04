package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ImuSample
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The seam issue #87's fix acts on, pinned on its own before anything uses it.
 *
 * [VelocityEstimator.isQuietSample] used to be one predicate serving two
 * consumers with different needs: the batch anchor walk in
 * [VelocityEstimator.quietMask], which has the whole set in hand, and
 * [StreamingSetTracker], which sees one sample at a time and has no
 * distribution to consult. This file pins the three symbols that split those
 * jobs apart -- [VelocityEstimator.isAnchorCandidate],
 * [VelocityEstimator.gyroQuantileDps] and [VelocityEstimator.gyroGateApplies]
 * -- and pins that introducing them changed nothing.
 *
 * The equivalence assertion is the load-bearing one: `isQuietSample` must stay
 * bit-identical to `isAnchorCandidate(gyroGate = true)` sample for sample,
 * because that is what keeps the live path out of this change entirely.
 */
class GyroGateTest {
    private fun load(n: String): List<ImuSample> = ImuCsv.decode(
        javaClass.getResourceAsStream("/$n.csv")!!.readBytes().decodeToString(),
    )

    /** Every capture in the corpus, so no fixture is silently outside this check. */
    private val corpus = listOf(
        "field-assistedpullup-3010-s37-set08",
        "field-assistedpullup-3010-s37-set09",
        "field-assistedpullup-3010-s37-set10",
        "field-backsquat-10hz",
        "field-backsquat-10hz-set5",
        "field-backsquat-4011-6rep-s36-set01",
        "field-backsquat-99hz-6rep",
        "field-backsquat-wrapping-s36-set01",
        "field-bench-3010-6rep-s37-set05",
        "field-bench-3010-6rep-s37-set06",
        "field-bench-rotating-6rep",
        "field-bench-rotating-6rep-ok",
        "field-cablerow-static-8rep",
        "field-facepull-static-12rep",
        "field-inclinepress-3010-12rep-s38-set02",
        "field-legcurl-1030-10rep",
        "field-legcurl-1030-12rep",
        "field-legcurl-1030-12rep-b",
        "field-legcurl-1030-12rep-c",
        "field-legpress-2010-8rep",
        "field-legpress-single-2010-8rep",
        "field-legpress-single-2011-8rep-s36-set07",
        "field-ohp-100hz-bursty",
        "field-ohp-3010-6rep-s37-set02",
        "field-ohp-3010-8rep-s37-set01",
        "field-ohp-3010-8rep-s38-set05",
        "field-ohp-prepinflated-s37-set03",
        "field-ohp-prepinflated-s37-set04",
        "field-ohp-rotating-8rep",
        "field-ohp-rotating-8rep-b",
        "field-pallof-static-12rep",
        "field-pullup-3010-8rep-s37-set09",
        "field-rdl-3010-10rep",
        "field-rdl-3010-10rep-s36-set04",
        "field-rdl-3010-10rep-s36-set05",
        "field-rdl-wrapping-s36-set05",
        "field-reardeltfly-s32-set06",
        "field-ropedeadhang-hold20-s37-set11",
        "field-seated-ohp-2rep",
        "field-still-0rep",
    )

    @Test
    fun `the split predicate is the old predicate when the gyro gate is on`() {
        // The whole live path rests on this. StreamingSetTracker keeps calling
        // isQuietSample and must keep getting the answer it got before.
        val config = DspConfig()
        corpus.forEach { fixture ->
            val samples = load(fixture)
            val disagreements = samples.count { s ->
                VelocityEstimator.isQuietSample(s, config) !=
                    VelocityEstimator.isAnchorCandidate(s, config, gyroGate = true)
            }
            assertEquals(0, disagreements, "$fixture: samples where the two forms disagree, of ${samples.size}")
        }
    }

    @Test
    fun `dropping the gyro gate can only widen the candidate set, never narrow it`() {
        // Structural, not fitted: the two predicates differ by one conjunct, so
        // every sample admitted with the gate on is admitted with it off. Pinned
        // because it is what bounds the fix -- the change can add candidate
        // windows and can never remove one.
        val config = DspConfig()
        corpus.forEach { fixture ->
            val samples = load(fixture)
            val lost = samples.count { s ->
                VelocityEstimator.isAnchorCandidate(s, config, gyroGate = true) &&
                    !VelocityEstimator.isAnchorCandidate(s, config, gyroGate = false)
            }
            assertEquals(0, lost, "$fixture: candidates the acceleration term alone would lose")
        }
    }

    @Test
    fun `the median gyro magnitude of each capture that carries a hand count`() {
        // Pinned exactly, because this number is one of the two gyroGateApplies
        // reads and a mutation to the quantile that returned, say, the mean
        // would pass every downstream assertion on most captures.
        val expected = mapOf(
            "field-ohp-3010-6rep-s37-set02" to 16.742,
            "field-bench-3010-6rep-s37-set05" to 23.923,
            "field-bench-3010-6rep-s37-set06" to 32.318,
            "field-backsquat-4011-6rep-s36-set01" to 15.497,
            "field-rdl-3010-10rep-s36-set04" to 5.809,
            "field-rdl-3010-10rep-s36-set05" to 6.423,
            "field-pullup-3010-8rep-s37-set09" to 0.936,
            "field-ohp-rotating-8rep" to 12.061,
            "field-ohp-rotating-8rep-b" to 12.976,
            "field-reardeltfly-s32-set06" to 62.870,
            "field-cablerow-static-8rep" to 1.099,
            "field-still-0rep" to 0.0,
            // The two committed for issue #245.
            "field-ohp-3010-8rep-s38-set05" to 20.972,
            "field-inclinepress-3010-12rep-s38-set02" to 19.462,
        )
        expected.forEach { (fixture, median) ->
            assertEquals(
                median,
                VelocityEstimator.medianGyroDps(load(fixture)),
                0.01,
                "$fixture: median gyro magnitude, deg/s",
            )
        }
    }

    @Test
    fun `the tenth-percentile probe, which is the half of the rule fitted to one capture`() {
        // The low probe of the straddle test, pinned for every capture whose
        // median clears the gate -- the only captures it can decide. One of the
        // THIRTEEN sits above the gate and it is the one the probe exists to
        // exclude; the other twelve sit at 0.06-5.81 deg/s, well clear. It read
        // eleven and 2.26-5.63 until issue #245 committed two more clearing
        // captures, whose tenth percentiles are 5.809 and 0.061. It read
        // "one of the eight ... the other seven ... 2.26-4.28" until the three
        // clearing captures issue #133 committed were measured into it; the
        // claim to cover EVERY capture whose median clears the gate is what
        // made the older wording false rather than merely narrow.
        val expected = mapOf(
            "field-ohp-3010-6rep-s37-set02" to 3.560,
            "field-bench-3010-6rep-s37-set05" to 3.807,
            "field-bench-3010-6rep-s37-set06" to 4.277,
            "field-backsquat-4011-6rep-s36-set01" to 2.487,
            "field-backsquat-99hz-6rep" to 2.966,
            "field-ohp-rotating-8rep" to 2.257,
            "field-ohp-rotating-8rep-b" to 2.807,
            "field-reardeltfly-s32-set06" to 13.194,
            "field-backsquat-wrapping-s36-set01" to 2.308,
            "field-ohp-prepinflated-s37-set03" to 5.633,
            "field-ohp-prepinflated-s37-set04" to 3.613,
            "field-ohp-3010-8rep-s38-set05" to 5.809,
            "field-inclinepress-3010-12rep-s38-set02" to 0.061,
        )
        expected.forEach { (fixture, p10) ->
            assertEquals(
                p10,
                VelocityEstimator.gyroQuantileDps(load(fixture), VelocityEstimator.GYRO_STILLNESS_QUANTILE),
                0.01,
                "$fixture: tenth percentile of gyro magnitude, deg/s",
            )
        }
        // And the two alternatives that do not work, measured rather than
        // asserted from reasoning: the fifth percentile fails to exclude the
        // rear delt fly, and the twenty-fifth wrongly excludes a bench set the
        // change is meant to help.
        assertTrue(
            VelocityEstimator.gyroQuantileDps(load("field-reardeltfly-s32-set06"), 0.05) < 10.0,
            "the fifth percentile would not exclude the rear delt fly",
        )
        assertTrue(
            VelocityEstimator.gyroQuantileDps(load("field-bench-3010-6rep-s37-set06"), 0.25) >= 10.0,
            "the twenty-fifth percentile would wrongly keep the gate on a bench set",
        )
    }

    @Test
    fun `the gate holds where the gyro distribution does not straddle it, and fails on the ten that do`() {
        val config = DspConfig()
        val holds = listOf(
            // Field-37 sets 8 to 10, committed on this branch for issue 96.
            // The gate holds on all three, asserted below rather than
            // predicted from a median quoted here.
            "field-assistedpullup-3010-s37-set08",
            "field-assistedpullup-3010-s37-set09",
            "field-assistedpullup-3010-s37-set10",
            "field-pullup-3010-8rep-s37-set09",
            "field-rdl-3010-10rep-s36-set04",
            "field-rdl-3010-10rep-s36-set05",
            "field-cablerow-static-8rep",
            "field-facepull-static-12rep",
            "field-pallof-static-12rep",
            "field-legcurl-1030-10rep",
            "field-legcurl-1030-12rep",
            "field-legcurl-1030-12rep-b",
            "field-legcurl-1030-12rep-c",
            "field-legpress-2010-8rep",
            "field-legpress-single-2010-8rep",
            "field-legpress-single-2011-8rep-s36-set07",
            "field-still-0rep",
            "field-ropedeadhang-hold20-s37-set11",
            // Committed on this branch for issue #133's rotation measure and
            // classified here rather than left to the corpus guard to catch:
            // median 6.42 deg/s with a tenth percentile of 0.0, so the
            // distribution sits entirely under the gate.
            "field-rdl-wrapping-s36-set05",
            "field-backsquat-10hz",
            "field-backsquat-10hz-set5",
            "field-bench-rotating-6rep",
            "field-bench-rotating-6rep-ok",
            "field-ohp-100hz-bursty",
            "field-rdl-3010-10rep",
            "field-seated-ohp-2rep",
            // Median 62.87 deg/s, well over the gate -- but a tenth percentile
            // of 13.19, also over it. The implement never stops rotating, so
            // the gate is not what costs this capture its anchors and dropping
            // it would only admit samples taken mid-rotation. The low probe of
            // the straddle test is what keeps this one out.
            "field-reardeltfly-s32-set06",
        )
        val fails = listOf(
            "field-ohp-3010-6rep-s37-set02",
            // The same seated overhead press one set earlier, and its gyro
            // magnitude exceeds the gate for the same reason set02's does.
            "field-ohp-3010-8rep-s37-set01",
            "field-bench-3010-6rep-s37-set05",
            "field-bench-3010-6rep-s37-set06",
            "field-backsquat-4011-6rep-s36-set01",
            "field-backsquat-99hz-6rep",
            "field-ohp-rotating-8rep",
            "field-ohp-rotating-8rep-b",
            // The other three committed on this branch for #133. Each
            // straddles: medians 15.34, 25.62 and 21.27 deg/s against tenth
            // percentiles of 2.31, 5.63 and 3.61.
            "field-backsquat-wrapping-s36-set01",
            "field-ohp-prepinflated-s37-set03",
            "field-ohp-prepinflated-s37-set04",
            // The two committed for issue #245. Both straddle: medians 20.97
            // and 19.47 deg/s against tenth percentiles of 5.81 and 0.06.
            "field-ohp-3010-8rep-s38-set05",
            "field-inclinepress-3010-12rep-s38-set02",
        )
        holds.forEach { assertTrue(VelocityEstimator.gyroGateApplies(load(it), config), "$it: gate should hold") }
        fails.forEach { assertFalse(VelocityEstimator.gyroGateApplies(load(it), config), "$it: gate should fail") }
        assertEquals(
            corpus.sorted(),
            (holds + fails).sorted(),
            "every capture in the corpus is classified exactly once",
        )
    }

    @Test
    fun `both probes are duty-cycle statistics, so appended idle time flips the gate`() {
        // Neither probe is a property of the MOUNT alone. Both are statistics
        // over the whole recorded window, so idle time inside the recording
        // moves them and how long the lifter left the sensor running is part of
        // what selects the policy.
        //
        // field-backsquat-99hz-6rep straddles the gate as recorded, 4444
        // samples at 99.394 Hz. Appending still samples -- gyro zeroed, nothing
        // else changed, and gyroGateApplies reads no timestamp so their arrival
        // times are irrelevant -- drops the median under the gate and the
        // clause comes back on. 451 of them do it; 450 do not. At that capture's
        // own rate that is 4.54 s of extra recording.
        val config = DspConfig()
        val samples = load("field-backsquat-99hz-6rep")
        val still = samples.first().copy(wxDps = 0.0, wyDps = 0.0, wzDps = 0.0)
        assertFalse(
            VelocityEstimator.gyroGateApplies(samples, config),
            "as recorded, this capture straddles and the clause is dropped",
        )
        assertFalse(
            VelocityEstimator.gyroGateApplies(samples + List(450) { still }, config),
            "450 appended still samples do not flip it",
        )
        assertTrue(
            VelocityEstimator.gyroGateApplies(samples + List(451) { still }, config),
            "451 appended still samples flip the clause back on",
        )
    }

    @Test
    fun `the corpus list here is the resource directory, not a hand-kept subset`() {
        // Same guard [CuedRepCoverageTest] carries, for the same reason: a
        // capture added and named nowhere would leave every assertion above
        // green while the claims they make went narrower than they read.
        val dir = File(javaClass.getResource("/field-still-0rep.csv")!!.toURI()).parentFile
        val onDisk = dir.list()!!
            .filter {
                it.startsWith("field-") && it.endsWith(".csv") &&
                    !it.endsWith("-cues.csv") && !it.endsWith("-prep.csv")
            }
            .map { it.removeSuffix(".csv") }
            .sorted()
        assertEquals(onDisk, corpus.sorted(), "every capture on the classpath is in this file's corpus")
    }
}
