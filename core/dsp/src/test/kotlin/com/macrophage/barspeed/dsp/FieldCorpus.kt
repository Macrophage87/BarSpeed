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
 */
internal object FieldCorpus {
    /** The sidecar suffixes a `field-*.csv` can carry; see the class KDoc. */
    val SIDECAR_SUFFIXES = listOf("-cues.csv", "-prep.csv", "-reps.csv")

    /**
     * Located by resolving one capture that is certain to exist and reading
     * its parent directory, which is how all eight callers did it: the
     * resource root is a build output whose path no test may hard-code.
     */
    fun onClasspath(): List<String> {
        val dir = File(FieldCorpus::class.java.getResource("/field-still-0rep.csv")!!.toURI()).parentFile
        return dir.list()!!
            .filter { name ->
                name.startsWith("field-") && name.endsWith(".csv") &&
                    SIDECAR_SUFFIXES.none { name.endsWith(it) }
            }
            .map { it.removeSuffix(".csv") }
            .sorted()
    }
}
