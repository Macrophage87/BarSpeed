package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.StartPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which reps a SET's peak figures and its velocity loss may be taken over,
 * issue #306, on the captures that issue and field-45's comment on it name.
 *
 * `AccelArtefact.isPeakEligible` is the rule: no sample above the bound inside
 * the rep's span (#290), none in the guard band before it
 * (`AccelArtefact.GUARD_BAND_S`, `GuardBandProvenanceTest`), and a displacement
 * the analysis can bound (`RomBound`, #291). A set's peak is then withheld
 * where it would fall below the mean of the same quantity the set publishes.
 *
 * EACH CLAUSE IS PINNED ON ITS OWN. On the headline rep two clauses both fire,
 * so a test of the whole rule would stay green with either removed. The
 * methods below mask the other inputs to "eligible" and ask whether the clause
 * under test still removes the rep, so each clause has a test that reds when
 * that clause alone is deleted.
 *
 * NO REP COUNT AND NO PER-REP FIGURE MOVES. The rows keep what their own
 * window measured; only the set-level claims narrow.
 */
class PeakEligibilityTest {
    private val deadlift = LiftDirection(startsWith = StartPhase.CONCENTRIC)

    private fun load(fixture: String): List<ImuSample> =
        ImuCsv.decode(javaClass.getResourceAsStream("/$fixture.csv")!!.readBytes().decodeToString())

    private fun field44(set: String, loadKg: Double) = SetAnalyzer.analyze(load(set), deadlift, loadKg).reps

    private fun set3() = field44("field-deadlift-straight-5rep-s44-set03", 102.05828325225797)

    private fun set4() = field44("field-deadlift-straight-4rep-s44-set04", 111.13013065245867)

    private fun set5() = field44("field-deadlift-straight-2rep-s44-set05", 120.20197805265938)

    /** [reps] with the guard band's input set to "clean", so only the other clauses decide. */
    private fun withoutBand(reps: List<RepAnalysis>) = reps.map { it.copy(guardArtefactSamples = 0) }

    /** [reps] with the bound's input set to "bounded", so only the other clauses decide. */
    private fun withoutBound(reps: List<RepAnalysis>) = reps.map { it.copy(romBounded = true) }

    @Test
    fun `field-44 set 4 does not publish its floor-contact rep's 7762 W as the set's peak`() {
        val reps = set4()
        assertEquals(8, reps.size, "detections, unchanged")
        assertEquals(7762.4, reps[2].peakPowerW, "rep 3's own row, unchanged")
        assertFalse(AccelArtefact.isPeakEligible(reps[2]), "rep 3 is in the peak population")
        // No rep of the set is eligible: every one is unbounded, so both
        // peaks are absent rather than taken over what is left.
        assertEquals(emptyList(), AccelArtefact.peakEligible(reps).map { it.index }, "eligible reps")
        assertNull(AccelArtefact.setPeakPowerW(reps), "the set's published peak power")
        assertNull(AccelArtefact.setPeakConVelMps(reps), "the set's published peak velocity")
    }

    @Test
    fun `the guard band alone keeps field-44 set 4's rep 3 out of the peak`() {
        val reps = withoutBound(set4())
        assertEquals(0, reps[2].artefactSamples, "the in-span count #290 reads keeps the rep")
        assertFalse(AccelArtefact.isPeakEligible(reps[2]), "with the bound masked, the band must remove it")
        assertEquals(listOf(7), AccelArtefact.peakEligible(reps).map { it.index }, "reps eligible on span and band")
        assertEquals(634.1, AccelArtefact.setPeakPowerW(reps), "the peak over what span and band leave")
    }

    @Test
    fun `the bound alone keeps field-44 set 4's rep 3 out of the peak`() {
        val reps = withoutBand(set4())
        assertEquals(false, reps[2].romBounded, "rep 3's displacement is not bounded")
        assertFalse(AccelArtefact.isPeakEligible(reps[2]), "with the band masked, the bound must remove it")
        assertNull(AccelArtefact.setPeakPowerW(reps), "no rep of the set is bounded")
    }

    @Test
    fun `field-44 set 5 publishes no peak below its own mean`() {
        val reps = set5()
        assertEquals(4, reps.size, "detections, unchanged")
        val published = AccelArtefact.setPeakConVelMps(reps)
        val mean = reps.map { it.meanConVelMps }.average()
        assertTrue(published == null || published >= mean, "a published peak $published below the mean $mean")
        assertNull(published, "set 5's peak velocity, which read 0.376 against a 0.382 mean")
    }

    @Test
    fun `below-mean withholding alone removes set 5's 0_376 m per s`() {
        // Band and bound masked: only #290's in-span clause and the
        // below-mean rule decide. Rep 2 (index 1) is the one rep with no
        // sample inside its span, and its 0.376 m/s is below the set's 0.382
        // mean -- a "peak" only because the reps the mean is made of were
        // left out.
        val reps = withoutBand(withoutBound(set5()))
        assertEquals(listOf(1), AccelArtefact.peakEligible(reps).map { it.index }, "reps clean inside their span")
        assertEquals(0.376, reps[1].peakConVelMps, "that rep's own peak")
        assertEquals(0.382, Math.round(reps.map { it.meanConVelMps }.average() * 1000.0) / 1000.0, "the set's mean")
        assertNull(AccelArtefact.setPeakConVelMps(reps), "a peak below its own mean is published")
    }

    @Test
    fun `field-44 set 3 publishes no velocity loss off a withheld reference`() {
        val reps = set3()
        assertEquals(13, reps.size, "detections, unchanged")
        // The pre-#306 figure: 1 - 0.155/1.025, the reference being rep 12
        // (index 11), whose span carries ten artefact samples.
        assertEquals(1.025, reps.maxOf { it.meanConVelMps }, "the reference every rep gives")
        assertEquals(11, reps.maxBy { it.meanConVelMps }.index, "and the rep it comes from")
        assertEquals(10, reps[11].artefactSamples, "whose span the #290 rule already marks")
        assertEquals(VelocityLoss.NoEligiblePair, VelocityLoss.of(reps), "set 3's velocity loss")
        assertNull(SetAnalyzer.velocityLossPct(reps), "the figure the set publishes")
    }

    /**
     * field-45 (2026-09-25, v0.1.54) sets 2 and 8, from the rows its own
     * `session.json` published: `con_s`, `meanConVel_mps`, `peakConVel_mps`,
     * `rom_m`, `romBounded`, `peakPower_w`, `meanConPower_w` and
     * `artefactSamples`, row for row. That export is schema 1.21 and carries
     * no guard-band count, so every row's is null, which the rule KEEPS -- the
     * bound is what decides here. The captures themselves are not on the
     * classpath: enrolling them is a corpus change this issue does not make.
     */
    private fun row(i: Int, conS: Double, mean: Double, peak: Double, rom: Double, p: Double, mp: Double, art: Int) =
        RepAnalysis(
            index = i,
            eccS = null,
            bottomPauseS = null,
            conS = conS,
            topPauseS = null,
            meanConVelMps = mean,
            peakConVelMps = peak,
            meanEccVelMps = null,
            peakEccVelMps = null,
            romM = rom,
            peakPowerW = p,
            meanConPowerW = mp,
            artefactSamples = art,
            romBounded = false,
        )

    private val field45set2 = listOf(
        row(0, 0.75, 0.545, 0.876, 0.416, 166.1, 98.2, 0),
        row(1, 1.09, 0.262, 0.569, 0.282, 78.8, 44.4, 0),
        row(2, 0.65, 0.49, 0.729, 0.324, 140.0, 88.2, 0),
        row(3, 0.85, 0.317, 0.75, 0.263, 84.5, 50.8, 0),
        row(4, 1.09, 0.635, 1.119, 0.696, 405.8, 118.7, 2),
        row(5, 1.87, 0.738, 1.686, 1.385, 393.0, 132.8, 0),
        row(6, 0.81, 0.471, 0.667, 0.388, 126.6, 84.8, 0),
        row(7, 0.85, 0.279, 0.589, 0.233, 70.3, 46.4, 0),
        row(8, 0.66, 0.434, 0.71, 0.292, 140.1, 78.2, 0),
        row(9, 1.1, 0.392, 0.937, 0.425, 116.8, 63.3, 0),
        row(10, 1.11, 0.55, 0.83, 0.614, 158.2, 99.2, 1),
        row(11, 0.67, 0.407, 0.64, 0.277, 125.3, 73.2, 0),
        row(12, 1.06, 0.331, 0.817, 0.345, 104.5, 53.8, 1),
        row(13, 0.93, 0.654, 0.968, 0.611, 445.4, 123.9, 1),
        row(14, 1.93, 0.714, 1.435, 1.384, 321.9, 128.6, 0),
        row(15, 1.58, 1.234, 1.937, 1.961, 623.6, 223.7, 0),
    )

    private val field45set8 = listOf(
        row(0, 0.88, 1.271, 2.136, 1.123, 247.1, 114.3, 0),
        row(1, 0.4, 0.291, 0.449, 0.12, 43.2, 26.2, 1),
        row(2, 0.72, 0.785, 1.354, 0.576, 146.4, 70.6, 1),
        row(3, 0.68, 0.784, 1.228, 0.543, 131.9, 70.5, 0),
        row(4, 0.6, 0.184, 0.657, 0.107, 34.2, 13.2, 0),
        row(5, 0.46, 0.284, 0.623, 0.133, 82.6, 29.6, 0),
        row(6, 0.59, 0.655, 1.039, 0.395, 106.5, 58.9, 0),
        row(7, 0.48, 0.387, 0.965, 0.181, 47.2, 25.9, 0),
        row(8, 0.47, 0.453, 0.696, 0.218, 70.4, 40.8, 0),
        row(9, 0.42, 0.373, 0.917, 0.152, 52.3, 24.4, 1),
        row(10, 0.8, 0.603, 0.923, 0.49, 118.4, 59.2, 0),
        row(11, 0.66, 0.507, 0.767, 0.341, 78.6, 45.6, 1),
    )

    @Test
    fun `field-45 set 2's unbounded 1_961 m detection does not set the published peak`() {
        // What v0.1.54 published off these rows: detection 16, 1.961 m on an
        // 18.1 kg seated press, zero artefact samples.
        assertEquals(1.937, AccelArtefact.setPeakConVelMps(withoutBound(field45set2)), "the span rule alone")
        assertEquals(623.6, AccelArtefact.setPeakPowerW(withoutBound(field45set2)), "the span rule alone, W")
        assertNull(AccelArtefact.setPeakConVelMps(field45set2), "set 2's published peak velocity")
        assertNull(AccelArtefact.setPeakPowerW(field45set2), "set 2's published peak power")
        assertEquals(VelocityLoss.NoEligiblePair, VelocityLoss.of(field45set2), "set 2's velocity loss")
    }

    @Test
    fun `field-45 set 8's unbounded 1_123 m detection does not set the published peak`() {
        assertEquals(2.136, AccelArtefact.setPeakConVelMps(withoutBound(field45set8)), "the span rule alone")
        assertNull(AccelArtefact.setPeakConVelMps(field45set8), "set 8's published peak velocity")
        assertNull(AccelArtefact.setPeakPowerW(field45set8), "set 8's published peak power")
        assertEquals(VelocityLoss.NoEligiblePair, VelocityLoss.of(field45set8), "set 8's velocity loss")
    }

    /**
     * THE CORPUS TABLE, as a pin: every committed capture's detection count and
     * how many of its reps are eligible, analysed at the geometry
     * `CandidateCorpus` declares for it (and, for the eleven `ArtefactCorpus`
     * carries, with their own cues, work-start instants and loads). Load moves
     * no count and no eligibility, so none is passed for the rest.
     *
     * The counts are the ones the capture resolved before #306: nothing
     * upstream of the rep list moved. The eligible column is the finding --
     * no capture has two eligible reps, so no capture publishes a velocity
     * loss, and the six with one take their peak figures from that one rep.
     */
    @Test
    fun `every committed capture keeps its rep count, and none has two eligible reps`() {
        assertEquals(FieldCorpus.onClasspath(), CandidateCorpus.ALL.map { it.fixture }.sorted(), "every capture")
        val measured = CandidateCorpus.ALL.associate { c ->
            val a = ArtefactCorpus.cases.firstOrNull { it.fixture == c.fixture }?.let { ArtefactCorpus.analyse(it) }
                ?: SetAnalyzer.analyze(load(c.fixture), c.direction)
            c.fixture to (a.reps.size to AccelArtefact.peakEligible(a.reps).size)
        }
        assertEquals(
            mapOf(
                "field-assistedpullup-3010-s37-set08" to (7 to 0),
                "field-assistedpullup-3010-s37-set09" to (6 to 1),
                "field-assistedpullup-3010-s37-set10" to (4 to 0),
                "field-backsquat-10hz" to (6 to 0),
                "field-backsquat-10hz-set5" to (1 to 0),
                "field-backsquat-4011-6rep-s36-set01" to (9 to 0),
                "field-backsquat-99hz-6rep" to (7 to 0),
                "field-backsquat-wrapping-s36-set01" to (7 to 1),
                "field-bench-3010-6rep-s37-set05" to (5 to 0),
                "field-bench-3010-6rep-s37-set06" to (6 to 0),
                "field-bench-3010-6rep-s42-set05" to (5 to 0),
                "field-bench-3010-6rep-s42-set07" to (6 to 0),
                "field-bench-rotating-6rep" to (5 to 0),
                "field-bench-rotating-6rep-ok" to (6 to 1),
                "field-cablerow-3010-8rep-s42-set08" to (6 to 0),
                "field-cablerow-3010-8rep-s42-set09" to (4 to 0),
                "field-cablerow-3010-8rep-s42-set10" to (3 to 0),
                "field-cablerow-static-8rep" to (11 to 0),
                "field-deadlift-straight-5rep-s43-set04" to (9 to 0),
                "field-deadlift-straight-5rep-s43-set05" to (7 to 0),
                "field-deadlift-straight-5rep-s43-set06" to (7 to 0),
                "field-deadlift-straight-2rep-s44-set05" to (4 to 0),
                "field-deadlift-straight-4rep-s44-set04" to (8 to 0),
                "field-deadlift-straight-5rep-s44-set01" to (6 to 0),
                "field-deadlift-straight-5rep-s44-set02" to (7 to 0),
                "field-deadlift-straight-5rep-s44-set03" to (13 to 0),
                "field-facepull-static-12rep" to (11 to 0),
                "field-inclinepress-3010-12rep-s38-set02" to (13 to 0),
                "field-latpulldown-1120-12rep-s38-set14" to (14 to 0),
                "field-latpulldown-1120-12rep-s41-set18" to (16 to 0),
                "field-legcurl-1030-10rep" to (12 to 0),
                "field-legcurl-1030-12rep" to (12 to 0),
                "field-legcurl-1030-12rep-b" to (13 to 0),
                "field-legcurl-1030-12rep-c" to (11 to 0),
                "field-legpress-2010-8rep" to (7 to 0),
                "field-legpress-single-2010-8rep" to (8 to 0),
                "field-legpress-single-2011-8rep-s36-set07" to (10 to 1),
                "field-ohp-100hz-bursty" to (7 to 0),
                "field-ohp-3010-6rep-s37-set02" to (9 to 0),
                "field-ohp-3010-7rep-s42-set02" to (9 to 0),
                "field-ohp-3010-8rep-s37-set01" to (11 to 0),
                "field-ohp-3010-8rep-s38-set04" to (9 to 0),
                "field-ohp-3010-8rep-s38-set05" to (15 to 0),
                "field-ohp-prepinflated-s37-set03" to (11 to 0),
                "field-ohp-prepinflated-s37-set04" to (7 to 0),
                "field-ohp-rotating-8rep" to (8 to 0),
                "field-ohp-rotating-8rep-b" to (8 to 0),
                "field-pallof-static-12rep" to (13 to 0),
                "field-pullup-3010-8rep-s37-set09" to (6 to 1),
                "field-pullup-3010-8rep-s42-set11" to (9 to 0),
                "field-pullup-4010-8rep-s42-set13" to (9 to 0),
                "field-pushdown-1120-14rep-s41-set16" to (1 to 1),
                "field-rdl-3010-10rep" to (11 to 0),
                "field-rdl-3010-10rep-s36-set04" to (11 to 0),
                "field-rdl-3010-10rep-s36-set05" to (11 to 0),
                "field-rdl-wrapping-s36-set05" to (11 to 0),
                "field-reardeltfly-s32-set06" to (17 to 0),
                "field-ropedeadhang-hold20-s37-set11" to (0 to 0),
                "field-ropedeadhang-hold45-s38-set17" to (0 to 0),
                "field-ropedeadhang-hold45-s38-set18" to (0 to 0),
                "field-ropefarmershold-hold30-s42-set16" to (0 to 0),
                "field-seated-ohp-2rep" to (3 to 0),
                "field-still-0rep" to (0 to 0),
            ),
            measured,
            "detections and eligible reps per capture",
        )
        assertTrue(measured.values.none { it.second >= 2 }, "a capture with two eligible reps")
    }

    /** A synthetic rep carrying only what the rule reads. */
    private fun rep(
        i: Int,
        mean: Double,
        peak: Double,
        power: Double? = null,
        meanPower: Double? = null,
        art: Int? = 0,
        band: Int? = 0,
        bounded: Boolean? = true,
    ) = RepAnalysis(
        index = i,
        eccS = null,
        bottomPauseS = null,
        conS = 1.0,
        topPauseS = null,
        meanConVelMps = mean,
        peakConVelMps = peak,
        meanEccVelMps = null,
        peakEccVelMps = null,
        romM = 0.5,
        peakPowerW = power,
        meanConPowerW = meanPower,
        artefactSamples = art,
        guardArtefactSamples = band,
        romBounded = bounded,
    )

    @Test
    fun `each input to the rule disqualifies on its own, and absence keeps the rep`() {
        assertTrue(AccelArtefact.isPeakEligible(rep(0, 0.5, 0.8)), "a clean, bounded rep")
        assertFalse(AccelArtefact.isPeakEligible(rep(0, 0.5, 0.8, art = 1)), "one sample inside the span")
        assertFalse(AccelArtefact.isPeakEligible(rep(0, 0.5, 0.8, band = 1)), "one sample in the band before it")
        assertFalse(AccelArtefact.isPeakEligible(rep(0, 0.5, 0.8, bounded = false)), "an unbounded displacement")
        // A stored analysis from before any of the three questions existed
        // carries null for all three and keeps the peak it always published.
        assertTrue(
            AccelArtefact.isPeakEligible(rep(0, 0.5, 0.8, art = null, band = null, bounded = null)),
            "an archived rep that was never asked",
        )
    }

    @Test
    fun `a set peak below the published mean of its own quantity is withheld, at the mean it is not`() {
        val reps = listOf(
            rep(0, mean = 0.60, peak = 0.95, power = 900.0, meanPower = 500.0, bounded = false),
            rep(1, mean = 0.50, peak = 0.54, power = 450.0, meanPower = 420.0),
            rep(2, mean = 0.58, peak = 0.90, power = 880.0, meanPower = 480.0, art = 2),
        )
        // Eligible: rep 1 alone. Its 0.54 m/s is below the 0.56 mean of the
        // three, and its 450 W below the 466.7 W mean power: neither is a peak.
        assertNull(AccelArtefact.setPeakConVelMps(reps), "0.54 against a 0.56 mean")
        assertNull(AccelArtefact.setPeakPowerW(reps), "450 W against a 466.7 W mean")
        val atMean = listOf(rep(0, mean = 0.5, peak = 0.5, power = 400.0, meanPower = 400.0))
        assertEquals(0.5, AccelArtefact.setPeakConVelMps(atMean), "a peak equal to the mean is published")
        assertEquals(400.0, AccelArtefact.setPeakPowerW(atMean), "and so is a power equal to its mean")
        val noMeanPower = listOf(rep(0, mean = 0.5, peak = 0.8, power = 600.0, meanPower = null))
        assertEquals(600.0, AccelArtefact.setPeakPowerW(noMeanPower), "no mean power to fall below")
    }

    @Test
    fun `velocity loss takes its reference over eligible reps and needs the last one eligible`() {
        // The fastest rep is marked, so the reference is the best of the rest.
        val marked = listOf(rep(0, 0.60, 0.9), rep(1, 0.80, 1.2, art = 3), rep(2, 0.45, 0.7))
        assertEquals(VelocityLoss.Measured(25.0), VelocityLoss.of(marked), "0.60 to 0.45, not 0.80 to 0.45")
        val lastUnbounded = listOf(rep(0, 0.60, 0.9), rep(1, 0.55, 0.8), rep(2, 0.45, 0.7, bounded = false))
        assertEquals(VelocityLoss.NoEligiblePair, VelocityLoss.of(lastUnbounded), "the last rep is not eligible")
        val oneEligible = listOf(rep(0, 0.60, 0.9, band = 1), rep(1, 0.45, 0.7))
        assertEquals(VelocityLoss.NoEligiblePair, VelocityLoss.of(oneEligible), "one eligible rep is not a pair")
        assertEquals(VelocityLoss.NotEnoughReps, VelocityLoss.of(listOf(rep(0, 0.5, 0.8))), "one rep at all")
        val archived = marked.map { it.copy(artefactSamples = null, guardArtefactSamples = null, romBounded = null) }
        assertEquals(VelocityLoss.Measured(43.8), VelocityLoss.of(archived), "an archived set keeps its figure")
    }
}
