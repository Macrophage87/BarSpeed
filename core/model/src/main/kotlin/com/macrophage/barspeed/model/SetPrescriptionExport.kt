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
    @SerialName("plannedLoad_kg") val plannedLoadKg: Double?,
    val plannedReps: Int?,
    @SerialName("plannedDuration_s") val plannedDurationS: Int?,
    /**
     * The rest PRESCRIBED after this set, in whole seconds -- never a
     * measurement of how long the lifter rested (#76).
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
    val tempoPrescribed: String?,
)

/**
 * The published shape of a set: [SetExport] with its [SetExport.prescription]
 * lifted into the set object, so the grouping is invisible on the wire.
 *
 * Applied where [ExerciseExport] lists its sets, through
 * [SetExportListSerializer], so every set the session document publishes
 * passes through it, and nothing else in the document is touched. Decoding reverses it, so a document this writer produced reads back
 * into the same objects.
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
