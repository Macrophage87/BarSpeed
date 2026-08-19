package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.StartPhase
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What the live tracker counts, and whose velocity it publishes, on a lift whose
 * drive goes DOWN.
 *
 * `LiftDirection` has carried the answer since it was written --
 * `driveIsPositive`, `concentricRun`, `eccentricRun` -- and the batch path
 * consumes all three (`RepSegmentation.kt:213,220,254,262`,
 * `SetAnalyzer.kt:152,167`). `StreamingSetTracker` is the only place in the
 * repository that decides it locally, and it decides it wrong for a leg curl.
 * Issue 102.
 *
 * These pins record the behaviour before that is corrected. Nothing here is a
 * target.
 *
 * ## Ground truth
 *
 * Coverage figures come from the metronome cue tracks, windowed exactly as
 * [CuedRepCoverageTest] does it: one median cue cycle per window, taken from
 * each track's own `Down` gaps, assigned on sample ARRIVAL timestamps. A cue is
 * an instruction and not a measurement; it says which rep was called and when,
 * never what the bar did.
 */
class LiveDriveDirectionTest {
    private fun load(n: String): List<ImuSample> = ImuCsv.decode(
        javaClass.getResourceAsStream("/$n.csv")!!.readBytes().decodeToString(),
    )

    private val legCurl = LiftDirection(
        startsWith = StartPhase.CONCENTRIC,
        concentricUp = false,
        sensorInverted = true,
        plane = MovementPlane.VERTICAL,
        sensorOnStack = true,
    )

    private val press = LiftDirection(startsWith = StartPhase.ECCENTRIC)

    private val driveDown = listOf(
        "field-legcurl-1030-12rep",
        "field-legcurl-1030-12rep-b",
        "field-legcurl-1030-12rep-c",
    )

    private val driveUp = listOf(
        "field-ohp-rotating-8rep",
        "field-ohp-rotating-8rep-b",
        "field-bench-rotating-6rep-ok",
        "field-bench-rotating-6rep",
    )

    private fun state(fixture: String, d: LiftDirection): LiveSetState {
        val tracker = StreamingSetTracker(d.startsWith, DspConfig(), velocityScale = d.sensorToLifter)
        var last = LiveSetState()
        load(fixture).forEach { last = tracker.feed(it) }
        return last
    }

    private fun windows(fixture: String): List<Pair<Long, Long>> {
        val downs = CueTrack.movement(fixture, "Down")
        val gaps = downs.zipWithNext { a, b -> b - a }.sorted()
        return downs.map { it to it + gaps[gaps.size / 2] }
    }

    /** Arrival time of the sample completing each counted rep. */
    private fun repEndsMs(fixture: String, d: LiftDirection): List<Long> {
        val samples = load(fixture)
        val tracker = StreamingSetTracker(d.startsWith, DspConfig(), velocityScale = d.sensorToLifter)
        val ends = mutableListOf<Long>()
        var seen = 0
        samples.forEachIndexed { i, s ->
            val n = tracker.feed(s).repCount
            if (n > seen) {
                seen = n
                ends += samples[i].timestampMs
            }
        }
        return ends
    }

    private fun coverage(fixtures: List<String>, d: LiftDirection): IntArray {
        var exactlyOnce = 0
        var empty = 0
        var doubled = 0
        var inWindow = 0
        fixtures.forEach { fixture ->
            val w = windows(fixture)
            val hit = IntArray(w.size)
            val tol = CueTrack.WINDOW_TOLERANCE_MS.toLong()
            repEndsMs(fixture, d).forEach { t ->
                val k = w.indexOfFirst { (a, b) -> t >= a - tol && t < b + tol }
                if (k >= 0) {
                    hit[k]++
                    inWindow++
                }
            }
            exactlyOnce += hit.count { it == 1 }
            empty += hit.count { it == 0 }
            doubled += hit.count { it > 1 }
        }
        return intArrayOf(inWindow, exactlyOnce, empty, doubled)
    }

    @Test
    fun `what the live counter reports on a drive-down lift (pre-fix)`() {
        assertEquals(listOf(10, 9, 11), driveDown.map { state(it, legCurl).repCount }, "leg curl counts")
        assertEquals(listOf(3, 5, 5, 1), driveUp.map { state(it, press).repCount }, "press counts")
    }

    @Test
    fun `how well those counts cover the reps that were called (pre-fix)`() {
        // in-window, covered exactly once, uncovered, covered twice.
        assertEquals(
            listOf(29, 15, 14, 7),
            coverage(driveDown, legCurl).toList(),
            "leg curl: 36 cued reps",
        )
        assertEquals(
            listOf(14, 14, 14, 0),
            coverage(driveUp, press).toList(),
            "press: 28 cued reps",
        )
    }

    @Test
    fun `the per-rep velocities the tracker publishes for a leg curl (pre-fix)`() {
        // LiveSetState calls these the CONCENTRIC mean and peak. On this lift
        // the concentric goes down, and the runs these are taken from go up.
        val s = state("field-legcurl-1030-12rep", legCurl)
        assertEquals(10, s.repMeanVelocities.size, "one mean per counted rep")
        assertEquals(10, s.repPeakVelocities.size, "one peak per counted rep")
        assertEquals(0.128, s.repMeanVelocities.first(), 5e-3, "first published mean")
        assertEquals(0.078, s.repMeanVelocities.last(), 5e-3, "last published mean")
        assertEquals(0.266, s.repPeakVelocities.first(), 5e-3, "first published peak")
    }

    @Test
    fun `LiftDirection already knows which way the drive goes`() {
        // The value the tracker needs exists and is consumed by the batch path.
        // It simply has no way in: StreamingSetTracker takes startsWith, config,
        // a sample rate and velocityScale, and nothing else.
        assertEquals(false, legCurl.driveIsPositive, "a leg curl drives down")
        assertEquals(true, press.driveIsPositive, "a press drives up")
        assertEquals(RunType.DOWN, legCurl.concentricRun, "so its drive is the DOWN run")
        assertEquals(RunType.UP, press.concentricRun, "and a press's drive is the UP run")
    }
}
