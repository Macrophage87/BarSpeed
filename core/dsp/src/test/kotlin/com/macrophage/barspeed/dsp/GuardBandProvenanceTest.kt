package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ImuSample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Where [AccelArtefact.GUARD_BAND_S] comes from, measured on every deadlift
 * stream on the classpath. Issue #306.
 *
 * The deadlift is the only lift in the committed corpus that meets the floor
 * between reps, so it is the only one on which a CONTACT -- a run of samples
 * above [AccelArtefact.BOUND_G] -- sits between two reps. What the band has to
 * cover is how long such a contact goes on disturbing the signal after it, and
 * this measures that directly rather than choosing it.
 *
 * ## The measurement
 *
 * A contact is a run of samples above the bound, a new one starting where the
 * previous artefact is more than [CLUSTER_GAP_S] back. Its RINGING runs from
 * its first sample above the bound to its last sample above [RING_G], ended by
 * [QUIET_S] of samples at or below it, looked for within [HORIZON_S]. [RING_G]
 * is 2 g because that is the most total support acceleration a
 * tempo-prescribed lift produces (`AccelArtefact`'s class KDoc): a sample above
 * it is not the lift. Times are on the reconstructed clock
 * `VelocityEstimator.estimate` builds, which is the clock the band is applied
 * on.
 *
 * Each stream's LAST contact is excluded, and that is a decision a reader
 * should check: no rep follows it, because it is the bar set down at the end
 * of the set, and it rings for far longer -- up to a second here -- than any
 * contact between reps. A band sized to it would reach back across a whole
 * dead-stop pause.
 *
 * ## What is NOT claimed
 *
 * That ringing is harmless after the band ends, or that it moved any velocity
 * while it lasted. 2 g bounds what a LIFT can produce, not what a ringing
 * sample does to an integral. The band says where a contact was measured to
 * ring for on these sixteen streams; a mount that damps it differently or a
 * lift that meets the floor harder is a `[Field]` question.
 */
class GuardBandProvenanceTest {
    private fun load(fixture: String): List<ImuSample> =
        ImuCsv.decode(javaClass.getResourceAsStream("/$fixture.csv")!!.readBytes().decodeToString())

    private val streams =
        (FieldCorpus.onClasspath() + FieldCorpus.partnersOnClasspath())
            .filter { it.startsWith("field-deadlift-") }
            .sorted()

    /** Seconds of ringing after each contact of [fixture], its last included, in stream order. */
    private fun ringing(fixture: String): List<Double> {
        val samples = load(fixture)
        val timeS = VelocityEstimator.estimate(samples).timeS
        val magnitude = samples.map { FrameTransform.accMagnitudeG(it) }
        val contacts = mutableListOf<MutableList<Int>>()
        for (i in AccelArtefact.indices(samples)) {
            val open = contacts.lastOrNull()
            if (open != null && timeS[i] - timeS[open.last()] <= CLUSTER_GAP_S) {
                open += i
            } else {
                contacts += mutableListOf(i)
            }
        }
        return contacts.map { contact ->
            val first = contact.first()
            var end = contact.last()
            var j = end + 1
            while (j < samples.size && timeS[j] - timeS[first] < HORIZON_S) {
                if (magnitude[j] > RING_G) end = j
                if (timeS[j] - timeS[end] > QUIET_S) break
                j++
            }
            timeS[end] - timeS[first]
        }
    }

    @Test
    fun `the band covers the longest ringing measured after a contact between deadlift reps`() {
        assertEquals(16, streams.size, "deadlift streams on the classpath, partners included: $streams")
        val longest = streams.flatMap { ringing(it).dropLast(1) }.max()
        assertEquals(0.362, longest, 0.005, "the longest ringing, s")
        assertTrue(longest <= AccelArtefact.GUARD_BAND_S, "the band is shorter than a measured ringing")
        // Rounded UP to the next tenth and no further: a band a whole tenth
        // longer than any measured ringing reaches into a dead-stop pause for
        // nothing the corpus shows.
        assertEquals(0.4, AccelArtefact.GUARD_BAND_S, "the band, rounded up to the next tenth")
        assertEquals(
            Math.ceil(longest * 10.0) / 10.0,
            AccelArtefact.GUARD_BAND_S,
            1e-9,
            "no margin beyond the rounding",
        )
    }

    @Test
    fun `a stream's last contact, the bar set down, rings longer than the band`() {
        val lastRings = streams.map { ringing(it).last() }
        assertTrue(lastRings.max() > AccelArtefact.GUARD_BAND_S, "no set-down contact outlasts the band: $lastRings")
        assertEquals(0.999, lastRings.max(), 0.005, "the longest set-down ringing, s")
    }

    @Test
    fun `field-44 set 4's 7762 W rep carries nothing in its span and two samples in its band`() {
        // Rep 3 of the 111.1 kg set, index 2: issue #306's headline. The
        // in-span count #290 reads is zero, so that rule keeps the rep; the
        // band before the span holds the floor contact it opens after.
        val reps = SetAnalyzer.analyze(
            load("field-deadlift-straight-4rep-s44-set04"),
            LiftDirection(startsWith = com.macrophage.barspeed.model.StartPhase.CONCENTRIC),
            111.13013065245867,
        ).reps
        assertEquals(8, reps.size, "batch detections, DeadliftHeavyFieldTest's figure")
        assertEquals(7762.4, reps[2].peakPowerW, "the rep's own peak power, W")
        assertEquals(0, reps[2].artefactSamples, "artefact samples inside its span")
        assertEquals(2, reps[2].guardArtefactSamples, "artefact samples in the band before it")
        assertEquals(
            listOf(1, 0, 2, 0, 3, 3, 1, 0),
            reps.map { it.guardArtefactSamples },
            "every rep's band count, so a band that moves is seen",
        )
    }

    @Test
    fun `the band is empty at the head of a stream and clamped short of it`() {
        val timeS = DoubleArray(100) { it * 0.01 }
        fun span(start: Int) = RepSpan(start, start + 10, start + 10, start + 20, turnaroundPauseS = null)
        assertEquals(IntRange.EMPTY, AccelArtefact.guardBandBefore(span(0), timeS), "a span opening on sample 0")
        assertEquals(0 until 20, AccelArtefact.guardBandBefore(span(20), timeS), "0.2 s into the stream")
        assertEquals(20 until 60, AccelArtefact.guardBandBefore(span(60), timeS), "a whole band before sample 60")
    }

    private companion object {
        const val CLUSTER_GAP_S = 0.3
        const val RING_G = 2.0
        const val QUIET_S = 0.2
        const val HORIZON_S = 1.5
    }
}
