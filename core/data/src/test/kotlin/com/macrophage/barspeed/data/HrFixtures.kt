package com.macrophage.barspeed.data

import com.macrophage.barspeed.hrm.HrTrust
import com.macrophage.barspeed.hrm.RrIngest
import com.macrophage.barspeed.model.HrSample
import kotlin.math.sqrt

/**
 * The real heart-rate captures these tests are checked against.
 *
 * `unworn` is session 28, app 0.1.38: a chest strap that sat on a table for
 * three sets, confirmed by the lifter. It is the negative control, and it is
 * the capture issue #79 was filed from.
 *
 * `worn` is session 26, app 0.1.37, the same strap on the same body the same
 * day, across five exercises. It is the POSITIVE control, and it is the more
 * important of the two: two candidate rules for this defect were withdrawn
 * because they fired on 16 of these 17 sets, and neither would have been caught
 * without it. Any rule here that looks at more than one sample has to be run
 * against all seventeen and the result stated as a number.
 *
 * What the worn control cannot do is bound its own range, and that has already
 * caused one wrong acceptance: its heart rate is min 78, median 109, and NOT
 * ONE of its 1,930 samples is below 70 bpm. It is silent about a resting
 * athlete, which is the population a heart-rate rule is most likely to hurt.
 *
 * `s30` and `s31` are two further sessions on the same strap and the same
 * body, app 0.1.40, and they exist because of that gap. Each set of each
 * carries BOTH its in-set stream and the REST PERIOD BEFORE IT -- the rest
 * streams are the point, since that is where the heart rate is low. They do
 * not close the gap, they narrow it: the lowest sample across all forty
 * streams is 67 bpm, not the 46 the unworn strap published. Session 30 was
 * deliberately submaximal, session 31 was real training loads across four
 * exercise families.
 *
 * The rest streams are stored under a DIFFERENT kind from the in-set ones --
 * `RawStreamEntity.KIND_REST_BEFORE_HRM` -- and [HrTrust] is never run on them
 * in production. They are evidence about what the rule WOULD do to a worn
 * stream at a low heart rate, which is the question the branch was held for,
 * and they are not evidence about a code path.
 *
 * Decoded with [HrCsv], the same codec the app writes these files with, rather
 * than a reader local to the tests -- a second parser would be free to disagree
 * with the one that produced the artifact.
 */
object HrFixtures {
    const val UNWORN_SETS = 3
    const val WORN_SETS = 17

    /**
     * Sets in the two 0.1.40 captures. Each contributes TWO streams: the set
     * and the rest before it.
     *
     * The blind spot pinned by `FieldHrTrustDischargeTest.the worn control
     * contains no resting heart rate at all` applies to these exactly as it
     * applies to [WORN_SETS]: the accessors below iterate these counts, not the
     * resources directory, so a forty-first capture dropped into
     * `src/test/resources` and left here is a file nothing opens.
     */
    const val S30_SETS = 7
    const val S31_SETS = 13

    /** Session 28, the strap on a table. Sets are 1-based, as the filenames are. */
    fun unworn(set: Int): List<HrSample> = load("field-hrm-unworn-s28-set$set.csv")

    /** Session 26, the strap worn. Sets are 1-based. */
    fun worn(set: Int): List<HrSample> = load("field-hrm-worn-s26-set${set.toString().padStart(2, '0')}.csv")

    /** Session 30 or 31, the strap worn, in-set stream. */
    fun inSet(session: Int, set: Int): List<HrSample> = load("field-hrm-worn-s$session-set${pad(set)}.csv")

    /** Session 30 or 31, the strap worn, the rest period BEFORE that set. */
    fun restBefore(session: Int, set: Int): List<HrSample> = load("field-hrm-worn-s$session-rest${pad(set)}.csv")

    fun allUnworn(): List<List<HrSample>> = (1..UNWORN_SETS).map(::unworn)

    fun allWorn(): List<List<HrSample>> = (1..WORN_SETS).map(::worn)

    /** The twenty in-set streams of the two 0.1.40 captures. */
    fun allInSet(): List<List<HrSample>> = (1..S30_SETS).map { inSet(30, it) } + (1..S31_SETS).map { inSet(31, it) }

    /** The twenty rest streams of the two 0.1.40 captures, which carry the low heart rates. */
    fun allRestBefore(): List<List<HrSample>> =
        (1..S30_SETS).map { restBefore(30, it) } + (1..S31_SETS).map { restBefore(31, it) }

    /**
     * Every stream in this directory recorded with the strap ON a body: 17 + 20
     * + 20 = 57.
     *
     * This is the population any claim about false withholding has to be made
     * over, and it is deliberately not called `allWorn` -- that name is bound to
     * session 26 and a dozen assertions state exact session-26 figures through
     * it. Widening it in place would have rewritten those silently.
     */
    fun everyWornStream(): List<List<HrSample>> = allWorn() + allInSet() + allRestBefore()

    private fun pad(set: Int) = set.toString().padStart(2, '0')

    /**
     * The variability of a stream, as the ONE definition every assertion in
     * these tests uses: the root mean square of the successive differences of
     * the DE-DUPLICATED beats, over the trusted samples only.
     *
     * It was written out four times before this, once per test, and four copies
     * of a definition is how two of them come to disagree about which series
     * they describe. The de-duplication matters to the value -- a series with
     * its ties removed has larger successive differences than the same series
     * with them left in -- so "sigma" here is not a property of the heart. It
     * is a property of the beats that survive [RrIngest.newBeats], which is
     * exactly the series [HrTrust.accountedFraction] measures.
     */
    fun sigmaOf(set: List<HrSample>): Double {
        val beats = RrIngest.newBeats(set.filter(HrTrust::isTrusted))
        val diffs = (1 until beats.size).map { beats[it] - beats[it - 1] }
        return sqrt(diffs.sumOf { it * it } / diffs.size)
    }

    private fun load(name: String): List<HrSample> {
        val stream =
            checkNotNull(HrFixtures::class.java.getResourceAsStream("/$name")) {
                "missing heart-rate fixture $name"
            }
        return HrCsv.decode(stream.use { it.readBytes() }.decodeToString())
    }
}
