package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.StartPhase
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * When the live tracker stops being able to stand behind its own rep count,
 * and what it says about it. Issue 94.
 *
 * ## What is being detected
 *
 * A movement run that carries further than [DspConfig.maxRunDisplacementM] --
 * the same bound `updateRuns` already uses to throw the run away. A run that
 * long is not a phase of a lift. It is the integrator with no zero: the live
 * velocity never returns to the dead band, so one run swallows several reps,
 * and once it is over the bound every rep inside it is discarded outright. The
 * longest in this corpus carries 127.405 m, pinned in [LiveCapCalibrationTest].
 *
 * ## What the flag does NOT do
 *
 * It changes no count. Every live rep count in this corpus is identical with
 * and without it, pinned below against the same figures
 * [LiveUnderCountAttributionTest] holds. Nothing in `:app` reads it, no screen
 * draws it and no cue is spoken or withheld because of it. It exists so that a
 * later change has something other than an `Int` to read: `repCount` cannot
 * express "I have lost track", so a stale low count reads exactly like a
 * correct low count, and every consumer today treats it as a fact.
 *
 * ## Why this is worth a flag rather than a bigger fix
 *
 * The count is monotone. There is no mechanism by which it re-synchronises
 * after a miss, so one missed rep does not cost one number -- it makes every
 * later number of that set wrong. Measured here: 32 of the 101 reps the counter
 * reports corpus-wide are reported AFTER the integrator had already lost its
 * zero, 27 of them on captures where the metronome says what actually
 * happened.
 */
class LiveIntegratorRunawayTest {
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

    /** Session 32 set 6's exported geometry block, as [SetEndWindowTest] reads it. */
    private val rearDeltFly = LiftDirection(
        startsWith = StartPhase.CONCENTRIC,
        concentricUp = true,
        sensorInverted = false,
        travelRatio = 1.0,
        plane = MovementPlane.VERTICAL,
        sensorOnStack = false,
    )

    private fun ecc() = LiftDirection(startsWith = StartPhase.ECCENTRIC)

    private fun con() = LiftDirection(startsWith = StartPhase.CONCENTRIC)

    private data class Capture(val fixture: String, val direction: LiftDirection, val cueTracked: Boolean)

    /**
     * Twenty-one committed captures, in the geometry the rest of the suite reads
     * them with. NOT all of them: field-legpress-single-2011-8rep-s36-set07
     * landed for issue #93 and is deliberately excluded, for the reason
     * `CuedRepCoverageTest.outsideCorpusTotals` states -- these figures are a
     * series against the twenty-one, and the 101 quoted in this file's KDoc is
     * that population.
     */
    private val corpus = listOf(
        Capture("field-ohp-rotating-8rep", ecc(), true),
        Capture("field-ohp-rotating-8rep-b", ecc(), true),
        Capture("field-bench-rotating-6rep-ok", ecc(), true),
        Capture("field-bench-rotating-6rep", ecc(), true),
        Capture("field-backsquat-99hz-6rep", ecc(), true),
        Capture("field-rdl-3010-10rep", ecc(), true),
        Capture("field-legpress-2010-8rep", ecc(), true),
        Capture("field-legpress-single-2010-8rep", con(), true),
        Capture("field-legcurl-1030-12rep", legCurl, true),
        Capture("field-legcurl-1030-12rep-b", legCurl, true),
        Capture("field-legcurl-1030-12rep-c", legCurl, true),
        Capture("field-legcurl-1030-10rep", legCurl, true),
        Capture("field-reardeltfly-s32-set06", rearDeltFly, true),
        Capture("field-ohp-100hz-bursty", con(), false),
        Capture("field-seated-ohp-2rep", con(), false),
        Capture("field-backsquat-10hz", ecc(), false),
        Capture("field-backsquat-10hz-set5", ecc(), false),
        Capture("field-cablerow-static-8rep", con(), false),
        Capture("field-facepull-static-12rep", con(), false),
        Capture("field-pallof-static-12rep", con(), false),
        Capture("field-still-0rep", ecc(), false),
    )

    /** What one replay of a capture through the shipped tracker reveals about the flag. */
    private data class Replay(
        val trusted: Boolean,
        val repCount: Int,
        /** [repCount] at the moment the flag first went false, or null if it never did. */
        val repsAtTrip: Int?,
        /** True if the flag ever returned to true after going false. */
        val everRecovered: Boolean,
    )

    private fun replay(c: Capture): Replay {
        val tracker = StreamingSetTracker.forLift(c.direction)
        var repsAtTrip: Int? = null
        var everRecovered = false
        var last = LiveSetState()
        load(c.fixture).forEach { sample ->
            last = tracker.feed(sample)
            if (repsAtTrip == null && !last.countTrusted) repsAtTrip = last.repCount
            if (repsAtTrip != null && last.countTrusted) everRecovered = true
        }
        return Replay(last.countTrusted, last.repCount, repsAtTrip, everRecovered)
    }

    private fun replays() = corpus.associateWith { replay(it) }

    @Test
    fun `a tracker that has seen nothing trusts its count`() {
        assertTrue(LiveSetState().countTrusted, "the default state")
        assertTrue(
            StreamingSetTracker.forLift(LiftDirection(startsWith = StartPhase.ECCENTRIC)).state.countTrusted,
            "a tracker before its first sample",
        )
    }

    @Test
    fun `the flag on every committed capture`() {
        // Named per capture rather than counted, so a capture changing sides
        // cannot be hidden by another changing back.
        assertEquals(
            mapOf(
                "field-ohp-rotating-8rep" to false,
                "field-ohp-rotating-8rep-b" to false,
                "field-bench-rotating-6rep-ok" to true,
                "field-bench-rotating-6rep" to false,
                "field-backsquat-99hz-6rep" to false,
                "field-rdl-3010-10rep" to false,
                "field-legpress-2010-8rep" to false,
                "field-legpress-single-2010-8rep" to false,
                "field-legcurl-1030-12rep" to false,
                "field-legcurl-1030-12rep-b" to false,
                "field-legcurl-1030-12rep-c" to false,
                "field-legcurl-1030-10rep" to true,
                "field-reardeltfly-s32-set06" to false,
                "field-ohp-100hz-bursty" to false,
                "field-seated-ohp-2rep" to false,
                "field-backsquat-10hz" to true,
                "field-backsquat-10hz-set5" to true,
                "field-cablerow-static-8rep" to false,
                "field-facepull-static-12rep" to true,
                "field-pallof-static-12rep" to true,
                "field-still-0rep" to true,
            ),
            replays().entries.associate { (c, r) -> c.fixture to r.trusted },
            "countTrusted at the last sample of each capture",
        )
    }

    @Test
    fun `it fires on eleven of the thirteen captures that carry truth, and on neither no-rep control`() {
        // Both directions, because a detector that fires on everything is not a
        // detector. The two no-rep controls are the discriminating cases: a set
        // spent standing still and a set spent racked for two quiet minutes both
        // keep the flag, so this is not simply "any long recording".
        val r = replays()
        val cued = r.filterKeys { it.cueTracked }
        assertEquals(13, cued.size, "captures carrying a cue track")
        assertEquals(11, cued.count { !it.value.trusted }, "of those, ones where the integrator ran away")
        assertEquals(
            listOf("field-bench-rotating-6rep-ok", "field-legcurl-1030-10rep"),
            cued.filterValues { it.trusted }.keys.map { it.fixture }.sorted(),
            "the two cue-tracked captures it does not fire on",
        )
        assertTrue(
            r.entries.first { it.key.fixture == "field-still-0rep" }.value.trusted,
            "a motionless sensor keeps the flag",
        )
        assertTrue(
            r.entries.first { it.key.fixture == "field-backsquat-10hz-set5" }.value.trusted,
            "two quiet minutes on the rack keep the flag",
        )
    }

    @Test
    fun `where it fires it fires early, and the count keeps rising afterwards`() {
        // The rep count at the instant the flag drops. Three of the thirteen
        // cued captures lose their zero before a single rep is counted, and
        // field-reardeltfly-s32-set06 -- which reports 0 against 12 performed --
        // is one of them.
        val r = replays()
        assertEquals(
            mapOf(
                "field-ohp-rotating-8rep" to 2,
                "field-ohp-rotating-8rep-b" to 2,
                "field-bench-rotating-6rep" to 0,
                "field-backsquat-99hz-6rep" to 0,
                "field-rdl-3010-10rep" to 1,
                "field-legpress-2010-8rep" to 1,
                "field-legpress-single-2010-8rep" to 5,
                "field-legcurl-1030-12rep" to 5,
                "field-legcurl-1030-12rep-b" to 2,
                "field-legcurl-1030-12rep-c" to 6,
                "field-reardeltfly-s32-set06" to 0,
                "field-ohp-100hz-bursty" to 1,
                "field-seated-ohp-2rep" to 1,
                "field-cablerow-static-8rep" to 2,
            ),
            r.entries.filter { it.value.repsAtTrip != null }
                .associate { (c, v) -> c.fixture to v.repsAtTrip },
            "reps already counted when the flag dropped",
        )
        // The number that matters to a lifter reading the counter: reps the
        // tracker went on to report after it had lost its zero. Every one of
        // them is spoken and drawn today with the same confidence as the first.
        val after = r.values.filter { it.repsAtTrip != null }.sumOf { it.repCount - it.repsAtTrip!! }
        val afterCued = r.filterKeys { it.cueTracked }.values
            .filter { it.repsAtTrip != null }
            .sumOf { it.repCount - it.repsAtTrip!! }
        assertEquals(32, after, "reps counted after the integrator lost its zero, corpus-wide")
        assertEquals(27, afterCued, "of those, ones on captures with a cue track")
    }

    @Test
    fun `the flag latches for the set and is never cleared`() {
        // Not vacuous: the count of captures it drops on is asserted here too,
        // so a flag that never drops fails this rather than passing it by
        // having nothing to latch.
        val r = replays()
        assertEquals(14, r.count { !it.value.trusted }, "captures where the flag drops at all")
        assertEquals(
            emptyList(),
            r.filterValues { it.everRecovered }.keys.map { it.fixture },
            "captures where the flag went back to true",
        )
    }

    @Test
    fun `no rep count moves`() {
        // The whole claim of this change: it adds a fact and removes none. These
        // are the same twenty-one figures `the per-capture live counts` holds in
        // LiveUnderCountAttributionTest, restated here so that a change to the
        // detector cannot alter a count without both files saying so.
        assertEquals(
            mapOf(
                "field-ohp-rotating-8rep" to 3,
                "field-ohp-rotating-8rep-b" to 5,
                "field-bench-rotating-6rep-ok" to 5,
                "field-bench-rotating-6rep" to 1,
                "field-backsquat-99hz-6rep" to 4,
                "field-rdl-3010-10rep" to 7,
                "field-legpress-2010-8rep" to 2,
                "field-legpress-single-2010-8rep" to 6,
                "field-legcurl-1030-12rep" to 8,
                "field-legcurl-1030-12rep-b" to 6,
                "field-legcurl-1030-12rep-c" to 9,
                "field-legcurl-1030-10rep" to 8,
                "field-reardeltfly-s32-set06" to 0,
                "field-ohp-100hz-bursty" to 3,
                "field-seated-ohp-2rep" to 1,
                "field-backsquat-10hz" to 4,
                "field-backsquat-10hz-set5" to 1,
                "field-cablerow-static-8rep" to 5,
                "field-facepull-static-12rep" to 11,
                "field-pallof-static-12rep" to 12,
                "field-still-0rep" to 0,
            ),
            replays().entries.associate { (c, r) -> c.fixture to r.repCount },
            "live rep count per capture, unchanged by the flag",
        )
    }

    @Test
    fun `the flag says the same thing as the run the qualification gate discarded`() {
        // The flag is tested WHILE a run accumulates, so it can in principle
        // fire on a run that is still open when the set ends and that the
        // qualification gate therefore never judges. On this corpus it does not:
        // every capture whose flag drops also contains a CLOSED run beyond the
        // bound, and every capture that keeps its flag contains none. So nothing
        // pinned above rests on the difference between the two readings, and if
        // a capture is ever added where they disagree, this is what says so.
        val c = DspConfig()
        corpus.forEach { capture ->
            val samples = load(capture.fixture)
            val dt = 1.0 / VelocityEstimator.measureSampleRate(
                samples.size,
                (samples.last().timestampMs - samples.first().timestampMs) / 1000.0,
            )
            val tracker = StreamingSetTracker.forLift(capture.direction, c)
            var type = 0
            var displacement = 0.0
            var closedRunOverBound = false
            samples.forEach { sample ->
                val v = tracker.feed(sample).velocityMps
                val k = when {
                    v > c.pauseBandMps -> 1
                    v < -c.pauseBandMps -> -1
                    else -> 0
                }
                if (k == type) {
                    if (k != 0) displacement += abs(v) * dt
                } else {
                    if (type != 0 && displacement > c.maxRunDisplacementM) closedRunOverBound = true
                    type = k
                    displacement = if (k != 0) abs(v) * dt else 0.0
                }
            }
            assertEquals(
                closedRunOverBound,
                !tracker.state.countTrusted,
                "${capture.fixture}: a closed run beyond the bound against the flag",
            )
        }
    }
}
