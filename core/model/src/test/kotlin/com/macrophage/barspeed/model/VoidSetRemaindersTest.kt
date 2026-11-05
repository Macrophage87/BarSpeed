package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Issue 232's remainders of the void mark (#60): which number the
 * confirmation gives a set, and whether taking the mark off asks first.
 */
class VoidSetRemaindersTest {
    private data class Row(val exerciseId: String, val orderIdx: Int)

    /**
     * The shape issue 232 gives for field-37: the thirteenth set of the
     * session is a later set of the dead hang, and the card never shows 13.
     * The other exercise and its eleven sets are made up for this test, not
     * read from the capture; here a lifter counting dead-hang cards reaches 2.
     */
    private val session =
        (0 until 11).map { Row("leg_press", it) } + Row("rope_dead_hang", 11) + Row("rope_dead_hang", 12)

    @Test
    fun `a set's number is its place among its own exercise's sets`() {
        assertEquals(2, VoidSetPolicy.setNumberInExercise(session, session[12], { it.exerciseId }, { it.orderIdx }))
        assertEquals(1, VoidSetPolicy.setNumberInExercise(session, session[11], { it.exerciseId }, { it.orderIdx }))
        assertEquals(11, VoidSetPolicy.setNumberInExercise(session, session[10], { it.exerciseId }, { it.orderIdx }))
    }

    /** Session order is the order, whatever order the list arrived in. */
    @Test
    fun `the number does not depend on the list's order`() {
        val shuffled = session.reversed()
        assertEquals(2, VoidSetPolicy.setNumberInExercise(shuffled, session[12], { it.exerciseId }, { it.orderIdx }))
    }

    @Test
    fun `a set not in the list gets no number`() {
        assertEquals(0, VoidSetPolicy.setNumberInExercise(session, Row("x", 99), { it.exerciseId }, { it.orderIdx }))
    }

    /** The question quotes what the tap deletes. */
    @Test
    fun `the un-void question quotes the reason it deletes`() {
        val text = VoidSetPolicy.unvoidConfirmation("app re-armed the slot")
        assertTrue("\"app re-armed the slot\"" in text, text)
        assertTrue("deleted" in text, text)
        assertTrue("cannot be brought back" in text, text)
    }
}
