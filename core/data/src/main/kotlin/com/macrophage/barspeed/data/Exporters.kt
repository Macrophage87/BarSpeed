package com.macrophage.barspeed.data

import com.macrophage.barspeed.dsp.ImuCsv
import com.macrophage.barspeed.model.ExerciseExport
import com.macrophage.barspeed.model.HrSessionSummary
import com.macrophage.barspeed.model.HrSetSummary
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
            if (session.hrAvgBpm != null || session.hrMaxBpm != null) {
                HrSessionSummary(avgBpm = session.hrAvgBpm, maxBpm = session.hrMaxBpm)
            } else {
                null
            },
            exercises = exercises,
        )
    }

    suspend fun exportJson(sessionId: Long, includeRepDetail: Boolean): String? =
        buildExport(sessionId, includeRepDetail)?.let { json.encodeToString(SessionExport.serializer(), it) }

    private fun setExport(record: SetRecordEntity, includeRepDetail: Boolean): SetExport {
        val analysis = sessionRepository.decodeAnalysis(record)
        val reps = analysis?.reps.orEmpty()
        return SetExport(
            loadKg = record.loadKg,
            loadLb = Math.round(record.loadKg * WeightUnit.LB_PER_KG * 10.0) / 10.0,
            plannedLoadKg = record.plannedLoadKg,
            reps = record.actualReps,
            plannedReps = record.plannedReps,
            restS = record.plannedRestS,
            tempoPrescribed = record.tempo,
            tempoCompliance =
            analysis?.tempoCompliance?.let {
                TempoComplianceExport(
                    prescribed = it.prescribed.notation(),
                    toleranceS = it.toleranceS,
                    withinTolerance = it.repsFullyCompliant,
                    of = it.repsEvaluated,
                )
            },
            velocityLossPct = analysis?.velocityLossPct,
            hr =
            if (record.hrEndOfSetBpm != null || record.hrAvgBpm != null || record.hrMaxBpm != null) {
                HrSetSummary(record.hrEndOfSetBpm, record.hrAvgBpm, record.hrMaxBpm)
            } else {
                null
            },
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
                    )
                }
            } else {
                null
            },
            summary =
            SetSummaryExport(
                meanConVelMps = reps.map { it.meanConVelMps }.averageOrNull()?.round3(),
                peakConVelMps = reps.maxOfOrNull { it.peakConVelMps },
                meanEccS = reps.map { it.eccS }.averageOrNull()?.round2(),
                meanConS = reps.map { it.conS }.averageOrNull()?.round2(),
                meanRomM = reps.map { it.romM }.averageOrNull()?.round3(),
            ),
        )
    }

    private fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else average()

    private fun Double.round2(): Double = Math.round(this * 100.0) / 100.0

    private fun Double.round3(): Double = Math.round(this * 1000.0) / 1000.0
}

/**
 * Builds the raw-data zip: per-set CSVs (device-frame IMU + HRM) with a
 * meta.json sidecar describing every file (spec 4.3).
 */
class RawExporter(
    private val sessionRepository: SessionRepository,
    private val appVersion: String,
) {
    suspend fun buildZip(sessionId: Long): ByteArray? {
        val session = sessionRepository.session(sessionId) ?: return null
        val sets = sessionRepository.sets(sessionId)
        val out = ByteArrayOutputStream()
        val meta = StringBuilder()
        meta.append("{\n  \"epoch\": \"${Instant.ofEpochMilli(session.startedAtMs)}\",\n")
        meta.append("  \"appVersion\": \"$appVersion\",\n  \"sensorModel\": \"WitMotion WT901BLECL\",\n")
        meta.append("  \"csvHeaderImu\": \"${ImuCsv.HEADER}\",\n  \"csvHeaderHrm\": \"${HrCsv.HEADER}\",\n")
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
                setLines +=
                    "    {\"set\": ${idx + 1}, \"exercise\": \"${record.exerciseId}\", " +
                    "\"load_kg\": ${record.loadKg}, \"reps\": ${record.actualReps}, " +
                    "\"sampleRate_hz\": ${streams.firstOrNull { it.kind == "imu" }?.sampleRateHz}, " +
                    "\"files\": [${files.joinToString(", ") { "\"$it\"" }}]}"
            }
            meta.append(setLines.joinToString(",\n")).append("\n  ]\n}\n")
            zip.putNextEntry(ZipEntry("meta.json"))
            zip.write(meta.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return out.toByteArray()
    }
}
