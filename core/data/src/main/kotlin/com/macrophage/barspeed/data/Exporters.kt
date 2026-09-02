package com.macrophage.barspeed.data

import com.macrophage.barspeed.dsp.ImuCsv
import com.macrophage.barspeed.dsp.SetAnalyzer
import com.macrophage.barspeed.dsp.VelocityEstimator
import com.macrophage.barspeed.dsp.VelocityLoss
import com.macrophage.barspeed.hrm.HrTrust
import com.macrophage.barspeed.model.AbandonedSetPolicy
import com.macrophage.barspeed.model.ArmedSilencePolicy
import com.macrophage.barspeed.model.ExerciseExport
import com.macrophage.barspeed.model.FailureProvenancePolicy
import com.macrophage.barspeed.model.GeometryExport
import com.macrophage.barspeed.model.GeometrySourceExport
import com.macrophage.barspeed.model.HrSessionSummary
import com.macrophage.barspeed.model.HrSetSummary
import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.PrepWindow
import com.macrophage.barspeed.model.RecordedTimeZone
import com.macrophage.barspeed.model.RepMetricsExport
import com.macrophage.barspeed.model.ResolvedGeometry
import com.macrophage.barspeed.model.SensorCapturePolicy
import com.macrophage.barspeed.model.SessionExport
import com.macrophage.barspeed.model.SetExport
import com.macrophage.barspeed.model.SetLimiter
import com.macrophage.barspeed.model.SetSensorsExport
import com.macrophage.barspeed.model.SetSummaryExport
import com.macrophage.barspeed.model.TempoComplianceExport
import com.macrophage.barspeed.model.WarmupMarkPolicy
import com.macrophage.barspeed.model.WeightUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds the LLM-facing session export JSON (docs/schemas/session-export.schema.json).
 *
 * [dispatcher] is where issue #29 lives: neither this class nor [RawExporter]
 * used to switch dispatcher at all, so every caller ran the whole of this
 * work -- CSV decode, gzip inflate, JSON serialization -- on whatever context
 * it happened to be launched from, which for every production caller is
 * `viewModelScope`'s `Dispatchers.Main.immediate`. Wrapping here, not at each
 * call site, is deliberate: that is the exact shape that produced #29 in the
 * first place, where one call site (`SessionDetailViewModel.savePendingTo`)
 * remembered to wrap and four others did not. `Default`, not `IO`: this work
 * is CPU-bound, and Room's own suspend DAO calls dispatch on their own
 * executor regardless of what this wraps them in, so the choice here decides
 * only what thread the codec work runs on.
 */
@OptIn(ExperimentalSerializationApi::class)
class SessionExporter(
    private val sessionRepository: SessionRepository,
    private val json: Json =
        Json {
            prettyPrint = true
            encodeDefaults = false
            explicitNulls = false
        },
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    /**
     * [minBpmOverride] is the fold for issue #29's double decompression, not
     * a general-purpose cache: when a set's id is a key in this map, its
     * value -- possibly null, meaning computed and nothing was trusted -- is
     * used as-is instead of this class inflating that set's own HRM stream
     * itself. [RawExporter] is the only caller that supplies it, because it
     * has already inflated that same stream once to write the zip's raw CSV
     * entry, and a second inflate here would buy nothing. The standalone
     * `exportJson`/`buildExport` path -- summary and detailed JSON shared
     * from the session-detail screen, with no zip involved -- passes none,
     * and every set falls back to inflating its own stream, unchanged from
     * before this parameter existed.
     */
    suspend fun buildExport(
        sessionId: Long,
        includeRepDetail: Boolean,
        minBpmOverride: Map<Long, Int?> = emptyMap(),
    ): SessionExport? = withContext(dispatcher) {
        val session = sessionRepository.session(sessionId) ?: return@withContext null
        val sets = sessionRepository.sets(sessionId)

        val byExercise = sets.groupBy { it.exerciseId }
        val exercises =
            byExercise.map { (exerciseId, records) ->
                ExerciseExport(
                    exercise = exerciseId,
                    sets = records.map { record -> setExport(record, includeRepDetail, minBpmOverride) },
                )
            }
        SessionExport(
            startedAt = Instant.ofEpochMilli(session.startedAtMs).toString(),
            endedAt = session.endedAtMs?.let { Instant.ofEpochMilli(it).toString() },
            // The two instants above are UTC and stay that way; this is what
            // makes them readable as a time of day. Taken from the row rather
            // than from ZoneId.systemDefault(): the exporting device's zone
            // now is not evidence of the zone the session was recorded in, and
            // a session recorded before these columns existed has no answer.
            timeZone = RecordedTimeZone.of(session.zoneId, session.utcOffsetMinutes),
            planRef =
            listOfNotNull(session.planName, session.planSessionName)
                .takeIf { it.isNotEmpty() }?.joinToString(" / "),
            notes = session.notes,
            // Copied from the row and gated by nothing (#159). It is the
            // lifter's own statement about the whole session, so unlike every
            // heart-rate figure below it there is no population it could
            // outlive: it is not aggregated from the set rows and withholding
            // it with them would delete an answer that was given. Null when
            // the lifter skipped the rating or when the session predates it,
            // and `explicitNulls = false` drops the key rather than writing a
            // number nobody said.
            sessionRpe = session.sessionRpe,
            // The session block is aggregated from the set rows, so it must not
            // outlive them. When a session HAS sets and not one of them can
            // still say anything about heart rate, its frozen columns were
            // aggregated from figures this export no longer publishes, and
            // publishing them would leave the session asserting what every one
            // of its sets has withdrawn. That is the retroactive half of issue
            // #83: for sessions already recorded, these columns are the only
            // place the unworn figures survive.
            //
            // THE PAIR IS RIGHT FOR TWO OF THE THREE FIGURES AND WRONG FOR THE
            // THIRD. avgBpm and maxBpm ARE aggregated from the set rows, so
            // withholding them with the rows is the same quantity. hrvRmssdMs
            // is not: `RecordViewModel` accumulates its input across READY,
            // IN_SET and RESTING, while this gate reads only the per-set IN_SET
            // streams. A session whose in-set streams are all silenced but
            // whose rest windows were sound loses a figure this gate never
            // looked at. No capture held here separates the two populations --
            // a strap on a table is on a table for the rests too -- so the
            // outcome is right on the one session that fires it, for a reason
            // narrower than the gate states.
            heartRate =
            if (exercises.isNotEmpty() && exercises.all { it.sets.all { set -> set.hr == null } }) {
                null
            } else if (session.hrAvgBpm != null || session.hrMaxBpm != null || session.hrvRmssdMs != null) {
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

    suspend fun exportJson(
        sessionId: Long,
        includeRepDetail: Boolean,
        minBpmOverride: Map<Long, Int?> = emptyMap(),
    ): String? = withContext(dispatcher) {
        buildExport(sessionId, includeRepDetail, minBpmOverride)
            ?.let { json.encodeToString(SessionExport.serializer(), it) }
    }

    private suspend fun setExport(
        record: SetRecordEntity,
        includeRepDetail: Boolean,
        minBpmOverride: Map<Long, Int?>,
    ): SetExport {
        val analysis = sessionRepository.decodeAnalysis(record)
        val reps = analysis?.reps.orEmpty()
        // Re-asked of the stored REPS, never read from the stored scalar.
        // analysis.velocityLossPct is frozen into analysisJson when the set is
        // recorded, so every set already on disk carries a figure computed
        // under whatever rule was in force then -- including the 0.0 this
        // withholds. Reading that column would leave the whole history
        // republishing it on every export. The judgement is a pure function of
        // the rep list the row already holds, which is what makes it
        // answerable here at all; the hr block below re-asks its own question
        // of the stored raw stream for the same reason (issue #83, schema 1.9).
        val velocityLoss = VelocityLoss.of(reps)
        // Cues are still fetched here even when RawExporter already fetched
        // this same set's streams for the zip entries -- named, not fixed:
        // RawExporter's own inflate is for the raw CSV text, this one for
        // parsed VoiceCue objects, and threading that through too was judged
        // more machinery than issue #29 asked for. minBpm is the one this
        // issue named, and that one no longer inflates a second time.
        val streams = sessionRepository.rawStreams(record.id)
        val voiceCues =
            if (includeRepDetail) {
                streams.firstOrNull { it.kind == RawStreamEntity.KIND_CUES }
                    ?.let { CueCsv.decode(Gzip.decompress(it.csvGzip)) }
                    ?.takeIf { it.isNotEmpty() }
            } else {
                null
            }
        // The instants a rep was COUNTED, issue #158, from the same fetch the
        // cue track came out of.
        //
        // Detail-gated on the same terms as voiceCues: both are per-set event
        // lists on the raw clock, read against the IMU stream rather than
        // read in prose, and the summary export exists to be short.
        //
        // takeIf isNotEmpty is what keeps absence a single state. A stream
        // holding nothing but its header would otherwise publish an empty
        // array, which is a CLAIM -- that the app counted, and counted
        // nothing -- and false on the ordinary sensor-counted set, which
        // never counts out loud. runCatching for the decode: a mark is the
        // least of what this document carries and a stream that will not
        // parse is not worth failing an export over, the same shape the HRM
        // decode below uses.
        val repMarks =
            if (includeRepDetail) {
                streams.firstOrNull { it.kind == RawStreamEntity.KIND_REPS }
                    ?.let { stream -> runCatching { RepMarkCsv.decode(Gzip.decompress(stream.csvGzip)) }.getOrNull() }
                    ?.takeIf { it.isNotEmpty() }
            } else {
                null
            }
        // Issue #83 reaches ALREADY-RECORDED sets here, not only new ones.
        // endOfSetBpm, avgBpm and maxBpm are frozen columns written when the
        // set was recorded; gating only the record path would leave every set
        // already on disk exporting them unchanged. So the stored stream is
        // asked the same question here.
        //
        // Decoded ONCE and both answers taken from it. minBpm used to inflate
        // this stream on its own; adding a second inflate for the verdict
        // would have reintroduced exactly the double decompression issue #29
        // removed, in the commit whose KDoc above records that it was removed.
        val hrSamples =
            streams.firstOrNull { it.kind == RawStreamEntity.KIND_HRM }
                ?.let { stream -> runCatching { HrCsv.decode(Gzip.decompress(stream.csvGzip)) }.getOrNull() }
        val streamTracksAHeart = hrSamples?.let { HrTrust.tracksAHeart(it) }
        val minBpm =
            if (record.id in minBpmOverride) {
                minBpmOverride.getValue(record.id)
            } else {
                hrSamples?.let { HrTrust.summarize(it).minBpm }
            }
        // What this set may publish about its own duration and its own prep,
        // which depends on whether its work phase ever began (#216). One call
        // for all three answers, so this writer cannot take one of them and
        // leave another behind -- and the same call in buildSetDescriptor, so
        // the two documents cannot disagree.
        val phase = AbandonedSetPolicy.published(record.workBegan, record.actualDurationS, record.prepS)
        return SetExport(
            loadKg = record.loadKg,
            loadLb = Math.round(record.loadKg * WeightUnit.LB_PER_KG * 10.0) / 10.0,
            plannedLoadKg = record.plannedLoadKg,
            reps = record.actualReps,
            repsManual = record.repsManual,
            plannedReps = record.plannedReps,
            durationS = phase.durationS,
            abandonedInPrep = phase.abandonedInPrep,
            plannedDurationS = record.plannedDurationS,
            side = record.side,
            plannedSide = record.plannedSide,
            rpe = record.rpe,
            failed = record.failed,
            // Whose verdict the failure is (#216). Never beside a set that did
            // not fail: a `false` there would read as a derived failure that
            // never happened.
            failedByLifter = FailureProvenancePolicy.published(record.failed, record.failedByLifter),
            // Why it ended, and the words that go with `other` (#189). Both,
            // or the note is orphaned from the answer it belongs to.
            limiter = record.publishedLimiter,
            limiterNote = record.publishedLimiterNote,
            // The plan's declaration and the lifter's mark, composed (#194).
            // WarmupMarkPolicy owns which wins; reading either column raw here
            // would put that rule in a second place and let the two writers
            // disagree about the same set.
            warmup = WarmupMarkPolicy.effective(record.warmup, record.warmupMark),
            warmupByLifter = WarmupMarkPolicy.markedByLifter(record.warmupMark),
            added = record.added,
            plannedPrepS = record.plannedPrepS,
            prepS = phase.prepS,
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
            velocityLossPct = velocityLoss.pctOrNull,
            velocityLossBasis = velocityLoss.basis.takeIf { reps.isNotEmpty() },
            // minBpm in this list is load-bearing, not defensive padding: it is
            // computed fresh, below, from this set's raw stream, while the
            // other three are read off columns frozen at record time. Those two
            // sources can disagree about whether there is anything to report at
            // all -- an old session predating minBpm's existence has no way for
            // its frozen columns to predict it -- so this cannot assume all
            // four move together, and listOfNotNull is what lets a fourth
            // condition join the other three without tripping detekt's
            // ComplexCondition on a fourth `||`.
            hr =
            if (
                streamTracksAHeart != false &&
                listOfNotNull(record.hrEndOfSetBpm, record.hrAvgBpm, record.hrMaxBpm, minBpm).isNotEmpty()
            ) {
                HrSetSummary(record.hrEndOfSetBpm, record.hrAvgBpm, record.hrMaxBpm, minBpm)
            } else {
                null
            },
            voiceCues = voiceCues,
            repMarks = repMarks,
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
            // The input the tempo verdict above was reached from. Published
            // because tempoCompliance is not checkable without it: the digits
            // are positional, so which stroke is the eccentric follows from the
            // drive direction and the plane rather than from the digit order.
            //
            // Absent when the row carries none, and never defaulted in. Every
            // set recorded before this column existed is permanently in that
            // state, and a fabricated "vertical, drive up, sensor on the bar"
            // would read identically to a squat that really was measured so.
            geometry = sessionRepository.decodeGeometry(record)?.let(::geometryExport),
            // How many accelerometers this set was armed with (#156).
            //
            // Read off the ROW and the raw-stream ROWS, both already fetched
            // above -- no stream is inflated for it, so the standalone share
            // path costs exactly what it cost before. `present` is the one
            // observation in the block: which armed roles actually produced a
            // row. Everything else is what the set declared when it began.
            //
            // Absent on the ordinary one-sensor set, which is what keeps such
            // an export what earlier versions wrote. Not gated on
            // includeRepDetail: how many sensors a set was recorded with
            // qualifies every figure the summary publishes, and a caveat that
            // appears only in the detailed export leaves the summary-only
            // reader holding the numbers with the warning removed.
            sensors = sensorsExport(record, streams),
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
                romSpreadPct = SetAnalyzer.romSpreadPct(reps),
                peakPowerW = reps.mapNotNull { it.peakPowerW }.maxOrNull(),
                meanConPowerW = reps.mapNotNull { it.meanConPowerW }.averageOrNull()?.round1(),
            ),
        )
    }

    /**
     * A set's lowest trusted bpm, read from its own raw HRM stream rather
     * than the stored row -- the only way this figure can exist for a
     * session recorded before it did. [SetRecordEntity.hrEndOfSetBpm],
     * hrAvgBpm and hrMaxBpm are frozen at whatever HrTrust.isTrusted meant
     * when SessionRepository.recordSet ran; this is computed under whatever
     * it means right now, at export time. That is a real divergence if
     * isTrusted is ever changed later, and it is also exactly what buys
     * retroactive coverage: an old session gets a minBpm computed under
     * today's rule from data it already has, rather than one frozen under a
     * rule that predates the concept of minBpm entirely. [setExport]'s hr
     * gate cannot assume this figure and its three siblings move together,
     * because they are answered by two different clocks.
     *
     * Reached only when [setExport] has no [minBpmOverride] entry for this
     * set -- the standalone `exportJson`/`buildExport` path, summary and
     * detailed JSON with no zip involved. [RawExporter] never reaches this
     * function: it inflates the same HRM stream once, to write the zip's raw
     * CSV entry, and supplies the result through the override instead of
     * asking this class to inflate it again. A malformed or absent stream
     * yields null rather than failing the export, the same shape
     * [RawExporter]'s IMU decode uses.
     */
    private fun minBpm(streams: List<RawStreamEntity>): Int? =
        streams.firstOrNull { it.kind == RawStreamEntity.KIND_HRM }
            ?.let { stream -> runCatching { HrCsv.decode(Gzip.decompress(stream.csvGzip)) }.getOrNull() }
            ?.let { HrTrust.summarize(it).minBpm }

    /**
     * The set's sensor declaration, with the roles that actually arrived
     * filled in, or null when the row states none.
     *
     * [SensorCapturePolicy.present] decides the intersection, in `:core:model`
     * where a test runs on it: which roles count as present is the kind of
     * rule that would otherwise be restated here and in `RawExporter` and
     * disagree in one of them.
     *
     * A stream whose role string this build does not recognise is dropped by
     * [SensorCapturePolicy.roleFromWire] rather than mapped onto a role it
     * does know, so a document written by a later version cannot relabel
     * somebody's capture on the way through.
     */
    private fun sensorsExport(record: SetRecordEntity, streams: List<RawStreamEntity>): SetSensorsExport? {
        val declared = sessionRepository.decodeSensors(record) ?: return null
        val captured =
            streams.filter { it.kind == RawStreamEntity.KIND_IMU }
                .mapNotNull { SensorCapturePolicy.roleFromWire(it.role) }
        return SetSensorsExport(
            count = declared.count,
            expected = declared.expected.map(SensorCapturePolicy::wireOf),
            present = SensorCapturePolicy.present(declared.expected, captured).map(SensorCapturePolicy::wireOf),
            analysedRole = declared.analysed?.let(SensorCapturePolicy::wireOf),
            // Read off the row, never re-decided here (#207). The analysis
            // this document publishes was computed when the set was RECORDED,
            // from whichever stream the record path chose then; deciding again
            // at export time would name a role the frozen figures did not come
            // from. A row an earlier build wrote therefore keeps publishing
            // the role it named, with no flag, and that is what it means.
            analysedFellBack = declared.analysedFellBack,
            shortfall = declared.shortfall?.let(SensorCapturePolicy::shortfallToWire),
            // Read off the row for `analysedFellBack`'s reason (#213). What a
            // link looked like when a set ended is not recoverable afterwards
            // from anything, so re-deciding it here would publish a reading of
            // a link state that exists now rather than one that existed then.
            // Empty on every set an earlier build wrote, and the exporter
            // drops an empty map, so such a set publishes no key -- which is
            // correct rather than a default, no earlier build having been able
            // to observe delivery at all.
            silent = declared.silent
                .map { (role, delivery) ->
                    SensorCapturePolicy.wireOf(role) to ArmedSilencePolicy.wireOf(delivery)
                }.toMap(),
            // The same reading for the set whose single stream carries no role
            // (#224). Read off the row for `silent`'s reason, and never both --
            // a row carrying roles carries the map, a row carrying none carries
            // this. Absent on every set an earlier build wrote, which is
            // correct: no build before this one could observe an unroled link.
            soleSilent = declared.soleSilent?.let(ArmedSilencePolicy::wireOf),
        )
    }

    /**
     * Into the plan's own vocabulary, so a reader holding both schemas reads
     * `"concentric": "down"` the same way in each.
     *
     * [ResolvedGeometry] speaks in the app's terms because it records what the
     * app used; this is the one place the two vocabularies meet.
     */
    private fun geometryExport(g: ResolvedGeometry) = GeometryExport(
        startsWith = g.startsWith.name.lowercase(),
        concentric = if (g.concentricUp) "up" else "down",
        plane = if (g.horizontal) "horizontal" else "vertical",
        sensorOnStack = g.sensorOnStack,
        sensorInverted = g.sensorInverted,
        travelRatio = g.travelRatio,
        kind = g.kind.name.lowercase(),
        bodyweight = g.bodyweight,
        source =
        GeometrySourceExport(
            startsWith = g.sources.startsWith.name.lowercase(),
            concentric = g.sources.concentric.name.lowercase(),
            plane = g.sources.plane.name.lowercase(),
            kind = g.sources.kind.name.lowercase(),
            travelRatio = g.sources.travelRatio.name.lowercase(),
            sensorOnStack = g.sources.sensorOnStack.name.lowercase(),
        ),
    )

    private fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else average()

    private fun Double.round1(): Double = Math.round(this * 10.0) / 10.0

    private fun Double.round2(): Double = Math.round(this * 100.0) / 100.0

    private fun Double.round3(): Double = Math.round(this * 1000.0) / 1000.0
}

/**
 * The answer as it may be PUBLISHED: read back through the vocabulary, or
 * absent (#189).
 *
 * THE PUBLISH BOUNDARY IS THE LAST GATE, NOT A SECOND OPINION. The vocabulary
 * is enforced where the value is PRODUCED and this build cannot write a row
 * that breaks it -- but the column is TEXT, and `SetLimiter.ofStored`'s own
 * KDoc says a row written by a LATER build may carry a value this one has
 * never heard of, so an unrecognised one publishes as NO answer rather than as
 * a member of a closed enum the document promises.
 */
private val SetRecordEntity.publishedLimiter: String?
    get() = SetLimiter.ofStored(limiter)?.stored

/**
 * The free-text note as it may be PUBLISHED: only where an answer stands
 * beside it (#189).
 *
 * One definition, read by both export writers, and that is the point rather
 * than tidiness. The session document is serialised by kotlinx and the raw
 * archive's manifest is assembled as text by a different function; a rule
 * written twice is a rule that drifts, and the half that drifts is whichever
 * writer the next change does not open.
 *
 * The gate is on the ANSWER being present rather than on it being `other`,
 * which is the weaker of the two available rules and is chosen deliberately.
 * Nothing this app writes puts a note beside any other answer -- the write
 * path drops it -- so the stricter rule would fire only on a row this build
 * did not produce, and would silently delete a lifter's words from the export
 * rather than publish something a reader can see is odd. What it does refuse
 * is a note with NO answer to attach to: that is free text a reader can
 * neither group nor attribute, and the published schema promises it is not
 * there.
 *
 * The note is normalized again on the way out, and that second pass matters
 * more than the gate above it: the manifest is assembled as text and escapes
 * nothing, so one stored backslash does not corrupt one note, it makes
 * meta.json unparseable for every set in the session.
 */
private val SetRecordEntity.publishedLimiterNote: String?
    get() = SetLimiter.normalizeNote(limiterNote)?.takeIf { publishedLimiter != null }

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
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    /**
     * BEST_SPEED, not the ZipOutputStream default (DEFAULT_COMPRESSION):
     * measured on issue #29's own 17-set composite, DEFLATE at the default
     * level cost 503-614 ms (min 503, 7 trials); BEST_SPEED cost 119-204 ms
     * (min 119) for a 25.5% larger archive (1,671,929 B against 1,332,338
     * B). No change to what a reader gets out of the zip: a Deflater level
     * only decides how hard the algorithm looks for redundancy, never the
     * decompressed bytes, so every entry is still the identical plain-text
     * CSV it always was. A constructor parameter, not a hardcoded call,
     * because a level is otherwise unreadable back out of a zip archive --
     * only the compression METHOD is recorded in the format, not the level
     * -- so a test that wants to prove which level actually ran needs to
     * hold two real archives built at two known levels apart, not infer one
     * from output size against a hand-built reference with different
     * entries and therefore different overhead.
     */
    private val zipCompressionLevel: Int = Deflater.BEST_SPEED,
) {
    /**
     * Issue #29's fold, in one place: each set's streams are fetched once and
     * each stream's bytes are inflated once, here, in this loop -- not once
     * for the raw zip entry and again for whatever structured figure needs
     * it. The IMU decode this used to duplicate (`imuSamples`, its own
     * separate inflate) is gone; the text this loop already holds is parsed
     * directly. The HRM decode is folded the same way, but crosses into
     * [SessionExporter]: `minBpm` is computed here, from the text this loop
     * already holds, and handed to [SessionExporter.exportJson] through
     * [SessionExporter.buildExport]'s `minBpmOverride`, rather than letting
     * that call inflate the same stream a second time the way it did when
     * minBpm was added for issue #90.
     */
    suspend fun buildZip(sessionId: Long): ByteArray? = withContext(dispatcher) {
        val session = sessionRepository.session(sessionId) ?: return@withContext null
        val sets = sessionRepository.sets(sessionId)
        val out = ByteArrayOutputStream()
        val meta = StringBuilder()
        meta.append("{\n  \"epoch\": \"${Instant.ofEpochMilli(session.startedAtMs)}\",\n")
        // Beside the instant they qualify. The zip has to stand on its own: an
        // analysis that opens only the CSVs has `epoch` and a pile of epoch
        // milliseconds, and nothing that turns either into a local time of day.
        //
        // Flat keys here and a nested object in session.json, which is what the
        // two documents already do with geometry. `timeZoneId` rather than
        // `timeZone` so one key name never means a string in one artifact and
        // an object in the other. Omitted entirely when the session carries no
        // zone -- this file writes no null literals.
        RecordedTimeZone.of(session.zoneId, session.utcOffsetMinutes)?.let { zone ->
            meta.append("  \"utcOffsetMinutes\": ${zone.utcOffsetMinutes},\n")
            meta.append("  \"timeZoneId\": \"${zone.id.replace("\"", "'")}\",\n")
        }
        meta.append("  \"appVersion\": \"$appVersion\",\n  \"sensorModel\": \"WitMotion WT901BLECL\",\n")
        meta.append("  \"analysisFile\": \"session.json\",\n")
        meta.append("  \"csvHeaderImu\": \"${ImuCsv.HEADER}\",\n  \"csvHeaderHrm\": \"${HrCsv.HEADER}\",\n")
        meta.append("  \"csvHeaderCues\": \"${CueCsv.HEADER}\",\n")
        // A header per format the archive can contain. The rep-mark CSV needs
        // no change to the zip loop -- every stream is written as
        // set%02d_<exercise>_<kind>.csv and the kind alone names the file --
        // but a column layout stated nowhere is one a reader has to guess at,
        // and this manifest has no published schema to guess from.
        meta.append("  \"csvHeaderReps\": \"${RepMarkCsv.HEADER}\",\n")
        // A fifth format, and the same reasoning: the prep window is stored as
        // a stream and so is written into the archive as a file by the same
        // loop, and a file whose column layout is stated nowhere is one a
        // reader has to guess at.
        meta.append("  \"csvHeaderPrep\": \"${PrepWindowCsv.HEADER}\",\n")
        meta.append("  \"sets\": [\n")

        // zipCompressionLevel is documented on the constructor parameter, not
        // repeated here.
        ZipOutputStream(out).apply { setLevel(zipCompressionLevel) }.use { zip ->
            val setLines = mutableListOf<String>()
            // Every set gets an entry, even null, so setExport's `record.id in
            // minBpmOverride` check (rather than a value-nullity check) can tell
            // "computed here, nothing trusted" apart from "not computed here".
            val minBpmBySet = mutableMapOf<Long, Int?>()
            for ((idx, record) in sets.withIndex()) {
                val streams = sessionRepository.rawStreams(record.id)
                val files = mutableListOf<String>()
                // Keyed by role, not a single `var`, and that is issue #156's
                // near miss rather than a tidy-up. This used to be
                // `var imuText` assigned in the loop, so a set carrying two
                // `imu` rows handed the descriptor only the LAST one seen --
                // one of the lifter's two captures silently missing from a
                // manifest that looked complete. The key is nullable because a
                // one-sensor stream carries no role, and that entry is exactly
                // what the unchanged single-sensor path reads back.
                val imuTextByRole = LinkedHashMap<String?, String>()
                val imuFileByRole = LinkedHashMap<String?, String>()
                // Parsed from the text this loop already inflated, like every
                // other figure the descriptor reads out of a stream.
                var prepWindow: PrepWindow? = null
                for (stream in streams) {
                    val name = entryName(idx, record, stream)
                    val text = Gzip.decompress(stream.csvGzip)
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(text.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                    files += name
                    when (stream.kind) {
                        RawStreamEntity.KIND_IMU -> {
                            imuTextByRole[stream.role] = text
                            imuFileByRole[stream.role] = name
                        }
                        RawStreamEntity.KIND_HRM -> minBpmBySet[record.id] = minBpmFrom(text)
                        RawStreamEntity.KIND_PREP -> prepWindow = PrepWindowCsv.decode(text)
                    }
                }
                if (record.id !in minBpmBySet) minBpmBySet[record.id] = null
                // The raw zip has to stand on its own: an analysis that opens
                // only the CSVs must still be able to tell left from right, a
                // warm-up from a working set, and which sets were rotating
                // enough for attitude error to matter.
                setLines +=
                    buildSetDescriptor(idx, record, streams, files, imuTextByRole, imuFileByRole, prepWindow)
            }
            meta.append(setLines.joinToString(",\n")).append("\n  ]\n}\n")
            zip.putNextEntry(ZipEntry("meta.json"))
            zip.write(meta.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            val sessionJson =
                sessionExporter.exportJson(sessionId, includeRepDetail = true, minBpmOverride = minBpmBySet)
            sessionJson?.let {
                zip.putNextEntry(ZipEntry("session.json"))
                zip.write(it.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        out.toByteArray()
    }

    /**
     * A malformed HRM stream yields null rather than failing the export, the
     * same shape [imuSamples] uses for the IMU side.
     */
    private fun minBpmFrom(hrText: String): Int? =
        runCatching { HrCsv.decode(hrText) }.getOrNull()?.let { HrTrust.summarize(it).minBpm }

    /**
     * What a stream is called inside the archive.
     *
     * The role is appended to the KIND segment rather than being folded into
     * the kind itself: `raw_streams.kind` stays the equality-matched vocabulary
     * every selector in this package relies on, and the hyphenated form exists
     * only here, in a filename, where a person reads it. A one-sensor stream
     * carries no role and keeps the name it has always had, which is what makes
     * a single-sensor archive byte-identical to what earlier versions wrote.
     */
    private fun entryName(idx: Int, record: SetRecordEntity, stream: RawStreamEntity): String {
        val kind = stream.role?.let { "${stream.kind}-$it" } ?: stream.kind
        return "set%02d_%s_%s.csv".format(idx + 1, record.exerciseId, kind)
    }

    private fun buildSetDescriptor(
        idx: Int,
        record: SetRecordEntity,
        streams: List<RawStreamEntity>,
        files: List<String>,
        imuTextByRole: Map<String?, String>,
        imuFileByRole: Map<String?, String>,
        prepWindow: PrepWindow?,
    ): String {
        // The same policy the session document asks, so the two cannot
        // disagree about one set (#216).
        val phase = AbandonedSetPolicy.published(record.workBegan, record.actualDurationS, record.prepS)
        val fields = mutableListOf<String>()
        fun num(key: String, value: Any?) = value?.let { fields += "\"$key\": $it" }
        fun str(key: String, value: String?) = value?.let { fields += "\"$key\": \"${it.replace("\"", "'")}\"" }
        fun flag(key: String, value: Boolean) = if (value) fields += "\"$key\": true" else Unit

        // Not [flag]. That one omits a false, which is right for warmup and
        // failed -- absence there reads correctly as "not flagged" -- and wrong
        // for geometry, where a reader that cannot see the key has to decide
        // whether the sensor was on a stack, and "the app did not say" and
        // "the app said no" are different answers.
        fun bool(key: String, value: Boolean) = fields.add("\"$key\": $value")

        num("set", idx + 1)
        str("exercise", record.exerciseId)
        num("load_kg", record.loadKg)
        num("load_lb", Math.round(record.loadKg * WeightUnit.LB_PER_KG * 10.0) / 10.0)
        num("reps", record.actualReps)
        num("plannedReps", record.plannedReps)
        num("duration_s", phase.durationS)
        // The set never entered its work phase, so the zeros beside this are
        // absences rather than measurements (#216). In the manifest as well as
        // the session document: two writers, one fact.
        flag("abandonedInPrep", phase.abandonedInPrep)
        str("side", record.side)
        // The prescription beside the arm worked, in the archive's manifest as
        // well as in the session document (#215). Two writers, one fact: a key
        // wired into only one of them publishes half a record, and which half
        // depends on which file the coach opened.
        str("plannedSide", record.plannedSide)
        num("rpe", record.rpe)
        flag("failed", record.failed)
        // [bool] and not [flag], gated on there being a failure at all: a
        // published false here is a real statement -- the app derived it and
        // the lifter never said so -- and omitting it would lose exactly the
        // half of the record #216 exists to add.
        FailureProvenancePolicy.published(record.failed, record.failedByLifter)?.let { bool("failedByLifter", it) }
        // The reason, in the archive's manifest as well as in the session
        // document. Two writers, one fact: a change wired into only one of
        // them publishes half a record, and which half depends on which file
        // the coach opened.
        //
        // [str] and not [bool]: absence must go on reading as "not asked" for
        // every archive already written. The note is the first FREE TEXT this
        // writer has ever carried -- every value before it was a machine token
        // -- and it is safe here because it is normalized on the way OUT, in
        // [publishedLimiterNote], and not only on the way in. Relying on the
        // write path alone was an assumption about a writer held at a reader:
        // a backslash arriving from a row this build did not write would not
        // corrupt the note, it would make this whole manifest unparseable for
        // every set in the session.
        str("limiter", record.publishedLimiter)
        str("limiterNote", record.publishedLimiterNote)
        flag("warmup", WarmupMarkPolicy.effective(record.warmup, record.warmupMark))
        // Which of the two facts this document carries. Without it a
        // plan-declared ramp and one the lifter marked at the rack read
        // identically, and the plan's own word -- overridden in the value
        // above -- would vanish with nothing saying it had.
        flag("warmupByLifter", WarmupMarkPolicy.markedByLifter(record.warmupMark))
        // [flag], never [bool]: absence must go on reading as "prescribed"
        // for every archive already written, and a `false` here would change
        // the bytes of every past session's manifest for no gain (#177).
        flag("added", record.added)
        flag("repsManual", record.repsManual)
        str("tempoPrescribed", record.tempo)
        // The prep, both halves. [num] drops a null, which is right -- a set
        // that ran no voice guide has no prep -- and writes a real 0, which is
        // also right: 0 is the prep in which nothing is spoken before the first
        // stroke call, not the absence of one.
        //
        // Here as well as in session.json because the archive has to stand on
        // its own, and because the cue track can no longer answer it: LeadInPlan
        // fixes the launch phrase to the END of the prep, so Ready sits a
        // prescribed PHRASE_S seconds before the first movement cue whether the
        // prep was 2 seconds or 20.
        num("plannedPrep_s", record.plannedPrepS)
        num("prep_s", phase.prepS)
        // Which way the lift moved and how the sensor was mounted.
        //
        // This manifest is the only thing a reader who opens the CSVs alone
        // has: the samples are device-frame and carry no phase labels, so
        // without this there is nothing to tell an eccentric from a concentric.
        // On a leg curl or a pushdown the down stroke is the drive, and no
        // accelerometer trace says so.
        //
        // Omitted entirely for a set that carries no stored geometry, which is
        // every set recorded before the column existed.
        sessionRepository.decodeGeometry(record)?.let { g ->
            str("startsWith", g.startsWith.name.lowercase())
            str("concentric", if (g.concentricUp) "up" else "down")
            str("plane", if (g.horizontal) "horizontal" else "vertical")
            bool("sensorOnStack", g.sensorOnStack)
            bool("sensorInverted", g.sensorInverted)
            num("travelRatio", g.travelRatio)
            str("kind", g.kind.name.lowercase())
            bool("bodyweight", g.bodyweight)
        }
        // How many accelerometers this set was armed with, which roles they
        // carried and which one everything else in this descriptor describes
        // (#156). Omitted entirely on the ordinary one-sensor set, which is
        // what keeps that archive's manifest what it has always been.
        //
        // Flat keys here and a nested object in session.json, which is what
        // the two documents already do with geometry and with the time zone;
        // the names differ deliberately, so one key never means a number in
        // one artifact and an object in the other.
        val declared = sessionRepository.decodeSensors(record)
        val analysedRole = declared?.analysed?.let(SensorCapturePolicy::wireOf)
        declared?.let { d ->
            num("sensorsArmed", d.count)
            // Absent when nothing was in the way, which is a dual set and the
            // ordinary one-sensor set both -- and the ordinary one does not
            // reach here at all, the whole declaration being null. Present
            // for two PAIRED units the app could not tell apart, the state
            // session.json also publishes and which neither document can
            // otherwise express, and, since #224, for a one-sensor set whose
            // sole unit was silent -- which reaches here with a declaration
            // and no shortfall, because nothing was in the roster's way.
            // Paired, not connected: nothing here has looked at a link, so it
            // does not say the second unit was on.
            str("sensorsShortfall", d.shortfall?.let(SensorCapturePolicy::shortfallToWire))
            // Written even when empty. An empty list is a set whose stream
            // carries no role: two paired units the app could not tell apart,
            // or -- since #224 -- one paired unit that delivered nothing.
            // Which of the two is sensorsShortfall and sensorsSoleSilent. An
            // absent key would read as this version not stating it.
            fields += "\"sensorRolesExpected\": " +
                "[${d.expected.joinToString(", ") { "\"${SensorCapturePolicy.wireOf(it)}\"" }}]"
            str("analysedRole", analysedRole)
            // [flag], so a false is omitted rather than written: the ordinary
            // set says nothing here and session.json says nothing there,
            // because its exporter drops the same default. Both documents
            // express "the analysed role is the role the set armed" by
            // omission, which is also what every archive written before this
            // key existed says.
            flag("analysedFellBack", d.analysedFellBack)
            // Which armed units delivered nothing, and what the app could see
            // of each one's link when the set ended (#213). Written only when
            // something was silent, so an ordinary dual set's descriptor is
            // what it has always been.
            //
            // An object under one key, where every other sensor key here is
            // flat. The flat convention is about keeping one name from meaning
            // a number in this document and an object in session.json; this is
            // a per-role fact and a flattening of it would have to invent
            // `sensorsSilentA`, whose absence could not be told from a role
            // that was never armed. It carries the SAME words session.json
            // carries, so a reader who opens the archive and one who opens the
            // analysis get one account of one set.
            if (d.silent.isNotEmpty()) {
                fields += "\"sensorsSilent\": {" +
                    d.silent.entries.joinToString(", ") { (role, delivery) ->
                        "\"${SensorCapturePolicy.wireOf(role)}\": \"${ArmedSilencePolicy.wireOf(delivery)}\""
                    } + "}"
            }
            // The same word for the set whose single stream carries no role
            // (#224), and FLAT rather than an object because there is no role
            // to key it by -- which is what makes it fit this document's flat
            // convention where `sensorsSilent` could not. Written only when
            // that one link was silent, so an ordinary one-sensor descriptor
            // is byte-for-byte what it has always been: such a set stores no
            // declaration at all and never reaches this branch.
            str("sensorsSoleSilent", d.soleSilent?.let(ArmedSilencePolicy::wireOf))
        }
        num("startedAt_ms", record.startedAtMs)
        num("endedAt_ms", record.endedAtMs)
        // Where the prep was, on the clock ImuCsv's timestamp_ms column is
        // stamped with, so a reader who opens this archive and no other
        // document can select the rows that fall before the set began (#185).
        //
        // Epoch milliseconds and not offsets. The keys either side of these
        // are already epoch milliseconds, and an offset would need a base --
        // the only top-level instant this manifest carries is the SESSION's
        // start, which is not this set's.
        //
        // BOTH halves, though the first equals startedAt_ms on every set this
        // build records. The window is one fact, and half of it living under a
        // key that means the row's own tap instant is how a reader ends up
        // bracketing a set with two instants that were never a pair.
        //
        // Omitted together when the set stored no window, which is every set
        // recorded before this version, every set that ran no prep, and every
        // set ended while its prep was still running. The absence is not a
        // claim that the lifter was never stationary, and no zero substitutes
        // for it: a zero here would be an instant in 1970.
        //
        // What is NOT here is anything derived from the samples inside the
        // window -- no stillness score, no gravity vector, no transform. Those
        // are the analysis's, which has the whole set, can filter without phase
        // lag and can re-run when a method turns out wrong; every derived
        // quantity published here is one it could not revise.
        prepWindow?.let { window ->
            num("prepStartedAt_ms", window.startedAtMs)
            num("workStartedAt_ms", window.workStartedAtMs)
        }
        // Parsed from the text buildZip's own loop already inflated -- not
        // re-inflated here, and shared with sampleRate_hz and
        // rollExcursion_deg below besides: decoding a whole IMU capture even
        // once more for either figure would buy nothing.
        //
        // The ANALYSED stream, selected by role rather than by "whichever IMU
        // row came first". On a one-sensor set there is no declaration and the
        // unroled entry is read, which is byte-for-byte the behaviour this
        // manifest has always had. Where the analysed role has no entry at all
        // every figure below is withheld rather than taken from the surviving
        // stream -- publishing one sensor's cadence under a key every reader
        // takes to describe the other is the wrong pair in its sharpest form.
        // Since #207 a set THIS build records reaches that state only when
        // nothing streamed; one whose armed unit alone was silent is analysed
        // from the unit that was not, so its role and its entry match. A row
        // an older build wrote can still be in it and is left there: the
        // figures that row carries were computed from no stream, and measuring
        // the survivor here would describe a capture the set was not analysed
        // from.
        val samples = imuSamples(if (declared == null) imuTextByRole[null] else imuTextByRole[analysedRole])
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
        // Matched on the role as well as the kind. `firstOrNull { kind ==
        // KIND_IMU }` would take the first IMU row on a dual set whatever it
        // was, which on a set whose analysed role has no row of its own is the
        // OTHER sensor's stored figure under the analysed sensor's key. Since
        // #207 that shape is a set an older build recorded, or one where
        // nothing streamed -- rarer, and still wrong to publish.
        val storedRate =
            streams.firstOrNull { it.kind == RawStreamEntity.KIND_IMU && it.role == analysedRole }?.sampleRateHz
        num("sampleRate_hz", (measuredRate ?: storedRate)?.takeIf { it > 0.0 })
        // Attitude excursion decides which analysis is even valid on this set:
        // a rail-guided machine barely rotates and integrates cleanly, while a
        // barbell tumbling through 300 degrees leaks gravity into every sample.
        rollExcursionDeg(samples)?.let { num("rollExcursion_deg", Math.round(it * 10.0) / 10.0) }
        // One entry per capture that actually arrived, each figure measured
        // from ITS OWN stream. A role that is armed and absent has no entry
        // here and appears in sensorRolesExpected only; the roles MISSING are
        // the set difference between the two, deliberately not a third key
        // that could disagree with its own inputs.
        sensorStreamEntries(imuTextByRole, imuFileByRole)?.let { fields += "\"sensors\": $it" }
        fields += "\"files\": [${files.joinToString(", ") { "\"$it\"" }}]"
        return "    {${fields.joinToString(", ")}}"
    }

    /**
     * The `sensors` array of a dual set's descriptor, or null when there is
     * none to write.
     *
     * Null on every set whose IMU streams carry no role, which is every
     * one-sensor set and every set recorded before roles existed -- so the
     * descriptor those produce is untouched.
     *
     * Each entry's `samples`, `sampleRate_hz` and `rollExcursion_deg` are
     * computed from that entry's own decoded stream. That is one extra
     * `ImuCsv.decode` per dual set and no extra INFLATE: the text is the one
     * `buildZip`'s loop already decompressed to write the CSV entry. A rate is
     * omitted where the stream cannot state one, and never written as zero,
     * the same rule the set-level key follows.
     */
    private fun sensorStreamEntries(
        imuTextByRole: Map<String?, String>,
        imuFileByRole: Map<String?, String>,
    ): String? {
        val roled = imuTextByRole.entries.filter { it.key != null }
        if (roled.isEmpty()) return null
        val entries =
            roled.map { (role, text) ->
                val samples = imuSamples(text)
                val parts = mutableListOf("\"role\": \"$role\"", "\"file\": \"${imuFileByRole[role]}\"")
                samples?.let { s ->
                    parts += "\"samples\": ${s.size}"
                    VelocityEstimator.measuredSampleRateOrNull(
                        s.size,
                        (s.last().timestampMs - s.first().timestampMs) / 1000.0,
                    )?.takeIf { it > 0.0 }?.let { parts += "\"sampleRate_hz\": $it" }
                }
                rollExcursionDeg(samples)?.let { parts += "\"rollExcursion_deg\": ${Math.round(it * 10.0) / 10.0}" }
                "{${parts.joinToString(", ")}}"
            }
        return "[${entries.joinToString(", ")}]"
    }

    /**
     * This set's IMU capture, parsed from the text [buildZip]'s own loop
     * already inflated -- never re-inflated here -- or null when there is
     * nothing readable to work from: no stream (so no text), text that will
     * not decode, or a decode with no data rows.
     *
     * The decode stays inside `runCatching`: a gzip round trip is not the
     * only way this fails. [ImuCsv.decode] parses each row and throws on a
     * malformed one, and a manifest is not worth failing an entire export
     * over.
     */
    private fun imuSamples(imuText: String?): List<ImuSample>? {
        if (imuText == null) return null
        val samples = runCatching { ImuCsv.decode(imuText) }.getOrNull() ?: return null
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
