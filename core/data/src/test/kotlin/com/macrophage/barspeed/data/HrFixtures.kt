package com.macrophage.barspeed.data

import com.macrophage.barspeed.model.HrSample

/**
 * The two real heart-rate captures these tests are checked against.
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
 * Decoded with [HrCsv], the same codec the app writes these files with, rather
 * than a reader local to the tests -- a second parser would be free to disagree
 * with the one that produced the artifact.
 */
object HrFixtures {
    const val UNWORN_SETS = 3
    const val WORN_SETS = 17

    /** Session 28, the strap on a table. Sets are 1-based, as the filenames are. */
    fun unworn(set: Int): List<HrSample> = load("field-hrm-unworn-s28-set$set.csv")

    /** Session 26, the strap worn. Sets are 1-based. */
    fun worn(set: Int): List<HrSample> = load("field-hrm-worn-s26-set${set.toString().padStart(2, '0')}.csv")

    fun allUnworn(): List<List<HrSample>> = (1..UNWORN_SETS).map(::unworn)

    fun allWorn(): List<List<HrSample>> = (1..WORN_SETS).map(::worn)

    private fun load(name: String): List<HrSample> {
        val stream =
            checkNotNull(HrFixtures::class.java.getResourceAsStream("/$name")) {
                "missing heart-rate fixture $name"
            }
        return HrCsv.decode(stream.use { it.readBytes() }.decodeToString())
    }
}
