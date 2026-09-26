/**
 * A session's heart-rate summary: the mean of its sets' average heart rates,
 * the highest of their maxima, and the session HRV (#62).
 *
 * ONE RULE FOR THE WRITER AND ITS READERS. `SessionRepository.endSession`
 * writes [aggregate] onto the session row at the close, with the HRV the
 * close computed. A reader that took the row's columns and nothing else
 * publishes no session heart rate for a session that never reached
 * `endSession` -- the lifter left without finishing, or the process died --
 * although every input to the two heart-rate figures is a set row that was
 * already durable (#62). [of] is the read: what the close stored where it
 * ran, and otherwise a derivation, so the figures derived for an unclosed
 * session are the figures the close would have written over those rows.
 *
 * THE HRV IS DERIVED FROM STREAMS, NOT FROM SET ROWS. No set row carries an
 * HRV. The close computes one over the R-R intervals it received while the
 * session ran, and the only durable copy of those intervals is the stored
 * heart-rate streams. This rule cannot read streams -- `:core:hrm`, where the
 * close's ingest and RMSSD live, depends on this module and not the other way
 * round -- so [of] takes the derived HRV from its caller as a function, and
 * calls it only for an unclosed session. The one mapping from rows and streams
 * to this rule is `SessionEntity.heartRate` in `:core:data`, which passes
 * `SessionHrv.rmssdMs` over the session's stored windows. A derived HRV is
 * computed from stored streams, not from the intervals the close received;
 * `SessionHrv` states how closely the two agreed where both exist.
 *
 * WHAT IT DOES NOT DO. It does not say whether a strap was worn: a set's
 * `hrAvgBpm` is whatever the set row stored. Withholding the session block
 * when a session has sets and none of them publishes an `hr` block (#83) is
 * the exporter's gate, not this rule's.
 */
data class SessionHeartRate(val avgBpm: Int?, val maxBpm: Int?, val hrvRmssdMs: Double? = null) {
    companion object {
        /**
         * The summary over a session's set rows, as `endSession` computes it.
         *
         * [setAvgBpm] and [setMaxBpm] are every set row's stored column,
         * nulls included; a null is a set with no figure and is skipped rather
         * than counted as 0. The two lists are aggregated independently, so
         * they need not be aligned.
         *
         * The mean is of the per-set averages, unweighted by how long each
         * set lasted, and TRUNCATED toward zero by `toInt()`, not rounded:
         * that is what `endSession` wrote before #62, and a rounding rule
         * here would make a derived figure one beat higher than the close's
         * on every mean whose fractional part is above .5. Voided sets are
         * included: nothing here can see the mark, and `endSession` counts
         * them too.
         *
         * It carries no HRV: no set row holds one. `endSession` writes the
         * HRV it was handed beside these two.
         */
        fun aggregate(setAvgBpm: List<Int?>, setMaxBpm: List<Int?>): SessionHeartRate {
            val avgs = setAvgBpm.filterNotNull()
            return SessionHeartRate(
                avgBpm = if (avgs.isEmpty()) null else avgs.average().toInt(),
                maxBpm = setMaxBpm.filterNotNull().maxOrNull(),
            )
        }

        /**
         * What a reader publishes for a session.
         *
         * [closed] is whether the session row carries an end time. The end
         * time and the three stored columns have one writer, `endSession`,
         * which writes all four in one update, so a closed row's
         * [storedAvgBpm], [storedMaxBpm] and [storedHrvRmssdMs] are that
         * close's answer -- a null included, which there means the close had
         * no figure -- and are returned exactly as stored, and
         * [derivedHrvRmssdMs] is never called. An unclosed row was never
         * summarised, so its stored columns are not an answer: the two heart
         * rates are derived from the set rows by [aggregate], and the HRV is
         * whatever [derivedHrvRmssdMs] returns, null where it has no figure.
         *
         * Keyed on the end time rather than on a null column because a
         * closed session whose sets carried no heart rate also stores nulls,
         * and a reader must not replace the close's answer with a later
         * reading of rows that may have changed since.
         *
         * Inline so that a caller in a coroutine can read streams inside
         * [derivedHrvRmssdMs] and read them only when the session is unclosed.
         */
        inline fun of(
            closed: Boolean,
            storedAvgBpm: Int?,
            storedMaxBpm: Int?,
            storedHrvRmssdMs: Double?,
            setAvgBpm: List<Int?>,
            setMaxBpm: List<Int?>,
            derivedHrvRmssdMs: () -> Double?,
        ): SessionHeartRate = if (closed) {
            SessionHeartRate(storedAvgBpm, storedMaxBpm, storedHrvRmssdMs)
        } else {
            aggregate(setAvgBpm, setMaxBpm).copy(hrvRmssdMs = derivedHrvRmssdMs())
        }
    }
}
