package com.macrophage.barspeed.data

import com.macrophage.barspeed.model.SessionHeartRate

/**
 * This session's heart-rate summary as a reader should publish it (#62):
 * [SessionHeartRate.of] over this row and [sets], which must be this
 * session's own set rows.
 *
 * The one mapping from rows to the rule, for every reader of a session's
 * heart rate, so no two readers can pick a different column or a different
 * end-time test. Pure: it reads the rows it is handed and nothing else.
 */
fun SessionEntity.heartRate(sets: List<SetRecordEntity>): SessionHeartRate = SessionHeartRate.of(
    closed = endedAtMs != null,
    storedAvgBpm = hrAvgBpm,
    storedMaxBpm = hrMaxBpm,
    setAvgBpm = sets.map { it.hrAvgBpm },
    setMaxBpm = sets.map { it.hrMaxBpm },
)
