package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ImuSample
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What [LiveRepCaller] says over the thirteen mark-carrying captures, scored
 * against the marks. Issue #145.
 *
 * ## Two questions, and only one of them is about accuracy
 *
 * **Does it take a number back?** A count the lifter has heard is not
 * withdrawable. [LiveRepCaller.spoken] only rises, so the withdrawal is not
 * visible in it at all; [LiveRepCaller.contradicted] counts the samples on
 * which the detector held FEWER reps than the number already said, and the
 * only value of it that means anything is zero.
 *
 * **Is the number right?** Scored against the rep marks under a window rule.
 * The k-th call is right when its instant falls in the cycle ending at the
 * k-th mark: `(mark - cycle - tolerance, mark + tolerance]`, with the cycle
 * taken from that set's own marks ([RepMarks.cycleMs]) and the tolerance
 * [CueTrack.WINDOW_TOLERANCE_MS]. The window is the same width and the same
 * tolerance as [BatchCueCoverageTest]'s; it opens one cycle EARLIER because a
 * mark is the instant the guide called rep k complete while a call is made
 * when the drive of rep k ends, which is inside the cycle before it.
 *
 * ## What the marks can and cannot settle
 *
 * [RepMarkTrackTest] measures that every mark on this corpus is a row of the
 * same capture's cue track, so scoring against them is scoring against the
 * metronome. It says whether a call landed on the rep the GUIDE called, which
 * is the rep the lifter performed on 102 of these 103 marks -- session 37 set
 * 2 is the exception, 6 reps recorded against 7 marks.
 *
 * What it cannot settle is issue #145's actual subject. Every capture here is
 * a tempo set with a 1 to 4 second prescribed eccentric; a straight-rep set is
 * faster and its lowering may or may not ramp through the dead band the same
 * way. #145's own comment names that as a hypothesis and names the capture
 * that decides it. That capture does not exist.
 *
 * ## What the numbers say, and what follows from them
 *
 * 35 calls across the corpus, 11 of them landing in the right window, against
 * 103 marks. Four of the thirteen sets say nothing at all. Two speak only
 * right numbers -- both are the same overhead press, sets 3 and 4 of session
 * 37 -- and seven speak at least one wrong number. `what a lifter hears while
 * the detector undercounts a twelve-rep pulldown` and `the back squat names a
 * seventh rep on a six-rep set` write out what that sounds like.
 *
 * So this does NOT clear #145's bar, which its own comment states as zero
 * wrong numbers, and nothing in `:app` is un-gated on the strength of it. The
 * bottleneck is measured and it is not the pairing rule this branch unified:
 * the batch path over the same captures resolves 5 to 15 spans per set where
 * this resolves 0 to 8, on the same rule and a different velocity. That is
 * issue #94's live-tracker estimate, which #145 already names as its
 * prerequisite.
 */
class LiveRepCallCorpusTest {
    /** marks on the track, calls made, calls in the right window, samples contradicted. */
    private data class Row(val marks: Int, val calls: Int, val right: Int, val contradicted: Int)

    private fun load(n: String): List<ImuSample> = ImuCsv.decode(
        javaClass.getResourceAsStream("/$n.csv")!!.readBytes().decodeToString(),
    )

    private data class Scored(val calls: List<RepCall.Speak>, val contradicted: Int)

    private fun score(fixture: String, direction: LiftDirection): Scored {
        val tracker = StreamingSetTracker.forLift(direction)
        val caller = LiveRepCaller(direction)
        val calls = load(fixture).mapNotNull {
            caller.feed(tracker.feed(it), it.timestampMs) as? RepCall.Speak
        }
        return Scored(calls, caller.contradicted)
    }

    /**
     * The k-th call is right when it lands in the cycle ending at the k-th
     * mark.
     *
     * This scores a call by its ORDINAL, not by the number it speaks.
     * [RepCall.Speak] carries a running total that may SKIP -- its own KDoc
     * states that when two reps resolve between one sample and the next the
     * later number is spoken and the earlier is never said -- so on a skipping
     * set this credits a call against a mark it did not name. Issue #145's F1
     * criterion is the SPOKEN number, and discharging it needs
     * `marks[call.count - 1]`, which this file does not do.
     *
     * On today's corpus the two criteria cannot differ, because no capture
     * skips: `every call speaks its own ordinal` pins that, so this KDoc's
     * "cannot differ" is read off an assertion rather than off a memory of
     * having checked once.
     */
    private fun rightCount(fixture: String, calls: List<RepCall.Speak>): Int {
        val marks = RepMarks.read(fixture)
        val cycle = RepMarks.cycleMs(fixture)!!
        val tolerance = CueTrack.WINDOW_TOLERANCE_MS
        return calls.withIndex().count { (k, call) ->
            k < marks.size &&
                call.atTimestampMs > marks[k] - cycle - tolerance &&
                call.atTimestampMs <= marks[k] + tolerance
        }
    }

    private fun rows(): Map<String, Row> = LiveRepCallCorpus.ALL.associate { (fixture, direction) ->
        val scored = score(fixture, direction)
        fixture to Row(
            RepMarks.read(fixture).size,
            scored.calls.size,
            rightCount(fixture, scored.calls),
            scored.contradicted,
        )
    }

    /**
     * The headline pin. Not one number the lifter hears is withdrawn by the
     * detector later in the same set.
     *
     * Asserted per capture and not as a sum, because a sum of zeros and a
     * single capture carrying all of them read the same at the call site and
     * only one of them is the property.
     */
    @Test
    fun `no capture takes back a number the caller has spoken`() {
        assertEquals(
            LiveRepCallCorpus.ALL.associate { (f, _) -> f to 0 },
            rows().mapValues { it.value.contradicted },
        )
    }

    /**
     * Every figure, per capture, in one table.
     *
     * Four numbers per row rather than a rep total, for the reason
     * [BatchCueCoverageTest] gives at length: over- and under-counting cancel
     * in a total, so a change that improves one can be hidden by the other.
     */
    @Test
    fun `what the thirteen captures make the caller say`() {
        assertEquals(
            mapOf(
                "field-backsquat-4011-6rep-s36-set01" to Row(6, 7, 1, 0),
                "field-rdl-3010-10rep-s36-set05" to Row(10, 0, 0, 0),
                "field-legpress-single-2011-8rep-s36-set07" to Row(8, 8, 1, 0),
                "field-ohp-3010-6rep-s37-set02" to Row(7, 0, 0, 0),
                "field-ohp-prepinflated-s37-set03" to Row(7, 3, 3, 0),
                "field-ohp-prepinflated-s37-set04" to Row(5, 1, 1, 0),
                "field-bench-3010-6rep-s37-set05" to Row(6, 1, 0, 0),
                "field-bench-3010-6rep-s37-set06" to Row(6, 0, 0, 0),
                "field-pullup-3010-8rep-s37-set09" to Row(8, 2, 1, 0),
                "field-inclinepress-3010-12rep-s38-set02" to Row(12, 4, 3, 0),
                "field-ohp-3010-8rep-s38-set04" to Row(8, 0, 0, 0),
                "field-ohp-3010-8rep-s38-set05" to Row(8, 2, 0, 0),
                "field-latpulldown-1120-12rep-s38-set14" to Row(12, 7, 1, 0),
            ),
            rows(),
        )
    }

    /**
     * The corpus rolled up three ways, so the headline cannot be read off one
     * flattering row.
     *
     * A set that says NOTHING is counted apart from a set that says only right
     * numbers, because on a straight-rep set silence is the feature not
     * running: it is the safe outcome and it is not the asked-for one.
     */
    @Test
    fun `four sets say nothing, two speak only right numbers, seven speak a wrong one`() {
        val r = rows().values
        assertEquals(103, r.sumOf { it.marks })
        assertEquals(35, r.sumOf { it.calls })
        assertEquals(11, r.sumOf { it.right })
        assertEquals(4, r.count { it.calls == 0 })
        assertEquals(2, r.count { it.calls > 0 && it.right == it.calls })
        assertEquals(7, r.count { it.calls > 0 && it.right < it.calls })
    }

    /**
     * Every call speaks its own ordinal, on all thirteen captures.
     *
     * [rightCount] scores by ordinal while issue #145's F1 criterion is the
     * spoken number. The two can only diverge on a set whose running total
     * skips, and none of these skip. Pinned rather than remembered: the day
     * one of them skips is the day the table above starts crediting a call
     * against a mark it did not name, and nothing else here would say so.
     *
     * A capture that says nothing contributes no rows, so this is a
     * conditional property and not a coverage claim; the table above is what
     * says how many calls there are.
     */
    @Test
    fun `every call speaks its own ordinal`() {
        val offBy = LiveRepCallCorpus.ALL.flatMap { (fixture, direction) ->
            score(fixture, direction).calls
                .mapIndexed { k, call -> Triple(fixture, k + 1, call.count) }
                .filter { it.second != it.third }
        }
        assertEquals(emptyList(), offBy)
    }

    /**
     * What the lifter hears, spelled out, on the set where the gap is widest.
     *
     * Session 38 set 14: twelve lat pulldowns, marks every four seconds from
     * 14.015 s. The voice says "one" at 10.500 s -- 3.5 s before the guide
     * called its first rep over -- then "two" 1.290 s later, both of them
     * before that first mark. Then nothing for 32 s. It resumes at 43.770 s
     * with "three", 1.725 s after the guide called rep 8 over at 42.045 s --
     * inside the guide's ninth cycle, five reps behind the metronome -- and
     * ends the set on "seven" against twelve marks.
     *
     * Every instant here is the guide's or the caller's. What the LIFTER was
     * doing at any of them is not readable from a metronome track and is not
     * claimed.
     *
     * That is the answer to what an undercount sounds like, and it is why a
     * skipped number is NOT recoverable here: the count never re-syncs, so
     * every number after the first miss is low by the same amount and is said
     * with no less confidence than a right one. Issue #145's design default 1
     * claimed the opposite and its own comment already retracted it; this is
     * the measurement behind the retraction, on a different corpus and a
     * different rule.
     *
     * Instants are seconds from the capture's first sample, to the
     * millisecond, so a change of one sample in where a call lands fails this.
     */
    @Test
    fun `what a lifter hears while the detector undercounts a twelve-rep pulldown`() {
        val fixture = "field-latpulldown-1120-12rep-s38-set14"
        val direction = LiveRepCallCorpus.ALL.first { it.first == fixture }.second
        val t0 = load(fixture).first().timestampMs
        assertEquals(
            listOf(
                1 to 10.500,
                2 to 11.790,
                3 to 43.770,
                4 to 46.801,
                5 to 50.942,
                6 to 55.170,
                7 to 61.860,
            ),
            score(fixture, direction).calls.map { it.count to (it.atTimestampMs - t0) / 1000.0 },
        )
        assertEquals(12, RepMarks.read(fixture).size)
    }

    /**
     * The other direction, on the corpus's only 4011 set.
     *
     * Session 36 set 1: six back squats, marks every six seconds from 15.0 s.
     * The voice makes SEVEN calls, and the seventh lands at 44.670 s -- inside
     * the window of the sixth and last mark, at 45.036 s. So the lifter racking
     * their last rep hears "seven".
     *
     * Over-counting and under-counting are not symmetric to a lifter running a
     * velocity-loss stop: a low count leaves them doing extra reps, a high one
     * stops them early. Both are wrong numbers and this file's `right` column
     * treats them alike; the difference is the reason the roll-up above counts
     * sets, not reps.
     */
    @Test
    fun `the back squat names a seventh rep on a six-rep set`() {
        val fixture = "field-backsquat-4011-6rep-s36-set01"
        val direction = LiveRepCallCorpus.ALL.first { it.first == fixture }.second
        val t0 = load(fixture).first().timestampMs
        val calls = score(fixture, direction).calls
        assertEquals(7, calls.size)
        assertEquals(7, calls.last().count)
        assertEquals(44.670, (calls.last().atTimestampMs - t0) / 1000.0)
        assertEquals(6, RepMarks.read(fixture).size)
        assertEquals(45.036, (RepMarks.read(fixture).last() - t0) / 1000.0)
    }
}
