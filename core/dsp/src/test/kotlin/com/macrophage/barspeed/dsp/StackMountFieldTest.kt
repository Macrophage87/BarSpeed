package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.AnalysedRoleBasis
import com.macrophage.barspeed.model.AnalysedRolePolicy
import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.SensorRole
import com.macrophage.barspeed.model.StackMountSignal
import com.macrophage.barspeed.model.StartPhase
import com.macrophage.barspeed.model.Tempo
import com.macrophage.barspeed.model.VoiceCue
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which of two real units a stack-declared set should be read from, measured on
 * seven committed pairs. Issue #278.
 *
 * ## The set this exists for
 *
 * Field-42's three seated cable rows were recorded with two units, declared
 * `sensorOnStack` true, and analysed from the unit clipped to the rotating
 * HANDLE: they published 3, 4 and 2 reps of the 8 the lifter performed. The
 * partner sat on the stack. Nothing in either document says which unit was
 * where -- there is no per-role mount field -- so the mount was inferred from
 * the exercise, and the inference was wrong by one unit.
 *
 * ## Provenance
 *
 * Seven pairs, lifted unedited from two archives with their `-cues.csv` and
 * `-prep.csv` sidecars. Each `-imu-b.csv` is the second unit's view of the same
 * set as its base capture. Read from each session's own `meta.json`:
 *
 * - field-42, app 0.1.52, WitMotion WT901BLECL, epoch 2026-09-07T09:39:39.465Z,
 *   America/New_York: sets 8, 9 and 10 `seated_cable_row` at 34.02, 40.82 and
 *   47.63 kg, 8 reps of 8 planned, tempo `3010`, 5 s prep, `startsWith`
 *   concentric, `concentric` up, plane HORIZONTAL, `sensorOnStack` true,
 *   `sensorInverted` false, `travelRatio` 1.0; set 11 `assisted_pull_up` at
 *   22.579 kg over 115.569 kg of body weight, 8 reps of 8, tempo `3010`, plane
 *   VERTICAL, same mount declaration; set 13 the same exercise at tempo `4010`,
 *   8 reps of 8.
 * - field-41, app 0.1.52, same sensor model, epoch 2026-09-11T10:21:49.960Z:
 *   set 16 `triceps_pushdown` at 13.61 kg, 14 reps of 14, tempo `1120`,
 *   `concentric` down, plane VERTICAL, `sensorOnStack` true, `sensorInverted`
 *   false; set 18 `lat_pulldown` at 34.02 kg, 12 reps of 12, tempo `1120`,
 *   `concentric` down, `sensorOnStack` AND `sensorInverted` true.
 *
 * Every roll figure asserted below equals the `rollExcursion_deg` that
 * session's `meta.json` published for that role, to the one decimal place the
 * document carries. That agreement is the cross-check: this classpath and the
 * build that recorded the sets compute the same figure over the same window.
 *
 * ## What is NOT claimed
 *
 * WHICH UNIT WAS ON THE STACK IS THE OWNER'S WORD, not a recorded fact -- that
 * is the gap #278 names. What is measured here is what each STREAM did. The rep
 * counts are this classpath's arithmetic over these captures; the "performed"
 * counts are the lifter's own manual entries from `meta.json`.
 *
 * THE FLIP IS NOT UNIFORMLY KINDER TO THE REP COUNT, and both directions are
 * pinned rather than only the flattering one. The rows go from 3, 4 and 2 of 8
 * to 9, 9 and 9; the pulldown goes from 13 to 12 of 12; and the two assisted
 * pull-ups go the other way, 9 to 10 of 8 and 9 to 5 of 8. The criterion is
 * which unit the DECLARATION describes, never which stream scores closest to
 * the hand count -- choosing by closeness would be fitting the mount to the
 * answer it produces.
 */
class StackMountFieldTest {
    private fun load(name: String): List<ImuSample> =
        ImuCsv.decode(javaClass.getResourceAsStream("/$name.csv")!!.readBytes().decodeToString())

    private fun cues(name: String) = CueTrack.read(name).map { VoiceCue(it.timestampMs, it.label) }

    /** The set's own `-prep.csv` work-start instant, `prep_started_ms,work_started_ms`. */
    private fun work(name: String): Long = javaClass.getResourceAsStream("/$name-prep.csv")!!
        .readBytes().decodeToString().trim().lines()[1].split(",")[1].trim().toLong()

    private fun deg3(value: Double): Double = Math.round(value * 1000.0) / 1000.0

    /** One recorded set and both of its streams, under the mount the set declared. */
    private inner class Pair2(
        val name: String,
        val direction: LiftDirection,
        val targets: SetTargets,
    ) {
        val cues = cues(name)
        val end = SetEnd.of(cues, cadenceGuided = targets.cadenceGuided)
        val workAt = work(name)

        fun samples(role: SensorRole) = load(if (role == SensorRole.A) name else "$name-imu-b")

        fun signal(role: SensorRole) = StackRollSignature.of(samples(role), workAt, end)

        fun roll(role: SensorRole) = deg3(RollExcursion.of(samples(role), workAt, end)!!.degrees)

        fun peakRate(role: SensorRole): Double {
            val window = RollExcursion.inWindow(samples(role), workAt, end)
            return deg3(window.maxOf { abs(it.wxDps) })
        }

        fun analysis(role: SensorRole) =
            SetAnalyzer.analyze(samples(role), direction, targets = targets, cues = cues, workStartedAtMs = workAt)

        /**
         * What the record path would choose for this set: both units delivered
         * full captures, so the frame counts decide nothing and the signature
         * decides everything.
         */
        fun choice() = AnalysedRolePolicy.choose(
            armed = SensorRole.A,
            expected = listOf(SensorRole.A, SensorRole.B),
            framesByRole = mapOf(
                SensorRole.A to samples(SensorRole.A).size,
                SensorRole.B to samples(SensorRole.B).size,
            ),
            declaresStackMount = direction.sensorOnStack,
            signalByRole = SensorRole.entries.associateWith { signal(it) },
        )
    }

    private val rowDirection = LiftDirection(
        startsWith = StartPhase.CONCENTRIC,
        concentricUp = true,
        plane = MovementPlane.HORIZONTAL,
        sensorOnStack = true,
    )

    private val pullUpDirection = LiftDirection(
        startsWith = StartPhase.CONCENTRIC,
        concentricUp = true,
        plane = MovementPlane.VERTICAL,
        sensorOnStack = true,
    )

    private val pushdownDirection = LiftDirection(
        startsWith = StartPhase.CONCENTRIC,
        concentricUp = false,
        plane = MovementPlane.VERTICAL,
        sensorOnStack = true,
    )

    private val pulldownDirection = LiftDirection(
        startsWith = StartPhase.CONCENTRIC,
        concentricUp = false,
        sensorInverted = true,
        plane = MovementPlane.VERTICAL,
        sensorOnStack = true,
    )

    private val rows = listOf(
        Pair2(
            "field-cablerow-3010-8rep-s42-set08",
            rowDirection,
            SetTargets(plannedReps = 8, tempo = Tempo.parse("3010"), cadenceGuided = true),
        ),
        Pair2(
            "field-cablerow-3010-8rep-s42-set09",
            rowDirection,
            SetTargets(plannedReps = 8, tempo = Tempo.parse("3010"), cadenceGuided = true),
        ),
        Pair2(
            "field-cablerow-3010-8rep-s42-set10",
            rowDirection,
            SetTargets(plannedReps = 8, tempo = Tempo.parse("3010"), cadenceGuided = true),
        ),
    )

    private val pullUp11 =
        Pair2(
            "field-pullup-3010-8rep-s42-set11",
            pullUpDirection,
            SetTargets(plannedReps = 8, tempo = Tempo.parse("3010"), cadenceGuided = true),
        )

    private val pullUp13 =
        Pair2(
            "field-pullup-4010-8rep-s42-set13",
            pullUpDirection,
            SetTargets(plannedReps = 8, tempo = Tempo.parse("4010"), cadenceGuided = true),
        )

    private val pushdown =
        Pair2(
            "field-pushdown-1120-14rep-s41-set16",
            pushdownDirection,
            SetTargets(plannedReps = 14, tempo = Tempo.parse("1120"), cadenceGuided = true),
        )

    private val pulldown =
        Pair2(
            "field-latpulldown-1120-12rep-s41-set18",
            pulldownDirection,
            SetTargets(plannedReps = 12, tempo = Tempo.parse("1120"), cadenceGuided = true),
        )

    /**
     * THE HEADLINE. All three rows read the handle unit as moved and the stack
     * unit as still, by two orders of magnitude, and the analysis moves onto the
     * stack unit.
     */
    @Test
    fun `all three seated cable rows read the handle unit as moved and its partner as still`() {
        assertEquals(listOf(37.002, 41.457, 11.827), rows.map { it.roll(SensorRole.A) }, "role a, the handle unit")
        assertEquals(listOf(0.242, 0.302, 0.461), rows.map { it.roll(SensorRole.B) }, "role b, on the stack")
        assertEquals(
            List(3) { StackMountSignal.NOT_ON_STACK },
            rows.map { it.signal(SensorRole.A) },
        )
        assertEquals(List(3) { StackMountSignal.ON_STACK }, rows.map { it.signal(SensorRole.B) })
        assertEquals(List(3) { SensorRole.B }, rows.map { it.choice().role }, "the analysis did not move")
        assertEquals(List(3) { AnalysedRoleBasis.STACK_SIGNATURE }, rows.map { it.choice().basis })
    }

    /**
     * And what that is worth to the lifter: 3, 4 and 2 reps of 8 become 9, 9
     * and 9.
     *
     * The three counts from the handle unit are the figures field-42's own
     * `session.json` published, reproduced by this classpath. Nine is not eight:
     * the stack unit's stream resolves one detection more than the lifter
     * counted on every one of the three, and that over-count is not what this
     * change repairs.
     */
    @Test
    fun `the rows read from the stack unit resolve nine reps rather than three four and two`() {
        assertEquals(listOf(3, 4, 2), rows.map { it.analysis(SensorRole.A).reps.size }, "as recorded")
        assertEquals(listOf(9, 9, 9), rows.map { it.analysis(SensorRole.B).reps.size }, "from the declared mount")
        assertEquals(
            listOf(null, 79.3, null),
            rows.map { everyRepLossPct(it.analysis(SensorRole.A).reps) },
            "velocity loss as recorded, over every rep: two of the three had none at all",
        )
        assertEquals(
            listOf(64.3, 59.3, 4.5),
            rows.map { everyRepLossPct(it.analysis(SensorRole.B).reps) },
            "velocity loss from the stack unit, over every rep",
        )
        // From #306 none of the six publishes a figure: no two reps are bounded.
        assertEquals(
            listOf(null, null, null, null, null, null),
            rows.flatMap { listOf(it.analysis(SensorRole.A), it.analysis(SensorRole.B)) }.map { it.velocityLossPct },
            "velocity loss the six analyses publish",
        )
    }

    /**
     * The analysed stream's SAMPLE RATE moves with the role on field-42, and
     * this is a second consequence rather than a restatement of the first: role
     * `a` delivered around 44 rows a second on that session while role `b`
     * delivered around 99. Span-based, so it says each stream was long and
     * evenly clocked and NOT that it was complete -- a dropout is
     * arithmetically indistinguishable from a slower sensor.
     */
    @Test
    fun `the field-42 flip moves the analysis onto the faster-clocked stream`() {
        assertEquals(
            listOf(44.42158207590306, 44.48378123635026, 44.452397995705084),
            rows.map { it.analysis(SensorRole.A).sampleRateHz },
            "role a span-based rates",
        )
        assertEquals(
            listOf(99.38271604938271, 99.36220978250894, 99.35385422379056),
            rows.map { it.analysis(SensorRole.B).sampleRateHz },
            "role b span-based rates",
        )
    }

    /**
     * THE FLIP AGAINST ITSELF. Both assisted pull-ups move onto the stack unit
     * and neither rep count gets closer to the eight the lifter performed: 9
     * becomes 10 on set 11 and 9 becomes 5 on set 13.
     *
     * Pinned because it is the honest half of the change. Set 11 is also the
     * tightest margin in the corpus -- 4.977 degrees on the handle unit against
     * a 3.0 bound -- so it is the one a slightly different threshold would have
     * decided the other way.
     */
    @Test
    fun `both assisted pull-ups move and their rep counts do not improve`() {
        assertEquals(4.977, pullUp11.roll(SensorRole.A), "the narrowest moved-unit figure in the corpus")
        assertEquals(0.401, pullUp11.roll(SensorRole.B))
        assertEquals(19.973, pullUp13.roll(SensorRole.A))
        assertEquals(0.428, pullUp13.roll(SensorRole.B))
        assertEquals(SensorRole.B, pullUp11.choice().role)
        assertEquals(SensorRole.B, pullUp13.choice().role)
        assertEquals(9, pullUp11.analysis(SensorRole.A).reps.size, "set 11 as recorded, against 8 performed")
        assertEquals(10, pullUp11.analysis(SensorRole.B).reps.size, "set 11 from the declared mount")
        assertEquals(9, pullUp13.analysis(SensorRole.A).reps.size, "set 13 as recorded, against 8 performed")
        assertEquals(5, pullUp13.analysis(SensorRole.B).reps.size, "set 13 from the declared mount")
    }

    /**
     * OVER-MOVING IS THE DEFECT A RULE LIKE THIS INVITES, so the case where the
     * armed unit is ALREADY the one on the stack is pinned from the other side.
     * The triceps pushdown armed the still unit; the signature confirms it and
     * the analysis does not move.
     *
     * Its rep count is 1 of the 14 performed and stays 1, which this change
     * does not repair and does not cause: the unit the declaration describes is
     * the unit already being read. Its partner, on the handle, resolves 15. That
     * is a separate defect, raised rather than folded in here.
     *
     * The stream is not one that "barely travels": that reading stood here and
     * is deleted. Under `sensorInverted` true the same stream resolves 14 of the
     * 14 performed -- the set's own geometry read the stack's rise as the
     * return, because the recorded declaration carries `sensorInverted` false
     * on a stack whose drive goes down. That is #317; the count asserted below
     * is the geometry as recorded, which does not move.
     */
    @Test
    fun `the triceps pushdown confirms the unit it already armed and moves nothing`() {
        assertEquals(0.28, pushdown.roll(SensorRole.A), "role a, on the stack")
        assertEquals(23.588, pushdown.roll(SensorRole.B), "role b, on the handle")
        assertEquals(StackMountSignal.ON_STACK, pushdown.signal(SensorRole.A))
        assertEquals(StackMountSignal.NOT_ON_STACK, pushdown.signal(SensorRole.B))
        assertEquals(SensorRole.A, pushdown.choice().role, "the analysis moved off the unit that was on the stack")
        assertEquals(AnalysedRoleBasis.STACK_SIGNATURE, pushdown.choice().basis, "a confirmation is not a shrug")
        assertEquals(1, pushdown.analysis(SensorRole.A).reps.size, "as recorded, against 14 performed")
        assertEquals(15, pushdown.analysis(SensorRole.B).reps.size, "the handle unit, for the record")
    }

    /**
     * The lat pulldown is the case where everything lines up: the analysis moves
     * onto the stack unit and the count becomes the twelve the lifter performed,
     * from thirteen.
     */
    @Test
    fun `the lat pulldown moves onto the stack unit and reads the count performed`() {
        assertEquals(6.597, pulldown.roll(SensorRole.A))
        assertEquals(0.385, pulldown.roll(SensorRole.B))
        assertEquals(SensorRole.B, pulldown.choice().role)
        assertEquals(AnalysedRoleBasis.STACK_SIGNATURE, pulldown.choice().basis)
        assertEquals(13, pulldown.analysis(SensorRole.A).reps.size, "as recorded, against 12 performed")
        assertEquals(12, pulldown.analysis(SensorRole.B).reps.size, "from the declared mount")
        assertEquals(
            27.5,
            everyRepLossPct(pulldown.analysis(SensorRole.A).reps),
            "velocity loss as recorded, over every rep",
        )
        assertNull(pulldown.analysis(SensorRole.A).velocityLossPct, "velocity loss as recorded: withheld from #306")
        assertEquals(
            41.4,
            everyRepLossPct(pulldown.analysis(SensorRole.B).reps),
            "velocity loss from the stack unit, over every rep",
        )
        assertNull(
            pulldown.analysis(SensorRole.B).velocityLossPct,
            "velocity loss from the stack unit: withheld from #306",
        )
    }

    /**
     * The rate guard's figures on real streams, so the constant is pinned
     * against data and not only against synthetics.
     */
    @Test
    fun `the roll rate separates nothing and every still unit is far under the guard`() {
        val still = listOf(rows[0], rows[1], rows[2], pullUp11, pullUp13, pulldown).map { it.peakRate(SensorRole.B) } +
            pushdown.peakRate(SensorRole.A)
        val moved = rows.map { it.peakRate(SensorRole.A) } +
            listOf(pullUp11.peakRate(SensorRole.A), pullUp13.peakRate(SensorRole.A), pulldown.peakRate(SensorRole.A)) +
            pushdown.peakRate(SensorRole.B)

        assertEquals(listOf(1.892, 2.441, 7.141, 6.348, 6.348, 4.639, 6.897), still, "streams read as on the stack")
        assertEquals(
            listOf(16.235, 22.522, 35.828, 4.456, 13.672, 42.114, 75.256),
            moved,
            "streams read as moved",
        )
        assertTrue(
            still.all { it <= StackRollSignature.MAX_STACK_ROLL_RATE_DPS },
            "a stream this rule calls ON_STACK is over the rate guard",
        )
        assertTrue(
            moved.any { it <= StackRollSignature.MAX_STACK_ROLL_RATE_DPS },
            "the rate would have separated the two populations on its own, which the guard is not claimed to do",
        )
    }

    /**
     * A BAR-MOUNTED PAIR NEVER MOVES, pinned on the deadlift pair #247
     * committed. Neither unit reads as riding a stack -- 367.2 and 17.2 degrees
     * -- so even under a stack declaration the set is analysed exactly as it is
     * today, and its own declaration names no stack at all, which stops the
     * question being asked.
     */
    @Test
    fun `a bar-mounted two-unit set is left alone from both directions`() {
        val name = "field-deadlift-straight-5rep-s43-set04"
        val cues = cues(name)
        val end = SetEnd.of(cues, cadenceGuided = true)
        val a = load(name)
        val b = load("$name-imu-b")

        assertEquals(367.246, deg3(RollExcursion.of(a, null, end)!!.degrees), "role a over the whole capture")
        assertEquals(17.172, deg3(RollExcursion.of(b, null, end)!!.degrees), "role b over the whole capture")
        assertEquals(StackMountSignal.NOT_ON_STACK, StackRollSignature.of(a, null, end))
        assertEquals(StackMountSignal.NOT_ON_STACK, StackRollSignature.of(b, null, end))

        val signals = mapOf(
            SensorRole.A to StackRollSignature.of(a, null, end),
            SensorRole.B to StackRollSignature.of(b, null, end),
        )
        val frames = mapOf(SensorRole.A to a.size, SensorRole.B to b.size)
        val asDeclared =
            AnalysedRolePolicy.choose(SensorRole.A, listOf(SensorRole.A, SensorRole.B), frames, false, signals)
        val asIfStack =
            AnalysedRolePolicy.choose(SensorRole.A, listOf(SensorRole.A, SensorRole.B), frames, true, signals)

        assertEquals(SensorRole.A, asDeclared.role)
        assertEquals(AnalysedRoleBasis.DECLARED, asDeclared.basis)
        assertEquals(SensorRole.A, asIfStack.role, "a barbell set was moved onto its partner")
        assertEquals(AnalysedRoleBasis.DECLARED, asIfStack.basis)
    }

    /** Across all seven committed pairs the flag #247's refusal reads stays false. */
    @Test
    fun `no committed pair sets the fallback flag`() {
        val all = rows + listOf(pullUp11, pullUp13, pushdown, pulldown)

        assertTrue(all.none { it.choice().fellBack }, "a signature verdict set the frame-count flag")
        all.forEach { assertFalse(it.analysis(it.choice().role!!).reps.isEmpty(), "${it.name} published no reps") }
    }
}
