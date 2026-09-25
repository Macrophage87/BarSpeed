package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ImuSample
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
        "field-bench-3010-6rep-s42-set05",
        "field-bench-3010-6rep-s42-set07",
        "field-bench-rotating-6rep",
        "field-bench-rotating-6rep-ok",
        "field-cablerow-3010-8rep-s42-set08",
        "field-cablerow-3010-8rep-s42-set09",
        "field-cablerow-3010-8rep-s42-set10",
        "field-cablerow-static-8rep",
        "field-deadlift-straight-5rep-s43-set04",
        "field-deadlift-straight-5rep-s43-set05",
        "field-deadlift-straight-5rep-s43-set06",
        "field-facepull-static-12rep",
        "field-inclinepress-3010-12rep-s38-set02",
        "field-latpulldown-1120-12rep-s38-set14",
        "field-latpulldown-1120-12rep-s41-set18",
        "field-legcurl-1030-10rep",
        "field-legcurl-1030-12rep",
        "field-legcurl-1030-12rep-b",
        "field-legcurl-1030-12rep-c",
        "field-legpress-2010-8rep",
        "field-legpress-single-2010-8rep",
        "field-legpress-single-2011-8rep-s36-set07",
        "field-ohp-100hz-bursty",
        "field-ohp-3010-6rep-s37-set02",
        "field-ohp-3010-7rep-s42-set02",
        "field-ohp-3010-8rep-s37-set01",
        "field-ohp-3010-8rep-s38-set04",
        "field-ohp-3010-8rep-s38-set05",
        "field-ohp-prepinflated-s37-set03",
        "field-ohp-prepinflated-s37-set04",
        "field-ohp-rotating-8rep",
        "field-ohp-rotating-8rep-b",
        "field-pallof-static-12rep",
        "field-pullup-3010-8rep-s37-set09",
        "field-pullup-3010-8rep-s42-set11",
        "field-pullup-4010-8rep-s42-set13",
        "field-pushdown-1120-14rep-s41-set16",
        "field-rdl-3010-10rep",
        "field-rdl-3010-10rep-s36-set04",
        "field-rdl-3010-10rep-s36-set05",
        "field-rdl-wrapping-s36-set05",
        "field-reardeltfly-s32-set06",
        "field-ropedeadhang-hold20-s37-set11",
        "field-ropedeadhang-hold45-s38-set17",
        "field-ropedeadhang-hold45-s38-set18",
        "field-ropefarmershold-hold30-s42-set16",
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
    fun `the gate holds where the gyro distribution does not straddle it, and fails on the ones that do`() {
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
            // The nine captures issues #290, #255 and #301 committed between
            // them -- #301 landed the three field-43 deadlifts, this lane the
            // six field-42 sets. The gate HOLDS on EIGHT and the ninth is
            // named in the straddling list below, which is the whole reason
            // they are classified here rather than left to the corpus guard.
            // Measured medians and tenth percentiles, this round: field-42
            // set 5 6.718 / 1.623, set 7 7.141 / 1.495, set 9 9.131 / 2.912,
            // set 11 5.043 / 0.000, set 13 7.998 / 0.000; field-43 sets 4, 5
            // and 6 5.250 / 0.000, 5.430 / 0.000 and 3.943 / 0.000. Every
            // median sits UNDER the 10 deg/s band, so none straddles -- while
            // 26 to 46 percent of each capture's samples are at or above the
            // band (26-32 percent on the three deadlifts) and the peaks reach
            // 48 to 1854 deg/s.
            //
            // The three deadlifts are the corpus's first loaded barbell hinge,
            // at 61-102 kg. #284's hazard H8 predicted a bar-mounted magnet on
            // a hinge would straddle "very likely"; on this session's mount it
            // does not -- three captures, both units on the collars -- so the
            // prediction is refuted there rather than merely unconfirmed, and a
            // centre mount has never been measured. What rotates on these sets
            // rotates in bursts at the floor, not throughout.
            "field-bench-3010-6rep-s42-set05",
            "field-bench-3010-6rep-s42-set07",
            "field-cablerow-3010-8rep-s42-set09",
            "field-deadlift-straight-5rep-s43-set04",
            "field-deadlift-straight-5rep-s43-set05",
            "field-deadlift-straight-5rep-s43-set06",
            // The seven pairs issue #278 committed, base captures only --
            // a partner is not in [FieldCorpus.onClasspath] and so not in
            // this corpus. Three of the seven -- field-42 sets 9, 11 and 13
            // -- were already here, committed for issues #290 and #255. The
            // gate HOLDS on all seven. Gyro magnitude medians, re-measured at
            // this tree through `VelocityEstimator.gyroQuantileDps`: 7.058,
            // 9.131 and 7.684 deg/s on the three seated cable rows (sets 8, 9
            // and 10), 5.043 and 7.998 on the two assisted pull-ups, 0.173 on
            // the triceps pushdown -- whose ARMED unit sat on the stack, the
            // lowest figure in the whole corpus bar the still control -- and
            // 9.007 on the lat pulldown. Tenth percentiles in the same order:
            // 2.610, 2.912, 2.592, 0.000, 0.000, 0.000 and 1.784. Every median
            // is under the 10 deg/s band and no distribution straddles it.
            // These fourteen figures are recorded, not asserted -- no test
            // compares them, so a change that moves them reds nothing.
            "field-cablerow-3010-8rep-s42-set08",
            "field-cablerow-3010-8rep-s42-set10",
            "field-latpulldown-1120-12rep-s41-set18",
            "field-pullup-3010-8rep-s42-set11",
            "field-pullup-4010-8rep-s42-set13",
            "field-pushdown-1120-14rep-s41-set16",
            "field-legcurl-1030-10rep",
            "field-legcurl-1030-12rep",
            "field-legcurl-1030-12rep-b",
            "field-legcurl-1030-12rep-c",
            "field-legpress-2010-8rep",
            "field-legpress-single-2010-8rep",
            "field-legpress-single-2011-8rep-s36-set07",
            "field-still-0rep",
            "field-ropedeadhang-hold20-s37-set11",
            // The three holds committed for #259. The gate holds on all
            // three, asserted below rather than predicted here: two of them
            // are a body hanging from a rope with the unit on the assist
            // stack, and the third is a carry whose handle unit turns slowly.
            "field-ropedeadhang-hold45-s38-set17",
            "field-ropedeadhang-hold45-s38-set18",
            "field-ropefarmershold-hold30-s42-set16",
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
            // Session 38 set 14, committed for issue #72: a lat pulldown
            // declaring `sensorOnStack` AND `sensorInverted` true, whose
            // committed roll column runs -86.2372 to -84.9792 deg over 6404
            // samples -- 1.258 deg of excursion -- so the analysed stream does
            // not rotate and the gate holds. This read "measured off the
            // stack", which is the opposite of what the set declares; the
            // mount-word sweep below fixed set 4 and left this one standing.
            "field-latpulldown-1120-12rep-s38-set14",
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
            // Session 38 set 4, committed for issue #72: the same seated
            // overhead press this issue is named after, sensor off the stack,
            // and its gyro distribution straddles. This comment used to read
            // "bar-mounted, 360 deg of roll excursion, and its distribution
            // straddles", putting the 360 beside the straddle as though it
            // caused it. Both halves of that are deleted: the word bar-mounted
            // is not what `sensorOnStack` false says, and the 360 is a
            // wraparound artifact rather than a rotation -- see
            // `session 38 set 4's roll excursion is a wraparound, not a
            // rotation` below. Nothing here attributes the straddle to roll.
            "field-ohp-3010-8rep-s38-set04",
            "field-ohp-prepinflated-s37-set03",
            "field-ohp-prepinflated-s37-set04",
            // The two committed for issue #245. Both straddle: medians 20.97
            // and 19.46 deg/s against tenth percentiles of 5.81 and 0.06.
            "field-ohp-3010-8rep-s38-set05",
            "field-inclinepress-3010-12rep-s38-set02",
            // The ONE of the nine committed for issues #290 and #255 that
            // straddles: median 14.68 deg/s against a tenth percentile of
            // 0.041. The same seated overhead press as session 37's sets 1 and
            // 2 above, one session later, and it straddles for the reason they
            // do. Its eight siblings are in `holds`.
            "field-ohp-3010-7rep-s42-set02",
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
    fun `session 38 set 4's roll excursion is a wraparound, not a rotation`() {
        // `meta.json` reports `rollExcursion_deg` 360.0 for this set, and the
        // previous commit asserted the reading that number invites: that it is
        // a rotation comparable to the 31-52 deg issue #72's table quotes for
        // the same exercise, so the roll column would span no more than 52
        // deg. It failed at 359.989, which is the tell rather than the finding.
        //
        // What the column actually shows is the estimator's roll output
        // running the full circle and wrapping: it reaches both ends of the
        // representable range and spends almost all of the set within 30 deg
        // of the seam, with only 910 of 4988 samples anywhere in the middle.
        // A sensor genuinely swinging 360 deg through eight overhead presses
        // is not what this is.
        //
        // Two further reasons not to read 360.0 as rotation, both from
        // `meta.json`: this set's OTHER sensor, role b, reports 360.0 as well
        // over the same 4988 samples, and the pulldown recorded on the same
        // day reports 1.3 on role a against 17.7 on role b. A summary that
        // reports the same 360.0 for two differently-mounted sensors is
        // reporting the wrap, not the mount.
        //
        // So 360.0 IS NOT COMPARABLE to the issue's 31-52 deg column, and no
        // comment in this file may use it to explain why the gate fails here.
        // Measured from the committed column rather than from `meta.json`,
        // because the summary is what is in question.
        val roll = load("field-ohp-3010-8rep-s38-set04").map { it.rollDeg }
        assertEquals(4988, roll.size, "samples in the committed role-a stream")
        assertEquals(-179.9945, roll.min(), 1e-9, "lowest roll_deg")
        assertEquals(179.9945, roll.max(), 1e-9, "highest roll_deg")
        assertEquals(2077, roll.count { it < -150.0 }, "samples below -150 deg")
        assertEquals(2001, roll.count { it > 150.0 }, "samples above 150 deg")
        assertEquals(910, roll.count { it >= -150.0 && it <= 150.0 }, "samples anywhere between")
        // The lat pulldown from the same session is the contrast: 1.3 deg in
        // `meta.json` and a column that never approaches the seam.
        val pulldown = load("field-latpulldown-1120-12rep-s38-set14").map { it.rollDeg }
        assertTrue(pulldown.max() - pulldown.min() < 2.0, "pulldown roll span: ${pulldown.max() - pulldown.min()}")
        assertEquals(0, pulldown.count { it < -150.0 || it > 150.0 }, "pulldown samples near the seam")
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
        val onDisk = FieldCorpus.onClasspath()
        assertEquals(onDisk, corpus.sorted(), "every capture on the classpath is in this file's corpus")
    }
}
