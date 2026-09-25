package com.macrophage.barspeed.dsp

/**
 * The per-rep truth for the eight deadlift sets, and the rule a call is
 * scored by.
 *
 * ## The windows are batch drive spans, labelled by a human
 *
 * Field-43's three sets reuse [CandidateCorpus.DEADLIFT_WINDOWS] unchanged.
 * Field-44's five are `RepSegmenter.segmentDetailed`'s concentric spans on
 * role a under the declared geometry, on the batch clock, re-derived by
 * `ClosingRuleCandidateTest` so no bound here is typed from memory. Each is
 * labelled against the capture's skeleton (drives, brakes, floor contacts,
 * #305's lens A) and the owner's settled count:
 *
 * - REP: a completed rep. Where the batch split one lift into two spans they
 *   are merged -- field-44 set 4's first lift, as #301's analysis merged
 *   field-43 set 4's reps 3 and 4.
 * - FAILED: field-44 set 5's third pull, *"about halfway up. Grip failed."*
 * - NOT_REP: a span the batch published that is no rep -- set 3's 119 s of
 *   plate handling, bounces off the floor after a contact, set 4's bar ROLL at
 *   30.8 s, the set-downs.
 *
 * Batch spans are velocity-derived, so a window can open early or late against
 * the pull: field-44 set 4's first REP opens at 6.86 s, 1.5 s before the bar
 * leaves the floor, because the batch integrator drifted from a knock at 6.80 s.
 * These are the finest per-rep truth the repository has for a straight-reps
 * set, and #145's F1 -- marks that are the lifter's own taps -- is still what
 * would replace them.
 *
 * ## The scoring rule
 *
 * A call is matched by the DRIVE it closes ([RepClosed]) to the nearest window
 * -- 0 when they overlap, the larger overlap on a tie -- within
 * [CandidateCorpus.CALL_TOLERANCE_S], the tolerance #301's harness used. A
 * first match on a REP counts it; a second is a phantom (a double); a match on
 * NOT_REP, or on nothing, is a phantom; a match on FAILED is the failed attempt
 * CALLED. The reported lag is the call instant minus the matched window's end.
 */
internal object DeadliftTruth {
    enum class Kind { REP, FAILED, NOT_REP }

    data class Window(val startS: Double, val endS: Double, val kind: Kind)

    const val S44 = "field-deadlift-straight-"

    /** The eight sets, field-44 first in set order, then field-43. */
    val SETS = listOf(
        "${S44}5rep-s44-set01",
        "${S44}5rep-s44-set02",
        "${S44}5rep-s44-set03",
        "${S44}4rep-s44-set04",
        "${S44}2rep-s44-set05",
        "field-deadlift-straight-5rep-s43-set04",
        "field-deadlift-straight-5rep-s43-set05",
        "field-deadlift-straight-5rep-s43-set06",
    )

    private fun rep(a: Double, b: Double) = Window(a, b, Kind.REP)

    private fun not(a: Double, b: Double) = Window(a, b, Kind.NOT_REP)

    /** Field-44's labelled spans; `ClosingRuleCandidateTest` asserts each bound against the batch path. */
    val FIELD_44 = mapOf(
        "${S44}5rep-s44-set01" to listOf(
            rep(4.77, 5.63), rep(7.49, 8.19), rep(9.61, 11.73), rep(13.48, 14.15), rep(15.68, 18.00),
            not(18.84, 20.44),
        ),
        "${S44}5rep-s44-set02" to listOf(
            rep(4.75, 5.59), not(5.62, 6.27), rep(6.99, 8.70), rep(10.72, 11.70), rep(14.54, 16.04),
            rep(18.37, 19.48), not(20.83, 22.19),
        ),
        "${S44}5rep-s44-set03" to listOf(
            not(37.99, 40.59), not(44.92, 52.39), not(56.19, 58.24), not(72.22, 77.32), not(88.74, 92.01),
            rep(119.94, 120.67), rep(121.98, 123.54), not(123.58, 124.26), rep(124.31, 125.47),
            rep(126.70, 128.48), rep(129.35, 130.96), not(131.20, 131.51), not(132.26, 133.59),
        ),
        "${S44}4rep-s44-set04" to listOf(
            rep(6.86, 10.38), not(11.14, 12.35), rep(13.27, 14.78), rep(16.07, 20.48), not(21.87, 22.85),
            not(30.83, 33.26), rep(48.16, 49.96),
        ),
        "${S44}2rep-s44-set05" to listOf(
            rep(4.64, 7.10), not(8.24, 9.94), rep(10.29, 12.91), Window(15.68, 18.30, Kind.FAILED),
        ),
    )

    /** Every set's windows: field-44 above, field-43 from [CandidateCorpus.DEADLIFT_WINDOWS]. */
    val WINDOWS: Map<String, List<Window>> = FIELD_44 + CandidateCorpus.DEADLIFT_WINDOWS.mapValues { (_, list) ->
        list.map { Window(it.startS, it.endS, if (it.isRep) Kind.REP else Kind.NOT_REP) }
    }

    data class Score(
        val counted: Int,
        val missed: Int,
        val phantom: Int,
        val failedCalled: Int,
        val lagsS: List<Double>,
    ) {
        operator fun plus(o: Score) = Score(
            counted + o.counted,
            missed + o.missed,
            phantom + o.phantom,
            failedCalled + o.failedCalled,
            lagsS + o.lagsS,
        )

        override fun toString(): String = "$counted/$missed/$phantom${if (failedCalled > 0) " F$failedCalled" else ""}"

        companion object {
            val ZERO = Score(0, 0, 0, 0, emptyList())
        }
    }

    fun score(fixture: String, calls: List<RepClosed>): Score {
        val windows = WINDOWS.getValue(fixture)
        val hit = BooleanArray(windows.size)
        var phantom = 0
        var failed = 0
        val lags = mutableListOf<Double>()
        for (call in calls) {
            val gaps = windows.map { w ->
                when {
                    call.driveEndS < w.startS -> w.startS - call.driveEndS
                    call.driveStartS > w.endS -> call.driveStartS - w.endS
                    else -> 0.0
                }
            }
            val overlaps = windows.map { w -> minOf(call.driveEndS, w.endS) - maxOf(call.driveStartS, w.startS) }
            val nearest = windows.indices.minWith(compareBy<Int> { gaps[it] }.thenByDescending { overlaps[it] })
            val w = windows[nearest]
            when {
                gaps[nearest] > CandidateCorpus.CALL_TOLERANCE_S || w.kind == Kind.NOT_REP -> phantom++
                w.kind == Kind.FAILED -> failed++
                hit[nearest] -> phantom++
                else -> {
                    hit[nearest] = true
                    lags += call.atS - w.endS
                }
            }
        }
        val reps = windows.indices.filter { windows[it].kind == Kind.REP }
        return Score(reps.count { hit[it] }, reps.count { !hit[it] }, phantom, failed, lags)
    }
}
