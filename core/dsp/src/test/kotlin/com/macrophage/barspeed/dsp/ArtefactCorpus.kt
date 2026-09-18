package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.StartPhase
import com.macrophage.barspeed.model.VoiceCue

/**
 * The eleven captures issues #290 and #255 are argued over, each with the
 * geometry, load and bounding instants its own session archive declares.
 *
 * Stated once here because four files walk the same eleven --
 * `ArtefactCorpusBaselineTest`, `ArtefactPeakWithholdingTest`,
 * `ArtefactRuleAlternativesTest` and `ArtefactWindowTest` -- and a second copy
 * of a load or a work-start instant is the *duplicate documentation drifts*
 * class with a numeric trigger. `ArtefactCorpusBaselineTest`'s KDoc carries the
 * provenance of every figure below and the licence for each capture; this file
 * carries no claims, only the inputs.
 */
internal object ArtefactCorpus {
    data class Case(
        val fixture: String,
        val loadKg: Double,
        val direction: LiftDirection,
        /** `workStartedAt_ms`, or null where the set recorded no prep window. */
        val workStartedAtMs: Long?,
        /** True where a metronome ran, which decides which terminal word may bound the set. */
        val cadenceGuided: Boolean,
        /** True where a cue track is committed beside the capture AND carries a metronome. */
        val cued: Boolean,
    )

    private val conFirst = LiftDirection(startsWith = StartPhase.CONCENTRIC, concentricUp = true)
    private val eccFirst = LiftDirection(startsWith = StartPhase.ECCENTRIC, concentricUp = true)
    private val rowOnStack = LiftDirection(
        startsWith = StartPhase.CONCENTRIC,
        concentricUp = true,
        plane = MovementPlane.HORIZONTAL,
        sensorOnStack = true,
    )
    private val pullOnStack = LiftDirection(
        startsWith = StartPhase.CONCENTRIC,
        concentricUp = true,
        sensorOnStack = true,
    )

    val cases = listOf(
        Case("field-ohp-3010-7rep-s42-set02", 24.94758035055195, conFirst, 1788774182888L, true, true),
        Case("field-bench-3010-6rep-s42-set05", 47.62719885105372, eccFirst, 1788774769073L, true, true),
        Case("field-bench-3010-6rep-s42-set07", 56.69904625125443, eccFirst, 1788775291600L, true, true),
        Case("field-cablerow-3010-8rep-s42-set09", 40.82331330090319, rowOnStack, 1788775636216L, true, true),
        Case("field-pullup-3010-8rep-s42-set11", 22.579000000000008, pullOnStack, 1788775982160L, true, true),
        Case("field-pullup-4010-8rep-s42-set13", 22.579000000000008, pullOnStack, 1788776254848L, true, true),
        Case("field-deadlift-straight-5rep-s43-set04", 61.234969951354785, conFirst, null, false, false),
        Case("field-deadlift-straight-5rep-s43-set05", 83.91458845185656, conFirst, null, false, false),
        Case("field-deadlift-straight-5rep-s43-set06", 102.05828325225797, conFirst, null, false, false),
        Case("field-assistedpullup-3010-s37-set08", 30.25, conFirst, null, false, false),
        Case("field-ohp-prepinflated-s37-set03", 22.67961850050177, conFirst, null, false, false),
    )

    fun load(fixture: String): List<ImuSample> =
        ImuCsv.decode(ArtefactCorpus::class.java.getResourceAsStream("/$fixture.csv")!!.readBytes().decodeToString())

    /** The analysis the capture's own session produced, from the samples handed in. */
    fun analyse(case: Case, samples: List<ImuSample> = load(case.fixture)): SetAnalysis = SetAnalyzer.analyze(
        samples,
        case.direction,
        case.loadKg,
        SetTargets(cadenceGuided = case.cadenceGuided),
        DspConfig(),
        if (case.cued) CueTrack.read(case.fixture).map { VoiceCue(it.timestampMs, it.label) } else emptyList(),
        case.workStartedAtMs,
    )

    fun round3(x: Double) = Math.round(x * 1000.0) / 1000.0
}
