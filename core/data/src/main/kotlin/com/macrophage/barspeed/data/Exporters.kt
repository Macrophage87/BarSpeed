package com.macrophage.barspeed.data

import com.macrophage.barspeed.dsp.ImuCsv
import com.macrophage.barspeed.dsp.VelocityEstimator
import com.macrophage.barspeed.model.ExerciseExport
import com.macrophage.barspeed.model.HrSessionSummary
import com.macrophage.barspeed.model.HrSetSummary
import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.RepMetricsExport
import com.macrophage.barspeed.model.SessionExport
import com.macrophage.barspeed.model.SetExport
import com.macrophage.barspeed.model.SetSummaryExport
import com.macrophage.barspeed.model.TempoComplianceExport
import com.macrophage.barspeed.model.WeightUnit
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Builds the LLM-facing session export JSON (docs/schemas/session-export.schema.json). */
@OptIn(ExperimentalSerializationApi::class)
class SessionExporter(
    private val sessionRepository: SessionRepository,
    private val json: Json =
        Json {
            prettyPrint = true
            encodeDefaults = false
            explicitNulls = false
        },
) {
    suspend fun buildExport(sessionId: Long, includeRepDetail: Boolean): SessionExport? {
        val session = sessionRepository.session(sessionId) ?: return null
        val sets = sessionRepository.sets(sessionId)

        val byExercise = sets.groupBy { it.exerciseId }
        val exercises =
            byExercise.map { (exerciseId, records) ->
                ExerciseExport(
                    exercise = exerciseId,
                    sets = records.map { record -> setExport(record, includeRepDetail) },
                )
            }
        return SessionExport(
            startedAt = Instant.ofEpochMilli(session.startedAtMs).toString(),
            endedAt = session.endedAtMs?.let { Instant.ofEpochMilli(it).toString() },
            planRef =
            listOfNotNull(session.planName, session.planSessionName)
                .takeIf { it.isNotEmpty() }?.joinToString(" / "),
            notes = session.notes,
            heartRate =
            if (session.hrAvgBpm != null || session.hrMaxBpm != null || session.hrvRmssdMs != null) {
                HrSessionSummary(
                    avgBpm = session.hrAvgBpm,
                    maxBpm = session.hrMaxBpm,
                    hrvRmssdMs = session.hrvRmssdMs?.let { Math.round(it * 10.0) / 10.0 },
                )
            } else {
                null
            },
            exercises = exercises,
        )
    }

    suspend fun exportJson(sessionId: Long, includeRepDetail: Boolean): String? =
        buildExport(sessionId, includeRepDetail)?.let { json.encodeToString(SessionExport.serializer(), it) }

    private suspend fun setExport(record: SetRecordEntity, includeRepDetail: Boolean): SetExport {
        val analysis = sessionRepository.decodeAnalysis(record)
        val reps = analysis?.reps.orEmpty()
        val voiceCues =
            if (includeRepDetail) {
                sessionRepository.rawStreams(record.id)
                    .firstOrNull { it.kind == RawStreamEntity.KIND_CUES }
                    ?.let { CueCsv.decode(Gzip.decompress(it.csvGzip)) }
                    ?.takeIf { it.isNotEmpty() }
            } else {
                null
            }
        return SetExport(
            loadKg = record.loadKg,
            loadLb = Math.round(record.loadKg * WeightUnit.LB_PER_KG * 10.0) / 10.0,
            plannedLoadKg = record.plannedLoadKg,
            reps = record.actualReps,
            repsManual = record.repsManual,
            plannedReps = record.plannedReps,
            durationS = record.actualDurationS,
            plannedDurationS = record.plannedDurationS,
            side = record.side,
            rpe = record.rpe,
            failed = record.failed,
            warmup = record.warmup,
            restS = record.plannedRestS,
            tempoPrescribed = record.tempo,
            tempoCompliance =
            analysis?.tempoCompliance?.let {
                TempoComplianceExport(
                    prescribed = it.prescribed.notation(),
                    toleranceS = it.toleranceS,
                    withinTolerance = it.repsFullyCompliant,
                    of = it.repsEvaluated,
                    scoredPhases = it.phases.filter { p -> p.scored }.map { p -> p.phase },
                    prescribedEccConRatio = it.prescribedEccConRatio,
                    actualEccConRatio = it.actualEccConRatio,
                )
            },
            velocityLossPct = analysis?.velocityLossPct,
            hr =
            if (record.hrEndOfSetBpm != null || record.hrAvgBpm != null || record.hrMaxBpm != null) {
                HrSetSummary(record.hrEndOfSetBpm, record.hrAvgBpm, record.hrMaxBpm)
            } else {
                null
            },
            voiceCues = voiceCues,
            // The stored rep count comes from the lifter or the voice guide; the
            // sensor segmenter is a separate opinion. Say so when they disagree
            // rather than letting a short per-rep array look like the whole set.
            //
            // Gated on the reps, never on the export mode. The three figures
            // this qualifies -- velocityLoss_pct, tempoCompliance and summary --
            // are published in both artifacts and all three are computed from
            // this same list, so a caveat keyed on whether per-rep detail was
            // requested leaves the summary-only reader holding the numbers with
            // the warning removed.
            repMetricsComplete = if (reps.isNotEmpty()) reps.size == record.actualReps else null,
            repMetrics =
            if (includeRepDetail && reps.isNotEmpty()) {
                reps.map {
                    RepMetricsExport(
                        eccS = it.eccS,
                        bottomPauseS = it.bottomPauseS,
                        conS = it.conS,
                        topPauseS = it.topPauseS,
                        meanConVelMps = it.meanConVelMps,
                        peakConVelMps = it.peakConVelMps,
                        meanEccVelMps = it.meanEccVelMps,
                        romM = it.romM,
                        peakPowerW = it.peakPowerW,
                        meanConPowerW = it.meanConPowerW,
                    )
                }
            } else {
                null
            },
            summary =
            SetSummaryExport(
                meanConVelMps = reps.map { it.meanConVelMps }.averageOrNull()?.round3(),
                peakConVelMps = reps.maxOfOrNull { it.peakConVelMps },
                meanEccS = reps.mapNotNull { it.eccS }.averageOrNull()?.round2(),
                meanConS = reps.map { it.conS }.averageOrNull()?.round2(),
                meanRomM = reps.map { it.romM }.averageOrNull()?.round3(),
                peakPowerW = reps.mapNotNull { it.peakPowerW }.maxOrNull(),
                meanConPowerW = reps.mapNotNull { it.meanConPowerW }.averageOrNull()?.round1(),
            ),
        )
    }

    private fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else average()

    private fun Double.round1(): Double = Math.round(this * 10.0) / 10.0

    private fun Double.round2(): Double = Math.round(this * 100.0) / 100.0

    private fun Double.round3(): Double = Math.round(this * 1000.0) / 1000.0
}

/**
 * Builds the raw-data zip: per-set CSVs (device-frame IMU + HRM), the FULL
 * detailed session analysis (session.json — everything the JSON export has,
 * including per-rep velocity/power, tempo compliance, RPE, sides, durations,
 * and HRV), and a meta.json manifest describing every file (spec 4.3).
 */
class RawExporter(
    private val sessionRepository: SessionRepository,
    private val sessionExporter: SessionExporter,
    private val appVersion: String,
) {
    suspend fun buildZip(sessionId: Long): ByteArray? {
        val session = sessionRepository.session(sessionId) ?: return null
        val sets = sessionRepository.sets(sessionId)
        val sessionJson = sessionExporter.exportJson(sessionId, includeRepDetail = true)
        val out = ByteArrayOutputStream()
        val meta = StringBuilder()
        meta.append("{\n  \"epoch\": \"${Instant.ofEpochMilli(session.startedAtMs)}\",\n")
        meta.append("  \"appVersion\": \"$appVersion\",\n  \"sensorModel\": \"WitMotion WT901BLECL\",\n")
        meta.append("  \"analysisFile\": \"session.json\",\n")
        meta.append("  \"csvHeaderImu\": \"${ImuCsv.HEADER}\",\n  \"csvHeaderHrm\": \"${HrCsv.HEADER}\",\n")
        meta.append("  \"csvHeaderCues\": \"${CueCsv.HEADER}\",\n")
        meta.append("  \"sets\": [\n")

        ZipOutputStream(out).use { zip ->
            val setLines = mutableListOf<String>()
            for ((idx, record) in sets.withIndex()) {
                val streams = sessionRepository.rawStreams(record.id)
                val files = mutableListOf<String>()
                for (stream in streams) {
                    val name = "set%02d_%s_%s.csv".format(idx + 1, record.exerciseId, stream.kind)
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(Gzip.decompress(stream.csvGzip).toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                    files += name
                }
                // The raw zip has to stand on its own: an analysis that opens
                // only the CSVs must still be able to tell left from right, a
                // warm-up from a working set, and which sets were rotating
                // enough for attitude error to matter.
                setLines += buildSetDescriptor(idx, record, streams, files)
            }
            meta.append(setLines.joinToString(",\n")).append("\n  ]\n}\n")
            zip.putNextEntry(ZipEntry("meta.json"))
            zip.write(meta.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            sessionJson?.let {
                zip.putNextEntry(ZipEntry("session.json"))
                zip.write(it.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun buildSetDescriptor(
        idx: Int,
        record: SetRecordEntity,
        streams: List<RawStreamEntity>,
        files: List<String>,
    ): String {
        val fields = mutableListOf<String>()
        fun num(key: String, value: Any?) = value?.let { fields += "\"$key\": $it" }
        fun str(key: String, value: String?) = value?.let { fields += "\"$key\": \"${it.replace("\"", "'")}\"" }
        fun flag(key: String, value: Boolean) = if (value) fields += "\"$key\": true" else Unit

        num("set", idx + 1)
        str("exercise", record.exerciseId)
        num("load_kg", record.loadKg)
        num("load_lb", Math.round(record.loadKg * WeightUnit.LB_PER_KG * 10.0) / 10.0)
        num("reps", record.actualReps)
        num("plannedReps", record.plannedReps)
        num("duration_s", record.actualDurationS)
        str("side", record.side)
        num("rpe", record.rpe)
        flag("failed", record.failed)
        flag("warmup", record.warmup)
        flag("repsManual", record.repsManual)
        str("tempoPrescribed", record.tempo)
        num("startedAt_ms", record.startedAtMs)
        num("endedAt_ms", record.endedAtMs)
        // Decoded once and shared. Two figures below are read off the same
        // stream, and decoding it twice would gzip-inflate and parse a set's
        // whole IMU capture a second time for no gain.
        val samples = imuSamples(streams)
        // Measured from the stream this key describes, not read off the row.
        //
        // What this states is the mean rate at which the rows in that file
        // ARRIVED -- (n-1) intervals over the span of their timestamps -- and
        // not the rate the sensor sampled at. The two are equal only if nothing
        // was dropped, and a dropout cannot be seen from the stream.
        //
        // Reading it off the row published a zero for every set the segmenter
        // never analysed, because those sets store a placeholder analysis while
        // the capture runs regardless. Deriving it here costs a set the DSP did
        // measure nothing at all: the stored figure came from this same
        // arithmetic over these same samples, and the CSV round trip preserves
        // both terms exactly.
        //
        // The stored value survives only as a fallback for a stream that will
        // not parse, and only when it is itself positive. Where neither can
        // state a rate the key is omitted: this manifest expresses every other
        // unknown by omission, and a rate is a number the reader divides by.
        val measuredRate =
            samples?.let {
                VelocityEstimator.measuredSampleRateOrNull(
                    it.size,
                    (it.last().timestampMs - it.first().timestampMs) / 1000.0,
                )
            }
        val storedRate = streams.firstOrNull { it.kind == RawStreamEntity.KIND_IMU }?.sampleRateHz
        num("sampleRate_hz", (measuredRate ?: storedRate)?.takeIf { it > 0.0 })
        // Attitude excursion decides which analysis is even valid on this set:
        // a rail-guided machine barely rotates and integrates cleanly, while a
        // barbell tumbling through 300 degrees leaks gravity into every sample.
        rollExcursionDeg(samples)?.let { num("rollExcursion_deg", Math.round(it * 10.0) / 10.0) }
        fields += "\"files\": [${files.joinToString(", ") { "\"$it\"" }}]"
        return "    {${fields.joinToString(", ")}}"
    }

    /**
     * This set's IMU capture, or null when there is nothing readable to work
     * from -- no stream, a stream that will not decode, or a stream with no
     * data rows.
     *
     * The decode stays inside `runCatching`: gzip inflating is not the only way
     * this fails. [ImuCsv.decode] parses each row and throws on a malformed
     * one, and a manifest is not worth failing an entire export over.
     */
    private fun imuSamples(streams: List<RawStreamEntity>): List<ImuSample>? {
        val imu = streams.firstOrNull { it.kind == RawStreamEntity.KIND_IMU } ?: return null
        val samples = runCatching { ImuCsv.decode(Gzip.decompress(imu.csvGzip)) }.getOrNull() ?: return null
        return samples.ifEmpty { null }
    }

    /**
     * How far the sensor's roll swept across the set, or null when the stream
     * cannot say.
     *
     * Two samples minimum. One sample has a maximum equal to its minimum, so
     * the range comes out 0.0 and the manifest would state that the set did not
     * rotate -- when what happened is that nothing measured whether it did.
     * This figure decides whether a reader trusts the integration on a set at
     * all, and a fabricated zero is the most reassuring answer available.
     */
    private fun rollExcursionDeg(samples: List<ImuSample>?): Double? {
        if (samples == null || samples.size < 2) return null
        val rolls = samples.map { it.rollDeg }
        return rolls.max() - rolls.min()
    }
}
