package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.StartPhase
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What a declared pulley ratio does to the number of reps this app believes
 * happened. Issue #70.
 *
 * `SetAnalyzer.analyze` maps the velocity series into the lifter's frame with
 * `mappedToLifter(direction.sensorToLifter)`, and `StreamingSetTracker`
 * multiplies each live sample by the same factor. Both then classify the
 * result against `DspConfig.pauseBandMps`, `startThresholdMps`, `minRomM` and
 * `maxRunDisplacementM` -- four numbers fitted on captures where the sensor
 * travelled 1:1 with the load. Nothing converts them into the frame the series
 * is now in, so `travelRatio` moves the limits relative to the signal and a
 * pure GEOMETRY declaration decides how many reps there were.
 *
 * The two captures below are the corpus's barbell case and its cable-stack
 * case, declared exactly as the rest of the corpus declares them:
 *
 *  - `field-backsquat-10hz` -- back squat, landed at `d4aa6ed1` from the
 *    2026-07-23 10 Hz session; `LiveDisplacementCapTest`'s corpus records the
 *    lifter performing 5 reps.
 *  - `field-legcurl-1030-12rep` -- seated leg curl, landed at `1613288c` from
 *    2026-08-18, prescribed 1030, sensor on the stack and inverted, 12 reps
 *    performed with a metronome cue track beside the capture.
 *
 * NEITHER CAPTURE DECLARES A RATIO OTHER THAN 1.0, and no capture in
 * `BarSpeed-field-captures` does: `travelRatio` reads 1.0 on all 64 sets of
 * sessions 30, 31, 32, 36 and 37. The ratio is swept here rather than
 * recorded, so this file measures a latent defect -- one no stored set has
 * been through -- and says nothing about any set already on disk.
 *
 * What is pinned below is INVARIANCE: the batch count, the live count and
 * `countTrusted` must not move when nothing but the declared geometry does.
 * The VALUES they hold to are still characterization -- 6 and 12 are what the
 * pipeline resolves at 1:1 today, not what the lifter performed -- so a change
 * that made the counts right would move them, and should.
 */
class TravelRatioSegmentationTest {
    private fun load(n: String) = ImuCsv.decode(
        javaClass.getResourceAsStream("/$n")!!.readBytes().decodeToString(),
    )

    /** Back squat off a rack: eccentric first, sensor on the bar, no cable. */
    private fun barbell(ratio: Double) = LiftDirection(startsWith = StartPhase.ECCENTRIC, travelRatio = ratio)

    /**
     * Seated leg curl, as `LiveDisplacementCapTest` declares it: the drive goes
     * DOWN and the sensor rides the stack, so `sensorToLifter` is negative.
     * That makes this the case that proves the conversion keys on the
     * MAGNITUDE of the ratio and not on the signed factor.
     */
    private fun stack(ratio: Double) = LiftDirection(
        startsWith = StartPhase.CONCENTRIC,
        concentricUp = false,
        sensorInverted = true,
        travelRatio = ratio,
        plane = MovementPlane.VERTICAL,
        sensorOnStack = true,
    )

    private val ratios = listOf(0.25, 0.5, 0.75, 1.0, 1.5, 2.0, 3.0)

    private fun batchCounts(file: String, direction: (Double) -> LiftDirection): List<Int> {
        val samples = load(file)
        return ratios.map { SetAnalyzer.analyze(samples, direction(it), loadKg = 60.0).reps.size }
    }

    private fun live(file: String, direction: (Double) -> LiftDirection): List<LiveSetState> {
        val samples = load(file)
        return ratios.map { ratio ->
            val tracker = StreamingSetTracker.forLift(direction(ratio))
            var last = tracker.feed(samples.first())
            samples.drop(1).forEach { last = tracker.feed(it) }
            last
        }
    }

    @Test
    fun `a declared ratio leaves the batch rep count alone on a barbell squat`() {
        // 5 reps were performed. The pipeline resolves 6 of them at the ratio
        // the lifter actually recorded, which is its own defect and not this
        // one; what belongs to issue #70 is that the answer moved at all.
        // Before the conversion this read 2, 5, 5, 6, 6, 5, 5.
        assertEquals(
            List(ratios.size) { 6 },
            batchCounts("field-backsquat-10hz.csv", ::barbell),
            "batch reps at ratios $ratios",
        )
    }

    @Test
    fun `a declared ratio leaves the batch rep count alone on a cable stack leg curl`() {
        // 12 reps were performed and the metronome cue track says when. Before
        // the conversion a 1:4 declaration erased seven of them and a 3:1
        // invented five: 5, 10, 12, 12, 12, 13, 17. This is also the sign
        // check -- sensorToLifter is NEGATIVE on this lift, so a conversion
        // that used the signed factor rather than its magnitude would drive
        // every limit negative and admit every run.
        assertEquals(
            List(ratios.size) { 12 },
            batchCounts("field-legcurl-1030-12rep.csv", ::stack),
            "batch reps at ratios $ratios",
        )
    }

    @Test
    fun `a declared ratio leaves the live rep count the lifter reads mid-set alone`() {
        // The live tracker is the same defect in the same module, and it is
        // the half a lifter is looking at while the set is happening. Before
        // the conversion these read 2, 4, 4, 4, 4, 3, 2 and 0, 7, 7, 8, 9, 9,
        // 13.
        assertEquals(
            List(ratios.size) { 4 },
            live("field-backsquat-10hz.csv", ::barbell).map { it.repCount },
            "live reps at ratios $ratios, barbell",
        )
        assertEquals(
            List(ratios.size) { 8 },
            live("field-legcurl-1030-12rep.csv", ::stack).map { it.repCount },
            "live reps at ratios $ratios, stack",
        )
    }

    @Test
    fun `a declared ratio does not decide whether the live count calls itself trusted`() {
        // countTrusted is latched false when a run carries past
        // maxRunDisplacementM, and that bound is one of the four applied to the
        // scaled series. So the app's own statement about whether it could be
        // believed was set by a geometry declaration: the same squat read
        // trusted at 1:1 and distrusted at 3:2, with nothing about the lift
        // changed. Before the conversion, true x4 then false x3 on the squat,
        // and true then false x6 on the leg curl.
        //
        // The leg curl staying FALSE at every ratio is not a failure to fix
        // anything. That capture's integrator does run away -- issue #94's
        // subject -- and the flag is telling the truth about it. What #70 asks
        // is that the answer be the lift's, not the declaration's.
        assertEquals(
            List(ratios.size) { true },
            live("field-backsquat-10hz.csv", ::barbell).map { it.countTrusted },
            "countTrusted at ratios $ratios, barbell",
        )
        assertEquals(
            List(ratios.size) { false },
            live("field-legcurl-1030-12rep.csv", ::stack).map { it.countTrusted },
            "countTrusted at ratios $ratios, stack",
        )
    }

    @Test
    fun `the 1 to 1 row is what every recorded set has been through`() {
        // Pinned separately and permanently: whatever the conversion does to
        // the other rows, this row must not move, because it is the only one
        // any capture in the corpus has ever been analysed at. If a fix for
        // #70 changes these five figures it has changed a stored set's
        // published rep count, which is not what #70 asks for.
        val oneToOne = ratios.indexOf(1.0)
        assertEquals(6, batchCounts("field-backsquat-10hz.csv", ::barbell)[oneToOne], "barbell batch at 1:1")
        assertEquals(12, batchCounts("field-legcurl-1030-12rep.csv", ::stack)[oneToOne], "stack batch at 1:1")
        val barbellLive = live("field-backsquat-10hz.csv", ::barbell)[oneToOne]
        assertEquals(4, barbellLive.repCount, "barbell live at 1:1")
        assertEquals(true, barbellLive.countTrusted, "barbell countTrusted at 1:1")
        assertEquals(8, live("field-legcurl-1030-12rep.csv", ::stack)[oneToOne].repCount, "stack live at 1:1")
    }
}
