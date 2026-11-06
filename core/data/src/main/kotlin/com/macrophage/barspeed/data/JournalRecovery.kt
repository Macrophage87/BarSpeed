package com.macrophage.barspeed.data

import com.macrophage.barspeed.dsp.ImuCsv
import com.macrophage.barspeed.dsp.SetAnalysis
import com.macrophage.barspeed.dsp.SetAnalyzer
import com.macrophage.barspeed.dsp.SetEnd
import com.macrophage.barspeed.dsp.SetTargets
import com.macrophage.barspeed.dsp.StackRollSignature
import com.macrophage.barspeed.dsp.liftDirection
import com.macrophage.barspeed.model.AbandonedSetPolicy
import com.macrophage.barspeed.model.HrSample
import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.JournalRecoveryPolicy
import com.macrophage.barspeed.model.PrepCase
import com.macrophage.barspeed.model.PrepWindowPolicy
import com.macrophage.barspeed.model.RecoveryRefusal
import com.macrophage.barspeed.model.SensorCapturePolicy
import com.macrophage.barspeed.model.SetGeometryPolicy
import com.macrophage.barspeed.model.VoiceCue
import com.macrophage.barspeed.model.armedCaptureOf
import java.io.File

/** What one attempt to put an interrupted set back into History did (#100). */
sealed interface RecoveryOutcome {
    /** The capture cannot become a row; it stays on disk, offered as a zip or a discard. */
    data class Refused(val refusal: RecoveryRefusal) : RecoveryOutcome

    /** A recording write is outstanding; nothing was written and the capture stays. */
    data object Waiting : RecoveryOutcome

    /** The set was already a row; nothing was written and the capture was removed. */
    data object AlreadyStored : RecoveryOutcome

    /** Written as [setId] in [sessionId]; [newSession] when that session was opened for it. */
    data class Stored(val sessionId: Long, val setId: Long, val newSession: Boolean) : RecoveryOutcome
}

/** A capture decoded and re-analysed, or why it could not be (#100). */
internal sealed interface PreparedRecovery {
    data class Refused(val refusal: RecoveryRefusal) : PreparedRecovery

    data class Ready(val set: CompletedSet) : PreparedRecovery
}

/**
 * Puts an interrupted set's capture back into History as a set row (#100, the
 * stage #42 split off).
 *
 * The decisions are `JournalRecoveryPolicy`'s, in `:core:model` where they are
 * red-gated: which captures are refused, which count the row carries, when the
 * set is recorded as having ended, and -- the hard one -- which session it goes
 * into. What is here is the plumbing those answers need: reading the journal's
 * streams back, re-running the analysis the set's own write would have run, and
 * the write itself.
 *
 * THE ANALYSIS IS THE NORMAL PATH'S, NOT A SECOND ONE. The analysed stream is
 * chosen by `armedCaptureOf`, the geometry by `SetGeometryPolicy.analysedUnder`,
 * and the figures by `SetAnalyzer.analyze` with the journaled cue track and
 * prep window -- the same three calls `RecordViewModel.endSet` and
 * `runSetWrite` make, over the same kinds of input, read back from disk instead
 * of from the heap.
 *
 * WHAT A RECOVERED ROW DOES NOT HAVE, said once here and in the card's
 * confirmation. No plan targets, side, tempo or effort rating: they were never
 * journaled. No live count: it died with the process, so the row's reps are
 * the segmenter's. No silence words for an armed unit that delivered nothing:
 * those read the link states at the end of the set, and the links ended with
 * the process. No time zone on a session opened for the set: the journal never
 * carried one, and null is `SessionOpening`'s "could not establish".
 *
 * ONLY COMPLETE LINES ARE READ. A process killed mid-append leaves a final line
 * with no newline after it, and that line can parse: a kill inside the tenth
 * field of an IMU row leaves ten fields, the last one short, and `ImuCsv.decode`
 * returns it as a sample with a wrong yaw. Everything after the last newline is
 * therefore dropped before decoding. The zip still carries those bytes.
 */
@Suppress("UnusedPrivateProperty") // the seam's: the write lands on the next commits
class JournalRecovery(
    private val repository: SessionRepository,
    private val store: SetJournalStore,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    /**
     * Why [orphan] cannot be put back, or null when it can -- from the listing
     * scan alone, so the card can ask without decoding anything (#271).
     *
     * The answer can still change at [recover], which decodes: a stream the
     * scan counted whole can turn out not to parse, and a prep file can be
     * present and say nothing. Both are refused there with the same words.
     */
    fun refusal(orphan: OrphanedSet): RecoveryRefusal? {
        val analysedFrames = analysedScan(orphan)?.rows ?: 0L
        return JournalRecoveryPolicy.refusal(
            facts = orphan.header.facts,
            imuReadable = listOfNotNull(orphan.imu, orphan.secondaryImu).all { it.counted },
            analysable = analysedFrames >= SensorCapturePolicy.MIN_ANALYSABLE_FRAMES,
            repMarks = orphan.repMarks?.takeIf { it.counted }?.rows ?: 0L,
            workStartJournaled = File(orphan.directory, SetJournal.PREP).isFile,
        )
    }

    /**
     * Put [orphan] back into History, or say why not.
     *
     * [writeOutstanding] is whether any recording hold is held; while one is,
     * nothing is decided (`JournalRecoveryPolicy.target`).
     */
    @Suppress("UnusedParameter")
    suspend fun recover(orphan: OrphanedSet, writeOutstanding: Boolean): RecoveryOutcome =
        when (val prepared = prepare(orphan)) {
            is PreparedRecovery.Refused -> RecoveryOutcome.Refused(prepared.refusal)
            // SEAM: nothing is written yet. The target and the write land
            // with their differentials on the next commits.
            is PreparedRecovery.Ready -> RecoveryOutcome.Waiting
        }

    /** [orphan]'s streams read back and re-analysed into the row it would become. */
    internal fun prepare(orphan: OrphanedSet): PreparedRecovery {
        refusal(orphan)?.let { return PreparedRecovery.Refused(it) }
        val header = orphan.header
        val facts = checkNotNull(header.facts) { "refusal() lets no capture without facts through" }
        val streams =
            runCatching { readStreams(orphan) }.getOrNull()
                ?: return PreparedRecovery.Refused(RecoveryRefusal.STREAM_UNREADABLE)
        // The one rule every writer of a window goes through, asked with the
        // journaled instant. A prepped set whose file says nothing it can stand
        // behind gets no window, and is refused rather than counted from the tap.
        val prepWindow = PrepWindowPolicy.of(facts.prepCase, header.startedAtMs, streams.workStartedAtMs)
        if (facts.prepCase != PrepCase.NONE && prepWindow == null) {
            return PreparedRecovery.Refused(RecoveryRefusal.WORK_START_UNKNOWN)
        }
        val cadenceGuided = facts.prepCase == PrepCase.CUED
        val capture =
            armedCaptureOf(
                armed = facts.armedSensors,
                secondaryRole = secondaryRole(header),
                analysedBuffer = streams.armed,
                secondaryBuffer = streams.secondary,
                declaresStackMount = facts.exercise.sensorOnStack,
                stackSignalOf = { samples ->
                    StackRollSignature.of(samples, prepWindow?.workStartedAtMs, SetEnd.of(streams.cues, cadenceGuided))
                },
            )
        val analysed =
            SetGeometryPolicy.analysedUnder(
                used = facts.exercise,
                geometry = facts.geometry,
                stackRuleApplied = facts.stackRuleApplied,
                analysedSignal = capture.analysedSignal,
            )
        val analysable = capture.samples.size >= SensorCapturePolicy.MIN_ANALYSABLE_FRAMES
        val analysis =
            if (analysable) {
                SetAnalyzer.analyze(
                    capture.samples,
                    analysed.exercise.liftDirection(),
                    facts.loadKg,
                    SetTargets(
                        countedReps = streams.marks.size.takeIf { it > 0 },
                        cadenceGuided = cadenceGuided,
                    ),
                    cues = streams.cues,
                    workStartedAtMs = prepWindow?.workStartedAtMs,
                    analysedUnitFellBack = capture.sensors?.analysedFellBack == true,
                )
            } else {
                SetAnalysis(emptyList(), 0.0, null, null, listOf(MARKS_ONLY_NOTE))
            }
        val count =
            JournalRecoveryPolicy.count(analysable, analysis.reps.size, streams.marks.size)
                ?: return PreparedRecovery.Refused(RecoveryRefusal.NOTHING_TO_COUNT)
        val endedAtMs =
            JournalRecoveryPolicy.endedAtMs(
                header.startedAtMs,
                listOfNotNull(
                    streams.armed.lastOrNull()?.timestampMs,
                    streams.secondary.lastOrNull()?.timestampMs,
                    streams.hr.lastOrNull()?.timestampMs,
                    streams.cues.lastOrNull()?.timestampMs,
                    streams.marks.maxOrNull(),
                    prepWindow?.workStartedAtMs,
                ),
            )
        return PreparedRecovery.Ready(
            CompletedSet(
                exerciseId = analysed.exercise.id,
                exerciseName = header.exerciseName,
                loadKg = facts.loadKg,
                plannedLoadKg = null,
                workingLoadKg = facts.loadKg,
                bodyWeightKg = facts.bodyWeightKg,
                plannedReps = null,
                manualReps = count.reps.takeIf { count.stated },
                tempo = null,
                targetMeanConVelMps = null,
                velocityLossStopPct = null,
                plannedRestS = null,
                restStartedAtMs = endedAtMs,
                plannedPrepS = null,
                prepS = null,
                startedAtMs = header.startedAtMs,
                endedAtMs = endedAtMs,
                analysis = analysis,
                geometry = analysed.geometry,
                imuSamples = capture.samples,
                hrSamples = streams.hr,
                voiceCues = streams.cues,
                repMarks = streams.marks,
                sensors = capture.sensors,
                secondary = capture.secondary,
                prepWindow = prepWindow,
                workBegan = AbandonedSetPolicy.workBegan(facts.prepCase, prepWindow?.workStartedAtMs),
                recovered = true,
            ),
        )
    }

    private class Streams(
        val armed: List<ImuSample>,
        val secondary: List<ImuSample>,
        val hr: List<HrSample>,
        val cues: List<VoiceCue>,
        val marks: List<Long>,
        val workStartedAtMs: Long?,
    )

    private fun readStreams(orphan: OrphanedSet): Streams {
        val dir = orphan.directory
        val secondary = secondaryRole(orphan.header)?.let { SetJournal.secondaryImuFile(it) }
        return Streams(
            armed = completeLines(dir, SetJournal.IMU)?.let(ImuCsv::decode).orEmpty(),
            secondary = secondary?.let { completeLines(dir, it) }?.let(ImuCsv::decode).orEmpty(),
            hr = completeLines(dir, SetJournal.HRM)?.let(HrCsv::decode).orEmpty(),
            cues = completeLines(dir, SetJournal.CUES)?.let(CueCsv::decode).orEmpty(),
            marks = completeLines(dir, SetJournal.REPS)?.let(RepMarkCsv::decode).orEmpty(),
            workStartedAtMs = completeLines(dir, SetJournal.PREP)?.let(PrepWindowCsv::decode)?.workStartedAtMs,
        )
    }

    private companion object {
        /** The note a row with no analysable stream carries, beside a count from the journaled marks. */
        const val MARKS_ONLY_NOTE = "Recovered set: reps from the counted marks — no analysable bar-sensor stream."

        /** The header's second role, read the way `SetJournalStore` reads it for the listing. */
        fun secondaryRole(header: SetJournalHeader) = header.sensorRoles.firstOrNull { it != header.armedRole }

        /** The stream the figures would come from, as the listing decided it. */
        fun analysedScan(orphan: OrphanedSet): JournalStreamScan? {
            val armed = orphan.header.armedRole
            return if (orphan.analysedRole == null || orphan.analysedRole == armed) orphan.imu else orphan.secondaryImu
        }

        /** [name]'s text up to and including its last newline, or null when the file is not there. */
        fun completeLines(dir: File, name: String): String? {
            val file = File(dir, name)
            if (!file.isFile) return null
            val text = file.readText(Charsets.UTF_8)
            val end = text.lastIndexOf('\n')
            return if (end < 0) "" else text.substring(0, end + 1)
        }
    }
}
