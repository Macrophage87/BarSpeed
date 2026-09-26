package com.macrophage.barspeed.model

/**
 * A session's heart-rate summary: the mean of its sets' average heart rates
 * and the highest of their maxima (#62).
 *
 * ONE RULE FOR THE WRITER AND ITS READERS. `SessionRepository.endSession`
 * writes [aggregate] onto the session row at the close. A reader that took
 * the row's two columns and nothing else publishes no session heart rate for
 * a session that never reached `endSession` -- the lifter left without
 * finishing, or the process died -- although every input is a set row that
 * was already durable (#62). [of] is the read: the stored pair where the
 * close wrote one, and otherwise [aggregate] over the same set rows, so the
 * figure derived for an unclosed session is the figure the close would have
 * written over those rows.
 *
 * WHAT IT COVERS. Only `hrAvgBpm` and `hrMaxBpm`. `hrvRmssd_ms` is
 * published only from a close; a derived block never carries it. Deriving it
 * from the stored hrm and rest_before_hrm streams is #62 half (b), measured
 * and not built.
 *
 * WHAT IT DOES NOT DO. It does not say whether a strap was worn: a set's
 * `hrAvgBpm` is whatever the set row stored. Withholding the session block
 * when a session has sets and none of them publishes an `hr` block (#83) is
 * the exporter's gate, not this rule's.
 */
data class SessionHeartRate(val avgBpm: Int?, val maxBpm: Int?) {
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
         * time and the two stored columns have one writer, `endSession`,
         * which writes all three in one update, so a closed row's
         * [storedAvgBpm] and [storedMaxBpm] are that close's answer -- a null
         * included, which there means no set carried a figure when it read
         * them -- and are returned exactly as stored. An unclosed row was
         * never summarised, so its stored columns are not an answer, and the
         * summary is derived from the set rows by [aggregate].
         *
         * Keyed on the end time rather than on a null column because a
         * closed session whose sets carried no heart rate also stores nulls,
         * and a reader must not replace the close's answer with a later
         * reading of rows that may have changed since.
         */
        fun of(
            closed: Boolean,
            storedAvgBpm: Int?,
            storedMaxBpm: Int?,
            setAvgBpm: List<Int?>,
            setMaxBpm: List<Int?>,
        ): SessionHeartRate = if (closed) {
            SessionHeartRate(storedAvgBpm, storedMaxBpm)
        } else {
            aggregate(setAvgBpm, setMaxBpm)
        }
    }
}
