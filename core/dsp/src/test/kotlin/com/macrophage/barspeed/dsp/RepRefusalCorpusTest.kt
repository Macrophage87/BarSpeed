package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.StartPhase
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What [RepRefusal] does to every committed capture -- the blast radius, which
 * is the question a reader of the published figures actually has.
 *
 * This is also where [RepRefusal.MAX_PAIRED_RANGE_RATIO_OBSERVED] and
 * [RepRefusal.MAX_UNPAIRED_KEPT_RANGE_RATIO_OBSERVED] stop being assertions
 * and become measurements. Both are recorded on the rule's own KDoc as the
 * reason the bound sits where it does, so both going stale as captures are
 * added must red rather than rot.
 *
 * The ratios are taken through [RepRefusal.rangeRatio] rather than recomputed
 * here: a second implementation of "the lower median of the others" could
 * measure a different quantity from the one that decides, and then this file
 * would pin a number no rule uses.
 */
class RepRefusalCorpusTest {
    private fun load(f: String): List<ImuSample> =
        ImuCsv.decode(javaClass.getResourceAsStream("/$f.csv")!!.readBytes().decodeToString())

    private val ecc = LiftDirection(startsWith = StartPhase.ECCENTRIC)
    private val con = LiftDirection(startsWith = StartPhase.CONCENTRIC)
    private val legCurl = LiftDirection(
        startsWith = StartPhase.CONCENTRIC,
        concentricUp = false,
        sensorInverted = true,
        plane = MovementPlane.VERTICAL,
        sensorOnStack = true,
    )

    /**
     * Every committed capture with the geometry it declared, taken from
     * `BatchCueCoverageTest`, `StackMountGeometryTest` and
     * `FieldDataRegressionTest` for the captures they carry and from the
     * session's own `meta.json` for the one added for issue #125. Loads are
     * approximate where a capture's own load is not recorded in this
     * repository; power is not asserted here, and range and phase resolution
     * do not depend on the load.
     */
    private val corpus = listOf(
        Triple("field-assistedpullup-3010-s37-set08", con, 30.25),
        Triple("field-assistedpullup-3010-s37-set09", con, 23.443564147942737),
        Triple("field-assistedpullup-3010-s37-set10", con, 23.443564147942737),
        Triple("field-backsquat-10hz", ecc, 60.0),
        Triple("field-backsquat-10hz-set5", ecc, 60.0),
        Triple("field-backsquat-4011-6rep-s36-set01", ecc, 60.0),
        Triple("field-backsquat-99hz-6rep", ecc, 60.0),
        Triple("field-backsquat-wrapping-s36-set01", ecc, 60.0),
        Triple("field-bench-3010-6rep-s37-set05", ecc, 47.62719885105372),
        Triple("field-bench-3010-6rep-s37-set06", ecc, 49.8951607011039),
        Triple("field-bench-rotating-6rep", ecc, 43.091275150953365),
        Triple("field-bench-rotating-6rep-ok", ecc, 43.091275150953365),
        Triple("field-cablerow-static-8rep", con, 27.215542200602126),
        Triple("field-facepull-static-12rep", con, 9.97903214022078),
        Triple("field-legcurl-1030-10rep", legCurl, 40.8),
        Triple("field-legcurl-1030-12rep", legCurl, 40.8),
        Triple("field-legcurl-1030-12rep-b", legCurl, 40.8),
        Triple("field-legcurl-1030-12rep-c", legCurl, 40.8),
        Triple("field-legpress-2010-8rep", ecc, 90.0),
        Triple("field-legpress-single-2010-8rep", con, 45.0),
        Triple("field-legpress-single-2011-8rep-s36-set07", con, 45.0),
        Triple("field-ohp-100hz-bursty", ecc, 20.4),
        Triple("field-ohp-3010-6rep-s37-set02", con, 24.94758035055195),
        Triple("field-ohp-3010-8rep-s37-set01", con, 20.411656650451594),
        Triple("field-ohp-prepinflated-s37-set03", con, 22.67961850050177),
        Triple("field-ohp-prepinflated-s37-set04", con, 22.67961850050177),
        Triple("field-ohp-rotating-8rep", ecc, 20.411656650451594),
        Triple("field-ohp-rotating-8rep-b", ecc, 24.94758035055195),
        Triple("field-pallof-static-12rep", con, 11.79340234968141),
        Triple("field-pullup-3010-8rep-s37-set09", con, 23.443564147942737),
        Triple("field-rdl-3010-10rep", ecc, 43.09),
        Triple("field-rdl-3010-10rep-s36-set04", ecc, 43.09),
        Triple("field-rdl-3010-10rep-s36-set05", ecc, 43.09),
        Triple("field-rdl-wrapping-s36-set05", ecc, 43.09),
        Triple("field-reardeltfly-s32-set06", con, 9.07),
        Triple("field-ropedeadhang-hold20-s37-set11", ecc, 43.86),
        Triple("field-seated-ohp-2rep", con, 20.4),
        Triple("field-still-0rep", ecc, 20.4),
    )

    private fun analyse(entry: Triple<String, LiftDirection, Double>): SetAnalysis =
        SetAnalyzer.analyze(load(entry.first), entry.second, entry.third, SetTargets(), DspConfig(), emptyList())

    /**
     * The corpus list is the whole resource directory, checked rather than
     * asserted in prose -- a capture added without a geometry here would
     * otherwise sit outside every figure below and nothing would say so.
     *
     * `-prep.csv` files are excluded on the same terms `RunawayDriftTest`
     * excludes them: they are prep-window instants, not IMU streams, and
     * `ImuCsv` cannot decode one.
     */
    @Test
    fun `the corpus list is every committed capture`() {
        val onDisk = File(javaClass.getResource("/field-still-0rep.csv")!!.toURI()).parentFile.list()!!
            .filter {
                it.startsWith("field-") && it.endsWith(".csv") &&
                    !it.endsWith("-cues.csv") && !it.endsWith("-prep.csv")
            }
            .map { it.removeSuffix(".csv") }
            .sorted()
        assertEquals(onDisk, corpus.map { it.first }.sorted())
        assertEquals(38, corpus.size, "captures this file walks")
    }

    /**
     * The two figures the bound is placed between, both re-derived here.
     *
     * The walk reads the list the analyzer PUBLISHES, so once the rule is
     * wired in, the refused detection is not in it -- which is the point.
     * `ArtefactRepTest` reconstructs that one from the segmenter and pins its
     * 5.23.
     */
    @Test
    fun `the bound sits above every kept drive-only ratio and below the refused one`() {
        var worstPaired = 0.0
        var worstPairedAt = ""
        var worstUnpaired = 0.0
        var worstUnpairedAt = ""
        for (entry in corpus) {
            val reps = analyse(entry).reps
            reps.indices.forEach { i ->
                val ratio = RepRefusal.rangeRatio(reps, i) ?: return@forEach
                if (reps[i].eccS != null) {
                    if (ratio > worstPaired) {
                        worstPaired = ratio
                        worstPairedAt = "${entry.first} rep$i"
                    }
                } else if (ratio > worstUnpaired) {
                    worstUnpaired = ratio
                    worstUnpairedAt = "${entry.first} rep$i"
                }
            }
        }
        assertEquals(
            RepRefusal.MAX_PAIRED_RANGE_RATIO_OBSERVED,
            Math.round(worstPaired * 100.0) / 100.0,
            "largest two-phase range ratio in the corpus, at $worstPairedAt",
        )
        assertTrue(
            worstPaired > RepRefusal.RANGE_RATIO_BOUND,
            "it is ABOVE the bound and clause 1 is what keeps it, at $worstPairedAt",
        )
        assertEquals(
            RepRefusal.MAX_UNPAIRED_KEPT_RANGE_RATIO_OBSERVED,
            Math.round(worstUnpaired * 100.0) / 100.0,
            "largest drive-only range ratio the rule keeps, at $worstUnpairedAt",
        )
        assertTrue(worstUnpaired < RepRefusal.RANGE_RATIO_BOUND, "and it is under the bound, at $worstUnpairedAt")
    }

    /**
     * The list a set publishes has nothing left in it for the rule to refuse.
     *
     * This is the differential that fails before the wiring lands and passes
     * after it: `field-assistedpullup-3010-s37-set10` publishes a detection at
     * 5.23 until [SetAnalyzer] applies the rule, and then it does not.
     */
    @Test
    fun `no published rep list anywhere in the corpus still holds a refusable detection`() {
        val left = corpus.mapNotNull { entry ->
            val refused = RepRefusal.refusedIndices(analyse(entry).reps)
            "${entry.first} $refused".takeIf { refused.isNotEmpty() }
        }
        assertEquals(emptyList(), left)
    }
}
