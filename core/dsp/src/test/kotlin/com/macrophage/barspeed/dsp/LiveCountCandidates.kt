package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.StartPhase
import kotlin.math.abs
import kotlin.math.max

/**
 * The rep-count candidates issue #301's design round measured, as code on the
 * test classpath rather than in a throwaway source set.
 *
 * Round 1 of that design round modelled every candidate in a scratch tree and
 * threw it away, then wrote *"every candidate is modelled in the test source
 * set"* in the proposal. That sentence was false when it was written and is
 * retracted; this file and the two tests beside it are what makes it true.
 * THAT SENTENCE IS NOW HALF WRONG IN THE OTHER DIRECTION, and is corrected here
 * rather than left standing: it read *"nothing here is production code and
 * nothing here is wired to anything: these are MODELS"*. Candidate (c) WAS
 * chosen, so its rule is [DriveImpulseCounter] in the main source set and
 * [DriveImpulseCandidate] below is a wrapper that drives it. [LeakyTracker] is
 * still a model and is still wired to nothing. Every table below re-derives by
 *
 * ```
 * ./gradlew -PjvmOnly :core:dsp:test --tests "com.macrophage.barspeed.dsp.DriveImpulseCandidateTest" \
 *   --tests "com.macrophage.barspeed.dsp.LeakyIntegratorCandidateTest"
 * ```
 *
 * ## What is re-derivable here and what is not
 *
 * Candidate **(c)**, the drive-impulse counter (now the production class
 * [DriveImpulseCounter], driven here by [DriveImpulseCandidate]), and
 * candidate **(a)**, the leaky integrator ([LeakyTracker] with a `leakTauS`),
 * are both here, each scored over the nine captures the proposal tabled and
 * over the whole-corpus truth set ([CandidateCorpus.truth]).
 *
 * Candidates **(b)**, **(d)** and **(e)** are NOT modelled here:
 *
 * - **(b)** is the shipped path under a different [DspConfig], so it needs no
 *   model -- `DspConfig(anchorStabilityBandMps = x)` handed to
 *   `StreamingSetTracker.forLift` reproduces every row of its table. Its jerk
 *   variant replaced a clause of `VelocityEstimator.isQuietSample` and that
 *   edit is not committed, so the jerk row alone is not re-derivable.
 * - **(d)** is a merge of two shipped counts, one per unit, which
 *   `DeadliftLiveCountFieldTest` already pins the inputs of.
 * - **(e)** composes (c) with the shipped count on the `countTrusted` latch;
 *   the pieces are here, the merge is not.
 *
 * So the (b)-jerk row and the (e) table stay as the proposal's own figures and
 * are marked there as not re-derivable from this branch.
 */
internal object LiveCountCandidates {
    /** Seconds, for readability where a raw millisecond arrives. */
    const val MS_PER_S = 1000.0

    fun load(fixture: String): List<ImuSample> = ImuCsv.decode(
        LiveCountCandidates::class.java.getResourceAsStream("/$fixture.csv")!!.readBytes().decodeToString(),
    )

    fun onClasspath(name: String): Boolean = LiveCountCandidates::class.java.getResource("/$name") != null

    /** The shipped live count over a whole stream, for the row every table compares against. */
    fun shippedCount(fixture: String, direction: LiftDirection, config: DspConfig = DspConfig()): Int {
        val tracker = StreamingSetTracker.forLift(direction, config)
        var last = LiveSetState()
        for (sample in load(fixture)) last = tracker.feed(sample)
        return last.repCount
    }
}

/**
 * Candidate (a): [StreamingSetTracker] with a leak on the velocity, and with a
 * second switch that re-anchors at every run boundary.
 *
 * ## Why a clone and not a `DspConfig` flag
 *
 * A leak is not a threshold -- it changes the integrator's difference equation
 * -- so it cannot be modelled by reconfiguring the shipped class. This is a
 * copy of `StreamingSetTracker`'s integrator, ZUPT, bias learner and run
 * machine with the leak inserted at one line, and it reads
 * `StreamingSetTracker`'s own constants rather than restating them, so the two
 * cannot drift on a number.
 *
 * **The clone's licence is measured, not assumed.** With both switches off it
 * must reproduce the shipped count on every committed capture;
 * [LeakyIntegratorCandidateTest] asserts exactly that over all 51, and every
 * switched-on figure is worthless without it.
 *
 * ## The leak, stated as an equation
 *
 * With `leakTauS = tau`, both the integrator state and the ZUPT anchor offset
 * decay by `dt / tau` each frame. Their difference is the published velocity,
 * so the published velocity obeys `dv/dt = a - v / tau` -- a one-pole
 * high-pass, which is what candidate (a) was. Decaying only the state would
 * have left the anchor offset standing and turned every accepted anchor into a
 * permanent velocity bias, which is a different model with worse behaviour and
 * not the one the proposal reported.
 *
 * **What the clone deliberately omits:** the per-rep mean and peak velocity
 * accumulators. Those feed `LiveSetState.repMeanVelocities` and
 * `repPeakVelocities`, which no candidate reads and no table below scores, and
 * they take no part in the count -- the licence test proves the omission costs
 * nothing that matters here by reproducing the shipped count on all 54.
 *
 * @param anchorAtEveryRunBoundary the "per-run reset instead of a leak"
 *   variant. Its rule was not written down in round 1, so it is stated here:
 *   every transition of the run classifier -- into motion, out of motion, or
 *   between directions -- is taken as a zero-velocity anchor, with no
 *   stillness test at all. That is the most aggressive re-anchoring available
 *   short of clamping, and the figures it produces are a re-measurement under
 *   this definition rather than a reproduction of round 1's.
 */
internal class LeakyTracker(
    direction: LiftDirection,
    private val config: DspConfig = DspConfig(),
    private val leakTauS: Double? = null,
    private val anchorAtEveryRunBoundary: Boolean = false,
    expectedSampleRateHz: Double = 100.0,
) {
    private val startsWith: StartPhase = direction.startsWith
    private val velocityScale: Double = direction.sensorToLifter
    private val driveIsPositive: Boolean = direction.driveIsPositive
    private val thresholds: RunThresholds = RunThresholds.forSeriesScaledBy(config, velocityScale)

    private var filter = Biquad.lowPass(config.lowPassCutoffHz, expectedSampleRateHz)

    private var rateCalibrated = false
    private var firstArrivalMs = Long.MIN_VALUE
    private var lastArrivalMs = Long.MIN_VALUE
    private var sampleCount = 0
    private var frameIntervalS = 1.0 / expectedSampleRateHz
    private var correctedTimeS = 0.0

    private var lastAccel = 0.0
    private var rawV = 0.0
    private var anchorOffset = 0.0
    private var anchorTimeS = 0.0
    private var accelBias = 0.0

    private var quietWindowStartS = Double.NaN
    private var quietWindowLo = 0.0
    private var quietWindowHi = 0.0

    private var runType = 0
    private var runStartS = 0.0
    private var runPeak = 0.0
    private var runDisplacement = 0.0

    private var repCount = 0
    private var countTrusted = true

    /** What this clone publishes; a subset of `LiveSetState`, the fields the candidates read. */
    data class Reading(
        val velocityMps: Double,
        val repCount: Int,
        val countTrusted: Boolean,
        val elapsedS: Double,
        val accelMps2: Double,
    )

    fun feed(sample: ImuSample): Reading {
        val firstSample = sampleCount == 0
        updateClock(sample.timestampMs)
        val timeS = correctedTimeS
        val filtered = filter.process(FrameTransform.verticalLinearAccelMps2(sample, config.gravityMps2))
        val quietSample = VelocityEstimator.isQuietSample(sample, config)
        if (quietSample) {
            val alpha = (frameIntervalS / StreamingSetTracker.BIAS_TAU_S)
                .coerceAtMost(StreamingSetTracker.MAX_BIAS_ALPHA)
            accelBias += alpha * (filtered - accelBias)
        }
        val accel = filtered - accelBias
        if (!firstSample) {
            rawV += 0.5 * (accel + lastAccel) * frameIntervalS
        }
        lastAccel = accel
        val tau = leakTauS
        if (tau != null) {
            val decay = frameIntervalS / tau
            rawV -= rawV * decay
            anchorOffset -= anchorOffset * decay
        }

        updateZupt(quietSample, timeS)
        val v = (rawV - anchorOffset) * velocityScale
        updateRuns(v, timeS)
        return Reading(
            velocityMps = v,
            repCount = repCount,
            countTrusted = countTrusted,
            elapsedS = timeS,
            accelMps2 = accel,
        )
    }

    private fun updateClock(arrivalMs: Long) {
        if (sampleCount == 0) {
            firstArrivalMs = arrivalMs
        } else {
            correctedTimeS += frameIntervalS
        }
        lastArrivalMs = arrivalMs
        sampleCount++
        val spanS = (lastArrivalMs - firstArrivalMs) / LiveCountCandidates.MS_PER_S
        if (sampleCount >= StreamingSetTracker.RATE_WARMUP_SAMPLES && spanS >= StreamingSetTracker.RATE_WARMUP_SPAN_S) {
            val measured = spanS / (sampleCount - 1)
            val usable = measured in
                StreamingSetTracker.MIN_FRAME_INTERVAL_S..StreamingSetTracker.MAX_FRAME_INTERVAL_S
            if (usable) {
                frameIntervalS = measured
                if (!rateCalibrated) {
                    filter = Biquad.lowPass(config.lowPassCutoffHz, 1.0 / measured)
                    rateCalibrated = true
                }
            }
        }
    }

    private fun updateZupt(quiet: Boolean, timeS: Double) {
        if (!quiet) {
            quietWindowStartS = Double.NaN
            return
        }
        if (quietWindowStartS.isNaN()) {
            quietWindowStartS = timeS
            quietWindowLo = rawV
            quietWindowHi = rawV
        }
        quietWindowLo = minOf(quietWindowLo, rawV)
        quietWindowHi = maxOf(quietWindowHi, rawV)
        if (timeS - quietWindowStartS >= config.minStationaryS) {
            val stable = quietWindowHi - quietWindowLo <= config.anchorStabilityBandMps
            val elapsedS = timeS - anchorTimeS
            val nearPrev = VelocityEstimator.anchorAcceptable(abs(rawV - anchorOffset), elapsedS, config)
            val starved = elapsedS > StreamingSetTracker.ANCHOR_STARVATION_S
            if (stable && (nearPrev || starved)) {
                anchorOffset = rawV
                anchorTimeS = timeS
            }
            quietWindowStartS = timeS
            quietWindowLo = rawV
            quietWindowHi = rawV
        }
    }

    private fun updateRuns(v: Double, timeS: Double) {
        val type = when {
            v > thresholds.pauseBandMps -> 1
            v < -thresholds.pauseBandMps -> -1
            else -> 0
        }
        if (type == runType) {
            if (type != 0) {
                runPeak = max(runPeak, abs(v))
                runDisplacement += abs(v) * frameIntervalS
                noteRunaway()
            }
            return
        }
        if (runType != 0) {
            val duration = timeS - runStartS
            val qualified = runPeak >= thresholds.startThresholdMps &&
                duration >= thresholds.minPhaseS &&
                runDisplacement >= thresholds.minRomM &&
                runDisplacement <= thresholds.maxRunDisplacementM
            if (qualified) onQualifiedRun(runType)
        }
        if (anchorAtEveryRunBoundary) {
            anchorOffset = rawV
            anchorTimeS = timeS
        }
        runType = type
        runStartS = timeS
        runPeak = abs(v)
        runDisplacement = abs(v) * frameIntervalS
        noteRunaway()
    }

    private fun noteRunaway() {
        if (runDisplacement > thresholds.maxRunDisplacementM) countTrusted = false
    }

    private var eccentricPending = false

    private fun onQualifiedRun(runDirection: Int) {
        val concentric = (runDirection == 1) == driveIsPositive
        if (startsWith == StartPhase.ECCENTRIC) {
            if (!concentric) {
                eccentricPending = true
            } else if (eccentricPending) {
                eccentricPending = false
                repCount++
            }
        } else if (concentric) {
            repCount++
        }
    }

    /** The count this clone reaches over a whole stream. */
    fun count(samples: List<ImuSample>): Int {
        var last = 0
        for (sample in samples) last = feed(sample).repCount
        return last
    }

    /**
     * The instants, in seconds on the reconstructed clock, at which this clone's
     * count goes up -- the run-close instant, which is where the shipped path
     * counts too, so these are directly comparable with a drive window.
     */
    fun callsAtS(samples: List<ImuSample>): List<Double> {
        val calls = mutableListOf<Double>()
        var previous = 0
        for (sample in samples) {
            val reading = feed(sample)
            if (reading.repCount > previous) calls += reading.elapsedS
            previous = reading.repCount
        }
        return calls
    }
}

/**
 * Candidate (c), the velocity-free drive-impulse counter -- now a THIN WRAPPER
 * over the production class, [DriveImpulseCounter].
 *
 * ## There is one copy of the rule, and it is not here any more
 *
 * This object held the state machine and four test-local `const val`s while the
 * candidate was being scored. The owner chose candidate (c) on issue #301, so
 * the rule moved to `:core:dsp`'s main source set and the four constants moved
 * to [DspConfig] with their provenance written on them. What is left here is the
 * harness: it feeds a [StreamingSetTracker] and a [DriveImpulseCounter] the same
 * stream the app would and collects the instants the counter speaks at, so every
 * table in [DriveImpulseCandidateTest] now measures the PRODUCTION class rather
 * than a model of it.
 *
 * Keeping the name is deliberate. Issue #301's design round published its tables
 * against "candidate (c)", and those tables re-derive here unchanged -- which is
 * the licence that the extraction changed nothing. A second copy of the rule to
 * compare against would be the repo's *duplicate documentation drifts* class in
 * code: two statements of one decision, free to diverge on a number.
 */
internal object DriveImpulseCandidate {
    /**
     * The instants, in seconds on the tracker's reconstructed clock, at which
     * the drive-impulse counter speaks.
     *
     * [LiveSetState.elapsedS] rather than the arrival stamp, because every
     * window and every published figure in this harness is on that clock.
     */
    fun callsAtS(samples: List<ImuSample>, direction: LiftDirection, config: DspConfig = DspConfig()): List<Double> {
        val tracker = StreamingSetTracker.forLift(direction, config)
        val counter = DriveImpulseCounter(direction, config)
        val calls = mutableListOf<Double>()
        for (sample in samples) {
            val live = tracker.feed(sample)
            if (counter.feed(live, sample.timestampMs) is RepCall.Speak) calls += live.elapsedS
        }
        return calls
    }

    fun count(samples: List<ImuSample>, direction: LiftDirection, config: DspConfig = DspConfig()): Int =
        callsAtS(samples, direction, config).size
}

/**
 * The captures both candidates are scored over, and where each capture's truth
 * comes from.
 *
 * ## The geometry table is a fourth copy and says so
 *
 * The 54 `(fixture, geometry)` pairs are `RepRefusalCorpusTest`'s list with the
 * loads dropped -- a count needs no load. That file's own KDoc records where it
 * took them from: `BatchCueCoverageTest`, `StackMountGeometryTest`,
 * `FieldDataRegressionTest` and each session's `meta.json`. A fifth copy would
 * be the repo's *duplicate documentation drifts* class, so what is copied here
 * is guarded the same mechanical way theirs is: [ALL] is asserted equal to
 * `FieldCorpus.onClasspath()`, so a capture committed without a row here reds a
 * test instead of sitting silently unscored.
 *
 * ## Truth, by one precedence, applied to every capture
 *
 * 1. **A STATED count** where a human said what the lifter did and no sidecar
 *    can: [STATED]. Four captures, each for a different reason, all named
 *    there.
 * 2. **Rep marks**, `-reps.csv`, where one is committed. Thirteen captures.
 *    `RepMarks`' KDoc is load-bearing here: on every committed capture the
 *    marks are the GUIDE's, so a mark is the metronome's claim about a rep and
 *    not the lifter's -- which is exactly why a stated count outranks it.
 * 3. **Cue-called reps**, the `Down` rows of `-cues.csv`, where one is
 *    committed. Sixteen captures.
 *
 * Twelve captures get no truth and are scored by neither candidate. Six of
 * those DO carry a performed count in `FieldDataRegressionTest`'s prose
 * (`field-cablerow-static-8rep` 8, `field-facepull-static-12rep` 12,
 * `field-pallof-static-12rep` 12, `field-ohp-100hz-bursty` 8,
 * `field-backsquat-10hz-set5` 0, `field-still-0rep` 0) and folding them in
 * would move every corpus total in both candidate tests. They are deliberately
 * left out so this file's totals stay comparable with the ones issue #301's
 * proposal published, and the omission is named here rather than left as a
 * silent boundary.
 */
internal object CandidateCorpus {
    private val ECC = LiftDirection(startsWith = StartPhase.ECCENTRIC)
    private val CON = LiftDirection(startsWith = StartPhase.CONCENTRIC)

    /** A stack-mounted, drive-DOWN lift: leg curl and lat pulldown declare the same block. */
    private val DOWN_ON_STACK = LiftDirection(
        startsWith = StartPhase.CONCENTRIC,
        concentricUp = false,
        sensorInverted = true,
        plane = MovementPlane.VERTICAL,
        sensorOnStack = true,
    )

    /**
     * The two stack-mounted geometries issues #290 and #255 committed, declared
     * here with the SAME values `ArtefactCorpus` declares for the same
     * captures, so the two lanes' corpora cannot disagree about one capture's
     * geometry.
     */
    private val ROW_ON_STACK = LiftDirection(
        startsWith = StartPhase.CONCENTRIC,
        concentricUp = true,
        plane = MovementPlane.HORIZONTAL,
        sensorOnStack = true,
    )
    private val PULL_ON_STACK = LiftDirection(
        startsWith = StartPhase.CONCENTRIC,
        concentricUp = true,
        sensorOnStack = true,
    )

    /** Where a capture's truth came from, published beside the number. */
    enum class Basis { STATED, MARKS, CUES, NONE }

    data class Capture(val fixture: String, val direction: LiftDirection)

    data class Truth(val reps: Int?, val basis: Basis)

    /**
     * A human-stated performed count, outranking both sidecars.
     *
     * - The three field-43 deadlifts: their `-cues.csv` is the SENSOR'S own
     *   calls, not a metronome, so reading reps off it would score the counter
     *   against its own output. 5 each is the owner's count in chat, with set
     *   6's stated as possibly 6 (`DeadliftLiveCountFieldTest`), so a
     *   candidate scoring 6 there is not thereby wrong.
     * - `field-seated-ohp-2rep`: no sidecar of any kind; 2 is
     *   `FieldDataRegressionTest`'s stated count.
     * - `field-ohp-3010-6rep-s37-set02`: a FAILED set. The metronome called 8
     *   and 7 marks were written while the lifter got 6
     *   (`CuedRepCoverageTest.Cued.cued`), so this is the one capture where a
     *   mark count is demonstrably not a rep count.
     *
     * **That last row is a correction to issue #301's proposal.** Its
     * corpus row took 7 marks for this capture while its nine-capture table
     * took the stated 6, so the two tables disagreed by one on the same
     * capture. Truth here is 6 in both, which moves the published corpus truth
     * from 254 to 253.
     */
    val STATED = mapOf(
        "field-deadlift-straight-5rep-s43-set04" to 5,
        "field-deadlift-straight-5rep-s43-set05" to 5,
        "field-deadlift-straight-5rep-s43-set06" to 5,
        "field-seated-ohp-2rep" to 2,
        "field-ohp-3010-6rep-s37-set02" to 6,
        // The six field-42 captures issues #290 and #255 committed. Each
        // number is the lifter's OWN count for that set, read this round from
        // `BarSpeed-field-captures/field-42/extracted/session.json`, where
        // every one of the six carries `repsManual: true` -- the strongest
        // basis this enum has, and the reason they are stated rather than left
        // to their cue tracks. Two of the six the sidecar would get wrong:
        // set 2 is a FAILED set whose metronome called eight Downs against
        // seven performed, and set 9 is a cable row whose track speaks
        // Drive/Return, so `CueTrack.calledReps` -- which counts "Down" --
        // reads 0 on an eight-rep set. That vocabulary gap in `calledReps` is
        // named rather than fixed here; it is not this lane's to change.
        "field-ohp-3010-7rep-s42-set02" to 7,
        "field-bench-3010-6rep-s42-set05" to 6,
        "field-bench-3010-6rep-s42-set07" to 6,
        "field-cablerow-3010-8rep-s42-set09" to 8,
        "field-pullup-3010-8rep-s42-set11" to 8,
        "field-pullup-4010-8rep-s42-set13" to 8,
    )

    val ALL = listOf(
        Capture("field-assistedpullup-3010-s37-set08", CON),
        Capture("field-assistedpullup-3010-s37-set09", CON),
        Capture("field-assistedpullup-3010-s37-set10", CON),
        Capture("field-backsquat-10hz", ECC),
        Capture("field-backsquat-10hz-set5", ECC),
        Capture("field-backsquat-4011-6rep-s36-set01", ECC),
        Capture("field-backsquat-99hz-6rep", ECC),
        Capture("field-backsquat-wrapping-s36-set01", ECC),
        Capture("field-bench-3010-6rep-s37-set05", ECC),
        Capture("field-bench-3010-6rep-s37-set06", ECC),
        Capture("field-bench-3010-6rep-s42-set05", ECC),
        Capture("field-bench-3010-6rep-s42-set07", ECC),
        Capture("field-bench-rotating-6rep", ECC),
        Capture("field-bench-rotating-6rep-ok", ECC),
        Capture("field-cablerow-3010-8rep-s42-set09", ROW_ON_STACK),
        Capture("field-cablerow-static-8rep", CON),
        Capture("field-deadlift-straight-5rep-s43-set04", CON),
        Capture("field-deadlift-straight-5rep-s43-set05", CON),
        Capture("field-deadlift-straight-5rep-s43-set06", CON),
        Capture("field-facepull-static-12rep", CON),
        Capture("field-inclinepress-3010-12rep-s38-set02", ECC),
        Capture("field-latpulldown-1120-12rep-s38-set14", DOWN_ON_STACK),
        Capture("field-legcurl-1030-10rep", DOWN_ON_STACK),
        Capture("field-legcurl-1030-12rep", DOWN_ON_STACK),
        Capture("field-legcurl-1030-12rep-b", DOWN_ON_STACK),
        Capture("field-legcurl-1030-12rep-c", DOWN_ON_STACK),
        Capture("field-legpress-2010-8rep", ECC),
        Capture("field-legpress-single-2010-8rep", CON),
        Capture("field-legpress-single-2011-8rep-s36-set07", CON),
        Capture("field-ohp-100hz-bursty", ECC),
        Capture("field-ohp-3010-6rep-s37-set02", CON),
        Capture("field-ohp-3010-7rep-s42-set02", CON),
        Capture("field-ohp-3010-8rep-s37-set01", CON),
        Capture("field-ohp-3010-8rep-s38-set04", CON),
        Capture("field-ohp-3010-8rep-s38-set05", CON),
        Capture("field-ohp-prepinflated-s37-set03", CON),
        Capture("field-ohp-prepinflated-s37-set04", CON),
        Capture("field-ohp-rotating-8rep", ECC),
        Capture("field-ohp-rotating-8rep-b", ECC),
        Capture("field-pallof-static-12rep", CON),
        Capture("field-pullup-3010-8rep-s37-set09", CON),
        Capture("field-pullup-3010-8rep-s42-set11", PULL_ON_STACK),
        Capture("field-pullup-4010-8rep-s42-set13", PULL_ON_STACK),
        Capture("field-rdl-3010-10rep", ECC),
        Capture("field-rdl-3010-10rep-s36-set04", ECC),
        Capture("field-rdl-3010-10rep-s36-set05", ECC),
        Capture("field-rdl-wrapping-s36-set05", ECC),
        Capture("field-reardeltfly-s32-set06", CON),
        Capture("field-ropedeadhang-hold20-s37-set11", ECC),
        // #259's three holds, committed on the branch that landed after
        // this file. Each carries a cue track that calls no rep, so [truth]
        // scores them 0 on the CUES basis -- the same basis and the same
        // number the hold already in this list takes.
        Capture("field-ropedeadhang-hold45-s38-set17", ECC),
        Capture("field-ropedeadhang-hold45-s38-set18", ECC),
        Capture("field-ropefarmershold-hold30-s42-set16", ECC),
        Capture("field-seated-ohp-2rep", CON),
        Capture("field-still-0rep", ECC),
    )

    fun truth(fixture: String): Truth {
        STATED[fixture]?.let { return Truth(it, Basis.STATED) }
        if (LiveCountCandidates.onClasspath("$fixture-reps.csv")) {
            return Truth(RepMarks.read(fixture).size, Basis.MARKS)
        }
        if (LiveCountCandidates.onClasspath("$fixture-cues.csv")) {
            return Truth(CueTrack.calledReps(fixture), Basis.CUES)
        }
        return Truth(null, Basis.NONE)
    }

    /** The captures with a truth, in [ALL]'s order. */
    fun scored(): List<Pair<Capture, Truth>> = ALL.map { it to truth(it.fixture) }.filter { it.second.reps != null }

    /**
     * The nine captures issue #301's proposal tabled per candidate: the three
     * deadlifts, five committed overhead presses and the seated two-rep press.
     * Truth comes from [truth] like every other row, so the nine-capture table
     * and the corpus table cannot disagree about a capture the way the
     * proposal's two tables did.
     */
    val NINE = listOf(
        "field-deadlift-straight-5rep-s43-set04",
        "field-deadlift-straight-5rep-s43-set05",
        "field-deadlift-straight-5rep-s43-set06",
        "field-ohp-3010-6rep-s37-set02",
        "field-ohp-prepinflated-s37-set03",
        "field-ohp-prepinflated-s37-set04",
        "field-ohp-3010-8rep-s38-set04",
        "field-ohp-3010-8rep-s38-set05",
        "field-seated-ohp-2rep",
    )

    fun capture(fixture: String): Capture = ALL.first { it.fixture == fixture }

    /**
     * Per-rep drive windows for the three field-43 deadlifts, in seconds on the
     * DSP's reconstructed clock, with the rows the analysis marked NOT a rep
     * carried as [RepWindow.isRep] false.
     *
     * **Provenance, and what it is not.** These are the per-rep tables of the
     * field-43 session analysis
     * (`BarSpeed-field-captures/field-43/analysis/lens-a/lens-a.md`), which
     * issue #301's body quotes for set 4. They are batch-derived drive spans
     * over this same capture, reviewed by a human against both units and the
     * cue track -- NOT a hand measurement and not an independent observation of
     * the barbell. So a per-rep score against them says where a candidate's
     * calls land relative to the drives the batch path resolved, which is the
     * finest truth this repository has for a straight-reps set; issue #145's F1
     * -- a capture whose marks are the lifter's own taps -- is still owed and is
     * what would replace it.
     */
    data class RepWindow(val startS: Double, val endS: Double, val isRep: Boolean)

    val DEADLIFT_WINDOWS = mapOf(
        "field-deadlift-straight-5rep-s43-set04" to listOf(
            RepWindow(5.39, 6.53, true),
            RepWindow(10.35, 10.92, true),
            RepWindow(12.60, 14.87, true),
            RepWindow(17.64, 19.33, true),
            RepWindow(22.74, 23.52, true),
            RepWindow(25.14, 28.10, false),
        ),
        "field-deadlift-straight-5rep-s43-set05" to listOf(
            RepWindow(5.47, 7.92, true),
            RepWindow(8.39, 9.11, true),
            RepWindow(10.58, 12.18, true),
            RepWindow(13.37, 15.16, true),
            RepWindow(16.38, 18.55, true),
            RepWindow(19.21, 22.01, false),
        ),
        "field-deadlift-straight-5rep-s43-set06" to listOf(
            RepWindow(4.37, 5.11, false),
            RepWindow(6.46, 7.89, true),
            RepWindow(8.78, 9.80, true),
            RepWindow(11.34, 12.22, true),
            RepWindow(13.83, 15.16, true),
            RepWindow(17.02, 18.35, true),
            RepWindow(19.49, 21.89, false),
        ),
    )

    /**
     * How far from a drive window a call may fall and still belong to it,
     * seconds, on EITHER side.
     *
     * Either side, and the leading side is the one that had to be measured
     * rather than assumed. A [DEADLIFT_WINDOWS] window is a
     * VELOCITY-derived span, while candidate (c) calls on ACCELERATION, which
     * leads velocity: set 4's rep 2 window opens at 10.35 s and (c) calls it at
     * 10.31 s, 40 ms early. A trailing-only tolerance scored that call as a
     * phantom AND the rep as missed -- two wrong cells from one clock
     * convention -- which is the repo's *wrong pair* class inside a scoring
     * rule. The first version of this harness did exactly that.
     *
     * 0.60 s admits every call either candidate produces near a window on these
     * three captures. It is wider than half the shortest gap between two
     * consecutive windows (set 5's 7.92 to 8.39, 0.47 s), so windows can
     * overlap, and [perRep] resolves that by NEAREST window rather than by
     * first match -- deterministic, and the only rule that does not depend on
     * the table's order.
     */
    const val CALL_TOLERANCE_S = 0.60

    /** counted / missed / phantom against [DEADLIFT_WINDOWS], for calls in seconds. */
    data class PerRep(val counted: Int, val missed: Int, val phantom: Int)

    fun perRep(fixture: String, callsAtS: List<Double>): PerRep {
        val windows = DEADLIFT_WINDOWS.getValue(fixture)
        val hit = BooleanArray(windows.size)
        var phantom = 0
        for (call in callsAtS) {
            val distances = windows.map { window ->
                when {
                    call < window.startS -> window.startS - call
                    call > window.endS -> call - window.endS
                    else -> 0.0
                }
            }
            val nearest = distances.indices.minBy { distances[it] }
            if (distances[nearest] > CALL_TOLERANCE_S || !windows[nearest].isRep) {
                phantom++
            } else {
                hit[nearest] = true
            }
        }
        val reps = windows.indices.filter { windows[it].isRep }
        return PerRep(
            counted = reps.count { hit[it] },
            missed = reps.count { !hit[it] },
            phantom = phantom,
        )
    }
}
