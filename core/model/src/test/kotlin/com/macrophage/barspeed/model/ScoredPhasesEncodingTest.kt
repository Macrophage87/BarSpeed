package com.macrophage.barspeed.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `tempoCompliance.scoredPhases` is written when it is empty (#66 (b)).
 *
 * The exporter drops defaults and nulls, and an absent `scoredPhases` reads
 * as "not stated" where an empty one means "nothing was graded". One
 * annotation, `@EncodeDefault(ALWAYS)` on the field, is all that keeps the
 * empty list in the document, and until this file nothing pinned it.
 *
 * This is the encoding under the exporter's own configuration, byte-compared,
 * one module upstream of the exporter; `SessionExportScoredPhasesBytesTest`
 * in `:core:data` pins the same bytes on real `SessionExporter` output. A
 * pin, green when written.
 */
@OptIn(ExperimentalSerializationApi::class)
class ScoredPhasesEncodingTest {
    /** `SessionExporter`'s configuration, less `prettyPrint`, so the bytes fit on one line. */
    private val exporting = Json {
        encodeDefaults = false
        explicitNulls = false
    }

    @Test
    fun `an empty scoredPhases is written, byte for byte, and every unset optional is not`() {
        val nothingGraded = TempoComplianceExport(prescribed = "3010", toleranceS = 0.5, withinTolerance = 0, of = 0)
        assertEquals(
            """{"prescribed":"3010","tolerance_s":0.5,"withinTolerance":0,"of":0,"scoredPhases":[]}""",
            exporting.encodeToString(TempoComplianceExport.serializer(), nothingGraded),
        )
    }
}
