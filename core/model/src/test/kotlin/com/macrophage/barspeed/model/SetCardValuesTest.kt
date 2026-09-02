package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the set card states, and which of its figures carry the plan's struck
 * through beside them.
 *
 * The untouched set is pinned first because it is what the card already draws:
 * this seam has to reproduce that line exactly, or #204 becomes a rendering
 * change that quietly restates every set. Every case where a figure has a
 * PAIR is a differential and is left to run red in the commit that adds it.
 *
 * The two zero cases are the reason this is not a string join. A stated 0
 * against a 90 kg plan is a lifter who stripped the bar and it has to speak; a
 * stated 0 against a plan that declared no load is the plan's own answer and
 * has to stay silent. A truthiness guard cannot tell them apart.
 */
class SetCardValuesTest {
    @Suppress("LongParameterList")
    private fun values(
        kind: ExerciseKind = ExerciseKind.DYNAMIC,
        bodyweight: Boolean = false,
        timed: Boolean = false,
        unit: WeightUnit = WeightUnit.KG,
        side: String? = null,
        plannedSide: String? = null,
        plannedLoadKg: Double? = 90.0,
        statedLoadKg: Double? = null,
        declaredLoadKg: Double? = 90.0,
        plannedReps: Int? = 5,
        reps: Int? = 5,
        plannedDurationS: Int? = null,
        durationS: Int? = null,
        plannedTempo: String? = "4010",
        tempo: String? = "4010",
    ) = SetCardValues.of(
        kind = kind,
        bodyweight = bodyweight,
        timed = timed,
        unit = unit,
        side = side,
        plannedSide = plannedSide,
        plannedLoadKg = plannedLoadKg,
        statedLoadKg = statedLoadKg,
        declaredLoadKg = declaredLoadKg,
        plannedReps = plannedReps,
        reps = reps,
        plannedDurationS = plannedDurationS,
        durationS = durationS,
        plannedTempo = plannedTempo,
        tempo = tempo,
    )

    /** The load value: the one with no word around it. Every case below leaves [side] null. */
    private fun load(list: List<SetCardValue>) = list.single { it.prefix.isEmpty() && it.suffix.isEmpty() }

    /**
     * [SetCardValues.plain] is the base text: the words once, the standing
     * figure, nothing struck. It is what the session preview draws (#202),
     * and pinning it against a literal rather than against
     * [SessionPreviewPolicy.setLine] is deliberate -- setLine is this function
     * now, so an equality between the two could not fail.
     */
    @Test
    fun `plain draws the words once around each standing figure`() {
        assertEquals("5 reps · 90 kg · tempo 4010", SetCardValues.plain(values()))
    }

    /**
     * THE COMPOSITION RULE, and the half of it that can go wrong. A plain
     * string cannot strike a figure through, so the plan's displaced figure is
     * DROPPED rather than rendered: printing both would tell the lifter to
     * lift 90 and 100. The card gets the pair and strikes it; the preview,
     * which draws sets nobody has deviated from, gets this.
     */
    @Test
    fun `plain drops the plan's displaced figure and states only what will be recorded`() {
        val struck = values(statedLoadKg = 100.0)
        assertEquals("90 kg", load(struck).planned)
        assertEquals("5 reps · 100 kg · tempo 4010", SetCardValues.plain(struck))
    }

    @Test
    fun `an untouched set draws the plan's figures and strikes nothing`() {
        assertEquals(
            listOf(
                SetCardValue(stated = "5", suffix = "reps"),
                SetCardValue(stated = "90 kg"),
                SetCardValue(stated = "4010", prefix = "tempo"),
            ),
            values(),
        )
    }

    @Test
    fun `the side leads the line`() {
        assertEquals(SetCardValue(stated = "Left"), values(side = "left").first())
    }

    /**
     * DIFFERENTIAL. The card struck nothing for a side before #215, because
     * there was nothing to strike it against: the value drawn was a copy of
     * the plan's own declaration. The line this replaces asserted the side was
     * "never struck", which was a true statement about a card that could not
     * show a deviation and is DELETED rather than reworded -- keeping it would
     * pin the defect.
     */
    @Test
    fun `an arm the lifter swapped is struck against the one the plan asked for`() {
        assertEquals(
            SetCardValue(stated = "Right", planned = "Left"),
            values(side = "right", plannedSide = "left").first(),
        )
    }

    /** Working the arm the plan asked for is not a deviation and reads as one figure. */
    @Test
    fun `an arm worked as prescribed strikes nothing`() {
        assertEquals(
            SetCardValue(stated = "Left"),
            values(side = "left", plannedSide = "left").first(),
        )
    }

    /**
     * An APPENDED set has no prescription at all, so its side has nothing to
     * be struck against -- the same rule that keeps its load from striking.
     */
    @Test
    fun `a set the plan never prescribed strikes no side`() {
        assertEquals(
            SetCardValue(stated = "Right"),
            values(side = "right", plannedSide = null).first(),
        )
    }

    @Test
    fun `a stated load equal to the plan's strikes nothing`() {
        // The lifter typed the number that was already there, or the #124 load
        // carry re-stated it for them. A strike here would train the lifter to
        // stop reading strikes.
        assertNull(load(values(statedLoadKg = 90.0)).planned)
    }

    @Test
    fun `a stated zero against a plan that declared no load draws no load at all`() {
        // SetLoadPolicy.resolve reads a null declaration as nothing added, so
        // a stated 0 records exactly what the plan asked for and there is
        // nothing to strike. The card draws no load figure for a loaded lift
        // the plan gave no load for, before this change and after it, so the
        // silence is an ABSENT value and not an unstruck one -- which is why
        // this asserts the whole line rather than one field of a value that
        // is not there.
        assertEquals(
            listOf(
                SetCardValue(stated = "5", suffix = "reps"),
                SetCardValue(stated = "4010", prefix = "tempo"),
            ),
            values(plannedLoadKg = null, statedLoadKg = 0.0, declaredLoadKg = null),
        )
    }

    @Test
    fun `a changed load strikes the plan's figure and states the lifter's`() {
        assertEquals(
            SetCardValue(stated = "100 kg", planned = "90 kg"),
            load(values(statedLoadKg = 100.0)),
        )
    }

    @Test
    fun `stripping the bar strikes ninety and states zero`() {
        // Zero is a statement, not an absence -- the same rule
        // SetLoadPolicy.standingStatedAddedKg keeps one file over. The card's
        // own load rule drops a non-positive load on the floor, so this is the
        // one place the pair has to override it: a struck 90 with nothing
        // beside it says less than the sentence it replaced.
        assertEquals(
            SetCardValue(stated = "0 kg", planned = "90 kg"),
            load(values(statedLoadKg = 0.0)),
        )
    }

    @Test
    fun `body-weight work strikes and states the composite`() {
        // One notation on both sides, not two. #160.
        assertEquals(
            SetCardValue(stated = "BW − 20 kg", planned = "BW − 50 kg"),
            load(values(bodyweight = true, plannedLoadKg = -50.0, declaredLoadKg = -50.0, statedLoadKg = -20.0)),
        )
        assertEquals(
            SetCardValue(stated = "BW + 10 kg", planned = "BW"),
            load(values(bodyweight = true, plannedLoadKg = null, declaredLoadKg = null, statedLoadKg = 10.0)),
        )
    }

    @Test
    fun `a changed rep count strikes the count and keeps one word`() {
        assertEquals(
            SetCardValue(stated = "8", planned = "5", suffix = "reps"),
            values(reps = 8).first(),
        )
    }

    /**
     * RED (#227 item 1). One rep is the noun's singular, not its plural with a
     * digit swapped in front -- "1 reps" reads as a card that does not know
     * how to count, on a card the lifter reads every set of every session.
     * The suffix follows the STATED figure, the one describing what the set
     * IS now, so a set corrected down to one rep singularises even with a
     * plural count struck beside it.
     */
    @Test
    fun `a single rep says rep, not reps`() {
        assertEquals(
            SetCardValue(stated = "1", suffix = "rep"),
            values(plannedReps = 1, reps = 1).first(),
        )
        assertEquals(
            SetCardValue(stated = "1", planned = "5", suffix = "rep"),
            values(plannedReps = 5, reps = 1).first(),
        )
        assertEquals("1 rep · 90 kg · tempo 4010", SetCardValues.plain(values(plannedReps = 1, reps = 1)))
    }

    @Test
    fun `a changed hold strikes the seconds and names the movement once`() {
        // A carry is not a hold, and the word is drawn once whichever it is.
        assertEquals(
            SetCardValue(stated = "45s", planned = "30s", suffix = "hold"),
            values(
                kind = ExerciseKind.HOLD,
                timed = true,
                plannedReps = null,
                reps = null,
                plannedDurationS = 30,
                durationS = 45,
            ).first(),
        )
        assertEquals(
            SetCardValue(stated = "45s", planned = "30s", suffix = "carry"),
            values(
                kind = ExerciseKind.CARRY,
                timed = true,
                plannedReps = null,
                reps = null,
                plannedDurationS = 30,
                durationS = 45,
            ).first(),
        )
    }

    @Test
    fun `a changed tempo strikes the digits after one tempo word`() {
        assertEquals(
            SetCardValue(stated = "6010", planned = "4010", prefix = "tempo"),
            values(tempo = "6010").last(),
        )
    }

    @Test
    fun `a tempo written with dashes is not a change from the same tempo without`() {
        // "4-0-1-0" and "4010" are one prescription in two spellings. A string
        // compare would strike a figure on every set of every plan written the
        // long way.
        assertNull(values(plannedTempo = "4-0-1-0", tempo = "4010").last().planned)
        assertNull(values(plannedTempo = "4010", tempo = "4-0-1-0").last().planned)
    }

    @Test
    fun `nothing is struck against a declaration the plan never made`() {
        // A tempo cannot be ADDED by the control, and a timed set has no rep
        // count to deviate from.
        assertNull(values(plannedTempo = null, tempo = "4010").last().planned)
        assertNull(values(plannedReps = null, reps = 8).first().planned)
    }

    @Test
    fun `an appended set states its load and strikes nothing`() {
        // An appended set carries NO prescription -- plannedLoadKg,
        // plannedReps, plannedDurationS and plannedTempo are all null on it --
        // so its load is read from the slot's own declaration and there is
        // nothing to strike it against. Before #204 the deviation line named
        // that load as a "change" from a plan that never asked for anything.
        val appended =
            values(
                plannedLoadKg = null,
                statedLoadKg = null,
                declaredLoadKg = 100.0,
                plannedReps = null,
                reps = 8,
                plannedTempo = null,
                tempo = null,
            )
        assertEquals(
            listOf(
                SetCardValue(stated = "8", suffix = "reps"),
                SetCardValue(stated = "100 kg"),
            ),
            appended,
        )
    }

    @Test
    fun `a timed set the plan gave no load for says bodyweight`() {
        // The card's own rule, kept: a plank with no added load is not a set
        // with no load, it is a set loaded by the lifter.
        assertEquals(
            listOf(
                SetCardValue(stated = "30s", suffix = "hold"),
                SetCardValue(stated = "bodyweight"),
            ),
            values(
                kind = ExerciseKind.HOLD,
                timed = true,
                plannedLoadKg = null,
                declaredLoadKg = null,
                plannedReps = null,
                reps = null,
                plannedDurationS = 30,
                durationS = 30,
                plannedTempo = null,
                tempo = null,
            ),
        )
    }

    @Test
    fun `the card states the load SetLoadPolicy will record`() {
        // SetCardValues restates resolve's planned-set rule rather than
        // calling it, because the card deals in strings and the policy in a
        // Double. RecordViewModel hands both the same two facts -- resolve
        // gets plannedAddedKg = slot.loadKg and statedAddedKg =
        // state.statedLoadKg, which reach the card as declaredLoadKg and
        // statedLoadKg -- so the two agree today. Nothing enforced it. A card
        // stating a load the set will not record is the failure #204 exists
        // to remove, so a later change to either rule reds here.
        fun recorded(declared: Double?, stated: Double?) = SetLoadPolicy.resolve(
            adHoc = false,
            plannedAddedKg = declared,
            typedAddedKg = 999.0,
            statedAddedKg = stated,
        )
        // A statement displaces the declaration, a declaration stands alone,
        // a stated zero is a statement, and the typed field -- one string
        // reused across the session -- is not evidence on a planned set.
        assertEquals(
            WeightUnit.KG.format(recorded(declared = 90.0, stated = 100.0)),
            load(values(declaredLoadKg = 90.0, statedLoadKg = 100.0)).stated,
        )
        assertEquals(
            WeightUnit.KG.format(recorded(declared = 90.0, stated = null)),
            load(values(declaredLoadKg = 90.0, statedLoadKg = null)).stated,
        )
        assertEquals(
            WeightUnit.KG.format(recorded(declared = 90.0, stated = 0.0)),
            load(values(declaredLoadKg = 90.0, statedLoadKg = 0.0)).stated,
        )
        // The one case where the two deliberately do not share a string:
        // nothing declared and nothing stated records 0 added, and the card
        // names no load at all rather than drawing a "0 kg" the plan never
        // asked for. Absence stays absence on the screen.
        assertEquals(0.0, recorded(declared = null, stated = null))
        assertTrue(
            values(plannedLoadKg = null, declaredLoadKg = null).none {
                it.prefix.isEmpty() && it.suffix.isEmpty()
            },
        )
    }

    /**
     * RED (#227 item 4). An appended set carries no prescription at all --
     * every planned* field is null -- so a TIMED appended set with a load
     * must strike nothing, the same rule an appended REP set already gets for
     * its load (see `an appended set states its load and strikes nothing`
     * above). `loadLabel`'s "timed set with nothing added" fallback exists to
     * say "bodyweight" for a genuinely prescribed hold with no load, using
     * [SetCardValue.planned]'s null as its only signal that nothing was
     * declared -- but an appended set's plannedLoadKg is ALSO null, for a
     * different reason: nothing was ever prescribed, not even a hold. Before
     * the fix, [SetCardValues.of] could not tell those two nulls apart and
     * struck a "bodyweight" the plan never said, against a set that carries a
     * real added load.
     */
    @Test
    fun `a timed appended set with a load strikes no planned bodyweight`() {
        val appended =
            values(
                kind = ExerciseKind.HOLD,
                timed = true,
                plannedLoadKg = null,
                statedLoadKg = null,
                declaredLoadKg = 10.0,
                plannedReps = null,
                reps = null,
                plannedDurationS = null,
                durationS = 30,
                plannedTempo = null,
                tempo = null,
            )
        assertEquals(SetCardValue(stated = "10 kg"), load(appended))
    }

    /**
     * The prescribed counterpart, kept green: a genuinely planned timed hold
     * with no load, where the lifter has now added one, DOES strike a
     * "bodyweight" the plan can be said to have asked for -- the signal is
     * [plannedDurationS] being non-null, which an appended set never carries.
     */
    @Test
    fun `a prescribed timed hold that gains a load strikes the plan's bodyweight`() {
        val loaded =
            values(
                kind = ExerciseKind.HOLD,
                timed = true,
                plannedLoadKg = null,
                statedLoadKg = 10.0,
                declaredLoadKg = null,
                plannedReps = null,
                reps = null,
                plannedDurationS = 30,
                durationS = 30,
                plannedTempo = null,
                tempo = null,
            )
        assertEquals(SetCardValue(stated = "10 kg", planned = "bodyweight"), load(loaded))
    }

    @Test
    fun `an untouched prep has no pair and a changed one does`() {
        assertNull(SetCardValues.prep(plannedPrepS = 10, prepS = 10))
        assertEquals(
            SetCardValue(stated = "20s", planned = "10s", prefix = "prep"),
            SetCardValues.prep(plannedPrepS = 10, prepS = 20),
        )
    }
}
