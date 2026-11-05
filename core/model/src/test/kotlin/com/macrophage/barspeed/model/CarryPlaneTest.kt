package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The plane the two seeded carries are measured in, and the one place that
 * reads a seed's plane without the seed in hand (#63).
 *
 * The rows here hold on either side of the seed change: they are the
 * neighbours it must not move.
 */
class CarryPlaneTest {
    private fun document(id: String, declarations: String): String = """
        {"schemaVersion":"1.12","planName":"P","sessions":[{"name":"S","exercises":[
          {"exercise":"$id"$declarations,"sets":[{"reps":3}]}
        ]}]}
    """.trimIndent()

    private fun inversionLines(id: String, declarations: String): List<String> =
        PlanImport.parse(document(id, declarations)).warnings.filter { "\"sensorInverted\"" in it }

    private val stackDriveDown = ""","sensorOnStack":true,"concentric":"down""""

    /** No seed but a carry travels horizontally, before or after #63. */
    @Test
    fun `no seed entry other than a carry is horizontal`() {
        val others = ExerciseDef.SEED.filter { it.kind != ExerciseKind.CARRY }
        assertTrue(others.isNotEmpty())
        for (def in others) assertEquals(false, def.horizontal, def.id)
    }

    /**
     * The import gate's inversion line on a vertical seed, and on the same
     * seed declared horizontal: the line follows the plane, declared or not.
     */
    @Test
    fun `the inversion line follows a vertical seed's plane and a declared one`() {
        assertEquals(1, inversionLines("back_squat", stackDriveDown).size)
        assertEquals(emptyList(), inversionLines("back_squat", stackDriveDown + ""","plane":"horizontal""""))
    }

    /** A declared vertical plane on a carry is the plan's word, and is read. */
    @Test
    fun `a carry declared vertical is read vertical by the inversion line`() {
        assertEquals(1, inversionLines("farmers_walk", stackDriveDown + ""","plane":"vertical"""").size)
    }

    /**
     * #63. A farmer's walk and a suitcase carry travel with the walker, so
     * the seed says horizontal, on the ad-hoc path too, which reads the seed.
     */
    @Test
    fun `the two seeded carries travel horizontally`() {
        assertEquals(
            listOf("farmers_walk", "suitcase_carry"),
            ExerciseDef.SEED.filter { it.horizontal }.map { it.id },
        )
        assertTrue(ExerciseDef.resolvedById("farmers_walk").horizontal)
        assertTrue(ExerciseDef.resolvedById("suitcase_carry").horizontal)
    }

    /**
     * The near neighbour of the seed: the import gate's inversion line reads
     * the seed's plane, so a carry with no "plane" is not told its stack
     * stream is inverted -- SetGeometryPolicy.stackInversion leaves
     * horizontal work uninverted, and resolve() reads the same seed.
     */
    @Test
    fun `an undeclared carry's inversion line reads the seed's horizontal plane`() {
        assertEquals(emptyList(), inversionLines("farmers_walk", stackDriveDown))
    }

    /**
     * DIFFERENTIAL, L5 review round 1 (B2). The seed changes what an OMITTED
     * "plane" means on these ids -- the published geometry, which tempo digit
     * is the concentric, the inversion line -- and no copy of the plan
     * contract said so. Every earlier seed (body weight, stack mount) was
     * stated in its key's description. Read off the seed rather than listed,
     * so a third horizontal seed that the contract does not name reds here.
     */
    @Test
    fun `every copy of the plan contract names the carries seeded horizontal`() {
        val seeded = ExerciseDef.SEED.filter { it.horizontal }.map { it.id }
        assertTrue(seeded.isNotEmpty())
        val schemaPlane =
            Json.parseToJsonElement(resource("/plan.schema.json")).jsonObject["\$defs"]!!.jsonObject["exercise"]!!
                .jsonObject["properties"]!!.jsonObject["plane"]!!.jsonObject["description"]!!.jsonPrimitive.content
        val promptPlane =
            resource("/kotlin/com/macrophage/barspeed/ui/screens/GuideScreen.kt").lineSequence()
                .first { it.trimStart().startsWith("\"plane\": \"vertical\" | \"horizontal\"") }
        for (id in seeded) {
            assertTrue(id in schemaPlane, "plan.schema.json's plane description does not name $id: $schemaPlane")
            assertTrue(id in promptPlane, "PLAN_PROMPT's plane line does not name $id: $promptPlane")
        }
        assertTrue("omitted" in schemaPlane, "the schema does not say what an omitted plane means on a carry")
        assertTrue("omitted" in promptPlane, "PLAN_PROMPT does not say what an omitted plane means on a carry")
    }

    private fun resource(path: String): String =
        checkNotNull(javaClass.getResourceAsStream(path)) { "$path is not on the test classpath" }
            .readBytes().decodeToString()
}
