package com.macrophage.barspeed.data

import com.macrophage.barspeed.dsp.ImuCsv
import com.macrophage.barspeed.dsp.RestStart
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
import com.macrophage.barspeed.model.RecoveryTarget
import com.macrophage.barspeed.model.SensorCapturePolicy
import com.macrophage.barspeed.model.SessionResumePolicy
import com.macrophage.barspeed.model.SetGeometryPolicy
import com.macrophage.barspeed.model.Tempo
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
 * of from the heap. Whether the analyzer runs at all is endSet's rule too: the
 * stream `armedCaptureOf` chose reaches
 * `SensorCapturePolicy.MIN_ANALYSABLE_FRAMES`. Span and stillness (#242,
 * #287) are not a second test here; they are inside `armedCaptureOf`'s choice
 * of that stream, as they are for a live set.
 *
 * WHAT A RECOVERED ROW DOES NOT HAVE, said once here and in the card's
 * confirmation. No plan targets, side or effort rating: they were never
 * journaled (the tempo, warm-up, added and planned-side declarations and the
 * prep seconds were, and are written as the set's own write writes them). No
 * live count: it died with the process. The row's reps are the journaled
 * marks, stated, on a set the lifter or the guide counted, and the batch
 * segmenter's, unstated, on a sensor-counted one -- `JournalRecoveryPolicy.
 * count`. No silence words for an armed unit that delivered nothing:
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
     * scan counted whole can turn out not to parse, a prep file can be
     * present and say nothing, and a sensor-counted set's analysis can find
     * no rep. Each is refused there with the same words.
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
     *
     * WHERE IT GOES is `JournalRecoveryPolicy.target`'s answer, asked with the
     * newest session over `SessionResumePolicy.LOOKBACK_MS` -- the read the
     * resume prompt makes -- and with whether a row of this exercise begun at
     * this instant already exists in any session that could hold it. A closed
     * session is never re-opened or rewritten: the set goes into a session of
     * its own, begun when the set began and closed when it ended, because
     * `endSession` writes a session's summary once and a set attached after
     * would sit outside the numbers drawn over its own session.
     *
     * THE CAPTURE GOES ONLY AFTER THE ROW HAS LANDED. Every write below
     * completes before `SetJournalStore.discard`, so a write that throws
     * leaves the journal on disk -- the only copy of the set -- and the
     * failure reaches the caller. A process killed between the row and the
     * discard leaves a capture whose set is stored, which the next attempt
     * answers AlreadyStored and removes without writing it twice. That is
     * asked before the re-analysis, so a stored set whose capture the
     * re-analysis would refuse -- a sensor-counted set whose analysis
     * withheld, whose row carries the live count -- goes too, rather than
     * being told no count survived (#100 review round 3, Lens A N2).
     *
     * A NEW SESSION IS TWO WRITES, NOT ONE (#100 review round 1, Lens A N6,
     * Lens B N5): `recordFirstSet` opens the session with the set in it, and
     * `endSession` closes it. A process killed, or an `endSession` that
     * throws, between the two leaves the set stored in a session that is
     * still open; the next attempt answers AlreadyStored and removes the
     * capture, and nothing closes that session. The set itself is not lost
     * or duplicated. The open session then reads as an unfinished workout,
     * and Record can offer "Continue this session?" for it while it is inside
     * the resume window. Stated rather than handled: closing it from here on
     * the AlreadyStored path would need a session-level write this class
     * does not otherwise make, and that has not been designed.
     */
    suspend fun recover(orphan: OrphanedSet, writeOutstanding: Boolean): RecoveryOutcome {
        val header = orphan.header
        // Asked before the re-analysis can refuse (#100 review round 3,
        // Lens A N2): a capture whose set is already a row is that row's
        // leftover whatever its streams now say. The row's exercise and
        // instant are the journal's own -- `prepare` writes facts.exercise's
        // id and the header's startedAtMs -- so nothing here needs the
        // analysis. A capture with no facts is refused below, as before.
        val stored = header.facts?.let { storedAlready(header, it.exercise.id) } == true
        if (stored && !writeOutstanding) {
            store.discard(orphan)
            return RecoveryOutcome.AlreadyStored
        }
        val set =
            when (val prepared = prepare(orphan)) {
                is PreparedRecovery.Refused -> return RecoveryOutcome.Refused(prepared.refusal)
                is PreparedRecovery.Ready -> prepared.set
            }
        val now = nowMs()
        val latest = repository.latestSession(now - SessionResumePolicy.LOOKBACK_MS, now)
        val target = JournalRecoveryPolicy.target(header.sessionId, latest, now, writeOutstanding, stored)
        return when (target) {
            RecoveryTarget.Wait -> RecoveryOutcome.Waiting
            RecoveryTarget.AlreadyStored -> {
                store.discard(orphan)
                RecoveryOutcome.AlreadyStored
            }
            is RecoveryTarget.Attach -> {
                repository.ensureExerciseExists(set.exerciseId)
                val setId = repository.recordSet(target.sessionId, target.orderIdx, set)
                store.discard(orphan)
                RecoveryOutcome.Stored(target.sessionId, setId, newSession = false)
            }
            RecoveryTarget.NewSession -> {
                repository.ensureExerciseExists(set.exerciseId)
                val opening = SessionOpening(header.planName, header.planSessionName, set.startedAtMs, timeZone = null)
                val written = repository.recordFirstSet(opening, JournalRecoveryPolicy.NEW_SESSION_ORDER_IDX, set)
                repository.endSession(written.sessionId, set.endedAtMs)
                store.discard(orphan)
                RecoveryOutcome.Stored(written.sessionId, written.setId, newSession = true)
            }
        }
    }

    /**
     * Whether a row of [exerciseId] begun at [header]'s instant is already in
     * any session that could hold it: begun before it, and no earlier than
     * the header's own session or the resume look-back, whichever reaches
     * further. Wider than the header's session alone, because a session
     * opened for an earlier recovery of this same capture begins at the
     * set's own start.
     */
    private suspend fun storedAlready(header: SetJournalHeader, exerciseId: String): Boolean {
        val from = minOf(header.sessionStartedAtMs, header.startedAtMs - SessionResumePolicy.LOOKBACK_MS)
        return JournalRecoveryPolicy.alreadyStored(
            exerciseId,
            header.startedAtMs,
            repository.setsBegunInSessionsBetween(from, header.startedAtMs),
        )
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
        // Asked before the analysis, because the count is what the analyzer
        // is handed to compare its own against -- the tally on a set the lifter
        // or the guide counted, exactly as `endSet` hands it `stated ?: live`.
        // A sensor-counted set's live count died with the process, so it is
        // handed none. The segmenter's count fills in after.
        val tally = JournalRecoveryPolicy.count(
            counter = facts.counter,
            analysable = false,
            analysedReps = 0,
            repMarks = streams.marks.size,
        )
        val analysis =
            if (analysable) {
                SetAnalyzer.analyze(
                    capture.samples,
                    analysed.exercise.liftDirection(),
                    facts.loadKg,
                    SetTargets(
                        countedReps = tally?.reps,
                        tempo = facts.tempo?.let { Tempo.parseOrNull(it) },
                        cadenceGuided = cadenceGuided,
                    ),
                    cues = streams.cues,
                    workStartedAtMs = prepWindow?.workStartedAtMs,
                    analysedUnitFellBack = capture.sensors?.analysedFellBack == true,
                    // The analysed unit's own roll, as endSet hands it: a
                    // fallback under a declaration naming no mount onto a
                    // unit that reads as riding a load is refused (#279;
                    // #100 review round 2, Lens A B1).
                    analysedSignal = capture.analysedSignal,
                )
            } else {
                SetAnalysis(emptyList(), 0.0, null, null, listOf(MARKS_ONLY_NOTE))
            }
        val count =
            JournalRecoveryPolicy.count(facts.counter, analysable, analysis.reps.size, streams.marks.size)
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
                tempo = facts.tempo,
                targetMeanConVelMps = null,
                velocityLossStopPct = null,
                plannedRestS = null,
                // The instant the set's own write would start the rest from,
                // asked of the same rule over the journaled cues and marks; no
                // hold reaches here, so no release decides it.
                restStartedAtMs = RestStart.of(streams.cues, cadenceGuided, streams.marks, null, endedAtMs),
                plannedSide = facts.plannedSide,
                plannedPrepS = facts.plannedPrepS,
                prepS = facts.prepS,
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
                warmup = facts.warmup,
                added = facts.added,
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
