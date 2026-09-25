package com.macrophage.barspeed.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.jsonObject

/**
 * A set's targets and its rest as the export publishes them: the keys BOTH
 * export writers must carry, in one type (#157, #219).
 *
 * THREE LAYERS PER TARGET (export 1.23). PLANNED is what the plan prescribed,
 * frozen when the plan was flattened, and no in-app control moves it:
 * [plannedLoadKg], [plannedReps], [plannedDurationS], [plannedTempo] and
 * [restS]. WORKING is the target the set ran against, fixed at START:
 * [workingLoadKg], [workingReps], [workingDurationS] and [tempoPrescribed].
 * ACTUAL is on [SetExport] itself -- `load_kg`, `reps`, `duration_s`,
 * `tempoCompliance` -- after any rest-screen correction. [restMeasuredS] is
 * the rest two clock instants measured.
 *
 * A working figure below the planned one is a lowered target, and a set that
 * met it is COMPLETED: the owner, 2026-09-25, "It's completed even if the
 * target is lowered, just note the discrepancy." The two figures side by side
 * are the note; nothing here judges it.
 *
 * ONE TYPE, TWO WRITERS. `session.json` is serialised by kotlinx and the raw
 * archive's `meta.json` is assembled as text by a different function. Before
 * this type each writer listed these keys itself, and they drifted: #219 is
 * `plannedLoad_kg`, published in `session.json` and never in `meta.json`. Here
 * [SetExport] carries this object and the archive's writer encodes the same
 * object and splices its keys into its own set descriptor, so a key added here
 * reaches both documents or neither.
 *
 * FLAT ON THE WIRE. The object is a Kotlin grouping and not a published one:
 * [SetExportWireSerializer] lifts its keys into the set object, so the
 * published shape is the flat one every existing reader reads.
 *
 * NO KOTLIN DEFAULTS, deliberately. There is one construction site per build
 * of the export, and a defaulted parameter is one a call site can silently
 * stop passing. Null is a real value for every one of these and is written
 * out at the call site; the exporter's `explicitNulls = false` then drops the
 * key.
 */
@Serializable
data class SetPrescriptionExport(
    /**
     * The load the PLAN prescribed, frozen when the plan was flattened, on
     * `load_kg`'s scale. Null where the plan named no load for the set, and on
     * an added or ad-hoc set, which no plan prescribed.
     */
    @SerialName("plannedLoad_kg") val plannedLoadKg: Double?,
    /**
     * The load the set RAN AGAINST, resolved when it ended and before any
     * rest-screen correction (1.23). Every set recorded from database v20
     * carries it, because every set has a load, so its absence is what marks a
     * set whose working targets the recording build could not state. Where
     * `load_kg` differs from it, the load was corrected after the set: the
     * rest-screen correction is the only write that moves `load_kg` on a
     * stored row.
     */
    @SerialName("workingLoad_kg") val workingLoadKg: Double?,
    /**
     * The rep count the PLAN prescribed, frozen when the plan was flattened.
     * Null on an added set and on a set the plan gave no count. On an ad-hoc
     * set, which has no plan, the recorder writes the count the lifter typed.
     */
    val plannedReps: Int?,
    /**
     * The rep count the set RAN AGAINST, fixed at START (1.23): the plan's
     * unless the lifter changed it in the app. The short-set judgement reads
     * this figure (`SetShortfallPolicy`), so a met lowered target is
     * completed. Published even where it equals [plannedReps], so its absence
     * never means "as planned": it means no rep target, or a set recorded
     * before database v20.
     */
    val workingReps: Int?,
    /**
     * The hold or carry seconds the PLAN prescribed, frozen when the plan was
     * flattened. A set RECORDED by v0.1.43 or earlier stored the working
     * target here and still publishes it.
     */
    @SerialName("plannedDuration_s") val plannedDurationS: Int?,
    /**
     * The hold or carry seconds the set RAN AGAINST (1.23): the countdown ran
     * to this figure. Null on a set that is not timed and on every set
     * recorded before database v20.
     */
    @SerialName("workingDuration_s") val workingDurationS: Int?,
    /**
     * The rest PRESCRIBED after this set, in whole seconds -- never a
     * measurement of how long the lifter rested (#76).
     *
     * A MINIMUM (1.23). The owner, 2026-09-25: "I consider rests a minimum. If
     * it takes more time to setup I do." So only a [restMeasuredS] SHORTER
     * than this is a discrepancy. Null where the plan declared no rest -- the
     * countdown then ran the app's default -- and on an added set it is
     * carried over from the block, prescribing nothing for that set.
     *
     * From 1.19 the published description states which instant it is counted
     * FROM, and `RestClockPolicy` owns that instant: a release that decided a
     * hold's seconds first (#259), then the terminal cue on the set's own cue
     * track -- which from 1.22 includes a timed set's `Time` where the timed
     * voice is on (#295) and the `Set ended` a timed set the lifter stopped
     * says (#288) -- or the set's end instant where nothing called it over.
     * The countdown and the archive's `rest_before_hrm` window both begin
     * there (#178); until 1.19 the window began when the set's capture stopped
     * instead, up to 53.06 s later on one measured set. `rest_after_hrm` --
     * the window a session close writes onto the LAST set, when there is no
     * next set to carry `rest_before_hrm` forward -- follows the same instant
     * and the same copy-forward. A gap this does not close: `endSet` cancels
     * the in-set collector before the app enters its rest stage, so a
     * notification landing in that interval reaches neither capture -- 0.08
     * to 0.58 s on the one session measured.
     */
    @SerialName("rest_s") val restS: Int?,
    /**
     * The rest after this set as two clock instants measure it, seconds to the
     * nearest tenth (1.23): from the instant [restS]'s countdown starts at --
     * the stored `restStartedAtMs` -- to the START tap on the next set of the
     * session, as [RestMeasurePolicy.measuredS] computes it. Walking and setup
     * are inside it; the next set's prep, which runs after that tap, is not.
     * Only a figure SHORTER than [restS] is a discrepancy. Null on the last
     * set, on a set recorded before database v20, and where the two instants
     * are inverted.
     */
    @SerialName("restMeasured_s") val restMeasuredS: Double?,
    /**
     * The WORKING tempo: the one the set ran against and `tempoCompliance`
     * scores. The name is historical; [plannedTempo] is the plan's.
     */
    val tempoPrescribed: String?,
    /**
     * The tempo the PLAN declared, frozen when the plan was flattened (1.23,
     * #151). Where it differs from [tempoPrescribed] the lifter adjusted the
     * tempo in the app. Null where no plan declared one -- an ad-hoc set, an
     * added set, a timed set, a planned set with no tempo -- and on every set
     * recorded before database v20.
     */
    val plannedTempo: String?,
)

/**
 * The published shape of a set: [SetExport] with its [SetExport.prescription]
 * lifted into the set object, so the grouping is invisible on the wire.
 *
 * Applied where [ExerciseExport] lists its sets, through
 * [SetExportListSerializer], so every set the session document publishes
 * passes through it, and nothing else in the document is touched. Decoding
 * reverses it, so a document this writer produced reads back into the same
 * objects.
 *
 * WHERE THE KEYS LAND. Where the grouping sits among [SetExport]'s own
 * properties -- not where each key sat before the grouping existed. JSON
 * object order carries no meaning and no reader may depend on it.
 */
object SetExportWireSerializer : JsonTransformingSerializer<SetExport>(SetExport.serializer()) {
    private const val NESTED_KEY = "prescription"

    /** The published keys [SetPrescriptionExport] contributes to a set. */
    val prescriptionKeys: Set<String> = keysOf(SetPrescriptionExport.serializer())

    /**
     * Every key a published set may carry: [SetExport]'s own, without the
     * grouping, and [prescriptionKeys]. The set the published schema's
     * `$defs.set.properties` is compared with.
     */
    val wireKeys: Set<String> = keysOf(SetExport.serializer()) - NESTED_KEY + prescriptionKeys

    override fun transformSerialize(element: JsonElement): JsonElement {
        val obj = element.jsonObject
        val nested = obj[NESTED_KEY]?.jsonObject ?: return element
        return JsonObject(
            buildMap<String, JsonElement> {
                obj.forEach { (key, value) ->
                    if (key == NESTED_KEY) putAll(nested) else put(key, value)
                }
            },
        )
    }

    override fun transformDeserialize(element: JsonElement): JsonElement {
        val obj = element.jsonObject
        val nested = JsonObject(obj.filterKeys { it in prescriptionKeys })
        return JsonObject(obj.filterKeys { it !in prescriptionKeys } + (NESTED_KEY to nested))
    }

    private fun keysOf(serializer: KSerializer<*>): Set<String> =
        serializer.descriptor.let { d -> (0 until d.elementsCount).map(d::getElementName).toSet() }
}

/**
 * [ExerciseExport.sets]' serializer: a list of [SetExportWireSerializer]
 * sets. An object of its own because a property-level `@Serializable(with =)`
 * names a serializer class, and ktlint refuses the type-use annotation that
 * would otherwise sit inside `List<>`.
 */
object SetExportListSerializer : KSerializer<List<SetExport>> by ListSerializer(SetExportWireSerializer)
