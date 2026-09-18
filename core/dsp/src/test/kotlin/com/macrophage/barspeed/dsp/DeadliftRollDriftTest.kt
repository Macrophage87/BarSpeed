package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.StartPhase
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Does the live integrator's drift track how far the mount rolled? Measured per
 * set and per unit, for the question the owner asked on issue #301.
 *
 * ## The question, and why it was worth asking
 *
 * Both field-43 units were on the bar's COLLARS, either side -- the owner's
 * words, not an inference. A deadlift bar's sleeves rotate in the collars, so a
 * unit on a collar rides a spinning sleeve, and a sensor frame rotating about
 * the bar axis while the bar is on the floor is a plausible source of the
 * integrator drift: the gravity vector moves in the sensor frame and any
 * imperfect compensation leaks into the vertical estimate. The owner asked
 * whether the drift correlates with roll rate per set, and whether a unit at the
 * bar's CENTRE would have counted.
 *
 * ## What this file measures and what it cannot
 *
 * It measures, per stream: roll excursion through [RollExcursion], the roll RATE
 * two independent ways, the live count, and the largest velocity the shipped
 * integrator publishes while the sensor is STILL by its own quiet test -- which
 * is the drift, in the units the lifter would have read.
 *
 * **The centre-mount half is unanswerable here and stays `[Field]`.** Neither
 * unit was at the bar's centre, so no committed capture holds a centre mount on
 * any lift. Nothing in this repository can produce one; only a session with the
 * magnet at the bar's centre can, which is item 1 of issue #301's field
 * protocol.
 *
 * Nor does this observe hardware. `roll_deg` is the sensor's own fused attitude
 * estimate and `wx_dps` its own rate reading; [RollExcursion]'s KDoc records
 * that roll is ill-conditioned near |pitch| 90 degrees, so some of a large
 * excursion can be the estimate's representation moving rather than the sleeve
 * turning. What is claimed below is what the STREAMS report and what the code
 * computes from them.
 */
class DeadliftRollDriftTest {
    private fun load(fixture: String): List<ImuSample> = ImuCsv.decode(
        javaClass.getResourceAsStream("/$fixture.csv")!!.readBytes().decodeToString(),
    )

    private val deadlift = LiftDirection(startsWith = StartPhase.CONCENTRIC)

    private val sets = listOf(
        "field-deadlift-straight-5rep-s43-set04",
        "field-deadlift-straight-5rep-s43-set05",
        "field-deadlift-straight-5rep-s43-set06",
    )

    private fun roles(fixture: String) = listOf("a" to fixture, "b" to "$fixture-imu-b")

    /** Unwrapped roll range over the whole capture; no deadlift track has a terminal cue (#285). */
    private fun excursionDeg(stream: String): Double =
        RollExcursion.of(load(stream), workStartedAtMs = null, end = SetEnd.NotCued)!!.degrees

    private fun median(values: List<Double>): Double = values.sorted()[values.size / 2]

    /** |wx|, the sensor's own roll-axis rate reading, deg/s. */
    private fun gyroRollRate(stream: String): List<Double> = load(stream).map { abs(it.wxDps) }

    /**
     * |d(unwrapped roll)/dt| on the reconstructed uniform clock, deg/s -- the
     * rate implied by the ATTITUDE estimate rather than reported by the gyro.
     *
     * Both are measured because they are different signals and either could be
     * the one that matters: a fused attitude can sweep where the gyro reads
     * little (the ill-conditioning [RollExcursion] documents) and the gyro can
     * read a burst the attitude smooths away.
     */
    private fun attitudeRollRate(stream: String): List<Double> {
        val samples = load(stream)
        val rateHz = (samples.size - 1) /
            ((samples.last().timestampMs - samples.first().timestampMs) / 1000.0)
        return RollExcursion.unwrap(samples.map { it.rollDeg }).zipWithNext { a, b -> abs(b - a) * rateHz }
    }

    /**
     * The largest |live velocity| published while the sensor is still, m/s, and
     * the second it happens.
     *
     * "Still" is `VelocityEstimator.isQuietSample` held for at least
     * `minStationaryS` -- the shipped predicate and the shipped duration, so this
     * is the integrator's own reading over the windows the integrator itself
     * treats as candidates for a zero. On a dead-stop deadlift those windows are
     * the bar sitting on the floor.
     */
    private fun driftAtRest(stream: String): Pair<Double, Double> {
        val samples = load(stream)
        val config = DspConfig()
        val rateHz = (samples.size - 1) /
            ((samples.last().timestampMs - samples.first().timestampMs) / 1000.0)
        val quiet = samples.map { VelocityEstimator.isQuietSample(it, config) }
        val inWindow = BooleanArray(samples.size)
        var i = 0
        while (i < quiet.size) {
            if (!quiet[i]) {
                i++
                continue
            }
            var j = i
            while (j + 1 < quiet.size && quiet[j + 1]) j++
            if ((j - i) / rateHz >= config.minStationaryS) for (k in i..j) inWindow[k] = true
            i = j + 1
        }
        val tracker = StreamingSetTracker.forLift(deadlift)
        var worst = 0.0
        var worstAtS = 0.0
        samples.forEachIndexed { index, sample ->
            val state = tracker.feed(sample)
            if (inWindow[index] && abs(state.velocityMps) > abs(worst)) {
                worst = state.velocityMps
                worstAtS = state.elapsedS
            }
        }
        return worst to worstAtS
    }

    private fun liveCount(stream: String): Int {
        val tracker = StreamingSetTracker.forLift(deadlift)
        var last = LiveSetState()
        for (sample in load(stream)) last = tracker.feed(sample)
        return last.repCount
    }

    /**
     * The table the owner's question needs: roll, roll rate, count and drift,
     * per set and per unit.
     *
     * Role b has the smaller roll EXCURSION on all three sets, by a factor of
     * 5 to 21, and role b counts FEWER reps -- 0, 0, 3 against 3, 1, 2. So on
     * this session the quieter frame did not count better and the misses do not
     * track roll excursion.
     *
     * **The two roll measures do not order the units the same way, and that is
     * the finding, not a footnote.** On set 4 role a sweeps 367.2 degrees while
     * its own gyro reads a MEDIAN |wx| of 0.427 deg/s and a peak of 23.9,
     * against role b's 0.549 median and 59.6 peak over 17.2 degrees of
     * excursion: the unit reporting the larger sweep is the one whose rate
     * reading is quieter. The attitude-implied rate peaks at 1857.7, 5613.9 and
     * 3689.0 deg/s on role a, far beyond anything the gyro reports and beyond
     * any rate a barbell sleeve turns at, which is `RollExcursion`'s documented
     * ill-conditioning near |pitch| 90 degrees showing up in this corpus: part
     * of role a's excursion is the fused estimate's representation moving, not
     * the sleeve. So "role a rolled more" is a statement about the reported
     * attitude and NOT an established statement about the mount.
     */
    @Test
    fun `the low-roll unit counts fewer reps, and the two roll measures disagree`() {
        println("set | role | excursion deg | wx med | wx peak | att med | att peak | live | drift m/s | at s")
        val measured = sets.flatMap { fixture ->
            roles(fixture).map { (role, stream) ->
                val gyro = gyroRollRate(stream)
                val attitude = attitudeRollRate(stream)
                val (drift, driftAtS) = driftAtRest(stream)
                val row = listOf(
                    fixture.takeLast(2),
                    role,
                    round(excursionDeg(stream), 1),
                    round(median(gyro), 3),
                    round(gyro.max(), 1),
                    round(median(attitude), 3),
                    round(attitude.max(), 1),
                    liveCount(stream).toString(),
                    round(drift, 2),
                    round(driftAtS, 2),
                )
                println(row.joinToString(" | "))
                Triple(role, excursionDeg(stream), drift)
            }
        }
        val roleA = measured.filter { it.first == "a" }
        val roleB = measured.filter { it.first == "b" }
        assertEquals(
            listOf(367.2, 84.3, 52.5),
            roleA.map { round(it.second, 1).toDouble() },
            "role a's roll excursion, set by set",
        )
        assertEquals(
            listOf(17.2, 11.9, 9.5),
            roleB.map { round(it.second, 1).toDouble() },
            "role b's roll excursion, set by set",
        )
        assertEquals(
            listOf(0.427, 0.855, 0.671),
            sets.map { round(median(gyroRollRate(it)), 3).toDouble() },
            "role a's median |wx|, deg/s, set by set",
        )
        assertEquals(
            listOf(0.549, 0.488, 0.427),
            sets.map { round(median(gyroRollRate("$it-imu-b")), 3).toDouble() },
            "role b's median |wx|, deg/s -- HIGHER than role a's on set 4",
        )
        assertEquals(
            listOf(23.9, 161.3, 95.6),
            sets.map { round(gyroRollRate(it).max(), 1).toDouble() },
            "role a's peak |wx|, deg/s, set by set",
        )
        assertEquals(
            listOf(59.6, 55.8, 51.5),
            sets.map { round(gyroRollRate("$it-imu-b").max(), 1).toDouble() },
            "role b's peak |wx|, deg/s -- HIGHER than role a's on set 4",
        )
        roleA.indices.forEach { i ->
            assertTrue(
                roleB[i].second < roleA[i].second,
                "set ${i + 4}: role b rolled ${roleB[i].second} against role a's ${roleA[i].second}",
            )
        }
        assertEquals(listOf(3, 1, 2), sets.map { liveCount(it) }, "role a's live count, set by set")
        assertEquals(listOf(0, 0, 3), sets.map { liveCount("$it-imu-b") }, "role b's live count, set by set")
    }

    /**
     * The drift figures, pinned per stream, and the comparison that answers the
     * question: the LOW-roll unit drifts at least as far as the high-roll unit
     * on every set. Whatever the missing zero is, it is not the sleeve spin.
     *
     * **A correction to a figure this issue has been carrying.** The field-43
     * session chat reported "+1.95 and +2.41 m/s with the bar motionless on the
     * floor" as set 6's, on role a. Under the rule stated in [driftAtRest] --
     * the largest |velocity| the shipped integrator publishes inside a window
     * its own quiet test holds for `minStationaryS` -- set 6 role a measures
     * 3.144 m/s and it is SET 4 role a that measures 1.944. So that pair does
     * not re-derive as set 6's; the six figures below are the ones with code
     * behind them and the pair is not requoted.
     *
     * Two of the six worst instants fall in the tail rather than between reps --
     * 04b at 29.27 s and 05a at 20.59 s, after each set's last drive -- which is
     * the bar being put down and handled with no anchor left to catch it. That is
     * part of the same failure and is named rather than trimmed away, because
     * trimming to the working window would report a smaller drift than the
     * integrator actually reached.
     */
    @Test
    fun `the drift at rest is at least as large on the low-roll unit`() {
        val perStream = sets.flatMap { fixture ->
            roles(
                fixture,
            ).map { (role, stream) -> "${fixture.takeLast(2)}$role" to driftAtRest(stream) }
        }
        perStream.forEach {
            println(
                "${it.first} drift ${round(it.second.first, 3)} m/s at ${round(it.second.second, 2)} s",
            )
        }
        assertEquals(
            listOf("04a", "04b", "05a", "05b", "06a", "06b"),
            perStream.map { it.first },
            "streams, in order",
        )
        assertEquals(
            listOf(1.94, 4.3, 4.25, 4.49, 3.14, 3.16),
            perStream.map { round(it.second.first, 2).toDouble() },
            "drift at rest, m/s, signed, stream by stream",
        )
        val byRole = perStream.chunked(2)
        byRole.forEach { (a, b) ->
            assertTrue(
                abs(b.second.first) >= abs(a.second.first),
                "${a.first}: role b drifts ${b.second.first} against role a's ${a.second.first}",
            )
        }
    }

    private fun round(value: Double, places: Int): String {
        val scale = generateSequence(1.0) { it * 10 }.elementAt(places)
        return (Math.round(value * scale) / scale).toString()
    }
}
