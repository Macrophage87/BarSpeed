package com.macrophage.barspeed.dsp

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * THE CORPUS TABLE for issue #335: every committed IMU stream's live count
 * under v0.1.57's full-cycle rule -- the FALL rejection on for every lift --
 * and under the rule with [CycleRule.fallRejectsFor] deciding it, both built
 * BY THEIR PARTS and fed by the app's own tracker. So this table does not
 * move when production does; what production runs is asserted against it
 * elsewhere.
 *
 * Every capture in [CandidateCorpus.ALL] under the geometry it declares, and
 * every partner (`-imu-b`) stream under its base capture's geometry. The table
 * is printed whole; what is ASSERTED is the exact set of streams whose count
 * moves, each with its before and after, and the two totals -- so a stream
 * that moved without being named here reds.
 */
class FallRejectionCorpusTest {
    /** Calls [rule] makes over [fixture] under [direction]. */
    private fun count(fixture: String, direction: LiftDirection, rule: CycleRule): Int {
        val counter = CycleRepCounter(direction, rule)
        val tracker = StreamingSetTracker.forLift(direction)
        return LiveCountCandidates.load(fixture).count { sample ->
            counter.feed(tracker.feed(sample), sample.timestampMs) is RepCall.Speak
        }
    }

    private data class Row(val stream: String, val before: Int, val after: Int)

    private val rows: List<Row> by lazy {
        val streams = CandidateCorpus.ALL.map { it.fixture to it.direction } +
            FieldCorpus.partnersOnClasspath().map { partner ->
                partner to CandidateCorpus.capture(partner.removeSuffix("-imu-b")).direction
            }
        streams.map { (stream, direction) ->
            Row(
                stream,
                before = count(stream, direction, CycleRule(DspConfig(), fallRejects = true)),
                after = count(stream, direction, CycleRule(DspConfig(), CycleRule.fallRejectsFor(direction))),
            )
        }
    }

    @Test
    fun `the predicate moves the count on field-46's squats and one hold's pocket unit and nowhere else`() {
        println("stream | v0.1.57 | fallRejectsFor")
        rows.forEach { println("${it.stream} | ${it.before} | ${it.after}") }
        assertEquals(68 + 22, rows.size, "streams: 68 captures and 22 partners")
        assertEquals(
            mapOf(
                // Field-46's back squats, role a: v0.1.57 counted 5, 1, 2, 1
                // and 5; set 5 does not move.
                "field-backsquat-straight-s46-set01" to (5 to 6),
                "field-backsquat-straight-s46-set02" to (1 to 4),
                "field-backsquat-straight-s46-set03" to (2 to 5),
                "field-backsquat-straight-s46-set04" to (1 to 5),
                // Role b of the same sets; sets 1 and 5 do not move.
                "field-backsquat-straight-s46-set02-imu-b" to (2 to 5),
                "field-backsquat-straight-s46-set03-imu-b" to (1 to 4),
                "field-backsquat-straight-s46-set04-imu-b" to (1 to 5),
                // The second unit of a 45 s rope dead hang, declared
                // eccentric-first, which the owner says was in his pocket. A
                // timed set arms no live counter in the app, so no lifter
                // heard either figure.
                "field-ropedeadhang-hold45-s38-set18-imu-b" to (2 to 3),
            ),
            rows.filter { it.before != it.after }.associate { it.stream to (it.before to it.after) },
            "streams whose live count moves",
        )
        val captures = rows.take(68)
        assertEquals(387 to 398, captures.sumOf { it.before } to captures.sumOf { it.after }, "captures, total calls")
        val partners = rows.drop(68)
        assertEquals(115 to 126, partners.sumOf { it.before } to partners.sumOf { it.after }, "partners, total calls")
    }
}
