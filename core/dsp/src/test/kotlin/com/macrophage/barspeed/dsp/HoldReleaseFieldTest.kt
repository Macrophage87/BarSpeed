package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.PrepCase
import com.macrophage.barspeed.model.RestClockPolicy
import com.macrophage.barspeed.model.SetClockPolicy
import com.macrophage.barspeed.model.TimedSetEndPolicy
import com.macrophage.barspeed.model.VoiceCue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the app records for a hold the lifter ends by hand, measured on the
 * four hold streams committed for #259 -- and what their own accelerometers
 * say about when the hold ended. Characterization first: every assertion in
 * this file is what the shipped code answers.
 *
 * The owner, 2026-09-05, which is the defect:
 *
 * > "The problem with a lot of these holds are that my hands are full because
 * > they are holding onto the rope. So it takes about 5-10 sec to actually
 * > grab my phone."
 *
 * ## The four streams and where each number here comes from
 *
 * Every instant below is read from the session archive's own `meta.json`, and
 * every target from the committed `-cues.csv` beside the stream, this round.
 * Nothing is taken from an issue body.
 *
 * - `field-ropedeadhang-hold45-s38-set17` -- field-38 (app 0.1.50, WitMotion
 *   WT901BLECL, 2026-09-04), set 17, `rope_dead_hang`, `kind: "hold"`,
 *   29.369 kg on the assist stack, `sensorOnStack` true, two units armed,
 *   `analysedRole: "a"`. Clock started 1788517883914, the write froze at
 *   1788517920142: 36.228 s of measured clock. Published `duration_s` 31,
 *   `failed` and `failedByLifter` true. The owner has confirmed he tapped the
 *   -5 s correction on this hang (#249), so 31 is 36 with one tap taken off.
 * - `field-ropedeadhang-hold45-s38-set18` -- the same session and exercise,
 *   set 18, 22.582564147942733 kg. Clock 1788517994385 to 1788518026573:
 *   32.188 s measured, published 22, which is two taps.
 * - `field-ropedeadhang-hold45-s38-set18-imu-b` -- set 18's SECOND unit. The
 *   owner's word for the two mounts is one on the assist stack and one in his
 *   pocket; nothing in the archive records which is which, so this file says
 *   only that it is role `b` and the set analysed role `a`.
 * - `field-ropefarmershold-hold30-s42-set16` -- field-42 (app 0.1.52,
 *   2026-09-07), set 16, `rope_farmers_hold`, 40.82331330090319 kg (90.0 lb),
 *   both units on the rope handle per the session's mount table, `rpe` 6 on
 *   the seconds ladder. Clock 1788776811073 to 1788776841088: 30.015 s, and
 *   its cue track ends on `Time` at 1788776841087 -- the app's own clock ended
 *   this one at the target, so no reach is inside it.
 *
 * ## What is NOT claimed
 *
 * Which mount each unit was on, what the lifter felt, and whether a release
 * always looks like this. The instants are the app's; the deviation figures
 * are arithmetic over committed samples.
 */
class HoldReleaseFieldTest {
    private data class Hold(
        val fixture: String,
        val tappedAtMs: Long,
        val clockStartedAtMs: Long,
        val endedAtMs: Long,
        val targetS: Int,
    )

    private val set17 = Hold(
        fixture = "field-ropedeadhang-hold45-s38-set17",
        tappedAtMs = 1788517875899,
        clockStartedAtMs = 1788517883914,
        endedAtMs = 1788517920142,
        targetS = 45,
    )
    private val set18 = Hold(
        fixture = "field-ropedeadhang-hold45-s38-set18",
        tappedAtMs = 1788517986372,
        clockStartedAtMs = 1788517994385,
        endedAtMs = 1788518026573,
        targetS = 45,
    )
    private val carry = Hold(
        fixture = "field-ropefarmershold-hold30-s42-set16",
        tappedAtMs = 1788776801058,
        clockStartedAtMs = 1788776811073,
        endedAtMs = 1788776841088,
        targetS = 30,
    )

    private fun load(fixture: String): List<ImuSample> =
        ImuCsv.decode(javaClass.getResourceAsStream("/$fixture.csv")!!.readBytes().decodeToString())

    private fun cues(fixture: String): List<VoiceCue> =
        CueTrack.read(fixture).map { VoiceCue(timestampMs = it.timestampMs, cue = it.label) }

    /**
     * The deviation from one gravity this file measures a release by, in g.
     *
     * [HoldRelease.deviationG]'s own arithmetic, not a copy of it: a second
     * expression here would let the pins measure one quantity while the
     * decision measured another, and they would agree for exactly as long as
     * nobody touched either. It was a local `abs(sqrt(...) - 1.0)` for one
     * commit, before the detector existed to ask.
     */
    private fun deviationG(s: ImuSample): Double = HoldRelease.deviationG(s)

    private fun inWindow(hold: Hold, fixture: String = hold.fixture): List<ImuSample> =
        load(fixture).filter { it.timestampMs >= hold.clockStartedAtMs }

    @Test
    fun `the committed streams are the streams the archive holds`() {
        // Sample counts and end stamps, so a fixture replaced or re-encoded
        // fails here rather than quietly moving every figure below it.
        assertEquals(4392, load(set17.fixture).size, "set 17 role a samples")
        assertEquals(1788517875907, load(set17.fixture).first().timestampMs, "set 17 first arrival")
        assertEquals(1788517920093, load(set17.fixture).last().timestampMs, "set 17 last arrival")
        assertEquals(3992, load(set18.fixture).size, "set 18 role a samples")
        assertEquals(3992, load("${set18.fixture}-imu-b").size, "set 18 role b samples")
        assertEquals(1772, load(carry.fixture).size, "set 16 role a samples")
        // The capture starts a whole prep before the clock does, which is why
        // everything below filters from clockStartedAtMs rather than trusting
        // the stream to begin at the hold.
        assertEquals(3596, inWindow(set17).size, "set 17 samples inside the hold")
        assertEquals(3196, inWindow(set18).size, "set 18 samples inside the hold")
        assertEquals(1332, inWindow(carry).size, "set 16 samples inside the hold")
    }

    @Test
    fun `the targets come off the cue tracks, and a broken hold has no terminal word`() {
        // TimedSetVoice speaks REMAINING seconds, so a milestone plus its own
        // offset from `Hold` is the target. This is where the 45 s and 30 s
        // above are from.
        val hold17 = cues(set17.fixture).first { it.cue == "Hold" }.timestampMs
        val milestone17 = cues(set17.fixture).first { it.cue == "30 seconds" }.timestampMs
        assertEquals(45L, (milestone17 - hold17) / 1000L + 30L, "set 17's target, from its own track")
        val hold16 = cues(carry.fixture).first { it.cue == "Hold" }.timestampMs
        val milestone16 = cues(carry.fixture).first { it.cue == "15 seconds" }.timestampMs
        assertEquals(30L, (milestone16 - hold16) / 1000L + 15L, "set 16's target, from its own track")

        // Both hangs were ended by the lifter BEFORE the target, so the
        // countdown was still running and no terminal word was ever spoken:
        // set 17's track stops on `9` and set 18's on `15 seconds`. The carry
        // ran to its target and ends on `Time`. That difference is what
        // decides which instant the rest clock can find, below.
        assertEquals("9", cues(set17.fixture).last().cue, "set 17's last spoken word")
        assertEquals("15 seconds", cues(set18.fixture).last().cue, "set 18's last spoken word")
        assertEquals("Time", cues(carry.fixture).last().cue, "set 16's last spoken word")
        assertEquals(1788776841087, cues(carry.fixture).last().timestampMs, "the carry's Time instant")
    }

    @Test
    fun `what each hold records today is the span that runs to the tap`() {
        // The defect, as arithmetic. On a TIMED prep the clock start IS the
        // work start, so the recorded span is the clock's own span -- and on a
        // hold the lifter ended, its far end is the tap, which the owner says
        // is 5-10 s after the hold was over.
        val measured17 = SetClockPolicy.heldSeconds(
            case = PrepCase.TIMED,
            tappedAtMs = set17.tappedAtMs,
            clockStartedAtMs = set17.clockStartedAtMs,
            endedAtMs = set17.endedAtMs,
        )
        val measured18 = SetClockPolicy.heldSeconds(
            case = PrepCase.TIMED,
            tappedAtMs = set18.tappedAtMs,
            clockStartedAtMs = set18.clockStartedAtMs,
            endedAtMs = set18.endedAtMs,
        )
        val measured16 = SetClockPolicy.heldSeconds(
            case = PrepCase.TIMED,
            tappedAtMs = carry.tappedAtMs,
            clockStartedAtMs = carry.clockStartedAtMs,
            endedAtMs = carry.endedAtMs,
        )
        assertEquals(36, measured17, "set 17's measured seconds")
        assertEquals(32, measured18, "set 18's measured seconds")
        assertEquals(30, measured16, "set 16's measured seconds")

        // And what the write stores: the measurement for a hold the lifter
        // ended, the target for one the clock ended.
        assertEquals(
            36,
            TimedSetEndPolicy.recordedSeconds(measured17, set17.targetS, autoEnded = false),
            "set 17 records its full span, reach included",
        )
        assertEquals(
            32,
            TimedSetEndPolicy.recordedSeconds(measured18, set18.targetS, autoEnded = false),
            "set 18 records its full span, reach included",
        )
        assertEquals(
            30,
            TimedSetEndPolicy.recordedSeconds(measured16, carry.targetS, autoEnded = true),
            "the carry records the target it ran to",
        )
        // The verdict on each, which the sensor end must not disturb: both
        // hangs fell short of 45 s and the carry did not fall short of 30.
        assertTrue(TimedSetEndPolicy.fellShort(36, set17.targetS), "set 17 fell short")
        assertTrue(TimedSetEndPolicy.fellShort(32, set18.targetS), "set 18 fell short")
        assertFalse(TimedSetEndPolicy.fellShort(30, carry.targetS), "the carry did not")
    }

    @Test
    fun `the rest after a broken hold runs from the tap, because nothing called it over`() {
        // #172 moved the rest clock's origin to the instant the set was called
        // over, read off the set's own frozen cue track. A hold broken before
        // its target has no such word, so the fallback -- the write instant,
        // which is the tap -- is what the countdown gets, and the 5-10 s reach
        // is inside the rest as well as inside the duration.
        assertEquals(
            SetEnd.NotCued,
            SetEnd.calledOver(cues(set17.fixture)),
            "set 17: nothing on the record called it over",
        )
        assertEquals(
            set17.endedAtMs,
            RestClockPolicy.startedAtMs(
                setOverCueAtMs = (SetEnd.calledOver(cues(set17.fixture)) as? SetEnd.Cued)?.atMs,
                sensorEndAtMs = null,
                endedAtMs = set17.endedAtMs,
            ),
            "set 17's rest runs from the tap",
        )
        // The carry, and the finding that took a run to establish: `Time` is
        // NOT in SetEnd.TERMINAL_CUES -- the vocabulary is `Done` and `Set
        // ended` -- so a hold that ran to its target is ALSO NotCued, and its
        // rest also runs from the write instant. It costs nothing there,
        // because the app ends the set on the same tick it speaks the word:
        // `Time` at 1788776841087 against a write at 1788776841088, 1 ms
        // apart. So for a hold the write instant is the only instant the rest
        // clock has, which is precisely why a hold ended by hand carries the
        // reach into the rest as well as into the duration.
        assertEquals(
            SetEnd.NotCued,
            SetEnd.calledOver(cues(carry.fixture)),
            "Time is not a terminal cue, so the carry is NotCued too",
        )
        assertEquals(
            1L,
            carry.endedAtMs - cues(carry.fixture).last().timestampMs,
            "the carry's write lands 1 ms after the word",
        )
        assertEquals(
            carry.endedAtMs,
            RestClockPolicy.startedAtMs(
                setOverCueAtMs = (SetEnd.calledOver(cues(carry.fixture)) as? SetEnd.Cued)?.atMs,
                sensorEndAtMs = null,
                endedAtMs = carry.endedAtMs,
            ),
            "the carry's rest runs from the write, 1 ms after Time",
        )
    }

    @Test
    fun `the release is one sample in a quiet stream, and a carry has none`() {
        // The corpus profile the #259 detector's band is chosen against,
        // measured here before any detector exists. Deviation from 1 g, over
        // the samples inside the hold.
        //
        // Both hangs: one instant crosses 1 g and everything else is under
        // 0.57. On set 17 that instant is 1788517913103, 29.189 s into a hold
        // the write closed at 36.228 s -- 7.039 s earlier, which is inside the
        // owner's own estimate of his reach. On set 18 it is 1788518020443,
        // 26.058 s into a hold closed at 32.188 s: 6.130 s earlier. Two
        // samples share that stamp, which is the burst arrival `ImuCsv`'s
        // header warns about rather than two events.
        assertEquals(
            listOf(1788517913103L),
            inWindow(set17).filter { deviationG(it) > 1.0 }.map { it.timestampMs },
            "set 17 crossings",
        )
        assertEquals(
            listOf(1788518020443L, 1788518020443L),
            inWindow(set18).filter { deviationG(it) > 1.0 }.map { it.timestampMs },
            "set 18 crossings",
        )
        assertEquals(5.213, round3(inWindow(set17).maxOf { deviationG(it) }), "set 17 peak deviation")
        assertEquals(7.923, round3(inWindow(set18).maxOf { deviationG(it) }), "set 18 peak deviation")
        assertEquals(
            0.569,
            round3(inWindow(set17).filter { deviationG(it) <= 1.0 }.maxOf { deviationG(it) }),
            "set 17's largest deviation under the band",
        )

        // The carry crosses NOTHING. Thirty seconds of walking with 90 lb on a
        // rope handle peaks at 0.542 g, so the band a release crosses is not a
        // band a carry crosses -- and the release it has, putting the handles
        // down after `Time`, is outside the capture because the app had
        // already ended the set.
        assertEquals(
            emptyList(),
            inWindow(carry).filter { deviationG(it) > 1.0 }.map { it.timestampMs },
            "the carry crosses nothing",
        )
        assertEquals(0.542, round3(inWindow(carry).maxOf { deviationG(it) }), "the carry's peak deviation")

        // And the partner, which is why a crossing cannot be trusted just for
        // being the first one: role b crosses at 1788517995410, 1.025 s into
        // the hold -- the start of the hang, not its end -- and eight more
        // times after that. Its quiet level is also far higher: 0.984 g under
        // the band against role a's 0.505 g on the same set.
        val partner = inWindow(set18, "${set18.fixture}-imu-b")
        val partnerCrossings = partner.filter { deviationG(it) > 1.0 }.map { it.timestampMs }
        assertEquals(9, partnerCrossings.size, "role b crossings")
        assertEquals(1788517995410, partnerCrossings.first(), "role b's first crossing is the step off")
        assertEquals(1788518017698, partnerCrossings[1], "role b's second crossing")
        assertEquals(
            0.984,
            round3(partner.filter { deviationG(it) <= 1.0 }.maxOf { deviationG(it) }),
            "role b's largest deviation under the band",
        )
    }

    @Test
    fun `the release instant each hold's own armed unit saw`() {
        // What `HoldRelease` answers over the four committed streams. Nothing
        // consumes it yet; this is the measurement the decision will be made
        // against.
        assertEquals(
            1788517913103,
            HoldRelease.atMs(load(set17.fixture), set17.clockStartedAtMs),
            "set 17's release",
        )
        assertEquals(
            1788518020443,
            HoldRelease.atMs(load(set18.fixture), set18.clockStartedAtMs),
            "set 18's release",
        )
        // The carry ran to `Time` with the handles still in the lifter's
        // hands, so there is no release inside the capture at all. Null, and
        // not a low number: a hold with nothing to say must not be able to
        // shorten itself.
        assertNull(
            HoldRelease.atMs(load(carry.fixture), carry.clockStartedAtMs),
            "the carry has no release in its capture",
        )
        // The partner: the SETTLED_MS guard is what makes this 23.313 s
        // instead of the 1.025 s step-off pinned above.
        assertEquals(
            1788518017698,
            HoldRelease.atMs(load("${set18.fixture}-imu-b"), set18.clockStartedAtMs),
            "set 18's partner unit",
        )
    }

    @Test
    fun `the release sits where a reach would put it, seconds before the tap`() {
        // The arithmetic the recorded seconds will be made of, in the same
        // function the measured span comes from -- so a sensor end and a tap
        // end are the same kind of number, floored the same way.
        val release17 = HoldRelease.atMs(load(set17.fixture), set17.clockStartedAtMs)!!
        val release18 = HoldRelease.atMs(load(set18.fixture), set18.clockStartedAtMs)!!
        assertEquals(29, secondsTo(set17, release17), "set 17's seconds to the release")
        assertEquals(26, secondsTo(set18, release18), "set 18's seconds to the release")
        // Against 36 and 32 to the tap: 7 s and 6 s of reach, inside the
        // owner's own "5-10 sec" and inside #172's measured 4.3-13.7 s.
        assertEquals(7, secondsTo(set17, set17.endedAtMs) - secondsTo(set17, release17), "set 17's reach")
        assertEquals(6, secondsTo(set18, set18.endedAtMs) - secondsTo(set18, release18), "set 18's reach")
        assertEquals(7039L, set17.endedAtMs - release17, "set 17's reach in milliseconds")
        assertEquals(6130L, set18.endedAtMs - release18, "set 18's reach in milliseconds")
    }

    private fun secondsTo(hold: Hold, instantMs: Long): Int = SetClockPolicy.heldSeconds(
        case = PrepCase.TIMED,
        tappedAtMs = hold.tappedAtMs,
        clockStartedAtMs = hold.clockStartedAtMs,
        endedAtMs = instantMs,
    )

    private fun round3(v: Double): Double = Math.round(v * 1000.0) / 1000.0
}
