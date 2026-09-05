package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [TempoAdjustPolicy], the decision half of the between-sets tempo picker.
 *
 * Three of these are the DIFFERENTIALS of #148, replacing the three
 * characterization pins that stood in their place at
 * 96111a7495bb7a945b4d0d94ba3daf383c38f711: a drive-down lift's labels, the
 * tempo offered to a set that declares none, and whether an adjustment stands
 * past the set it was made on. Each names what it inverted. They red against
 * that parent, which is the point of them.
 *
 * The lift shapes are named for what they are and not for a machine: what the
 * policy is asked is a pair of booleans, and it is the pair that reaches
 * different branches.
 */
class TempoAdjustPolicyTest {
    private fun benchPress() = TempoAdjustPolicy.digits(concentricUp = true, horizontal = false)

    private fun pushdown() = TempoAdjustPolicy.digits(concentricUp = false, horizontal = false)

    private fun seatedRow() = TempoAdjustPolicy.digits(concentricUp = true, horizontal = true)

    private fun labelled(digits: List<TempoDigit>) = digits.map { "${it.label}/${it.caption}" }

    /**
     * The digit at [position] on a BENCH PRESS, for the sweeps and the pins
     * that are about stepping rather than about the lift.
     *
     * `TempoAdjustPolicy.steppedValue`, `canStep` and `withDigit` take a
     * [TempoDigit] and not a bare position, because which values a digit may be
     * stepped through depends on which stroke is the drive (#251). Everything
     * in this class that is not itself about the drive direction asks about the
     * shape the app sees most: vertical, drive up. The pins that ARE about the
     * direction live in `TempoStrokeRoleContractTest`.
     *
     * A position outside the notation has no digit, and one is fabricated for
     * it so that the out-of-range pins can still be made.
     */
    private fun wheel(position: Int): TempoDigit = benchPress().firstOrNull { it.position == position }
        ?: TempoDigit(position, "", "", TempoAdjustPolicy.spellable(position))

    /**
     * The tempo one tap of a stepper leaves behind: the screen's own two calls,
     * in the order the screen makes them, so the pins cover the round trip
     * rather than the digit alone.
     */
    private fun tapped(tempo: String, position: Int, delta: Int): String? =
        TempoAdjustPolicy.steppedValue(tempo, wheel(position), delta)?.let {
            TempoAdjustPolicy.withDigit(tempo, wheel(position), it)
        }

    @Test
    fun `a drive-up vertical lift calls digit 1 the down stroke and its eccentric`() {
        assertEquals(
            listOf(
                "DOWN/eccentric",
                "PAUSE/after the eccentric",
                "UP/concentric",
                "PAUSE/after the concentric",
            ),
            labelled(benchPress()),
        )
        assertEquals(listOf(1, 2, 3, 4), benchPress().map { it.position })
    }

    @Test
    fun `horizontal work is called DRIVE and RETURN, because it has no up or down`() {
        assertEquals(
            listOf(
                "RETURN/eccentric",
                "PAUSE/after the eccentric",
                "DRIVE/concentric",
                "PAUSE/after the concentric",
            ),
            labelled(seatedRow()),
        )
    }

    /**
     * A pushdown, a lat pulldown and a leg curl all drive DOWN. Digit 1 is
     * still the down stroke -- `TempoSchedule.of` reads it that way and the
     * guide calls it "DOWN" -- and on these lifts that stroke is the drive, so
     * the wheel saying DOWN is captioned concentric and it is digit 3 that is
     * the eccentric.
     *
     * Naming the digits by phase-then-direction instead gets both words and
     * both captions backwards, which is v0.1.41's defect in a new place: a
     * lifter scrolling the wheel captioned "eccentric" would be lengthening
     * their drive.
     *
     * Inverts the pin that stood here at
     * 96111a7495bb7a945b4d0d94ba3daf383c38f711.
     */
    @Test
    fun `a drive-down lift keeps the digits where they are and moves the phases`() {
        assertEquals(
            listOf(
                "DOWN/concentric",
                "PAUSE/after the concentric",
                "UP/eccentric",
                "PAUSE/after the eccentric",
            ),
            labelled(pushdown()),
        )
    }

    @Test
    fun `a tempo string may not spell a zero stroke and only the up stroke may spell X`() {
        assertEquals((1..9).map { it.toString() }, TempoAdjustPolicy.spellable(TempoAdjustPolicy.DOWN_STROKE))
        assertEquals((0..9).map { it.toString() }, TempoAdjustPolicy.spellable(TempoAdjustPolicy.BOTTOM_PAUSE))
        assertEquals(
            (1..9).map { it.toString() } + "X",
            TempoAdjustPolicy.spellable(TempoAdjustPolicy.UP_STROKE),
        )
        assertEquals((0..9).map { it.toString() }, TempoAdjustPolicy.spellable(TempoAdjustPolicy.TOP_PAUSE))
    }

    /**
     * Every value of every wheel spells a tempo the rest of the app accepts.
     *
     * The check that makes the alphabet a fact rather than a comment: 36 wheel
     * settings against `Tempo.parseOrNull`, which is what `PlanFile.validate`,
     * `RecordViewModel.beginSet` and the in-set ring all read a tempo through.
     */
    @Test
    fun `every value of every wheel spells a tempo the app can parse`() {
        (1..TempoAdjustPolicy.DIGITS).forEach { position ->
            wheel(position).choices.forEach { value ->
                val text = TempoAdjustPolicy.withDigit("3010", wheel(position), value)
                assertNotNull(text, "digit $position = $value spells nothing")
                assertNotNull(Tempo.parseOrNull(text), "digit $position = $value spells '$text', which will not parse")
                assertEquals(text, TempoAdjustPolicy.wheelValues(text)?.joinToString(""), "and it draws back the same")
            }
        }
    }

    @Test
    fun `a compact tempo, an explosive one and a dashed one all spell four wheels`() {
        assertEquals(listOf("3", "0", "1", "0"), TempoAdjustPolicy.wheelValues("3010"))
        assertEquals(listOf("3", "0", "X", "0"), TempoAdjustPolicy.wheelValues("30X0"))
        assertEquals(listOf("4", "0", "1", "0"), TempoAdjustPolicy.wheelValues("4-0-1-0"))
        assertEquals(listOf("4", "2", "1", "1"), TempoAdjustPolicy.wheelValues("4211"))
    }

    /**
     * The five inputs that get no control at all, rather than a rewritten one.
     *
     * A set declaring no tempo is the case #148 folds in: it must get no
     * picker, because a picker that can ADD a tempo turns on the prep, the
     * pacing and the compliance verdict. The other four are prescriptions four
     * single-character wheels cannot show without changing them.
     */
    @Test
    fun `a tempo four wheels cannot show gets no wheels rather than a rewritten one`() {
        assertNull(TempoAdjustPolicy.wheelValues(null), "a set that declares no tempo")
        assertNull(TempoAdjustPolicy.wheelValues(""), "and neither does an empty string")
        assertNull(TempoAdjustPolicy.wheelValues("3-0-1.5-0"), "a fractional component")
        assertNull(TempoAdjustPolicy.wheelValues("10-0-1-0"), "a component of ten or more")
        assertNull(TempoAdjustPolicy.wheelValues("0010"), "a down stroke below the floor")
        assertNull(TempoAdjustPolicy.wheelValues("3000"), "an up stroke below the floor")
    }

    @Test
    fun `wheel values spell a tempo only when every one of them is legal`() {
        assertEquals("3010", TempoAdjustPolicy.compose(listOf("3", "0", "1", "0")))
        assertEquals("30X0", TempoAdjustPolicy.compose(listOf("3", "0", "X", "0")))
        assertNull(TempoAdjustPolicy.compose(listOf("0", "0", "1", "0")), "a zero down stroke")
        assertNull(TempoAdjustPolicy.compose(listOf("3", "X", "1", "0")), "X outside the up stroke")
        assertNull(TempoAdjustPolicy.compose(listOf("3", "0", "1")), "three wheels are not a tempo")
        assertNull(TempoAdjustPolicy.compose(listOf("3", "0", "1", "0", "0")), "and neither are five")
    }

    @Test
    fun `changing one digit changes that digit and nothing else`() {
        assertEquals("5010", TempoAdjustPolicy.withDigit("3010", wheel(TempoAdjustPolicy.DOWN_STROKE), "5"))
        assertEquals("3210", TempoAdjustPolicy.withDigit("3010", wheel(TempoAdjustPolicy.BOTTOM_PAUSE), "2"))
        assertEquals("30X0", TempoAdjustPolicy.withDigit("3010", wheel(TempoAdjustPolicy.UP_STROKE), "X"))
        assertEquals("3013", TempoAdjustPolicy.withDigit("3010", wheel(TempoAdjustPolicy.TOP_PAUSE), "3"))
        assertEquals("3010", TempoAdjustPolicy.withDigit("30X0", wheel(TempoAdjustPolicy.UP_STROKE), "1"))
    }

    @Test
    fun `a change that would not spell a tempo changes nothing`() {
        assertNull(TempoAdjustPolicy.withDigit("3010", wheel(TempoAdjustPolicy.DOWN_STROKE), "0"), "the stroke floor")
        assertNull(
            TempoAdjustPolicy.withDigit("3010", wheel(TempoAdjustPolicy.BOTTOM_PAUSE), "X"),
            "X off the up stroke",
        )
        assertNull(TempoAdjustPolicy.withDigit("3010", wheel(0), "1"), "there is no digit 0")
        assertNull(TempoAdjustPolicy.withDigit("3010", wheel(5), "1"), "and no digit 5")
        assertNull(TempoAdjustPolicy.withDigit(null, wheel(TempoAdjustPolicy.DOWN_STROKE), "3"), "nothing to change")
        assertNull(
            TempoAdjustPolicy.withDigit("3-0-1.5-0", wheel(TempoAdjustPolicy.BOTTOM_PAUSE), "1"),
            "a tempo the control could not have drawn cannot be written back through it",
        )
    }

    /**
     * The inputs a stepper is not drawn for at all, checked through the stepping
     * pair rather than assumed to follow from [TempoAdjustPolicy.wheelValues].
     *
     * Same six tempos as the no-control pin above plus the two positions outside
     * the notation, because [TempoAdjustPolicy.steppedValue] takes a position and
     * `wheelValues` does not: a stepper drawn for digit 5 would have no digit to
     * move and must say so rather than moving digit 4.
     */
    @Test
    fun `a tempo with no control on it cannot be stepped, and neither can a digit that is not there`() {
        listOf(null, "", "3-0-1.5-0", "10-0-1-0", "0010", "3000").forEach { tempo ->
            (1..TempoAdjustPolicy.DIGITS).forEach { position ->
                assertNull(TempoAdjustPolicy.steppedValue(tempo, wheel(position), 1), "'$tempo' digit $position up")
                assertNull(TempoAdjustPolicy.steppedValue(tempo, wheel(position), -1), "'$tempo' digit $position down")
                assertFalse(TempoAdjustPolicy.canStep(tempo, wheel(position), 1), "'$tempo' digit $position offers up")
                assertFalse(
                    TempoAdjustPolicy.canStep(tempo, wheel(position), -1),
                    "'$tempo' digit $position offers down",
                )
            }
        }
        listOf(0, 5).forEach { position ->
            assertNull(TempoAdjustPolicy.steppedValue("3010", wheel(position), 1), "there is no digit $position")
            assertFalse(TempoAdjustPolicy.canStep("3010", wheel(position), 1), "and no button on it either")
        }
    }

    /**
     * What a tap may produce, over every tempo the control can draw.
     *
     * 39 x 4 x 25 = 3,900 taps: every single-digit variation of `3010` (9 down
     * strokes, 10 up strokes, 10 of each pause), every digit, every delta from
     * -12 to 12 -- both ends well past the length of the longest choice list, so
     * the clamp is exercised rather than trusted.
     * Three invariants, each of which the app depends on somewhere else: the
     * result is a value that digit is allowed to take, the tempo it spells is
     * one `Tempo.parseOrNull` accepts (`beginSet`, `PlanFile.validate` and the
     * in-set ring all read a tempo through it), and no other digit moved.
     *
     * It says nothing about WHICH value a tap produces. That is the differential
     * pins' job below; this one would pass on a control that never moved at all.
     */
    @Test
    fun `every tap of every digit lands on a value that digit is allowed to take`() {
        (1..TempoAdjustPolicy.DIGITS).forEach { source ->
            wheel(source).choices.forEach { value ->
                val tempo = TempoAdjustPolicy.withDigit("3010", wheel(source), value)
                assertNotNull(tempo, "digit $source = $value spells nothing")
                (1..TempoAdjustPolicy.DIGITS).forEach { position ->
                    (-12..12).forEach { delta ->
                        val stepped = TempoAdjustPolicy.steppedValue(tempo, wheel(position), delta)
                        assertNotNull(stepped, "'$tempo' digit $position by $delta")
                        assertTrue(
                            stepped in wheel(position).choices,
                            "'$tempo' digit $position by $delta gives '$stepped', which that digit may not take",
                        )
                        val after = TempoAdjustPolicy.withDigit(tempo, wheel(position), stepped)
                        assertNotNull(after, "'$tempo' digit $position by $delta spells nothing")
                        assertNotNull(Tempo.parseOrNull(after), "'$after' will not parse")
                        val moved = TempoAdjustPolicy.wheelValues(after)!!
                        val before = TempoAdjustPolicy.wheelValues(tempo)!!
                        assertEquals(
                            before.indices.filter { before[it] != moved[it] }.toSet() - (position - 1),
                            emptySet(),
                            "'$tempo' digit $position by $delta moved a digit it was not asked to",
                        )
                    }
                }
            }
        }
    }

    /**
     * One tap, one place, on the digit that was tapped.
     *
     * The whole content of "each change feels deliberate": the lifter taps once
     * and one number moves by one. A tap that moved two places, or moved the
     * digit beside it, would be the wheel's own defect wearing a button.
     */
    @Test
    fun `one tap moves the digit it is on by one place`() {
        assertEquals("4", TempoAdjustPolicy.steppedValue("3010", wheel(TempoAdjustPolicy.DOWN_STROKE), 1))
        assertEquals("2", TempoAdjustPolicy.steppedValue("3010", wheel(TempoAdjustPolicy.DOWN_STROKE), -1))
        assertEquals("1", TempoAdjustPolicy.steppedValue("3010", wheel(TempoAdjustPolicy.BOTTOM_PAUSE), 1))
        assertEquals("2", TempoAdjustPolicy.steppedValue("3010", wheel(TempoAdjustPolicy.UP_STROKE), 1))
        assertEquals("1", TempoAdjustPolicy.steppedValue("3010", wheel(TempoAdjustPolicy.TOP_PAUSE), 1))
        assertEquals("3010", tapped("2010", TempoAdjustPolicy.DOWN_STROKE, 1))
        assertEquals("3010", tapped("3011", TempoAdjustPolicy.TOP_PAUSE, -1))
    }

    /**
     * A stroke stops at one second and never reaches zero.
     *
     * [TempoAdjustPolicy.MIN_STROKE_S] is measured, not chosen: `CadencePlan.of`
     * floors every stroke at a second because the runner can only sleep in whole
     * ones, so a stroke prescribed as 0 is PLAYED as 1 while the scorer goes on
     * grading the lifter against the 0. A stepper that could walk a digit down
     * to 0 would build that state one tap at a time.
     */
    @Test
    fun `a stroke steps down to one second and stops there`() {
        assertEquals("2", TempoAdjustPolicy.steppedValue("3010", wheel(TempoAdjustPolicy.DOWN_STROKE), -1))
        assertEquals("1", TempoAdjustPolicy.steppedValue("2010", wheel(TempoAdjustPolicy.DOWN_STROKE), -1))
        assertEquals("1", TempoAdjustPolicy.steppedValue("1010", wheel(TempoAdjustPolicy.DOWN_STROKE), -1))
        assertEquals("1", TempoAdjustPolicy.steppedValue("1010", wheel(TempoAdjustPolicy.DOWN_STROKE), -5))
        assertFalse(TempoAdjustPolicy.canStep("1010", wheel(TempoAdjustPolicy.DOWN_STROKE), -1), "no tap left to offer")
        assertEquals("1", TempoAdjustPolicy.steppedValue("3020", wheel(TempoAdjustPolicy.UP_STROKE), -1))
        assertEquals(
            "X",
            TempoAdjustPolicy.steppedValue("3010", wheel(TempoAdjustPolicy.UP_STROKE), -1),
            "one second is the floor of a stroke's SECONDS; on the drive there is one more tap below it, #251",
        )
    }

    /**
     * The drive steps DOWN into X and stops, and steps back out into 1.
     *
     * INVERTED by #251, and the sentence that used to be here -- "X is the last
     * entry of the up stroke's choices ... the top of that digit's range" -- is
     * deleted rather than reworded, because it was the defect. X is the FASTEST
     * stroke there is, so putting it past 9 put it at the SLOW end of the
     * range: one mis-tap past a nine-second stroke produced an explosive one,
     * and the lifter who wanted an explosive drive had to walk up through eight
     * values to reach it. Below 1 is where it belongs, and where the owner's
     * ask put it.
     *
     * `TempoStrokeRoleContractTest` carries the other half -- that this is the
     * DRIVE's range and not digit 3's.
     */
    @Test
    fun `the drive steps below one into X and back out into one`() {
        assertEquals("X", TempoAdjustPolicy.steppedValue("3010", wheel(TempoAdjustPolicy.UP_STROKE), -1))
        assertEquals("X", TempoAdjustPolicy.steppedValue("30X0", wheel(TempoAdjustPolicy.UP_STROKE), -1))
        assertFalse(TempoAdjustPolicy.canStep("30X0", wheel(TempoAdjustPolicy.UP_STROKE), -1), "nothing faster than X")
        assertEquals("1", TempoAdjustPolicy.steppedValue("30X0", wheel(TempoAdjustPolicy.UP_STROKE), 1))
        assertEquals("9", TempoAdjustPolicy.steppedValue("3090", wheel(TempoAdjustPolicy.UP_STROKE), 1))
        assertEquals("30X0", tapped("3010", TempoAdjustPolicy.UP_STROKE, -1))
    }

    /** A pause steps down to zero -- the pause where the lifter does not stop -- and stops. */
    @Test
    fun `a pause steps down to zero and stops`() {
        assertEquals("0", TempoAdjustPolicy.steppedValue("3011", wheel(TempoAdjustPolicy.TOP_PAUSE), -1))
        assertTrue(TempoAdjustPolicy.canStep("3011", wheel(TempoAdjustPolicy.TOP_PAUSE), -1))
        assertEquals("0", TempoAdjustPolicy.steppedValue("3010", wheel(TempoAdjustPolicy.TOP_PAUSE), -1))
        assertFalse(TempoAdjustPolicy.canStep("3010", wheel(TempoAdjustPolicy.TOP_PAUSE), -1), "zero is the floor")
        assertEquals("0", TempoAdjustPolicy.steppedValue("3110", wheel(TempoAdjustPolicy.BOTTOM_PAUSE), -3))
    }

    /**
     * A step past the end of a range clamps; it does not wrap.
     *
     * Wrapping would turn a 9 into a 1 on one mis-tap. On the down stroke that
     * is an eight-second difference between what the voice paces and what the
     * lifter is graded against, and it would arrive silently, at the exact
     * moment the lifter was not looking at the screen.
     */
    @Test
    fun `a step past the end of the range clamps rather than wrapping`() {
        assertEquals("9", TempoAdjustPolicy.steppedValue("3010", wheel(TempoAdjustPolicy.DOWN_STROKE), 99))
        assertEquals("9", TempoAdjustPolicy.steppedValue("3010", wheel(TempoAdjustPolicy.BOTTOM_PAUSE), 99))
        assertEquals("9", TempoAdjustPolicy.steppedValue("3010", wheel(TempoAdjustPolicy.UP_STROKE), 99))
        assertEquals("X", TempoAdjustPolicy.steppedValue("3050", wheel(TempoAdjustPolicy.UP_STROKE), -99))
        assertEquals("0", TempoAdjustPolicy.steppedValue("3919", wheel(TempoAdjustPolicy.TOP_PAUSE), -99))
        assertEquals("1", TempoAdjustPolicy.steppedValue("3919", wheel(TempoAdjustPolicy.DOWN_STROKE), -99))
    }

    /**
     * A button is offered only where it would move something.
     *
     * The stepper's enabled state, decided here so the screen holds no rule of
     * its own. A button drawn enabled that does nothing is the state #154 was
     * raised about, one control over.
     */
    @Test
    fun `a tap that would move nothing is not offered`() {
        assertTrue(TempoAdjustPolicy.canStep("3010", wheel(TempoAdjustPolicy.DOWN_STROKE), 1), "3 has an up")
        assertTrue(TempoAdjustPolicy.canStep("3010", wheel(TempoAdjustPolicy.DOWN_STROKE), -1), "and a down")
        assertFalse(TempoAdjustPolicy.canStep("9010", wheel(TempoAdjustPolicy.DOWN_STROKE), 1), "9 is the ceiling")
        assertFalse(TempoAdjustPolicy.canStep("3010", wheel(TempoAdjustPolicy.DOWN_STROKE), 0), "a tap of nothing")
        assertEquals("3", TempoAdjustPolicy.steppedValue("3010", wheel(TempoAdjustPolicy.DOWN_STROKE), 0))
    }

    @Test
    fun `the set coming up is offered its own plan's tempo`() {
        assertEquals(
            "3010",
            TempoAdjustPolicy.seedTempo(hasPlannedNext = true, nextDeclaredTempo = "3010", lastRanTempo = "4010"),
        )
    }

    /**
     * A planned set that declares no tempo is offered none.
     *
     * The shipped example's Upper A runs dumbbell_bench_press at 3010 straight
     * into single_arm_dumbbell_row, which declares no tempo at all. Offered
     * 3010, the row is paced by the voice against it, counted by the guide
     * rather than by the lifter, given a prep its author never declared, and
     * records 3010 as its prescription for good. Declaring nothing is a
     * declaration.
     *
     * Inverts the pin that stood here at
     * 96111a7495bb7a945b4d0d94ba3daf383c38f711.
     */
    @Test
    fun `a planned set declaring no tempo is offered none, not the one the last set ran`() {
        assertNull(
            TempoAdjustPolicy.seedTempo(hasPlannedNext = true, nextDeclaredTempo = null, lastRanTempo = "3010"),
        )
    }

    /**
     * The ad-hoc half, which is correct today and stays. There is no plan to
     * declare anything, the tempo field is the only declaration there is, and
     * carrying it from one set to the next is what the lifter typed it for.
     */
    @Test
    fun `with no planned set at all the last tempo carries, which is the ad-hoc case`() {
        assertEquals(
            "3010",
            TempoAdjustPolicy.seedTempo(hasPlannedNext = false, nextDeclaredTempo = null, lastRanTempo = "3010"),
        )
        assertNull(TempoAdjustPolicy.seedTempo(hasPlannedNext = false, nextDeclaredTempo = null, lastRanTempo = null))
        assertNull(TempoAdjustPolicy.seedTempo(hasPlannedNext = true, nextDeclaredTempo = null, lastRanTempo = null))
    }

    /**
     * An adjustment holds for the rest of the block it was made in.
     *
     * The lifter who slows the eccentric on set 1 of an exercise did not mean
     * it for set 1 alone, any more than a lifter who moves up in weight means
     * it for one set -- which is #124, decided the same way for load. Without
     * this, every set after the first is re-offered the plan's tempo and paced
     * against it, and the export records compliance with a prescription the
     * lifter deliberately left.
     *
     * Inverts the pin that stood here at
     * 96111a7495bb7a945b4d0d94ba3daf383c38f711.
     */
    @Test
    fun `an adjusted tempo stands for the rest of the block it was set in`() {
        assertEquals(
            "4010",
            TempoAdjustPolicy.standingAdjustedTempo(
                adjustedTempo = "4010",
                sameExerciseBlock = true,
                lastDeclaredTempo = "3010",
                nextDeclaredTempo = "3010",
            ),
        )
    }

    /**
     * The three boundaries the carry stops at, all of them
     * [SetLoadPolicy.standingStatedAddedKg]'s and none of them restated here.
     *
     * [SetLoadPolicy.sameExerciseBlock] is what decides the first two -- the
     * next exercise, and a second block of the same movement -- and it is
     * pinned in `SetLoadPolicyTest` rather than a second time here; this takes
     * its answer as a parameter, which is the whole reason it is one.
     *
     * The third is the plan prescribing a CHANGE. A block written 3010 / 4010
     * is asking for a different tempo on its second set, and it is that tempo
     * the lifter is offered. Without it the fix becomes the same defect facing
     * the other way: an adjustment made to the opener would flatten a
     * prescribed progression.
     */
    @Test
    fun `an adjusted tempo stops at the block boundary and at a prescribed change`() {
        assertNull(
            TempoAdjustPolicy.standingAdjustedTempo("4010", false, "3010", "3010"),
            "a new exercise, or a second block of the same one, is offered its own plan",
        )
        assertNull(
            TempoAdjustPolicy.standingAdjustedTempo("4010", true, "3010", "4010"),
            "the plan prescribes a change for the next set, and it wins",
        )
        assertNull(
            TempoAdjustPolicy.standingAdjustedTempo(null, true, "3010", "3010"),
            "nothing was adjusted, so nothing stands",
        )
        assertNull(
            TempoAdjustPolicy.standingAdjustedTempo("4010", true, "3010", null),
            "the plan declared a tempo for one of the two sets and not the other",
        )
    }

    /**
     * The walk through the shipped example's Upper A, in the order the record
     * flow takes it.
     *
     * dumbbell_bench_press declares 3010 on both its sets. The lifter slows the
     * eccentric to 4010 on set 1; set 2 runs 4010 without being retyped. The
     * next exercise is single_arm_dumbbell_row, a different movement declaring
     * no tempo at all: nothing said about the bench reaches it, and it is
     * offered no tempo, so it runs unpaced as its author wrote it.
     */
    @Test
    fun `the walk through Upper A carries an adjustment to set 2 and stops it at the row`() {
        val withinTheBlock =
            TempoAdjustPolicy.standingAdjustedTempo(
                adjustedTempo = "4010",
                sameExerciseBlock = true,
                lastDeclaredTempo = "3010",
                nextDeclaredTempo = "3010",
            )
        assertEquals("4010", withinTheBlock, "set 2 of the bench press keeps what the lifter set")
        assertEquals("4010", TempoAdjustPolicy.carriedIntoNextSet("3010", withinTheBlock))

        val acrossTheBoundary =
            TempoAdjustPolicy.standingAdjustedTempo(
                adjustedTempo = "4010",
                sameExerciseBlock = false,
                lastDeclaredTempo = "3010",
                nextDeclaredTempo = null,
            )
        assertNull(acrossTheBoundary, "nothing the lifter said about the bench reaches the row")
        assertNull(TempoAdjustPolicy.carriedIntoNextSet(null, acrossTheBoundary))
        assertNull(
            TempoAdjustPolicy.seedTempo(hasPlannedNext = true, nextDeclaredTempo = null, lastRanTempo = "4010"),
            "and the row is offered no tempo at all, which is what it declares",
        )
    }

    @Test
    fun `an adjustment displaces the declaration for the set it is carried into`() {
        assertEquals("3010", TempoAdjustPolicy.carriedIntoNextSet(declaredTempo = "3010", adjustedTempo = null))
        assertEquals("4010", TempoAdjustPolicy.carriedIntoNextSet(declaredTempo = "3010", adjustedTempo = "4010"))
        assertEquals("4010", TempoAdjustPolicy.carriedIntoNextSet(declaredTempo = null, adjustedTempo = "4010"))
        assertNull(TempoAdjustPolicy.carriedIntoNextSet(declaredTempo = null, adjustedTempo = null))
    }
}
