package com.macrophage.barspeed.data

import com.macrophage.barspeed.hrm.SessionHrv
import com.macrophage.barspeed.model.HrSample
import com.macrophage.barspeed.model.SessionHeartRate

/**
 * This session's heart-rate summary as a reader should publish it (#62):
 * [SessionHeartRate.of] over this row and [sets], which must be this
 * session's own set rows.
 *
 * The one mapping from rows to the rule, for every reader of a session's
 * heart rate, so no two readers can pick a different column or a different
 * end-time test. Pure: it reads the rows it is handed and nothing else.
 *
 * The HRV read here is the stored column only; an unclosed session gets none.
 * The overload taking the session's stored heart-rate windows is the one that
 * derives it.
 */
fun SessionEntity.heartRate(sets: List<SetRecordEntity>): SessionHeartRate = heartRate(sets) { emptyList() }

/**
 * This session's heart-rate summary, its HRV included (#62 half (b)):
 * [SessionHeartRate.of] over this row, [sets] and the session's stored
 * heart-rate windows.
 *
 * [hrWindows] returns the windows `SessionHrv.rmssdMs` takes, in session
 * order: [SessionRepository.storedHrWindows] is the one production source.
 * It is called only for an unclosed session, the only kind whose HRV the rule
 * publishes from a derivation; a closed session publishes the figure its
 * close stored and reads no stream. The rule decides what is published
 * either way -- this check only spares a closed session a read the rule would
 * discard. Inline, within this module, so that [hrWindows] can read the
 * database from a coroutine.
 */
inline fun SessionEntity.heartRate(
    sets: List<SetRecordEntity>,
    hrWindows: () -> List<List<HrSample>>,
): SessionHeartRate {
    val closed = endedAtMs != null
    return SessionHeartRate.of(
        closed = closed,
        storedAvgBpm = hrAvgBpm,
        storedMaxBpm = hrMaxBpm,
        storedHrvRmssdMs = hrvRmssdMs,
        setAvgBpm = sets.map { it.hrAvgBpm },
        setMaxBpm = sets.map { it.hrMaxBpm },
        derivedHrvRmssdMs = if (closed) null else SessionHrv.rmssdMs(hrWindows()),
    )
}
