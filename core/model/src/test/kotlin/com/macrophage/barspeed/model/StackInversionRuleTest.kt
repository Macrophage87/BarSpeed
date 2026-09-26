package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What an omitted `sensorInverted` resolves to, and what the import gate says
 * about it. Issue #317.
 *
 * ## Why the question exists
 *
 * Field-41's two triceps pushdowns resolved `sensorOnStack` true from the
 * app's own stack table (their published `geometry.source.sensorOnStack` is
 * `seeded`), `concentric` down, and `sensorInverted` FALSE. A weight stack
 * rises while the handle is driven down, so the stack unit's motion was read
 * with the drive and the return swapped, and the set published one detection
 * for fourteen reps. Whether that plan omitted `sensorInverted` or declared it
 * false cannot be read from the export -- the key has no published source
 * (#289) -- and is stated here as the likelier reading, not as a fact: its
 * `sensorOnStack` was certainly omitted. The same imported plan declared
 * `sensorOnStack` and `sensorInverted` on its lat pulldown and left both
 * pushdowns' `sensorOnStack` to the seed; that is the likelier reading, not
 * a recorded fact.
 *
 * ## What this file pins, and in which commit
 *
 * The cases the rule must NOT move are pinned first and are green before the
 * rule exists and after: a declaration wins either way, horizontal work on a
 * stack never inverts, a stack that rises with the drive never inverts, and a
 * drive-down lift read off the handle never inverts. The case the rule exists
 * for is a separate, red differential.
 */
class StackInversionRuleTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val down = ""","concentric":"down""""
    private val horizontalOnStack = ""","plane":"horizontal","sensorOnStack":true"""
    private val declaredFalse = ""","sensorInverted":false"""
    private val declaredTrue = ""","sensorInverted":true"""
    private val offStack = ""","sensorOnStack":false"""

    private fun document(id: String, declarations: String): String {
        val text =
            """
            {"schemaVersion":"1.12","planName":"P","sessions":[{"name":"S","exercises":[
              {"exercise":"$id"$declarations,"sets":[{"reps":12,"tempo":"1120"}]}
            ]}]}
            """.trimIndent()
        return text
    }

    private fun declared(id: String, declarations: String = ""): PlanExerciseDef =
        json.decodeFromString(PlanFile.serializer(), document(id, declarations)).sessions[0].exercises[0]

    private fun resolved(id: String, declarations: String = ""): ExerciseDef =
        SetGeometryPolicy.resolve(ExerciseDef(id, id), declared(id, declarations))

    private fun inversionLines(id: String, declarations: String = ""): List<String> =
        PlanImport.parse(document(id, declarations)).warnings.filter { "\"sensorInverted\"" in it }

    // ---- what the rule must not move: green before it and after it ----------

    /** A declaration is somebody's word about the machine in front of them. */
    @Test
    fun `a declared false on a stack lift whose drive goes down still wins`() {
        val used = resolved("triceps_pushdown", down + declaredFalse)
        assertTrue(used.sensorOnStack, "the stack mount the app ships for this id")
        assertEquals(false, used.sensorInverted)
    }

    @Test
    fun `a declared true wins on a lift the rule would leave uninverted`() {
        val used = resolved("cable_face_pull", horizontalOnStack + declaredTrue)
        assertEquals(true, used.sensorInverted)
    }

    /**
     * Horizontal work has no up: the drive is oriented positive, and the stack
     * rises on the drive, so the stack stream needs no flip. Field-45's face
     * pull is this shape and reads 9 of its 9 reps uninverted.
     */
    @Test
    fun `an omitted key on horizontal stack work stays uninverted`() {
        assertEquals(false, resolved("cable_face_pull", horizontalOnStack).sensorInverted)
        assertEquals(false, resolved("seated_cable_row", ""","plane":"horizontal"""").sensorInverted)
        assertEquals(
            false,
            resolved("seated_cable_row", ""","plane":"horizontal","concentric":"down"""").sensorInverted,
            "a drive word on horizontal work orients nothing",
        )
    }

    /** A leg extension's stack rises with its drive, so the default is right. */
    @Test
    fun `an omitted key on a stack lift whose drive goes up stays uninverted`() {
        assertEquals(false, resolved("leg_extension").sensorInverted)
        assertEquals(false, resolved("leg_extension", ""","concentric":"up"""").sensorInverted)
    }

    /**
     * The sensor on the handle travels WITH the drive, which is #110's shape:
     * declaring the handle is declaring no stack, and nothing inverts.
     */
    @Test
    fun `an omitted key on a drive-down lift read off the handle stays uninverted`() {
        assertEquals(
            false,
            resolved("triceps_pushdown", down + offStack).sensorInverted,
        )
    }

    /** No drive direction declared means the default drive-up, and #263 warns about that instead. */
    @Test
    fun `an omitted key on a stack lift that declares no drive stays uninverted`() {
        assertEquals(false, resolved("triceps_pushdown").sensorInverted)
    }

    @Test
    fun `the import gate names no inversion where the plan declared the key or nothing is inferred`() {
        assertEquals(emptyList(), inversionLines("triceps_pushdown", down + declaredFalse))
        assertEquals(emptyList(), inversionLines("lat_pulldown", down + declaredTrue))
        assertEquals(emptyList(), inversionLines("triceps_pushdown", down + offStack))
        assertEquals(emptyList(), inversionLines("cable_face_pull", horizontalOnStack))
        assertEquals(emptyList(), inversionLines("leg_extension"))
    }

    // ---- the case the rule exists for: red before it -------------------------

    /**
     * RED before #317's rule. A weight stack rises while the handle is driven
     * down, so on a lift that resolves onto the stack with a downward drive in
     * the vertical plane, the stack unit moves opposite to the lifter's drive.
     * Three ways to get there: field-41's own shape (the stack mount from the
     * app's table), a declared stack mount, and an id the app has never heard
     * of -- the rule reads the resolved geometry, never the words in an id.
     */
    @Test
    fun `an omitted key on a stack lift whose drive goes down resolves inverted`() {
        assertEquals(true, resolved("triceps_pushdown", down).sensorInverted, "field-41's shape")
        assertEquals(
            true,
            resolved("lat_pulldown", down + ""","sensorOnStack":true""").sensorInverted,
            "a declared stack mount",
        )
        assertEquals(
            true,
            resolved("rope_pushdown", down + ""","sensorOnStack":true""").sensorInverted,
            "an id no table carries",
        )
    }

    /**
     * RED before #317's rule. The import gate names each inference it makes, one
     * line per exercise, as it does for a stack mount it applied (#223); an
     * inversion the plan never wrote is not visible anywhere else before the
     * set is recorded -- the export publishes no source for it (#289).
     */
    @Test
    fun `the import gate names the inversion it applied`() {
        val lines = inversionLines("triceps_pushdown", down)
        assertEquals(1, lines.size, "one exercise, one line: $lines")
        assertTrue(lines[0].startsWith("sessions[0].exercises[0]: triceps_pushdown"), lines[0])
        assertTrue("does not declare \"sensorInverted\"" in lines[0], lines[0])
    }

    /**
     * RED before #323's fix. The gate's line said "the set is recorded
     * inverted" whichever unit the set came to be analysed from; since #323 a
     * unit whose own roll moved -- on the handle or the rope -- has its
     * figures analysed uninverted, so the line names the unit the rule reads.
     *
     * Moved in #323's round 1: the line then said the set "is recorded
     * inverted" / "uninverted where it moved with the handle", which
     * overclaimed -- on a set the sensor counts, the recorded rep count is the
     * live tracker's, and that tracker applies the rule whatever the roll says
     * -- and named only the handle where the schema and PLAN_PROMPT both name
     * the rope too. The first pin reads what the analysis does; the second
     * names the rope and what the live count does.
     */
    @Test
    fun `the import gate's inversion line names the unit the rule reaches`() {
        val line = inversionLines("triceps_pushdown", down).single()
        val analysed = "the set's figures are analysed inverted wherever the analysed unit's own roll " +
            "says it rode the stack"
        val live = "uninverted where it moved with the handle or the rope; the live count during the set " +
            "reads it inverted either way"
        assertTrue(analysed in line, line)
        assertTrue(live in line, line)
    }
}
