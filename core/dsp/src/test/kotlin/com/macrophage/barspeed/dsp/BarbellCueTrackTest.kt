package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ImuSample
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Metronome cue tracks for the four ECCENTRIC-FIRST BARBELL captures, seated
 * overhead press and bench press, session 26 on 2026-08-17 at prescribed 3010.
 *
 * Until now every per-rep result in this repository came from three seated
 * leg-curl captures -- concentric-first, stack-mounted, drive-down -- while the
 * rep-counting defects under investigation live on the eccentric-first,
 * bar-mounted captures, which carried a per-set hand count and nothing else.
 *
 * These four tracks were not recorded for this purpose. They already existed:
 * the app writes a cue CSV for every guided set except carries and holds, and
 * the four IMU captures already in the corpus ARE session-26 sets 01, 04, 05
 * and 06, byte-identical once line endings are normalised. Only the cue files
 * were left behind. Committing them costs 2,620 bytes.
 *
 * ## A cue is an instruction, not a measurement
 *
 * The track records what the app told the lifter to do and when it said so. It
 * does not record what the barbell did. The lifter was following the
 * metronome, so the two are closely coupled -- and that is exactly the kind of
 * claim that hardens into "identical" unless it is written where it is read.
 * Nothing here may be cited as evidence of what the bar actually did.
 *
 * ## The announcement beat, which is the trap in reading these
 *
 * The same defect [LegCurlCueTrackTest] documents is present here, and it bites
 * in the opposite phase. Tempo 3010 is 3 s eccentric, no pause, 1 s concentric,
 * no pause -- a 4 s cycle. The metronome delivers an extra beat per rep for the
 * rep announcement, so the observed cycle is 5.006 s:
 *
 * - "Down" to "Up" is 3.004 s. This is the eccentric, and it matches.
 * - "Up" to the rep announcement is 1.001 s. THIS is the concentric.
 * - The announcement to the next "Down" is 1.001 s, and is the extra beat.
 * - So "Up" to the next "Down" is 2.002 s, which is the concentric PLUS the
 *   announcement. Reading that as the prescription halves the velocity.
 *
 * An earlier report of these captures did exactly that and called the
 * concentric 2.002 s. It is 1.001 s. Both intervals are asserted below so the
 * choice is never made from memory.
 *
 * ## Two clocks
 *
 * See [CueTrack]. Cue times are wall-clock; the DSP reports phase boundaries on
 * a uniform reconstructed clock. The skew between them is pinned here.
 *
 * ## These recordings predate the cadence fix
 *
 * These sets were paced before issue 106 removed the one-second announcement
 * beat. The beat is a row of its own, so the intervals that SPAN it change and
 * the intervals inside a stroke do not: `Down` to `Up` stays 3.004 s and `Up`
 * to the announcement stays 1.001 s, while the announcement-to-`Down` interval
 * and the 5.006 s cycle disappear and become 4.005 s of continuous cycling.
 * Everything here stays valid as a recording of what the metronome did on
 * 2026-08-17; none of it describes current behaviour.
 */
class BarbellCueTrackTest {
    private fun load(n: String): List<ImuSample> = ImuCsv.decode(
        javaClass.getResourceAsStream("/$n.csv")!!.readBytes().decodeToString(),
    )

    /** Fixture to the count the lifter performed, as the regression suite asserts it. */
    private val performed = mapOf(
        "field-ohp-rotating-8rep" to 8,
        "field-ohp-rotating-8rep-b" to 8,
        "field-bench-rotating-6rep-ok" to 6,
        "field-bench-rotating-6rep" to 6,
    )

    @Test
    fun `the metronome called exactly the reps the corpus records as performed`() {
        // The corroboration these fixtures exist for. Those performed counts
        // were hand counts written into a test with nothing behind them; the
        // cue track is an independent record of the same quantity.
        // FieldDataRegressionTest asserts the same equality from its own side,
        // so the two cannot drift apart silently in either direction.
        performed.forEach { (fixture, count) ->
            assertEquals(count, CueTrack.calledReps(fixture), "$fixture: reps the metronome called")
        }
    }

    @Test
    fun `the cue track lies inside the capture it annotates`() {
        performed.keys.forEach { fixture ->
            val samples = load(fixture)
            val cues = CueTrack.read(fixture)
            val head = cues.first().timestampMs - samples.first().timestampMs
            val tail = samples.last().timestampMs - cues.last().timestampMs
            // The Ready cue lands a few tens of ms BEFORE the first sample, so
            // head is negative and small; the re-rack leaves seconds of capture
            // after the last cue.
            assertTrue(head in -60..0, "$fixture: Ready cue to first sample was $head ms")
            assertTrue(tail > 4_000, "$fixture: only $tail ms of capture after the last cue")
        }
    }

    @Test
    fun `the prescribed 3010 phases, and the announcement beat that hides the concentric`() {
        performed.keys.forEach { fixture ->
            val downs = CueTrack.movement(fixture, "Down")
            val ups = CueTrack.movement(fixture, "Up")
            val announcements = CueTrack.read(fixture)
                .filter { it.label.startsWith("Rep ") || it.label == "Last rep" || it.label == "Done" }
                .map { it.timestampMs }
            assertEquals(downs.size, ups.size, "$fixture: every Down must have an Up")
            assertEquals(downs.size, announcements.size, "$fixture: every rep must be announced")

            val ecc = median(downs.zip(ups) { d, u -> (u - d) / 1000.0 })
            val con = median(ups.zip(announcements) { u, a -> (a - u) / 1000.0 })
            val beat = median(announcements.zip(downs.drop(1)) { a, d -> (d - a) / 1000.0 })
            val cycle = median(downs.zip(downs.drop(1)) { a, b -> (b - a) / 1000.0 })

            assertEquals(3.004, ecc, 2e-3, "$fixture: Down to Up, the eccentric")
            assertEquals(1.001, con, 2e-3, "$fixture: Up to announcement, the CONCENTRIC")
            assertEquals(1.001, beat, 2e-3, "$fixture: announcement to Down, the extra beat")
            assertEquals(5.006, cycle, 2e-3, "$fixture: cycle, against a prescribed 4.000")
        }
    }

    private fun median(v: List<Double>) = v.sorted()[v.size / 2]

    @Test
    fun `how far the reconstructed clock drifts from the arrival clock`() {
        // Pinned so CueTrack.MAX_SKEW_MS cannot quietly stop being true. Zero
        // at both ends of every capture by construction, because the rate is
        // (n - 1) / span; the excursion is in the middle, where burst arrivals
        // bunch samples that the uniform clock spreads evenly.
        val worst = mapOf(
            "field-ohp-rotating-8rep" to 58.5,
            "field-ohp-rotating-8rep-b" to 105.3,
            "field-bench-rotating-6rep-ok" to 65.3,
            "field-bench-rotating-6rep" to 52.5,
        )
        var corpusWorst = 0.0
        worst.forEach { (fixture, expected) ->
            val samples = load(fixture)
            val n = samples.size
            val spanS = (samples.last().timestampMs - samples.first().timestampMs) / 1000.0
            val dt = 1.0 / VelocityEstimator.measureSampleRate(n, spanS)
            var maxSkewMs = 0.0
            for (i in 0 until n) {
                val skew = i * dt - (samples[i].timestampMs - samples.first().timestampMs) / 1000.0
                if (abs(skew) > abs(maxSkewMs)) maxSkewMs = skew
            }
            assertEquals(expected, abs(maxSkewMs) * 1000.0, 0.5, "$fixture: worst clock skew, ms")
            corpusWorst = maxOf(corpusWorst, abs(maxSkewMs) * 1000.0)
        }
        assertEquals(CueTrack.MAX_SKEW_MS, corpusWorst, 0.5, "the constant must be the measurement")
        assertTrue(
            CueTrack.WINDOW_TOLERANCE_MS > corpusWorst,
            "tolerance ${CueTrack.WINDOW_TOLERANCE_MS} must exceed the worst skew $corpusWorst",
        )
        // And stay under half the shortest cued phase, or a rep could match its
        // neighbour instead of itself.
        assertTrue(CueTrack.WINDOW_TOLERANCE_MS < 1.001 * 1000.0 / 2, "tolerance must not span two phases")
    }
}
