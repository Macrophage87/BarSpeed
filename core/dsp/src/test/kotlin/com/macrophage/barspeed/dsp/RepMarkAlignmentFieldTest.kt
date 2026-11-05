package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.RepMarkAlignment
import com.macrophage.barspeed.model.StartPhase
import com.macrophage.barspeed.model.Tempo
import com.macrophage.barspeed.model.VoiceCue
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [RepMarkAlignment] over committed guided captures, through the shipped
 * analyzer: the corpus its half-cycle rule is argued from (#246).
 *
 * ## What is scored
 *
 * Every committed capture whose shipped analysis resolves AS MANY reps as its
 * guide counted -- the only sets on which `repMetricsComplete` could publish
 * true, and so the only sets on which the order matters. Seven of them.
 *
 * The marks are the set's own `-reps.csv` where one is committed: the instants
 * `GuidedCadenceRunner.onRepCounted` fired. The older captures carry no mark
 * file, and for them the marks are the track's `Down` cues, one per cycle, the
 * grid `BatchCueCoverageTest` opens its windows on. That substitution changes
 * no verdict, because the rule is blind to where in a cycle the marks fall --
 * `RepMarkAlignmentTest` pins that on a synthetic grid and
 * `the verdict reads the same off the stored marks and the Down cues` pins it
 * on the two captures that carry both.
 *
 * ## What it says
 *
 * Five aligned, the furthest residual 1681 ms from its median against a
 * 4004 ms cycle (`field-inclinepress-3010-12rep-s38-set02`, a lifter drifting
 * a second and a half against the guide). Two NOT aligned at twelve of twelve:
 * `field-legcurl-1030-12rep` and `-12rep-b`, the furthest residuals 4493 and
 * 5457 ms against a 5005 ms cycle -- #246's cancelling shape on committed
 * streams, publishing `repMetricsComplete` true under count equality.
 *
 * CHARACTERIZATION of the shipped analyzer as well as a pin on the rule: a
 * change to segmentation that moves a detection can move a row here, and that
 * is a change to read rather than a failure to re-baseline blindly.
 */
class RepMarkAlignmentFieldTest {
    private val eccFirst = LiftDirection(startsWith = StartPhase.ECCENTRIC)
    private val conFirst = LiftDirection(startsWith = StartPhase.CONCENTRIC)

    /** The seated leg curl, as `BatchCueCoverageTest` declares it. */
    private val legCurl = LiftDirection(
        startsWith = StartPhase.CONCENTRIC,
        concentricUp = false,
        sensorInverted = true,
        plane = MovementPlane.VERTICAL,
        sensorOnStack = true,
    )

    private data class Case(val fixture: String, val direction: LiftDirection, val tempo: String)

    private val cases = listOf(
        Case("field-inclinepress-3010-12rep-s38-set02", eccFirst, "3010"),
        Case("field-legpress-single-2011-8rep-s36-set07", conFirst, "2011"),
        Case("field-ohp-rotating-8rep", eccFirst, "3010"),
        Case("field-bench-rotating-6rep-ok", eccFirst, "3010"),
        Case("field-rdl-3010-10rep", eccFirst, "3010"),
        Case("field-legcurl-1030-12rep", legCurl, "1030"),
        Case("field-legcurl-1030-12rep-b", legCurl, "1030"),
    )

    private fun text(name: String): String? = javaClass.getResourceAsStream("/$name")?.readBytes()?.decodeToString()

    private fun storedMarks(fixture: String): List<Long>? =
        text("$fixture-reps.csv")?.lines()?.mapNotNull { it.trim().toLongOrNull() }

    private fun marks(fixture: String): List<Long> = storedMarks(fixture) ?: CueTrack.movement(fixture, "Down")

    private fun analysis(c: Case, marks: List<Long>): SetAnalysis {
        val samples: List<ImuSample> = ImuCsv.decode(text("${c.fixture}.csv")!!)
        val cues = CueTrack.read(c.fixture).map { VoiceCue(it.timestampMs, it.label) }
        val workAt = text("${c.fixture}-prep.csv")?.trim()?.lines()?.get(1)?.split(",")?.get(1)?.trim()?.toLong()
        return SetAnalyzer.analyze(
            samples,
            c.direction,
            targets = SetTargets(plannedReps = marks.size, tempo = Tempo.parse(c.tempo), cadenceGuided = true),
            cues = cues,
            workStartedAtMs = workAt,
        )
    }

    /** The furthest residual from the median, in ms, under [RepMarkAlignment]'s own pairing. */
    private fun furthest(marks: List<Long>, starts: List<Long>): Long {
        val r = starts.sorted().indices.map { starts.sorted()[it] - marks.sorted()[it] }
        val centre = r.sorted()[r.size / 2]
        return r.maxOf { abs(it - centre) }
    }

    @Test
    fun `the guided captures whose counts agree, and which of them are aligned`() {
        // (detections, marks, cycle ms, furthest residual from the median ms, aligned)
        val expected = mapOf(
            "field-inclinepress-3010-12rep-s38-set02" to listOf(12L, 12L, 4004L, 1681L, 1L),
            "field-legpress-single-2011-8rep-s36-set07" to listOf(8L, 8L, 4004L, 930L, 1L),
            "field-ohp-rotating-8rep" to listOf(8L, 8L, 5007L, 382L, 1L),
            "field-bench-rotating-6rep-ok" to listOf(6L, 6L, 5006L, 263L, 1L),
            "field-rdl-3010-10rep" to listOf(10L, 10L, 4004L, 1127L, 1L),
            "field-legcurl-1030-12rep" to listOf(12L, 12L, 5005L, 4493L, 0L),
            "field-legcurl-1030-12rep-b" to listOf(12L, 12L, 5006L, 5457L, 0L),
        )
        val actual = cases.associate { c ->
            val m = marks(c.fixture)
            val starts = analysis(c, m).reps.map { it.driveStartedAtMs!! }
            val cycle = m.sorted().zipWithNext { a, b -> b - a }.sorted().let { it[it.size / 2] }
            val verdict = RepMarkAlignment.aligned(m, starts)!!
            c.fixture to listOf(
                starts.size.toLong(),
                m.size.toLong(),
                cycle,
                furthest(m, starts),
                if (verdict) 1L else 0L,
            )
        }
        assertEquals(expected, actual, "detections, marks, cycle, furthest residual, aligned")
    }

    /**
     * The half-cycle boundary sits in the gap the corpus leaves: every aligned
     * capture's furthest residual is under 0.45 of its cycle and every
     * cancelling one's is over 0.85.
     */
    @Test
    fun `half a cycle separates the aligned captures from the cancelling ones`() {
        for (c in cases) {
            val m = marks(c.fixture)
            val starts = analysis(c, m).reps.map { it.driveStartedAtMs!! }
            val cycle = m.sorted().zipWithNext { a, b -> b - a }.sorted().let { it[it.size / 2] }
            val share = furthest(m, starts).toDouble() / cycle
            assertTrue(share < 0.45 || share > 0.85, "${c.fixture}: ${"%.3f".format(share)} of a cycle")
        }
    }

    /** Where in the cycle the marks fall does not decide the verdict, on the captures that carry both. */
    @Test
    fun `the verdict reads the same off the stored marks and the Down cues`() {
        for (c in cases.filter { storedMarks(it.fixture) != null }) {
            val stored = storedMarks(c.fixture)!!
            val downs = CueTrack.movement(c.fixture, "Down")
            val starts = analysis(c, stored).reps.map { it.driveStartedAtMs!! }
            assertEquals(
                RepMarkAlignment.aligned(stored, starts),
                RepMarkAlignment.aligned(downs.takeLast(stored.size), starts),
                c.fixture,
            )
        }
    }

    /** Every rep the analyzer keeps carries the arrival stamp of a real sample, in drive order. */
    @Test
    fun `every analysed rep carries the stamp of the sample its drive began on`() {
        val c = cases.first()
        val samples = ImuCsv.decode(text("${c.fixture}.csv")!!)
        val stamps = samples.map { it.timestampMs }.toSet()
        val starts = analysis(c, marks(c.fixture)).reps.map { it.driveStartedAtMs }
        assertTrue(starts.isNotEmpty() && starts.all { it != null && it in stamps }, "a rep with no sample's stamp")
        assertEquals(starts.sortedBy { it }, starts, "the stamps are not in drive order")
    }
}
