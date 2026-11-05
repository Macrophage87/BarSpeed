package com.macrophage.barspeed.model

/**
 * One decoded IMU measurement.
 *
 * Units follow the WitMotion convention: acceleration in g, angular velocity in
 * degrees/second, orientation angles in degrees. [timestampMs] is host arrival
 * time in milliseconds relative to the stream epoch.
 *
 * ## An arrival stamp is not a measurement instant (#292)
 *
 * [timestampMs] is when Android's Bluetooth stack handed the notification to
 * the app -- `WitmotionClient.onNotification` reads the clock and
 * `WitmotionStreamDecoder.feed` copies that one value onto every frame the
 * notification carried -- because the 0x61 frame carries no device time to
 * stamp from. Nothing downstream re-stamps it. So the stamp includes however
 * long the sample spent in the sensor, the radio link and the phone's stack,
 * and this code has no way to measure that delay.
 *
 * MEASURED ONCE, AND NOT CORRECTED. On field-42 (app 0.1.52), with both units
 * on one body on sets 1-7, role a's content ran a CONSTANT 600-620 ms behind
 * role b's by gyro-magnitude cross-correlation (r 0.70-0.93 at that lag,
 * -0.03 to +0.35 at zero; the same lag in every third of every set, so an
 * offset and not drift). That link delivered 44 frames per second in 90 ms
 * bursts against role b's 99. Four other two-unit sessions (field-41, 43, 44
 * and 45), every link at 96.8-99.6 Hz in 31 ms bursts, agree within -120 to
 * +80 ms on every one-body set but field-41 set 4 (+240 ms, r 0.67 against
 * 0.54 at zero), and the sign wanders between sets of one session -- no
 * fixed per-unit lag explains that. Which unit was right in absolute terms
 * is not established. No offset is applied anywhere: a fixed per-unit
 * correction would move the four healthy sessions 600 ms off, and one keyed
 * on a starved link would be fitted to one session. Capture faithfully,
 * align offline -- two co-mounted streams carry their own lag estimate.
 *
 * WHERE IT BITES. A constant offset changes no duration, so velocities, ROM
 * and tempo are not moved by it. What it moves is every comparison of a
 * stream stamp against an APP-CLOCK instant -- `PrepWindow.workStartedAtMs`,
 * a cue's `VoiceCue.timestampMs` -- which is what `WorkStart`, `SetEnd` and
 * `RollExcursion` in `:core:dsp` each do; each points here rather than
 * restating this. On a link like field-42's the motion is stamped at least
 * about 600 ms after it happened: the lag was measured against the other
 * unit, whose own delay is unknown, and an arrival stamp cannot precede the
 * motion. So against those bounds handling that ended within that link's
 * delay -- 600 ms or more -- BEFORE work began can read as inside the
 * working window, and a real drive begun within it before the terminal cue
 * can read as after it.
 *
 * [Field]: three rack-pin taps with both units on the bar, on a set where
 * Devices shows both streaming; read each unit's impact instant in the
 * export. Pass: within 40 ms of each other.
 */
data class ImuSample(
    val timestampMs: Long,
    val axG: Double,
    val ayG: Double,
    val azG: Double,
    val wxDps: Double,
    val wyDps: Double,
    val wzDps: Double,
    val rollDeg: Double,
    val pitchDeg: Double,
    val yawDeg: Double,
)

/**
 * One decoded heart-rate measurement. RR intervals are in milliseconds.
 *
 * [contact] is what the notification said about skin contact (#82), and
 * [SensorContact.NOT_RECORDED] by default, which is what every sample decoded
 * from a file written before the field existed must say.
 */
data class HrSample(
    val timestampMs: Long,
    val bpm: Int,
    val rrIntervalsMs: List<Double> = emptyList(),
    val contact: SensorContact = SensorContact.NOT_RECORDED,
)

/**
 * The Heart Rate Measurement characteristic's Sensor Contact bits, as one
 * notification reported them (#82).
 *
 * Bit 2 of the flags byte is Sensor Contact Support and bit 1 is Sensor
 * Contact Status. A strap that sets Support says, per notification, whether it
 * detected skin contact; one that clears it says nothing either way, and a
 * stream decoded from a file written before this was carried says nothing
 * either.
 *
 * THREE STATES, NOT TWO, and the third is the point. A boolean would have to
 * call every historical capture and every strap that does not report contact
 * either "worn" or "not worn", and both are claims nobody measured: absence
 * rendered as a value, one level below the trust check it feeds.
 * [NOT_RECORDED] covers both "the strap did not report it" and "this file
 * predates the column", which are the same fact for every reader here:
 * nothing is known about contact. A reader needing to tell them apart has the
 * file's header -- three columns or four -- and the export's app version.
 *
 * DETECTED is what the strap claimed, not what was true. Whether the strap in
 * use sets either bit at all has never been observed: the flags byte was
 * discarded before anything was stored.
 */
enum class SensorContact {
    /** Support set, Status set: the strap reported skin contact. */
    DETECTED,

    /** Support set, Status clear: the strap reported no skin contact. */
    NOT_DETECTED,

    /** Support clear, or a stream written before contact was recorded. */
    NOT_RECORDED,
}

/** Rep phases, in the order they occur for an eccentric-first lift. */
enum class Phase { IDLE, ECCENTRIC, BOTTOM_PAUSE, CONCENTRIC, TOP_PAUSE }

/** Which phase a lift begins with (squat/bench: ECCENTRIC, deadlift: CONCENTRIC). */
enum class StartPhase { ECCENTRIC, CONCENTRIC }
