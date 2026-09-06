package com.macrophage.barspeed.dsp

import java.io.File

/**
 * Every committed IMU capture on this module's test classpath, by fixture
 * name, sorted.
 *
 * Eight files walked the resource directory with their own copy of the same
 * three-clause filter -- `startsWith("field-")`, `endsWith(".csv")`, and a
 * hand-kept list of sidecar suffixes to skip. `AnchorSupplyByMountTest`,
 * `BatchCueCoverageTest`, `BlankAnalysisReasonTest`, `BlankAnalysisTest`,
 * `CuedRepCoverageTest`, `GyroGateTest`, `RepRefusalCorpusTest` and
 * `RunawayDriftTest` each held one. Adding a THIRD sidecar suffix meant
 * editing all eight, and the failure mode of missing one is not a compile
 * error: the sidecar is handed to `ImuCsv.decode`, which cannot read it.
 *
 * That is the repo's *duplicate documentation drifts* class with a mechanical
 * trigger, so the filter is stated once here and every caller reads it.
 *
 * ## What counts as a capture
 *
 * A `field-*.csv` that is not a sidecar. The sidecars are the streams the app
 * writes BESIDE a set's IMU file, one suffix each, all of them on the epoch-ms
 * arrival clock and none of them decodable as IMU:
 *
 * - `-cues.csv` -- the voice track, read by [CueTrack].
 * - `-prep.csv` -- the prep window's instants, read by `PrepDetectionFieldTest`.
 * - `-reps.csv` -- the rep marks, read by [RepMarks]. Added with issue #145.
 *
 * The list is a suffix set rather than a pattern because a pattern would have
 * to guess: `field-legpress-single-2010-8rep` is a capture and
 * `field-legcurl-1030-12rep-b` is a capture, and neither is distinguishable
 * from a sidecar by shape.
 *
 * ## What a PARTNER stream is, and why it is not one
 *
 * A `-imu-b.csv` is the SECOND UNIT'S view of a set whose first unit is
 * already in this list. It is not a sidecar -- it decodes as IMU like any
 * capture -- and it is not another capture either: it is the same set, the
 * same reps and the same seconds, seen from a different mount. Issue #247
 * committed the first one.
 *
 * Counting it here would double every aggregate the corpora publish. The rep
 * coverage totals, the cue coverage totals, the gyro-gate corpus and the
 * runaway-pass histogram are all read as "how much of the recorded work
 * resolves", and one set contributing twice makes that figure a statement
 * about the fixture directory rather than about the corpus.
 *
 * SO IT IS EXCLUDED AND THE EXCLUSION IS NARROW. The guarantee those corpora
 * exist for -- no committed capture is silently unscored -- is kept by
 * [partnersOnClasspath] and the pins in `FallbackMountGeometryTest`, which
 * name every partner, score every partner, and refuse a partner whose base
 * capture is not itself in [onClasspath]. A partner cannot be smuggled onto
 * the classpath unscored, and it cannot be smuggled in without its own set.
 */
internal object FieldCorpus {
    /** The sidecar suffixes a `field-*.csv` can carry; see the class KDoc. */
    val SIDECAR_SUFFIXES = listOf("-cues.csv", "-prep.csv", "-reps.csv")

    /** The suffix that marks a second unit's view of a set already in [onClasspath]. */
    const val PARTNER_SUFFIX = "-imu-b.csv"

    /**
     * Located by resolving one capture that is certain to exist and reading
     * its parent directory, which is how all eight callers did it: the
     * resource root is a build output whose path no test may hard-code.
     */
    fun onClasspath(): List<String> = names().filterNot { it.endsWith(PARTNER_SUFFIX.removeSuffix(".csv")) }.sorted()

    /**
     * The partner streams, by fixture name -- the second unit's view of a set
     * [onClasspath] already carries. Empty until #247; see the class KDoc.
     */
    fun partnersOnClasspath(): List<String> =
        names().filter { it.endsWith(PARTNER_SUFFIX.removeSuffix(".csv")) }.sorted()

    /** Every `field-*.csv` that is not a sidecar, partners included. */
    private fun names(): List<String> {
        val dir = File(FieldCorpus::class.java.getResource("/field-still-0rep.csv")!!.toURI()).parentFile
        return dir.list()!!
            .filter { name ->
                name.startsWith("field-") && name.endsWith(".csv") &&
                    SIDECAR_SUFFIXES.none { name.endsWith(it) }
            }
            .map { it.removeSuffix(".csv") }
    }
}
