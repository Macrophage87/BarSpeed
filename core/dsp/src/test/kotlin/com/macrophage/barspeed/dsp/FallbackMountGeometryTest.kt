package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.StartPhase
import com.macrophage.barspeed.model.Tempo
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a declared stack geometry does to a stream that did NOT come off the
 * stack. Issue #247.
 *
 * `SensorCapturePolicy.analysedStream` moves the analysis onto the partner
 * unit when the armed one delivered too few frames to run on. It swaps WHICH
 * UNIT is analysed and nothing swaps the geometry it is analysed under:
 * `RecordViewModel` hands `SetAnalyzer.analyze` the EXERCISE's
 * `liftDirection()`, and on a cable machine that direction describes one
 * unit's MOUNT -- `sensorInverted`, `sensorOnStack`, `travelRatio` -- rather
 * than the lift.
 *
 * ## What is pinned here, and what it claims
 *
 * These are characterization pins on the behaviour that shipped, taken before
 * any fix. They say what the analyzer COMPUTES over two streams. They claim
 * nothing about where either unit was mounted, which no byte in this
 * repository records: the mount is the owner's word, and the session that
 * supplied these captures is itself the evidence that one declaration covers
 * two different second-unit mounts.
 *
 * ## The synthetic pair
 *
 * Fabricated, and deliberately so: it is the only way to hold everything but
 * the mount constant. One world-vertical acceleration track is turned into two
 * streams -- a lifter-side one and its exact negation, which is what a unit on
 * the other end of a 1:1 cable sees. Real units differ in noise, orientation
 * and drift as well as in mount, so a real pair cannot isolate the geometry
 * term; this pair can, because the two streams differ in NOTHING else.
 *
 * The track is a lat pulldown as `1120` prescribes it: a 2 s drive DOWN and a
 * 1 s return UP, five times, with stillness between so the ZUPT integrator has
 * anchors. Nothing here was measured on a machine.
 *
 * ## The field pair
 *
 * `field-latpulldown-1120-12rep-s38-set14` (already committed; role `a`, the
 * unit the set armed and analysed) and
 * `field-latpulldown-1120-12rep-s38-set14-imu-b` (added with this test; role
 * `b`, the partner). Provenance, read from field-38's own `meta.json`: app
 * 0.1.50, WitMotion WT901BLECL, epoch 2026-09-04T09:52:33.623Z, set 14,
 * `lat_pulldown`, 34.019427750752655 kg (75.0 lb), 12 reps of 12 planned, RPE
 * 6, tempo `1120`, 10 s prep, and the geometry both fixtures are scored under
 * below -- `startsWith` concentric, `concentric` down, plane vertical,
 * `sensorOnStack` true, `sensorInverted` true, `travelRatio` 1.0. Role `a`
 * holds 6404 samples at a span-based 99.36374922408443 Hz, role `b` 6400 at
 * 99.34946979459392 Hz.
 *
 * SPAN-BASED, so those rates say each stream was long and evenly clocked and
 * NOT that it was complete: a dropout is arithmetically indistinguishable from
 * a slower sensor.
 *
 * Nothing fell back on that session -- all 18 sets published `analysedRole`
 * `a` with both roles present and no `fellBack` key anywhere -- so the hazard
 * these pins describe is unfired, and every figure below is this classpath's
 * arithmetic rather than an observation of a fallback.
 */
class FallbackMountGeometryTest {
    /** The geometry field-38 declared for its three pulldown sets. */
    private val declared = LiftDirection(
        startsWith = StartPhase.CONCENTRIC,
        concentricUp = false,
        sensorInverted = true,
        plane = MovementPlane.VERTICAL,
        sensorOnStack = true,
    )

    /**
     * The same lift seen from the lifter's end of the cable: the handle moves
     * WITH the load, so nothing is inverted and nothing rides the stack. The
     * drive still goes down, because which way the lifter drives is a property
     * of the exercise and not of where a unit is clipped.
     */
    private val lifterSide = LiftDirection(
        startsWith = StartPhase.CONCENTRIC,
        concentricUp = false,
        sensorInverted = false,
        plane = MovementPlane.VERTICAL,
        sensorOnStack = false,
    )

    private val gravityMps2 = 9.80665
    private val hz = 100

    /**
     * World-vertical acceleration for one phase travelling [displacementM]
     * metres over [seconds], as a half-cosine so velocity starts and ends at
     * zero and the integral is the displacement asked for.
     */
    private fun phase(displacementM: Double, seconds: Double): List<Double> {
        val n = (seconds * hz).toInt()
        return (0 until n).map { i ->
            displacementM * PI * PI / (2 * seconds * seconds) * cos(PI * i.toDouble() / n)
        }
    }

    private fun still(seconds: Double): List<Double> = List((seconds * hz).toInt()) { 0.0 }

    /** Five `1120` pulldown reps as the HANDLE moves: 2 s down, 1 s back up. */
    private fun handleTrack(): List<Double> = buildList {
        addAll(still(1.0))
        repeat(5) {
            addAll(phase(-0.6, 2.0))
            addAll(still(0.6))
            addAll(phase(0.6, 1.0))
            addAll(still(0.6))
        }
        addAll(still(1.0))
    }

    /**
     * A track as an IMU stream. Roll and pitch are zero, so
     * `FrameTransform.verticalLinearAccelMps2` reads world-vertical
     * acceleration straight off `azG`.
     */
    private fun stream(track: List<Double>, inverted: Boolean): List<ImuSample> = track.mapIndexed { i, a ->
        ImuSample(
            timestampMs = i * 10L,
            axG = 0.0,
            ayG = 0.0,
            azG = 1.0 + (if (inverted) -a else a) / gravityMps2,
            wxDps = 0.0,
            wyDps = 0.0,
            wzDps = 0.0,
            rollDeg = 0.0,
            pitchDeg = 0.0,
            yawDeg = 0.0,
        )
    }

    private fun load(name: String): List<ImuSample> =
        ImuCsv.decode(javaClass.getResourceAsStream("/$name.csv")!!.readBytes().decodeToString())

    private fun assertClose(expected: Double, actual: Double?, tolerance: Double, message: String) {
        assertTrue(actual != null && abs(actual - expected) <= tolerance, "$message: expected ~$expected, got $actual")
    }

    @Test
    fun `the stack stream under its own declared geometry resolves the prescribed phases`() {
        val stack = stream(handleTrack(), inverted = true)
        val analysis = SetAnalyzer.analyze(stack, declared)

        assertEquals(5, analysis.reps.size, "the five fabricated reps did not resolve")
        // 2 s drive, 1 s return, clipped at the dead band the way every phase
        // in this repository is -- RepSegmenter's own KDoc measures the loss.
        assertClose(1.89, analysis.reps[0].conS, 0.05, "the 2 s drive")
        assertClose(0.98, analysis.reps[0].eccS, 0.05, "the 1 s return")
    }

    @Test
    fun `the partner stream under that same geometry swaps the two phases outright`() {
        val track = handleTrack()
        val stack = SetAnalyzer.analyze(stream(track, inverted = true), declared)
        val handle = SetAnalyzer.analyze(stream(track, inverted = false), declared)

        // The defect, pinned as it stands: the rep COUNT is identical, so the
        // obvious check misses it. What moves is which stroke is called which.
        assertEquals(stack.reps.size, handle.reps.size, "the swap is not visible in the rep count")
        assertClose(0.98, handle.reps[0].conS, 0.05, "the 2 s drive is published as the 1 s return's duration")
        assertClose(1.89, handle.reps[0].eccS, 0.05, "the 1 s return is published as the 2 s drive's duration")
        // Same stream, same lift, two geometries: the published velocity-loss
        // figure is computed over the other stroke.
        assertEquals(0.8, handle.velocityLossPct, "velocity loss under the declared geometry")
        assertEquals(
            2.2,
            SetAnalyzer.analyze(stream(track, inverted = false), lifterSide).velocityLossPct,
            "velocity loss under the geometry the handle unit needs",
        )
    }

    @Test
    fun `the two field-38 units are not interchangeable under one declared geometry`() {
        val roleA = SetAnalyzer.analyze(load("field-latpulldown-1120-12rep-s38-set14"), declared)
        val roleB = SetAnalyzer.analyze(load("field-latpulldown-1120-12rep-s38-set14-imu-b"), declared)

        // Both streams cover the same 12 reps the lifter counted, and the one
        // declaration reads them as different sets. Nothing here says which
        // unit was where; what it says is that one geometry does not describe
        // both.
        assertEquals(14, roleA.reps.size, "role a, the armed and analysed unit")
        assertEquals(13, roleB.reps.size, "role b, the partner")
        assertEquals(81.0, roleA.velocityLossPct, "role a velocity loss under the declared geometry")
        assertEquals(33.5, roleB.velocityLossPct, "role b velocity loss under the same declaration")
        // The rate is a property of the timestamps and matches the rate
        // field-38's own meta.json published for each file.
        assertEquals(99.36374922408443, roleA.sampleRateHz, "role a span-based rate")
        assertEquals(99.34946979459392, roleB.sampleRateHz, "role b span-based rate")
    }

    /**
     * The completeness guarantee the single-stream corpora give, kept for the
     * one file they deliberately do not carry.
     *
     * `FieldCorpus.onClasspath()` excludes a partner stream because it is the
     * same SET as a capture already in the list and counting it would double
     * that set in every coverage aggregate. This is the pin that stops the
     * exclusion becoming a hiding place: every partner is named here, every
     * partner is scored by the tests above, and a partner whose base capture
     * is not itself in the corpus is refused.
     */
    @Test
    fun `every partner stream is named here and belongs to a capture in the corpus`() {
        val partners = FieldCorpus.partnersOnClasspath()

        assertEquals(
            listOf("field-latpulldown-1120-12rep-s38-set14-imu-b"),
            partners,
            "a partner stream reached the classpath without a pin in this file",
        )
        assertTrue(
            FieldCorpus.PARTNER_SUFFIX !in FieldCorpus.SIDECAR_SUFFIXES,
            "a partner is IMU and must not be filtered as a sidecar",
        )
        val corpus = FieldCorpus.onClasspath()
        partners.forEach { partner ->
            val base = partner.removeSuffix(FieldCorpus.PARTNER_SUFFIX.removeSuffix(".csv"))
            assertTrue(base in corpus, "$partner has no base capture in the corpus")
            assertTrue(partner !in corpus, "$partner is being counted as a capture of its own")
        }
    }

    @Test
    fun `the field-38 partner reads differently again under the lifter-side geometry`() {
        val samples = load("field-latpulldown-1120-12rep-s38-set14-imu-b")

        // The same stream, the two geometries a fallback has to choose
        // between, and no byte on this classpath says which one that unit
        // needs.
        assertEquals(13, SetAnalyzer.analyze(samples, declared).reps.size, "under the declared stack geometry")
        assertEquals(18, SetAnalyzer.analyze(samples, lifterSide).reps.size, "under the lifter-side geometry")
        assertEquals(33.5, SetAnalyzer.analyze(samples, declared).velocityLossPct, "declared")
        assertEquals(79.3, SetAnalyzer.analyze(samples, lifterSide).velocityLossPct, "lifter-side")
    }

    // ---------------------------------------------------------------------
    // The differentials. Everything above pins what shipped; everything below
    // states what must be true instead, and is red until the analyzer reads
    // `analysedUnitFellBack`.
    // ---------------------------------------------------------------------

    /** A prescription, so the refusal can be shown to withhold a graded tempo too. */
    private val pulldownTargets = SetTargets(plannedReps = 12, tempo = Tempo.parse("1120"))

    @Test
    fun `a fallback under a mount-specific declaration publishes no figures`() {
        val handle = stream(handleTrack(), inverted = false)
        val refused = SetAnalyzer.analyze(handle, declared, targets = pulldownTargets, analysedUnitFellBack = true)

        assertTrue(refused.reps.isEmpty(), "figures were published from a unit the declaration does not describe")
        assertEquals(NoRepsReason.MOUNT_NOT_DECLARED, refused.noRepsReason, "the blank set does not say why")
        assertNull(refused.velocityLossPct, "velocity loss survived the refusal")
        assertNull(refused.tempoCompliance, "the tempo was graded against a stroke nothing identified")
        assertEquals(
            listOf("Analysis moved to the other sensor, whose mounting is not declared — no figures for this set."),
            refused.verdicts,
            "the lifter is told nothing about why the set is blank",
        )
    }

    /**
     * The refusal still measures the sample rate, which comes off the
     * timestamps and owes nothing to geometry.
     *
     * A hard requirement rather than a nicety: `SessionRepository.recordSet`
     * writes `analysis.sampleRateHz` into `RawStreamEntity.sampleRateHz`, and
     * a 0.0 there is the number `ImuCsv`'s own header tells a downstream
     * consumer to divide by. A refusal that returned a placeholder zero would
     * republish that defect on a new population.
     */
    @Test
    fun `the refusal reports the rate it measured rather than a zero`() {
        val handle = stream(handleTrack(), inverted = false)
        val analysed = SetAnalyzer.analyze(handle, declared)
        val refused = SetAnalyzer.analyze(handle, declared, analysedUnitFellBack = true)

        assertTrue(refused.sampleRateHz > 0.0, "a zero rate would reach the raw stream row as a measurement")
        assertEquals(analysed.sampleRateHz, refused.sampleRateHz, "the refusal invented a rate of its own")
    }

    /** The field pair, same rule: role b under the stack declaration is refused rather than read. */
    @Test
    fun `the field-38 partner is refused when the analysis fell back to it`() {
        val samples = load("field-latpulldown-1120-12rep-s38-set14-imu-b")
        val refused = SetAnalyzer.analyze(samples, declared, analysedUnitFellBack = true)

        assertTrue(refused.reps.isEmpty(), "the 13 reps of a stream read under the wrong mount were published")
        assertEquals(NoRepsReason.MOUNT_NOT_DECLARED, refused.noRepsReason)
        assertEquals(99.34946979459392, refused.sampleRateHz, "the measured rate is still published")
    }

    /**
     * Over-refusal is the defect a refusal invites, so both halves of the
     * condition are asserted from the other side.
     *
     * A SINGLE-UNIT SET IS UNCHANGED. That is every ordinary set: nothing
     * fell back, so nothing is refused however the geometry is declared.
     */
    @Test
    fun `a set that did not fall back is analysed under its declaration as before`() {
        val stack = stream(handleTrack(), inverted = true)

        assertEquals(
            SetAnalyzer.analyze(stack, declared),
            SetAnalyzer.analyze(stack, declared, analysedUnitFellBack = false),
            "the flag changed a set that never fell back",
        )
        assertEquals(5, SetAnalyzer.analyze(stack, declared, analysedUnitFellBack = false).reps.size)
    }

    /**
     * AND A FALLBACK UNDER A MOUNT-FREE DECLARATION IS UNCHANGED: the gate
     * does not fire and the analysis proceeds.
     *
     * "THE DECLARATION NAMES NO MOUNT, SO EITHER UNIT'S STREAM IS DESCRIBED
     * BY IT" STOOD HERE AND IS DELETED. It does not follow.
     * [LiftDirection.mountSpecific] reads the ARMED unit's declaration and
     * nothing else, so false is a fact about that declaration and carries no
     * information about where a partner was clipped. What this pins is the
     * gate's condition -- a mount-free declaration is not refused -- and the
     * case it leaves open, a mount-free declaration over a partner that IS
     * mount-specific, is named at [LiftDirection.mountSpecific] as an
     * unfixed remainder.
     */
    @Test
    fun `a fallback under a mount-free declaration is analysed as before`() {
        val freeWeight = LiftDirection(startsWith = StartPhase.CONCENTRIC, concentricUp = true)
        val samples = stream(handleTrack(), inverted = false)

        assertEquals(
            SetAnalyzer.analyze(samples, freeWeight),
            SetAnalyzer.analyze(samples, freeWeight, analysedUnitFellBack = true),
            "a fallback was refused on a declaration that names no mount",
        )
        assertTrue(
            SetAnalyzer.analyze(samples, freeWeight, analysedUnitFellBack = true).reps.isNotEmpty(),
            "the mount-free fallback published nothing",
        )
    }
}
