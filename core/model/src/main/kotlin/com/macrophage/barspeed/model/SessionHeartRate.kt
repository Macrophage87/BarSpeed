package com.macrophage.barspeed.model

/**
 * A session's heart-rate summary: the mean of its sets' average heart rates,
 * the highest of their maxima, and the session HRV (#62).
 *
 * NOT A MEAN OVER SAMPLES, and the difference is measured (#296). Each set
 * counts once however long it ran, and each set's figure is itself already
 * truncated to a whole beat (`HrStreamSummary.avgBpm`), so the session mean
 * truncates truncated values. On field-42 (app 0.1.52) the published 99 is
 * the mean of sixteen per-set figures, 99.25, truncated; the unfloored set
 * means average 99.83, and the true mean over all 1528 in-set samples is
 * 100.34. Kept as it is, and described, rather than redefined: the close
 * freezes the figure on the session row, a redefinition would give one key
 * two meanings across the archive, and 1.3 beats on a session average is a
 * difference the issue itself says decides nothing. A reader wanting the
 * mean over samples has every in-set `hrm` stream in the raw archive.
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
 * round -- so [of] takes the derived HRV from its caller as a value, and
 * publishes it only for an unclosed session. The one mapping from rows and
 * streams to this rule is `SessionEntity.heartRate` in `:core:data`, which
 * passes `SessionHrv.rmssdMs` over the session's stored windows and reads
 * those windows only for an unclosed session. A derived HRV is
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
         * no figure -- and are returned exactly as stored, whatever
         * [derivedHrvRmssdMs] holds. An unclosed row was never summarised, so
         * its stored columns are not an answer: the two heart rates are
         * derived from the set rows by [aggregate], and the HRV is
         * [derivedHrvRmssdMs], null where the caller has no figure.
         *
         * Keyed on the end time rather than on a null column because a
         * closed session whose sets carried no heart rate also stores nulls,
         * and a reader must not replace the close's answer with a later
         * reading of rows that may have changed since.
         *
         * A value and not a function: this module is compiled for JVM 21 and
         * `:core:data` for 17, and an inline function cannot be inlined across
         * that boundary, so a caller cannot defer a coroutine's stream read
         * into this call. The caller computes the derived HRV only where the
         * row has no end time; see `SessionEntity.heartRate`.
         */
        fun of(
            closed: Boolean,
            storedAvgBpm: Int?,
            storedMaxBpm: Int?,
            storedHrvRmssdMs: Double?,
            setAvgBpm: List<Int?>,
            setMaxBpm: List<Int?>,
            derivedHrvRmssdMs: Double?,
        ): SessionHeartRate = if (closed) {
            SessionHeartRate(storedAvgBpm, storedMaxBpm, storedHrvRmssdMs)
        } else {
            aggregate(setAvgBpm, setMaxBpm).copy(hrvRmssdMs = derivedHrvRmssdMs)
        }
    }
}
