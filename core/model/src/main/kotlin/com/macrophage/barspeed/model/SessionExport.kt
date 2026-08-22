package com.macrophage.barspeed.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Root of a session export; contract is docs/schemas/session-export.schema.json. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SessionExport(
    // Always written, even though it equals its default: the exporter drops
    // defaults, and an export without its version is unreadable by anything
    // that has to tell 1.0's field meanings from 1.1's.
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val schemaVersion: String = SCHEMA_VERSION,
    val startedAt: String,
    val endedAt: String? = null,
    /**
     * Where the device was and what UTC offset it was on when this session was
     * recorded, so a reader can recover the local time of day it happened at.
     *
     * [startedAt] and [endedAt] are UTC instants and stay that way: they are
     * correct, and a conforming reader gets the same moment out of them either
     * rendered with an offset or rendered with a `Z`. What it cannot get out of
     * them is the time on the wall, and time of day is a training variable —
     * it separates a fasted early session from an evening one and is how a
     * session lines up against sleep.
     *
     * Absent means the session was recorded before the app captured this, and
     * that is permanent for those sessions. Nothing durable — not the session
     * row, not the set rows, not the raw IMU, heart-rate or cue CSVs, all of
     * which carry epoch milliseconds — records the offset a past session was
     * on, so it cannot be recomputed the way a DSP figure can. Filling it in at
     * export time from the device's current zone was refused deliberately: it
     * would be right for a session recorded in the zone the phone is in now,
     * silently wrong for one recorded before a flight, and indistinguishable
     * from a value that was actually measured.
     */
    val timeZone: RecordedTimeZone? = null,
    val planRef: String? = null,
    val notes: String? = null,
    val heartRate: HrSessionSummary? = null,
    val exercises: List<ExerciseExport>,
) {
    companion object {
        /**
         * 1.1 — velocityLoss_pct became best→LAST rep (was best→worst), unknown
         * phases report null instead of 0, tempo compliance scores movement
         * digits only, and repMetricsComplete says whether the per-rep array
         * covers the whole set.
         *
         * 1.2 — a set may carry the direction and geometry it was measured
         * with, and where each of those values came from. Purely additive: no
         * existing key changed type or stopped being written, so a reader
         * written against 1.1 works unchanged against 1.2. The key is absent on
         * sets recorded before the app captured it.
         *
         * 1.3 — a session may carry [timeZone], the device's zone and the UTC
         * offset in effect when it was recorded. Purely additive on the same
         * terms: `startedAt` and `endedAt` are byte-for-byte what 1.2 wrote,
         * still UTC with a `Z`, so a 1.2 reader works unchanged against 1.3.
         * Re-rendering them with an offset was considered and refused — both
         * forms are the same instant to a conforming parser, so it would buy a
         * correct reader nothing, while a reader that strips the designator
         * would silently start treating a local time as UTC and lose the
         * instant altogether. The key is absent on sessions recorded before the
         * app captured it.
         *
         * 1.4 through 1.10 are documented in the `schemaVersion` description of
         * `docs/schemas/session-export.schema.json`, which is the published
         * contract, and are deliberately not repeated here -- this KDoc stopped
         * being kept up at 1.3, and backfilling seven entries from memory into a
         * second statement of a contract that already has one is how the plan
         * contract came to have four statements that disagree. The drift is real
         * and is named rather than fixed here.
         *
         * 1.11 -- a set may carry `plannedPrep_s` and `prep_s`. Purely
         * additive: no key from 1.10 changed type or stopped being written, so
         * a 1.10 reader works unchanged against a 1.11 export. Both keys are
         * absent on a set that played no prep, and on every set recorded before
         * this version.
         */
        const val SCHEMA_VERSION = "1.11"

        /**
         * `"1.10"` is not the number 1.1 -- a reader that parses this field as
         * a float collides 1.10 with 1.1, which is a different contract.
         */
        val SUPPORTED_SCHEMA_VERSIONS =
            setOf(
                "1.0", "1.1", "1.2", "1.3", "1.4", "1.5",
                "1.6", "1.7", "1.8", "1.9", "1.10", "1.11",
            )

        /**
         * Which phase a rep opened with, lowercased [StartPhase] names. 1:1
         * with the enum, so it is pinned in both directions rather than only
         * against the published schema.
         */
        val VALID_STARTS_WITH = setOf("eccentric", "concentric")

        /** How a geometry value was arrived at, lowercased [GeometrySource] names. */
        val VALID_GEOMETRY_SOURCES = setOf("declared", "seeded", "inferred", "default")

        /**
         * Why a set does or does not carry `velocityLoss_pct`, the values
         * [SetExport.velocityLossBasis] is drawn from.
         *
         * The names are owned by `VelocityLoss` in `:core:dsp`, which this
         * module cannot see -- the dependency runs the other way. They are
         * mirrored here so the published schema has a Kotlin constant to be
         * pinned against, the same arrangement [VALID_STARTS_WITH] uses.
         * `VelocityLossTest` asserts the two lists are equal, from the side
         * that can see both.
         */
        val VALID_VELOCITY_LOSS_BASES =
            setOf("measured", "notEnoughReps", "noReference", "terminalRepIsFastest")
    }
}

@Serializable
data class HrSessionSummary(
    val avgBpm: Int? = null,
    val maxBpm: Int? = null,
    /** Session-wide HRV (RMSSD, ms) from R-R intervals. */
    @SerialName("hrvRmssd_ms") val hrvRmssdMs: Double? = null,
)

@Serializable
data class ExerciseExport(
    val exercise: String,
    val sets: List<SetExport>,
)

@Serializable
data class SetExport(
    @SerialName("load_kg") val loadKg: Double,
    /** Same load in pounds, for readers who think in lb; kg remains canonical. */
    @SerialName("load_lb") val loadLb: Double? = null,
    @SerialName("plannedLoad_kg") val plannedLoadKg: Double? = null,
    val reps: Int,
    /** True when reps were entered or corrected manually rather than sensor-counted. */
    val repsManual: Boolean = false,
    val plannedReps: Int? = null,
    /** Actual hold/carry time for timed sets (planks, farmer's walks). */
    @SerialName("duration_s") val durationS: Int? = null,
    @SerialName("plannedDuration_s") val plannedDurationS: Int? = null,
    /** Unilateral sets: "left" or "right". */
    val side: String? = null,
    /** Lifter-reported RPE (6–10). */
    val rpe: Int? = null,
    /** True when the lifter marked the set as failed. Omitted when false. */
    val failed: Boolean = false,
    /** True for warm-up sets (no RPE recorded). Omitted when false. */
    val warmup: Boolean = false,
    /**
     * The prep prescribed before this set, and the prep that played, in whole
     * seconds.
     *
     * Whenever the two differ, the lifter adjusted the prep in the app; they
     * are equal both when no adjustment exists and when the adjustment happens
     * to equal what the plan prescribed. The difference is what lets the next
     * plan be authored from this document instead of re-guessed.
     *
     * [plannedPrepS] is present whenever the set played a prep, including where
     * the plan declared nothing: the app's default is still what was
     * prescribed, and a reader that saw only [prepS] could not tell an
     * adjustment from a declaration without knowing the app's constant.
     *
     * [restS] beside them is the one planned value in this type whose name does
     * not say it is planned, so a reader takes a prescription for an
     * observation. That is issue #76.
     *
     * Both absent on a set that played no prep -- such a set has none -- and
     * both absent on every set recorded before 1.11, and on every hold and
     * carry recorded before a prep reached them. 0 is a value, not an absence: it is
     * the prep in which nothing is spoken before the set begins, and the default
     * here is null precisely so that 0 survives `encodeDefaults = false`.
     */
    @SerialName("plannedPrep_s") val plannedPrepS: Int? = null,
    @SerialName("prep_s") val prepS: Int? = null,
    @SerialName("rest_s") val restS: Int? = null,
    val tempoPrescribed: String? = null,
    val tempoCompliance: TempoComplianceExport? = null,
    @SerialName("velocityLoss_pct") val velocityLossPct: Double? = null,
    /**
     * Which case [velocityLossPct] is in, drawn from
     * [SessionExport.VALID_VELOCITY_LOSS_BASES].
     *
     * Present whenever the sensor resolved any reps, including -- especially
     * -- when [velocityLossPct] itself is absent, so that a reader can tell a
     * figure that was WITHHELD from one an older app version simply never
     * wrote. Absent when no reps were resolved at all, the same condition
     * under which [repMetricsComplete] is absent: there is no rep list for it
     * to be a statement about.
     */
    val velocityLossBasis: String? = null,
    val hr: HrSetSummary? = null,
    /** Per-rep detail; included only when the user enables detailed export. */
    val repMetrics: List<RepMetricsExport>? = null,
    /** Spoken cues with epoch-ms stamps, cross-referenceable with the raw IMU stream (detailed export only). */
    val voiceCues: List<VoiceCue>? = null,
    /**
     * False when the sensor segmenter resolved a different number of reps than
     * the set records — the lifter or the voice guide counted something else.
     *
     * Stated without reference to [repMetrics], deliberately. Everything drawn
     * from the segmented reps carries this caveat — [velocityLossPct],
     * [tempoCompliance] and [summary] as much as the per-rep array — and those
     * three are published whether or not per-rep detail was asked for, so a
     * caveat that only appears alongside the array leaves the summary-only
     * reader holding the numbers without the warning.
     *
     * True is weaker than it looks and should not be read as an independent
     * check: when [repsManual] is false the stored rep count IS the segmenter's
     * count, so the two agree by construction. Only false carries information
     * the reader could not derive from [repsManual] alone.
     *
     * Null is a third state, not a synonym for false: the segmenter resolved no
     * reps at all, so there is no figure left to qualify.
     */
    val repMetricsComplete: Boolean? = null,
    /**
     * The direction and geometry this set's numbers were measured with.
     *
     * Absent means the set was recorded before the app stored it. Absent does
     * NOT mean vertical, drive-up, sensor-on-the-bar: a wrong declaration is
     * worse than no declaration, so nothing is defaulted in.
     */
    val geometry: GeometryExport? = null,
    /** Always-included summary across reps. */
    val summary: SetSummaryExport,
)

/**
 * How the lift moved and how the sensor was mounted, as the app resolved it for
 * this set — not as a plan declared it, because the app applies a precedence
 * chain and a plan's text may have been overridden.
 *
 * This is what makes the rest of the set checkable. [SetExport.tempoPrescribed]
 * is positional notation — digit 1 is the down stroke, digit 3 the up stroke —
 * so which stroke is the eccentric follows from [concentric] and [plane], not
 * from the digit order. Without those, [SetExport.tempoCompliance] is a verdict
 * whose input the reader cannot see.
 *
 * **Every field is required, and none has a Kotlin default.** The exporter
 * writes JSON with `encodeDefaults = false`, so a field defaulted to `false`
 * would be dropped from the wire and its absence would read as "not stated"
 * when it meant "stated false" — the exact defect this object exists to fix.
 * Contrast [SetExport.failed] and [SetExport.warmup], where false is the
 * unremarkable normal and omission reads correctly.
 */
@Serializable
data class GeometryExport(
    /** Which phase opened each rep: "eccentric" or "concentric". */
    val startsWith: String,
    /** Which way the driving phase moved: "up" or "down". */
    val concentric: String,
    /** The plane the LIFTER moved in: "vertical" or "horizontal". */
    val plane: String,
    /**
     * True when the sensor rode a cable weight stack. The stack travels
     * vertically however the lifter moves, so this overrides [plane] for the
     * axis that was actually measured.
     */
    val sensorOnStack: Boolean,
    /** True when the sensor moved opposite to the load the lifter drove. */
    val sensorInverted: Boolean,
    /**
     * Lifter-side travel per unit of sensor travel. Every velocity and range of
     * motion in this set is lifter-side, so on a 2:1 pulley they are twice what
     * the sensor saw.
     */
    val travelRatio: Double,
    /** How the movement is performed: "dynamic", "hold", "carry" or "explosive". */
    val kind: String,
    /** True when the lifter's own body was the load, so load_kg includes body weight. */
    val bodyweight: Boolean,
    /** Where each of the resolvable values came from. */
    val source: GeometrySourceExport,
)

/**
 * Declared, seeded, inferred or default, per value.
 *
 * Three of [GeometryExport]'s eight values are missing here on purpose:
 * `sensorOnStack`, `sensorInverted` and `bodyweight` are non-nullable booleans
 * in the plan format, so a declared `false` and an omitted key are the same
 * value and no source can be told apart. Stating one would be an invention.
 */
@Serializable
data class GeometrySourceExport(
    val startsWith: String,
    val concentric: String,
    val plane: String,
    val kind: String,
    val travelRatio: String,
)

@Serializable
data class SetSummaryExport(
    @SerialName("meanConVel_mps") val meanConVelMps: Double? = null,
    @SerialName("peakConVel_mps") val peakConVelMps: Double? = null,
    @SerialName("meanEcc_s") val meanEccS: Double? = null,
    @SerialName("meanCon_s") val meanConS: Double? = null,
    @SerialName("meanRom_m") val meanRomM: Double? = null,
    /**
     * How far the reps of this set disagree with each other about rom_m: the
     * population standard deviation as a percentage of [meanRomM]. Absent,
     * never 0, below two reps or when the reps average no displacement --
     * dispersion is undefined there. See SetAnalyzer.romSpreadPct.
     */
    @SerialName("romSpread_pct") val romSpreadPct: Double? = null,
    /** Best instantaneous concentric power across the set, watts. */
    @SerialName("peakPower_w") val peakPowerW: Double? = null,
    /** Mean of per-rep average concentric power, watts. */
    @SerialName("meanConPower_w") val meanConPowerW: Double? = null,
)

@Serializable
data class RepMetricsExport(
    /** Null when no eccentric was measurable — never 0, which would read as an instant phase. */
    @SerialName("ecc_s") val eccS: Double? = null,
    @SerialName("bottomPause_s") val bottomPauseS: Double,
    @SerialName("con_s") val conS: Double,
    @SerialName("topPause_s") val topPauseS: Double,
    /** Mean drive velocity, positive in the direction the drive moves. */
    @SerialName("meanConVel_mps") val meanConVelMps: Double,
    @SerialName("peakConVel_mps") val peakConVelMps: Double,
    @SerialName("meanEccVel_mps") val meanEccVelMps: Double? = null,
    @SerialName("rom_m") val romM: Double,
    @SerialName("peakPower_w") val peakPowerW: Double? = null,
    @SerialName("meanConPower_w") val meanConPowerW: Double? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class TempoComplianceExport(
    val prescribed: String,
    @SerialName("tolerance_s") val toleranceS: Double,
    /**
     * Reps within tolerance on every scored phase THAT REP RESOLVED, out of
     * [of], the reps that resolved at least one. Pauses are reported but
     * never scored. A phase the sensor did not measure is not counted against
     * the lifter and does not appear in [scoredPhases], so read that field to
     * know what this ratio covers: on a slow concentric-first lift it is
     * often the drive alone. `of: 0` means nothing was gradeable.
     */
    val withinTolerance: Int,
    val of: Int,
    /**
     * Which phases were scored — the movement digits only, and only those
     * actually measured. Always written, including empty: the exporter drops
     * defaults, and an absent key reads as "not stated" when it means
     * "nothing was graded".
     */
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val scoredPhases: List<String> = emptyList(),
    /** Prescribed eccentric:concentric contrast — what a tempo block actually trains. */
    val prescribedEccConRatio: Double? = null,
    val actualEccConRatio: Double? = null,
)

@Serializable
data class HrSetSummary(
    val endOfSetBpm: Int? = null,
    val avgBpm: Int? = null,
    val maxBpm: Int? = null,
    /**
     * The lowest bpm this set's trusted samples support -- the same
     * population [avgBpm] and [maxBpm] are drawn from, never the whole
     * stream. :core:model has no dependency on :core:data, so the reasoning
     * lives where the divergence is concrete: SessionExporter.setExport in
     * :core:data computes this one figure fresh from the set's raw HRM
     * stream at export time, while its three siblings here are read off
     * columns frozen at record time.
     */
    val minBpm: Int? = null,
)
