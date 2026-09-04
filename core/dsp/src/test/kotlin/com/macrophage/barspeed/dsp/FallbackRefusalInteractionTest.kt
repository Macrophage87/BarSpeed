package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.StartPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What [RepSegmenter]'s eccentric-first drive-alone fallback does to
 * [RepRefusal], measured over the committed corpus. Issue #72, round 2
 * finding 2.
 *
 * ## Why the two rules meet at all
 *
 * The fallback publishes a rep on its drive alone, so every rep it adds
 * carries a null `eccS` -- which is exactly the shape [RepRefusal]'s clause 1
 * selects. `SetAnalyzer.analyze` calls [RepRefusal.kept] unconditionally on
 * every set, so the fallback's output is judged by a rule written before it
 * existed and against a corpus measured without it. Two distinct couplings
 * follow, and neither is visible from either file alone:
 *
 *  - **The added detection is itself refusable.** It has no eccentric
 *    partner, so only its range keeps it.
 *  - **The added detection moves the reference every OTHER detection of the
 *    set is judged against.** [RepRefusal] compares a detection's range to the
 *    median range of the set's others, so one more small-range member drags
 *    that median down and lifts every other detection's ratio. It also moves
 *    the set across [RepRefusal.MIN_DETECTIONS], below which no bound is
 *    derived at all.
 *
 * The second is the one that bites, and it bites a rep the fallback never
 * touched.
 *
 * ## "Judged on its paired detections alone"
 *
 * The counterfactual below is the same published list with its drive-only
 * detections removed and the survivors renumbered -- a defined computation
 * over one analysis, not a second analyzer run. That it is also the list
 * `pairEccentricFirst` produced BEFORE the fallback is reasoned from that
 * function's control flow and is not measured here: the fallback branch adds
 * reps and advances `lastCountedEndIdx`, and `lastCountedEndIdx` is read
 * nowhere but inside that branch, so a pair's detection cannot depend on it.
 *
 * ## Loads
 *
 * Taken from `RepRefusalCorpusTest`, which is where the corpus and its
 * declared geometry are canonical. Nothing asserted here is a power figure,
 * and neither range, phase resolution nor a range RATIO depends on the load.
 * The directory-completeness check that reds when a capture is added lives
 * there too.
 */
class FallbackRefusalInteractionTest {
    private fun load(f: String): List<ImuSample> =
        ImuCsv.decode(javaClass.getResourceAsStream("/$f.csv")!!.readBytes().decodeToString())

    private val ecc = LiftDirection(startsWith = StartPhase.ECCENTRIC)

    /** Every eccentric-first capture in the corpus -- the only sets `pairEccentricFirst` runs on. */
    private val eccFirstCorpus = listOf(
        "field-backsquat-10hz" to 60.0,
        "field-backsquat-10hz-set5" to 60.0,
        "field-backsquat-4011-6rep-s36-set01" to 60.0,
        "field-backsquat-99hz-6rep" to 60.0,
        "field-backsquat-wrapping-s36-set01" to 60.0,
        "field-bench-3010-6rep-s37-set05" to 47.62719885105372,
        "field-bench-3010-6rep-s37-set06" to 49.8951607011039,
        "field-bench-rotating-6rep" to 43.091275150953365,
        "field-bench-rotating-6rep-ok" to 43.091275150953365,
        "field-legpress-2010-8rep" to 90.0,
        "field-ohp-100hz-bursty" to 20.4,
        "field-ohp-rotating-8rep" to 20.411656650451594,
        "field-ohp-rotating-8rep-b" to 24.94758035055195,
        "field-rdl-3010-10rep" to 43.09,
        "field-rdl-3010-10rep-s36-set04" to 43.09,
        "field-rdl-3010-10rep-s36-set05" to 43.09,
        "field-rdl-wrapping-s36-set05" to 43.09,
        "field-ropedeadhang-hold20-s37-set11" to 43.86,
        "field-still-0rep" to 20.4,
    )

    private fun analyse(name: String, loadKg: Double): List<RepAnalysis> =
        SetAnalyzer.analyze(load(name), ecc, loadKg, SetTargets(), DspConfig(), emptyList()).reps

    /** The same list judged on its paired detections alone -- see the class note. */
    private fun pairedOnly(reps: List<RepAnalysis>): List<RepAnalysis> =
        reps.filter { it.eccS != null }.mapIndexed { i, rep -> rep.copy(index = i) }

    private fun r2(d: Double) = Math.round(d * 100.0) / 100.0

    /**
     * Which captures the fallback moves, and by what.
     *
     * Five files, four distinct streams: `field-rdl-wrapping-s36-set05` is
     * byte-identical to `field-rdl-3010-10rep-s36-set05`, which
     * `BatchCueCoverageTest` asserts rather than assumes. `RepSegmenter`'s own
     * note counts the four streams.
     */
    @Test
    fun `the fallback adds one drive-only detection to five captures and none to the rest`() {
        val added = eccFirstCorpus.mapNotNull { (name, loadKg) ->
            val reps = analyse(name, loadKg)
            val drivesOnly = reps.indices.filter { reps[it].eccS == null }
            if (drivesOnly.isEmpty()) null else name to drivesOnly.map { it to reps[it].romM }
        }
        assertEquals(
            listOf(
                "field-backsquat-4011-6rep-s36-set01",
                "field-bench-3010-6rep-s37-set05",
                "field-rdl-3010-10rep-s36-set04",
                "field-rdl-3010-10rep-s36-set05",
                "field-rdl-wrapping-s36-set05",
            ),
            added.map { it.first },
            "eccentric-first captures carrying a drive-only detection",
        )
        assertEquals(
            listOf(listOf(5), listOf(0), listOf(8), listOf(6), listOf(6)),
            added.map { entry -> entry.second.map { it.first } },
            "one added detection each, at these positions in the published list",
        )
        listOf(0.313, 0.278, 0.107, 0.228, 0.228).forEachIndexed { i, romM ->
            assertEquals(romM, added[i].second.single().second, 5e-4, "${added[i].first} added rom_m")
        }
    }

    /**
     * The refusal outcome is unchanged on every capture the fallback moves,
     * in both directions.
     *
     * Nothing added is refused, nothing already there becomes refusable, and
     * no set crosses [RepRefusal.MIN_DETECTIONS] -- the smallest affected set
     * resolves four paired detections without the fallback and five with it,
     * so a bound is derivable either way and `refusedCount` is 0 rather than
     * null on both sides.
     *
     * The visited count is asserted because without it this test passes
     * vacuously on a tree where the fallback fires on nothing -- measured, not
     * feared: disabling the fallback and re-running reds twenty other pins in
     * this module and left this one green until the count was added.
     */
    @Test
    fun `no capture the fallback moves refuses a detection, with the fallback or without it`() {
        var visited = 0
        for ((name, loadKg) in eccFirstCorpus) {
            val reps = analyse(name, loadKg)
            if (reps.none { it.eccS == null }) continue
            visited++
            val paired = pairedOnly(reps)
            assertEquals(emptyList(), RepRefusal.refusedIndices(reps), "$name refuses nothing as published")
            assertEquals(emptyList(), RepRefusal.refusedIndices(paired), "$name refuses nothing without the fallback")
            assertEquals(0, RepRefusal.refusedCount(reps), "$name refusedCount as published")
            assertEquals(0, RepRefusal.refusedCount(paired), "$name refusedCount without the fallback")
        }
        assertEquals(5, visited, "captures carrying a fallback-added detection, so the loop asserted something")
    }

    /**
     * The coupling that is NOT neutral, and the figure it moved.
     *
     * On `field-bench-3010-6rep-s37-set05` the fallback adds a 0.278 m drive
     * at the head of the list. That detection is the second smallest range in
     * the set, so it becomes the lower median of the others for the set's
     * largest rep, whose ratio goes from 1.52 to 4.90 -- past
     * [RepRefusal.RANGE_RATIO_BOUND], which it was nowhere near before.
     *
     * That rep resolved BOTH its phases, so clause 1 keeps it and nothing the
     * lifter sees moves. What moves is
     * [RepRefusal.MAX_PAIRED_RANGE_RATIO_OBSERVED], the corpus measurement
     * `RepRefusal`'s own KDoc offers as the evidence clause 1 is load-bearing:
     * the largest two-phase ratio in the corpus is no longer the 4.82 main
     * measured on a concentric-first capture, it is this one.
     */
    @Test
    fun `the added detection lifts a paired rep of the same set past the bound`() {
        val reps = analyse("field-bench-3010-6rep-s37-set05", 47.62719885105372)
        val paired = pairedOnly(reps)
        assertEquals(5, reps.size, "detections as published")
        assertEquals(4, paired.size, "detections resolving both phases")
        val largest = reps.indices.maxBy { reps[it].romM }
        assertEquals(4, largest, "the set's largest-range detection")
        assertNotNull(reps[largest].eccS, "it resolved an eccentric, so clause 1 keeps it")
        assertEquals(1.52, r2(RepRefusal.rangeRatio(paired, 3)!!), "its ratio judged on paired detections alone")
        assertEquals(4.9, r2(RepRefusal.rangeRatio(reps, largest)!!), "its ratio as published")
        assertTrue(
            RepRefusal.rangeRatio(reps, largest)!! > RepRefusal.RANGE_RATIO_BOUND,
            "and that is past the bound",
        )
        assertEquals(
            RepRefusal.MAX_PAIRED_RANGE_RATIO_OBSERVED,
            r2(RepRefusal.rangeRatio(reps, largest)!!),
            "the corpus's largest two-phase ratio is now this branch's, not main's 4.82",
        )
    }

    /**
     * The other half of the coupling, which IS neutral and is asserted rather
     * than assumed: no detection the fallback adds comes near the bound
     * itself. The largest reaches 1.07 against a bound of 4.5.
     */
    @Test
    fun `no detection the fallback adds is anywhere near refusable`() {
        val ratios = eccFirstCorpus.flatMap { (name, loadKg) ->
            val reps = analyse(name, loadKg)
            reps.indices.filter { reps[it].eccS == null }.map { RepRefusal.rangeRatio(reps, it)!! }
        }
        assertEquals(5, ratios.size, "one ratio per added detection")
        assertEquals(1.07, r2(ratios.max()), "the largest ratio any added detection reaches")
        assertTrue(ratios.max() < RepRefusal.RANGE_RATIO_BOUND, "all of them under the bound")
    }
}
