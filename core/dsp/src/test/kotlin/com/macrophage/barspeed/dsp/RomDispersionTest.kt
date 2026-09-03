package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.StartPhase
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How far the reported range of motion varies WITHIN one set.
 *
 * A machine has one travel: a seated leg curl rail is the same distance every
 * rep of every set, and a lifter's range of motion varies a little, not by an
 * order of magnitude. So the spread of `rom_m` across a set is a statement
 * about the measurement rather than about the lifter. Issue 74 is the case that
 * displacement is the root quantity -- velocity, power, velocity loss and tempo
 * compliance all descend from it -- so a set whose displacement disagrees with
 * itself has poisoned every one of them.
 *
 * Issue 74 proposes auditing `rom_m` against a declared window of 0.05 to
 * 1.2 m. That window is a downstream tool's declaration, as issue 74 says
 * itself, and it is measured below to be too blunt to act on: it flags 7 of 98
 * reps while PASSING a 1.2 m rep on a rail that is 0.4 to 0.5 m long.
 *
 * These are characterization pins. Nothing is fixed by this commit.
 */
class RomDispersionTest {
    private fun load(n: String) = ImuCsv.decode(
        javaClass.getResourceAsStream("/$n")!!.readBytes().decodeToString(),
    )

    private val legCurl = LiftDirection(
        startsWith = StartPhase.CONCENTRIC,
        concentricUp = false,
        sensorInverted = true,
        plane = MovementPlane.VERTICAL,
        sensorOnStack = true,
    )

    private fun ecc() = LiftDirection(startsWith = StartPhase.ECCENTRIC)

    private fun con() = LiftDirection(startsWith = StartPhase.CONCENTRIC)

    private val corpus = listOf(
        Triple("field-ohp-rotating-8rep.csv", ecc(), 20.411656650451594),
        Triple("field-ohp-rotating-8rep-b.csv", ecc(), 24.94758035055195),
        Triple("field-bench-rotating-6rep-ok.csv", ecc(), 43.091275150953365),
        Triple("field-bench-rotating-6rep.csv", ecc(), 43.091275150953365),
        Triple("field-cablerow-static-8rep.csv", con(), 27.215542200602126),
        Triple("field-facepull-static-12rep.csv", con(), 9.97903214022078),
        Triple("field-pallof-static-12rep.csv", con(), 11.79340234968141),
        Triple("field-backsquat-10hz.csv", ecc(), 47.6),
        Triple("field-backsquat-10hz-set5.csv", ecc(), 47.6),
        Triple("field-ohp-100hz-bursty.csv", con(), 29.5),
        Triple("field-seated-ohp-2rep.csv", con(), 20.4),
        Triple("field-still-0rep.csv", ecc(), 20.411656650451594),
        Triple("field-legcurl-1030-12rep.csv", legCurl, 34.019427750752655),
        Triple("field-legcurl-1030-12rep-b.csv", legCurl, 34.019427750752655),
        Triple("field-legcurl-1030-12rep-c.csv", legCurl, 34.019427750752655),
    )

    private fun roms(file: String, d: LiftDirection, kg: Double): List<Double> =
        SetAnalyzer.analyze(load(file), d, loadKg = kg).reps.map { it.romM }

    /** Population coefficient of variation, as a fraction of the mean. */
    private fun cv(r: List<Double>): Double {
        val mean = r.average()
        val variance = r.sumOf { (it - mean) * (it - mean) } / r.size
        return sqrt(variance) / mean
    }

    private fun ratio(r: List<Double>) = r.max() / r.min()

    @Test
    fun `the reported range of motion varies within a set by up to sixteen times (pre-fix)`() {
        val expected = mapOf(
            // Issue #94's runaway correction moves eight of the thirteen and
            // in BOTH directions. Down, meaning the reps within a set agree
            // better than they did: the first overhead press 0.426 to 0.379,
            // the second 0.674 to 0.657, the bursty press 0.645 to 0.669 is
            // the exception. Up, sharply, on the two whose rep list was two
            // reps long and is now five and six: the rotating bench press
            // 0.017 to 0.607 and the 10 Hz squat 0.109 to 0.391. A CV over two
            // reps is not a spread, and this is what it looks like when the
            // population it is taken over becomes real.
            "field-backsquat-10hz.csv" to 0.391,
            "field-bench-rotating-6rep-ok.csv" to 0.233,
            "field-bench-rotating-6rep.csv" to 0.607,
            "field-cablerow-static-8rep.csv" to 0.636,
            "field-facepull-static-12rep.csv" to 0.266,
            "field-pallof-static-12rep.csv" to 0.621,
            "field-ohp-100hz-bursty.csv" to 0.669,
            "field-ohp-rotating-8rep.csv" to 0.379,
            "field-ohp-rotating-8rep-b.csv" to 0.657,
            "field-seated-ohp-2rep.csv" to 0.373,
            "field-legcurl-1030-12rep.csv" to 0.337,
            "field-legcurl-1030-12rep-b.csv" to 0.503,
            "field-legcurl-1030-12rep-c.csv" to 0.834,
        )
        corpus.forEach { (file, d, kg) ->
            val r = roms(file, d, kg)
            if (r.size < 2) return@forEach
            assertEquals(expected.getValue(file), cv(r), 5e-4, "$file: ROM spread as a fraction of the mean")
        }
        // Two captures cannot state a spread at all, and any published figure
        // must be ABSENT on them rather than 0.0 or 1.0.
        assertEquals(1, roms("field-backsquat-10hz-set5.csv", ecc(), 47.6).size, "reps on the quiet-rack capture")
        assertEquals(0, roms("field-still-0rep.csv", ecc(), 20.411656650451594).size, "reps on the still capture")
    }

    @Test
    fun `the plausibility window issue 74 inherits is too blunt to act on (pre-fix)`() {
        val all = corpus.flatMap { (file, d, kg) -> roms(file, d, kg) }
        assertEquals(118, all.size, "reps segmented across the corpus")
        assertEquals(8, all.count { it < 0.05 || it > 1.2 }, "reps outside the 0.05-1.2 m window issue 74 quotes")
        // The one capture whose travel is known independently: a seated leg
        // curl rail, 0.4 to 0.5 m. Against the machine itself HALF the reps are
        // impossible, and the inherited window passes almost all of them.
        val curl = corpus.filter { it.first.contains("legcurl") }.flatMap { (f, d, kg) -> roms(f, d, kg) }
        assertEquals(36, curl.size, "leg-curl reps")
        assertEquals(18, curl.count { it < 0.30 || it > 0.60 }, "leg-curl reps outside a generous rail band")
        assertEquals(2, curl.count { it < 0.05 || it > 1.2 }, "the same reps the inherited window flags")
    }

    @Test
    fun `spread is measured as CV because max over min misorders the controls`() {
        // Why the figure is a coefficient of variation and not the more legible
        // max/min. Kept executable rather than written down once and forgotten.
        //
        // ORDERING, which is the disqualifying one. This repo holds independent
        // opinions about two of these captures: field-bench-rotating-6rep-ok
        // resolves 6 of 6 and is the negative control for every segmentation
        // change here, and field-facepull-static-12rep is documented as
        // defective. CV ranks the control BELOW the defective capture. max/min
        // inverts them, and a statistic that misorders the two captures the
        // repo has opinions about is disqualified whatever its legibility.
        val control = roms("field-bench-rotating-6rep-ok.csv", ecc(), 43.091275150953365)
        val defective = roms("field-facepull-static-12rep.csv", con(), 9.97903214022078)
        assertTrue(cv(control) < cv(defective), "CV puts the known-good control below the known-bad capture")
        assertTrue(ratio(control) > ratio(defective), "max/min inverts that ordering, which is why it is not used")
        // The margin, stated because issue #94's runaway correction narrowed
        // it and a bare `>` would hide that: max/min reads 2.255 against 2.213,
        // a 2% separation, where CV reads 0.233 against 0.266. The inversion
        // still holds and it is now close enough that one more capture could
        // end it.
        assertEquals(2.255, ratio(control), 5e-3, "max/min on the control")
        assertEquals(2.213, ratio(defective), 5e-3, "max/min on the defective capture")

        // STABILITY, the secondary argument. Dropping any single rep moves
        // max/min far more than CV on the sets long enough for the question to
        // arise -- max/min is a two-point statistic and one truncated rep sets
        // it.
        fun worstSwing(r: List<Double>, stat: (List<Double>) -> Double): Double {
            val whole = stat(r)
            val loo = r.indices.map { i -> stat(r.filterIndexed { j, _ -> j != i }) }
            return (whole - loo.min()) / whole
        }
        // The gap narrows with issue #94's runaway correction -- 0.27 against
        // 0.11 becomes 0.102 against 0.087 -- because this capture's rep list
        // goes from 8 to 11 and a leave-one-out statistic is less sensitive
        // over a longer list. The stability argument therefore carries less
        // weight than it did, and it was already the secondary one; the
        // ORDERING argument above is what disqualifies max/min.
        val cable = roms("field-cablerow-static-8rep.csv", con(), 27.215542200602126)
        assertEquals(0.102, worstSwing(cable, ::ratio), 5e-3, "max/min swing when one rep is dropped")
        assertEquals(0.087, worstSwing(cable, ::cv), 5e-3, "CV swing when one rep is dropped")
    }
}
